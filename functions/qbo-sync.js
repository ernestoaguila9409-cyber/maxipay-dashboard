const admin = require("firebase-admin");
const { FieldValue } = require("firebase-admin/firestore");
const logger = require("firebase-functions/logger");
const { merchantCol } = require("./merchant-firestore");
const qboApi = require("./qbo-api");

function toQboDate(value) {
  if (!value) return new Date().toISOString().slice(0, 10);
  const d = typeof value.toDate === "function" ? value.toDate() : new Date(value);
  if (Number.isNaN(d.getTime())) return new Date().toISOString().slice(0, 10);
  return d.toISOString().slice(0, 10);
}

function formatModifiers(modifiers) {
  if (!Array.isArray(modifiers) || modifiers.length === 0) return "";
  const parts = modifiers
    .map((m) => {
      if (!m || typeof m !== "object") return null;
      const name = m.name || m.optionName || m.label;
      return name ? String(name) : null;
    })
    .filter(Boolean);
  return parts.length ? ` (${parts.join(", ")})` : "";
}

function lineAmountCents(item) {
  const withTax = item.lineTotalWithTaxInCents;
  if (withTax != null && Number(withTax) > 0) return Number(withTax);
  const subtotal = Number(item.lineTotalInCents) || 0;
  const tax = Number(item.lineTaxInCents) || 0;
  return subtotal + tax;
}

function buildSalesReceiptLines(items, itemRef) {
  const lines = [];
  let lineNum = 1;

  for (const item of items) {
    const qty = Math.max(1, Number(item.quantity) || 1);
    const amountCents = lineAmountCents(item);
    if (amountCents <= 0) continue;

    const amount = qboApi.centsToDollars(amountCents);
    const unitPrice = Math.round((amount / qty) * 100) / 100;
    const name = String(item.name || item.itemName || "Item").trim();
    const description = `${name}${formatModifiers(item.modifiers)}`.substring(0, 4000);

    lines.push({
      LineNum: lineNum,
      Description: description,
      Amount: amount,
      DetailType: "SalesItemLineDetail",
      SalesItemLineDetail: {
        ItemRef: itemRef,
        UnitPrice: unitPrice,
        Qty: qty,
      },
    });
    lineNum += 1;
  }

  return lines;
}

async function loadOrderItems(merchantId, orderId) {
  const snap = await merchantCol(merchantId, "Orders")
    .doc(orderId)
    .collection("items")
    .get();
  return snap.docs.map((doc) => ({ id: doc.id, ...doc.data() }));
}

function buildSalesReceiptPayload(order, items, itemRef) {
  const lines = buildSalesReceiptLines(items, itemRef);

  const tipCents = Number(order.tipAmountInCents) || 0;
  if (tipCents > 0) {
    lines.push({
      LineNum: lines.length + 1,
      Description: "Tip",
      Amount: qboApi.centsToDollars(tipCents),
      DetailType: "SalesItemLineDetail",
      SalesItemLineDetail: {
        ItemRef: itemRef,
        UnitPrice: qboApi.centsToDollars(tipCents),
        Qty: 1,
      },
    });
  }

  if (lines.length === 0) {
    const totalCents =
      Number(order.totalPaidInCents) ||
      Number(order.totalInCents) ||
      0;
    if (totalCents <= 0) {
      throw new Error("Order has no line items and no total to sync");
    }
    lines.push({
      LineNum: 1,
      Description: `Order ${order.orderNumber || "sale"}`,
      Amount: qboApi.centsToDollars(totalCents),
      DetailType: "SalesItemLineDetail",
      SalesItemLineDetail: {
        ItemRef: itemRef,
        UnitPrice: qboApi.centsToDollars(totalCents),
        Qty: 1,
      },
    });
  }

  const orderLabel = order.orderNumber != null ? String(order.orderNumber) : "";
  const docNumber = orderLabel
    ? `MP-${orderLabel}`.substring(0, 21)
    : undefined;

  const payload = {
    TxnDate: toQboDate(order.paidAt || order.updatedAt || order.createdAt),
    Line: lines,
    PrivateNote: [
      "MaxiPay POS",
      orderLabel ? `Order #${orderLabel}` : null,
      order.orderType ? `Type: ${order.orderType}` : null,
      order.employeeName ? `Server: ${order.employeeName}` : null,
    ]
      .filter(Boolean)
      .join(" | ")
      .substring(0, 4000),
  };

  if (docNumber) payload.DocNumber = docNumber;

  return payload;
}

/**
 * Creates a QuickBooks SalesReceipt for a closed order. Idempotent if qboSalesReceiptId exists.
 */
async function syncOrderToQuickBooks(merchantId, orderId, orderData) {
  if (orderData.qboSalesReceiptId) {
    return {
      skipped: true,
      salesReceiptId: orderData.qboSalesReceiptId,
    };
  }

  if (orderData.status !== "CLOSED") {
    throw new Error(`Order status is ${orderData.status}, expected CLOSED`);
  }

  const remaining = Number(orderData.remainingInCents);
  if (Number.isFinite(remaining) && remaining > 0) {
    throw new Error("Order is not fully paid");
  }

  const orderRef = merchantCol(merchantId, "Orders").doc(orderId);
  const items = await loadOrderItems(merchantId, orderId);
  const itemRef = await qboApi.getDefaultSalesItemRef();
  const salesReceiptPayload = buildSalesReceiptPayload(orderData, items, itemRef);

  logger.info("[qbo-sync] Creating SalesReceipt", {
    merchantId,
    orderId,
    lineCount: salesReceiptPayload.Line.length,
    docNumber: salesReceiptPayload.DocNumber,
  });

  const created = await qboApi.createSalesReceipt(salesReceiptPayload);

  await orderRef.set(
    {
      qboSalesReceiptId: created.id,
      qboSalesReceiptDocNumber: created.docNumber,
      qboSyncedAt: FieldValue.serverTimestamp(),
      qboSyncStatus: "success",
      qboSyncError: FieldValue.delete(),
    },
    { merge: true },
  );

  logger.info("[qbo-sync] SalesReceipt created", {
    merchantId,
    orderId,
    salesReceiptId: created.id,
    docNumber: created.docNumber,
  });

  return {
    skipped: false,
    salesReceiptId: created.id,
    docNumber: created.docNumber,
  };
}

async function markSyncError(merchantId, orderId, error) {
  const message =
    error?.error_description ||
    error?.message ||
    String(error || "Unknown error");
  await merchantCol(merchantId, "Orders").doc(orderId).set(
    {
      qboSyncStatus: "error",
      qboSyncError: message.substring(0, 500),
      qboSyncAttemptedAt: FieldValue.serverTimestamp(),
    },
    { merge: true },
  );
}

module.exports = {
  syncOrderToQuickBooks,
  markSyncError,
  buildSalesReceiptPayload,
  loadOrderItems,
};
