const { onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const uberApi = require("./uber-api");

const STORE_UUID = "5e3578ad-cddd-4f48-bfd9-8ea2c2119837";

function rethrow(err, context) {
  logger.error(`[uber-callables] ${context} failed`, {
    err: err.message,
    status: err.status,
    body: err.responseBody,
  });
  throw new HttpsError("internal", err.message, {
    status: err.status,
    responseBody: err.responseBody,
  });
}

// ---------------------------------------------------------------------------
// Integration Config: Get Stores + Get Integration Details
// ---------------------------------------------------------------------------

exports.uberGetStores = onCall(async (request) => {
  logger.info("[uber-callables] uberGetStores called");

  try {
    const storesResult = await uberApi.getStores();
    logger.info("[uber-callables] getStores success", {
      storeCount: storesResult?.stores?.length ?? 0,
    });

    let integrationDetails = null;
    const storeId = request.data?.storeId || STORE_UUID;
    try {
      integrationDetails = await uberApi.getIntegrationDetails(storeId);
      logger.info("[uber-callables] getIntegrationDetails success", { storeId });
    } catch (detailErr) {
      logger.warn("[uber-callables] getIntegrationDetails failed (non-fatal)", {
        storeId, err: detailErr.message,
      });
    }

    return { stores: storesResult, integrationDetails };
  } catch (err) {
    return rethrow(err, "uberGetStores");
  }
});

// ---------------------------------------------------------------------------
// Integration Config: Activate / Update integration on a store
// ---------------------------------------------------------------------------

/**
 * Activate the Uber Eats POS integration for a store.
 *
 * Request data:
 *   { storeId?, integratorStoreId?, integratorBrandId?,
 *     integrationEnabled?: boolean, merchantManaged?: boolean }
 *
 * This is the "Activate Integration" endpoint Uber's certification team
 * asks merchants to exercise. Uber returns 204 No Content on success.
 */
exports.uberActivateIntegration = onCall(async (request) => {
  const storeId = request.data?.storeId || STORE_UUID;
  logger.info("[uber-callables] uberActivateIntegration called", { storeId });

  try {
    const result = await uberApi.activateIntegration(storeId, {
      integratorStoreId: request.data?.integratorStoreId,
      integratorBrandId: request.data?.integratorBrandId,
      integrationEnabled: request.data?.integrationEnabled,
      merchantManaged: request.data?.merchantManaged,
    });

    let integrationDetails = null;
    try {
      integrationDetails = await uberApi.getIntegrationDetails(storeId);
    } catch (e) {
      logger.warn("[uber-callables] post-activation getIntegrationDetails failed", {
        storeId, err: e.message,
      });
    }

    logger.info("[uber-callables] uberActivateIntegration success", { storeId });
    return { success: true, result, integrationDetails };
  } catch (err) {
    return rethrow(err, "uberActivateIntegration");
  }
});

// ---------------------------------------------------------------------------
// Menu: Upload / Update menu
// ---------------------------------------------------------------------------

exports.uberSyncMenu = onCall(async (request) => {
  const storeId = request.data?.storeId || STORE_UUID;
  logger.info("[uber-callables] uberSyncMenu called", { storeId });

  // If a full menu payload is provided, use it directly.
  // Otherwise build a minimal valid menu from the request data.
  let menuPayload = request.data?.menuPayload;

  if (!menuPayload) {
    menuPayload = buildMenuPayload(request.data);
  }

  try {
    const result = await uberApi.uploadMenu(storeId, menuPayload);
    logger.info("[uber-callables] uploadMenu success", { storeId });
    return { success: true, result };
  } catch (err) {
    return rethrow(err, "uberSyncMenu");
  }
});

// ---------------------------------------------------------------------------
// Menu: Update single Item / Modifier Group (Uber "Update Item/Modifier")
// ---------------------------------------------------------------------------

/**
 * Update a single menu item without re-uploading the whole menu.
 *
 * Request data:
 *   { storeId?, itemId, item: { ...partial item payload } }
 *
 * Example minimal patch (price change):
 *   { itemId: "item_123", item: { price_info: { price: 1299 } } }
 */
exports.uberUpdateItem = onCall(async (request) => {
  const storeId = request.data?.storeId || STORE_UUID;
  const itemId = request.data?.itemId;
  if (!itemId) {
    throw new HttpsError("invalid-argument", "itemId is required");
  }

  const itemPatch = request.data?.item || {
    title: { translations: { en: request.data?.title || "Test Item" } },
    price_info: { price: request.data?.priceCents ?? 999 },
  };

  logger.info("[uber-callables] uberUpdateItem called", { storeId, itemId });
  try {
    const result = await uberApi.updateMenuItem(storeId, itemId, itemPatch);
    return { success: true, result };
  } catch (err) {
    return rethrow(err, "uberUpdateItem");
  }
});

/**
 * Update a single modifier group without re-uploading the whole menu.
 *
 * Request data:
 *   { storeId?, modifierGroupId, modifierGroup: { ...partial modifier group } }
 */
exports.uberUpdateModifier = onCall(async (request) => {
  const storeId = request.data?.storeId || STORE_UUID;
  const groupId = request.data?.modifierGroupId;
  if (!groupId) {
    throw new HttpsError("invalid-argument", "modifierGroupId is required");
  }

  const groupPatch = request.data?.modifierGroup || {
    title: { translations: { en: request.data?.title || "Test Modifier Group" } },
    quantity_info: { quantity: { min_permitted: 0, max_permitted: 1 } },
  };

  logger.info("[uber-callables] uberUpdateModifier called", { storeId, groupId });
  try {
    const result = await uberApi.updateModifierGroup(storeId, groupId, groupPatch);
    return { success: true, result };
  } catch (err) {
    return rethrow(err, "uberUpdateModifier");
  }
});

/**
 * 86 (suspend) a menu item for `durationMinutes` minutes. Pass 0 to unsuspend.
 *
 * Request data: { storeId?, itemId, durationMinutes?: number, reason?: string }
 */
exports.uberSuspendItem = onCall(async (request) => {
  const storeId = request.data?.storeId || STORE_UUID;
  const itemId = request.data?.itemId;
  if (!itemId) {
    throw new HttpsError("invalid-argument", "itemId is required");
  }
  const minutes = Number(request.data?.durationMinutes ?? 60);
  const suspendUntil = minutes > 0
    ? Math.floor(Date.now() / 1000) + minutes * 60
    : 0;

  logger.info("[uber-callables] uberSuspendItem called", {
    storeId, itemId, minutes, suspendUntil,
  });
  try {
    const result = await uberApi.setItemSuspension(
      storeId, itemId, suspendUntil, request.data?.reason,
    );
    return { success: true, suspendUntil, result };
  } catch (err) {
    return rethrow(err, "uberSuspendItem");
  }
});

// ---------------------------------------------------------------------------
// Order Suite: Adjust / Release / Resolve fulfillment issue
// ---------------------------------------------------------------------------

/**
 * Adjust a live Uber order (out-of-stock substitution, modifier swap, etc.).
 * Request data: { orderId, adjustment }
 */
exports.uberAdjustOrder = onCall(async (request) => {
  const orderId = request.data?.orderId;
  if (!orderId) {
    throw new HttpsError("invalid-argument", "orderId is required");
  }
  const adjustment = request.data?.adjustment || { reason: "OUT_OF_STOCK" };

  logger.info("[uber-callables] uberAdjustOrder called", { orderId });
  try {
    const result = await uberApi.adjustOrder(orderId, adjustment);
    return { success: true, result };
  } catch (err) {
    return rethrow(err, "uberAdjustOrder");
  }
});

/**
 * Release an order back to Uber's control.
 * Request data: { orderId, reason?, details? }
 */
exports.uberReleaseOrder = onCall(async (request) => {
  const orderId = request.data?.orderId;
  if (!orderId) {
    throw new HttpsError("invalid-argument", "orderId is required");
  }
  logger.info("[uber-callables] uberReleaseOrder called", { orderId });
  try {
    const result = await uberApi.releaseOrder(orderId, {
      reason: request.data?.reason,
      details: request.data?.details,
    });
    return { success: true, result };
  } catch (err) {
    return rethrow(err, "uberReleaseOrder");
  }
});

/**
 * Resolve a fulfillment issue raised by Uber (eater request, OOS, etc.).
 * Request data: { orderId, resolution }
 */
exports.uberResolveFulfillmentIssue = onCall(async (request) => {
  const orderId = request.data?.orderId;
  if (!orderId) {
    throw new HttpsError("invalid-argument", "orderId is required");
  }
  const resolution = request.data?.resolution || {};
  logger.info("[uber-callables] uberResolveFulfillmentIssue called", { orderId });
  try {
    const result = await uberApi.resolveFulfillmentIssue(orderId, resolution);
    return { success: true, result };
  } catch (err) {
    return rethrow(err, "uberResolveFulfillmentIssue");
  }
});

/**
 * Build a minimal Uber-compatible menu payload from structured data.
 * If no items are passed, creates a single placeholder menu so the
 * endpoint is exercised during validation.
 */
function buildMenuPayload(data = {}) {
  const categories = data.categories || [{
    id: "cat_default",
    title: { translations: { en: "Menu" } },
  }];

  const items = data.items || [{
    id: "item_placeholder",
    title: { translations: { en: "Test Item" } },
    price_info: { price: 999, overrides: [] },
    tax_info: { tax_rate: 0 },
    quantity_info: { quantity: { max_permitted: 99 } },
  }];

  const menus = data.menus || [{
    id: "menu_default",
    title: { translations: { en: "Main Menu" } },
    category_ids: categories.map((c) => c.id),
    service_availability: [{
      day_of_week: "monday",
      time_periods: [{ start_time: "00:00", end_time: "23:59" }],
    }, {
      day_of_week: "tuesday",
      time_periods: [{ start_time: "00:00", end_time: "23:59" }],
    }, {
      day_of_week: "wednesday",
      time_periods: [{ start_time: "00:00", end_time: "23:59" }],
    }, {
      day_of_week: "thursday",
      time_periods: [{ start_time: "00:00", end_time: "23:59" }],
    }, {
      day_of_week: "friday",
      time_periods: [{ start_time: "00:00", end_time: "23:59" }],
    }, {
      day_of_week: "saturday",
      time_periods: [{ start_time: "00:00", end_time: "23:59" }],
    }, {
      day_of_week: "sunday",
      time_periods: [{ start_time: "00:00", end_time: "23:59" }],
    }],
  }];

  return {
    menus,
    categories: categories.map((c) => ({
      ...c,
      entities: (data.items || items).map((i) => ({ id: i.id, type: "ITEM" })),
    })),
    items,
    modifier_groups: data.modifier_groups || [],
  };
}

// ---------------------------------------------------------------------------
// Reporting: Create / Get report files
// ---------------------------------------------------------------------------

exports.uberGetReports = onCall(async (request) => {
  logger.info("[uber-callables] uberGetReports called");

  const params = {
    report_type: request.data?.reportType || "ORDERS_AND_ITEMS_REPORT",
    store_uuids: request.data?.storeIds || [STORE_UUID],
    start_date: request.data?.startDate || formatDate(daysAgo(14)),
    end_date: request.data?.endDate || formatDate(new Date()),
  };

  try {
    const result = await uberApi.createReport(params);
    logger.info("[uber-callables] createReport success");
    return { success: true, result };
  } catch (err) {
    return rethrow(err, "uberGetReports");
  }
});

function daysAgo(n) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d;
}

function formatDate(d) {
  return d.toISOString().split("T")[0];
}

// ---------------------------------------------------------------------------
// Certification harness — exercises the three endpoints Uber asks merchants
// to test (Activate Integration, Get Stores, Update Item/Modifier) plus a
// menu PUT, and returns a single per-step report for submission to Uber.
//
// Invoke once with:
//   firebase functions:shell  →  uberRunCertificationTests({ storeId: "..." })
// or from the dashboard / Postman as a callable.
// ---------------------------------------------------------------------------

exports.uberRunCertificationTests = onCall(async (request) => {
  const storeId = request.data?.storeId || STORE_UUID;
  const itemId = request.data?.itemId || "item_placeholder";
  const modifierGroupId = request.data?.modifierGroupId || null;

  logger.info("[uber-callables] uberRunCertificationTests started", {
    storeId, itemId, modifierGroupId,
  });

  const steps = [];
  const record = async (name, fn) => {
    const t0 = Date.now();
    try {
      const data = await fn();
      const elapsed = Date.now() - t0;
      steps.push({ name, ok: true, elapsedMs: elapsed, data });
      logger.info(`[cert] ${name} OK`, { elapsedMs: elapsed });
    } catch (err) {
      const elapsed = Date.now() - t0;
      steps.push({
        name, ok: false, elapsedMs: elapsed,
        error: err.message, status: err.status, body: err.responseBody,
      });
      logger.error(`[cert] ${name} FAILED`, {
        err: err.message, status: err.status, body: err.responseBody,
      });
    }
  };

  await record("getStores", () => uberApi.getStores());
  await record("activateIntegration", () => uberApi.activateIntegration(storeId, {
    integratorStoreId: request.data?.integratorStoreId,
    integratorBrandId: request.data?.integratorBrandId,
  }));
  await record("getIntegrationDetails", () => uberApi.getIntegrationDetails(storeId));
  await record("uploadMenu", () => uberApi.uploadMenu(storeId, buildMenuPayload({
    items: [{
      id: itemId,
      title: { translations: { en: "Cert Test Item" } },
      price_info: { price: 999, overrides: [] },
      tax_info: { tax_rate: 0 },
      quantity_info: { quantity: { max_permitted: 99 } },
    }],
  })));
  await record("updateMenuItem", () => uberApi.updateMenuItem(storeId, itemId, {
    title: { translations: { en: "Cert Test Item (updated)" } },
    price_info: { price: 1099 },
  }));
  if (modifierGroupId) {
    await record("updateModifierGroup", () => uberApi.updateModifierGroup(
      storeId, modifierGroupId,
      {
        title: { translations: { en: "Cert Test Modifier Group" } },
        quantity_info: { quantity: { min_permitted: 0, max_permitted: 1 } },
      },
    ));
  }
  await record("suspendItem(60min)", () => uberApi.setItemSuspension(
    storeId, itemId, Math.floor(Date.now() / 1000) + 3600, "OUT_OF_STOCK",
  ));
  await record("unsuspendItem", () => uberApi.setItemSuspension(storeId, itemId, 0));

  const summary = {
    storeId,
    total: steps.length,
    passed: steps.filter((s) => s.ok).length,
    failed: steps.filter((s) => !s.ok).length,
    steps,
  };
  logger.info("[uber-callables] uberRunCertificationTests done", {
    storeId, passed: summary.passed, failed: summary.failed,
  });
  return summary;
});
