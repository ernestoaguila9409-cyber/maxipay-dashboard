const logger = require("firebase-functions/logger");
const { getAccessToken, clearTokenCache } = require("./uber-auth");

const SANDBOX_BASE = "https://test-api.uber.com";
const PROD_BASE = "https://api.uber.com";

function getBase() {
  return process.env.UBER_ENV !== "production" ? SANDBOX_BASE : PROD_BASE;
}

// ---------------------------------------------------------------------------
// Generic fetch wrapper with automatic Bearer token + retry on 401
// ---------------------------------------------------------------------------

async function uberFetch(method, path, { body, query, expectStatus } = {}) {
  const url = new URL(path, getBase());
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v != null) url.searchParams.set(k, v);
    }
  }

  const attempt = async (retry) => {
    const token = await getAccessToken();
    const opts = {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        "Accept-Encoding": "gzip",
      },
    };

    if (body) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }

    logger.info("[uber-api] request", { method, path, retry });

    const res = await fetch(url.toString(), opts);

    if (res.status === 401 && !retry) {
      logger.warn("[uber-api] 401 — clearing token cache and retrying");
      clearTokenCache();
      return attempt(true);
    }

    const expected = expectStatus || [200, 204];
    const ok = Array.isArray(expected)
      ? expected.includes(res.status)
      : res.status === expected;

    if (!ok) {
      const text = await res.text();
      logger.error("[uber-api] unexpected status", {
        method, path, status: res.status, body: text.substring(0, 500),
      });
      const err = new Error(`Uber API ${method} ${path}: ${res.status}`);
      err.status = res.status;
      err.responseBody = text.substring(0, 1000);
      throw err;
    }

    if (res.status === 204 || res.headers.get("content-length") === "0") {
      logger.info("[uber-api] success (no body)", { method, path, status: res.status });
      return null;
    }
    const text = await res.text();
    logger.info("[uber-api] success", {
      method, path, status: res.status,
      body: text.substring(0, 500),
    });
    try { return JSON.parse(text); } catch { return null; }
  };

  return attempt(false);
}

// ---------------------------------------------------------------------------
// Integration Config
// ---------------------------------------------------------------------------

/** GET /v1/eats/stores — list all stores authorized to the app. */
async function getStores(limit = 50) {
  return uberFetch("GET", "/v1/eats/stores", { query: { limit } });
}

/** GET /v1/eats/stores/{storeId}/pos_data — integration details for a store. */
async function getIntegrationDetails(storeId) {
  return uberFetch("GET", `/v1/eats/stores/${storeId}/pos_data`);
}

/**
 * POST /v1/eats/stores/{storeId}/pos_data — activate the POS integration
 * for a store. Uber requires this before menu / order endpoints will work.
 *
 * @param {string} storeId
 * @param {object} [opts]
 * @param {string} [opts.integratorStoreId]  POS-side store identifier
 * @param {string} [opts.integratorBrandId]  POS-side brand identifier (optional)
 * @param {boolean} [opts.integrationEnabled=true]
 * @param {boolean} [opts.merchantManaged=true] Whether merchant manages menu/orders via POS
 */
async function activateIntegration(storeId, opts = {}) {
  const payload = {
    integration_enabled: opts.integrationEnabled !== false,
    merchant_managed: opts.merchantManaged !== false,
  };
  if (opts.integratorStoreId) payload.integrator_store_id = opts.integratorStoreId;
  if (opts.integratorBrandId) payload.integrator_brand_id = opts.integratorBrandId;

  return uberFetch("POST", `/v1/eats/stores/${storeId}/pos_data`, {
    body: payload,
    expectStatus: [200, 204],
  });
}

/**
 * PATCH /v1/eats/stores/{storeId}/pos_data — update integration flags
 * (e.g. temporarily disable a store).
 */
async function updateIntegrationDetails(storeId, patch = {}) {
  return uberFetch("PATCH", `/v1/eats/stores/${storeId}/pos_data`, {
    body: patch,
    expectStatus: [200, 204],
  });
}

// ---------------------------------------------------------------------------
// Order lifecycle — Order Suite API (/v1/delivery/order/)
// ---------------------------------------------------------------------------

/** GET /v1/delivery/order/{orderId} — full order details. */
async function getOrderDetails(orderId) {
  return uberFetch("GET", `/v1/delivery/order/${orderId}`, {
    query: { expand: "carts,deliveries,payment" },
  });
}

/**
 * POST /v1/delivery/order/{orderId}/accept — accept an order.
 * @param {string} orderId
 * @param {object} [opts]
 * @param {string} [opts.readyForPickupTime] RFC3339 timestamp
 * @param {string} [opts.externalReferenceId]
 * @param {string} [opts.acceptedBy]
 */
async function acceptOrder(orderId, opts = {}) {
  const payload = {};
  if (opts.readyForPickupTime) payload.ready_for_pickup_time = opts.readyForPickupTime;
  if (opts.externalReferenceId) payload.external_reference_id = opts.externalReferenceId;
  if (opts.acceptedBy) payload.accepted_by = opts.acceptedBy;
  if (opts.pickupInstructions) payload.order_pickup_instructions = opts.pickupInstructions;

  return uberFetch("POST", `/v1/delivery/order/${orderId}/accept`, {
    body: payload,
    expectStatus: [200, 204],
  });
}

/**
 * POST /v1/delivery/order/{orderId}/deny — deny an order.
 * @param {string} orderId
 * @param {object} reason  { explanation, type }
 */
async function denyOrder(orderId, reason = {}) {
  const payload = {
    deny_reason: {
      info: reason.explanation || "Order cannot be fulfilled at this time",
      type: reason.type || "ITEM_ISSUE",
    },
  };

  return uberFetch("POST", `/v1/delivery/order/${orderId}/deny`, {
    body: payload,
    expectStatus: [200, 204],
  });
}

/**
 * POST /v1/delivery/order/{orderId}/cancel — cancel a live order.
 * @param {string} orderId
 * @param {string} [reason] e.g. "ITEM_ISSUE", "STORE_CLOSED", "RESTAURANT_TOO_BUSY"
 * @param {string} [details]
 */
async function cancelOrder(orderId, reason, details) {
  const payload = {
    cancellation_reason: {
      info: details || reason || "Order cannot be fulfilled",
      type: reason || "ITEM_ISSUE",
    },
  };

  return uberFetch("POST", `/v1/delivery/order/${orderId}/cancel`, {
    body: payload,
    expectStatus: [200, 204],
  });
}

/** POST /v1/delivery/order/{orderId}/ready — mark order as ready for pickup. */
async function markOrderReady(orderId) {
  logger.info("[uber-api] markOrderReady", { orderId, path: `/v1/delivery/order/${orderId}/ready` });
  return uberFetch("POST", `/v1/delivery/order/${orderId}/ready`, {
    body: {},
    expectStatus: [200, 204],
  });
}

/**
 * POST /v1/delivery/order/{orderId}/adjust — adjust a live order (out-of-stock
 * item, modifier swap, partial refund, quantity change).
 *
 * @param {string} orderId
 * @param {object} adjustment Partial Uber adjustment payload, e.g.
 *   { reason: "OUT_OF_STOCK", items: [{ id: "item_1", quantity: { value: 0 } }] }
 */
async function adjustOrder(orderId, adjustment = {}) {
  return uberFetch("POST", `/v1/delivery/order/${orderId}/adjust`, {
    body: adjustment,
    expectStatus: [200, 204],
  });
}

/**
 * POST /v1/delivery/order/{orderId}/release — release the order back to
 * Uber's control (e.g. POS unable to fulfill but doesn't want to deny).
 *
 * @param {string} orderId
 * @param {object} [opts] { reason, details }
 */
async function releaseOrder(orderId, opts = {}) {
  const payload = {};
  if (opts.reason) payload.reason = opts.reason;
  if (opts.details) payload.details = opts.details;

  return uberFetch("POST", `/v1/delivery/order/${orderId}/release`, {
    body: payload,
    expectStatus: [200, 204],
  });
}

/**
 * POST /v1/delivery/order/{orderId}/resolve_fulfillment_issue — respond to a
 * fulfillment issue raised by Uber (e.g. eater changed their mind, OOS).
 *
 * @param {string} orderId
 * @param {object} resolution Resolution payload as defined in Uber's docs.
 */
async function resolveFulfillmentIssue(orderId, resolution = {}) {
  return uberFetch("POST", `/v1/delivery/order/${orderId}/resolve_fulfillment_issue`, {
    body: resolution,
    expectStatus: [200, 204],
  });
}

// ---------------------------------------------------------------------------
// Menu
// ---------------------------------------------------------------------------

/**
 * PUT /v2/eats/stores/{storeId}/menus — upload / replace menu.
 * The payload must include menus, categories, items, modifier_groups arrays.
 */
async function uploadMenu(storeId, menuPayload) {
  return uberFetch("PUT", `/v2/eats/stores/${storeId}/menus`, {
    body: menuPayload,
    expectStatus: [200, 204],
  });
}

/**
 * POST /v2/eats/stores/{storeId}/menus/items/{itemId} — update a single
 * menu item (price, title, description, suspension, etc.) without replacing
 * the full menu. Uber's "Update Item/Modifier" certification endpoint.
 */
async function updateMenuItem(storeId, itemId, itemPatch) {
  return uberFetch("POST", `/v2/eats/stores/${storeId}/menus/items/${itemId}`, {
    body: itemPatch,
    expectStatus: [200, 204],
  });
}

/**
 * POST /v2/eats/stores/{storeId}/menus/modifier_groups/{modifierGroupId} —
 * update a single modifier group (add/remove modifiers, change limits).
 */
async function updateModifierGroup(storeId, modifierGroupId, groupPatch) {
  return uberFetch("POST", `/v2/eats/stores/${storeId}/menus/modifier_groups/${modifierGroupId}`, {
    body: groupPatch,
    expectStatus: [200, 204],
  });
}

/**
 * 86 (suspend) a menu item for a given duration, or unsuspend it.
 *
 * Per Uber's v2 API there is **no dedicated suspension endpoint**; suspension
 * is communicated as a `suspension_info` field on the regular Update Item
 * endpoint (`POST /v2/eats/stores/{store_id}/menus/items/{item_id}`). See:
 * https://developer.uber.com/docs/eats/references/api/v2/post-eats-stores-storeid-menus-items-itemid
 *
 * @param {string} storeId
 * @param {string} itemId
 * @param {number} suspendUntilEpochSec  Unix epoch seconds when item resumes.
 *   Pass 0 (or any value <= now) to unsuspend immediately (sets `suspension: null`).
 * @param {string} [reason]
 */
async function setItemSuspension(storeId, itemId, suspendUntilEpochSec, reason) {
  const nowSec = Math.floor(Date.now() / 1000);
  const until = Math.max(0, Math.floor(suspendUntilEpochSec || 0));

  const suspensionInfo = until > nowSec
    ? {
      suspension: {
        suspend_until: until,
        ...(reason ? { reason } : {}),
      },
    }
    : { suspension: null };

  return updateMenuItem(storeId, itemId, { suspension_info: suspensionInfo });
}

// ---------------------------------------------------------------------------
// Reporting
// ---------------------------------------------------------------------------

/**
 * POST /v1/eats/report — create / retrieve a report.
 * @param {object} params  { report_type, store_ids[], start_date, end_date }
 */
async function createReport(params) {
  return uberFetch("POST", "/v1/eats/report", {
    body: params,
    expectStatus: [200, 201],
  });
}

module.exports = {
  getStores,
  getIntegrationDetails,
  activateIntegration,
  updateIntegrationDetails,
  getOrderDetails,
  acceptOrder,
  denyOrder,
  cancelOrder,
  markOrderReady,
  adjustOrder,
  releaseOrder,
  resolveFulfillmentIssue,
  uploadMenu,
  updateMenuItem,
  updateModifierGroup,
  setItemSuspension,
  createReport,
};
