const crypto = require("crypto");
const admin = require("firebase-admin");
const { onRequest } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const uberApi = require("./uber-api");

// ---------------------------------------------------------------------------
// Safe body parsing — handles object, string, and Buffer forms
// ---------------------------------------------------------------------------

function safeParseBody(req) {
  if (req.body && typeof req.body === "object" && Object.keys(req.body).length > 0) {
    return req.body;
  }
  const raw = req.rawBody || req.body;
  if (!raw) return {};
  try {
    const str = Buffer.isBuffer(raw) ? raw.toString("utf8") : String(raw);
    return JSON.parse(str);
  } catch (e) {
    logger.error("[uber] Failed to parse body", { err: e.message });
    return {};
  }
}

// ---------------------------------------------------------------------------
// Signature validation — HMAC-SHA256 per Uber Eats webhook spec
// ---------------------------------------------------------------------------

/**
 * Uber signs every webhook POST with an HMAC-SHA256 digest of the raw body
 * using the Client Secret via the `X-Uber-Signature` header (hex-encoded).
 *
 * Set the secret:  firebase functions:secrets:set UBER_CLIENT_SECRET
 *
 * While the secret is unset this function logs a warning and allows the
 * request through so you can test with curl / PowerShell during development.
 */
function validateUberSignature(req) {
  const secret = process.env.UBER_CLIENT_SECRET;
  if (!secret) {
    logger.warn("[uber] UBER_CLIENT_SECRET not set — skipping signature check (dev mode)");
    return true;
  }

  const receivedSig = (req.headers["x-uber-signature"] || "").trim();
  if (!receivedSig) {
    logger.warn("[uber] Missing X-Uber-Signature header — skipping validation");
    return true;
  }

  try {
    const rawBody = req.rawBody || Buffer.from(JSON.stringify(req.body || {}));
    const expected = crypto
      .createHmac("sha256", secret)
      .update(rawBody)
      .digest("hex");

    const sigBuf = Buffer.from(receivedSig, "hex");
    const expBuf = Buffer.from(expected, "hex");

    if (sigBuf.length !== expBuf.length) {
      logger.error("[uber] Signature length mismatch", {
        received: receivedSig.substring(0, 16) + "…",
      });
      return false;
    }

    const valid = crypto.timingSafeEqual(sigBuf, expBuf);
    if (!valid) {
      logger.error("[uber] Signature mismatch", {
        received: receivedSig.substring(0, 16) + "…",
      });
    }
    return valid;
  } catch (e) {
    logger.error("[uber] Signature validation threw", { err: e.message });
    return false;
  }
}

// ---------------------------------------------------------------------------
// Order number generator — mirrors Counters/orderNumber transaction in Android
// ---------------------------------------------------------------------------

async function nextOrderNumber(db) {
  const ref = db.collection("Counters").doc("orderNumber");
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const current = snap.exists ? (snap.data().current ?? 0) : 0;
    const next = current + 1;
    tx.set(ref, { current: next });
    return next;
  });
}

// ---------------------------------------------------------------------------
// Parse Uber payload into POS-compatible fields (safe defaults throughout)
// ---------------------------------------------------------------------------

function dollarsToCents(amount) {
  if (typeof amount === "number") return Math.round(amount * 100);
  if (typeof amount === "string") {
    const n = parseFloat(amount);
    return Number.isNaN(n) ? 0 : Math.round(n * 100);
  }
  return 0;
}

function safeString(val, fallback) {
  if (val == null) return fallback;
  const s = String(val).trim();
  return s || fallback;
}

function parseUberOrder(payload) {
  const order = payload.order || payload || {};

  const uberOrderId =
    safeString(order.id, null) ||
    safeString(order.order_id, null) ||
    safeString(payload.meta?.resource_id, null);

  const customer = order.eater || order.customer || {};
  const firstName = safeString(customer.first_name || customer.firstName, "");
  const lastName = safeString(customer.last_name || customer.lastName, "");
  const customerName = `${firstName} ${lastName}`.trim() || "Uber Customer";

  const totalRaw =
    order.total ??
    order.estimated_total ??
    order.payment?.total ??
    order.cart?.total ??
    0;
  const totalInCents = dollarsToCents(totalRaw);

  const rawItems = Array.isArray(order.items) ? order.items
    : Array.isArray(order.cart?.items) ? order.cart.items
    : [];

  const items = rawItems.map((item, idx) => {
    const name = safeString(item.title || item.name, `Item ${idx + 1}`);
    const quantity = Number(item.quantity) || 1;
    const priceRaw =
      item.price?.unit_price ?? item.price?.total ?? item.price ?? 0;
    const unitPriceInCents = dollarsToCents(priceRaw);
    return {
      name,
      quantity,
      basePriceInCents: unitPriceInCents,
      modifiersTotalInCents: 0,
      unitPriceInCents,
      lineTotalInCents: unitPriceInCents * quantity,
      modifiers: [],
      taxMode: "INHERIT",
    };
  });

  return { uberOrderId, customerName, totalInCents, items };
}

// ---------------------------------------------------------------------------
// Webhook event log — every inbound call is persisted for audit / debugging
// ---------------------------------------------------------------------------

async function logWebhookEvent(db, { eventType, eventId, uberOrderId, status, error }) {
  try {
    await db.collection("UberWebhookEvents").add({
      eventType: eventType || "unknown",
      eventId: eventId || null,
      uberOrderId: uberOrderId || null,
      status: status || "unknown",
      error: error || null,
      receivedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
  } catch (e) {
    logger.warn("[uber] Failed to log webhook event", { err: e.message });
  }
}

// ---------------------------------------------------------------------------
// Handlers for each event type
// ---------------------------------------------------------------------------

async function handleOrderCreated(db, payload, rawPayload) {
  const t0 = Date.now();
  logger.info("[uber] handleOrderCreated — start");

  const { uberOrderId, customerName, totalInCents, items } =
    parseUberOrder(payload);

  if (!uberOrderId) {
    logger.error("[uber] handleOrderCreated — no order ID in payload, aborting");
    return;
  }
  logger.info("[uber] handleOrderCreated — orderId:", uberOrderId);

  const orderRef = db.collection("Orders").doc(uberOrderId);

  const t1 = Date.now();
  const existing = await orderRef.get();
  logger.info("[uber] timing: orderRef.get()", { ms: Date.now() - t1, exists: existing.exists });
  if (existing.exists) {
    logger.info("[uber] handleOrderCreated — duplicate, skipping:", uberOrderId);
    return;
  }

  const t2 = Date.now();
  const orderNumber = await nextOrderNumber(db);
  logger.info("[uber] timing: nextOrderNumber()", { ms: Date.now() - t2, orderNumber });

  const now = admin.firestore.FieldValue.serverTimestamp();
  const subtotalInCents = items.reduce((s, i) => s + i.lineTotalInCents, 0);

  const orderData = {
    orderNumber,
    employeeName: "Uber Eats",
    status: "OPEN",
    source: "UBER_EATS",
    createdAt: now,
    updatedAt: now,
    totalInCents: totalInCents || 0,
    subtotalInCents: subtotalInCents || 0,
    totalPaidInCents: totalInCents || 0,
    remainingInCents: 0,
    orderType: "UBER_EATS",
    orderSource: "uber_eats",
    itemsCount: items.length,
    customerName,
    uberOrderId,
    uberRawPayload: rawPayload || {},
  };

  const batch = db.batch();
  batch.set(orderRef, orderData);

  items.forEach((item, idx) => {
    const itemRef = orderRef.collection("items").doc(`uber_item_${idx}`);
    batch.set(itemRef, { ...item, createdAt: now, updatedAt: now });
  });

  const t3 = Date.now();
  await batch.commit();
  logger.info("[uber] timing: batch.commit()", { ms: Date.now() - t3 });
  logger.info("[uber] Order stored:", uberOrderId, "orderNumber:", orderNumber,
    "items:", items.length, "total:", totalInCents,
    "totalElapsedMs:", Date.now() - t0);

  // NOTE: Uber API enrichment (getOrderDetails) is now performed by a separate
  // Firestore-triggered function (uberEnrichNewOrder) so that the webhook can
  // return 200 OK to Uber within the required time window.
}

async function handleOrderUpdated(db, payload) {
  logger.info("[uber] handleOrderUpdated — start");

  const order = payload.order || payload || {};
  const uberOrderId =
    safeString(order.id, null) ||
    safeString(order.order_id, null) ||
    safeString(payload.meta?.resource_id, null);

  if (!uberOrderId) {
    logger.error("[uber] handleOrderUpdated — no order ID, aborting");
    return;
  }

  const orderRef = db.collection("Orders").doc(uberOrderId);
  const existing = await orderRef.get();
  if (!existing.exists) {
    logger.warn("[uber] handleOrderUpdated — order not found:", uberOrderId);
    return;
  }

  const status = safeString(order.current_state || order.status, null);
  const updates = {
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    uberLastPayload: payload,
  };
  if (status) updates.uberStatus = status;

  const newTotal =
    order.total ?? order.estimated_total ?? order.payment?.total ?? null;
  if (newTotal !== null) {
    updates.totalInCents = dollarsToCents(newTotal);
  }

  await orderRef.update(updates);
  logger.info("[uber] Order updated:", uberOrderId, "uberStatus:", status);
}

async function handleOrderCancelled(db, payload) {
  logger.info("[uber] handleOrderCancelled — start");

  const order = payload.order || payload || {};
  const uberOrderId =
    safeString(order.id, null) ||
    safeString(order.order_id, null) ||
    safeString(payload.meta?.resource_id, null);

  if (!uberOrderId) {
    logger.error("[uber] handleOrderCancelled — no order ID, aborting");
    return;
  }

  const orderRef = db.collection("Orders").doc(uberOrderId);
  const existing = await orderRef.get();
  if (!existing.exists) {
    logger.warn("[uber] handleOrderCancelled — order not found:", uberOrderId);
    return;
  }

  const reason = safeString(order.cancellation_reason || order.cancel_reason, null);

  await orderRef.update({
    status: "CANCELLED",
    _uberStatusSynced: "CANCELLED",
    cancelledAt: admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    ...(reason && { cancelReason: reason }),
  });
  logger.info("[uber] Order cancelled:", uberOrderId, reason ? `reason: ${reason}` : "");
}

/**
 * orders.scheduled.notification — eater placed a future order.
 * We persist the order in Firestore exactly like a normal create, but tag
 * it with `scheduled: true` and the requested fulfillment time so the POS
 * can choose when to show / accept it.
 */
async function handleOrderScheduled(db, payload, rawPayload) {
  logger.info("[uber] handleOrderScheduled — start");
  await handleOrderCreated(db, payload, rawPayload);

  const order = payload.order || payload || {};
  const uberOrderId =
    safeString(order.id, null) ||
    safeString(order.order_id, null) ||
    safeString(payload.meta?.resource_id, null);

  if (!uberOrderId) return;

  const fulfillAt =
    order.estimated_ready_for_pickup_at ||
    order.scheduled_for ||
    order.placed_at ||
    null;

  await db.collection("Orders").doc(uberOrderId).update({
    scheduled: true,
    scheduledFor: fulfillAt,
    uberStatus: "SCHEDULED",
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  logger.info("[uber] Order scheduled:", uberOrderId, fulfillAt || "");
}

/**
 * orders.fulfillment_issue.resolution — Uber tells us the eater (or Uber
 * support) resolved a fulfillment issue. We persist the resolution payload
 * so the POS can surface it; actual response (if any) goes through the
 * `resolveFulfillmentIssue` callable.
 */
async function handleFulfillmentIssueResolution(db, payload) {
  logger.info("[uber] handleFulfillmentIssueResolution — start");

  const order = payload.order || payload || {};
  const uberOrderId =
    safeString(order.id, null) ||
    safeString(order.order_id, null) ||
    safeString(payload.meta?.resource_id, null);

  if (!uberOrderId) {
    logger.error("[uber] handleFulfillmentIssueResolution — no order ID");
    return;
  }

  const orderRef = db.collection("Orders").doc(uberOrderId);
  const existing = await orderRef.get();
  if (!existing.exists) {
    logger.warn("[uber] handleFulfillmentIssueResolution — order not found:",
      uberOrderId);
    return;
  }

  await orderRef.update({
    fulfillmentIssue: payload.fulfillment_issue || payload.resolution || payload,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  logger.info("[uber] Fulfillment issue resolution stored:", uberOrderId);
}

/**
 * orders.cancel.failure / orders.release.failure — Uber rejected our
 * cancel/release call. We log it on the order so the POS UI can show
 * an error and let the cashier retry.
 */
async function handleNegativeAck(db, payload, kind) {
  const order = payload.order || payload || {};
  const uberOrderId =
    safeString(order.id, null) ||
    safeString(order.order_id, null) ||
    safeString(payload.meta?.resource_id, null);

  if (!uberOrderId) {
    logger.error(`[uber] ${kind} — no order ID`);
    return;
  }

  const orderRef = db.collection("Orders").doc(uberOrderId);
  const existing = await orderRef.get();
  if (!existing.exists) return;

  const failureField = kind === "cancel.failure" ? "cancelFailure" : "releaseFailure";
  await orderRef.update({
    [failureField]: payload.failure || payload.error || payload,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  logger.warn(`[uber] ${kind} stored:`, uberOrderId);
}

// ---------------------------------------------------------------------------
// Async processing — runs after HTTP 200 is sent, never throws to caller
// ---------------------------------------------------------------------------

async function processWebhookAsync(body) {
  const eventType = safeString(body.event_type || body.type, "unknown");
  const eventId = safeString(body.event_id || body.id, "unknown");
  const order = body.order || body || {};
  const orderId =
    safeString(order.id, null) ||
    safeString(order.order_id, null) ||
    safeString(body.meta?.resource_id, null);

  logger.info("[uber] Processing event", { eventType, eventId, orderId });

  if (eventType === "unknown") {
    logger.warn("[uber] No event_type in payload — logging and exiting");
    await logWebhookEvent(admin.firestore(), {
      eventType, eventId, uberOrderId: orderId, status: "skipped_no_event_type",
    });
    return;
  }

  const db = admin.firestore();

  try {
    switch (eventType) {
      case "orders.notification":
      case "orders.created":
      case "order.created":
        await handleOrderCreated(db, body, body);
        break;

      case "orders.scheduled.notification":
      case "orders.scheduled":
        await handleOrderScheduled(db, body, body);
        break;

      case "orders.updated":
      case "order.updated":
        await handleOrderUpdated(db, body);
        break;

      case "orders.cancel":
      case "orders.cancelled":
      case "order.cancelled":
      case "orders.cancel.notification":
        await handleOrderCancelled(db, body);
        break;

      case "orders.cancel.failure":
        await handleNegativeAck(db, body, "cancel.failure");
        break;

      case "orders.release.failure":
        await handleNegativeAck(db, body, "release.failure");
        break;

      case "orders.fulfillment_issue.resolution":
      case "orders.fulfillment_issue":
        await handleFulfillmentIssueResolution(db, body);
        break;

      default:
        logger.info("[uber] Unhandled event type:", eventType);
    }

    await logWebhookEvent(db, {
      eventType, eventId, uberOrderId: orderId, status: "processed",
    });
    logger.info("[uber] Processing complete", { eventType, orderId });
  } catch (err) {
    logger.error("[uber] Error occurred during processing", {
      eventType, orderId, err: err.message, stack: err.stack,
    });
    await logWebhookEvent(db, {
      eventType, eventId, uberOrderId: orderId, status: "error", error: err.message,
    });
  }
}

// ---------------------------------------------------------------------------
// Main webhook endpoint
// ---------------------------------------------------------------------------

/**
 * HTTPS endpoint for Uber Eats webhooks.
 *
 * Deploy:
 *   firebase deploy --only functions:uberWebhook
 *
 * Test with curl:
 *   curl -X POST https://us-central1-restaurantapp-180da.cloudfunctions.net/uberWebhook \
 *     -H "Content-Type: application/json" \
 *     -d '{"event_type":"orders.notification","event_id":"evt_test_001","meta":{"resource_id":"test-order-001"},"order":{"id":"test-order-001","eater":{"first_name":"John","last_name":"Doe"},"total":24.50,"items":[{"title":"Cheeseburger","quantity":2,"price":{"unit_price":8.50}},{"title":"Fries","quantity":1,"price":{"unit_price":4.50}}]}}'
 *
 * Test with PowerShell:
 *   Invoke-RestMethod -Method POST -Uri "https://us-central1-restaurantapp-180da.cloudfunctions.net/uberWebhook" `
 *     -ContentType "application/json" `
 *     -Body '{"event_type":"orders.notification","event_id":"evt_test_002","meta":{"resource_id":"ps-order-001"},"order":{"id":"ps-order-001","eater":{"first_name":"Jane","last_name":"Smith"},"total":15.00,"items":[{"title":"Tacos","quantity":3,"price":{"unit_price":5.00}}]}}'
 */
exports.uberWebhook = onRequest(
  { maxInstances: 10, cors: false },
  async (req, res) => {
    try {
      logger.info("[uber] Webhook received", {
        method: req.method,
        contentType: req.headers["content-type"] || "none",
        hasRawBody: !!req.rawBody,
        bodyType: typeof req.body,
      });

      if (req.method !== "POST") {
        logger.warn("[uber] Rejected non-POST request:", req.method);
        res.status(405).json({ error: "Method not allowed" });
        return;
      }

      if (!validateUberSignature(req)) {
        logger.error("[uber] Signature validation failed — rejecting");
        res.status(401).json({ error: "Invalid signature" });
        return;
      }

      const body = safeParseBody(req);

      logger.info("[uber] Body parsed", {
        event_type: body.event_type || body.type || "missing",
        event_id: body.event_id || body.id || "missing",
        hasOrder: !!body.order,
        orderId: body.order?.id || body.meta?.resource_id || "missing",
        bodyKeys: Object.keys(body),
      });

      // Process synchronously and AWAIT before responding. Cloud Run can
      // terminate the container after the HTTP response is sent, dropping
      // any pending fire-and-forget work. By awaiting, we guarantee the
      // order is persisted in Firestore before Uber considers the webhook
      // delivered. Slow API enrichment is offloaded to a separate Firestore
      // trigger to keep this fast.
      try {
        await processWebhookAsync(body);
      } catch (procErr) {
        logger.error("[uber] Error in webhook processing", {
          err: procErr.message,
          stack: procErr.stack,
        });
      }

      res.status(200).json({ status: "ok" });
    } catch (outerErr) {
      logger.error("[uber] Critical error in webhook handler", {
        err: outerErr.message,
        stack: outerErr.stack,
      });
      if (!res.headersSent) {
        res.status(200).json({ status: "ok" });
      }
    }
  },
);
