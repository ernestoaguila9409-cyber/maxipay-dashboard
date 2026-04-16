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
  /** [menuSchedules] ids on the item (dashboard Menus / scheduling). */
  scheduleIds: string[];
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
  /** [menuSchedules] ids; empty = not tied to a scheduled menu. */
  scheduleIds: string[];
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

/** Section for grouping the KDS assign UI by [menuSchedules]. */
export interface ScheduleAssignmentSection {
  id: string;
  name: string;
  categories: CategoryRow[];
  items: MenuItemForKds[];
}

export const UNSCHEDULED_SECTION_ID = "__unscheduled__";

/**
 * Split categories/items into one block per schedule (plus “No schedule” when needed).
 * Items can appear in more than one section if they match multiple schedules.
 */
export function buildScheduleAssignmentSections(
  scheduleDocs: { id: string; name: string }[],
  categories: CategoryRow[],
  items: MenuItemForKds[]
): ScheduleAssignmentSection[] {
  const sorted = [...scheduleDocs].sort((a, b) =>
    a.name.localeCompare(b.name, undefined, { sensitivity: "base" })
  );
  const out: ScheduleAssignmentSection[] = [];

  for (const sch of sorted) {
    const secCats = categories.filter((c) => c.scheduleIds.includes(sch.id));
    const itemIds = new Set<string>();
    for (const it of items) {
      if (it.scheduleIds.includes(sch.id)) itemIds.add(it.id);
      for (const pid of it.placements) {
        const cat = categories.find((c) => c.id === pid);
        if (cat?.scheduleIds.includes(sch.id)) itemIds.add(it.id);
      }
    }
    const secItems = items.filter((it) => itemIds.has(it.id));
    if (secCats.length > 0 || secItems.length > 0) {
      out.push({
        id: sch.id,
        name: sch.name,
        categories: secCats,
        items: secItems,
      });
    }
  }

  const unschedCats = categories.filter((c) => c.scheduleIds.length === 0);
  const unschedItems = items.filter((it) => {
    if (it.scheduleIds.length > 0) return false;
    const cats = it.placements
      .map((pid) => categories.find((c) => c.id === pid))
      .filter((c): c is CategoryRow => c != null);
    if (cats.length === 0) return true;
    return cats.every((c) => c.scheduleIds.length === 0);
  });
  if (unschedCats.length > 0 || unschedItems.length > 0) {
    out.push({
      id: UNSCHEDULED_SECTION_ID,
      name: "No schedule",
      categories: unschedCats,
      items: unschedItems,
    });
  }

  return out;
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
