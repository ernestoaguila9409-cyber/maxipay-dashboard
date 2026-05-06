/**
 * Kitchen routing label resolution — aligned with Android
 * `MenuItemRoutingLabel.fromMenuItemDoc` + `MenuItemRoutingLabel.resolve`
 * (item → subcategory → category).
 */

export type RoutingLabelCategory = {
  id: string;
  kitchenLabel?: string;
};

export type RoutingLabelSubcategory = {
  id: string;
  categoryId: string;
  kitchenLabel?: string;
};

export type RoutingLabelMenuItem = {
  categoryId: string;
  subcategoryId: string;
  subcategoryByCategoryId?: Record<string, string>;
  /** Firestore `labels` (first non-empty wins on POS). */
  labels?: string[];
  printerLabel?: string;
};

/** Item-level label only: `labels[0]` then `printerLabel` (matches POS). */
export function ownRoutingLabelFromMenuItem(item: RoutingLabelMenuItem): string | undefined {
  const raw = Array.isArray(item.labels)
    ? item.labels
        .filter((x): x is string => typeof x === "string" && x.trim().length > 0)
        .map((x) => x.trim())
    : [];
  if (raw.length > 0) return raw[0];
  const pl = typeof item.printerLabel === "string" ? item.printerLabel.trim() : "";
  return pl.length > 0 ? pl : undefined;
}

/** Subcategory id for the item’s primary `categoryId` (POS `subcategoryByCategoryId` fallback). */
export function effectiveSubcategoryIdForPrimaryCategory(item: RoutingLabelMenuItem): string {
  const byCat = item.subcategoryByCategoryId?.[item.categoryId];
  if (typeof byCat === "string" && byCat.trim().length > 0) return byCat.trim();
  return (item.subcategoryId ?? "").trim();
}

export function resolveKitchenRoutingLabel(
  itemLabel: string | undefined,
  subcategoryKitchenLabel: string | undefined,
  categoryKitchenLabel: string | undefined
): string | undefined {
  const i = itemLabel?.trim();
  if (i) return i;
  const s = subcategoryKitchenLabel?.trim();
  if (s) return s;
  const c = categoryKitchenLabel?.trim();
  if (c) return c;
  return undefined;
}

/** Effective label shown on POS / kitchen tickets for this item. */
export function resolveMenuItemKitchenRoutingLabel(
  item: RoutingLabelMenuItem,
  categories: RoutingLabelCategory[],
  subcategories: RoutingLabelSubcategory[]
): string | undefined {
  const own = ownRoutingLabelFromMenuItem(item);
  const subId = effectiveSubcategoryIdForPrimaryCategory(item);
  const sub = subcategories.find((x) => x.id === subId);
  const cat = categories.find((x) => x.id === item.categoryId);
  return resolveKitchenRoutingLabel(own, sub?.kitchenLabel, cat?.kitchenLabel);
}

/** Label from subcategory/category only (no item-level `labels` / `printerLabel`). */
export function inheritedKitchenRoutingLabelOnly(
  item: Pick<RoutingLabelMenuItem, "categoryId" | "subcategoryId" | "subcategoryByCategoryId">,
  categories: RoutingLabelCategory[],
  subcategories: RoutingLabelSubcategory[]
): string | undefined {
  return resolveMenuItemKitchenRoutingLabel(
    { ...item, labels: undefined, printerLabel: undefined },
    categories,
    subcategories
  );
}
