require("dotenv").config();

const admin = require("firebase-admin");
admin.initializeApp();

const { onCall } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions");
const sgMail = require("@sendgrid/mail");
const logger = require("firebase-functions/logger");

sgMail.setApiKey(process.env.SENDGRID_API_KEY);

setGlobalOptions({ maxInstances: 10 });

const LOGO_URL = "https://your-server-or-storage/maxipay-logo.png";

const ORDER_TYPE_LABELS = {
  DINE_IN: "DINE IN",
  TO_GO: "TO-GO",
  BAR_TAB: "BAR",
};

// ---------------------------------------------------------------------------
// Shared HTML helpers
// ---------------------------------------------------------------------------

function brandedHeader() {
  return `
    <div style="text-align:center;margin-bottom:20px;">
      <img src="${LOGO_URL}" alt="Volt Merchant Solutions"
           style="max-width:120px;height:auto;margin-bottom:10px;display:inline-block;">
      <div style="font-weight:bold;font-size:18px;color:#222;">Volt Merchant Solutions</div>
      <div style="font-size:13px;color:#555;line-height:1.5;">
        2105 NW 102 AVE ST 3<br>
        Doral FL 33172<br>
        305-407-1100
      </div>
    </div>
    <hr style="border:none;border-top:1px solid #ddd;margin:16px 0;">`;
}

function parseTimestamp(raw) {
  if (!raw) return null;
  if (raw instanceof Date) return raw;
  if (typeof raw.toDate === "function") return raw.toDate();
  if (raw._seconds) return new Date(raw._seconds * 1000);
  return null;
}

function orderMetaSection(order) {
  const orderNumber = order.orderNumber ?? "";
  const orderType = order.orderType ?? "";
  const employeeName = order.employeeName ?? "";
  const typeLabel = ORDER_TYPE_LABELS[orderType] || orderType || "";

  const ts = parseTimestamp(order.createdAt);
  let dateStr = "";
  let timeStr = "";
  if (ts) {
    dateStr = ts.toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
    timeStr = ts.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit", hour12: true });
  }

  const rows = [];
  if (orderNumber) rows.push(row("Order #:", escapeHtml(String(orderNumber))));
  if (typeLabel) rows.push(row("Order Type:", escapeHtml(typeLabel)));
  if (dateStr) rows.push(row("Date:", escapeHtml(dateStr)));
  if (timeStr) rows.push(row("Time:", escapeHtml(timeStr)));
  if (employeeName) rows.push(row("Cashier:", escapeHtml(employeeName)));

  if (rows.length === 0) return "";

  return `
    <table style="width:100%;font-size:14px;color:#333;" cellpadding="0" cellspacing="0">
      ${rows.join("\n")}
    </table>
    <hr style="border:none;border-top:1px solid #ddd;margin:16px 0;">`;
}

function row(label, value) {
  return `<tr>
    <td style="padding:3px 0;font-weight:bold;">${label}</td>
    <td style="padding:3px 0;text-align:right;">${value}</td>
  </tr>`;
}

function itemsTableHtml(items, strikethrough) {
  const strike = strikethrough ? "text-decoration:line-through;color:#999;" : "";

  let rows = "";
  items.forEach((item) => {
    const lineTotal = (item.lineTotalInCents / 100).toFixed(2);
    rows += `<tr>
      <td style="padding:6px 0;${strike}">${escapeHtml(item.name)}</td>
      <td style="padding:6px 0;text-align:center;${strike}">${item.quantity}</td>
      <td style="padding:6px 0;text-align:right;${strike}">$${lineTotal}</td>
    </tr>`;

    if (item.modifiers && item.modifiers.length > 0) {
      item.modifiers.forEach((mod) => {
        if (mod.action === "REMOVE") {
          rows += `<tr>
            <td colspan="3" style="padding:2px 0 2px 20px;color:#D32F2F;font-size:13px;font-weight:bold;${strike}">No ${escapeHtml(mod.name)}</td>
          </tr>`;
        } else {
          const modPrice = (mod.priceInCents / 100).toFixed(2);
          rows += `<tr>
            <td colspan="2" style="padding:2px 0 2px 20px;color:#777;font-size:13px;${strike}">+ ${escapeHtml(mod.name)}</td>
            <td style="padding:2px 0;text-align:right;color:#777;font-size:13px;${strike}">$${modPrice}</td>
          </tr>`;
        }
      });
    }
  });

  return `
    <table style="width:100%;border-collapse:collapse;font-size:14px;" cellpadding="0" cellspacing="0">
      <thead>
        <tr style="border-bottom:2px solid #ddd;">
          <th style="padding:8px 0;text-align:left;font-weight:bold;color:#333;">Item</th>
          <th style="padding:8px 0;text-align:center;font-weight:bold;color:#333;">Qty</th>
          <th style="padding:8px 0;text-align:right;font-weight:bold;color:#333;">Price</th>
        </tr>
      </thead>
      <tbody>${rows}</tbody>
    </table>
    <hr style="border:none;border-top:1px solid #ddd;margin:16px 0;">`;
}

function totalsHtml({ subtotal, tax, tip, total, totalStrike, refundAmount, isFullRefund }) {
  const totalColor = totalStrike ? "color:#999;text-decoration:line-through;" : "color:#222;";
  const totalSize = totalStrike ? "font-size:16px;" : "font-size:20px;";

  let body = `
    <table style="width:100%;font-size:14px;color:#333;" cellpadding="0" cellspacing="0">
      <tr><td style="padding:4px 0;">Subtotal</td><td style="padding:4px 0;text-align:right;">$${subtotal.toFixed(2)}</td></tr>
      <tr><td style="padding:4px 0;">Tax</td><td style="padding:4px 0;text-align:right;">$${tax.toFixed(2)}</td></tr>`;

  if (tip > 0) {
    body += `<tr><td style="padding:4px 0;">Tip</td><td style="padding:4px 0;text-align:right;">$${tip.toFixed(2)}</td></tr>`;
  }

  body += `
    </table>
    <hr style="border:none;border-top:1px solid #ddd;margin:12px 0;">
    <table style="width:100%;" cellpadding="0" cellspacing="0">
      <tr>
        <td style="padding:8px 0;font-weight:bold;${totalSize}${totalColor}">TOTAL</td>
        <td style="padding:8px 0;text-align:right;font-weight:bold;${totalSize}${totalColor}">$${total.toFixed(2)}</td>
      </tr>`;

  if (refundAmount != null) {
    body += `<tr>
      <td style="padding:8px 0;font-size:18px;font-weight:bold;color:#D32F2F;">Refund Amount</td>
      <td style="padding:8px 0;text-align:right;font-size:18px;font-weight:bold;color:#D32F2F;">-$${refundAmount.toFixed(2)}</td>
    </tr>`;
  }

  body += "</table>";
  return body;
}

function paymentHtml(payments) {
  if (!payments || payments.length === 0) return "";

  let html = '<hr style="border:none;border-top:1px solid #ddd;margin:16px 0;">';

  payments.forEach((p) => {
    const type = (p.paymentType || "").toUpperCase();
    if (type === "CASH") {
      html += `<p style="margin:4px 0;font-size:14px;color:#333;">Paid with Cash</p>`;
    } else {
      const brand = p.cardBrand || "Card";
      const last4 = p.last4 || "";
      const display = last4 ? `${escapeHtml(brand)} &bull;&bull;&bull;&bull; ${escapeHtml(last4)}` : escapeHtml(brand);
      html += `<p style="margin:4px 0;font-size:14px;color:#333;">Paid with ${display}</p>`;
    }
  });

  return html;
}

function footerHtml(extraMessage) {
  return `
    <hr style="border:none;border-top:1px solid #ddd;margin:20px 0 16px 0;">
    ${extraMessage ? `<p style="text-align:center;font-weight:bold;margin:0 0 12px 0;">${extraMessage}</p>` : ""}
    <p style="text-align:center;color:#555;font-size:14px;margin:0 0 4px 0;">Thank you for your purchase!</p>
    <p style="text-align:center;color:#555;font-size:14px;margin:0;font-weight:bold;">Powered by MaxiPay</p>`;
}

function statusBadge(label, bgColor) {
  return `
    <div style="text-align:center;margin:12px 0;">
      <span style="background:${bgColor};color:#fff;padding:6px 18px;border-radius:4px;font-weight:bold;font-size:18px;display:inline-block;">
        ${escapeHtml(label)}
      </span>
    </div>`;
}

function wrapEmail(bodyContent) {
  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
</head>
<body style="margin:0;padding:0;font-family:Arial,Helvetica,sans-serif;background-color:#f4f4f4;-webkit-font-smoothing:antialiased;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f4f4f4;">
    <tr>
      <td align="center" style="padding:30px 15px;">
        <!--[if mso]><table role="presentation" width="500" cellpadding="0" cellspacing="0"><tr><td><![endif]-->
        <div style="max-width:500px;margin:0 auto;background:#ffffff;padding:30px;border-radius:8px;">
          ${bodyContent}
        </div>
        <!--[if mso]></td></tr></table><![endif]-->
      </td>
    </tr>
  </table>
</body>
</html>`;
}

// ---------------------------------------------------------------------------
// Data helpers
// ---------------------------------------------------------------------------

function parseItems(itemsSnap) {
  const items = [];
  let subtotalInCents = 0;

  itemsSnap.forEach((doc) => {
    const d = doc.data();
    const name = d.name ?? "Item";
    const quantity = d.quantity ?? 1;
    const unitPriceInCents = d.unitPriceInCents ?? 0;
    const lineTotalInCents = d.lineTotalInCents ?? unitPriceInCents * quantity;
    subtotalInCents += lineTotalInCents;

    let modifiers = [];
    const raw = d.modifiers;
    if (Array.isArray(raw) && raw.length > 0) {
      modifiers = raw.map((m) => {
        const modName = (m?.name ?? m?.first ?? m?.[0])?.toString?.() ?? "Modifier";
        const modAction = (m?.action ?? "ADD").toString().toUpperCase();
        const modPrice = modAction === "REMOVE" ? 0 : (m?.price ?? m?.second ?? m?.[1] ?? 0);
        const priceInCents = typeof modPrice === "number" ? Math.round(modPrice * 100) : 0;
        return { name: modName, action: modAction, priceInCents };
      });
    }

    items.push({ name, quantity, unitPriceInCents, lineTotalInCents, modifiers });
  });

  return { items, subtotalInCents };
}

function parseTax(taxBreakdown) {
  let taxInCents = 0;
  (taxBreakdown || []).forEach((entry) => {
    taxInCents += entry.amountInCents ?? 0;
  });
  return taxInCents;
}

async function fetchSalePayments(db, orderId) {
  const snap = await db
    .collection("Transactions")
    .where("orderId", "==", orderId)
    .where("type", "==", "SALE")
    .limit(1)
    .get();

  if (snap.empty) return [];

  const tx = snap.docs[0].data();
  return tx.payments || [];
}

// ---------------------------------------------------------------------------
// 1. Standard Receipt
// ---------------------------------------------------------------------------

exports.sendReceiptEmail = onCall(async (request) => {
  const { email, orderId } = request.data || {};

  if (!email || !orderId) {
    return { success: false, error: "Email and orderId are required." };
  }

  const fromEmail = process.env.SENDGRID_FROM_EMAIL;
  if (!fromEmail) {
    logger.error("SENDGRID_FROM_EMAIL is not configured");
    return { success: false, error: "Email service is not configured." };
  }

  const db = admin.firestore();
  const orderRef = db.collection("Orders").doc(orderId);
  const orderDoc = await orderRef.get();

  if (!orderDoc.exists) {
    return { success: false, error: "Order not found." };
  }

  const order = orderDoc.data();
  const totalInCents = order.totalInCents ?? 0;
  const tipAmountInCents = order.tipAmountInCents ?? 0;
  const taxBreakdown = order.taxBreakdown ?? [];

  const itemsSnap = await orderRef.collection("items").get();
  const { items, subtotalInCents } = parseItems(itemsSnap);
  const taxInCents = parseTax(taxBreakdown);
  const payments = await fetchSalePayments(db, orderId);

  const subtotal = subtotalInCents / 100;
  const tax = taxInCents / 100;
  const tip = tipAmountInCents / 100;
  const total = totalInCents / 100;

  const body =
    brandedHeader() +
    orderMetaSection(order) +
    itemsTableHtml(items, false) +
    totalsHtml({ subtotal, tax, tip, total }) +
    paymentHtml(payments) +
    footerHtml();

  const html = wrapEmail(body);

  try {
    await sgMail.send({
      to: email,
      from: fromEmail,
      subject: `Receipt - Order #${order.orderNumber || orderId}`,
      html,
    });
    logger.info("Receipt sent", { to: email, orderId });
    return { success: true };
  } catch (error) {
    logger.error("SendGrid error:", error.message);
    if (error.response) {
      logger.error("SendGrid response:", JSON.stringify(error.response.body));
    }
    return { success: false, error: error.message };
  }
});

// ---------------------------------------------------------------------------
// 2. Void Receipt
// ---------------------------------------------------------------------------

exports.sendVoidReceiptEmail = onCall(async (request) => {
  const { email, orderId } = request.data || {};

  if (!email || !orderId) {
    return { success: false, error: "Email and orderId are required." };
  }

  const fromEmail = process.env.SENDGRID_FROM_EMAIL;
  if (!fromEmail) {
    logger.error("SENDGRID_FROM_EMAIL is not configured");
    return { success: false, error: "Email service is not configured." };
  }

  const db = admin.firestore();
  const orderDoc = await db.collection("Orders").doc(orderId).get();

  if (!orderDoc.exists) {
    return { success: false, error: "Order not found." };
  }

  const order = orderDoc.data();
  const totalInCents = order.totalInCents ?? 0;
  const tipAmountInCents = order.tipAmountInCents ?? 0;
  const taxBreakdown = order.taxBreakdown ?? [];
  const total = totalInCents / 100;

  const voidedBy = order.voidedBy ?? "";
  const voidedAt = parseTimestamp(order.voidedAt);
  const voidedAtStr = voidedAt ? voidedAt.toLocaleString("en-US") : "";

  const itemsSnap = await db.collection("Orders").doc(orderId).collection("items").get();
  const { items, subtotalInCents } = parseItems(itemsSnap);
  const taxInCents = parseTax(taxBreakdown);

  const subtotal = subtotalInCents / 100;
  const tax = taxInCents / 100;
  const tip = tipAmountInCents / 100;

  let voidMeta = "";
  if (voidedBy || voidedAtStr) {
    voidMeta += '<div style="text-align:center;margin-bottom:12px;font-size:14px;color:#777;">';
    if (voidedBy) voidMeta += `Voided by: ${escapeHtml(voidedBy)}<br>`;
    if (voidedAtStr) voidMeta += escapeHtml(voidedAtStr);
    voidMeta += "</div>";
  }

  const body =
    brandedHeader() +
    statusBadge("VOIDED", "#D32F2F") +
    voidMeta +
    orderMetaSection(order) +
    itemsTableHtml(items, true) +
    totalsHtml({ subtotal, tax, tip, total, totalStrike: true }) +
    footerHtml('<span style="color:#D32F2F;">This transaction has been voided.</span>');

  const html = wrapEmail(body);

  try {
    await sgMail.send({
      to: email,
      from: fromEmail,
      subject: `VOID Receipt - Order #${order.orderNumber || orderId}`,
      html,
    });
    logger.info("Void receipt sent", { to: email, orderId });
    return { success: true };
  } catch (error) {
    logger.error("SendGrid error:", error.message);
    if (error.response) {
      logger.error("SendGrid response:", JSON.stringify(error.response.body));
    }
    return { success: false, error: error.message };
  }
});

// ---------------------------------------------------------------------------
// 3. Refund Receipt
// ---------------------------------------------------------------------------

exports.sendRefundReceiptEmail = onCall(async (request) => {
  try {
    const { email, orderId, transactionId } = request.data || {};

    if (!email || !orderId) {
      return { success: false, error: "Email and orderId are required." };
    }

    const fromEmail = process.env.SENDGRID_FROM_EMAIL;
    if (!fromEmail) {
      logger.error("SENDGRID_FROM_EMAIL is not configured");
      return { success: false, error: "Email service is not configured." };
    }

    const db = admin.firestore();
    const orderDoc = await db.collection("Orders").doc(orderId).get();

    if (!orderDoc.exists) {
      return { success: false, error: "Order not found." };
    }

    const order = orderDoc.data();
    const totalInCents = order.totalInCents ?? 0;
    const totalRefundedInCents = order.totalRefundedInCents ?? 0;
    const tipAmountInCents = order.tipAmountInCents ?? 0;
    const taxBreakdown = order.taxBreakdown ?? [];
    const total = totalInCents / 100;

    const itemsSnap = await db.collection("Orders").doc(orderId).collection("items").get();
    const { items, subtotalInCents } = parseItems(itemsSnap);
    const taxInCents = parseTax(taxBreakdown);

    const subtotal = subtotalInCents / 100;
    const tax = taxInCents / 100;
    const tip = tipAmountInCents / 100;

    let refundAmountCents = 0;
    let refundedBy = "";
    let refundDate = "";

    if (transactionId) {
      const refundSnap = await db
        .collection("Transactions")
        .where("type", "==", "REFUND")
        .where("originalReferenceId", "==", transactionId)
        .get();

      if (!refundSnap.empty) {
        const sorted = refundSnap.docs.sort((a, b) => {
          const aTime = a.data().createdAt?.toMillis?.() ?? a.data().createdAt?._seconds ?? 0;
          const bTime = b.data().createdAt?.toMillis?.() ?? b.data().createdAt?._seconds ?? 0;
          return bTime - aTime;
        });
        const refundDoc = sorted[0].data();
        refundAmountCents = refundDoc.amountInCents ?? Math.round((refundDoc.amount ?? 0) * 100);
        refundedBy = refundDoc.refundedBy ?? "";
        if (refundDoc.createdAt) {
          const ts = refundDoc.createdAt;
          const ms = ts.toMillis ? ts.toMillis() : (ts._seconds ?? ts.seconds ?? 0) * 1000;
          refundDate = new Date(ms).toLocaleString("en-US");
        }
      }
    }

    if (refundAmountCents === 0) {
      refundAmountCents = totalRefundedInCents > 0 ? totalRefundedInCents : totalInCents;
    }

    const refundAmount = refundAmountCents / 100;
    const isFullRefund = refundAmountCents >= totalInCents;

    let refundMeta = "";
    if (refundedBy || refundDate) {
      refundMeta += '<div style="text-align:center;margin-bottom:12px;font-size:14px;color:#777;">';
      if (refundedBy) refundMeta += `Refunded by: ${escapeHtml(refundedBy)}<br>`;
      if (refundDate) refundMeta += escapeHtml(refundDate);
      refundMeta += "</div>";
    }

    const refundMsg = isFullRefund
      ? '<span style="color:#F57C00;">This order has been fully refunded.</span>'
      : '<span style="color:#F57C00;">A partial refund has been applied to this order.</span>';

    const body =
      brandedHeader() +
      statusBadge("REFUND", "#F57C00") +
      refundMeta +
      orderMetaSection(order) +
      itemsTableHtml(items, false) +
      totalsHtml({ subtotal, tax, tip, total, totalStrike: false, refundAmount, isFullRefund }) +
      footerHtml(refundMsg);

    const html = wrapEmail(body);

    try {
      await sgMail.send({
        to: email,
        from: fromEmail,
        subject: `REFUND Receipt - Order #${order.orderNumber || orderId}`,
        html,
      });
      logger.info("Refund receipt sent", { to: email, orderId });
      return { success: true };
    } catch (error) {
      logger.error("SendGrid error:", error.message);
      if (error.response) {
        logger.error("SendGrid response:", JSON.stringify(error.response.body));
      }
      return { success: false, error: error.message };
    }
  } catch (error) {
    logger.error("sendRefundReceiptEmail error:", error.message, error);
    return { success: false, error: error.message || "An unexpected error occurred." };
  }
});

// ---------------------------------------------------------------------------
// 4. PIN Verification (returns a custom auth token)
// ---------------------------------------------------------------------------

exports.verifyPin = onCall(async (request) => {
  const { pin } = request.data || {};

  if (!pin) {
    return { success: false, error: "PIN is required." };
  }

  const db = admin.firestore();
  const snap = await db
    .collection("Employees")
    .where("pin", "==", pin)
    .where("active", "==", true)
    .limit(1)
    .get();

  if (snap.empty) {
    return { success: false, error: "Invalid PIN" };
  }

  const employee = snap.docs[0];
  const name = employee.data().name ?? "";
  const role = employee.data().role ?? "";

  return { success: true, name, role };
});

// ---------------------------------------------------------------------------

function escapeHtml(str) {
  if (typeof str !== "string") return "";
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}
