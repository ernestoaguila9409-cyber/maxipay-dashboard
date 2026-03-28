const admin = require("firebase-admin");
admin.initializeApp();

const { onCall } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions");
const sgMail = require("@sendgrid/mail");
const { Vonage } = require("@vonage/server-sdk");
const logger = require("firebase-functions/logger");

sgMail.setApiKey(process.env.SENDGRID_API_KEY);

const vonage = new Vonage({
  apiKey: process.env.VONAGE_API_KEY,
  apiSecret: process.env.VONAGE_API_SECRET,
});

setGlobalOptions({ maxInstances: 10 });

const LOGO_URL = "https://your-server-or-storage/maxipay-logo.png";

const ORDER_TYPE_LABELS = {
  DINE_IN: "DINE IN",
  TO_GO: "TO-GO",
  BAR_TAB: "BAR",
};

const ORDER_TYPE_COLORS = {
  DINE_IN: "#2E7D32",
  TO_GO: "#E65100",
  BAR_TAB: "#00897B",
  BAR: "#00897B",
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
  const typeColor = ORDER_TYPE_COLORS[orderType] || "#757575";

  const ts = parseTimestamp(order.createdAt);
  let dateStr = "";
  let timeStr = "";
  if (ts) {
    dateStr = ts.toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
    timeStr = ts.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit", hour12: true });
  }

  const rows = [];
  if (orderNumber) rows.push(row("Order #:", escapeHtml(String(orderNumber))));
  if (typeLabel) {
    const badge = `<span style="background:${typeColor};color:#fff;padding:3px 10px;border-radius:4px;font-size:12px;font-weight:bold;display:inline-block;">${escapeHtml(typeLabel)}</span>`;
    rows.push(row("Order Type:", badge));
  }
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

function itemsTableHtml(items, strikethrough, appliedDiscounts) {
  const strike = strikethrough ? "text-decoration:line-through;color:#999;" : "";
  const discounts = Array.isArray(appliedDiscounts) ? appliedDiscounts : [];

  let rows = "";
  items.forEach((item) => {
    const lineTotal = (item.lineTotalInCents / 100).toFixed(2);
    const showItemPrice = (item.basePriceInCents ?? item.lineTotalInCents) > 0;
    rows += `<tr>
      <td style="padding:6px 0;${strike}">${escapeHtml(item.name)}</td>
      <td style="padding:6px 0;text-align:center;${strike}">${item.quantity}</td>
      <td style="padding:6px 0;text-align:right;${strike}">${showItemPrice ? `$${lineTotal}` : ""}</td>
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

    const itemDiscounts = discounts.filter((d) => d.lineKey && d.lineKey === item.lineKey);
    itemDiscounts.forEach((d) => {
      const name = d.discountName || "Discount";
      const type = (d.type || "").toLowerCase();
      const val = d.value;
      const cents = d.amountInCents ?? 0;
      let label = name;
      if (val != null && type === "percentage") {
        const pctStr = val % 1 === 0 ? val.toFixed(0) : val.toFixed(1);
        label = `${name} ${pctStr}% off`;
      } else if (val != null && type === "fixed") {
        label = `${name} $${val.toFixed(2)} off`;
      }
      const discDollars = (cents / 100).toFixed(2);
      rows += `<tr>
        <td colspan="2" style="padding:2px 0 2px 20px;color:#2E7D32;font-size:13px;font-weight:bold;font-style:italic;">${escapeHtml(label)}</td>
        <td style="padding:2px 0;text-align:right;color:#2E7D32;font-size:13px;font-weight:bold;">-$${discDollars}</td>
      </tr>`;
    });
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

function totalsHtml({ subtotal, tax, taxBreakdown, tip, total, totalStrike, refundAmount, isFullRefund, discountInCents, appliedDiscounts }) {
  const totalColor = totalStrike ? "color:#999;text-decoration:line-through;" : "color:#222;";
  const totalSize = totalStrike ? "font-size:16px;" : "font-size:20px;";

  let body = `
    <table style="width:100%;font-size:14px;color:#333;" cellpadding="0" cellspacing="0">
      <tr><td style="padding:4px 0;">Subtotal</td><td style="padding:4px 0;text-align:right;">$${subtotal.toFixed(2)}</td></tr>`;

  // Discounts — group ALL by name (item-level + order-level) to match printed receipt
  const allDiscounts = Array.isArray(appliedDiscounts) ? appliedDiscounts : [];
  const totalDiscountCents = discountInCents ?? 0;

  const groupedByName = {};
  allDiscounts.forEach((d) => {
    const name = d.discountName || "Discount";
    if (!groupedByName[name]) {
      groupedByName[name] = { name, type: d.type, value: d.value, totalCents: 0 };
    }
    groupedByName[name].totalCents += (d.amountInCents ?? 0);
  });
  const groupedDiscounts = Object.values(groupedByName).filter((g) => g.totalCents > 0);

  if (groupedDiscounts.length > 0) {
    groupedDiscounts.forEach((gd) => {
      const type = (gd.type || "").toLowerCase();
      const val = gd.value;
      let label = gd.name;
      if (val != null && type === "percentage") {
        const pctStr = val % 1 === 0 ? val.toFixed(0) : val.toFixed(1);
        label = `${gd.name} (${pctStr}%)`;
      }
      const discDollars = (gd.totalCents / 100).toFixed(2);
      body += `<tr>
        <td style="padding:4px 0;color:#2E7D32;font-weight:bold;">${escapeHtml(label)}</td>
        <td style="padding:4px 0;text-align:right;color:#2E7D32;font-weight:bold;">-$${discDollars}</td>
      </tr>`;
    });
  } else if (totalDiscountCents > 0) {
    const discountDollars = totalDiscountCents / 100;
    body += `<tr>
      <td style="padding:4px 0;color:#2E7D32;font-weight:bold;">Discount</td>
      <td style="padding:4px 0;text-align:right;color:#2E7D32;font-weight:bold;">-$${discountDollars.toFixed(2)}</td>
    </tr>`;
  }

  // Tax breakdown
  if (Array.isArray(taxBreakdown) && taxBreakdown.length > 0) {
    taxBreakdown.forEach((entry) => {
      const taxName = entry.name || "Tax";
      const taxType = entry.taxType || "PERCENTAGE";
      const rate = entry.rate;
      const taxCents = entry.amountInCents ?? 0;
      const taxDollars = taxCents / 100;
      let label = taxName;
      if (taxType === "PERCENTAGE" && rate != null) {
        const rateStr = rate % 1 === 0 ? rate.toFixed(0) : rate.toFixed(2);
        label = `${taxName} (${rateStr}%)`;
      }
      body += `<tr><td style="padding:4px 0;">${escapeHtml(label)}</td><td style="padding:4px 0;text-align:right;">$${taxDollars.toFixed(2)}</td></tr>`;
    });
  } else if (tax > 0) {
    body += `<tr><td style="padding:4px 0;">Tax</td><td style="padding:4px 0;text-align:right;">$${tax.toFixed(2)}</td></tr>`;
  }

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

    const basePriceInCents = d.basePriceInCents ?? unitPriceInCents;
    items.push({ lineKey: doc.id, name, quantity, unitPriceInCents, lineTotalInCents, basePriceInCents, modifiers });
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
  const discountInCents = order.discountInCents ?? 0;
  const appliedDiscounts = order.appliedDiscounts ?? [];

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
    itemsTableHtml(items, false, appliedDiscounts) +
    totalsHtml({ subtotal, tax, taxBreakdown, tip, total, discountInCents, appliedDiscounts }) +
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
  const discountInCents = order.discountInCents ?? 0;
  const appliedDiscounts = order.appliedDiscounts ?? [];
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
    itemsTableHtml(items, true, appliedDiscounts) +
    totalsHtml({ subtotal, tax, taxBreakdown, tip, total, totalStrike: true, discountInCents, appliedDiscounts }) +
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
    const taxBreakdown = order.taxBreakdown ?? [];

    const itemsSnap = await db.collection("Orders").doc(orderId).collection("items").get();
    const { items, subtotalInCents } = parseItems(itemsSnap);

    let refundAmountCents = 0;
    let refundedBy = "";
    let allRefundDocs = [];

    if (transactionId) {
      const refundSnap = await db
        .collection("Transactions")
        .where("type", "==", "REFUND")
        .where("originalReferenceId", "==", transactionId)
        .get();

      if (!refundSnap.empty) {
        allRefundDocs = refundSnap.docs;
        const sorted = [...refundSnap.docs].sort((a, b) => {
          const aTime = a.data().createdAt?.toMillis?.() ?? a.data().createdAt?._seconds ?? 0;
          const bTime = b.data().createdAt?.toMillis?.() ?? b.data().createdAt?._seconds ?? 0;
          return bTime - aTime;
        });
        const refundDoc = sorted[0].data();
        refundAmountCents = refundDoc.amountInCents ?? Math.round((refundDoc.amount ?? 0) * 100);
        refundedBy = refundDoc.refundedBy ?? "";
      }
    }

    if (refundAmountCents === 0) {
      refundAmountCents = totalRefundedInCents > 0 ? totalRefundedInCents : totalInCents;
    }

    const refundAmount = refundAmountCents / 100;

    // Build refunded items list matched to order items for base prices
    const itemById = {};
    const itemByName = {};
    items.forEach((item) => {
      if (item.lineKey) itemById[item.lineKey] = item;
      if (!itemByName[item.name]) itemByName[item.name] = [];
      itemByName[item.name].push(item);
    });

    const refundedItems = [];
    for (const rd of allRefundDocs) {
      const rdData = rd.data();
      const rdAmount = rdData.amountInCents ?? Math.round((rdData.amount ?? 0) * 100);
      const lineKey = rdData.refundedLineKey || "";
      const rdItemName = (rdData.refundedItemName || "").trim();

      let matched = null;
      if (lineKey) matched = itemById[lineKey] || null;
      else if (rdItemName) matched = (itemByName[rdItemName] || [])[0] || null;

      if (matched) {
        refundedItems.push({ name: matched.name, quantity: matched.quantity, baseCents: matched.lineTotalInCents, refundCents: rdAmount });
      } else if (rdItemName) {
        refundedItems.push({ name: rdItemName, quantity: 1, baseCents: rdAmount, refundCents: rdAmount });
      }
    }

    if (refundedItems.length === 0) {
      items.forEach((item) => {
        refundedItems.push({ name: item.name, quantity: item.quantity, baseCents: item.lineTotalInCents, refundCents: item.lineTotalInCents });
      });
    }

    let refundSubtotalCents = 0;
    refundedItems.forEach((ri) => { refundSubtotalCents += ri.baseCents; });

    // Payment info from the latest refund doc
    let paymentLabel = "";
    if (allRefundDocs.length > 0) {
      const latestRefund = allRefundDocs.sort((a, b) => {
        const aTime = a.data().createdAt?.toMillis?.() ?? a.data().createdAt?._seconds ?? 0;
        const bTime = b.data().createdAt?.toMillis?.() ?? b.data().createdAt?._seconds ?? 0;
        return bTime - aTime;
      })[0].data();
      const payments = latestRefund.payments ?? [];
      if (payments.length > 0) {
        const p = payments[0];
        if ((p.paymentType || "").toLowerCase() === "cash") {
          paymentLabel = "Cash";
        } else {
          const parts = [];
          if (p.cardBrand) parts.push(p.cardBrand);
          if (p.last4) parts.push(`**** ${p.last4}`);
          paymentLabel = parts.length > 0 ? parts.join(" ") : (p.paymentType || "");
        }
      } else {
        const pt = latestRefund.paymentType || "";
        if (pt.toLowerCase() === "cash") {
          paymentLabel = "Cash";
        } else {
          const parts = [];
          if (latestRefund.cardBrand) parts.push(latestRefund.cardBrand);
          if (latestRefund.last4) parts.push(`**** ${latestRefund.last4}`);
          paymentLabel = parts.length > 0 ? parts.join(" ") : pt;
        }
      }
    }

    // Order type color badge
    const orderType = order.orderType ?? "";
    const typeLabel = ORDER_TYPE_LABELS[orderType] || orderType || "";
    const typeColor = ORDER_TYPE_COLORS[orderType] || "#757575";

    // --- Build email body matching printed receipt layout ---

    // Title
    const titleHtml = `
      <div style="text-align:center;margin:16px 0;">
        <span style="font-size:22px;font-weight:bold;color:#222;">REFUND RECEIPT</span>
      </div>`;

    // Order # and Date (centered, like printed receipt)
    const orderNumber = order.orderNumber ?? "";
    const ts = parseTimestamp(order.createdAt);
    let dateTimeStr = "";
    if (ts) {
      dateTimeStr = ts.toLocaleDateString("en-US", { month: "2-digit", day: "2-digit", year: "numeric" }) +
        " " + ts.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit", hour12: true });
    }
    let orderInfoHtml = '<div style="text-align:center;font-size:14px;color:#333;margin-bottom:4px;">';
    if (orderNumber) orderInfoHtml += `<div style="font-weight:bold;">Order #${escapeHtml(String(orderNumber))}</div>`;
    if (dateTimeStr) orderInfoHtml += `<div>Date: ${escapeHtml(dateTimeStr)}</div>`;
    if (refundedBy) orderInfoHtml += `<div>Refunded by: ${escapeHtml(refundedBy)}</div>`;
    if (typeLabel) {
      orderInfoHtml += `<div style="margin-top:6px;"><span style="background:${typeColor};color:#fff;padding:3px 10px;border-radius:4px;font-size:12px;font-weight:bold;display:inline-block;">${escapeHtml(typeLabel)}</span></div>`;
    }
    orderInfoHtml += "</div>";

    // Refunded Items section
    let riRows = "";
    refundedItems.forEach((ri) => {
      const basePrice = (ri.baseCents / 100).toFixed(2);
      riRows += `<tr>
        <td style="padding:4px 0;font-size:14px;color:#333;">${escapeHtml(ri.name)}</td>
        <td style="padding:4px 0;text-align:right;font-size:14px;color:#333;">$${basePrice}</td>
      </tr>`;
    });
    const refundItemsHtml = `
      <div style="margin-top:16px;">
        <div style="font-weight:bold;font-size:14px;color:#333;margin-bottom:4px;">Refunded Items:</div>
        <hr style="border:none;border-top:1px dashed #999;margin:0 0 8px 0;">
        <table style="width:100%;border-collapse:collapse;" cellpadding="0" cellspacing="0">
          ${riRows}
        </table>
      </div>`;

    // Taxes Refunded section
    let taxRowsHtml = "";
    if (Array.isArray(taxBreakdown) && taxBreakdown.length > 0) {
      taxBreakdown.forEach((entry) => {
        const taxName = entry.name || "Tax";
        const taxType = entry.taxType || "PERCENTAGE";
        const rate = entry.rate;
        let prorated;
        if (taxType === "PERCENTAGE" && rate > 0) {
          prorated = Math.round(refundSubtotalCents * rate / 100);
        } else {
          const orderTaxAmt = entry.amountInCents ?? 0;
          prorated = subtotalInCents > 0 ? Math.round(orderTaxAmt * refundSubtotalCents / subtotalInCents) : orderTaxAmt;
        }
        const taxDollars = (prorated / 100).toFixed(2);
        let label = taxName;
        if (taxType === "PERCENTAGE" && rate != null) {
          const rateStr = rate % 1 === 0 ? rate.toFixed(0) : rate.toFixed(2);
          label = `${taxName} (${rateStr}%)`;
        }
        taxRowsHtml += `<tr>
          <td style="padding:4px 0;font-size:14px;color:#333;">${escapeHtml(label)}</td>
          <td style="padding:4px 0;text-align:right;font-size:14px;color:#333;">$${taxDollars}</td>
        </tr>`;
      });
    }
    const taxesHtml = taxRowsHtml ? `
      <div style="margin-top:16px;">
        <div style="font-weight:bold;font-size:14px;color:#333;margin-bottom:4px;">Taxes Refunded:</div>
        <hr style="border:none;border-top:1px dashed #999;margin:0 0 8px 0;">
        <table style="width:100%;border-collapse:collapse;" cellpadding="0" cellspacing="0">
          ${taxRowsHtml}
        </table>
      </div>` : "";

    // TOTAL REFUND section
    const totalRefundHtml = `
      <div style="margin-top:16px;">
        <hr style="border:none;border-top:3px double #333;margin:0 0 8px 0;">
        <table style="width:100%;border-collapse:collapse;" cellpadding="0" cellspacing="0">
          <tr>
            <td style="padding:6px 0;font-size:18px;font-weight:bold;color:#222;">TOTAL REFUND</td>
            <td style="padding:6px 0;text-align:right;font-size:18px;font-weight:bold;color:#D32F2F;">-$${refundAmount.toFixed(2)}</td>
          </tr>
        </table>
        <hr style="border:none;border-top:3px double #333;margin:8px 0 0 0;">
      </div>`;

    // Payment + Thank you footer
    let footerSection = '<div style="text-align:center;margin-top:20px;font-size:14px;color:#333;">';
    if (paymentLabel) footerSection += `<div style="margin-bottom:8px;">${escapeHtml(paymentLabel)}</div>`;
    footerSection += '<div>Thank you</div></div>';

    const body =
      brandedHeader() +
      titleHtml +
      orderInfoHtml +
      refundItemsHtml +
      taxesHtml +
      totalRefundHtml +
      footerSection;

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
// 5. SMS Receipt via Vonage
// ---------------------------------------------------------------------------

exports.sendReceiptSms = onCall(async (request) => {
  logger.info("sendReceiptSms invoked", {
    dataKeys: Object.keys(request.data || {}),
    hasAuth: !!request.auth,
  });

  const { phone, orderId } = request.data || {};
  logger.info("sendReceiptSms params", { phone: phone ?? "MISSING", orderId: orderId ?? "MISSING" });

  if (!phone || !orderId) {
    logger.warn("sendReceiptSms early exit: missing phone or orderId");
    return { success: false, error: "Phone and orderId are required." };
  }

  const fromNumber = process.env.VONAGE_FROM_NUMBER;
  const hasApiKey = !!process.env.VONAGE_API_KEY;
  const hasApiSecret = !!process.env.VONAGE_API_SECRET;
  logger.info("sendReceiptSms env check", {
    hasFromNumber: !!fromNumber,
    fromNumberLength: (fromNumber || "").length,
    hasApiKey,
    hasApiSecret,
  });

  if (!fromNumber || !hasApiKey) {
    logger.error("Vonage SMS env variables are not configured", {
      hasFromNumber: !!fromNumber,
      hasApiKey,
      hasApiSecret,
    });
    return { success: false, error: "SMS service is not configured." };
  }

  const cleaned = phone.replace(/[\s\-()]/g, "");
  logger.info("sendReceiptSms phone cleaned", { original: phone, cleaned });

  if (!/^\+1\d{10}$/.test(cleaned)) {
    logger.warn("sendReceiptSms invalid phone format", { cleaned });
    return { success: false, error: "Invalid phone number. Must be E.164 format (+1XXXXXXXXXX)." };
  }

  const db = admin.firestore();

  try {
    // Step 1: Firestore read
    logger.info("sendReceiptSms step 1: fetching order from Firestore", { orderId });
    const orderDoc = await db.collection("Orders").doc(orderId).get();

    if (!orderDoc.exists) {
      logger.warn("sendReceiptSms order not found in Firestore", { orderId });
      return { success: false, error: "Order not found." };
    }
    logger.info("sendReceiptSms step 1 complete: order found", { orderId });

    // Step 2: Extract and validate order fields
    const order = orderDoc.data();
    const orderNumber = order.orderNumber ?? orderId;
    const totalInCents = order.totalInCents ?? 0;
    const businessName = order.businessName ?? "MaxiPay";

    logger.info("sendReceiptSms step 2: order data extracted", {
      orderId,
      orderNumber,
      totalInCents,
      totalInCentsType: typeof order.totalInCents,
      businessName,
      businessNameSource: order.businessName ? "firestore" : "default",
      orderFieldKeys: Object.keys(order),
    });

    // Step 3: Format message
    const total = (totalInCents / 100).toFixed(2);
    logger.info("sendReceiptSms step 3: formatting message", { total, totalInCents });

    const message = [
      businessName,
      `Order #${orderNumber}`,
      `Total: $${total}`,
      "",
      "Thank you for your purchase!",
      "",
      "Reply STOP to opt out.",
    ].join("\n");

    logger.info("sendReceiptSms step 3 complete: message formatted", {
      messageLength: message.length,
      messagePreview: message.substring(0, 80),
    });

    // Step 4: Send via Vonage
    logger.info("sendReceiptSms step 4: calling vonage.sms.send", {
      to: cleaned,
      from: fromNumber,
      textLength: message.length,
    });

    let resp;
    try {
      resp = await vonage.sms.send({
        to: cleaned,
        from: fromNumber,
        text: message,
      });
      logger.info("sendReceiptSms step 4 complete: Vonage responded", {
        respType: typeof resp,
        respKeys: resp ? Object.keys(resp) : "null",
        messageCount: resp?.messages?.length ?? 0,
        rawResp: JSON.stringify(resp).substring(0, 500),
      });
    } catch (vonageErr) {
      logger.error("sendReceiptSms step 4 FAILED: Vonage SDK threw", {
        name: vonageErr.name,
        message: vonageErr.message,
        code: vonageErr.code,
        statusCode: vonageErr.statusCode,
        stack: vonageErr.stack,
        body: vonageErr.body ? JSON.stringify(vonageErr.body).substring(0, 500) : undefined,
        response: vonageErr.response ? JSON.stringify(vonageErr.response).substring(0, 500) : undefined,
      });
      return { success: false, error: vonageErr.message || "Vonage API call failed." };
    }

    // Step 5: Check Vonage response status
    const msg = resp.messages?.[0];
    logger.info("sendReceiptSms step 5: checking response status", {
      hasMessages: !!resp.messages,
      firstMsgStatus: msg?.status,
      firstMsgErrorText: msg?.["error-text"],
    });

    if (msg && msg.status !== "0") {
      logger.error("Vonage SMS rejected", {
        status: msg.status,
        errorText: msg["error-text"],
        to: cleaned,
        orderId,
        fullMsg: JSON.stringify(msg).substring(0, 500),
      });
      return { success: false, error: msg["error-text"] || "SMS rejected by carrier." };
    }

    logger.info("SMS receipt sent successfully", {
      to: cleaned,
      orderId,
      messageId: msg?.["message-id"],
    });

    // Step 6: Update order document with SMS status
    logger.info("sendReceiptSms step 6: updating order with SMS status", { orderId });
    await db.collection("Orders").doc(orderId).update({
      smsStatus: "sent",
      smsPhone: cleaned,
      smsSentAt: admin.firestore.FieldValue.serverTimestamp(),
    }).catch((e) => logger.warn("Failed to save SMS status to order", {
      orderId,
      error: e.message,
      stack: e.stack,
    }));

    logger.info("sendReceiptSms completed successfully", { orderId });
    return { success: true, messageId: msg?.["message-id"] };
  } catch (error) {
    logger.error("sendReceiptSms UNHANDLED error", {
      name: error.name,
      message: error.message,
      code: error.code,
      stack: error.stack,
      orderId,
      phone: cleaned,
      errorJson: (() => {
        try { return JSON.stringify(error, Object.getOwnPropertyNames(error)).substring(0, 1000); } catch (_) { return "unserializable"; }
      })(),
    });
    return { success: false, error: error.message || "Failed to send SMS." };
  }
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
