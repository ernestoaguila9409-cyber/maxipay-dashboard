function parseStringArrayField(data: Record<string, unknown>, key: string): string[] {
  const raw = data[key];
  if (!Array.isArray(raw)) return [];
  return raw
    .map((x) => String(x ?? "").trim())
    .filter((x) => x.length > 0);
}

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
  /** Primary category doc id (POS). */
  categoryId: string;
  /** [subcategories] doc id for default placement. */
  subcategoryId: string;
  /** Per–category subcategory when item spans multiple categories. */
  subcategoryByCategoryId?: Record<string, string>;
}

export function parseSubcategoryByCategoryId(
  data: Record<string, unknown>
): Record<string, string> | undefined {
  const raw = data.subcategoryByCategoryId;
  if (!raw || typeof raw !== "object") return undefined;
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
    const id = String(v ?? "").trim();
    if (id) out[String(k).trim()] = id;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/** One [MenuItems] row for KDS assignment (dashboard). */
export function parseMenuItemForKds(
  id: string,
  data: Record<string, unknown>
): MenuItemForKds | null {
  const name = String(data.name ?? "").trim();
  if (!name) return null;
  const placements = placementCategoryIds(data);
  const categoryId =
    String(data.categoryId ?? "").trim() || (placements[0] ?? "") || "";
  return {
    id,
    name,
    placements,
    scheduleIds: parseStringArrayField(data, "scheduleIds"),
    categoryId,
    subcategoryId: String(data.subcategoryId ?? "").trim(),
    subcategoryByCategoryId: parseSubcategoryByCategoryId(data),
  };
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

/**
 * How many [MenuItems] rows match the device (same expansion as the tablet).
 * Full categories are stored only in `assignedCategoryIds`; this counts every line included.
 *
 * @param menuCatalogReady pass false until the MenuItems listener has fired at least once
 *        so we do not show "0" while the catalog is still loading.
 */
export function effectiveAssignedMenuItemCount(
  assignedCategoryIds: string[],
  assignedItemIds: string[],
  menuItems: MenuItemForKds[],
  menuCatalogReady: boolean
): number | null {
  if (assignedCategoryIds.length > 0 && !menuCatalogReady) return null;
  return deriveSelectedItemIdsFromDevice(
    assignedCategoryIds,
    assignedItemIds,
    menuItems
  ).size;
}

export interface CategoryRow {
  id: string;
  name: string;
  /** [menuSchedules] ids; empty = not tied to a scheduled menu. */
  scheduleIds: string[];
}

export interface SubcategoryRow {
  id: string;
  name: string;
  categoryId: string;
  order: number;
}

/** Human-readable subcategory line for KDS assign / item rows. */
export function resolveSubcategoryLabel(
  item: MenuItemForKds,
  subcategories: SubcategoryRow[]
): string | null {
  const byId = (id: string) =>
    subcategories.find((s) => s.id === id)?.name?.trim() || null;
  const sid = item.subcategoryId?.trim();
  if (sid) {
    const n = byId(sid);
    if (n) return n;
  }
  const map = item.subcategoryByCategoryId;
  if (!map || Object.keys(map).length === 0) return null;
  const names = [...new Set(Object.values(map))]
    .map((id) => byId(String(id)))
    .filter((x): x is string => Boolean(x));
  return names.length > 0 ? names.join(", ") : null;
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

/**
 * Whether a menu item appears on this KDS given `kds_devices` assignment fields.
 * Matches KDS Android: both lists empty ⇒ no filter (all kitchen orders / all items).
 */
export function kdsDeviceRoutesMenuItemLine(
  assignedCategoryIds: string[],
  assignedItemIds: string[],
  itemId: string,
  placementCategoryIds: string[]
): boolean {
  if (assignedCategoryIds.length === 0 && assignedItemIds.length === 0) {
    return true;
  }
  return menuItemMatchesAssignment(
    itemId,
    placementCategoryIds,
    new Set(assignedCategoryIds),
    new Set(assignedItemIds)
  );
}

export function itemsInSectionCategory(
  section: ScheduleAssignmentSection,
  categoryId: string
): string[] {
  return section.items
    .filter((it) => it.placements.includes(categoryId))
    .map((it) => it.id);
}

/** True if the menu item is assigned to this [subcategories] row (POS fields). */
export function menuItemMatchesSubcategory(
  item: MenuItemForKds,
  subcategoryId: string
): boolean {
  const sid = subcategoryId.trim();
  if (!sid) return false;
  if (item.subcategoryId?.trim() === sid) return true;
  const map = item.subcategoryByCategoryId;
  if (!map) return false;
  return Object.values(map).some((v) => String(v).trim() === sid);
}

export function itemsInSectionSubcategory(
  section: ScheduleAssignmentSection,
  subcategoryId: string
): string[] {
  return section.items
    .filter((it) => menuItemMatchesSubcategory(it, subcategoryId))
    .map((it) => it.id);
}
