import {
  collection,
  doc,
  type CollectionReference,
  type DocumentReference,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";

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

/**
 * Merchant-scoped collection: `Merchants/{merchantId}/{resolvedName}`.
 * Accepts either old root name (e.g. `"Orders"`) or new name (`"orders"`).
 */
export function merchantCol(
  merchantId: string,
  name: string
): CollectionReference {
  return collection(db, "Merchants", merchantId, resolvedName(name));
}

/**
 * Merchant-scoped document: `Merchants/{merchantId}/{resolvedName}/{docId}`.
 */
export function merchantDoc(
  merchantId: string,
  collectionName: string,
  docId: string
): DocumentReference {
  return doc(db, "Merchants", merchantId, resolvedName(collectionName), docId);
}
