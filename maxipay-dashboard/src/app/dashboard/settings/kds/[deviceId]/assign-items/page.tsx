"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  collection,
  doc,
  onSnapshot,
  orderBy,
  query,
  updateDoc,
  serverTimestamp,
} from "firebase/firestore";
import { FirebaseError } from "firebase/app";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import {
  buildScheduleAssignmentSections,
  deriveSelectedItemIdsFromDevice,
  itemsInSectionCategory,
  itemsInSectionSubcategory,
  menuItemMatchesSubcategory,
  normalizeAssignmentForSave,
  parseMenuItemForKds,
  resolveSubcategoryLabel,
  type CategoryRow,
  type MenuItemForKds,
  type ScheduleAssignmentSection,
  type SubcategoryRow,
} from "@/lib/kdsMenuAssignment";
import {
  ArrowLeft,
  Search,
  Layers,
  UtensilsCrossed,
  Calendar,
  ChevronDown,
  ChevronRight,
} from "lucide-react";

const KDS_DEVICES_COLLECTION = "kds_devices";
const CATEGORIES_COLLECTION = "Categories";
const MENU_ITEMS_COLLECTION = "MenuItems";
const MENU_SCHEDULES_COLLECTION = "menuSchedules";
const SUBCATEGORIES_COLLECTION = "subcategories";

function parseStringArrayField(data: Record<string, unknown>, key: string): string[] {
  const raw = data[key];
  if (!Array.isArray(raw)) return [];
  return raw
    .map((x) => String(x ?? "").trim())
    .filter((x) => x.length > 0);
}

function parseCategoryRow(id: string, data: Record<string, unknown>): CategoryRow {
  return {
    id,
    name: String(data.name ?? "").trim() || "Category",
    scheduleIds: parseStringArrayField(data, "scheduleIds"),
  };
}

/** Browses the menu list on the right; independent of assignment checkboxes. */
type SectionListFilter =
  | { kind: "category"; categoryId: string }
  | { kind: "subcategory"; subcategoryId: string };

export default function KdsAssignItemsPage() {
  const { user } = useAuth();
  const router = useRouter();
  const params = useParams();
  const deviceId = String(params.deviceId ?? "").trim();

  const [deviceName, setDeviceName] = useState("");
  const [schedules, setSchedules] = useState<{ id: string; name: string }[]>([]);
  const [categories, setCategories] = useState<CategoryRow[]>([]);
  const [subcategories, setSubcategories] = useState<SubcategoryRow[]>([]);
  const [menuItems, setMenuItems] = useState<MenuItemForKds[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [assignedCategoryIds, setAssignedCategoryIds] = useState<string[]>([]);
  const [assignedItemIds, setAssignedItemIds] = useState<string[]>([]);
  const [selectedItemIds, setSelectedItemIds] = useState<Set<string>>(new Set());
  const [dirty, setDirty] = useState(false);

  const [search, setSearch] = useState("");
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saveOk, setSaveOk] = useState(false);

  /** Per schedule card: only `true` means expanded (default collapsed). */
  const [sectionExpanded, setSectionExpanded] = useState<Record<string, boolean>>(
    {}
  );

  /** Per section: filter menu list by category/subcategory name click (not selection). */
  const [sectionListFilter, setSectionListFilter] = useState<
    Record<string, SectionListFilter>
  >({});

  useEffect(() => {
    if (!user || !deviceId) {
      setLoading(false);
      return;
    }

    const unsubDevice = onSnapshot(
      doc(db, KDS_DEVICES_COLLECTION, deviceId),
      (snap) => {
        if (!snap.exists()) {
          setLoadError("This KDS device was not found.");
          setLoading(false);
          return;
        }
        const data = snap.data() as Record<string, unknown>;
        setDeviceName(String(data.name ?? "").trim() || "KDS device");
        setAssignedCategoryIds(parseStringArrayField(data, "assignedCategoryIds"));
        setAssignedItemIds(parseStringArrayField(data, "assignedItemIds"));
        setLoadError(null);
      },
      (err) => {
        console.error("[KDS assign] device:", err);
        setLoadError("Could not load device.");
        setLoading(false);
      }
    );

    const unsubSchedules = onSnapshot(
      collection(db, MENU_SCHEDULES_COLLECTION),
      (snap) => {
        const list: { id: string; name: string }[] = [];
        snap.forEach((d) => {
          const data = d.data() as Record<string, unknown>;
          const name = String(data.name ?? "").trim();
          if (name) list.push({ id: d.id, name });
        });
        list.sort((a, b) =>
          a.name.localeCompare(b.name, undefined, { sensitivity: "base" })
        );
        setSchedules(list);
      },
      (err) => console.error("[KDS assign] schedules:", err)
    );

    const unsubCats = onSnapshot(
      collection(db, CATEGORIES_COLLECTION),
      (snap) => {
        const list: CategoryRow[] = [];
        snap.forEach((d) => {
          list.push(parseCategoryRow(d.id, d.data() as Record<string, unknown>));
        });
        list.sort((a, b) =>
          a.name.localeCompare(b.name, undefined, { sensitivity: "base" })
        );
        setCategories(list);
      },
      (err) => console.error("[KDS assign] categories:", err)
    );

    const unsubItems = onSnapshot(
      collection(db, MENU_ITEMS_COLLECTION),
      (snap) => {
        const list: MenuItemForKds[] = [];
        snap.forEach((d) => {
          const row = parseMenuItemForKds(d.id, d.data() as Record<string, unknown>);
          if (row) list.push(row);
        });
        list.sort((a, b) =>
          a.name.localeCompare(b.name, undefined, { sensitivity: "base" })
        );
        setMenuItems(list);
        setLoading(false);
      },
      (err) => {
        console.error("[KDS assign] menu items:", err);
        setLoading(false);
      }
    );

    const unsubSubcats = onSnapshot(
      query(collection(db, SUBCATEGORIES_COLLECTION), orderBy("order", "asc")),
      (snap) => {
        const list: SubcategoryRow[] = [];
        snap.forEach((d) => {
          const data = d.data() as Record<string, unknown>;
          list.push({
            id: d.id,
            name: String(data.name ?? "").trim() || "Subcategory",
            categoryId: String(data.categoryId ?? "").trim(),
            order:
              typeof data.order === "number" && !Number.isNaN(data.order)
                ? data.order
                : Number(data.order) || 0,
          });
        });
        setSubcategories(list);
      },
      (err) => console.error("[KDS assign] subcategories:", err)
    );

    return () => {
      unsubDevice();
      unsubSchedules();
      unsubCats();
      unsubItems();
      unsubSubcats();
    };
  }, [user, deviceId]);

  useEffect(() => {
    if (dirty) return;
    if (menuItems.length === 0) return;
    setSelectedItemIds(
      deriveSelectedItemIdsFromDevice(assignedCategoryIds, assignedItemIds, menuItems)
    );
  }, [assignedCategoryIds, assignedItemIds, menuItems, dirty]);

  const scheduleSections = useMemo((): ScheduleAssignmentSection[] => {
    if (schedules.length === 0 && categories.length > 0) {
      return [
        {
          id: "all-menu",
          name: "Menu",
          categories,
          items: menuItems,
        },
      ];
    }
    const built = buildScheduleAssignmentSections(schedules, categories, menuItems);
    if (
      built.length === 0 &&
      (categories.length > 0 || menuItems.length > 0)
    ) {
      return [
        {
          id: "fallback",
          name: "Menu",
          categories,
          items: menuItems,
        },
      ];
    }
    return built;
  }, [schedules, categories, menuItems]);

  useEffect(() => {
    setSectionExpanded((prev) => {
      const next = { ...prev };
      const ids = new Set(scheduleSections.map((s) => s.id));
      for (const key of Object.keys(next)) {
        if (!ids.has(key)) delete next[key];
      }
      return next;
    });
  }, [scheduleSections]);

  useEffect(() => {
    setSectionListFilter((prev) => {
      const ids = new Set(scheduleSections.map((s) => s.id));
      const next = { ...prev };
      for (const key of Object.keys(next)) {
        if (!ids.has(key)) delete next[key];
      }
      return next;
    });
  }, [scheduleSections]);

  const searchLower = search.trim().toLowerCase();

  const isSectionExpanded = useCallback(
    (sectionId: string) => sectionExpanded[sectionId] === true,
    [sectionExpanded]
  );

  const toggleSectionExpanded = (sectionId: string) => {
    setSectionExpanded((prev) => ({
      ...prev,
      [sectionId]: !(prev[sectionId] === true),
    }));
  };

  const filterCategory = useCallback(
    (c: CategoryRow) => {
      if (!searchLower) return true;
      if (c.name.toLowerCase().includes(searchLower)) return true;
      return subcategories.some(
        (s) => s.categoryId === c.id && s.name.toLowerCase().includes(searchLower)
      );
    },
    [searchLower, subcategories]
  );

  const filterItem = useCallback(
    (it: MenuItemForKds) => {
      if (!searchLower) return true;
      if (it.name.toLowerCase().includes(searchLower)) return true;
      const subLabel = resolveSubcategoryLabel(it, subcategories);
      if (subLabel?.toLowerCase().includes(searchLower)) return true;
      if (
        it.placements.some((pid) => {
          const cat = categories.find((c) => c.id === pid);
          return cat?.name.toLowerCase().includes(searchLower);
        })
      )
        return true;
      return subcategories.some((s) => {
        if (!s.name.toLowerCase().includes(searchLower)) return false;
        if (it.subcategoryId === s.id) return true;
        return Object.values(it.subcategoryByCategoryId ?? {}).some((x) => x === s.id);
      });
    },
    [categories, searchLower, subcategories]
  );

  const categoryVisualState = useCallback(
    (section: ScheduleAssignmentSection, categoryId: string) => {
      const ids = itemsInSectionCategory(section, categoryId);
      if (ids.length === 0) return { checked: false, indeterminate: false };
      let n = 0;
      for (const id of ids) if (selectedItemIds.has(id)) n++;
      if (n === 0) return { checked: false, indeterminate: false };
      if (n === ids.length) return { checked: true, indeterminate: false };
      return { checked: false, indeterminate: true };
    },
    [selectedItemIds]
  );

  const toggleCategory = (
    section: ScheduleAssignmentSection,
    categoryId: string,
    turnOn: boolean
  ) => {
    setDirty(true);
    setSaveOk(false);
    setSelectedItemIds((prev) => {
      const next = new Set(prev);
      const ids = itemsInSectionCategory(section, categoryId);
      if (turnOn) {
        for (const id of ids) next.add(id);
      } else {
        for (const id of ids) next.delete(id);
      }
      return next;
    });
  };

  const toggleListFilterCategory = (
    sectionId: string,
    categoryId: string
  ) => {
    setSectionListFilter((prev) => {
      const cur = prev[sectionId];
      if (cur?.kind === "category" && cur.categoryId === categoryId) {
        const next = { ...prev };
        delete next[sectionId];
        return next;
      }
      return { ...prev, [sectionId]: { kind: "category", categoryId } };
    });
  };

  const toggleListFilterSubcategory = (
    sectionId: string,
    subcategoryId: string
  ) => {
    setSectionListFilter((prev) => {
      const cur = prev[sectionId];
      if (cur?.kind === "subcategory" && cur.subcategoryId === subcategoryId) {
        const next = { ...prev };
        delete next[sectionId];
        return next;
      }
      return { ...prev, [sectionId]: { kind: "subcategory", subcategoryId } };
    });
  };

  const subcategoryVisualState = useCallback(
    (section: ScheduleAssignmentSection, subcategoryId: string) => {
      const ids = itemsInSectionSubcategory(section, subcategoryId);
      if (ids.length === 0) return { checked: false, indeterminate: false };
      let n = 0;
      for (const id of ids) if (selectedItemIds.has(id)) n++;
      if (n === 0) return { checked: false, indeterminate: false };
      if (n === ids.length) return { checked: true, indeterminate: false };
      return { checked: false, indeterminate: true };
    },
    [selectedItemIds]
  );

  const toggleSubcategory = (
    section: ScheduleAssignmentSection,
    subcategoryId: string,
    turnOn: boolean
  ) => {
    setDirty(true);
    setSaveOk(false);
    setSelectedItemIds((prev) => {
      const next = new Set(prev);
      const ids = itemsInSectionSubcategory(section, subcategoryId);
      if (turnOn) {
        for (const id of ids) next.add(id);
      } else {
        for (const id of ids) next.delete(id);
      }
      return next;
    });
  };

  const toggleItem = (itemId: string) => {
    setDirty(true);
    setSaveOk(false);
    setSelectedItemIds((prev) => {
      const next = new Set(prev);
      if (next.has(itemId)) next.delete(itemId);
      else next.add(itemId);
      return next;
    });
  };

  const selectAllItems = () => {
    setDirty(true);
    setSaveOk(false);
    const allIds = new Set<string>();
    for (const it of menuItems) allIds.add(it.id);
    for (const sec of scheduleSections) {
      for (const it of sec.items) allIds.add(it.id);
    }
    setSelectedItemIds(allIds);
    setSectionExpanded((prev) => {
      const next = { ...prev };
      for (const s of scheduleSections) next[s.id] = true;
      return next;
    });
  };

  const clearSelection = () => {
    setDirty(true);
    setSaveOk(false);
    setSelectedItemIds(new Set());
  };

  const handleSave = async () => {
    if (!user || !deviceId) return;
    setSaveError(null);
    setSaveOk(false);
    setSaving(true);
    try {
      const { assignedCategoryIds: cats, assignedItemIds: items } =
        normalizeAssignmentForSave(selectedItemIds, menuItems, categories);
      await updateDoc(doc(db, KDS_DEVICES_COLLECTION, deviceId), {
        assignedCategoryIds: cats,
        assignedItemIds: items,
        updatedAt: serverTimestamp(),
      });
      setDirty(false);
      setAssignedCategoryIds(cats);
      setAssignedItemIds(items);
      setSaveOk(true);
    } catch (err) {
      console.error("[KDS assign] save:", err);
      setSaveError(
        err instanceof FirebaseError
          ? `${err.code}: ${err.message}`
          : "Could not save. Check Firestore rules."
      );
    } finally {
      setSaving(false);
    }
  };

  if (!user) {
    return (
      <div className="min-h-[50vh] flex items-center justify-center text-slate-500">
        Sign in to manage KDS devices.
      </div>
    );
  }

  if (!deviceId) {
    return (
      <div className="p-6 text-slate-600">
        Invalid device.{" "}
        <Link href="/dashboard/settings/kds" className="text-blue-600 font-medium">
          Back to KDS
        </Link>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50/80">
      <header className="sticky top-0 z-20 border-b border-slate-200 bg-white/95 backdrop-blur-sm">
        <div className="mx-auto flex max-w-[1400px] items-center gap-4 px-4 py-4 sm:px-6">
          <button
            type="button"
            onClick={() => router.push("/dashboard/settings/kds")}
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-slate-200 text-slate-600 hover:bg-slate-50 transition-colors"
            aria-label="Back to KDS settings"
          >
            <ArrowLeft size={20} />
          </button>
          <div className="min-w-0 flex-1">
            <h1 className="text-lg font-semibold text-slate-900 sm:text-xl">
              Assign Items to KDS
            </h1>
            <p className="mt-0.5 truncate text-sm text-slate-500">
              {deviceName || "Loading…"} · grouped by schedule · live from Firestore
            </p>
          </div>
        </div>
      </header>

      <div className="mx-auto max-w-[1400px] px-4 py-6 sm:px-6">
        {loadError && (
          <div className="mb-6 rounded-2xl border border-red-100 bg-red-50 px-4 py-3 text-sm text-red-800">
            {loadError}{" "}
            <Link href="/dashboard/settings/kds" className="font-medium text-red-900 underline">
              Return to KDS
            </Link>
          </div>
        )}

        <div className="mb-6 flex flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm sm:flex-row sm:items-center sm:justify-between">
          <div className="relative min-w-0 flex-1">
            <Search
              className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400"
              aria-hidden
            />
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search categories or items…"
              className="w-full rounded-xl border border-slate-200 bg-slate-50/80 py-2.5 pl-10 pr-4 text-sm text-slate-800 placeholder:text-slate-400 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20"
            />
          </div>
          <div className="flex shrink-0 flex-wrap gap-2">
            <button
              type="button"
              onClick={selectAllItems}
              disabled={loading || menuItems.length === 0}
              className="rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50 transition-colors"
            >
              Select all
            </button>
            <button
              type="button"
              onClick={clearSelection}
              disabled={loading}
              className="rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50 transition-colors"
            >
              Clear
            </button>
            <button
              type="button"
              onClick={handleSave}
              disabled={saving || loading || !!loadError}
              className="rounded-xl bg-blue-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {saving ? "Saving…" : "Save"}
            </button>
          </div>
        </div>

        {saveError && (
          <div className="mb-4 rounded-xl border border-red-100 bg-red-50 px-4 py-2.5 text-sm text-red-800">
            {saveError}
          </div>
        )}
        {saveOk && (
          <div className="mb-4 rounded-xl border border-emerald-100 bg-emerald-50 px-4 py-2.5 text-sm text-emerald-900">
            Saved. Paired tablets pick this up from Firestore automatically.
          </div>
        )}

        {loading ? (
          <div className="flex justify-center py-24">
            <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-200 border-t-blue-600" />
          </div>
        ) : (
          <div className="space-y-8">
            {scheduleSections.map((section) => {
              const filteredCats = section.categories.filter(filterCategory);
              const filteredSecItems = section.items.filter(filterItem);
              const listFilter = sectionListFilter[section.id];
              const itemsToShow =
                listFilter?.kind === "category"
                  ? filteredSecItems.filter((it) =>
                      it.placements.includes(listFilter.categoryId)
                    )
                  : listFilter?.kind === "subcategory"
                    ? filteredSecItems.filter((it) =>
                        menuItemMatchesSubcategory(it, listFilter.subcategoryId)
                      )
                    : filteredSecItems;
              const expanded = isSectionExpanded(section.id);
              return (
                <div
                  key={section.id}
                  className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm"
                >
                  <button
                    type="button"
                    onClick={() => toggleSectionExpanded(section.id)}
                    className="flex w-full items-center gap-3 border-b border-slate-100 bg-gradient-to-r from-slate-50 to-white px-4 py-3 text-left transition-colors hover:bg-slate-100/60 sm:px-5"
                    aria-expanded={expanded}
                  >
                    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-600">
                      {expanded ? (
                        <ChevronDown className="h-4 w-4" aria-hidden />
                      ) : (
                        <ChevronRight className="h-4 w-4" aria-hidden />
                      )}
                    </span>
                    <Calendar className="h-5 w-5 shrink-0 text-blue-600" aria-hidden />
                    <h2 className="min-w-0 flex-1 text-base font-semibold text-slate-900">
                      {section.name}
                    </h2>
                    <span className="shrink-0 text-xs font-medium text-slate-400">
                      {filteredCats.length} cat · {itemsToShow.length} items
                    </span>
                  </button>
                  {expanded ? (
                  <div className="grid min-h-[200px] gap-4 p-4 lg:grid-cols-2 lg:p-5">
                    <div className="flex min-h-0 flex-col rounded-xl border border-slate-100 bg-slate-50/40">
                      <div className="flex items-center gap-2 border-b border-slate-100/80 px-3 py-2.5">
                        <Layers className="h-4 w-4 text-slate-500" aria-hidden />
                        <span className="text-sm font-semibold text-slate-700">
                          Categories
                        </span>
                      </div>
                      <div className="max-h-[min(420px,50vh)] overflow-y-auto p-2">
                        {filteredCats.length === 0 ? (
                          <p className="px-2 py-6 text-center text-sm text-slate-500">
                            No categories in this schedule match the search.
                          </p>
                        ) : (
                          <ul className="space-y-1">
                            {filteredCats.map((c) => {
                              const { checked, indeterminate } =
                                categoryVisualState(section, c.id);
                              const subs = subcategories
                                .filter((s) => s.categoryId === c.id)
                                .sort(
                                  (a, b) =>
                                    a.order - b.order ||
                                    a.name.localeCompare(b.name, undefined, {
                                      sensitivity: "base",
                                    })
                                );
                              const categoryFilterOn =
                                listFilter?.kind === "category" &&
                                listFilter.categoryId === c.id;
                              return (
                                <li key={`${section.id}-cat-${c.id}`}>
                                  <div className="flex items-center gap-3 rounded-lg px-2 py-2 text-sm text-slate-800 hover:bg-white">
                                    <input
                                      type="checkbox"
                                      id={`${section.id}-cat-cb-${c.id}`}
                                      ref={(el) => {
                                        if (el) el.indeterminate = indeterminate;
                                      }}
                                      checked={checked}
                                      onChange={(e) =>
                                        toggleCategory(
                                          section,
                                          c.id,
                                          e.target.checked
                                        )
                                      }
                                      className="h-4 w-4 shrink-0 cursor-pointer rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                                      aria-label={`Select all items in ${c.name}`}
                                    />
                                    <button
                                      type="button"
                                      onClick={() =>
                                        toggleListFilterCategory(section.id, c.id)
                                      }
                                      className={`min-w-0 text-left font-medium hover:underline ${
                                        categoryFilterOn
                                          ? "text-blue-600"
                                          : "text-slate-800"
                                      }`}
                                    >
                                      {c.name}
                                    </button>
                                  </div>
                                  {subs.length > 0 ? (
                                    <ul className="ml-2 space-y-0.5 border-l border-slate-200 py-0.5 pl-2 sm:ml-9 sm:pl-3">
                                      {subs.map((s) => {
                                        const subVs = subcategoryVisualState(
                                          section,
                                          s.id
                                        );
                                        const subFilterOn =
                                          listFilter?.kind === "subcategory" &&
                                          listFilter.subcategoryId === s.id;
                                        return (
                                          <li
                                            key={`${section.id}-sub-${c.id}-${s.id}`}
                                            className="rounded-md pl-1"
                                          >
                                            <div className="flex items-center gap-2 py-0.5 text-xs">
                                              <input
                                                type="checkbox"
                                                id={`${section.id}-sub-cb-${s.id}`}
                                                ref={(el) => {
                                                  if (el)
                                                    el.indeterminate =
                                                      subVs.indeterminate;
                                                }}
                                                checked={subVs.checked}
                                                onChange={(e) =>
                                                  toggleSubcategory(
                                                    section,
                                                    s.id,
                                                    e.target.checked
                                                  )
                                                }
                                                className="h-3.5 w-3.5 shrink-0 cursor-pointer rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                                                aria-label={`Select all items in ${s.name}`}
                                              />
                                              <button
                                                type="button"
                                                onClick={() =>
                                                  toggleListFilterSubcategory(
                                                    section.id,
                                                    s.id
                                                  )
                                                }
                                                className={`text-left hover:underline ${
                                                  subFilterOn
                                                    ? "font-medium text-blue-600"
                                                    : "text-slate-500"
                                                }`}
                                              >
                                                {s.name}
                                              </button>
                                            </div>
                                          </li>
                                        );
                                      })}
                                    </ul>
                                  ) : null}
                                </li>
                              );
                            })}
                          </ul>
                        )}
                      </div>
                    </div>
                    <div className="flex min-h-0 flex-col rounded-xl border border-slate-100 bg-slate-50/40">
                      <div className="flex items-center gap-2 border-b border-slate-100/80 px-3 py-2.5">
                        <UtensilsCrossed className="h-4 w-4 text-slate-500" aria-hidden />
                        <span className="text-sm font-semibold text-slate-700">
                          Menu items
                        </span>
                      </div>
                      <div className="max-h-[min(420px,50vh)] overflow-y-auto p-2">
                        {filteredSecItems.length === 0 ? (
                          <p className="px-2 py-6 text-center text-sm text-slate-500">
                            No items in this schedule match the search.
                          </p>
                        ) : itemsToShow.length === 0 ? (
                          <p className="px-2 py-6 text-center text-sm text-slate-500">
                            No items match this filter. Click the same category or
                            subcategory name again to show all items, or adjust the
                            search.
                          </p>
                        ) : (
                          <ul className="space-y-0.5">
                            {itemsToShow.map((it) => {
                              const checked = selectedItemIds.has(it.id);
                              const catLabels = it.placements
                                .map((pid) => categories.find((c) => c.id === pid)?.name)
                                .filter(Boolean)
                                .join(" · ");
                              const subLabel = resolveSubcategoryLabel(
                                it,
                                subcategories
                              );
                              return (
                                <li key={`${section.id}-item-${it.id}`}>
                                  <label className="flex cursor-pointer items-start gap-3 rounded-lg px-2 py-2 text-sm hover:bg-white">
                                    <input
                                      type="checkbox"
                                      checked={checked}
                                      onChange={() => toggleItem(it.id)}
                                      className="mt-0.5 h-4 w-4 shrink-0 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                                    />
                                    <span className="min-w-0 flex-1">
                                      <span className="font-medium text-slate-800">
                                        {it.name}
                                      </span>
                                      {catLabels ? (
                                        <span className="mt-0.5 block text-xs text-slate-500">
                                          {catLabels}
                                        </span>
                                      ) : null}
                                      {subLabel ? (
                                        <span className="mt-0.5 block text-xs text-slate-400">
                                          {subLabel}
                                        </span>
                                      ) : null}
                                    </span>
                                  </label>
                                </li>
                              );
                            })}
                          </ul>
                        )}
                      </div>
                    </div>
                  </div>
                  ) : null}
                </div>
              );
            })}
          </div>
        )}

        <p className="mt-8 text-center text-xs text-slate-500">
          Tickets appear on this KDS if any line&apos;s menu item is in an assigned
          category or explicitly listed. Leave both empty on the device to show all
          tickets.
        </p>
      </div>
    </div>
  );
}
