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
      return null;
    }
    return res.json();
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
// Order lifecycle
// ---------------------------------------------------------------------------

/** GET /v2/eats/order/{orderId} — full order details. */
async function getOrderDetails(orderId) {
  return uberFetch("GET", `/v2/eats/order/${orderId}`);
}

/**
 * POST /v1/eats/orders/{orderId}/accept_pos_order — accept an order.
 * @param {string} orderId
 * @param {object} [opts]
 * @param {string} [opts.reason]
 * @param {number} [opts.pickupTime] Unix timestamp
 * @param {string} [opts.externalReferenceId]
 */
async function acceptOrder(orderId, opts = {}) {
  const payload = {};
  if (opts.reason) payload.reason = opts.reason;
  if (opts.pickupTime) payload.pickup_time = opts.pickupTime;
  if (opts.externalReferenceId) payload.external_reference_id = opts.externalReferenceId;

  return uberFetch("POST", `/v1/eats/orders/${orderId}/accept_pos_order`, {
    body: payload,
    expectStatus: [204, 200],
  });
}

/**
 * POST /v1/eats/orders/{orderId}/deny_pos_order — deny an order.
 * @param {string} orderId
 * @param {object} reason  { explanation, reason_code, ... }
 */
async function denyOrder(orderId, reason = {}) {
  const payload = {
    reason: {
      explanation: reason.explanation || "Order cannot be fulfilled",
      reason_code: reason.reason_code || "ITEM_AVAILABILITY",
      ...(reason.out_of_stock_items && { out_of_stock_items: reason.out_of_stock_items }),
      ...(reason.invalid_items && { invalid_items: reason.invalid_items }),
    },
  };

  return uberFetch("POST", `/v1/eats/orders/${orderId}/deny_pos_order`, {
    body: payload,
    expectStatus: [204, 200],
  });
}

/**
 * POST /v1/eats/orders/{orderId}/cancel — cancel a live order.
 * @param {string} orderId
 * @param {string} [reason] e.g. "OUT_OF_ITEMS", "KITCHEN_CLOSED"
 * @param {string} [details]
 */
async function cancelOrder(orderId, reason, details) {
  const payload = {};
  if (reason) payload.reason = reason;
  if (details) payload.details = details;
  payload.cancelling_party = "MERCHANT";

  return uberFetch("POST", `/v1/eats/orders/${orderId}/cancel`, {
    body: payload,
    expectStatus: [200, 204],
  });
}

/** POST /v1/eats/orders/{orderId}/ready — mark order as ready for pickup. */
async function markOrderReady(orderId) {
  return uberFetch("POST", `/v1/eats/orders/${orderId}/ready`, {
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
