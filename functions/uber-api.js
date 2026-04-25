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
 * @param {string} [reason] e.g. "OUT_OF_ITEMS"
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
  getOrderDetails,
  acceptOrder,
  denyOrder,
  cancelOrder,
  markOrderReady,
  uploadMenu,
  createReport,
};
