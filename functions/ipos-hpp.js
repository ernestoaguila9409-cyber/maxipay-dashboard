/**
 * iPOSpays Hosted Payment Page (HPP) integration — LIVE/production by default.
 *
 * Env vars (set via Firebase secrets or .env):
 *   IPOS_HPP_TPN          – CloudPOS terminal processing number (live merchant)
 *   IPOS_HPP_AUTH_TOKEN    – merchant auth token from the iPOSpays portal (live)
 *   IPOS_HPP_BASE_URL      – (optional) override; defaults to PRODUCTION URL.
 *                            Only set to sandbox URL when testing with sandbox creds.
 *   IPOS_HPP_QUERY_API_KEY – API key for queryPaymentStatus (Authorization header)
 *   ONLINE_ORDERING_BASE_URL – e.g. https://order.maxipaypos.com
 *
 * Per-merchant credentials: `Settings/onlineOrdering` in Firestore may set
 * `iposHppTpn` and `iposHppAuthToken` (dashboard → Online ordering). When set,
 * they override env for createHppPaymentLink / queryHppPaymentStatus.
 *
 * IMPORTANT: TPN + auth token MUST belong to the same environment as the URL
 * (live token with live URL, or sandbox token with sandbox URL). Mixing them
 * returns a valid-looking link that resolves to "Link Expired".
 */

const admin = require("firebase-admin");
const { onCall } = require("firebase-functions/v2/https");
const { onRequest } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");

const SANDBOX_URL = "https://payment.ipospays.tech/api/v1/external-payment-transaction";
const PROD_URL = "https://payment.ipospays.com/api/v1/external-payment-transaction";
const SANDBOX_QUERY_URL = "https://api.ipospays.tech/v1/queryPaymentStatus";
const PROD_QUERY_URL = "https://api.ipospays.com/v1/queryPaymentStatus";

/**
 * iPOS sometimes omits transactionId on the webhook; RRN / merchant ref still allow void tooling.
 * @returns {{ payments: object[]; txExtra: Record<string, unknown> }}
 */
function buildEcommerceSalePaymentAndTxRefs(hpResp, txRefId, totalInCents) {
  const gatewayTxnId = String(
    hpResp.transactionId || hpResp.TransactionId || hpResp.gatewayTransactionId || ""
  ).trim();
  const rrn = String(
    hpResp.rrn || hpResp.RRN || hpResp.retrievalReferenceNumber || ""
  ).trim();
  const refForVoid = gatewayTxnId || rrn || "";
  const clientRef = String(txRefId || "").trim();
  const batchNum = hpResp.batchNumber ?? hpResp.BatchNumber;
  const txnNum = hpResp.transactionNumber ?? hpResp.TransactionNumber;
  const payments = [
    {
      paymentType: "CREDIT",
      cardBrand: hpResp.cardType || "",
      last4: String(hpResp.cardLast4Digit || ""),
      authCode: hpResp.responseApprovalCode || "",
      entryType: "ECOMMERCE",
      amountInCents: totalInCents,
      referenceId: refForVoid,
      clientReferenceId: clientRef,
      batchNumber: batchNum != null && batchNum !== "" ? String(batchNum) : "",
      transactionNumber: txnNum != null && txnNum !== "" ? String(txnNum) : "",
    },
  ];
  const txExtra = {
    referenceId: refForVoid,
    clientReferenceId: clientRef,
    gatewayReferenceId: refForVoid,
    hppTransactionRefId: clientRef,
    hppTransactionId: gatewayTxnId || refForVoid,
  };
  return { payments, txExtra };
}

function hppBaseUrl() {
  return process.env.IPOS_HPP_BASE_URL || PROD_URL;
}

function queryBaseUrl() {
  const base = hppBaseUrl();
  if (base.includes("ipospays.com")) return PROD_QUERY_URL;
  return SANDBOX_QUERY_URL;
}

function getTpn() {
  return (process.env.IPOS_HPP_TPN || "").trim();
}

function getAuthToken() {
  return (process.env.IPOS_HPP_AUTH_TOKEN || "").trim();
}

function getOnlineOrderingBaseUrl() {
  return (process.env.ONLINE_ORDERING_BASE_URL || "").trim();
}

/**
 * @param {FirebaseFirestore.Firestore} db
 * @returns {Promise<{ tpn: string; authToken: string }>}
 */
async function resolveHppCredentials(db) {
  let tpn = "";
  let authToken = "";
  try {
    const snap = await db.collection("Settings").doc("onlineOrdering").get();
    const d = snap.exists ? snap.data() : {};
    tpn = String(d.iposHppTpn || "").trim();
    authToken = String(d.iposHppAuthToken || "").trim();
  } catch (e) {
    logger.warn("[ipos-hpp] Firestore credential read failed", e?.message || e);
  }
  if (!tpn) tpn = getTpn();
  if (!authToken) authToken = getAuthToken();
  return { tpn, authToken };
}

// ---------------------------------------------------------------------------
// 1. createHppPaymentLink — callable from web frontend
// ---------------------------------------------------------------------------

const createHppPaymentLink = onCall(async (request) => {
  const { orderId } = request.data || {};

  if (!orderId) {
    return { success: false, error: "orderId is required." };
  }

  const db = admin.firestore();
  const { tpn, authToken } = await resolveHppCredentials(db);
  if (!tpn || !authToken) {
    logger.error("[ipos-hpp] IPOS_HPP_TPN or IPOS_HPP_AUTH_TOKEN not configured");
    return { success: false, error: "Payment service is not configured." };
  }

  const orderDoc = await db.collection("Orders").doc(orderId).get();
  if (!orderDoc.exists) {
    return { success: false, error: "Order not found." };
  }
  const order = orderDoc.data();

  const totalInCents = order.totalInCents ?? 0;
  if (totalInCents <= 0) {
    return { success: false, error: "Order total must be greater than zero." };
  }

  const taxInCents = order.taxAmountInCents ?? 0;
  const customerName = (order.customerName || "").trim();
  const customerEmail = (order.customerEmail || "").trim();
  const customerPhone = (order.customerPhone || "").trim();

  const txRefId = `ORD${orderId.substring(0, 15)}`;

  const siteUrl = getOnlineOrderingBaseUrl();
  const returnUrl = siteUrl ? `${siteUrl}/payment/success?orderId=${orderId}` : "";
  const failureUrl = siteUrl ? `${siteUrl}/payment/failure?orderId=${orderId}` : "";
  const cancelUrl = siteUrl ? `${siteUrl}/payment/cancel?orderId=${orderId}` : "";

  const projectId = process.env.GCLOUD_PROJECT || process.env.GCP_PROJECT || "";
  let postAPI = "";
  if (projectId) {
    postAPI = `https://us-central1-${projectId}.cloudfunctions.net/iposPaymentWebhook`;
  }

  const payload = {
    merchantAuthentication: {
      merchantId: tpn,
      transactionReferenceId: txRefId,
    },
    transactionRequest: {
      transactionType: 1,
      amount: String(totalInCents),
      calculateFee: false,
      tipsInputPrompt: false,
      calculateTax: false,
      lTaxAmount: String(taxInCents),
      lTaxLabel: "Tax",
      txReferenceTag1: {
        tagLabel: "Order",
        tagValue: String(order.orderNumber || orderId),
        isTagMandate: false,
      },
      expiry: 1,
    },
    notificationOption: {
      notificationBySMS: false,
      mobileNumber: "",
      notifyByPOST: !!postAPI,
      authHeader: authToken,
      postAPI: postAPI,
      notifyByRedirect: !!returnUrl,
      returnUrl: returnUrl,
      failureUrl: failureUrl,
      cancelUrl: cancelUrl,
    },
    preferences: {
      integrationType: 1,
      avsVerification: false,
      eReceipt: !!(customerEmail || customerPhone),
      eReceiptInputPrompt: false,
      customerName: customerName || "Customer",
      customerEmail: customerEmail,
      customerMobile: customerPhone,
      requestCardToken: false,
      shortenURL: true,
      sendPaymentLink: false,
      integrationVersion: "v2",
    },
    personalization: {
      merchantName: order.businessName || "MaxiPay",
      themeColor: "#1976D2",
      description: `Order #${order.orderNumber || orderId}`,
      payNowButtonText: "Pay Now",
      buttonColor: "#1976D2",
      cancelButtonText: "Cancel",
    },
  };

  const bizSnap = await db.collection("Settings").doc("businessInfo").get();
  if (bizSnap.exists) {
    const biz = bizSnap.data() || {};
    if (biz.businessName) payload.personalization.merchantName = biz.businessName;
    if (biz.logoUrl && String(biz.logoUrl).startsWith("http")) {
      payload.personalization.logoUrl = String(biz.logoUrl);
    }
  }

  logger.info("[ipos-hpp] Creating payment link", {
    orderId,
    txRefId,
    amountCents: totalInCents,
  });

  try {
    const resp = await fetch(hppBaseUrl(), {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        token: authToken,
      },
      body: JSON.stringify(payload),
    });

    const body = await resp.json();

    if (body.information && body.message) {
      await db.collection("Orders").doc(orderId).update({
        hppTransactionRefId: txRefId,
        hppPaymentUrl: body.information,
        hppCreatedAt: admin.firestore.FieldValue.serverTimestamp(),
        hppStatus: "PENDING",
      });

      logger.info("[ipos-hpp] Payment link created", {
        orderId,
        url: body.information,
      });

      return {
        success: true,
        paymentUrl: body.information,
        transactionReferenceId: txRefId,
      };
    }

    logger.error("[ipos-hpp] HPP URL generation failed", { orderId, body });
    const errMsg = Array.isArray(body.errors)
      ? body.errors.map((e) => `${e.field}: ${e.message}`).join("; ")
      : "Payment page generation failed.";
    return { success: false, error: errMsg };
  } catch (err) {
    logger.error("[ipos-hpp] Request error", { orderId, error: err.message });
    return { success: false, error: "Failed to connect to payment service." };
  }
});

// ---------------------------------------------------------------------------
// 2. iposPaymentWebhook — HTTP endpoint for notifyByPOST callbacks
// ---------------------------------------------------------------------------

const iposPaymentWebhook = onRequest(async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }

  const payload = req.body;
  const hpResp = payload?.iposHPResponse || payload;

  const txRefId = hpResp.transactionReferenceId;
  const responseCode = Number(hpResp.responseCode);
  const responseMessage = hpResp.responseMessage || "";

  logger.info("[ipos-hpp] Webhook received", {
    txRefId,
    responseCode,
    responseMessage,
    cardType: hpResp.cardType,
    totalAmount: hpResp.totalAmount,
  });

  if (!txRefId) {
    logger.warn("[ipos-hpp] Webhook missing transactionReferenceId");
    res.status(400).json({ error: "Missing transactionReferenceId" });
    return;
  }

  const db = admin.firestore();

  const ordersSnap = await db
    .collection("Orders")
    .where("hppTransactionRefId", "==", txRefId)
    .limit(1)
    .get();

  if (ordersSnap.empty) {
    logger.warn("[ipos-hpp] No order found for txRefId", { txRefId });
    res.status(404).json({ error: "Order not found" });
    return;
  }

  const orderDoc = ordersSnap.docs[0];
  const orderId = orderDoc.id;
  const order = orderDoc.data();

  const isSuccess = responseCode === 200;

  if (order.status === "CLOSED" || order.status === "PAID") {
    logger.info("[ipos-hpp] Order already settled, skipping webhook update", { orderId });
    res.status(200).json({ received: true, orderId, status: order.status });
    return;
  }

  const updateData = {
    hppStatus: isSuccess ? "PAID" : "FAILED",
    hppResponseCode: responseCode,
    hppResponseMessage: responseMessage,
    hppRespondedAt: admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  };

  if (isSuccess) {
    updateData.hppCardType = hpResp.cardType || "";
    updateData.hppCardLast4 = hpResp.cardLast4Digit || "";
    updateData.hppTransactionId = hpResp.transactionId || "";
    updateData.hppTransactionNumber = hpResp.transactionNumber || "";
    updateData.hppBatchNumber = hpResp.batchNumber || "";
    updateData.hppApprovalCode = hpResp.responseApprovalCode || "";
    updateData.hppRrn = hpResp.rrn || "";
    updateData.hppTotalAmount = hpResp.totalAmount || 0;
    updateData.hppTips = hpResp.tips || 0;
    updateData.hppErrResponseCode = hpResp.errResponseCode || "";
    updateData.hppErrResponseMessage = hpResp.errResponseMessage || "";

    if (hpResp.cardToken) {
      updateData.hppCardToken = hpResp.cardToken;
    }

    const totalInCents = order.totalInCents || 0;
    updateData.totalPaidInCents = totalInCents;
    updateData.remainingInCents = 0;
    updateData.status = "CLOSED";
    updateData.paidAt = admin.firestore.FieldValue.serverTimestamp();

    const { payments, txExtra } = buildEcommerceSalePaymentAndTxRefs(hpResp, txRefId, totalInCents);

    const txRef = db.collection("Transactions").doc();
    await txRef.set({
      orderId,
      orderNumber: order.orderNumber || 0,
      type: "SALE",
      totalPaidInCents: totalInCents,
      totalPaid: totalInCents / 100,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      voided: false,
      settled: false,
      ecommerce: true,
      payments,
      ...txExtra,
    });

    updateData.saleTransactionId = txRef.id;
  } else {
    updateData.hppErrResponseCode = hpResp.errResponseCode || "";
    updateData.hppErrResponseMessage = hpResp.errResponseMessage || "";
  }

  await db.collection("Orders").doc(orderId).update(updateData);

  logger.info("[ipos-hpp] Order updated from webhook", {
    orderId,
    status: updateData.hppStatus,
    responseCode,
  });

  res.status(200).json({ received: true, orderId, status: updateData.hppStatus });
});

// ---------------------------------------------------------------------------
// 3. queryHppPaymentStatus — callable to poll payment status
// ---------------------------------------------------------------------------

const queryHppPaymentStatus = onCall(async (request) => {
  const { orderId } = request.data || {};

  if (!orderId) {
    return { success: false, error: "orderId is required." };
  }

  const db = admin.firestore();
  const { tpn, authToken } = await resolveHppCredentials(db);
  const apiKey = (process.env.IPOS_HPP_QUERY_API_KEY || "").trim() || authToken;
  if (!tpn || !apiKey) {
    return { success: false, error: "Payment service is not configured." };
  }
  const orderDoc = await db.collection("Orders").doc(orderId).get();
  if (!orderDoc.exists) {
    return { success: false, error: "Order not found." };
  }

  const order = orderDoc.data();
  const txRefId = order.hppTransactionRefId;
  if (!txRefId) {
    return { success: false, error: "No payment link was created for this order." };
  }

  if (order.status === "CLOSED" || order.status === "PAID") {
    return {
      success: true,
      status: "PAID",
      responseCode: order.hppResponseCode,
      message: "Payment already confirmed.",
    };
  }

  const url = `${queryBaseUrl()}?tpn=${encodeURIComponent(tpn)}&transactionReferenceId=${encodeURIComponent(txRefId)}`;

  try {
    const resp = await fetch(url, {
      method: "GET",
      headers: { Authorization: apiKey },
    });

    const body = await resp.json();
    const hpResp = body?.iposHPResponse || body;

    const responseCode = Number(hpResp.responseCode);
    const isSuccess = responseCode === 200;

    if (isSuccess && order.status !== "CLOSED" && order.status !== "PAID") {
      const totalInCents = order.totalInCents || 0;
      const { payments, txExtra } = buildEcommerceSalePaymentAndTxRefs(hpResp, txRefId, totalInCents);

      const txRef = db.collection("Transactions").doc();
      await txRef.set({
        orderId,
        orderNumber: order.orderNumber || 0,
        type: "SALE",
        totalPaidInCents: totalInCents,
        totalPaid: totalInCents / 100,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        voided: false,
        settled: false,
        ecommerce: true,
        payments,
        ...txExtra,
      });

      await db.collection("Orders").doc(orderId).update({
        hppStatus: "PAID",
        hppResponseCode: responseCode,
        hppResponseMessage: hpResp.responseMessage || "",
        hppCardType: hpResp.cardType || "",
        hppCardLast4: hpResp.cardLast4Digit || "",
        hppTransactionId: hpResp.transactionId || "",
        hppApprovalCode: hpResp.responseApprovalCode || "",
        hppTotalAmount: hpResp.totalAmount || 0,
        hppRespondedAt: admin.firestore.FieldValue.serverTimestamp(),
        totalPaidInCents: totalInCents,
        remainingInCents: 0,
        status: "CLOSED",
        saleTransactionId: txRef.id,
        paidAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    }

    return {
      success: true,
      status: isSuccess ? "PAID" : "PENDING",
      responseCode,
      responseMessage: hpResp.responseMessage || "",
    };
  } catch (err) {
    logger.error("[ipos-hpp] Query status error", { orderId, error: err.message });
    return { success: false, error: "Failed to query payment status." };
  }
});

module.exports = {
  createHppPaymentLink,
  iposPaymentWebhook,
  queryHppPaymentStatus,
};
