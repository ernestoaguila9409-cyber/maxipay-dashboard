"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  collection,
  doc,
  onSnapshot,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import {
  DEFAULT_ONLINE_ORDERING_SETTINGS,
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  parseOnlineOrderingSettings,
  type OnlineOrderingSettings,
} from "@/lib/onlineOrderingShared";
import {
  isMenuItemVisibleOnOnlineChannel,
  menuItemPlacementCategoryIds,
} from "@/lib/onlineMenuCuration";
import { Check, Globe, Loader2, Pencil, Plus } from "lucide-react";

interface CategoryRow {
  id: string;
  name: string;
  sortOrder: number;
}

interface ItemRow {
  id: string;
  name: string;
  /** Placement categories for this item (may be empty). */
  categoryIds: string[];
  /** Full Firestore fields for visibility rules. */
  firestoreData: Record<string, unknown>;
}

function parseItem(docId: string, data: Record<string, unknown>): ItemRow {
  return {
    id: docId,
    name: typeof data.name === "string" ? data.name : "Item",
    categoryIds: menuItemPlacementCategoryIds(data),
    firestoreData: data,
  };
}

export default function OnlineMenuPage() {
  const { user } = useAuth();
  const [categories, setCategories] = useState<CategoryRow[]>([]);
  const [items, setItems] = useState<ItemRow[]>([]);
  const [oo, setOo] = useState<OnlineOrderingSettings>(DEFAULT_ONLINE_ORDERING_SETTINGS);
  const [loading, setLoading] = useState(true);

  /** setup = no curated menu yet; summary = saved curation; edit = picking items */
  const [mode, setMode] = useState<"setup" | "summary" | "edit">("setup");
  const [selectedCat, setSelectedCat] = useState<Set<string>>(new Set());
  const [selectedItem, setSelectedItem] = useState<Set<string>>(new Set());
  const [saving, setSaving] = useState(false);
  const [saveErr, setSaveErr] = useState<string | null>(null);

  useEffect(() => {
    if (!user) return;
    const unsubs = [
      onSnapshot(
        query(collection(db, "Categories"), orderBy("sortOrder")),
        (snap) => {
          const rows: CategoryRow[] = [];
          snap.forEach((d) => {
            const data = d.data();
            rows.push({
              id: d.id,
              name: (data.name as string) || "Category",
              sortOrder: typeof data.sortOrder === "number" ? data.sortOrder : 0,
            });
          });
          rows.sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));
          setCategories(rows);
        },
        () => setCategories([])
      ),
      onSnapshot(collection(db, "MenuItems"), (snap) => {
        const rows: ItemRow[] = [];
        snap.forEach((d) => rows.push(parseItem(d.id, d.data() as Record<string, unknown>)));
        rows.sort((a, b) => a.name.localeCompare(b.name));
        setItems(rows);
        setLoading(false);
      }),
      onSnapshot(
        doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC),
        (snap) => {
          const parsed = parseOnlineOrderingSettings(
            snap.data() as Record<string, unknown> | undefined
          );
          setOo(parsed);
          setMode((m) => {
            if (m === "edit") return m;
            return parsed.onlineMenuCurationEnabled ? "summary" : "setup";
          });
        },
        () => {}
      ),
    ];
    return () => unsubs.forEach((u) => u());
  }, [user]);

  const initDraftFromSettings = useCallback(() => {
    setSelectedCat(new Set(oo.onlineMenuCategoryIds));
    setSelectedItem(new Set(oo.onlineMenuItemIds));
  }, [oo.onlineMenuCategoryIds, oo.onlineMenuItemIds]);

  const enterEdit = useCallback(() => {
    initDraftFromSettings();
    setMode("edit");
    setSaveErr(null);
  }, [initDraftFromSettings]);

  const cancelEdit = useCallback(() => {
    setSaveErr(null);
    setMode(oo.onlineMenuCurationEnabled ? "summary" : "setup");
  }, [oo.onlineMenuCurationEnabled]);

  const orphanItems = useMemo(
    () => items.filter((it) => it.categoryIds.length === 0),
    [items]
  );

  const categoryItemMap = useMemo(() => {
    const m = new Map<string, ItemRow[]>();
    for (const c of categories) m.set(c.id, []);
    for (const it of items) {
      const ids = it.categoryIds.length > 0 ? it.categoryIds : [];
      for (const cid of ids) {
        if (!m.has(cid)) m.set(cid, []);
        m.get(cid)!.push(it);
      }
    }
    for (const [, arr] of m) arr.sort((a, b) => a.name.localeCompare(b.name));
    return m;
  }, [categories, items]);

  const itemCoveredByCategory = useCallback(
    (it: ItemRow) => it.categoryIds.some((cid) => selectedCat.has(cid)),
    [selectedCat]
  );

  const toggleCategory = (id: string) => {
    setSelectedCat((prev) => {
      const n = new Set(prev);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  };

  const toggleItem = (it: ItemRow) => {
    if (itemCoveredByCategory(it)) return;
    setSelectedItem((prev) => {
      const n = new Set(prev);
      if (n.has(it.id)) n.delete(it.id);
      else n.add(it.id);
      return n;
    });
  };

  const visibleItems = useMemo(() => {
    return items.filter((it) =>
      isMenuItemVisibleOnOnlineChannel(it.id, it.firestoreData, oo)
    );
  }, [items, oo]);

  const visibleByCategory = useMemo(() => {
    const m = new Map<string, ItemRow[]>();
    for (const c of categories) m.set(c.id, []);
    for (const it of visibleItems) {
      if (it.categoryIds.length === 0) continue;
      const primary =
        categories.find((c) => it.categoryIds.includes(c.id))?.id ?? it.categoryIds[0];
      if (primary && m.has(primary)) m.get(primary)!.push(it);
    }
    for (const [, arr] of m) arr.sort((a, b) => a.name.localeCompare(b.name));
    return m;
  }, [categories, visibleItems]);

  const save = async () => {
    setSaveErr(null);
    const categoryIds = Array.from(selectedCat);
    const itemIds = Array.from(selectedItem).filter((id) => {
      const it = items.find((x) => x.id === id);
      if (!it) return false;
      return !it.categoryIds.some((cid) => selectedCat.has(cid));
    });

    if (categoryIds.length === 0 && itemIds.length === 0) {
      setSaveErr("Select at least one category or item, or cancel.");
      return;
    }

    setSaving(true);
    try {
      await setDoc(
        doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC),
        {
          onlineMenuCurationEnabled: true,
          onlineMenuCategoryIds: categoryIds,
          onlineMenuItemIds: itemIds,
          updatedAt: serverTimestamp(),
        },
        { merge: true }
      );
      setMode("summary");
    } catch (e) {
      setSaveErr((e as Error)?.message ?? "Save failed");
    } finally {
      setSaving(false);
    }
  };

  const disableCuration = async () => {
    if (
      !confirm(
        "Turn off the curated online menu? Guests will follow each item's POS online channel flag again."
      )
    )
      return;
    setSaving(true);
    setSaveErr(null);
    try {
      await setDoc(
        doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC),
        {
          onlineMenuCurationEnabled: false,
          onlineMenuCategoryIds: [],
          onlineMenuItemIds: [],
          updatedAt: serverTimestamp(),
        },
        { merge: true }
      );
      setMode("setup");
    } catch (e) {
      setSaveErr((e as Error)?.message ?? "Update failed");
    } finally {
      setSaving(false);
    }
  };

  if (!user) return null;

  return (
    <>
      <Header title="Online menu" />
      <div className="p-6 max-w-4xl space-y-6">
        <div className="flex items-start gap-3 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <div className="p-2 rounded-xl bg-violet-50 text-violet-700">
            <Globe size={22} />
          </div>
          <div>
            <h2 className="font-semibold text-slate-900">What customers see online</h2>
            <p className="text-sm text-slate-500 mt-1 max-w-2xl">
              Choose whole categories (every item inside) and/or individual items. After you save,
              only those items appear on the public ordering page. This replaces per-item “online”
              channel toggles until you turn the curated menu off.
            </p>
          </div>
        </div>

        {loading ? (
          <div className="flex items-center gap-2 text-slate-500">
            <Loader2 className="animate-spin" size={20} />
            Loading menu…
          </div>
        ) : mode === "setup" ? (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-slate-50/80 p-10 text-center space-y-4">
            <p className="text-slate-600">You have not built a curated online menu yet.</p>
            <button
              type="button"
              onClick={() => {
                setSelectedCat(new Set());
                setSelectedItem(new Set());
                setMode("edit");
                setSaveErr(null);
              }}
              className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700"
            >
              <Plus size={18} />
              Add menu
            </button>
          </div>
        ) : mode === "summary" ? (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-sm text-slate-600">
                <span className="font-semibold text-slate-800">{visibleItems.length}</span> items
                on the online menu
              </p>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={enterEdit}
                  className="inline-flex items-center gap-2 px-4 py-2 rounded-xl border border-slate-200 text-sm font-medium text-slate-700 hover:bg-slate-50"
                >
                  <Pencil size={16} />
                  Edit menu
                </button>
                <button
                  type="button"
                  disabled={saving}
                  onClick={disableCuration}
                  className="text-sm text-red-600 hover:underline disabled:opacity-50"
                >
                  Use POS per-item online channel flags instead
                </button>
              </div>
            </div>
            <div className="space-y-6">
              {categories.map((cat) => {
                const list = visibleByCategory.get(cat.id) ?? [];
                if (list.length === 0) return null;
                return (
                  <div key={cat.id} className="rounded-2xl border border-slate-200 bg-white shadow-sm overflow-hidden">
                    <div className="px-4 py-3 bg-slate-50 border-b border-slate-100 text-sm font-semibold text-slate-800">
                      {cat.name}
                    </div>
                    <ul className="divide-y divide-slate-100">
                      {list.map((it) => (
                        <li key={it.id} className="px-4 py-2.5 text-sm text-slate-700">
                          {it.name}
                        </li>
                      ))}
                    </ul>
                  </div>
                );
              })}
              {visibleItems.some((it) => it.categoryIds.length === 0) ? (
                <div className="rounded-2xl border border-slate-200 bg-white shadow-sm overflow-hidden">
                  <div className="px-4 py-3 bg-slate-50 border-b border-slate-100 text-sm font-semibold text-slate-800">
                    Other
                  </div>
                  <ul className="divide-y divide-slate-100">
                    {visibleItems
                      .filter((it) => it.categoryIds.length === 0)
                      .map((it) => (
                        <li key={it.id} className="px-4 py-2.5 text-sm text-slate-700">
                          {it.name}
                        </li>
                      ))}
                  </ul>
                </div>
              ) : null}
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-sm text-slate-600">
                Check a category to include every item in it, or pick individual items.
              </p>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={cancelEdit}
                  disabled={saving}
                  className="px-4 py-2 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  onClick={save}
                  disabled={saving}
                  className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:bg-blue-300"
                >
                  {saving ? <Loader2 className="animate-spin" size={16} /> : <Check size={16} />}
                  Save
                </button>
              </div>
            </div>
            {saveErr ? (
              <p className="text-sm text-red-600">{saveErr}</p>
            ) : null}

            <div className="space-y-6 max-h-[calc(100vh-280px)] overflow-y-auto pr-1">
              {categories.map((cat) => {
                const catItems = categoryItemMap.get(cat.id) ?? [];
                return (
                  <div
                    key={cat.id}
                    className="rounded-2xl border border-slate-200 bg-white shadow-sm overflow-hidden"
                  >
                    <label className="flex items-center gap-3 px-4 py-3 bg-slate-50 border-b border-slate-100 cursor-pointer hover:bg-slate-100/80">
                      <input
                        type="checkbox"
                        className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                        checked={selectedCat.has(cat.id)}
                        onChange={() => toggleCategory(cat.id)}
                      />
                      <span className="text-sm font-semibold text-slate-800">{cat.name}</span>
                      <span className="text-xs text-slate-400 ml-auto">{catItems.length} items</span>
                    </label>
                    {catItems.length > 0 ? (
                      <ul className="divide-y divide-slate-100">
                        {catItems.map((it) => {
                          const covered = itemCoveredByCategory(it);
                          const checked = covered || selectedItem.has(it.id);
                          return (
                            <li key={it.id} className="flex items-center gap-3 px-4 py-2">
                              <input
                                type="checkbox"
                                className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                                checked={checked}
                                disabled={covered}
                                onChange={() => toggleItem(it)}
                              />
                              <span
                                className={`text-sm flex-1 ${
                                  covered ? "text-slate-400" : "text-slate-700"
                                }`}
                              >
                                {it.name}
                                {covered ? (
                                  <span className="ml-2 text-xs text-slate-400">(via category)</span>
                                ) : null}
                              </span>
                            </li>
                          );
                        })}
                      </ul>
                    ) : (
                      <p className="px-4 py-3 text-xs text-slate-400">No items in this category.</p>
                    )}
                  </div>
                );
              })}
              {orphanItems.length > 0 ? (
                <div className="rounded-2xl border border-amber-200 bg-amber-50/40 shadow-sm overflow-hidden">
                  <div className="px-4 py-3 border-b border-amber-100 text-sm font-semibold text-amber-900">
                    Uncategorized (pick items only; no category checkbox)
                  </div>
                  <ul className="divide-y divide-amber-100/80 bg-white">
                    {orphanItems.map((it) => {
                      const covered = itemCoveredByCategory(it);
                      const checked = covered || selectedItem.has(it.id);
                      return (
                        <li key={it.id} className="flex items-center gap-3 px-4 py-2">
                          <input
                            type="checkbox"
                            className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                            checked={checked}
                            disabled={covered}
                            onChange={() => toggleItem(it)}
                          />
                          <span className="text-sm text-slate-700 flex-1">{it.name}</span>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              ) : null}
            </div>
          </div>
        )}
      </div>
    </>
  );
}
