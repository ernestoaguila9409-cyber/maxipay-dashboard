const { onDocumentUpdated } = require("firebase-functions/v2/firestore");
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
          const pickupTime = after.estimatedPickupTime || undefined;
          await uberApi.acceptOrder(orderId, {
            reason: "accepted",
            pickupTime,
            externalReferenceId: String(after.orderNumber || ""),
          });
          logger.info("[uber-triggers] Order accepted on Uber", { orderId });
          break;
        }

        case "DENIED": {
          const reason = {
            explanation: after.denyReason || "Order cannot be fulfilled at this time",
            reason_code: after.denyReasonCode || "ITEM_AVAILABILITY",
          };
          await uberApi.denyOrder(orderId, reason);
          logger.info("[uber-triggers] Order denied on Uber", { orderId });
          break;
        }

        case "CANCELLED": {
          const reason = after.cancelReason || "OUT_OF_ITEMS";
          const details = after.cancelDetails || undefined;
          await uberApi.cancelOrder(orderId, reason, details);
          logger.info("[uber-triggers] Order cancelled on Uber", { orderId });
          break;
        }

        case "READY": {
          await uberApi.markOrderReady(orderId);
          logger.info("[uber-triggers] Order marked ready on Uber", { orderId });
          break;
        }

        default:
          logger.info("[uber-triggers] No Uber action for transition", {
            orderId, oldStatus, newStatus,
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
