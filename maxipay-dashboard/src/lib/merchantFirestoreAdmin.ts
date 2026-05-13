import * as admin from "firebase-admin";
import { getFirebaseAdminApp } from "./firebaseAdmin";

/**
 * Old root-level name -> new subcollection name under `Merchants/{mid}/`.
 */
const NAME_MAP: Record<string, string> = {
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

export function resolvedName(name: string): string {
  return NAME_MAP[name] ?? name;
}

function requireMerchantId(merchantId: string): string {
  const mid = merchantId.trim();
  if (!mid) {
    throw new Error("merchantId is required for Merchants/{merchantId}/... paths");
  }
  return mid;
}

/**
 * Merchant-scoped collection (admin SDK): `Merchants/{merchantId}/{resolvedName}`.
 */
export function merchantCol(
  merchantId: string,
  name: string
): admin.firestore.CollectionReference {
  getFirebaseAdminApp();
  const db = admin.firestore();
  const mid = requireMerchantId(merchantId);
  return db.collection("Merchants").doc(mid).collection(resolvedName(name));
}

/**
 * Merchant-scoped document (admin SDK): `Merchants/{merchantId}/{resolvedName}/{docId}`.
 */
export function merchantDoc(
  merchantId: string,
  collectionName: string,
  docId: string
): admin.firestore.DocumentReference {
  return merchantCol(merchantId, collectionName).doc(docId);
}
