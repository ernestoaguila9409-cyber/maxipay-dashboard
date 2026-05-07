"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  collection,
  doc,
  onSnapshot,
  setDoc,
  writeBatch,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import {
  COURSE_FIRING_COLLECTION_PATH,
  COURSE_FIRING_DOC,
  DEFAULT_COURSES,
  parseCourseFiringSettings,
  type CourseDefinition,
  type CourseFiringSettings,
} from "@/lib/courseFiringShared";
import {
  menuItemMatchesSubcategory,
  parseMenuItemForKds,
  parseSubcategoryByCategoryId,
  placementCategoryIds,
  type MenuItemForKds,
} from "@/lib/kdsMenuAssignment";
import { ArrowDown, ArrowUp, Loader2, Plus, Trash2, X } from "lucide-react";

interface CategoryRow {
  id: string;
  name: string;
}

interface SubcategoryRow {
  id: string;
  name: string;
  categoryId: string;
}

type CourseMenuItem = MenuItemForKds & { courseId?: string };

function parseCourseMenuItem(id: string, data: Record<string, unknown>): CourseMenuItem | null {
  const parsed = parseMenuItemForKds(id, data);
  const courseId = typeof data.courseId === "string" ? data.courseId : undefined;
  if (parsed) return { ...parsed, courseId };
  const name = String(data.name ?? "").trim();
  if (!name) return null;
  const placements = placementCategoryIds(data);
  const categoryId =
    String(data.categoryId ?? "").trim() || (placements[0] ?? "") || "";
  return {
    id,
    name,
    placements,
    scheduleIds: [],
    categoryId,
    subcategoryId: String(data.subcategoryId ?? "").trim(),
    subcategoryByCategoryId: parseSubcategoryByCategoryId(data),
    courseId,
  };
}

/** Uniform course on all items in this category, else "" or "__mixed__". */
function courseForCategory(catId: string, items: CourseMenuItem[]): string {
  const rel = items.filter((it) => it.placements.includes(catId));
  if (rel.length === 0) return "";
  const first = rel[0].courseId ?? "";
  if (rel.every((it) => (it.courseId ?? "") === first)) return first;
  return "__mixed__";
}

function courseForSubcategory(subId: string, items: CourseMenuItem[]): string {
  const rel = items.filter((it) => menuItemMatchesSubcategory(it, subId));
  if (rel.length === 0) return "";
  const first = rel[0].courseId ?? "";
  if (rel.every((it) => (it.courseId ?? "") === first)) return first;
  return "__mixed__";
}

function itemsInCategory(items: CourseMenuItem[], catId: string): CourseMenuItem[] {
  return items.filter((it) => it.placements.includes(catId));
}

function itemsMatchingSubcategory(items: CourseMenuItem[], subId: string): CourseMenuItem[] {
  return items.filter((it) => menuItemMatchesSubcategory(it, subId));
}

function isExclusiveOwner(owner: string): boolean {
  return owner.length > 0 && owner !== "__mixed__";
}

type PickerKind = "category" | "subcategory" | "item";

export default function CourseFiringPage() {
  const { user, loading: authLoading } = useAuth();
  const [settings, setSettings] = useState<CourseFiringSettings | null>(null);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [categories, setCategories] = useState<CategoryRow[]>([]);
  const [subcategories, setSubcategories] = useState<SubcategoryRow[]>([]);
  const [menuItems, setMenuItems] = useState<CourseMenuItem[]>([]);
  const [savingAssign, setSavingAssign] = useState(false);
  const [picker, setPicker] = useState<{
    courseId: string;
    courseLabel: string;
    kind: PickerKind;
  } | null>(null);
  const [pickerFilter, setPickerFilter] = useState("");

  useEffect(() => {
    if (!user) return;
    const ref = doc(db, COURSE_FIRING_COLLECTION_PATH, COURSE_FIRING_DOC);
    const unsub = onSnapshot(ref, (snap) => {
      const data = snap.exists() ? (snap.data() as Record<string, unknown>) : undefined;
      const parsed = parseCourseFiringSettings(data);
      setSettings(parsed);
      setDirty(false);
    });
    return unsub;
  }, [user]);

  useEffect(() => {
    if (!user) return;
    const unsub1 = onSnapshot(collection(db, "Categories"), (snap) => {
      const list: CategoryRow[] = [];
      snap.forEach((d) => {
        const data = d.data();
        list.push({ id: d.id, name: String(data.name ?? "Unnamed") });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setCategories(list);
    });
    const unsub2 = onSnapshot(collection(db, "subcategories"), (snap) => {
      const list: SubcategoryRow[] = [];
      snap.forEach((d) => {
        const data = d.data();
        list.push({
          id: d.id,
          name: String(data.name ?? "Unnamed").trim() || "Subcategory",
          categoryId: String(data.categoryId ?? ""),
        });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setSubcategories(list);
    });
    const unsub3 = onSnapshot(collection(db, "MenuItems"), (snap) => {
      const list: CourseMenuItem[] = [];
      snap.forEach((d) => {
        const row = parseCourseMenuItem(d.id, d.data() as Record<string, unknown>);
        if (row) list.push(row);
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setMenuItems(list);
    });
    return () => {
      unsub1();
      unsub2();
      unsub3();
    };
  }, [user]);

  const catNameById = useMemo(() => new Map(categories.map((c) => [c.id, c.name])), [categories]);

  const openPicker = (course: CourseDefinition, kind: PickerKind) => {
    setPickerFilter("");
    setPicker({ courseId: course.id, courseLabel: course.name || course.id, kind });
  };

  const assignCategoryToCourse = async (categoryId: string, courseId: string) => {
    const items = itemsInCategory(menuItems, categoryId);
    if (items.length === 0) return;
    setSavingAssign(true);
    try {
      const batch = writeBatch(db);
      for (const it of items) {
        batch.update(doc(db, "MenuItems", it.id), { courseId: courseId || null });
      }
      await batch.commit();
    } finally {
      setSavingAssign(false);
    }
  };

  const assignSubcategoryToCourse = async (subcategoryId: string, courseId: string) => {
    const items = itemsMatchingSubcategory(menuItems, subcategoryId);
    if (items.length === 0) return;
    setSavingAssign(true);
    try {
      const batch = writeBatch(db);
      for (const it of items) {
        batch.update(doc(db, "MenuItems", it.id), { courseId: courseId || null });
      }
      await batch.commit();
    } finally {
      setSavingAssign(false);
    }
  };

  const assignItemToCourse = async (itemId: string, courseId: string) => {
    setSavingAssign(true);
    try {
      await setDoc(doc(db, "MenuItems", itemId), { courseId: courseId || null }, { merge: true });
    } finally {
      setSavingAssign(false);
    }
  };

  const clearCategoryAssignment = async (categoryId: string) => {
    await assignCategoryToCourse(categoryId, "");
  };

  const clearSubcategoryAssignment = async (subcategoryId: string) => {
    await assignSubcategoryToCourse(subcategoryId, "");
  };

  const clearItemAssignment = async (itemId: string) => {
    await assignItemToCourse(itemId, "");
  };

  const assignedCategoriesForCourse = useCallback(
    (courseId: string) =>
      categories.filter((c) => courseForCategory(c.id, menuItems) === courseId),
    [categories, menuItems]
  );

  const assignedSubcategoriesForCourse = useCallback(
    (courseId: string) =>
      subcategories.filter((s) => {
        if (courseForSubcategory(s.id, menuItems) !== courseId) return false;
        const parent = courseForCategory(s.categoryId, menuItems);
        return parent !== courseId;
      }),
    [subcategories, menuItems]
  );

  const assignedItemsForCourse = useCallback(
    (courseId: string) =>
      menuItems.filter((it) => {
        if ((it.courseId ?? "") !== courseId) return false;
        for (const catId of it.placements) {
          if (courseForCategory(catId, menuItems) === courseId) return false;
        }
        return true;
      }),
    [menuItems]
  );

  if (authLoading || !user) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader2 className="w-6 h-6 animate-spin text-gray-400" />
      </div>
    );
  }

  if (!settings) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader2 className="w-6 h-6 animate-spin text-gray-400" />
      </div>
    );
  }

  const save = async (next: CourseFiringSettings) => {
    setSaving(true);
    try {
      const ref = doc(db, COURSE_FIRING_COLLECTION_PATH, COURSE_FIRING_DOC);
      await setDoc(ref, {
        enabled: next.enabled,
        courses: next.courses.map((c, i) => ({
          id: c.id,
          name: c.name,
          order: i,
          delayAfterReadySeconds: c.delayAfterReadySeconds,
        })),
      });
      setDirty(false);
    } finally {
      setSaving(false);
    }
  };

  const toggleEnabled = () => {
    const next = { ...settings, enabled: !settings.enabled };
    setSettings(next);
    save(next);
  };

  const updateCourse = (idx: number, patch: Partial<CourseDefinition>) => {
    const courses = settings.courses.map((c, i) => (i === idx ? { ...c, ...patch } : c));
    const next = { ...settings, courses };
    setSettings(next);
    setDirty(true);
  };

  const moveCourse = (idx: number, dir: -1 | 1) => {
    const target = idx + dir;
    if (target < 0 || target >= settings.courses.length) return;
    const courses = [...settings.courses];
    [courses[idx], courses[target]] = [courses[target], courses[idx]];
    const reordered = courses.map((c, i) => ({ ...c, order: i }));
    const next = { ...settings, courses: reordered };
    setSettings(next);
    save(next);
  };

  const addCourse = () => {
    const maxOrder = settings.courses.length;
    const newId = `COURSE_${Date.now()}`;
    const courses = [
      ...settings.courses,
      { id: newId, name: "", order: maxOrder, delayAfterReadySeconds: 180 },
    ];
    const next = { ...settings, courses };
    setSettings(next);
    setDirty(true);
  };

  const removeCourse = (idx: number) => {
    const courses = settings.courses.filter((_, i) => i !== idx).map((c, i) => ({ ...c, order: i }));
    const next = { ...settings, courses };
    setSettings(next);
    save(next);
  };

  const resetToDefaults = () => {
    const next: CourseFiringSettings = { enabled: settings.enabled, courses: [...DEFAULT_COURSES] };
    setSettings(next);
    save(next);
  };

  return (
    <>
      <Header title="Course Firing" />
      <div className="p-6 max-w-4xl">
        <p className="text-sm text-gray-500 mb-6">
          Control the order in which courses are sent to the kitchen for dine-in orders.
          When enabled, items fire in sequence — the next course is sent after the previous
          course is marked Ready on the KDS, plus an optional delay.
        </p>

        <div className="flex items-center gap-3 mb-8">
          <button
            type="button"
            onClick={toggleEnabled}
            className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
              settings.enabled ? "bg-indigo-600" : "bg-gray-300"
            }`}
          >
            <span
              className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                settings.enabled ? "translate-x-6" : "translate-x-1"
              }`}
            />
          </button>
          <span className="text-sm font-medium text-gray-700">
            {settings.enabled ? "Enabled" : "Disabled"}
          </span>
        </div>

        <div className="space-y-4 mb-6">
          {settings.courses.map((course, idx) => (
            <div
              key={course.id}
              className="p-4 bg-white rounded-lg border border-gray-200 space-y-3"
            >
              <div className="flex flex-wrap items-center gap-2 sm:gap-3">
                <div className="flex flex-col gap-0.5">
                  <button
                    type="button"
                    onClick={() => moveCourse(idx, -1)}
                    disabled={idx === 0}
                    className="text-gray-400 hover:text-gray-700 disabled:opacity-30"
                  >
                    <ArrowUp size={14} />
                  </button>
                  <button
                    type="button"
                    onClick={() => moveCourse(idx, 1)}
                    disabled={idx === settings.courses.length - 1}
                    className="text-gray-400 hover:text-gray-700 disabled:opacity-30"
                  >
                    <ArrowDown size={14} />
                  </button>
                </div>

                <span className="text-xs text-gray-400 font-mono w-6 text-center shrink-0">
                  {idx + 1}
                </span>

                <input
                  type="text"
                  value={course.name}
                  onChange={(e) => updateCourse(idx, { name: e.target.value })}
                  onBlur={() => {
                    if (dirty) save(settings);
                  }}
                  placeholder="Course name"
                  className="min-w-[8rem] flex-1 border border-gray-200 rounded px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-indigo-500"
                />

                <div className="flex items-center gap-1.5 shrink-0">
                  <label className="text-xs text-gray-500 whitespace-nowrap">Delay after ready:</label>
                  <input
                    type="number"
                    min={0}
                    value={Math.round(course.delayAfterReadySeconds / 60)}
                    onChange={(e) =>
                      updateCourse(idx, {
                        delayAfterReadySeconds: Math.max(0, Number(e.target.value) || 0) * 60,
                      })
                    }
                    onBlur={() => {
                      if (dirty) save(settings);
                    }}
                    className="w-16 border border-gray-200 rounded px-2 py-1.5 text-sm text-center focus:outline-none focus:ring-1 focus:ring-indigo-500"
                  />
                  <span className="text-xs text-gray-400">min</span>
                </div>

                <div className="flex flex-wrap gap-1.5 items-center w-full sm:w-auto sm:ml-auto justify-end">
                  <button
                    type="button"
                    onClick={() => openPicker(course, "category")}
                    disabled={savingAssign}
                    className="px-2 py-1 text-xs font-medium text-indigo-700 bg-indigo-50 border border-indigo-200 rounded-md hover:bg-indigo-100 disabled:opacity-50"
                  >
                    Add category
                  </button>
                  <button
                    type="button"
                    onClick={() => openPicker(course, "subcategory")}
                    disabled={savingAssign}
                    className="px-2 py-1 text-xs font-medium text-indigo-700 bg-indigo-50 border border-indigo-200 rounded-md hover:bg-indigo-100 disabled:opacity-50"
                  >
                    Add subcategory
                  </button>
                  <button
                    type="button"
                    onClick={() => openPicker(course, "item")}
                    disabled={savingAssign}
                    className="px-2 py-1 text-xs font-medium text-indigo-700 bg-indigo-50 border border-indigo-200 rounded-md hover:bg-indigo-100 disabled:opacity-50"
                  >
                    Add item
                  </button>
                  <button
                    type="button"
                    onClick={() => removeCourse(idx)}
                    className="text-gray-400 hover:text-red-500 p-1"
                    aria-label="Delete course"
                  >
                    <Trash2 size={15} />
                  </button>
                </div>
              </div>

              <CourseAssignmentsSummary
                courseId={course.id}
                assignedCategories={assignedCategoriesForCourse(course.id)}
                assignedSubcategories={assignedSubcategoriesForCourse(course.id)}
                assignedItems={assignedItemsForCourse(course.id)}
                catNameById={catNameById}
                onRemoveCategory={(id) => void clearCategoryAssignment(id)}
                onRemoveSubcategory={(id) => void clearSubcategoryAssignment(id)}
                onRemoveItem={(id) => void clearItemAssignment(id)}
                busy={savingAssign}
              />
            </div>
          ))}
        </div>

        <div className="flex gap-3">
          <button
            type="button"
            onClick={addCourse}
            className="inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50"
          >
            <Plus size={14} /> Add course
          </button>
          <button
            type="button"
            onClick={resetToDefaults}
            className="inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-gray-500 hover:text-gray-700"
          >
            Reset to defaults
          </button>
        </div>

        {saving && (
          <p className="text-xs text-gray-400 mt-4 flex items-center gap-1">
            <Loader2 size={12} className="animate-spin" /> Saving…
          </p>
        )}
      </div>

      {picker && (
        <AssignmentPickerModal
          picker={picker}
          filter={pickerFilter}
          onFilterChange={setPickerFilter}
          categories={categories}
          subcategories={subcategories}
          menuItems={menuItems}
          catNameById={catNameById}
          saving={savingAssign}
          onClose={() => setPicker(null)}
          onPickCategory={async (categoryId) => {
            await assignCategoryToCourse(categoryId, picker.courseId);
            setPicker(null);
          }}
          onPickSubcategory={async (subId) => {
            await assignSubcategoryToCourse(subId, picker.courseId);
            setPicker(null);
          }}
          onPickItem={async (itemId) => {
            await assignItemToCourse(itemId, picker.courseId);
            setPicker(null);
          }}
        />
      )}
    </>
  );
}

function CourseAssignmentsSummary({
  courseId,
  assignedCategories,
  assignedSubcategories,
  assignedItems,
  catNameById,
  onRemoveCategory,
  onRemoveSubcategory,
  onRemoveItem,
  busy,
}: {
  courseId: string;
  assignedCategories: CategoryRow[];
  assignedSubcategories: SubcategoryRow[];
  assignedItems: CourseMenuItem[];
  catNameById: Map<string, string>;
  onRemoveCategory: (id: string) => void;
  onRemoveSubcategory: (id: string) => void;
  onRemoveItem: (id: string) => void;
  busy: boolean;
}) {
  const empty =
    assignedCategories.length === 0 &&
    assignedSubcategories.length === 0 &&
    assignedItems.length === 0;
  if (empty) {
    return (
      <p className="text-xs text-gray-400 pl-1">
        No categories, subcategories, or items assigned to this course yet.
      </p>
    );
  }
  return (
    <div className="border-t border-gray-100 pt-2 space-y-2">
      {assignedCategories.length > 0 && (
        <div className="flex flex-wrap gap-1.5 items-center">
          <span className="text-[10px] uppercase tracking-wide text-gray-400 shrink-0">Categories</span>
          {assignedCategories.map((c) => (
            <span
              key={`cat-${courseId}-${c.id}`}
              className="inline-flex items-center gap-1 pl-2 pr-1 py-0.5 rounded-full bg-gray-100 text-xs text-gray-800"
            >
              {c.name}
              <button
                type="button"
                disabled={busy}
                onClick={() => onRemoveCategory(c.id)}
                className="p-0.5 rounded-full hover:bg-gray-200 text-gray-500 disabled:opacity-40"
                aria-label={`Remove ${c.name}`}
              >
                <X size={12} />
              </button>
            </span>
          ))}
        </div>
      )}
      {assignedSubcategories.length > 0 && (
        <div className="flex flex-wrap gap-1.5 items-center">
          <span className="text-[10px] uppercase tracking-wide text-gray-400 shrink-0">
            Subcategories
          </span>
          {assignedSubcategories.map((s) => (
            <span
              key={`sub-${courseId}-${s.id}`}
              className="inline-flex items-center gap-1 pl-2 pr-1 py-0.5 rounded-full bg-amber-50 border border-amber-100 text-xs text-gray-800"
            >
              {s.name}
              <span className="text-[10px] text-gray-400">
                ({catNameById.get(s.categoryId) ?? "—"})
              </span>
              <button
                type="button"
                disabled={busy}
                onClick={() => onRemoveSubcategory(s.id)}
                className="p-0.5 rounded-full hover:bg-amber-100 text-gray-500 disabled:opacity-40"
                aria-label={`Remove ${s.name}`}
              >
                <X size={12} />
              </button>
            </span>
          ))}
        </div>
      )}
      {assignedItems.length > 0 && (
        <div className="flex flex-wrap gap-1.5 items-center">
          <span className="text-[10px] uppercase tracking-wide text-gray-400 shrink-0">Items</span>
          {assignedItems.map((it) => (
            <span
              key={`it-${courseId}-${it.id}`}
              className="inline-flex items-center gap-1 pl-2 pr-1 py-0.5 rounded-full bg-indigo-50 border border-indigo-100 text-xs text-gray-800 max-w-[220px]"
            >
              <span className="truncate">{it.name}</span>
              <button
                type="button"
                disabled={busy}
                onClick={() => onRemoveItem(it.id)}
                className="p-0.5 rounded-full hover:bg-indigo-100 text-gray-500 shrink-0 disabled:opacity-40"
                aria-label={`Remove ${it.name}`}
              >
                <X size={12} />
              </button>
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

function AssignmentPickerModal({
  picker,
  filter,
  onFilterChange,
  categories,
  subcategories,
  menuItems,
  catNameById,
  saving,
  onClose,
  onPickCategory,
  onPickSubcategory,
  onPickItem,
}: {
  picker: { courseId: string; courseLabel: string; kind: PickerKind };
  filter: string;
  onFilterChange: (v: string) => void;
  categories: CategoryRow[];
  subcategories: SubcategoryRow[];
  menuItems: CourseMenuItem[];
  catNameById: Map<string, string>;
  saving: boolean;
  onClose: () => void;
  onPickCategory: (categoryId: string) => Promise<void>;
  onPickSubcategory: (subId: string) => Promise<void>;
  onPickItem: (itemId: string) => Promise<void>;
}) {
  const f = filter.trim().toLowerCase();

  const categoryOwner = (catId: string) => courseForCategory(catId, menuItems);
  const subOwner = (subId: string) => courseForSubcategory(subId, menuItems);

  const categoryRows = categories
    .map((c) => ({ c, disabled: exclusiveDisabled(categoryOwner(c.id), picker.courseId) }))
    .filter(({ c }) => !f || c.name.toLowerCase().includes(f));

  const subRows = subcategories
    .map((s) => ({
      s,
      disabled: exclusiveDisabled(subOwner(s.id), picker.courseId),
      label: `${s.name} (${catNameById.get(s.categoryId) ?? "—"})`,
    }))
    .filter(({ label }) => !f || label.toLowerCase().includes(f));

  const itemRows = menuItems
    .map((it) => {
      const owner = it.courseId ?? "";
      const disabled = owner.length > 0 && owner !== picker.courseId;
      const catLabel = catNameById.get(it.categoryId) ?? "";
      return { it, disabled: !!disabled, sub: `${it.name} ${catLabel}`.toLowerCase() };
    })
    .filter(({ it, sub }) => !f || it.name.toLowerCase().includes(f) || sub.includes(f));

  const title =
    picker.kind === "category"
      ? "Add category"
      : picker.kind === "subcategory"
        ? "Add subcategory"
        : "Add item";

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40"
      role="dialog"
      aria-modal="true"
      aria-labelledby="course-picker-title"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        className="bg-white rounded-xl shadow-xl max-w-md w-full max-h-[min(480px,85vh)] flex flex-col border border-gray-200"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="px-4 py-3 border-b border-gray-100 flex items-start justify-between gap-2">
          <div>
            <h2 id="course-picker-title" className="text-sm font-semibold text-gray-900">
              {title}
            </h2>
            <p className="text-xs text-gray-500 mt-0.5">
              Assign to <span className="font-medium text-gray-700">{picker.courseLabel}</span>.
              Choices already used on another course are grayed out.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="p-1 rounded-md text-gray-400 hover:text-gray-700 hover:bg-gray-100"
            aria-label="Close"
          >
            <X size={18} />
          </button>
        </div>
        <div className="px-4 py-2 border-b border-gray-50">
          <input
            type="search"
            placeholder="Search…"
            value={filter}
            onChange={(e) => onFilterChange(e.target.value)}
            className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-indigo-500"
          />
        </div>
        <div className="overflow-y-auto flex-1 p-2">
          {picker.kind === "category" &&
            categoryRows.map(({ c, disabled }) => (
              <button
                key={c.id}
                type="button"
                disabled={disabled || saving}
                onClick={() => void onPickCategory(c.id)}
                className={`w-full text-left px-3 py-2 rounded-lg text-sm mb-0.5 ${
                  disabled
                    ? "text-gray-300 cursor-not-allowed bg-gray-50"
                    : "text-gray-800 hover:bg-indigo-50"
                }`}
              >
                {c.name}
                {disabled && (
                  <span className="block text-[10px] text-gray-400">Assigned to another course</span>
                )}
              </button>
            ))}
          {picker.kind === "subcategory" &&
            subRows.map(({ s, disabled, label }) => (
              <button
                key={s.id}
                type="button"
                disabled={disabled || saving}
                onClick={() => void onPickSubcategory(s.id)}
                className={`w-full text-left px-3 py-2 rounded-lg text-sm mb-0.5 ${
                  disabled
                    ? "text-gray-300 cursor-not-allowed bg-gray-50"
                    : "text-gray-800 hover:bg-indigo-50"
                }`}
              >
                {label}
                {disabled && (
                  <span className="block text-[10px] text-gray-400">Assigned to another course</span>
                )}
              </button>
            ))}
          {picker.kind === "item" &&
            itemRows.map(({ it, disabled }) => (
              <button
                key={it.id}
                type="button"
                disabled={disabled || saving}
                onClick={() => void onPickItem(it.id)}
                className={`w-full text-left px-3 py-2 rounded-lg text-sm mb-0.5 ${
                  disabled
                    ? "text-gray-300 cursor-not-allowed bg-gray-50"
                    : "text-gray-800 hover:bg-indigo-50"
                }`}
              >
                <span className="font-medium">{it.name}</span>
                <span className="text-xs text-gray-400 ml-2">{catNameById.get(it.categoryId) ?? ""}</span>
                {disabled && (
                  <span className="block text-[10px] text-gray-400">Assigned to another course</span>
                )}
              </button>
            ))}
          {picker.kind === "category" && categoryRows.length === 0 && (
            <p className="text-sm text-gray-400 px-2 py-4">No categories match.</p>
          )}
          {picker.kind === "subcategory" && subRows.length === 0 && (
            <p className="text-sm text-gray-400 px-2 py-4">No subcategories match.</p>
          )}
          {picker.kind === "item" && itemRows.length === 0 && (
            <p className="text-sm text-gray-400 px-2 py-4">No items match.</p>
          )}
        </div>
        {saving && (
          <div className="px-4 py-2 border-t border-gray-100 text-xs text-gray-500 flex items-center gap-2">
            <Loader2 size={12} className="animate-spin" /> Updating…
          </div>
        )}
      </div>
    </div>
  );
}

function exclusiveDisabled(owner: string, thisCourseId: string): boolean {
  return isExclusiveOwner(owner) && owner !== thisCourseId;
}
