const admin = require("firebase-admin");
admin.initializeApp();

const { onCall } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions");
const sgMail = require("@sendgrid/mail");
const { Vonage } = require("@vonage/server-sdk");
const logger = require("firebase-functions/logger");

/**
 * SendGrid returns error.message "Unauthorized" (HTTP 401) when the API key is missing,
 * revoked, or lacks Mail Send permission. Keys must be set on the deployed function:
 *   firebase functions:secrets:set SENDGRID_API_KEY
 * and (v2) bound in code or set as env in Google Cloud Console → Cloud Functions → Runtime.
 *
 * Password reset / verification emails are sent by Firebase Auth, not this file. Enable
 * Custom SMTP in Firebase Console → Authentication → Email templates; use a branded
 * @maxipaypos.com sender there, not receipt@ (receipts use SENDGRID_FROM_EMAIL). See
 * functions/README.md.
 */
function getSendGridApiKey() {
  const k = process.env.SENDGRID_API_KEY;
  return k && String(k).trim() ? String(k).trim() : null;
}

async function sendGridMail(msg) {
  const key = getSendGridApiKey();
  if (!key) {
    const err = new Error("SENDGRID_API_KEY is not set");
    err.code = "CONFIG";
    throw err;
  }
  sgMail.setApiKey(key);
  return sgMail.send(msg);
}

function sendGridErrorForClient(error) {
  if (error?.code === "CONFIG") {
    return "Email service is not configured. Set SENDGRID_API_KEY for Cloud Functions.";
  }
  const msg = error?.message ? String(error.message) : "Unknown error";
  const code = error?.response?.statusCode ?? error?.code;
  if (msg === "Unauthorized" || code === 401) {
    return "SendGrid rejected the request (401). Create a new API key with Mail Send permission, set SENDGRID_API_KEY as a function secret, and redeploy.";
  }
  return msg;
}

const vonage = new Vonage({
  apiKey: process.env.VONAGE_API_KEY,
  apiSecret: process.env.VONAGE_API_SECRET,
});

setGlobalOptions({ maxInstances: 10 });

const BUSINESS_TIMEZONE = "America/New_York";

/** Public HTTPS URL from Settings/businessInfo.logoUrl (e.g. Firebase Storage). No placeholder — avoids broken images in email clients. */
function resolveBusinessLogoUrl(biz) {
  const raw = biz && biz.logoUrl != null ? String(biz.logoUrl).trim() : "";
  if (!raw) return null;
  const lower = raw.toLowerCase();
  if (lower.includes("your-server-or-storage")) return null;
  if (lower.startsWith("https://") || lower.startsWith("http://")) return raw;
  return null;
}

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
// Business info from Firestore
// ---------------------------------------------------------------------------

async function fetchBusinessInfo(db) {
  const snap = await db.collection("Settings").doc("businessInfo").get();
  if (!snap.exists) return {};
  return snap.data();
}

// ---------------------------------------------------------------------------
// Shared HTML helpers
// ---------------------------------------------------------------------------

function brandedHeader(biz) {
  const name = biz.businessName || "My Restaurant";
  const logoUrl = resolveBusinessLogoUrl(biz);
  const address = biz.address || "";
  const phone = biz.phone || "";
  const email = biz.email || "";

  const addressLines = address
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean)
    .map((l) => escapeHtml(l))
    .join("<br>");

  let contactLine = "";
  if (phone) contactLine += escapeHtml(phone);
  if (phone && email) contactLine += "<br>";
  if (email) contactLine += escapeHtml(email);

  const detailLines = [addressLines, contactLine].filter(Boolean).join("<br>");

  const logoBlock = logoUrl
    ? `<img src="${escapeHtml(logoUrl)}" alt="${escapeHtml(name)}"
           style="max-width:160px;height:auto;margin-bottom:12px;display:block;margin-left:auto;margin-right:auto;border:0;outline:none;text-decoration:none;">`
    : "";

  return `
    <div style="text-align:center;margin-bottom:20px;">
      ${logoBlock}
      <div style="font-weight:bold;font-size:18px;color:#222;">${escapeHtml(name)}</div>
      ${detailLines ? `<div style="font-size:13px;color:#555;line-height:1.5;">${detailLines}</div>` : ""}
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

/**
 * Prefer [order.customerName]; if missing but [order.customerId] is set, load Customers/{id}
 * (same firstName+lastName vs name rules as POS). Used so email receipts show the customer for
 * Bar Tab and other flows that only stored customerId.
 */
async function resolveCustomerDisplayNameForReceipt(db, order) {
  const existing = order?.customerName != null ? String(order.customerName).trim() : "";
  if (existing) return existing;
  const cid = order?.customerId != null ? String(order.customerId).trim() : "";
  if (!cid) return "";
  try {
    const snap = await db.collection("Customers").doc(cid).get();
    if (!snap.exists) return "";
    const d = snap.data() || {};
    const first = String(d.firstName ?? "").trim();
    const last = String(d.lastName ?? "").trim();
    const composed = `${first} ${last}`.trim();
    if (composed) return composed;
    const nm = String(d.name ?? "").trim();
    return nm || "";
  } catch (e) {
    logger.warn("resolveCustomerDisplayNameForReceipt failed", { cid, err: e.message });
    return "";
  }
}

function resolveTransactionTypeLabel(payments) {
  if (!Array.isArray(payments) || payments.length === 0) return "CASH";
  const hasDebit = payments.some(
    (p) => String(p.paymentType ?? "").toLowerCase() === "debit"
  );
  const hasCredit = payments.some(
    (p) => String(p.paymentType ?? "").toLowerCase() === "credit"
  );
  if (hasDebit) return "DEBIT SALE";
  if (hasCredit) return "CREDIT SALE";
  return "CASH";
}

function orderMetaSection(order, payments) {
  const orderNumber = order.orderNumber ?? "";
  const orderType = order.orderType ?? "";
  const employeeName = order.employeeName ?? "";
  const customerName = order.customerName != null ? String(order.customerName).trim() : "";
  const typeLabel = ORDER_TYPE_LABELS[orderType] || orderType || "";
  const typeColor = ORDER_TYPE_COLORS[orderType] || "#757575";

  const ts = parseTimestamp(order.createdAt);
  let dateStr = "";
  let timeStr = "";
  if (ts) {
    dateStr = ts.toLocaleDateString("en-US", { timeZone: BUSINESS_TIMEZONE, month: "short", day: "numeric", year: "numeric" });
    timeStr = ts.toLocaleTimeString("en-US", { timeZone: BUSINESS_TIMEZONE, hour: "numeric", minute: "2-digit", hour12: true });
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
  if (customerName) rows.push(row("Customer:", escapeHtml(customerName)));

  if (Array.isArray(payments) && payments.length > 0) {
    const txLabel = resolveTransactionTypeLabel(payments);
    const txColor = txLabel === "CASH" ? "#4CAF50" : txLabel === "DEBIT SALE" ? "#1976D2" : "#7B1FA2";
    const txBadge = `<span style="background:${txColor};color:#fff;padding:3px 10px;border-radius:4px;font-size:12px;font-weight:bold;display:inline-block;">${escapeHtml(txLabel)}</span>`;
    rows.push(row("Transaction:", txBadge));
  }

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

/** Avoid "No NO LETTUCE" when the stored label is already NO-prefixed (same rule as POS cart lines). */
function removeModifierCartLine(name) {
  const t = String(name ?? "").trim();
  if (!t) return t;
  const u = t.toUpperCase();
  if (u.startsWith("NO ") || u === "NO") return t;
  return `No ${t}`;
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
            <td colspan="3" style="padding:2px 0 2px 20px;color:#D32F2F;font-size:13px;font-weight:bold;${strike}">${escapeHtml(removeModifierCartLine(mod.name))}</td>
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

function totalsHtml({
  subtotal,
  tax,
  taxBreakdown,
  tip,
  total,
  totalStrike,
  refundAmount,
  isFullRefund,
  discountInCents,
  appliedDiscounts,
  tipConfig,
  tipAmountInCents,
  /** Email receipts omit tip rows to match requested layout (no tip line). */
  omitTipLine,
}) {
  const totalColor = totalStrike ? "color:#999;text-decoration:line-through;" : "color:#222;";
  const totalSize = totalStrike ? "font-size:16px;" : "font-size:20px;";
  const tipCents = tipAmountInCents != null ? Math.round(tipAmountInCents) : Math.round((tip ?? 0) * 100);

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

  if (!omitTipLine) {
    if (shouldIncludeTipLineOnPrintedReceipt(tipConfig, tipCents)) {
      body += `<tr><td style="padding:4px 0;">Tip</td><td style="padding:4px 0;text-align:right;">$${(tipCents / 100).toFixed(2)}</td></tr>`;
    } else if ((tip ?? 0) > 0) {
      body += `<tr><td style="padding:4px 0;">Tip</td><td style="padding:4px 0;text-align:right;">$${tip.toFixed(2)}</td></tr>`;
    }
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

  const count = Array.isArray(payments) ? payments.length : 0;
  let html =
    '<hr style="border:none;border-top:1px solid #ddd;margin:20px 0 12px 0;">' +
    (count > 1
      ? `<div style="font-size:14px;font-weight:800;color:#222;margin-bottom:8px;text-align:center;letter-spacing:0.6px;">MIX PAYMENTS (${count} methods)</div>`
      : "") +
    '<div style="font-size:13px;font-weight:bold;color:#444;margin-bottom:12px;text-align:center;">Payment information</div>';

  payments.forEach((p) => {
    const type = (p.paymentType || "").toUpperCase();
    const block =
      "margin:0 auto 14px auto;max-width:340px;padding:14px 18px;background:#fafafa;border:1px solid #e8e8e8;border-radius:6px;" +
      "text-align:left;font-size:14px;color:#222;line-height:1.65;";
    if (type === "CASH") {
      html += `<div style="${block}">`;
      html += `<div style="font-weight:600;">Paid with Cash</div>`;
      html += `</div>`;
    } else {
      const brand = (p.cardBrand || "").trim();
      const last4 = (p.last4 || "").trim();
      const cardLine = last4
        ? `${escapeHtml(brand || "Card")} **** ${escapeHtml(last4)}`
        : escapeHtml(brand || "Card");
      html += `<div style="${block}">`;
      html += `<div style="font-weight:600;margin-bottom:8px;">${cardLine}</div>`;
      const auth = p.authCode != null ? String(p.authCode).trim() : "";
      if (auth) {
        html += `<div><span style="color:#666;">Auth:</span> ${escapeHtml(auth)}</div>`;
      }
      if (p.paymentType) {
        html += `<div><span style="color:#666;">Type:</span> ${escapeHtml(String(p.paymentType))}</div>`;
      }
      const entryLabel = receiptLabelForCardEntryType(p.entryType);
      if (entryLabel) {
        html += `<div><span style="color:#666;">Payment method:</span> ${escapeHtml(entryLabel)}</div>`;
      }
      html += `</div>`;
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
// Split receipt email (matches app SplitReceiptPayload / SplitReceiptRenderer)
// ---------------------------------------------------------------------------

function centsToDisplayFromSplit(c) {
  const n = Number(c);
  if (Number.isNaN(n)) return "0.00";
  return (n / 100).toFixed(2);
}

function isEvenSplitShareLineEmail(line) {
  const orig = line.originalLineTotalInCents;
  return orig != null && Number(orig) > 0 &&
    line.splitIndex != null && line.totalSplits != null;
}

/** Matches app SplitReceiptLine.modifierLines (bullet text per line). */
function splitReceiptModifierRowsHtml(line) {
  const raw = line && line.modifierLines;
  if (!Array.isArray(raw) || raw.length === 0) return "";
  let html = "";
  raw.forEach((m) => {
    const s = m != null ? String(m).trim() : "";
    if (!s) return;
    html += `<tr><td colspan="2" style="padding:2px 0 2px 12px;font-size:13px;color:#555;line-height:1.35;">${escapeHtml(s)}</td></tr>`;
  });
  return html;
}

function validateSplitReceiptPayload(sr) {
  if (!sr || typeof sr !== "object") return false;
  if (sr.splitIndex == null || sr.totalSplits == null) return false;
  if (Number(sr.totalSplits) < 2) return false;
  const gl = sr.guestLabel != null ? String(sr.guestLabel).trim() : "";
  if (!gl) return false;
  return true;
}

function splitReceiptItemsAndTotalsHtml(sr, tipConfig) {
  const note = (sr.sharedItemsNote != null ? String(sr.sharedItemsNote) : "").trim();
  let itemRows = "";
  if (note) {
    itemRows = `<tr><td colspan="2" style="padding:8px 0;font-size:14px;color:#333;">${escapeHtml(note)}</td></tr>`;
  } else {
    const items = Array.isArray(sr.items) ? sr.items : [];
    items.forEach((line) => {
      const share = Number(line.lineTotalInCents) || 0;
      if (isEvenSplitShareLineEmail(line)) {
        const label = (line.originalItemName || line.name || "Item").toString();
        const si = line.splitIndex;
        const ts = line.totalSplits;
        itemRows += `<tr><td style="padding:6px 0;">${escapeHtml(`${label} (${si}/${ts} share)`)}</td>` +
          `<td style="padding:6px 0;text-align:right;">$${centsToDisplayFromSplit(share)}</td></tr>`;
        itemRows += splitReceiptModifierRowsHtml(line);
        const o = Number(line.originalLineTotalInCents) || 0;
        itemRows += `<tr><td colspan="2" style="padding:2px 0 8px 12px;font-size:13px;color:#666;">` +
          `Line total $${centsToDisplayFromSplit(o)}</td></tr>`;
      } else {
        const name = (line.name || "Item").toString();
        const qty = Number(line.quantity) || 1;
        const lbl = qty > 1 ? `${qty}x ${name}` : name;
        itemRows += `<tr><td style="padding:6px 0;">${escapeHtml(lbl)}</td>` +
          `<td style="padding:6px 0;text-align:right;">$${centsToDisplayFromSplit(share)}</td></tr>`;
        itemRows += splitReceiptModifierRowsHtml(line);
      }
    });
  }

  const subtotal = Number(sr.subtotalInCents) || 0;
  const discount = Number(sr.discountInCents) || 0;
  const tax = Number(sr.taxInCents) || 0;
  const tip = Number(sr.tipInCents) || 0;
  const total = Number(sr.totalInCents) || 0;
  const taxLines = Array.isArray(sr.taxLines) ? sr.taxLines : [];
  const method = (sr.paymentMethod != null ? String(sr.paymentMethod) : "");

  let tot = `
    <table style="width:100%;font-size:14px;color:#333;" cellpadding="0" cellspacing="0">
      <tr><td style="padding:4px 0;">Subtotal</td><td style="padding:4px 0;text-align:right;">$${centsToDisplayFromSplit(subtotal)}</td></tr>`;
  if (discount > 0) {
    tot += `<tr><td style="padding:4px 0;">Discount</td><td style="padding:4px 0;text-align:right;">-$${centsToDisplayFromSplit(discount)}</td></tr>`;
  }
  if (taxLines.length > 0) {
    taxLines.forEach((tl) => {
      const lab = (tl && tl.label != null) ? String(tl.label) : "Tax";
      const amt = tl && tl.amountInCents != null ? Number(tl.amountInCents) : 0;
      if (amt > 0) {
        tot += `<tr><td style="padding:4px 0;">${escapeHtml(lab)}</td><td style="padding:4px 0;text-align:right;">$${centsToDisplayFromSplit(amt)}</td></tr>`;
      }
    });
  } else if (tax > 0) {
    tot += `<tr><td style="padding:4px 0;">Tax</td><td style="padding:4px 0;text-align:right;">$${centsToDisplayFromSplit(tax)}</td></tr>`;
  }
  if (shouldIncludeTipLineOnPrintedReceipt(tipConfig, tip) && tip > 0) {
    tot += `<tr><td style="padding:4px 0;">Tip</td><td style="padding:4px 0;text-align:right;">$${centsToDisplayFromSplit(tip)}</td></tr>`;
  }
  tot += `</table>
    <hr style="border:none;border-top:1px solid #ddd;margin:12px 0;">
    <table style="width:100%;" cellpadding="0" cellspacing="0">
      <tr>
        <td style="padding:8px 0;font-weight:bold;font-size:20px;color:#222;">TOTAL</td>
        <td style="padding:8px 0;text-align:right;font-weight:bold;font-size:20px;color:#222;">$${centsToDisplayFromSplit(total)}</td>
      </tr>
    </table>
    <div style="text-align:center;margin-top:16px;font-size:14px;color:#444;">
      <strong>Payment:</strong> ${escapeHtml(method)}
    </div>`;

  const guestHead = (sr.guestLabel != null ? String(sr.guestLabel) : "").trim();
  return `
    <div style="text-align:center;margin:8px 0 12px 0;font-size:15px;font-weight:bold;color:#5E4085;">${escapeHtml(guestHead)}</div>
    <hr style="border:none;border-top:1px solid #ddd;margin:12px 0;">
    <table style="width:100%;font-size:14px;color:#333;" cellpadding="0" cellspacing="0"><tbody>${itemRows}</tbody></table>
    <hr style="border:none;border-top:1px solid #ddd;margin:16px 0;">
    ${tot}`;
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

/** Full sale/capture transaction doc for status, payments, void flag (matches thermal receipt). */
async function fetchSaleTransactionForOrder(db, orderId, orderData) {
  const o = orderData || {};
  const sid = o.saleTransactionId;
  if (sid) {
    const doc = await db.collection("Transactions").doc(String(sid)).get();
    if (doc.exists) return doc;
  }
  let snap = await db
    .collection("Transactions")
    .where("orderId", "==", orderId)
    .where("type", "==", "SALE")
    .limit(1)
    .get();
  if (!snap.empty) return snap.docs[0];
  snap = await db
    .collection("Transactions")
    .where("orderId", "==", orderId)
    .where("type", "==", "CAPTURE")
    .limit(1)
    .get();
  return snap.empty ? null : snap.docs[0];
}

/** Synced from app (Settings/tipConfig); mirrors Android TipConfig defaults when missing. */
async function fetchTipConfig(db) {
  const snap = await db.collection("Settings").doc("tipConfig").get();
  if (!snap.exists) {
    return {
      tipsEnabled: true,
      customTipEnabled: true,
      calculationBase: "TOTAL",
      tipPresentation: "CUSTOMER_SCREEN",
      presets: [15, 18, 20],
    };
  }
  const d = snap.data() || {};
  return {
    tipsEnabled: d.tipsEnabled !== false,
    customTipEnabled: d.customTipEnabled !== false,
    calculationBase: d.calculationBase === "SUBTOTAL" ? "SUBTOTAL" : "TOTAL",
    tipPresentation: d.tipPresentation === "RECEIPT" ? "RECEIPT" : "CUSTOMER_SCREEN",
    presets: Array.isArray(d.presets)
      ? d.presets.map((n) => Number(n)).filter((n) => n > 0 && n <= 100)
      : [],
  };
}

function receiptLabelForCardEntryType(raw) {
  const s = raw != null ? String(raw).trim() : "";
  if (!s) return null;
  const u = s.toUpperCase();
  if (
    u.includes("CONTACTLESS") ||
    u.includes("CTLS") ||
    u.includes("NFC") ||
    u.includes("TAP") ||
    u === "PROX" ||
    u.includes("PROXIMITY")
  ) {
    return "Contactless";
  }
  if (
    u.includes("CHIP") ||
    u.includes("ICC") ||
    u.includes("INSERT") ||
    (u.includes("EMV") && !u.includes("CONTACTLESS"))
  ) {
    return "Chip";
  }
  if (u.includes("SWIPE") || u.includes("MAG") || u.includes("MSR") || u.includes("TRACK")) {
    return "Swipe";
  }
  return s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();
}

function shouldIncludeTipLineOnPrintedReceipt(tipConfig, tipAmountInCents) {
  if (!tipConfig || tipConfig.tipsEnabled === false) return false;
  if (tipConfig.tipPresentation === "RECEIPT") return true;
  return (tipAmountInCents ?? 0) > 0;
}

function centsToDisplaySigned(cents) {
  const n = Number(cents) || 0;
  const abs = Math.abs(n);
  const base = (abs / 100).toFixed(2);
  return n < 0 ? `-$${base}` : `$${base}`;
}

function parseAmountToCents(rawAmount, rawAmountInCents) {
  if (rawAmountInCents != null && rawAmountInCents !== "") {
    const n = Number(rawAmountInCents);
    return Number.isFinite(n) ? Math.round(n) : 0;
  }
  const n = Number(rawAmount);
  return Number.isFinite(n) ? Math.round(n * 100) : 0;
}

async function fetchOrderReversedTransactions(db, orderId) {
  const snap = await db.collection("Orders").doc(orderId).collection("transactions").get();
  if (snap.empty) return [];
  return snap.docs.map((d) => ({ id: d.id, ...(d.data() || {}) }));
}

function formatRightAlignedText(left, right, width) {
  const l = String(left ?? "").trimEnd();
  const r = String(right ?? "").trimStart();
  const minGap = 1;
  const maxLeftLen = Math.max(0, width - r.length - minGap);
  const clippedLeft = l.length <= maxLeftLen ? l : l.slice(0, maxLeftLen);
  const spaces = Math.max(minGap, width - clippedLeft.length - r.length);
  return clippedLeft + " ".repeat(spaces) + r;
}

function reversedPaymentsHtml(reversedTxns) {
  if (!Array.isArray(reversedTxns) || reversedTxns.length === 0) return "";

  const width = 42; // matches typical 80mm receipt-ish line length for mono blocks
  const sep = "-".repeat(width);
  const count = reversedTxns.length;
  const header = count > 1
    ? `Reversed Payments (${count} methods):`
    : "Reversed Payment:";

  const lines = [];
  lines.push(sep, "", header, sep);

  let totalRefundedCents = 0;

  reversedTxns.forEach((t, idx) => {
    const brand = String(t.cardBrand || "Card").trim() || "Card";
    const last4 = String(t.last4 || "").trim();
    const entry = String(t.entryMethod || "").trim();
    const auth = String(t.authCode || "").trim();
    const cents = -Math.abs(parseAmountToCents(t.amount, t.amountInCents));
    totalRefundedCents += cents;

    const left = [brand, last4 ? `**** ${last4}` : "", entry ? `(${entry})` : ""].filter(Boolean).join(" ");
    lines.push(formatRightAlignedText(left, centsToDisplaySigned(cents), width));
    if (auth) lines.push(`  Auth: ${auth}`);
    if (idx !== reversedTxns.length - 1) lines.push("");
  });

  lines.push(sep);
  lines.push(formatRightAlignedText("Total Refunded:", centsToDisplaySigned(totalRefundedCents), width));
  lines.push(formatRightAlignedText("Balance:", "$0.00", width));

  // Render as a monospaced block so spacing aligns like the thermal receipt.
  return (
    '<hr style="border:none;border-top:1px solid #ddd;margin:20px 0 12px 0;">' +
    `<pre style="margin:0 auto;max-width:500px;font-family:Menlo,Consolas,Monaco,monospace;font-size:13px;line-height:1.45;color:#222;white-space:pre-wrap;">${escapeHtml(lines.join("\n"))}</pre>`
  );
}

function receiptTitleHtml(title) {
  return `<div style="text-align:center;margin:4px 0 14px 0;font-weight:bold;font-size:17px;letter-spacing:0.5px;color:#222;">${escapeHtml(title)}</div>`;
}

/** Same HTML document as standard (non-split) email receipt — used by sendReceiptEmail and getReceiptEmailPreview. */
async function composeStandardReceiptWrappedHtml(db, orderId) {
  const biz = await fetchBusinessInfo(db);
  const orderRef = db.collection("Orders").doc(orderId);
  const orderDoc = await orderRef.get();
  if (!orderDoc.exists) {
    const e = new Error("Order not found.");
    e.code = "NOT_FOUND";
    throw e;
  }
  const order = orderDoc.data();
  const resolvedCustomerName = await resolveCustomerDisplayNameForReceipt(db, order);
  const orderWithCustomer = {
    ...order,
    customerName: resolvedCustomerName || (order.customerName != null ? String(order.customerName).trim() : "") || "",
  };

  const totalInCents = order.totalInCents ?? 0;
  const tipAmountInCents = order.tipAmountInCents ?? 0;
  const taxBreakdown = order.taxBreakdown ?? [];
  const discountInCents = order.discountInCents ?? 0;
  const appliedDiscounts = order.appliedDiscounts ?? [];

  const itemsSnap = await orderRef.collection("items").get();
  const { items, subtotalInCents } = parseItems(itemsSnap);
  const taxInCents = parseTax(taxBreakdown);
  const tipConfig = await fetchTipConfig(db);
  const saleTxSnap = await fetchSaleTransactionForOrder(db, orderId, order);
  const txData = saleTxSnap ? saleTxSnap.data() : null;
  const payments = txData && txData.payments && txData.payments.length > 0
    ? txData.payments
    : await fetchSalePayments(db, orderId);

  const subtotal = subtotalInCents / 100;
  const tax = taxInCents / 100;
  const tip = tipAmountInCents / 100;
  const total = totalInCents / 100;

  const body =
    brandedHeader(biz) +
    receiptTitleHtml("RECEIPT") +
    orderMetaSection(orderWithCustomer, payments) +
    itemsTableHtml(items, false, appliedDiscounts) +
    totalsHtml({
      subtotal,
      tax,
      taxBreakdown,
      tip,
      total,
      discountInCents,
      appliedDiscounts,
      tipConfig,
      tipAmountInCents,
      omitTipLine: true,
    }) +
    paymentHtml(payments) +
    footerHtml();

  return wrapEmail(body);
}

/** Same HTML as void email receipt. */
async function composeVoidReceiptWrappedHtml(db, orderId) {
  const biz = await fetchBusinessInfo(db);
  const orderDoc = await db.collection("Orders").doc(orderId).get();
  if (!orderDoc.exists) {
    const e = new Error("Order not found.");
    e.code = "NOT_FOUND";
    throw e;
  }
  const order = orderDoc.data();
  const resolvedCustomerName = await resolveCustomerDisplayNameForReceipt(db, order);
  const orderWithCustomer = {
    ...order,
    customerName: resolvedCustomerName || (order.customerName != null ? String(order.customerName).trim() : "") || "",
  };
  const totalInCents = order.totalInCents ?? 0;
  const tipAmountInCents = order.tipAmountInCents ?? 0;
  const taxBreakdown = order.taxBreakdown ?? [];
  const discountInCents = order.discountInCents ?? 0;
  const appliedDiscounts = order.appliedDiscounts ?? [];
  const total = totalInCents / 100;

  const voidedBy = order.voidedBy ?? "";
  const voidedAt = parseTimestamp(order.voidedAt);
  const voidedAtStr = voidedAt ? voidedAt.toLocaleString("en-US", { timeZone: BUSINESS_TIMEZONE }) : "";

  const itemsSnap = await db.collection("Orders").doc(orderId).collection("items").get();
  const { items, subtotalInCents } = parseItems(itemsSnap);
  const taxInCents = parseTax(taxBreakdown);
  const tipConfig = await fetchTipConfig(db);
  const reversedTxns = await fetchOrderReversedTransactions(db, orderId);
  const saleTxSnap = await fetchSaleTransactionForOrder(db, orderId, order);
  const txData = saleTxSnap ? saleTxSnap.data() : null;
  const fallbackPayments = txData && txData.payments && txData.payments.length > 0
    ? txData.payments
    : await fetchSalePayments(db, orderId);

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
    brandedHeader(biz) +
    statusBadge("VOIDED", "#D32F2F") +
    receiptTitleHtml("VOID RECEIPT") +
    voidMeta +
    orderMetaSection(orderWithCustomer, fallbackPayments) +
    itemsTableHtml(items, true, appliedDiscounts) +
    totalsHtml({
      subtotal,
      tax,
      taxBreakdown,
      tip,
      total,
      totalStrike: true,
      discountInCents,
      appliedDiscounts,
      tipConfig,
      tipAmountInCents,
      omitTipLine: true,
    }) +
    (reversedTxns.length > 0 ? reversedPaymentsHtml(reversedTxns) : paymentHtml(fallbackPayments)) +
    footerHtml('<span style="color:#D32F2F;">This transaction has been voided.</span>');

  return wrapEmail(body);
}

/**
 * Same HTML as refund email receipt.
 * @param {string} [transactionId] Original sale transaction id (REFUND docs use originalReferenceId).
 */
async function composeRefundReceiptWrappedHtml(db, orderId, transactionId) {
  const biz = await fetchBusinessInfo(db);
  const orderDoc = await db.collection("Orders").doc(orderId).get();
  if (!orderDoc.exists) {
    const e = new Error("Order not found.");
    e.code = "NOT_FOUND";
    throw e;
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

  const saleTxSnapRf = await fetchSaleTransactionForOrder(db, orderId, order);
  let salePaymentsRf = saleTxSnapRf ? (saleTxSnapRf.data().payments || []) : [];
  if (salePaymentsRf.length === 0 && allRefundDocs.length > 0) {
    const latestRefund = [...allRefundDocs].sort((a, b) => {
      const aTime = a.data().createdAt?.toMillis?.() ?? a.data().createdAt?._seconds ?? 0;
      const bTime = b.data().createdAt?.toMillis?.() ?? b.data().createdAt?._seconds ?? 0;
      return bTime - aTime;
    })[0].data();
    if (latestRefund.payments && latestRefund.payments.length > 0) {
      salePaymentsRf = latestRefund.payments;
    } else if (latestRefund.paymentType || latestRefund.cardBrand) {
      salePaymentsRf = [{
        paymentType: latestRefund.paymentType || "Credit",
        cardBrand: latestRefund.cardBrand || "",
        last4: latestRefund.last4 || "",
        authCode: latestRefund.authCode || "",
        entryType: latestRefund.entryType || "",
      }];
    }
  }

  const orderType = order.orderType ?? "";
  const typeLabel = ORDER_TYPE_LABELS[orderType] || orderType || "";
  const typeColor = ORDER_TYPE_COLORS[orderType] || "#757575";

  const titleHtml = receiptTitleHtml("REFUND RECEIPT");

  const orderNumber = order.orderNumber ?? "";
  const ts = parseTimestamp(order.createdAt);
  let dateTimeStr = "";
  if (ts) {
    dateTimeStr = ts.toLocaleDateString("en-US", { timeZone: BUSINESS_TIMEZONE, month: "2-digit", day: "2-digit", year: "numeric" }) +
      " " + ts.toLocaleTimeString("en-US", { timeZone: BUSINESS_TIMEZONE, hour: "numeric", minute: "2-digit", hour12: true });
  }
  let orderInfoHtml = '<div style="text-align:center;font-size:14px;color:#333;margin-bottom:4px;">';
  if (orderNumber) orderInfoHtml += `<div style="font-weight:bold;">Order #${escapeHtml(String(orderNumber))}</div>`;
  if (dateTimeStr) orderInfoHtml += `<div>Date: ${escapeHtml(dateTimeStr)}</div>`;
  if (refundedBy) orderInfoHtml += `<div>Refunded by: ${escapeHtml(refundedBy)}</div>`;
  if (typeLabel) {
    orderInfoHtml += `<div style="margin-top:6px;"><span style="background:${typeColor};color:#fff;padding:3px 10px;border-radius:4px;font-size:12px;font-weight:bold;display:inline-block;">${escapeHtml(typeLabel)}</span></div>`;
  }
  orderInfoHtml += "</div>";

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

  const body =
    brandedHeader(biz) +
    titleHtml +
    orderInfoHtml +
    refundItemsHtml +
    taxesHtml +
    totalRefundHtml +
    paymentHtml(salePaymentsRf) +
    footerHtml();

  return wrapEmail(body);
}

// ---------------------------------------------------------------------------
// 1. Standard Receipt
// ---------------------------------------------------------------------------

exports.sendReceiptEmail = onCall(async (request) => {
  const { email, orderId, splitReceipt } = request.data || {};

  if (!email || !orderId) {
    return { success: false, error: "Email and orderId are required." };
  }

  const fromEmail = process.env.SENDGRID_FROM_EMAIL;
  if (!fromEmail) {
    logger.error("SENDGRID_FROM_EMAIL is not configured");
    return { success: false, error: "Email service is not configured." };
  }
  if (!getSendGridApiKey()) {
    logger.error("SENDGRID_API_KEY is not configured");
    return {
      success: false,
      error: "Email service is not configured. Set SENDGRID_API_KEY for Cloud Functions.",
    };
  }

  const db = admin.firestore();
  const biz = await fetchBusinessInfo(db);
  const orderRef = db.collection("Orders").doc(orderId);
  const orderDoc = await orderRef.get();

  if (!orderDoc.exists) {
    return { success: false, error: "Order not found." };
  }

  const order = orderDoc.data();
  const resolvedCustomerName = await resolveCustomerDisplayNameForReceipt(db, order);
  const orderWithCustomer = {
    ...order,
    customerName: resolvedCustomerName || (order.customerName != null ? String(order.customerName).trim() : "") || "",
  };

  /** Per-guest split receipt: SendGrid HTML (no client share sheet). */
  if (validateSplitReceiptPayload(splitReceipt)) {
    const tipConfig = await fetchTipConfig(db);
    const body =
      brandedHeader(biz) +
      receiptTitleHtml("RECEIPT (SPLIT)") +
      orderMetaSection(orderWithCustomer) +
      splitReceiptItemsAndTotalsHtml(splitReceipt, tipConfig) +
      footerHtml();
    const html = wrapEmail(body);
    const gl = String(splitReceipt.guestLabel).trim();
    try {
      await sendGridMail({
        to: email,
        from: fromEmail,
        subject: `Receipt - Order #${order.orderNumber || orderId} — ${gl}`,
        html,
      });
      logger.info("Split receipt sent", { to: email, orderId, guestLabel: gl });
      return { success: true };
    } catch (error) {
      logger.error("SendGrid error (split receipt):", error.message);
      if (error.response) {
        logger.error("SendGrid response:", JSON.stringify(error.response.body));
      }
      return { success: false, error: sendGridErrorForClient(error) };
    }
  }

  let html;
  try {
    html = await composeStandardReceiptWrappedHtml(db, orderId);
  } catch (e) {
    if (e.code === "NOT_FOUND") {
      return { success: false, error: "Order not found." };
    }
    throw e;
  }

  try {
    await sendGridMail({
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
    return { success: false, error: sendGridErrorForClient(error) };
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
  if (!getSendGridApiKey()) {
    logger.error("SENDGRID_API_KEY is not configured");
    return {
      success: false,
      error: "Email service is not configured. Set SENDGRID_API_KEY for Cloud Functions.",
    };
  }

  const db = admin.firestore();
  const orderDoc = await db.collection("Orders").doc(orderId).get();
  if (!orderDoc.exists) {
    return { success: false, error: "Order not found." };
  }
  const order = orderDoc.data();

  let html;
  try {
    html = await composeVoidReceiptWrappedHtml(db, orderId);
  } catch (e) {
    if (e.code === "NOT_FOUND") {
      return { success: false, error: "Order not found." };
    }
    throw e;
  }

  try {
    await sendGridMail({
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
    return { success: false, error: sendGridErrorForClient(error) };
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
    if (!getSendGridApiKey()) {
      logger.error("SENDGRID_API_KEY is not configured");
      return {
        success: false,
        error: "Email service is not configured. Set SENDGRID_API_KEY for Cloud Functions.",
      };
    }

    const db = admin.firestore();
    const orderDoc = await db.collection("Orders").doc(orderId).get();

    if (!orderDoc.exists) {
      return { success: false, error: "Order not found." };
    }

    const order = orderDoc.data();

    let html;
    try {
      html = await composeRefundReceiptWrappedHtml(db, orderId, transactionId);
    } catch (e) {
      if (e.code === "NOT_FOUND") {
        return { success: false, error: "Order not found." };
      }
      throw e;
    }

    try {
      await sendGridMail({
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
      return { success: false, error: sendGridErrorForClient(error) };
    }
  } catch (error) {
    logger.error("sendRefundReceiptEmail error:", error.message, error);
    return { success: false, error: error.message || "An unexpected error occurred." };
  }
});

/**
 * Web dashboard: returns the same HTML document as the emailed receipt (no SendGrid).
 * @param {string} orderId
 * @param {"standard"|"void"|"refund"} [receiptKind] default standard
 * @param {string} [saleTransactionId] For refund receipts: original sale transaction id (REFUND.originalReferenceId).
 */
exports.getReceiptEmailPreview = onCall(async (request) => {
  if (!request.auth?.uid) {
    return { success: false, error: "You must be signed in to view a receipt." };
  }
  const { orderId, receiptKind, saleTransactionId } = request.data || {};
  if (!orderId) {
    return { success: false, error: "orderId is required." };
  }
  const kind = String(receiptKind || "standard").toLowerCase();
  const db = admin.firestore();

  try {
    if (kind === "void") {
      const html = await composeVoidReceiptWrappedHtml(db, orderId);
      return { success: true, html };
    }
    if (kind === "refund") {
      const html = await composeRefundReceiptWrappedHtml(db, orderId, saleTransactionId || "");
      return { success: true, html };
    }
    const html = await composeStandardReceiptWrappedHtml(db, orderId);
    return { success: true, html };
  } catch (e) {
    if (e.code === "NOT_FOUND") {
      return { success: false, error: "Order not found." };
    }
    logger.error("getReceiptEmailPreview", e);
    return { success: false, error: e.message || "Failed to build receipt." };
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
    const biz = await fetchBusinessInfo(db);
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
    const businessName = biz.businessName || order.businessName || "MaxiPay";

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

// ── Temporary: Set Storage CORS (remove after running once) ──
const { onRequest } = require("firebase-functions/v2/https");

exports.fixStorageCors = onRequest(async (req, res) => {
  const corsConfig = [
    {
      origin: [
        "https://www.maxipaypos.com",
        "https://maxipaypos.com",
        "http://localhost:3000",
      ],
      method: ["GET", "POST", "PUT"],
      responseHeader: ["Content-Type"],
      maxAgeSeconds: 3600,
    },
  ];

  try {
    const { Storage } = require("@google-cloud/storage");
    const gcs = new Storage();

    const [allBuckets] = await gcs.getBuckets();
    const allNames = allBuckets.map((b) => b.name);

    const storageBuckets = allNames.filter(
      (n) =>
        n.includes("restaurantapp") &&
        !n.startsWith("gcf-") &&
        !n.includes("cloudfunctions")
    );

    const targets =
      storageBuckets.length > 0
        ? storageBuckets
        : allNames;

    const results = { allBuckets: allNames, targets };

    for (const name of targets) {
      try {
        const bucket = gcs.bucket(name);
        await bucket.setCorsConfiguration(corsConfig);
        const [metadata] = await bucket.getMetadata();
        results[name] = { success: true, cors: metadata.cors };
      } catch (err) {
        results[name] = { error: err.message };
      }
    }

    res.json(results);
  } catch (err) {
    logger.error("CORS fix failed", err);
    res.status(500).json({ error: err.message });
  }
});

// ---------------------------------------------------------------------------
// Uber Eats — webhook, Firestore triggers, and callable functions
// ---------------------------------------------------------------------------

exports.uberWebhook = require("./uber").uberWebhook;

const uberTriggers = require("./uber-triggers");
exports.uberOnOrderStatusChange = uberTriggers.uberOnOrderStatusChange;
exports.uberEnrichNewOrder = uberTriggers.uberEnrichNewOrder;

const uberCallables = require("./uber-callables");
exports.uberGetStores = uberCallables.uberGetStores;
exports.uberActivateIntegration = uberCallables.uberActivateIntegration;
exports.uberSyncMenu = uberCallables.uberSyncMenu;
exports.uberUpdateItem = uberCallables.uberUpdateItem;
exports.uberUpdateModifier = uberCallables.uberUpdateModifier;
exports.uberSuspendItem = uberCallables.uberSuspendItem;
exports.uberAdjustOrder = uberCallables.uberAdjustOrder;
exports.uberReleaseOrder = uberCallables.uberReleaseOrder;
exports.uberResolveFulfillmentIssue = uberCallables.uberResolveFulfillmentIssue;
exports.uberRunCertificationTests = uberCallables.uberRunCertificationTests;
exports.uberGetReports = uberCallables.uberGetReports;

const uberOAuth = require("./uber-oauth");
exports.uberOAuthStart = uberOAuth.uberOAuthStart;
exports.uberOAuthCallback = uberOAuth.uberOAuthCallback;
exports.uberOAuthStatus = uberOAuth.uberOAuthStatus;

// ---------------------------------------------------------------------------
// Server-side card refund (no card present) — iPOS Transact API
// ---------------------------------------------------------------------------
//
// SPIn (/Payment/Return) always routes through the physical terminal.
// For card-not-present refunds we use the iPOS Transact API instead, which
// processes refunds server-side using the original transaction's RRN
// (Retrieval Reference Number = pnReferenceId from SPIn sales).
//
// Docs: https://docs.ipospays.com/ipos-transact/apidocs
// Endpoint: POST /api/v1/iposTransact  (or v2/v3)
// transactionType 3 = Refund
// ---------------------------------------------------------------------------

const { randomUUID } = require("crypto");

const IPOS_TRANSACT_PROD = "https://payment.ipospays.com/api/v1/iposTransact";
const IPOS_TRANSACT_SANDBOX = "https://payment.ipospays.tech/api/v1/iposTransact";

/**
 * Resolves iPOS Transact credentials.
 *
 * Priority:
 *   1. Active `payment_terminals` doc — `config.tpn` + `config.iposTransactAuthToken`
 *      (P-series terminals store the token alongside SPIn creds)
 *   2. Fallback auth token: `Settings/onlineOrdering` (iposTransactAuthToken / iposHppAuthToken)
 *   3. Fallback auth token: env IPOS_HPP_AUTH_TOKEN
 *   4. Fallback TPN: Settings/onlineOrdering iposHppTpn → env IPOS_HPP_TPN
 */
async function resolveIposTransactCredentials(db) {
  let terminalTpn = "";
  let authToken = "";
  let useSandbox = false;

  // 1. Terminal config (TPN + optional iPOS Transact auth token from P-series)
  const termSnap = await db.collection("payment_terminals").where("active", "==", true).limit(1).get();
  if (!termSnap.empty) {
    const cfg = termSnap.docs[0].data().config || {};
    terminalTpn = String(cfg.tpn || "").trim();
    authToken = String(cfg.iposTransactAuthToken || "").trim();
  }

  // 2. Fallback auth token: Settings/onlineOrdering → env
  if (!authToken) {
    try {
      const snap = await db.collection("Settings").doc("onlineOrdering").get();
      const d = snap.exists ? snap.data() : {};
      authToken = String(d.iposTransactAuthToken || d.iposHppAuthToken || "").trim();
      if (d.iposTransactSandbox === true || d.iposHppSandbox === true) useSandbox = true;
    } catch (e) {
      logger.warn("[processServerRefund] Firestore credential read", e?.message || e);
    }
  }
  if (!authToken) authToken = (process.env.IPOS_HPP_AUTH_TOKEN || "").trim();

  // 3. Fallback TPN: Settings/onlineOrdering → env
  if (!terminalTpn) {
    try {
      const snap = await db.collection("Settings").doc("onlineOrdering").get();
      const d = snap.exists ? snap.data() : {};
      terminalTpn = String(d.iposHppTpn || "").trim();
    } catch (_) { /* */ }
    if (!terminalTpn) terminalTpn = (process.env.IPOS_HPP_TPN || "").trim();
  }

  const baseUrl = (process.env.IPOS_HPP_BASE_URL || "").includes("ipospays.tech") || useSandbox
    ? IPOS_TRANSACT_SANDBOX
    : IPOS_TRANSACT_PROD;

  return { tpn: terminalTpn, authToken, baseUrl };
}

exports.processServerRefund = onCall(async (request) => {
  if (!request.auth?.uid) {
    return { success: false, error: "You must be signed in to process a refund." };
  }
  const {
    transactionId,
    orderId,
    amountInCents,
    refundedLineKey,
    refundedItemName,
  } = request.data || {};

  if (!transactionId || !orderId) {
    return { success: false, error: "transactionId and orderId are required." };
  }
  if (!Number.isFinite(amountInCents) || amountInCents <= 0) {
    return { success: false, error: "amountInCents must be a positive integer." };
  }

  const db = admin.firestore();

  // --- 1. Load + validate the sale transaction ---
  const txDoc = await db.collection("Transactions").doc(transactionId).get();
  if (!txDoc.exists) {
    return { success: false, error: "Transaction not found." };
  }
  const tx = txDoc.data();
  const txType = String(tx.type ?? "");
  if (!["SALE", "CAPTURE", "PRE_AUTH"].includes(txType)) {
    return { success: false, error: `Unsupported transaction type: ${txType}` };
  }
  if (tx.voided === true) {
    return { success: false, error: "Transaction is already voided." };
  }
  if (tx.ecommerce === true) {
    return {
      success: false,
      error: "Online / hosted card payments cannot be refunded through this endpoint.",
    };
  }
  // Note: server-side (iPOS Transact) refund is allowed while the sale is still unsettled
  // (open batch) for merchant testing; processor may still decline depending on host state.

  // Find the first card payment leg with a PNReferenceId (= RRN for iPOS Transact)
  const payments = Array.isArray(tx.payments) ? tx.payments : [];
  const cardLeg = payments.find(
    (p) =>
      !String(p.paymentType ?? "").toLowerCase().includes("cash") &&
      String(p.pnReferenceId || p.PNReferenceId || "").trim().length > 0
  );
  if (!cardLeg) {
    return {
      success: false,
      error:
        "No processor reference (RRN) found on this sale. A server-side refund requires the RRN from the original sale.",
    };
  }
  const rrn = String(cardLeg.pnReferenceId || cardLeg.PNReferenceId || "").trim();

  // --- 2. Validate against the order ---
  const orderDoc = await db.collection("Orders").doc(orderId).get();
  if (!orderDoc.exists) {
    return { success: false, error: "Order not found." };
  }
  const order = orderDoc.data();
  const orderTotalInCents = Number(order.totalInCents ?? 0);
  const alreadyRefunded = Number(order.totalRefundedInCents ?? 0);
  const remainingRefundable = orderTotalInCents - alreadyRefunded;
  if (remainingRefundable <= 0) {
    return { success: false, error: "Order is already fully refunded." };
  }
  const saleTotalPaidCents = Number(tx.totalPaidInCents ?? 0);
  const cappedAmount = Math.min(amountInCents, remainingRefundable, saleTotalPaidCents);
  if (cappedAmount <= 0) {
    return { success: false, error: "Nothing to refund after capping to order/sale limits." };
  }

  // --- 3. Resolve iPOS Transact credentials ---
  const creds = await resolveIposTransactCredentials(db);
  if (!creds.tpn || !creds.authToken) {
    return {
      success: false,
      error:
        "iPOS Transact credentials not configured. Set iposHppTpn + iposHppAuthToken in Settings/onlineOrdering, or IPOS_HPP_TPN + IPOS_HPP_AUTH_TOKEN env vars.",
    };
  }

  const refReferenceId = `REF${randomUUID().replace(/-/g, "").slice(0, 16)}`;
  const originalPaymentType = String(cardLeg.paymentType ?? "Credit");

  const iposPayload = {
    merchantAuthentication: {
      merchantId: creds.tpn,
      transactionReferenceId: refReferenceId,
    },
    transactionRequest: {
      transactionType: 3,
      rrn: rrn,
      amount: String(cappedAmount),
    },
  };

  logger.info("[processServerRefund] Calling iPOS Transact", {
    url: creds.baseUrl,
    transactionId,
    orderId,
    amountInCents: cappedAmount,
    rrn,
    tpn: creds.tpn,
  });

  // --- 4. Call iPOS Transact /api/v1/iposTransact ---
  let iposResponse;
  let httpStatus;
  try {
    const resp = await fetch(creds.baseUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        token: creds.authToken,
      },
      body: JSON.stringify(iposPayload),
    });
    httpStatus = resp.status;
    iposResponse = await resp.json();
  } catch (err) {
    logger.error("[processServerRefund] iPOS Transact network error", {
      error: err.message,
    });
    return {
      success: false,
      error: `Network error calling iPOS Transact: ${err.message}`,
    };
  }

  logger.info("[processServerRefund] iPOS Transact response", {
    httpStatus,
    iposResponse: JSON.stringify(iposResponse).slice(0, 2000),
    transactionId,
  });

  // iPOS Transact returns { iposTransactResponse: { responseCode, responseMessage, ... } }
  // or { errors: [...] } for validation failures
  if (iposResponse.errors) {
    const errMsg = iposResponse.errors
      .map((e) => `${e.field}: ${e.message}`)
      .join("; ");
    return { success: false, error: errMsg, iposResponse };
  }

  const txnResp = iposResponse.iposhpresponse
    || iposResponse.iposTransactResponse
    || iposResponse.iposHPResponse
    || iposResponse;
  const responseCode = Number(txnResp.responseCode ?? -1);
  const responseMessage = String(txnResp.responseMessage ?? "").trim();
  const errResponseCode = String(txnResp.errResponseCode ?? "").trim();
  const errResponseMessage = String(txnResp.errResponseMessage ?? "").trim();

  if (responseCode !== 200) {
    const rawSnippet = JSON.stringify(iposResponse).slice(0, 500);
    const detail = [
      errResponseMessage,
      responseMessage,
      errResponseCode ? `errCode=${errResponseCode}` : "",
      `responseCode=${responseCode}`,
      `HTTP=${httpStatus}`,
      `TPN=${creds.tpn}`,
      `RRN=${rrn}`,
      `RAW=${rawSnippet}`,
    ].filter(Boolean).join(" | ");
    return {
      success: false,
      error: detail,
      iposResponse,
    };
  }

  // --- 5. Persist refund in Firestore (mirrors RemoteRefundExecutor) ---
  const refundDollars = cappedAmount / 100;
  const refundedByLabel = `Dashboard: ${request.auth.token?.email || request.auth.uid}`;
  const refundAmountCents = cappedAmount;

  let batchSnap = await db
    .collection("Batches")
    .where("closed", "==", false)
    .limit(1)
    .get();
  let openBatchId = batchSnap.empty ? "" : batchSnap.docs[0].id;
  if (!openBatchId) {
    const newBatchId = `BATCH_${Date.now()}`;
    await db.collection("Batches").doc(newBatchId).set({
      batchId: newBatchId,
      total: 0,
      count: 0,
      closed: false,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      type: "OPEN",
      transactionCounter: 0,
    });
    openBatchId = newBatchId;
    logger.info("[processServerRefund] Created open batch for refund", { openBatchId });
  }

  const refundMap = {
    referenceId: refReferenceId,
    originalReferenceId: transactionId,
    amount: refundDollars,
    amountInCents: refundAmountCents,
    type: "REFUND",
    paymentType: originalPaymentType,
    cardBrand: String(txnResp.cardType || cardLeg.cardBrand || ""),
    last4: String(cardLeg.last4 ?? ""),
    entryType: String(cardLeg.entryType ?? ""),
    voided: false,
    settled: false,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    refundedBy: refundedByLabel,
    batchId: openBatchId,
    orderId,
    orderNumber: Number(order.orderNumber ?? 0),
    serverRefund: true,
    approvalCode: String(txnResp.responseApprovalCode || ""),
    gatewayTransactionId: String(txnResp.transactionId || ""),
    refundRrn: String(txnResp.rrn || ""),
    hostResponseCode: String(txnResp.hostResponseCode || ""),
    hostResponseMessage: String(txnResp.hostResponseMessage || ""),
  };
  if (refundedLineKey) refundMap.refundedLineKey = refundedLineKey;
  if (refundedItemName) refundMap.refundedItemName = refundedItemName;

  try {
    await db.runTransaction(async (firestoreTxn) => {
      if (openBatchId) {
        const batchRef = db.collection("Batches").doc(openBatchId);
        const batchDoc = await firestoreTxn.get(batchRef);
        const counter = Number(batchDoc.data()?.transactionCounter ?? 0);
        firestoreTxn.update(batchRef, { transactionCounter: counter + 1 });
        refundMap.appTransactionNumber = counter + 1;
      }
      const refundRef = db.collection("Transactions").doc();
      firestoreTxn.set(refundRef, refundMap);
    });
  } catch (err) {
    logger.error("[processServerRefund] Firestore write failed", {
      error: err.message,
    });
    return {
      success: false,
      error: `Refund approved by processor but failed to save: ${err.message}`,
    };
  }

  if (openBatchId) {
    await db
      .collection("Batches")
      .doc(openBatchId)
      .update({
        totalRefundsInCents: admin.firestore.FieldValue.increment(refundAmountCents),
        netTotalInCents: admin.firestore.FieldValue.increment(-refundAmountCents),
        transactionCount: admin.firestore.FieldValue.increment(1),
      })
      .catch((e) =>
        logger.warn("[processServerRefund] batch counter update", e.message)
      );
  }

  const currentRefunded = Number(order.totalRefundedInCents ?? 0);
  const newTotalRefunded = currentRefunded + refundAmountCents;
  const orderUpdates = {
    totalRefundedInCents: newTotalRefunded,
    refundedAt: admin.firestore.FieldValue.serverTimestamp(),
  };
  if (newTotalRefunded >= orderTotalInCents) {
    orderUpdates.status = "REFUNDED";
  }
  await db
    .collection("Orders")
    .doc(orderId)
    .update(orderUpdates)
    .catch((e) =>
      logger.warn("[processServerRefund] order update", e.message)
    );

  logger.info("[processServerRefund] Refund complete", {
    transactionId,
    orderId,
    refundAmountCents,
  });

  const approvalCode = String(txnResp.responseApprovalCode || "");
  const approvalSuffix = approvalCode ? ` Approval: ${approvalCode}` : "";
  return {
    success: true,
    message: `Refund of $${refundDollars.toFixed(2)} approved.${approvalSuffix}`,
    iposResponse,
  };
});

/**
 * Resolve SPIn P-series terminal credentials from Firestore.
 * Only SPIN_P terminals support dashboard-initiated settlement;
 * credentials come from `payment_terminals` (Payments page).
 */
async function resolveSpinSettleCredentials(db) {
  const SPIN_DEFAULT_BASE = "https://spinpos.net/v2";

  const ptSnap = await db
    .collection("payment_terminals")
    .where("active", "==", true)
    .get();

  for (const doc of ptSnap.docs) {
    const d = doc.data();
    const provider = String(d.provider || "").toUpperCase();
    if (provider !== "SPIN_P") continue;

    const cfg = d.config || {};
    const baseUrl = String(d.baseUrl || SPIN_DEFAULT_BASE).replace(/\/+$/, "");
    const endpoints = d.endpoints || {};
    const settlePath = String(endpoints.settle || "/Payment/Settle");
    const settleUrl = settlePath.startsWith("http")
      ? settlePath
      : `${baseUrl}${settlePath.startsWith("/") ? "" : "/"}${settlePath}`;
    return {
      found: true,
      provider,
      deviceModel: String(d.deviceModel || d.name || "").trim(),
      terminalName: String(d.name || doc.id).trim(),
      tpn: String(cfg.tpn || "").trim(),
      registerId: String(cfg.registerId || "").trim(),
      authKey: String(cfg.authKey || "").trim(),
      settleUrl,
    };
  }

  return { found: false, provider: "", deviceModel: "", terminalName: "", tpn: "", registerId: "", authKey: "", settleUrl: "" };
}

/**
 * Dashboard: close the open batch — calls SPIn settle (Z8) first,
 * then updates Firestore exactly like the Android POS.
 */
function computeNetAmountForBatchClose(data) {
  const type = String(data?.type ?? "SALE");
  if (type === "SALE" || type === "CAPTURE") {
    const cents = data.totalPaidInCents;
    if (typeof cents === "number") return cents / 100;
    const tp = data.totalPaid;
    if (typeof tp === "number") return tp;
    const amt = data.amount;
    if (typeof amt === "number") return amt;
    return 0;
  }
  if (type === "REFUND") {
    const a = data.amount;
    return typeof a === "number" ? -a : 0;
  }
  return 0;
}

exports.closeOpenBatchFromDashboard = onCall(
  { timeoutSeconds: 300, memory: "512MiB" },
  async (request) => {
    if (!request.auth?.uid) {
      return { success: false, error: "You must be signed in to close a batch." };
    }
    const { expectedBatchId } = request.data || {};
    const expected =
      expectedBatchId != null && String(expectedBatchId).trim()
        ? String(expectedBatchId).trim()
        : null;

    const db = admin.firestore();
    const closedBy = String(request.auth.token?.email || request.auth.uid);

    let batchSnap = await db
      .collection("Batches")
      .where("closed", "==", false)
      .limit(1)
      .get();

    let batchId;
    if (batchSnap.empty) {
      batchId = `BATCH_${Date.now()}`;
      await db
        .collection("Batches")
        .doc(batchId)
        .set({
          batchId,
          total: 0,
          count: 0,
          closed: false,
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
          type: "OPEN",
          transactionCounter: 0,
        });
      logger.info("[closeOpenBatchFromDashboard] Created missing open batch", {
        batchId,
        uid: request.auth.uid,
      });
    } else {
      batchId = batchSnap.docs[0].id;
    }

    if (expected && expected !== batchId) {
      return {
        success: false,
        error: "Open batch changed. Refresh the page and try again.",
      };
    }

    const txSnap = await db
      .collection("Transactions")
      .where("settled", "==", false)
      .where("voided", "==", false)
      .get();

    const txDocs = txSnap.docs;
    let preAuthCount = 0;
    let settleableCount = 0;
    for (const doc of txDocs) {
      const t = String(doc.data()?.type ?? "SALE");
      if (t === "PRE_AUTH") preAuthCount += 1;
      else settleableCount += 1;
    }
    if (preAuthCount > 0) {
      return {
        success: false,
        error:
          "There are open pre-authorizations. Capture or void all bar-tab pre-auths on the POS before closing the batch.",
      };
    }
    if (settleableCount <= 0) {
      return {
        success: false,
        error: "No open transactions to settle (same rule as the POS).",
      };
    }

    const batchRef = db.collection("Batches").doc(batchId);
    const batchFresh = await batchRef.get();
    if (!batchFresh.exists || batchFresh.data()?.closed === true) {
      return {
        success: false,
        error: "That batch is already closed. Refresh the page.",
      };
    }

    // --- Call SPIn Z8 settle (only SPIN_P terminals) ---
    const spinCreds = await resolveSpinSettleCredentials(db);
    if (!spinCreds.found) {
      return {
        success: false,
        error:
          "No active SPIn (SPInPos Gateway) P terminal found. " +
          "Go to Settings \u2192 Payments, add or activate a SPIN_P terminal with TPN + Auth Key.",
      };
    }
    if (!spinCreds.tpn || !spinCreds.authKey) {
      return {
        success: false,
        error:
          `Terminal \"${spinCreds.terminalName}\" is missing TPN or Auth Key. ` +
          "Edit it in Settings \u2192 Payments and fill in the credentials.",
      };
    }

    const settlePayload = {
      PrintReceipt: false,
      GetReceipt: false,
      SettlementType: "Force",
      Tpn: spinCreds.tpn,
      RegisterId: spinCreds.registerId,
      Authkey: spinCreds.authKey,
    };

    logger.info("[closeOpenBatchFromDashboard] Calling SPIn settle (Z8)", {
      url: spinCreds.settleUrl,
      tpn: spinCreds.tpn,
      deviceModel: spinCreds.deviceModel,
      terminalName: spinCreds.terminalName,
      uid: request.auth.uid,
    });

    let spinResponse;
    try {
      const resp = await fetch(spinCreds.settleUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(settlePayload),
      });
      spinResponse = await resp.json();
    } catch (err) {
      logger.error("[closeOpenBatchFromDashboard] SPIn settle network error", {
        error: err.message,
        deviceModel: spinCreds.deviceModel,
      });
      return {
        success: false,
        error: `Network error calling SPIn settle on ${spinCreds.deviceModel || "terminal"}: ${err.message}`,
      };
    }

    logger.info("[closeOpenBatchFromDashboard] SPIn settle response", {
      response: JSON.stringify(spinResponse).slice(0, 2000),
      deviceModel: spinCreds.deviceModel,
    });

    const generalResponse = spinResponse.GeneralResponse || {};
    const resultCode = String(generalResponse.ResultCode ?? "-1");
    const detailedMessage = String(generalResponse.DetailedMessage ?? "");
    const settleDetails = Array.isArray(spinResponse.SettleDetails)
      ? spinResponse.SettleDetails
      : [];
    const hostApproved = settleDetails.some(
      (d) => String(d.HostStatus) === "0"
    );

    if (resultCode !== "0" && !hostApproved) {
      const msg = detailedMessage || `ResultCode: ${resultCode}`;
      logger.warn("[closeOpenBatchFromDashboard] SPIn settle declined", {
        msg,
        resultCode,
        deviceModel: spinCreds.deviceModel,
      });
      return {
        success: false,
        error: `Processor settlement failed on ${spinCreds.deviceModel || "terminal"}: ${msg}`,
        spinResponse,
      };
    }

    // --- Z8 approved — now update Firestore (same as Android settleOpenBatch) ---

    let totalSales = 0;
    let totalTipsCents = 0;
    let count = 0;
    for (const doc of txDocs) {
      const d = doc.data();
      const net = computeNetAmountForBatchClose(d);
      if (Math.abs(net) > 1e-9) {
        totalSales += net;
        count += 1;
      }
      const type = String(d?.type ?? "SALE");
      if (type === "SALE" || type === "CAPTURE") {
        totalTipsCents += Number(d.tipAmountInCents ?? 0);
      }
    }

    const CHUNK = 450;
    for (let i = 0; i < txDocs.length; i += CHUNK) {
      const wb = db.batch();
      const slice = txDocs.slice(i, i + CHUNK);
      for (const doc of slice) {
        wb.update(doc.ref, {
          settled: true,
          batchId,
        });
      }
      await wb.commit();
    }

    const again = await batchRef.get();
    if (!again.exists || again.data()?.closed === true) {
      return {
        success: false,
        error:
          "This batch was closed while processing (another session or device). Refresh and verify your data.",
      };
    }

    const newBatchId = `BATCH_${Date.now()}`;
    const finalWb = db.batch();
    finalWb.update(batchRef, {
      closed: true,
      closedAt: admin.firestore.FieldValue.serverTimestamp(),
      totalSales,
      totalTipsInCents: totalTipsCents,
      transactionCount: count,
      type: "SETTLEMENT",
      closedFromDashboardBy: closedBy,
      closedFromDashboardUid: request.auth.uid,
    });
    finalWb.set(db.collection("Batches").doc(newBatchId), {
      batchId: newBatchId,
      total: 0,
      count: 0,
      closed: false,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      type: "OPEN",
      transactionCounter: 0,
    });
    await finalWb.commit();

    logger.info("[closeOpenBatchFromDashboard] Closed batch", {
      closedBatchId: batchId,
      newBatchId,
      transactionDocs: txDocs.length,
      summaryCount: count,
      uid: request.auth.uid,
    });

    const deviceLabel = spinCreds.deviceModel || "terminal";
    const spinMsg = hostApproved && resultCode !== "0"
      ? `Z8 Batch Closed Successfully on ${deviceLabel} (host approved, ResultCode=${resultCode})`
      : `Z8 Batch Closed Successfully on ${deviceLabel}`;

    return {
      success: true,
      closedBatchId: batchId,
      newBatchId,
      settledTransactionDocs: txDocs.length,
      transactionCount: count,
      totalSales,
      processorMessage: spinMsg,
      deviceModel: spinCreds.deviceModel,
      terminalName: spinCreds.terminalName,
    };
  }
);

// ---------------------------------------------------------------------------
// iPOSpays Hosted Payment Page — online ordering payments
// ---------------------------------------------------------------------------

const iposHpp = require("./ipos-hpp");
exports.createHppPaymentLink = iposHpp.createHppPaymentLink;
exports.iposPaymentWebhook = iposHpp.iposPaymentWebhook;
exports.queryHppPaymentStatus = iposHpp.queryHppPaymentStatus;

// ---------------------------------------------------------------------------
// Menu item images — Pexels search + Storage commit (Android + dashboard parity)
// ---------------------------------------------------------------------------

const menuItemImage = require("./menu-item-image");
exports.menuItemImageSearch = menuItemImage.menuItemImageSearch;
exports.menuItemImageCommitPexels = menuItemImage.menuItemImageCommitPexels;
