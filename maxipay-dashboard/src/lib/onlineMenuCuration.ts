import type { OnlineOrderingSettings } from "@/lib/onlineOrderingShared";

/** Category ids where this item is placed (POS / multi-category). */
export function menuItemPlacementCategoryIds(
  data: Record<string, unknown>
): string[] {
  const raw = data.categoryIds;
  if (Array.isArray(raw)) {
    const ids = raw.filter(
      (x): x is string => typeof x === "string" && x.trim().length > 0
    );
    if (ids.length > 0) return ids;
  }
  const c = data.categoryId;
  if (typeof c === "string" && c.trim().length > 0) return [c];
  return [];
}

export function isOnlineChannelOnData(data: Record<string, unknown>): boolean {
  const ch = data.channels as Record<string, unknown> | undefined;
  return ch?.online === true;
}

/**
 * Whether this menu item may appear on the public online menu and in checkout.
 * Curated mode uses category + item allowlists; otherwise legacy `channels.online`.
 */
export function isMenuItemVisibleOnOnlineChannel(
  itemDocId: string,
  data: Record<string, unknown>,
  oo: OnlineOrderingSettings
): boolean {
  if (oo.onlineMenuCurationEnabled) {
    const cats = new Set(oo.onlineMenuCategoryIds);
    const items = new Set(oo.onlineMenuItemIds);
    if (items.has(itemDocId)) return true;
    for (const cid of menuItemPlacementCategoryIds(data)) {
      if (cats.has(cid)) return true;
    }
    return false;
  }
  return isOnlineChannelOnData(data);
}
