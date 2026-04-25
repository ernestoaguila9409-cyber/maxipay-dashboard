const { onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const uberApi = require("./uber-api");

const STORE_UUID = "5e3578ad-cddd-4f48-bfd9-8ea2c2119837";

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
    logger.error("[uber-callables] uberGetStores failed", { err: err.message });
    throw new HttpsError("internal", err.message);
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
    logger.error("[uber-callables] uploadMenu failed", {
      storeId, err: err.message, body: err.responseBody,
    });
    throw new HttpsError("internal", err.message);
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
    logger.error("[uber-callables] createReport failed", {
      err: err.message, body: err.responseBody,
    });
    throw new HttpsError("internal", err.message);
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
