"use client";

import { useEffect, useRef, useState } from "react";
import {
  collection,
  doc,
  getDocs,
  query,
  where,
  writeBatch,
  updateDoc,
  onSnapshot,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import MenuUploadModal from "@/components/MenuUploadModal";
import {
  Search,
  Plus,
  Upload,
  Trash2,
  Pencil,
  AlertTriangle,
  Package,
} from "lucide-react";

const ALL_ORDER_TYPES = ["DINE_IN", "TO_GO", "BAR_TAB"] as const;
const ORDER_TYPE_LABELS: Record<string, string> = {
  DINE_IN: "DINE IN",
  TO_GO: "TO GO",
  BAR_TAB: "BAR",
};

interface Category {
  id: string;
  name: string;
  availableOrderTypes: string[];
}

interface MenuItem {
  id: string;
  name: string;
  price: number;
  stock: number;
  categoryId: string;
  categoryName: string;
  availableOrderTypes: string[] | null;
  effectiveOrderTypes: string[];
}

export default function MenuPage() {
  const { user } = useAuth();
  const [categories, setCategories] = useState<Category[]>([]);
  const [items, setItems] = useState<MenuItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [activeCategory, setActiveCategory] = useState<string | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<MenuItem | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [editTarget, setEditTarget] = useState<MenuItem | null>(null);
  const [editPrice, setEditPrice] = useState("");
  const [editStock, setEditStock] = useState("");
  const [editUseCategoryTypes, setEditUseCategoryTypes] = useState(true);
  const [editOrderTypes, setEditOrderTypes] = useState<Record<string, boolean>>({});
  const [saving, setSaving] = useState(false);

  const catSnap = useRef<Map<string, { name: string; availableOrderTypes: string[] }>>(new Map());
  const itemSnap = useRef<
    { id: string; name: string; price: number; stock: number; categoryId: string; availableOrderTypes: string[] | null }[]
  >([]);
  const bothReady = useRef({ cats: false, items: false });

  useEffect(() => {
    if (!user) return;

    function rebuild() {
      if (!bothReady.current.cats || !bothReady.current.items) return;

      const catList: Category[] = [];
      catSnap.current.forEach((val, id) =>
        catList.push({ id, name: val.name, availableOrderTypes: val.availableOrderTypes })
      );
      catList.sort((a, b) => a.name.localeCompare(b.name));
      setCategories(catList);

      const menuItems: MenuItem[] = itemSnap.current.map((raw) => {
        const cat = catSnap.current.get(raw.categoryId);
        const catTypes = cat?.availableOrderTypes ?? [];
        const effective = raw.availableOrderTypes ?? catTypes;
        return {
          ...raw,
          categoryName: cat?.name ?? "Uncategorized",
          effectiveOrderTypes: effective,
        };
      });
      menuItems.sort((a, b) => a.name.localeCompare(b.name));
      setItems(menuItems);
      setLoading(false);
    }

    const unsubCats = onSnapshot(collection(db, "Categories"), (snap) => {
      catSnap.current.clear();
      snap.forEach((d) => {
        const data = d.data();
        if (data.name) {
          catSnap.current.set(d.id, {
            name: data.name,
            availableOrderTypes: Array.isArray(data.availableOrderTypes) ? data.availableOrderTypes : [],
          });
        }
      });
      bothReady.current.cats = true;
      rebuild();
    });

    const unsubItems = onSnapshot(collection(db, "MenuItems"), (snap) => {
      const list: typeof itemSnap.current = [];
      snap.forEach((d) => {
        const data = d.data();
        list.push({
          id: d.id,
          name: data.name || "Unnamed",
          price: data.price ?? 0,
          stock: data.stock ?? 0,
          categoryId: data.categoryId ?? "",
          availableOrderTypes: Array.isArray(data.availableOrderTypes) ? data.availableOrderTypes : null,
        });
      });
      itemSnap.current = list;
      bothReady.current.items = true;
      rebuild();
    });

    return () => {
      unsubCats();
      unsubItems();
      bothReady.current = { cats: false, items: false };
    };
  }, [user]);

  // ── Filter + group ──

  const filtered = items.filter((item) => {
    if (search && !item.name.toLowerCase().includes(search.toLowerCase()))
      return false;
    if (activeCategory && item.categoryId !== activeCategory) return false;
    return true;
  });

  const grouped = new Map<string, MenuItem[]>();
  for (const item of filtered) {
    const key = item.categoryName;
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key)!.push(item);
  }
  const sortedGroups = Array.from(grouped.entries()).sort(([a], [b]) => {
    if (a === "Uncategorized") return 1;
    if (b === "Uncategorized") return -1;
    return a.localeCompare(b);
  });

  // ── Delete ──

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      const batch = writeBatch(db);
      batch.delete(doc(db, "MenuItems", deleteTarget.id));

      const linksSnap = await getDocs(
        query(
          collection(db, "ItemModifierGroups"),
          where("itemId", "==", deleteTarget.id)
        )
      );
      linksSnap.forEach((d) => batch.delete(d.ref));
      await batch.commit();
    } catch (err) {
      console.error("Failed to delete item:", err);
    } finally {
      setDeleting(false);
      setDeleteTarget(null);
    }
  };

  // ── Edit ──

  const openEdit = (item: MenuItem) => {
    setEditTarget(item);
    setEditPrice(item.price.toFixed(2));
    setEditStock(String(item.stock));

    const useCat = item.availableOrderTypes === null;
    setEditUseCategoryTypes(useCat);

    const types: Record<string, boolean> = {};
    const active = item.availableOrderTypes ?? item.effectiveOrderTypes;
    for (const t of ALL_ORDER_TYPES) {
      types[t] = active.includes(t);
    }
    setEditOrderTypes(types);
  };

  const handleSave = async () => {
    if (!editTarget) return;
    const price = parseFloat(editPrice);
    const stock = parseInt(editStock, 10);
    if (isNaN(price) || price < 0) return;
    if (isNaN(stock) || stock < 0) return;

    setSaving(true);
    try {
      const update: Record<string, unknown> = { price, stock };

      if (editUseCategoryTypes) {
        update.availableOrderTypes = (await import("firebase/firestore")).deleteField();
      } else {
        update.availableOrderTypes = ALL_ORDER_TYPES.filter((t) => editOrderTypes[t]);
      }

      await updateDoc(doc(db, "MenuItems", editTarget.id), update);
    } catch (err) {
      console.error("Failed to update item:", err);
    } finally {
      setSaving(false);
      setEditTarget(null);
    }
  };

  // ── Stats ──
  const totalItems = items.length;
  const totalCategories = categories.length;
  const outOfStock = items.filter((i) => i.stock <= 0).length;

  return (
    <>
      <Header title="Menu" />
      <div className="p-6 space-y-6">
        {/* ── Top bar ── */}
        <div className="flex items-center justify-between gap-4 flex-wrap">
          <div className="flex items-center gap-4">
            <div className="relative">
              <Search
                size={16}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
              />
              <input
                type="text"
                placeholder="Search menu items..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="pl-9 pr-4 py-2 rounded-xl bg-white border border-slate-200 text-sm text-slate-700 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 w-72"
              />
            </div>
            <div className="hidden md:flex items-center gap-3 text-xs text-slate-500">
              <span className="px-2.5 py-1 bg-white rounded-lg border border-slate-200 font-medium">
                {totalItems} items
              </span>
              <span className="px-2.5 py-1 bg-white rounded-lg border border-slate-200 font-medium">
                {totalCategories} categories
              </span>
              {outOfStock > 0 && (
                <span className="px-2.5 py-1 bg-red-50 rounded-lg border border-red-200 font-medium text-red-600">
                  {outOfStock} out of stock
                </span>
              )}
            </div>
          </div>

          <div className="flex items-center gap-3">
            <button
              onClick={() => setUploadOpen(true)}
              className="flex items-center gap-2 px-4 py-2 rounded-xl border border-slate-200 bg-white text-sm font-medium text-slate-700 hover:bg-slate-50 transition-colors"
            >
              <Upload size={16} />
              Upload Menu
            </button>
            <button className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors">
              <Plus size={16} />
              Add Item
            </button>
          </div>
        </div>

        {/* ── Category filter tabs ── */}
        {!loading && categories.length > 0 && (
          <div className="flex items-center gap-2 overflow-x-auto pb-1">
            <button
              onClick={() => setActiveCategory(null)}
              className={`shrink-0 px-4 py-1.5 rounded-full text-sm font-medium transition-colors ${
                activeCategory === null
                  ? "bg-blue-600 text-white"
                  : "bg-white border border-slate-200 text-slate-600 hover:bg-slate-50"
              }`}
            >
              All
            </button>
            {categories.map((cat) => (
              <button
                key={cat.id}
                onClick={() =>
                  setActiveCategory(
                    activeCategory === cat.id ? null : cat.id
                  )
                }
                className={`shrink-0 px-4 py-1.5 rounded-full text-sm font-medium transition-colors ${
                  activeCategory === cat.id
                    ? "bg-blue-600 text-white"
                    : "bg-white border border-slate-200 text-slate-600 hover:bg-slate-50"
                }`}
              >
                {cat.name}
              </button>
            ))}
          </div>
        )}

        {/* ── Content ── */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          </div>
        ) : filtered.length === 0 ? (
          <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-12 text-center">
            <p className="text-slate-400 text-lg">No menu items found</p>
            <p className="text-slate-400 text-sm mt-1">
              Items from your POS will appear here
            </p>
          </div>
        ) : (
          <div className="space-y-8">
            {sortedGroups.map(([categoryName, groupItems]) => (
              <section key={categoryName}>
                <div className="flex items-center gap-2 mb-3">
                  <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wider">
                    {categoryName}
                  </h2>
                  <span className="text-xs text-slate-400">
                    ({groupItems.length})
                  </span>
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
                  {groupItems.map((item) => {
                    const inStock = item.stock > 0;
                    return (
                      <div
                        key={item.id}
                        className={`group bg-white rounded-2xl p-5 shadow-sm border hover:shadow-md transition-shadow ${
                          inStock
                            ? "border-slate-100"
                            : "border-red-100 bg-red-50/30"
                        }`}
                      >
                        <div className="flex items-start justify-between mb-3">
                          <h3
                            className={`font-semibold ${
                              inStock ? "text-slate-800" : "text-slate-400"
                            }`}
                          >
                            {item.name}
                          </h3>
                          <div className="flex items-center gap-1">
                            <button
                              onClick={() => openEdit(item)}
                              className="p-1 rounded-lg text-slate-300 opacity-0 group-hover:opacity-100 hover:bg-blue-50 hover:text-blue-500 transition-all"
                              title="Edit item"
                            >
                              <Pencil size={14} />
                            </button>
                            <button
                              onClick={() => setDeleteTarget(item)}
                              className="p-1 rounded-lg text-slate-300 opacity-0 group-hover:opacity-100 hover:bg-red-50 hover:text-red-500 transition-all"
                              title="Delete item"
                            >
                              <Trash2 size={14} />
                            </button>
                          </div>
                        </div>

                        <p className="text-xl font-bold text-slate-800 mb-2">
                          ${item.price.toFixed(2)}
                        </p>

                        <div className="flex items-center gap-1.5 text-xs mb-2">
                          <Package size={12} className={inStock ? "text-emerald-500" : "text-red-400"} />
                          <span
                            className={`font-medium ${
                              inStock ? "text-emerald-600" : "text-red-500"
                            }`}
                          >
                            {inStock
                              ? `Stock: ${item.stock}`
                              : "OUT OF STOCK"}
                          </span>
                        </div>

                        <div className="flex items-center gap-1.5 flex-wrap">
                          {item.effectiveOrderTypes.length > 0 ? (
                            item.effectiveOrderTypes.map((t) => (
                              <span
                                key={t}
                                className="text-[10px] px-1.5 py-0.5 rounded font-medium bg-blue-50 text-blue-600"
                              >
                                {ORDER_TYPE_LABELS[t] ?? t}
                              </span>
                            ))
                          ) : (
                            <span className="text-[10px] px-1.5 py-0.5 rounded font-medium bg-slate-100 text-slate-400">
                              No order types
                            </span>
                          )}
                          {item.availableOrderTypes === null && (
                            <span className="text-[10px] text-slate-300 italic">
                              (from category)
                            </span>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </section>
            ))}
          </div>
        )}
      </div>

      <MenuUploadModal
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onImportComplete={() => {}}
      />

      {/* ── Delete confirmation modal ── */}
      {deleteTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !deleting && setDeleteTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex flex-col items-center gap-3">
                <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center">
                  <AlertTriangle size={24} className="text-red-500" />
                </div>
                <h3 className="text-lg font-semibold text-slate-800">
                  Delete Item
                </h3>
              </div>

              <p className="text-sm text-slate-500 text-center">
                Are you sure you want to delete{" "}
                <strong className="text-slate-700">
                  {deleteTarget.name}
                </strong>
                ? This will permanently remove it from Firestore and the POS
                app.
              </p>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setDeleteTarget(null)}
                  disabled={deleting}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleDelete}
                  disabled={deleting}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {deleting ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Deleting…
                    </>
                  ) : (
                    "Delete"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Edit item modal ── */}
      {editTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !saving && setEditTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-5">
              <div>
                <h3 className="text-lg font-semibold text-slate-800">
                  Edit Item
                </h3>
                <p className="text-sm text-slate-500 mt-0.5">
                  {editTarget.name}
                </p>
              </div>

              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Price ($)
                  </label>
                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={editPrice}
                    onChange={(e) => setEditPrice(e.target.value)}
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Stock
                  </label>
                  <input
                    type="number"
                    min="0"
                    step="1"
                    value={editStock}
                    onChange={(e) => setEditStock(e.target.value)}
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>

                {/* ── Order Types ── */}
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-2">
                    Order Types
                  </label>

                  <label className="flex items-center gap-2 mb-3 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={editUseCategoryTypes}
                      onChange={(e) =>
                        setEditUseCategoryTypes(e.target.checked)
                      }
                      className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                    />
                    <span className="text-sm text-slate-600">
                      Use category availability
                    </span>
                  </label>

                  {!editUseCategoryTypes && (
                    <div className="flex flex-col gap-2 pl-1">
                      {ALL_ORDER_TYPES.map((t) => (
                        <label
                          key={t}
                          className="flex items-center gap-2 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={editOrderTypes[t] ?? false}
                            onChange={(e) =>
                              setEditOrderTypes((prev) => ({
                                ...prev,
                                [t]: e.target.checked,
                              }))
                            }
                            className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="text-sm text-slate-700">
                            {ORDER_TYPE_LABELS[t]}
                          </span>
                        </label>
                      ))}
                    </div>
                  )}
                </div>
              </div>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setEditTarget(null)}
                  disabled={saving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSave}
                  disabled={saving}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {saving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Saving…
                    </>
                  ) : (
                    "Save"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
