"use client";

import { useEffect, useState } from "react";
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
import { ArrowDown, ArrowUp, Loader2, Plus, Trash2 } from "lucide-react";

interface CategoryRow {
  id: string;
  name: string;
}

interface MenuItemRow {
  id: string;
  name: string;
  categoryId: string;
  categoryIds?: string[];
  courseId?: string;
}

export default function CourseFiringPage() {
  const { user, loading: authLoading } = useAuth();
  const [settings, setSettings] = useState<CourseFiringSettings | null>(null);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [categories, setCategories] = useState<CategoryRow[]>([]);
  const [menuItems, setMenuItems] = useState<MenuItemRow[]>([]);
  const [assignMode, setAssignMode] = useState<"category" | "item">("category");
  const [savingAssign, setSavingAssign] = useState(false);

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
    const unsub2 = onSnapshot(collection(db, "MenuItems"), (snap) => {
      const list: MenuItemRow[] = [];
      snap.forEach((d) => {
        const data = d.data();
        const rawCatIds = Array.isArray(data.categoryIds)
          ? (data.categoryIds as string[]).filter((x) => typeof x === "string" && x.length > 0)
          : undefined;
        list.push({
          id: d.id,
          name: String(data.name ?? "Item"),
          categoryId: String(data.categoryId ?? ""),
          categoryIds: rawCatIds && rawCatIds.length > 0 ? rawCatIds : undefined,
          courseId: typeof data.courseId === "string" ? data.courseId : undefined,
        });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setMenuItems(list);
    });
    return () => { unsub1(); unsub2(); };
  }, [user]);

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
      <div className="p-6 max-w-3xl">
        <p className="text-sm text-gray-500 mb-6">
          Control the order in which courses are sent to the kitchen for dine-in orders.
          When enabled, items fire in sequence — the next course is sent after the previous
          course is marked Ready on the KDS, plus an optional delay.
        </p>

        <div className="flex items-center gap-3 mb-8">
          <button
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

        <div className="space-y-3 mb-6">
          {settings.courses.map((course, idx) => (
            <div
              key={course.id}
              className="flex items-center gap-3 p-4 bg-white rounded-lg border border-gray-200"
            >
              <div className="flex flex-col gap-0.5">
                <button
                  onClick={() => moveCourse(idx, -1)}
                  disabled={idx === 0}
                  className="text-gray-400 hover:text-gray-700 disabled:opacity-30"
                >
                  <ArrowUp size={14} />
                </button>
                <button
                  onClick={() => moveCourse(idx, 1)}
                  disabled={idx === settings.courses.length - 1}
                  className="text-gray-400 hover:text-gray-700 disabled:opacity-30"
                >
                  <ArrowDown size={14} />
                </button>
              </div>

              <span className="text-xs text-gray-400 font-mono w-6 text-center">{idx + 1}</span>

              <input
                type="text"
                value={course.name}
                onChange={(e) => updateCourse(idx, { name: e.target.value })}
                onBlur={() => { if (dirty) save(settings); }}
                placeholder="Course name"
                className="flex-1 border border-gray-200 rounded px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-indigo-500"
              />

              <div className="flex items-center gap-1.5">
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
                  onBlur={() => { if (dirty) save(settings); }}
                  className="w-16 border border-gray-200 rounded px-2 py-1.5 text-sm text-center focus:outline-none focus:ring-1 focus:ring-indigo-500"
                />
                <span className="text-xs text-gray-400">min</span>
              </div>

              <button
                onClick={() => removeCourse(idx)}
                className="text-gray-400 hover:text-red-500 p-1"
              >
                <Trash2 size={15} />
              </button>
            </div>
          ))}
        </div>

        <div className="flex gap-3">
          <button
            onClick={addCourse}
            className="inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50"
          >
            <Plus size={14} /> Add course
          </button>
          <button
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

        <hr className="my-10 border-gray-200" />

        <h2 className="text-lg font-semibold text-gray-800 mb-2">Assign items to courses</h2>
        <p className="text-sm text-gray-500 mb-4">
          Choose a course for each category (all items in that category will fire together),
          or switch to per-item assignment for finer control.
        </p>

        <div className="flex gap-2 mb-4">
          <button
            onClick={() => setAssignMode("category")}
            className={`px-3 py-1.5 text-sm rounded-lg border ${
              assignMode === "category"
                ? "bg-indigo-50 border-indigo-300 text-indigo-700"
                : "border-gray-200 text-gray-600 hover:bg-gray-50"
            }`}
          >
            By category
          </button>
          <button
            onClick={() => setAssignMode("item")}
            className={`px-3 py-1.5 text-sm rounded-lg border ${
              assignMode === "item"
                ? "bg-indigo-50 border-indigo-300 text-indigo-700"
                : "border-gray-200 text-gray-600 hover:bg-gray-50"
            }`}
          >
            By item
          </button>
        </div>

        {assignMode === "category" && (
          <CategoryCourseAssign
            categories={categories}
            menuItems={menuItems}
            courses={settings.courses}
            saving={savingAssign}
            onAssign={async (categoryId, courseId) => {
              setSavingAssign(true);
              try {
                const items = menuItems.filter((it) => {
                  const placements = it.categoryIds ?? (it.categoryId ? [it.categoryId] : []);
                  return placements.includes(categoryId);
                });
                if (items.length === 0) return;
                const batch = writeBatch(db);
                for (const it of items) {
                  batch.update(doc(db, "MenuItems", it.id), { courseId: courseId || null });
                }
                await batch.commit();
              } finally {
                setSavingAssign(false);
              }
            }}
          />
        )}

        {assignMode === "item" && (
          <ItemCourseAssign
            menuItems={menuItems}
            courses={settings.courses}
            categories={categories}
            saving={savingAssign}
            onAssign={async (itemId, courseId) => {
              setSavingAssign(true);
              try {
                await setDoc(doc(db, "MenuItems", itemId), { courseId: courseId || null }, { merge: true });
              } finally {
                setSavingAssign(false);
              }
            }}
          />
        )}
      </div>
    </>
  );
}

function CategoryCourseAssign({
  categories,
  menuItems,
  courses,
  saving,
  onAssign,
}: {
  categories: CategoryRow[];
  menuItems: MenuItemRow[];
  courses: CourseDefinition[];
  saving: boolean;
  onAssign: (categoryId: string, courseId: string) => Promise<void>;
}) {
  const courseForCategory = (catId: string): string => {
    const items = menuItems.filter((it) => {
      const placements = it.categoryIds ?? (it.categoryId ? [it.categoryId] : []);
      return placements.includes(catId);
    });
    if (items.length === 0) return "";
    const first = items[0].courseId ?? "";
    if (items.every((it) => (it.courseId ?? "") === first)) return first;
    return "__mixed__";
  };

  return (
    <div className="space-y-2">
      {categories.map((cat) => {
        const current = courseForCategory(cat.id);
        return (
          <div key={cat.id} className="flex items-center gap-3 py-2 px-3 bg-white border border-gray-100 rounded-lg">
            <span className="text-sm text-gray-800 flex-1">{cat.name}</span>
            <select
              value={current}
              disabled={saving}
              onChange={(e) => onAssign(cat.id, e.target.value)}
              className="text-sm border border-gray-200 rounded px-2 py-1 focus:outline-none focus:ring-1 focus:ring-indigo-500"
            >
              <option value="">— None —</option>
              {current === "__mixed__" && <option value="__mixed__" disabled>(Mixed)</option>}
              {courses.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          </div>
        );
      })}
      {categories.length === 0 && (
        <p className="text-sm text-gray-400">No categories found.</p>
      )}
    </div>
  );
}

function ItemCourseAssign({
  menuItems,
  courses,
  categories,
  saving,
  onAssign,
}: {
  menuItems: MenuItemRow[];
  courses: CourseDefinition[];
  categories: CategoryRow[];
  saving: boolean;
  onAssign: (itemId: string, courseId: string) => Promise<void>;
}) {
  const [filter, setFilter] = useState("");
  const catMap = new Map(categories.map((c) => [c.id, c.name]));
  const filtered = filter
    ? menuItems.filter((it) => it.name.toLowerCase().includes(filter.toLowerCase()))
    : menuItems;

  return (
    <div>
      <input
        type="text"
        placeholder="Filter items…"
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
        className="w-full mb-3 border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-indigo-500"
      />
      <div className="space-y-1 max-h-96 overflow-y-auto">
        {filtered.map((item) => (
          <div key={item.id} className="flex items-center gap-3 py-1.5 px-3 bg-white border border-gray-100 rounded">
            <div className="flex-1 min-w-0">
              <span className="text-sm text-gray-800 block truncate">{item.name}</span>
              <span className="text-xs text-gray-400">{catMap.get(item.categoryId) ?? ""}</span>
            </div>
            <select
              value={item.courseId ?? ""}
              disabled={saving}
              onChange={(e) => onAssign(item.id, e.target.value)}
              className="text-sm border border-gray-200 rounded px-2 py-1 focus:outline-none focus:ring-1 focus:ring-indigo-500"
            >
              <option value="">— None —</option>
              {courses.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          </div>
        ))}
      </div>
      {filtered.length === 0 && (
        <p className="text-sm text-gray-400 mt-2">No items found.</p>
      )}
    </div>
  );
}
