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

function escapeHtml(str) {
  if (typeof str !== "string") return "";
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}
