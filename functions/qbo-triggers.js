const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const qboSync = require("./qbo-sync");
const { merchantCol, merchantIdFromAuth } = require("./merchant-firestore");

function shouldSyncClosedOrder(before, after) {
  if (!after || after.status !== "CLOSED") return false;
  if (after.qboSalesReceiptId) return false;

  const remaining = Number(after.remainingInCents);
  if (Number.isFinite(remaining) && remaining > 0) return false;

  if (!before || before.status !== "CLOSED") return true;

  return false;
}

function buildQboTriggers(secrets) {
  const qboOnOrderClosed = onDocumentWritten(
    {
      document: "Merchants/{merchantId}/orders/{orderId}",
      secrets,
      maxInstances: 10,
    },
    async (event) => {
    const after = event.data?.after?.exists ? event.data.after.data() : null;
    const before = event.data?.before?.exists ? event.data.before.data() : null;

    if (!shouldSyncClosedOrder(before, after)) return;

    const { merchantId, orderId } = event.params;

    try {
      await qboSync.syncOrderToQuickBooks(merchantId, orderId, after);
    } catch (err) {
      logger.error("[qbo-triggers] SalesReceipt sync failed", {
        merchantId,
        orderId,
        err: err.message,
        error: err.error,
      });
      await qboSync.markSyncError(merchantId, orderId, err);
    }
    },
  );

  /**
   * Manual retry: sync one closed order to QuickBooks (dashboard / support).
   */
  const qboSyncOrder = onCall(
    { secrets, maxInstances: 5 },
    async (request) => {
    const merchantId = merchantIdFromAuth(request);
    const orderId = request.data?.orderId;
    if (!orderId || typeof orderId !== "string") {
      throw new HttpsError("invalid-argument", "orderId is required");
    }

    const snap = await merchantCol(merchantId, "Orders").doc(orderId).get();
    if (!snap.exists) {
      throw new HttpsError("not-found", "Order not found");
    }

    const order = snap.data();
    if (order.status !== "CLOSED") {
      throw new HttpsError(
        "failed-precondition",
        `Order status is ${order.status}; only CLOSED orders can sync`,
      );
    }

    try {
      const result = await qboSync.syncOrderToQuickBooks(merchantId, orderId, order);
      return { ok: true, ...result };
    } catch (err) {
      await qboSync.markSyncError(merchantId, orderId, err);
      throw new HttpsError(
        "internal",
        err.error_description || err.message || "QuickBooks sync failed",
      );
    }
    },
  );

  return { qboOnOrderClosed, qboSyncOrder };
}

module.exports = { buildQboTriggers };
