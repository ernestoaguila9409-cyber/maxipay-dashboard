const admin = require("firebase-admin");

/**
 * Old root-level name -> new subcollection name under `Merchants/{mid}/`.
 */
const NAME_MAP = {
  Orders: "orders",
  Categories: "categories",
  subcategories: "subcategories",
  MenuItems: "menuItems",
  ModifierGroups: "modifierGroups",
  ModifierOptions: "modifierOptions",
  ItemModifierGroups: "itemModifierGroups",
  menus: "menus",
  menuSchedules: "menuSchedules",
  Employees: "employees",
  Customers: "customers",
  Taxes: "taxes",
  discounts: "discounts",
  Printers: "printers",
  Batches: "batches",
  Transactions: "transactions",
  cashLogs: "cashLogs",
  kds_devices: "kds_devices",
  payment_terminals: "payment_terminals",
  Sections: "sections",
  tableLayouts: "tableLayouts",
  Tables: "tables",
  PosDevices: "posDevices",
  DeviceActivations: "deviceActivations",
  Reservations: "reservations",
  Settings: "settings",
  settings: "settings",
  OnlineHeroSlides: "onlineHeroSlides",
  RemotePaymentCommands: "remotePaymentCommands",
  OnlineTerminalPaymentRequests: "onlineTerminalPaymentRequests",
  Counters: "counters",
};

function resolvedName(name) {
  return NAME_MAP[name] || name;
}

/**
 * Merchant-scoped collection: `Merchants/{merchantId}/{resolvedName}`.
 */
function merchantCol(merchantId, name) {
  const db = admin.firestore();
  return db.collection("Merchants").doc(merchantId).collection(resolvedName(name));
}

/**
 * Merchant-scoped document: `Merchants/{merchantId}/{resolvedName}/{docId}`.
 */
function merchantDoc(merchantId, collectionName, docId) {
  return merchantCol(merchantId, collectionName).doc(docId);
}

/**
 * Extract merchantId from a callable request's auth token.
 * Falls back to request.data.merchantId for clients that pass it explicitly
 * (e.g. anonymous-auth POS devices).
 * Returns the merchantId string or throws if not found.
 */
function merchantIdFromAuth(request) {
  const mid =
    request.auth?.token?.merchantId ||
    (typeof request.data?.merchantId === "string" ? request.data.merchantId.trim() : "");
  if (!mid || typeof mid !== "string" || !mid.trim()) {
    throw new Error("No merchantId in auth token");
  }
  return mid.trim();
}

module.exports = {
  NAME_MAP,
  resolvedName,
  merchantCol,
  merchantDoc,
  merchantIdFromAuth,
};
