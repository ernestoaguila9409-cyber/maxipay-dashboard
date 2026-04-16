/** Category placements for a menu item (matches POS / Menu page). */
export function placementCategoryIds(data: Record<string, unknown>): string[] {
  const rawList = data.categoryIds as unknown;
  if (Array.isArray(rawList) && rawList.length > 0) {
    return rawList
      .map((x) => String(x ?? "").trim())
      .filter((x) => x.length > 0);
  }
  const catId = String(data.categoryId ?? "").trim();
  return catId ? [catId] : [];
}

export interface MenuItemForKds {
  id: string;
  name: string;
  placements: string[];
}

/** Build full selection from Firestore fields + current menu catalog. */
export function deriveSelectedItemIdsFromDevice(
  assignedCategoryIds: string[],
  assignedItemIds: string[],
  items: MenuItemForKds[]
): Set<string> {
  const catSet = new Set(assignedCategoryIds);
  const sel = new Set<string>();
  for (const id of assignedItemIds) {
    if (id.trim()) sel.add(id.trim());
  }
  for (const it of items) {
    if (it.placements.some((p) => catSet.has(p))) sel.add(it.id);
  }
  return sel;
}

export interface CategoryRow {
  id: string;
  name: string;
}

/**
 * Compact Firestore: full category rows get [assignedCategoryIds];
 * remaining selected lines go to [assignedItemIds].
 */
export function normalizeAssignmentForSave(
  selectedItemIds: Set<string>,
  items: MenuItemForKds[],
  categories: CategoryRow[]
): { assignedCategoryIds: string[]; assignedItemIds: string[] } {
  const sel = selectedItemIds;
  const assignedCategoryIds: string[] = [];
  for (const cat of categories) {
    const inCat = items
      .filter((it) => it.placements.includes(cat.id))
      .map((it) => it.id);
    if (inCat.length === 0) continue;
    if (inCat.every((id) => sel.has(id))) assignedCategoryIds.push(cat.id);
  }
  const catSaved = new Set(assignedCategoryIds);
  const assignedItemIds = [...sel].filter((id) => {
    const it = items.find((x) => x.id === id);
    if (!it) return true;
    return !it.placements.some((p) => catSaved.has(p));
  });
  return { assignedCategoryIds, assignedItemIds };
}

/** KDS routing: line matches if item in assigned categories OR explicit item id. */
export function menuItemMatchesAssignment(
  itemId: string,
  placements: string[],
  assignedCategoryIds: Set<string>,
  assignedItemIds: Set<string>
): boolean {
  const id = itemId.trim();
  if (!id) return false;
  if (assignedItemIds.has(id)) return true;
  return placements.some((p) => assignedCategoryIds.has(p));
}
