/**
 * Import function triggers from their respective submodules:
 *
 * const {onCall} = require("firebase-functions/v2/https");
 * const {onDocumentWritten} = require("firebase-functions/v2/firestore");
 *
 * See a full list of supported triggers at https://firebase.google.com/docs/functions
 */

const admin = require("firebase-admin");
admin.initializeApp();

const { setGlobalOptions } = require("firebase-functions");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const sgMail = require("@sendgrid/mail");
const logger = require("firebase-functions/logger");

const apiKey = process.env.SENDGRID_API_KEY;
logger.info("SendGrid API key present:", !!apiKey, "length:", apiKey ? apiKey.length : 0);
sgMail.setApiKey(apiKey);

// For cost control, you can set the maximum number of containers that can be
// running at the same time. This helps mitigate the impact of unexpected
// traffic spikes by instead downgrading performance. This limit is a
// per-function limit. You can override the limit for each function using the
// `maxInstances` option in the function's options, e.g.
// `onRequest({ maxInstances: 5 }, (req, res) => { ... })`.
// NOTE: setGlobalOptions does not apply to functions using the v1 API. V1
// functions should each use functions.runWith({ maxInstances: 10 }) instead.
// In the v1 API, each function can only serve one request per container, so
// this will be the maximum concurrent request count.
setGlobalOptions({ maxInstances: 10 });

exports.sendReceiptEmail = onCall(async (request) => {
  const { email, orderId } = request.data || {};

  if (!email || !orderId) {
    throw new HttpsError("invalid-argument", "Email and orderId are required.");
  }

  const fromEmail = process.env.SENDGRID_FROM_EMAIL;
  if (!fromEmail) {
    logger.error("SENDGRID_FROM_EMAIL is not configured");
    throw new HttpsError("internal", "Email service is not configured.");
  }

  const db = admin.firestore();
  const orderRef = db.collection("Orders").doc(orderId);
  const orderDoc = await orderRef.get();

  if (!orderDoc.exists) {
    throw new HttpsError("not-found", "Order not found.");
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
    items.push({
      name,
      quantity,
      price: unitPriceInCents / 100,
      lineTotal: lineTotalInCents / 100,
    });
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
    itemsHtml += `
    <tr>
      <td>${escapeHtml(item.name)}</td>
      <td style="text-align:center">${item.quantity}</td>
      <td style="text-align:right">$${item.price.toFixed(2)}</td>
      <td style="text-align:right">$${item.lineTotal.toFixed(2)}</td>
    </tr>
  `;
  });

  const html = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>Receipt - Order ${escapeHtml(orderId)}</title>
</head>
<body>
<div style="font-family: Arial; background:#f4f4f4; padding:30px;">
  <div style="max-width:600px;margin:auto;background:white;padding:30px;border-radius:8px;">

    <h2 style="text-align:center;">MaxiPay POS</h2>
    <p style="text-align:center;">Thank you for your visit</p>

    <hr/>

    <p><strong>Order ID:</strong> ${escapeHtml(orderId)}</p>
    <p><strong>Date:</strong> ${new Date().toLocaleString()}</p>

    <table width="100%" style="border-collapse:collapse;margin-top:20px;">
      <thead>
        <tr style="border-bottom:1px solid #ddd;">
          <th align="left">Item</th>
          <th align="center">Qty</th>
          <th align="right">Price</th>
          <th align="right">Total</th>
        </tr>
      </thead>
      <tbody>
        ${itemsHtml}
      </tbody>
    </table>

    <hr/>

    <p style="text-align:right;">Subtotal: $${subtotal.toFixed(2)}</p>
    <p style="text-align:right;">Tax: $${tax.toFixed(2)}</p>
    <h3 style="text-align:right;">Total: $${total.toFixed(2)}</h3>

    <hr/>

    <p style="text-align:center;color:#777;font-size:12px;">
      Powered by MaxiPay POS
    </p>

  </div>
</div>
</body>
</html>
  `.trim();

  try {
    logger.info("Sending receipt email", { to: email, from: fromEmail, orderId });
    await sgMail.send({
      to: email,
      from: fromEmail,
      subject: `Receipt for Order ${orderId}`,
      html,
    });
    logger.info("Receipt email sent successfully to", email);
    return { success: true };
  } catch (error) {
    logger.error("SendGrid error:", error.message, "code:", error.code);
    if (error.response) {
      logger.error("SendGrid response body:", JSON.stringify(error.response.body));
    }
    throw new HttpsError("internal", "Failed to send receipt email.");
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
