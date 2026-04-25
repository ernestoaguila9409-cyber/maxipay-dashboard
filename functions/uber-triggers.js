const { onDocumentUpdated, onDocumentCreated } = require("firebase-functions/v2/firestore");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const logger = require("firebase-functions/logger");
const uberApi = require("./uber-api");

/**
 * Firestore trigger: when an Uber Eats order's status changes in the POS,
 * relay the status transition to Uber's API.
 *
 * Transition map:
 *   OPEN → ACCEPTED   =>  acceptOrder
 *   OPEN → DENIED     =>  denyOrder
 *   * → CANCELLED     =>  cancelOrder
 *   ACCEPTED → READY  =>  markOrderReady
 */
exports.uberOnOrderStatusChange = onDocumentUpdated(
  "Orders/{orderId}",
  async (event) => {
    const before = event.data.before.data();
    const after = event.data.after.data();

    if (after.orderType !== "UBER_EATS") return;

    const oldStatus = before.status;
    const newStatus = after.status;
    if (oldStatus === newStatus) return;

    const orderId = event.params.orderId;
    logger.info("[uber-triggers] Status change detected", {
      orderId, oldStatus, newStatus,
    });

    // Guard: don't relay changes that originated from the webhook itself
    // (e.g. webhook sets CANCELLED — we don't need to call cancelOrder back)
    if (after._uberStatusSynced === newStatus) {
      logger.info("[uber-triggers] Skipping — status was set by Uber webhook", { orderId });
      return;
    }

    try {
      switch (newStatus) {
        case "ACCEPTED": {
          await uberApi.acceptOrder(orderId, {
            externalReferenceId: String(after.orderNumber || ""),
            acceptedBy: after.employeeName || "POS",
          });
          logger.info("[uber-triggers] Order accepted on Uber", { orderId });
          break;
        }

        case "DENIED": {
          await uberApi.denyOrder(orderId, {
            explanation: after.denyReason || "Order cannot be fulfilled at this time",
            type: "ITEM_ISSUE",
          });
          logger.info("[uber-triggers] Order denied on Uber", { orderId });
          break;
        }

        case "CANCELLED": {
          const reason = after.cancelReason || "ITEM_ISSUE";
          const details = after.cancelDetails || "Order cancelled by merchant";
          await uberApi.cancelOrder(orderId, reason, details);
          logger.info("[uber-triggers] Order cancelled on Uber", { orderId });
          break;
        }

        case "READY": {
          logger.info("[uber-triggers] Calling markOrderReady", { orderId, oldStatus });
          await uberApi.markOrderReady(orderId);
          logger.info("[uber-triggers] Order marked ready on Uber", { orderId });
          break;
        }

        default:
          logger.info("[uber-triggers] No Uber action for transition", {
            orderId, oldStatus, newStatus,
          });
      }

      // Fetch the order details after the transition so we can see
      // what state Uber thinks the order is actually in. This tells us
      // whether the Uber simulator UI lag is real, or our call did nothing.
      try {
        const verify = await uberApi.getOrderDetails(orderId);
        const order = verify?.order || verify;
        logger.info("[uber-triggers] Verify order state after transition", {
          orderId,
          requestedStatus: newStatus,
          uberState: order?.state,
          uberStatus: order?.status,
          preparationStatus: order?.preparation_status,
        });
      } catch (verifyErr) {
        logger.warn("[uber-triggers] Failed to verify order state", {
          orderId, err: verifyErr.message,
        });
      }
    } catch (err) {
      logger.error("[uber-triggers] Failed to sync status to Uber", {
        orderId, oldStatus, newStatus, err: err.message,
        responseBody: err.responseBody || null,
      });
    }
  },
);

/**
 * Firestore trigger: when a new Uber Eats order is created in Firestore
 * (by the webhook), fetch the full order details from Uber and enrich
 * the order document.
 *
 * This was previously done inline in the webhook handler, but it added
 * 11+ seconds to the webhook response time and risked having the work
 * dropped when the Cloud Run container terminated. Running it here makes
 * it durable: if the function fails, it can be retried without losing
 * the original webhook event.
 */
exports.uberEnrichNewOrder = onDocumentCreated(
  "Orders/{orderId}",
  async (event) => {
    const data = event.data?.data();
    if (!data) return;
    if (data.orderType !== "UBER_EATS") return;

    const orderId = event.params.orderId;
    logger.info("[uber-enrich] New Uber order detected, enriching", { orderId });

    try {
      const response = await uberApi.getOrderDetails(orderId);
      const fullOrder = response?.order || response;
      if (!fullOrder) {
        logger.warn("[uber-enrich] No order data returned", { orderId });
        return;
      }

      const enrichment = {
        uberFullOrder: fullOrder,
        updatedAt: FieldValue.serverTimestamp(),
      };
      const store = fullOrder.store;
      if (store) enrichment.uberStoreId = store.store_id || store.id || null;
      const customer =
        fullOrder.eater ||
        (fullOrder.customers && fullOrder.customers[0]) ||
        {};
      const phone = customer.phone?.number || customer.phone || null;
      if (phone) enrichment.customerPhone = phone;

      const db = getFirestore();
      await db.collection("Orders").doc(orderId).update(enrichment);
      logger.info("[uber-enrich] Order enriched", { orderId });
    } catch (err) {
      logger.warn("[uber-enrich] Failed to enrich order (non-fatal)", {
        orderId, err: err.message,
      });
    }
  },
);
