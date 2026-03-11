require("dotenv").config();

const admin = require("firebase-admin");
admin.initializeApp();

const { onCall } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions");
const sgMail = require("@sendgrid/mail");
const logger = require("firebase-functions/logger");

sgMail.setApiKey(process.env.SENDGRID_API_KEY);

setGlobalOptions({ maxInstances: 10 });

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
  const taxBreakdown = order.taxBreakdown ?? [];

  const itemsSnap = await orderRef.collection("items").get();
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
        const modPrice = m?.price ?? m?.second ?? m?.[1] ?? 0;
        const priceInCents = typeof modPrice === "number" ? Math.round(modPrice * 100) : 0;
        return { name: modName, priceInCents };
      });
    }

    items.push({ name, quantity, unitPriceInCents, lineTotalInCents, modifiers });
  });

  let taxInCents = 0;
  taxBreakdown.forEach((entry) => {
    taxInCents += entry.amountInCents ?? 0;
  });

  const subtotal = subtotalInCents / 100;
  const tax = taxInCents / 100;
  const total = totalInCents / 100;

  let itemsHtml = "";
  items.forEach((item) => {
    const price = (item.unitPriceInCents / 100).toFixed(2);
    const lineTotal = (item.lineTotalInCents / 100).toFixed(2);
    itemsHtml += `<p style="margin:4px 0;">${item.quantity}x ${escapeHtml(item.name)} - $${lineTotal}</p>\n`;

    item.modifiers.forEach((mod) => {
      const modPrice = (mod.priceInCents / 100).toFixed(2);
      itemsHtml += `<p style="margin:2px 0 2px 20px;color:#555;font-size:14px;">+ ${escapeHtml(mod.name)} - $${modPrice}</p>\n`;
    });
  });

  const html = `
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"></head>
<body style="font-family:Arial,sans-serif;background:#f4f4f4;padding:30px;">
  <div style="max-width:500px;margin:auto;background:#fff;padding:30px;border-radius:8px;">
    <h2 style="text-align:center;margin-bottom:4px;">MaxiPay Receipt</h2>
    <p style="text-align:center;color:#555;">Order #${escapeHtml(orderId)}</p>
    <hr/>
    ${itemsHtml}
    <hr/>
    <p style="text-align:right;">Subtotal: $${subtotal.toFixed(2)}</p>
    <p style="text-align:right;">Tax: $${tax.toFixed(2)}</p>
    <h3 style="text-align:right;">Total: $${total.toFixed(2)}</h3>
    <p style="text-align:center;color:#777;margin-top:24px;">Thank you for your purchase.</p>
  </div>
</body>
</html>`.trim();

  try {
    await sgMail.send({
      to: email,
      from: fromEmail,
      subject: `MaxiPay Receipt - Order #${orderId}`,
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
  const total = totalInCents / 100;
  const voidedBy = order.voidedBy ?? "";
  const voidedAt = order.voidedAt
    ? new Date(order.voidedAt._seconds * 1000).toLocaleString("en-US")
    : "";

  const itemsSnap = await db.collection("Orders").doc(orderId).collection("items").get();
  let itemsHtml = "";
  itemsSnap.forEach((doc) => {
    const d = doc.data();
    const name = d.name ?? "Item";
    const quantity = d.quantity ?? 1;
    const lineTotalInCents = d.lineTotalInCents ?? (d.unitPriceInCents ?? 0) * quantity;
    const lineTotal = (lineTotalInCents / 100).toFixed(2);
    itemsHtml += `<p style="margin:4px 0;text-decoration:line-through;color:#999;">${quantity}x ${escapeHtml(name)} - $${lineTotal}</p>\n`;
  });

  const html = `
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"></head>
<body style="font-family:Arial,sans-serif;background:#f4f4f4;padding:30px;">
  <div style="max-width:500px;margin:auto;background:#fff;padding:30px;border-radius:8px;">
    <h2 style="text-align:center;margin-bottom:4px;">MaxiPay Receipt</h2>
    <div style="text-align:center;margin:12px 0;">
      <span style="background:#D32F2F;color:#fff;padding:6px 18px;border-radius:4px;font-weight:bold;font-size:18px;">VOIDED</span>
    </div>
    <p style="text-align:center;color:#555;">Order #${escapeHtml(orderId)}</p>
    ${voidedBy ? `<p style="text-align:center;color:#777;">Voided by: ${escapeHtml(voidedBy)}</p>` : ""}
    ${voidedAt ? `<p style="text-align:center;color:#777;">${escapeHtml(voidedAt)}</p>` : ""}
    <hr/>
    ${itemsHtml}
    <hr/>
    <h3 style="text-align:right;text-decoration:line-through;color:#999;">Total: $${total.toFixed(2)}</h3>
    <p style="text-align:center;color:#D32F2F;font-weight:bold;margin-top:16px;">This transaction has been voided.</p>
    <p style="text-align:center;color:#777;margin-top:24px;">Thank you.</p>
  </div>
</body>
</html>`.trim();

  try {
    await sgMail.send({
      to: email,
      from: fromEmail,
      subject: `VOID Receipt - Order #${orderId}`,
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

exports.sendRefundReceiptEmail = onCall(async (request) => {
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
  const total = totalInCents / 100;
  const taxBreakdown = order.taxBreakdown ?? [];

  const itemsSnap = await db.collection("Orders").doc(orderId).collection("items").get();
  const items = [];
  let subtotalInCents = 0;

  itemsSnap.forEach((doc) => {
    const d = doc.data();
    const name = d.name ?? "Item";
    const quantity = d.quantity ?? 1;
    const unitPriceInCents = d.unitPriceInCents ?? 0;
    const lineTotalInCents = d.lineTotalInCents ?? unitPriceInCents * quantity;
    subtotalInCents += lineTotalInCents;
    items.push({ name, quantity, lineTotalInCents });
  });

  let taxInCents = 0;
  taxBreakdown.forEach((entry) => {
    taxInCents += entry.amountInCents ?? 0;
  });

  const subtotal = subtotalInCents / 100;
  const tax = taxInCents / 100;

  let refundAmountCents = 0;
  let refundedBy = "";
  let refundDate = "";

  if (transactionId) {
    const refundSnap = await db.collection("Transactions")
      .where("type", "==", "REFUND")
      .where("originalReferenceId", "==", transactionId)
      .orderBy("createdAt", "desc")
      .limit(1)
      .get();

    if (!refundSnap.empty) {
      const refundDoc = refundSnap.docs[0].data();
      refundAmountCents = refundDoc.amountInCents ?? Math.round((refundDoc.amount ?? 0) * 100);
      refundedBy = refundDoc.refundedBy ?? "";
      if (refundDoc.createdAt) {
        refundDate = new Date(refundDoc.createdAt._seconds * 1000).toLocaleString("en-US");
      }
    }
  }

  if (refundAmountCents === 0) {
    refundAmountCents = totalRefundedInCents > 0 ? totalRefundedInCents : totalInCents;
  }

  const refundAmount = refundAmountCents / 100;
  const isFullRefund = refundAmountCents >= totalInCents;

  let itemsHtml = "";
  items.forEach((item) => {
    const lineTotal = (item.lineTotalInCents / 100).toFixed(2);
    itemsHtml += `<p style="margin:4px 0;">${item.quantity}x ${escapeHtml(item.name)} - $${lineTotal}</p>\n`;
  });

  const html = `
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"></head>
<body style="font-family:Arial,sans-serif;background:#f4f4f4;padding:30px;">
  <div style="max-width:500px;margin:auto;background:#fff;padding:30px;border-radius:8px;">
    <h2 style="text-align:center;margin-bottom:4px;">MaxiPay Receipt</h2>
    <div style="text-align:center;margin:12px 0;">
      <span style="background:#F57C00;color:#fff;padding:6px 18px;border-radius:4px;font-weight:bold;font-size:18px;">REFUND</span>
    </div>
    <p style="text-align:center;color:#555;">Order #${escapeHtml(orderId)}</p>
    ${refundedBy ? `<p style="text-align:center;color:#777;">Refunded by: ${escapeHtml(refundedBy)}</p>` : ""}
    ${refundDate ? `<p style="text-align:center;color:#777;">${escapeHtml(refundDate)}</p>` : ""}
    <hr/>
    ${itemsHtml}
    <hr/>
    <p style="text-align:right;">Subtotal: $${subtotal.toFixed(2)}</p>
    <p style="text-align:right;">Tax: $${tax.toFixed(2)}</p>
    <p style="text-align:right;">Original Total: $${total.toFixed(2)}</p>
    <hr/>
    <h3 style="text-align:right;color:#D32F2F;">Refund Amount: -$${refundAmount.toFixed(2)}</h3>
    ${isFullRefund
      ? `<p style="text-align:center;color:#F57C00;font-weight:bold;margin-top:16px;">This order has been fully refunded.</p>`
      : `<p style="text-align:center;color:#F57C00;font-weight:bold;margin-top:16px;">A partial refund has been applied to this order.</p>`}
    <p style="text-align:center;color:#777;margin-top:24px;">Thank you.</p>
  </div>
</body>
</html>`.trim();

  try {
    await sgMail.send({
      to: email,
      from: fromEmail,
      subject: `REFUND Receipt - Order #${orderId}`,
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
});

function escapeHtml(str) {
  if (typeof str !== "string") return "";
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}
