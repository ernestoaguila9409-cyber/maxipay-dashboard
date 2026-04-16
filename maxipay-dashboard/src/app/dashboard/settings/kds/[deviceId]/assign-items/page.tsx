"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  collection,
  doc,
  onSnapshot,
  updateDoc,
  serverTimestamp,
} from "firebase/firestore";
import { FirebaseError } from "firebase/app";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import {
  deriveSelectedItemIdsFromDevice,
  normalizeAssignmentForSave,
  placementCategoryIds,
  type CategoryRow,
  type MenuItemForKds,
} from "@/lib/kdsMenuAssignment";
import { ArrowLeft, Search, Layers, UtensilsCrossed } from "lucide-react";

const KDS_DEVICES_COLLECTION = "kds_devices";
const CATEGORIES_COLLECTION = "Categories";
const MENU_ITEMS_COLLECTION = "MenuItems";

function parseStringArrayField(data: Record<string, unknown>, key: string): string[] {
  const raw = data[key];
  if (!Array.isArray(raw)) return [];
  return raw
    .map((x) => String(x ?? "").trim())
    .filter((x) => x.length > 0);
}

export default function KdsAssignItemsPage() {
  const { user } = useAuth();
  const router = useRouter();
  const params = useParams();
  const deviceId = String(params.deviceId ?? "").trim();

  const [deviceName, setDeviceName] = useState("");
  const [categories, setCategories] = useState<CategoryRow[]>([]);
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
        const cats = parseStringArrayField(data, "assignedCategoryIds");
        const items = parseStringArrayField(data, "assignedItemIds");
        setAssignedCategoryIds(cats);
        setAssignedItemIds(items);
        if (!dirty) {
          setSelectedItemIds(new Set()); // placeholder until menuItems load merges in effect
        }
        setLoadError(null);
      },
      (err) => {
        console.error("[KDS assign] device:", err);
        setLoadError("Could not load device.");
        setLoading(false);
      }
    );

    const unsubCats = onSnapshot(
      collection(db, CATEGORIES_COLLECTION),
      (snap) => {
        const list: CategoryRow[] = [];
        snap.forEach((d) => {
          const data = d.data() as Record<string, unknown>;
          const name = String(data.name ?? "").trim();
          if (name) list.push({ id: d.id, name });
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
          const data = d.data() as Record<string, unknown>;
          const name = String(data.name ?? "").trim();
          if (!name) return;
          list.push({
            id: d.id,
            name,
            placements: placementCategoryIds(data),
          });
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

    return () => {
      unsubDevice();
      unsubCats();
      unsubItems();
    };
  }, [user, deviceId]);

  useEffect(() => {
    if (dirty) return;
    if (menuItems.length === 0) return;
    setSelectedItemIds(
      deriveSelectedItemIdsFromDevice(assignedCategoryIds, assignedItemIds, menuItems)
    );
  }, [assignedCategoryIds, assignedItemIds, menuItems, dirty]);

  const searchLower = search.trim().toLowerCase();

  const filteredCategories = useMemo(() => {
    if (!searchLower) return categories;
    return categories.filter((c) => c.name.toLowerCase().includes(searchLower));
  }, [categories, searchLower]);

  const filteredItems = useMemo(() => {
    if (!searchLower) return menuItems;
    return menuItems.filter((it) => {
      if (it.name.toLowerCase().includes(searchLower)) return true;
      return it.placements.some((pid) => {
        const cat = categories.find((c) => c.id === pid);
        return cat?.name.toLowerCase().includes(searchLower);
      });
    });
  }, [menuItems, categories, searchLower]);

  const itemsInCategory = useCallback(
    (categoryId: string) =>
      menuItems.filter((it) => it.placements.includes(categoryId)).map((it) => it.id),
    [menuItems]
  );

  const categoryVisualState = useCallback(
    (categoryId: string) => {
      const ids = itemsInCategory(categoryId);
      if (ids.length === 0) return { checked: false, indeterminate: false };
      let n = 0;
      for (const id of ids) if (selectedItemIds.has(id)) n++;
      if (n === 0) return { checked: false, indeterminate: false };
      if (n === ids.length) return { checked: true, indeterminate: false };
      return { checked: false, indeterminate: true };
    },
    [itemsInCategory, selectedItemIds]
  );

  const toggleCategory = (categoryId: string, turnOn: boolean) => {
    setDirty(true);
    setSaveOk(false);
    setSelectedItemIds((prev) => {
      const next = new Set(prev);
      const ids = itemsInCategory(categoryId);
      if (turnOn) {
        for (const id of ids) next.add(id);
      } else {
        for (const it of menuItems) {
          if (!it.placements.includes(categoryId)) continue;
          next.delete(it.id);
        }
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

  const filteredItemIds = useMemo(
    () => new Set(filteredItems.map((it) => it.id)),
    [filteredItems]
  );

  const selectAllVisible = () => {
    setDirty(true);
    setSaveOk(false);
    setSelectedItemIds((prev) => {
      const next = new Set(prev);
      for (const id of filteredItemIds) next.add(id);
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
              {deviceName || "Loading…"} · updates in real time from Menu
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
              onClick={selectAllVisible}
              disabled={loading || filteredItems.length === 0}
              className="rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50 transition-colors"
            >
              Select all visible
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
          <div className="grid min-h-[420px] gap-4 lg:grid-cols-2">
            <section className="flex flex-col rounded-2xl border border-slate-200 bg-white shadow-sm overflow-hidden">
              <div className="flex items-center gap-2 border-b border-slate-100 bg-slate-50/80 px-4 py-3">
                <Layers className="h-5 w-5 text-slate-500" aria-hidden />
                <h2 className="font-semibold text-slate-800">Categories</h2>
                <span className="ml-auto text-xs font-medium text-slate-400">
                  {filteredCategories.length}
                </span>
              </div>
              <div className="max-h-[min(560px,calc(100vh-16rem))] overflow-y-auto p-3">
                {filteredCategories.length === 0 ? (
                  <p className="px-2 py-8 text-center text-sm text-slate-500">
                    No categories match this search.
                  </p>
                ) : (
                  <ul className="space-y-1">
                    {filteredCategories.map((c) => {
                      const { checked, indeterminate } = categoryVisualState(c.id);
                      return (
                        <li key={c.id}>
                          <label className="flex cursor-pointer items-center gap-3 rounded-xl px-3 py-2.5 text-sm text-slate-800 hover:bg-slate-50">
                            <input
                              type="checkbox"
                              ref={(el) => {
                                if (el) el.indeterminate = indeterminate;
                              }}
                              checked={checked}
                              onChange={(e) => toggleCategory(c.id, e.target.checked)}
                              className="h-4 w-4 shrink-0 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                            />
                            <span className="min-w-0 font-medium">{c.name}</span>
                          </label>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
            </section>

            <section className="flex flex-col rounded-2xl border border-slate-200 bg-white shadow-sm overflow-hidden">
              <div className="flex items-center gap-2 border-b border-slate-100 bg-slate-50/80 px-4 py-3">
                <UtensilsCrossed className="h-5 w-5 text-slate-500" aria-hidden />
                <h2 className="font-semibold text-slate-800">Menu items</h2>
                <span className="ml-auto text-xs font-medium text-slate-400">
                  {filteredItems.length}
                </span>
              </div>
              <div className="max-h-[min(560px,calc(100vh-16rem))] overflow-y-auto p-3">
                {filteredItems.length === 0 ? (
                  <p className="px-2 py-8 text-center text-sm text-slate-500">
                    No items match this search.
                  </p>
                ) : (
                  <ul className="space-y-1">
                    {filteredItems.map((it) => {
                      const checked = selectedItemIds.has(it.id);
                      const catLabels = it.placements
                        .map((pid) => categories.find((c) => c.id === pid)?.name)
                        .filter(Boolean)
                        .join(" · ");
                      return (
                        <li key={it.id}>
                          <label className="flex cursor-pointer items-start gap-3 rounded-xl px-3 py-2.5 text-sm hover:bg-slate-50">
                            <input
                              type="checkbox"
                              checked={checked}
                              onChange={() => toggleItem(it.id)}
                              className="mt-0.5 h-4 w-4 shrink-0 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                            />
                            <span className="min-w-0 flex-1">
                              <span className="font-medium text-slate-800">{it.name}</span>
                              {catLabels ? (
                                <span className="mt-0.5 block text-xs text-slate-500">
                                  {catLabels}
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
            </section>
          </div>
        )}

        <p className="mt-6 text-center text-xs text-slate-500">
          Tickets appear on this KDS if any line&apos;s menu item is in an assigned
          category or explicitly listed. Leave both empty on the device to show all
          tickets.
        </p>
      </div>
    </div>
  );
}
