"use client";

import { useCallback, useMemo, useState } from "react";
import { doc, serverTimestamp, setDoc } from "firebase/firestore";
import { Loader2, Search, Sparkles, X } from "lucide-react";
import { db } from "@/firebase/firebaseConfig";
import {
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
} from "@/lib/onlineOrderingShared";
import type { OnlineMenuCategory, OnlineMenuItem } from "@/lib/onlineOrderingServer";

interface FeaturedItemsManagerProps {
  items: OnlineMenuItem[];
  categories: OnlineMenuCategory[];
  featuredItemIds: string[];
}

/**
 * Lets the owner explicitly pick which items show up in the storefront's "Popular" row.
 * When the list is empty, the storefront falls back to auto-popular (first items with images).
 *
 * Persists `featuredItemIds: string[]` on `Settings/onlineOrdering`.
 */
export default function FeaturedItemsManager({
  items,
  categories,
  featuredItemIds,
}: FeaturedItemsManagerProps) {
  const [saving, setSaving] = useState(false);
  const [search, setSearch] = useState("");
  const featuredSet = useMemo(() => new Set(featuredItemIds), [featuredItemIds]);

  const persist = useCallback(async (next: string[]) => {
    setSaving(true);
    try {
      await setDoc(
        doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC),
        { featuredItemIds: next, updatedAt: serverTimestamp() },
        { merge: true }
      );
    } catch (e) {
      console.error("[featured-items]", e);
    } finally {
      setSaving(false);
    }
  }, []);

  const toggle = useCallback(
    (id: string) => {
      const next = featuredSet.has(id)
        ? featuredItemIds.filter((x) => x !== id)
        : [...featuredItemIds, id];
      void persist(next);
    },
    [featuredItemIds, featuredSet, persist]
  );

  const remove = useCallback(
    (id: string) => {
      void persist(featuredItemIds.filter((x) => x !== id));
    },
    [featuredItemIds, persist]
  );

  const clearAll = useCallback(() => {
    if (featuredItemIds.length === 0) return;
    if (!confirm("Clear all featured items? Storefront will fall back to auto-popular.")) return;
    void persist([]);
  }, [featuredItemIds.length, persist]);

  const featuredOrdered = useMemo(() => {
    const map = new Map(items.map((it) => [it.id, it] as const));
    return featuredItemIds
      .map((id) => map.get(id))
      .filter((it): it is OnlineMenuItem => Boolean(it));
  }, [featuredItemIds, items]);

  const categoriesById = useMemo(
    () => new Map(categories.map((c) => [c.id, c.name] as const)),
    [categories]
  );

  const visibleItems = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return items;
    return items.filter(
      (it) =>
        it.name.toLowerCase().includes(q) ||
        it.description.toLowerCase().includes(q)
    );
  }, [items, search]);

  return (
    <div className="space-y-5">
      <div>
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">Featured items</h2>
            <p className="text-sm text-slate-500 mt-0.5">
              Highlight up to a dozen items in your storefront&apos;s &ldquo;Popular&rdquo; row.
              Leave empty to auto-pick.
            </p>
          </div>
          <div className="flex items-center gap-2">
            {saving && <Loader2 size={16} className="animate-spin text-slate-400" />}
            {featuredItemIds.length > 0 && (
              <button
                type="button"
                onClick={clearAll}
                className="text-xs text-rose-600 hover:underline"
              >
                Clear all
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Currently featured */}
      <div>
        <p className="text-[11px] font-semibold text-slate-400 uppercase tracking-wider mb-2">
          {featuredOrdered.length === 0
            ? "Currently auto-popular"
            : `Featured (${featuredOrdered.length})`}
        </p>
        {featuredOrdered.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-slate-50 p-6 text-center text-sm text-slate-500">
            <Sparkles className="mx-auto text-slate-400 mb-1.5" size={22} />
            None featured yet — the storefront automatically shows your first items with photos.
          </div>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {featuredOrdered.map((it) => (
              <li
                key={it.id}
                className="inline-flex items-center gap-1.5 rounded-full border border-blue-200 bg-blue-50 text-blue-700 pl-2 pr-1 py-1 text-xs font-medium"
              >
                {it.imageUrl ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={it.imageUrl}
                    alt=""
                    className="w-5 h-5 rounded-full object-cover ring-1 ring-blue-100"
                  />
                ) : null}
                {it.name}
                <button
                  type="button"
                  onClick={() => remove(it.id)}
                  aria-label={`Remove ${it.name}`}
                  className="ml-1 rounded-full p-0.5 hover:bg-blue-100 text-blue-700"
                >
                  <X size={12} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Picker */}
      <div>
        <p className="text-[11px] font-semibold text-slate-400 uppercase tracking-wider mb-2">
          Pick items
        </p>
        <div className="relative mb-3">
          <Search size={14} className="absolute top-1/2 -translate-y-1/2 left-3 text-slate-400" />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search menu items"
            className="w-full pl-8 pr-3 py-2 text-sm rounded-lg border border-slate-200 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20"
          />
        </div>
        <ul className="max-h-[420px] overflow-y-auto rounded-xl border border-slate-200 divide-y divide-slate-100 bg-white">
          {visibleItems.length === 0 ? (
            <li className="px-4 py-6 text-sm text-slate-400 text-center">
              No matches.
            </li>
          ) : (
            visibleItems.map((it) => {
              const checked = featuredSet.has(it.id);
              const catName =
                it.categoryIds.length > 0
                  ? it.categoryIds
                      .map((id) => categoriesById.get(id))
                      .filter(Boolean)
                      .join(" · ")
                  : "Uncategorized";
              return (
                <li key={it.id}>
                  <label className="flex items-center gap-3 px-3 py-2.5 hover:bg-slate-50 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggle(it.id)}
                      className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                    />
                    {it.imageUrl ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={it.imageUrl}
                        alt=""
                        className="w-10 h-10 rounded-lg object-cover ring-1 ring-slate-200 shrink-0"
                      />
                    ) : (
                      <div className="w-10 h-10 rounded-lg bg-slate-100 shrink-0" />
                    )}
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium text-slate-800 truncate">{it.name}</p>
                      <p className="text-[11px] text-slate-400 truncate">{catName}</p>
                    </div>
                    <span className="text-xs text-slate-500 tabular-nums shrink-0">
                      ${(it.unitPriceCents / 100).toFixed(2)}
                    </span>
                  </label>
                </li>
              );
            })
          )}
        </ul>
      </div>
    </div>
  );
}
