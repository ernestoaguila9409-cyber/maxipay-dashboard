import { randomUUID } from "crypto";
import type { Firestore } from "firebase-admin/firestore";
import { Timestamp, type DocumentData } from "firebase-admin/firestore";
import {
  BUSINESS_INFO_DOC,
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  parseOnlineOrderingSettings,
  slugify,
  type OnlineOrderingSettings,
  type OnlinePaymentChoice,
} from "@/lib/onlineOrderingShared";
import {
  isMenuItemVisibleOnOnlineChannel,
  menuItemPlacementCategoryIds,
} from "@/lib/onlineMenuCuration";

export type { OnlineOrderingSettings, OnlinePaymentChoice };
export {
  BUSINESS_INFO_DOC,
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  parseOnlineOrderingSettings,
} from "@/lib/onlineOrderingShared";

export interface PublicOnlineOrderingConfig {
  enabled: boolean;
  businessName: string;
  slug: string;
  allowPayInStore: boolean;
  allowRequestTerminalFromWeb: boolean;
}

export interface OnlineMenuItem {
  id: string;
  name: string;
  description: string;
  categoryId: string;
  categoryIds: string[];
  /** Unit price in cents (online channel). */
  unitPriceCents: number;
  stock: number;
  imageUrl: string;
}

export interface OnlineMenuCategory {
  id: string;
  name: string;
  sortOrder: number;
}

function menuItemOnlinePriceCents(data: DocumentData): number {
  const rawPricing = data.pricing as Record<string, unknown> | undefined;
  const pricingOnline =
    rawPricing && typeof rawPricing.online === "number" ? rawPricing.online : null;
  if (pricingOnline != null) return Math.round(pricingOnline * 100);
  const legacy = typeof data.price === "number" ? data.price : 0;
  const rawPrices = data.prices as Record<string, number> | undefined;
  if (rawPrices && typeof rawPrices === "object") {
    const first = Object.values(rawPrices)[0];
    if (typeof first === "number") return Math.round(first * 100);
  }
  return Math.round(legacy * 100);
}

function asRecord(data: DocumentData): Record<string, unknown> {
  return data as unknown as Record<string, unknown>;
}

export async function loadPublicOnlineOrderingConfig(
  db: Firestore
): Promise<PublicOnlineOrderingConfig> {
  const [bizSnap, ooSnap] = await Promise.all([
    db.collection(SETTINGS_COLLECTION).doc(BUSINESS_INFO_DOC).get(),
    db.collection(SETTINGS_COLLECTION).doc(ONLINE_ORDERING_SETTINGS_DOC).get(),
  ]);
  const bizName =
    (bizSnap.exists ? bizSnap.get("businessName") : null)?.toString().trim() ||
    "Restaurant";
  const oo = parseOnlineOrderingSettings(
    ooSnap.data() as Record<string, unknown> | undefined
  );
  const slug = oo.onlineOrderingSlug || slugify(bizName);
  return {
    enabled: oo.enabled,
    businessName: bizName,
    slug,
    allowPayInStore: oo.allowPayInStore,
    allowRequestTerminalFromWeb: oo.allowRequestTerminalFromWeb,
  };
}

export async function loadOnlineMenu(
  db: Firestore
): Promise<{ categories: OnlineMenuCategory[]; items: OnlineMenuItem[] }> {
  const [catSnap, itemSnap, ooSnap] = await Promise.all([
    db.collection("Categories").get(),
    db.collection("MenuItems").get(),
    db.collection(SETTINGS_COLLECTION).doc(ONLINE_ORDERING_SETTINGS_DOC).get(),
  ]);
  const oo = parseOnlineOrderingSettings(
    ooSnap.data() as Record<string, unknown> | undefined
  );

  const categories: OnlineMenuCategory[] = catSnap.docs.map((d) => {
    const data = d.data();
    return {
      id: d.id,
      name: (data.name as string) || "Category",
      sortOrder: typeof data.sortOrder === "number" ? data.sortOrder : 0,
    };
  });
  categories.sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));

  const items: OnlineMenuItem[] = [];
  for (const d of itemSnap.docs) {
    const data = d.data();
    const rec = asRecord(data);
    if (!isMenuItemVisibleOnOnlineChannel(d.id, rec, oo)) continue;
    const unitPriceCents = menuItemOnlinePriceCents(data);
    const rawCategoryIds = menuItemPlacementCategoryIds(rec);
    const categoryId =
      rawCategoryIds[0] || (typeof data.categoryId === "string" ? data.categoryId : "");
    items.push({
      id: d.id,
      name: (data.name as string) || "Item",
      description: typeof data.description === "string" ? data.description : "",
      categoryId,
      categoryIds: rawCategoryIds.length > 0 ? rawCategoryIds : categoryId ? [categoryId] : [],
      unitPriceCents,
      stock: typeof data.stock === "number" ? data.stock : 0,
      imageUrl: typeof data.imageUrl === "string" ? data.imageUrl : "",
    });
  }

  const catIdsUsed = new Set<string>();
  for (const it of items) {
    for (const cid of it.categoryIds.length > 0 ? it.categoryIds : [it.categoryId]) {
      if (cid) catIdsUsed.add(cid);
    }
  }
  const filteredCategories = categories.filter((c) => catIdsUsed.has(c.id));

  return { categories: filteredCategories, items };
}

export interface CartLineInput {
  itemId: string;
  quantity: number;
}

export interface CreateOnlineOrderResult {
  orderId: string;
  orderNumber: number;
  totalInCents: number;
  paymentChoice: OnlinePaymentChoice;
}

export class OnlineOrderValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "OnlineOrderValidationError";
  }
}

/**
 * Validates cart lines against Firestore menu (online channel), then creates `Orders` + `items`
 * in one transaction (same counter pattern as Android [OrderNumberGenerator]).
 */
export async function createOnlineOrderTransaction(
  db: Firestore,
  params: {
    lines: CartLineInput[];
    customerName: string;
    customerPhone: string;
    customerEmail: string;
    paymentChoice: OnlinePaymentChoice;
  }
): Promise<CreateOnlineOrderResult> {
  const lines = params.lines.filter((l) => l.itemId && l.quantity > 0);
  if (lines.length === 0) {
    throw new OnlineOrderValidationError("Cart is empty.");
  }

  const ooSnap = await db
    .collection(SETTINGS_COLLECTION)
    .doc(ONLINE_ORDERING_SETTINGS_DOC)
    .get();
  const oo = parseOnlineOrderingSettings(
    ooSnap.data() as Record<string, unknown> | undefined
  );

  const itemIds = [...new Set(lines.map((l) => l.itemId))];
  const refs = itemIds.map((id) => db.collection("MenuItems").doc(id));
  const snaps = await db.getAll(...refs);

  const byId = new Map<string, DocumentData>();
  for (const s of snaps) {
    if (s.exists) byId.set(s.id, s.data()!);
  }

  let totalInCents = 0;
  const resolvedLines: {
    lineKey: string;
    itemId: string;
    name: string;
    quantity: number;
    basePriceInCents: number;
    lineTotalInCents: number;
    taxIds: string[];
  }[] = [];

  for (const line of lines) {
    const data = byId.get(line.itemId);
    if (!data) throw new OnlineOrderValidationError(`Unknown menu item: ${line.itemId}`);
    if (!isMenuItemVisibleOnOnlineChannel(line.itemId, asRecord(data), oo)) {
      throw new OnlineOrderValidationError(`Item is not available online: ${line.itemId}`);
    }
    const stock = typeof data.stock === "number" ? data.stock : 0;
    if (stock > 0 && line.quantity > stock) {
      throw new OnlineOrderValidationError(`Not enough stock for "${data.name || line.itemId}".`);
    }
    const basePriceCents = menuItemOnlinePriceCents(data);
    const name = (data.name as string) || "Item";
    const taxIds = Array.isArray(data.taxIds)
      ? (data.taxIds as unknown[]).filter((x): x is string => typeof x === "string" && x.length > 0)
      : [];
    const lineTotalInCents = basePriceCents * line.quantity;
    totalInCents += lineTotalInCents;
    resolvedLines.push({
      lineKey: randomUUID(),
      itemId: line.itemId,
      name,
      quantity: line.quantity,
      basePriceInCents: basePriceCents,
      lineTotalInCents,
      taxIds,
    });
  }

  const now = Timestamp.now();
  const customerName = params.customerName.trim();
  const customerPhone = params.customerPhone.trim();
  const customerEmail = params.customerEmail.trim();

  const out = await db.runTransaction(async (t) => {
    const cRef = db.collection("Counters").doc("orderNumber");
    const cSnap = await t.get(cRef);
    const cur = (cSnap.data()?.current as number | undefined) ?? 0;
    const orderNumber = cur + 1;
    t.set(cRef, { current: orderNumber }, { merge: true });

    const orderRef = db.collection("Orders").doc();
    const orderId = orderRef.id;

    const orderFields: Record<string, unknown> = {
      orderNumber,
      employeeName: customerName || "Online order",
      status: "OPEN",
      createdAt: now,
      updatedAt: now,
      totalInCents: totalInCents,
      totalPaidInCents: 0,
      remainingInCents: totalInCents,
      orderType: "ONLINE_PICKUP",
      itemsCount: resolvedLines.length,
      orderSource: "online_ordering",
      onlinePaymentChoice: params.paymentChoice,
      customerName: customerName || "Guest",
      customerPhone: customerPhone,
      customerEmail: customerEmail,
    };

    t.set(orderRef, orderFields);

    for (const rl of resolvedLines) {
      const modifiersTotalInCents = 0;
      const unitPriceInCents = rl.basePriceInCents + modifiersTotalInCents;
      const lineDoc: Record<string, unknown> = {
        itemId: rl.itemId,
        name: rl.name,
        quantity: rl.quantity,
        basePriceInCents: rl.basePriceInCents,
        modifiersTotalInCents,
        unitPriceInCents,
        lineTotalInCents: rl.lineTotalInCents,
        modifiers: [],
        taxMode: "INHERIT",
        updatedAt: now,
        createdAt: now,
      };
      if (rl.taxIds.length > 0) lineDoc.taxIds = rl.taxIds;
      t.set(orderRef.collection("items").doc(rl.lineKey), lineDoc);
    }

    return {
      orderId,
      orderNumber,
      totalInCents: totalInCents,
      paymentChoice: params.paymentChoice,
    };
  });

  return out;
}
