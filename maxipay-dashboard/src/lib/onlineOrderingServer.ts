import { randomUUID } from "crypto";
import type { Firestore } from "firebase-admin/firestore";
import { Timestamp, type DocumentData } from "firebase-admin/firestore";
import {
  BUSINESS_INFO_DOC,
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  formatPrepTimeRange,
  isStoreCurrentlyOpen,
  parseOnlineOrderingSettings,
  slugify,
  type OnlineOrderingSettings,
  type OnlinePaymentChoice,
} from "@/lib/onlineOrderingShared";
import {
  HERO_SLIDES_COLLECTION,
  HERO_SLIDES_MAX,
  parseHeroSlide,
  type HeroSlide,
  type PublicStorefront,
} from "@/lib/storefrontShared";
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
  logoUrl: string;
  slug: string;
  isOpen: boolean;
  prepTimeMinutes: number;
  prepTimeLabel: string;
  allowPayInStore: boolean;
  allowPayOnlineHpp: boolean;
}

export interface OnlineModifierOption {
  id: string;
  name: string;
  /** Additional charge in dollars (same as Firestore / POS). */
  price: number;
  triggersModifierGroupIds: string[];
}

export interface OnlineModifierGroup {
  id: string;
  name: string;
  required: boolean;
  minSelection: number;
  maxSelection: number;
  /** "ADD" (default) or "REMOVE" (e.g. "No onions"). */
  groupType: string;
  options: OnlineModifierOption[];
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
  /** True when the owner explicitly featured this item from the dashboard. */
  isFeatured: boolean;
  /** Modifier group document ids, in display order (see `ModifierGroups`). */
  modifierGroupIds: string[];
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

function parseModifierGroupDoc(id: string, data: DocumentData): OnlineModifierGroup {
  const rawOptions = Array.isArray(data.options) ? data.options : [];
  const options: OnlineModifierOption[] = rawOptions.map((o: Record<string, unknown>, i: number) => ({
    id: (o.id as string) || `opt_${i}`,
    name: (o.name as string) || "",
    price: typeof o.price === "number" ? o.price : 0,
    triggersModifierGroupIds: Array.isArray(o.triggersModifierGroupIds)
      ? (o.triggersModifierGroupIds as unknown[]).filter((x): x is string => typeof x === "string" && x.length > 0)
      : [],
  }));
  return {
    id,
    name: (data.name as string) || "",
    required: Boolean(data.required),
    minSelection:
      typeof data.minSelection === "number" ? data.minSelection : data.required ? 1 : 0,
    maxSelection: typeof data.maxSelection === "number" ? data.maxSelection : 1,
    groupType: (data.groupType as string) || "ADD",
    options,
  };
}

function itemModifierGroupIds(data: DocumentData): string[] {
  const raw = data.modifierGroupIds;
  if (!Array.isArray(raw)) return [];
  return raw.filter((x): x is string => typeof x === "string" && x.length > 0);
}

/** Server-only: iPOSpays HPP credentials (Firestore `Settings/onlineOrdering`, env fallback). */
export interface IposHppCredentials {
  tpn: string;
  authToken: string;
  queryApiKey: string;
  hppBaseUrl: string;
}

/**
 * Loads TPN + auth for HPP POST and queryPaymentStatus.
 * Firestore fields `iposHppTpn` / `iposHppAuthToken` on `Settings/onlineOrdering` override env when set.
 */
export async function loadIposHppCredentials(db: Firestore): Promise<IposHppCredentials> {
  const ooSnap = await db
    .collection(SETTINGS_COLLECTION)
    .doc(ONLINE_ORDERING_SETTINGS_DOC)
    .get();
  const oo = parseOnlineOrderingSettings(
    ooSnap.data() as Record<string, unknown> | undefined
  );
  const fromFsTpn = oo.iposHppTpn.trim();
  const fromFsAuth = oo.iposHppAuthToken.trim();
  const envTpn = (process.env.IPOS_HPP_TPN || "").trim();
  const envAuth = (process.env.IPOS_HPP_AUTH_TOKEN || "").trim();
  const tpn = fromFsTpn || envTpn;
  const authToken = fromFsAuth || envAuth;
  const queryApiKey = (
    (process.env.IPOS_HPP_QUERY_API_KEY || "").trim() || authToken
  ).trim();
  const hppBaseUrl =
    (process.env.IPOS_HPP_BASE_URL || "").trim();
  return { tpn, authToken, queryApiKey, hppBaseUrl };
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
  const logoUrl =
    (bizSnap.exists ? bizSnap.get("logoUrl") : null)?.toString().trim() || "";
  const oo = parseOnlineOrderingSettings(
    ooSnap.data() as Record<string, unknown> | undefined
  );
  const slug = oo.onlineOrderingSlug || slugify(bizName);
  return {
    enabled: oo.enabled,
    businessName: bizName,
    logoUrl,
    slug,
    isOpen: isStoreCurrentlyOpen(oo),
    prepTimeMinutes: oo.prepTimeMinutes,
    prepTimeLabel: formatPrepTimeRange(oo.prepTimeMinutes),
    allowPayInStore: oo.allowPayInStore,
    allowPayOnlineHpp: oo.allowPayOnlineHpp,
  };
}

/** Lists hero slides ordered by `order ASC`, capped to [HERO_SLIDES_MAX]. */
export async function loadHeroSlides(db: Firestore): Promise<HeroSlide[]> {
  const snap = await db.collection(HERO_SLIDES_COLLECTION).get();
  const slides = snap.docs.map((d) => parseHeroSlide(d.id, d.data() as Record<string, unknown>));
  slides.sort((a, b) => a.order - b.order);
  return slides.slice(0, HERO_SLIDES_MAX);
}

/**
 * Single round-trip data the customer page needs for its hero, header, featured row
 * and payment options. Hides items the menu loader would also hide.
 */
export async function loadPublicStorefront(db: Firestore): Promise<PublicStorefront> {
  const [cfg, slides] = await Promise.all([
    loadPublicOnlineOrderingConfig(db),
    loadHeroSlides(db),
  ]);
  const ooSnap = await db
    .collection(SETTINGS_COLLECTION)
    .doc(ONLINE_ORDERING_SETTINGS_DOC)
    .get();
  const oo = parseOnlineOrderingSettings(
    ooSnap.data() as Record<string, unknown> | undefined
  );
  return {
    enabled: cfg.enabled,
    isOpen: cfg.isOpen,
    businessName: cfg.businessName,
    logoUrl: cfg.logoUrl,
    slug: cfg.slug,
    prepTimeMinutes: cfg.prepTimeMinutes,
    prepTimeLabel: cfg.prepTimeLabel,
    allowPayInStore: cfg.allowPayInStore,
    allowPayOnlineHpp: cfg.allowPayOnlineHpp,
    heroSlides: slides,
    featuredItemIds: oo.featuredItemIds,
  };
}

export async function loadOnlineMenu(
  db: Firestore
): Promise<{
  categories: OnlineMenuCategory[];
  items: OnlineMenuItem[];
  modifierGroups: OnlineModifierGroup[];
}> {
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

  const featuredSet = new Set(oo.featuredItemIds);
  const items: OnlineMenuItem[] = [];
  const modifierGroupIdSet = new Set<string>();
  for (const d of itemSnap.docs) {
    const data = d.data();
    const rec = asRecord(data);
    if (!isMenuItemVisibleOnOnlineChannel(d.id, rec, oo)) continue;
    const unitPriceCents = menuItemOnlinePriceCents(data);
    const rawCategoryIds = menuItemPlacementCategoryIds(rec);
    const categoryId =
      rawCategoryIds[0] || (typeof data.categoryId === "string" ? data.categoryId : "");
    const modifierGroupIds = itemModifierGroupIds(data);
    for (const mg of modifierGroupIds) modifierGroupIdSet.add(mg);
    items.push({
      id: d.id,
      name: (data.name as string) || "Item",
      description: typeof data.description === "string" ? data.description : "",
      categoryId,
      categoryIds: rawCategoryIds.length > 0 ? rawCategoryIds : categoryId ? [categoryId] : [],
      unitPriceCents,
      stock: typeof data.stock === "number" ? data.stock : 0,
      imageUrl: typeof data.imageUrl === "string" ? data.imageUrl : "",
      isFeatured: featuredSet.has(d.id),
      modifierGroupIds,
    });
  }

  const groupRefs = [...modifierGroupIdSet].map((id) => db.collection("ModifierGroups").doc(id));
  const groupSnaps = groupRefs.length > 0 ? await db.getAll(...groupRefs) : [];
  const modifierGroups: OnlineModifierGroup[] = [];
  for (const s of groupSnaps) {
    if (s.exists) modifierGroups.push(parseModifierGroupDoc(s.id, s.data()!));
  }
  modifierGroups.sort((a, b) => a.name.localeCompare(b.name));

  const catIdsUsed = new Set<string>();
  for (const it of items) {
    for (const cid of it.categoryIds.length > 0 ? it.categoryIds : [it.categoryId]) {
      if (cid) catIdsUsed.add(cid);
    }
  }
  const filteredCategories = categories.filter((c) => catIdsUsed.has(c.id));

  return { categories: filteredCategories, items, modifierGroups };
}

/** One chosen option in a modifier group (server resolves price / labels from Firestore). */
export interface CartModifierSelectionInput {
  groupId: string;
  optionId: string;
}

export interface CartLineInput {
  itemId: string;
  quantity: number;
  /** Required when the menu item has modifier groups with minSelection > 0. */
  modifierSelections?: CartModifierSelectionInput[];
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
 * Validates selections against the item's modifierGroupIds and group rules, then returns
 * Firestore line maps compatible with Android [OrderEngine.serializeModifier].
 */
function buildValidatedModifiersForLine(
  itemName: string,
  itemModifierGroupIds: string[],
  selectionsInput: CartModifierSelectionInput[],
  groupById: Map<string, OnlineModifierGroup>
): { modifierMaps: Record<string, unknown>[]; modifiersTotalInCents: number } {
  const selections = Array.isArray(selectionsInput) ? selectionsInput : [];
  if (itemModifierGroupIds.length === 0) {
    if (selections.length > 0) {
      throw new OnlineOrderValidationError(`"${itemName}" does not support modifiers.`);
    }
    return { modifierMaps: [], modifiersTotalInCents: 0 };
  }

  const seenPair = new Set<string>();
  for (const s of selections) {
    if (!s.groupId || !s.optionId) {
      throw new OnlineOrderValidationError(`Invalid modifier selection for "${itemName}".`);
    }
    const key = `${s.groupId}\0${s.optionId}`;
    if (seenPair.has(key)) {
      throw new OnlineOrderValidationError(`Duplicate modifier selection for "${itemName}".`);
    }
    seenPair.add(key);
    if (!itemModifierGroupIds.includes(s.groupId)) {
      throw new OnlineOrderValidationError(`Invalid modifier group for "${itemName}".`);
    }
  }

  for (const gid of itemModifierGroupIds) {
    const g = groupById.get(gid);
    if (!g) {
      throw new OnlineOrderValidationError(`Modifier group is not available for "${itemName}".`);
    }
    const picks = selections.filter((x) => x.groupId === gid);
    if (picks.length < g.minSelection || picks.length > g.maxSelection) {
      throw new OnlineOrderValidationError(
        `Choose between ${g.minSelection} and ${g.maxSelection} option(s) for "${g.name}" on "${itemName}".`
      );
    }
  }

  const modifierMaps: Record<string, unknown>[] = [];
  let modifiersTotalInCents = 0;

  for (const gid of itemModifierGroupIds) {
    const g = groupById.get(gid)!;
    const picks = selections.filter((x) => x.groupId === gid);
    for (const p of picks) {
      const opt = g.options.find((o) => o.id === p.optionId);
      if (!opt) {
        throw new OnlineOrderValidationError(`Unknown option for "${g.name}" on "${itemName}".`);
      }
      const action = g.groupType === "REMOVE" ? "REMOVE" : "ADD";
      const map: Record<string, unknown> = {
        name: opt.name,
        action,
        price: opt.price,
        groupId: g.id,
        groupName: g.name,
      };
      modifierMaps.push(map);
      if (action === "ADD") {
        modifiersTotalInCents += Math.round(opt.price * 100);
      }
    }
  }

  return { modifierMaps, modifiersTotalInCents };
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

  if (!isStoreCurrentlyOpen(oo)) {
    throw new OnlineOrderValidationError(
      "We're currently closed for online orders. Please try again soon."
    );
  }

  const itemIds = [...new Set(lines.map((l) => l.itemId))];
  const refs = itemIds.map((id) => db.collection("MenuItems").doc(id));
  const snaps = await db.getAll(...refs);

  const byId = new Map<string, DocumentData>();
  for (const s of snaps) {
    if (s.exists) byId.set(s.id, s.data()!);
  }

  const modifierGroupIdSet = new Set<string>();
  for (const line of lines) {
    const data = byId.get(line.itemId);
    if (!data) continue;
    for (const gid of itemModifierGroupIds(data)) modifierGroupIdSet.add(gid);
    for (const sel of line.modifierSelections ?? []) {
      if (sel.groupId) modifierGroupIdSet.add(sel.groupId);
    }
  }
  const groupRefs = [...modifierGroupIdSet].map((id) => db.collection("ModifierGroups").doc(id));
  const groupSnaps = groupRefs.length > 0 ? await db.getAll(...groupRefs) : [];
  const groupById = new Map<string, OnlineModifierGroup>();
  for (const s of groupSnaps) {
    if (s.exists) groupById.set(s.id, parseModifierGroupDoc(s.id, s.data()!));
  }

  let totalInCents = 0;
  const resolvedLines: {
    lineKey: string;
    itemId: string;
    name: string;
    quantity: number;
    basePriceInCents: number;
    taxIds: string[];
    modifierMaps: Record<string, unknown>[];
    modifiersTotalInCents: number;
    unitPriceInCents: number;
    lineTotalInCents: number;
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
    const itemModIds = itemModifierGroupIds(data);
    const { modifierMaps, modifiersTotalInCents } = buildValidatedModifiersForLine(
      name,
      itemModIds,
      line.modifierSelections ?? [],
      groupById
    );
    const unitPriceInCents = basePriceCents + modifiersTotalInCents;
    const lineTotalInCents = unitPriceInCents * line.quantity;
    totalInCents += lineTotalInCents;
    resolvedLines.push({
      lineKey: randomUUID(),
      itemId: line.itemId,
      name,
      quantity: line.quantity,
      basePriceInCents: basePriceCents,
      taxIds,
      modifierMaps,
      modifiersTotalInCents,
      unitPriceInCents,
      lineTotalInCents,
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
      /** POS must confirm before the order appears in Online orders / KDS (see `requireStaffConfirmOrder`). */
      awaitingStaffConfirmOrder: oo.requireStaffConfirmOrder === true,
      onlinePaymentChoice: params.paymentChoice,
      customerName: customerName || "Guest",
      customerPhone: customerPhone,
      customerEmail: customerEmail,
    };

    t.set(orderRef, orderFields);

    for (const rl of resolvedLines) {
      const lineDoc: Record<string, unknown> = {
        itemId: rl.itemId,
        name: rl.name,
        quantity: rl.quantity,
        basePriceInCents: rl.basePriceInCents,
        modifiersTotalInCents: rl.modifiersTotalInCents,
        unitPriceInCents: rl.unitPriceInCents,
        lineTotalInCents: rl.lineTotalInCents,
        modifiers: rl.modifierMaps,
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
