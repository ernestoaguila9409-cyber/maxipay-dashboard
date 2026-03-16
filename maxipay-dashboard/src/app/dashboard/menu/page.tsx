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
  addDoc,
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
  Download,
  Trash2,
  Pencil,
  AlertTriangle,
  Package,
  X,
  SlidersHorizontal,
} from "lucide-react";
import * as XLSX from "xlsx";

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

interface ModifierGroup {
  id: string;
  name: string;
  groupType: string;
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

  const [addOpen, setAddOpen] = useState(false);
  const [addName, setAddName] = useState("");
  const [addPrice, setAddPrice] = useState("");
  const [addStock, setAddStock] = useState("");
  const [addCategoryId, setAddCategoryId] = useState("");
  const [addUseCategoryTypes, setAddUseCategoryTypes] = useState(true);
  const [addOrderTypes, setAddOrderTypes] = useState<Record<string, boolean>>(
    () => Object.fromEntries(ALL_ORDER_TYPES.map((t) => [t, true]))
  );
  const [addSaving, setAddSaving] = useState(false);
  const [addModifiers, setAddModifiers] = useState<Record<string, boolean>>({});
  const [stockCountingEnabled, setStockCountingEnabled] = useState(true);

  // Modifier groups + assignment state
  const [modifierGroups, setModifierGroups] = useState<ModifierGroup[]>([]);
  const [editModifiers, setEditModifiers] = useState<Record<string, boolean>>({});
  const [editModifierLinks, setEditModifierLinks] = useState<Record<string, string>>({});

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

    const unsubSettings = onSnapshot(doc(db, "Settings", "inventory"), (snap) => {
      const data = snap.data();
      setStockCountingEnabled(data?.stockCountingEnabled ?? true);
    });

    const unsubModGroups = onSnapshot(collection(db, "ModifierGroups"), (snap) => {
      const list: ModifierGroup[] = [];
      snap.forEach((d) => {
        const data = d.data();
        if (data.name) {
          list.push({ id: d.id, name: data.name, groupType: data.groupType ?? "ADD" });
        }
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setModifierGroups(list);
    });

    return () => {
      unsubCats();
      unsubItems();
      unsubSettings();
      unsubModGroups();
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

  const openEdit = async (item: MenuItem) => {
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

    // Load current modifier assignments
    const assigned: Record<string, boolean> = {};
    const links: Record<string, string> = {};
    try {
      const snap = await getDocs(
        query(collection(db, "ItemModifierGroups"), where("itemId", "==", item.id))
      );
      snap.forEach((d) => {
        const groupId = d.data().groupId;
        if (groupId) {
          assigned[groupId] = true;
          links[groupId] = d.id;
        }
      });
    } catch (err) {
      console.error("Failed to load modifier assignments:", err);
    }
    setEditModifiers(assigned);
    setEditModifierLinks(links);
  };

  const handleSave = async () => {
    if (!editTarget) return;
    const price = parseFloat(editPrice);
    if (isNaN(price) || price < 0) return;

    const update: Record<string, unknown> = { price };

    if (stockCountingEnabled) {
      const stock = parseInt(editStock, 10);
      if (isNaN(stock) || stock < 0) return;
      update.stock = stock;
    }

    setSaving(true);
    try {

      if (editUseCategoryTypes) {
        update.availableOrderTypes = (await import("firebase/firestore")).deleteField();
      } else {
        update.availableOrderTypes = ALL_ORDER_TYPES.filter((t) => editOrderTypes[t]);
      }

      await updateDoc(doc(db, "MenuItems", editTarget.id), update);

      // Sync modifier assignments
      const batch = writeBatch(db);
      const currentlyAssigned = new Set(Object.keys(editModifierLinks));
      const wantAssigned = new Set(
        Object.entries(editModifiers).filter(([, v]) => v).map(([k]) => k)
      );

      // Remove unchecked
      for (const groupId of currentlyAssigned) {
        if (!wantAssigned.has(groupId)) {
          batch.delete(doc(db, "ItemModifierGroups", editModifierLinks[groupId]));
        }
      }

      // Add newly checked
      let nextOrder = wantAssigned.size;
      for (const groupId of wantAssigned) {
        if (!currentlyAssigned.has(groupId)) {
          const ref = doc(collection(db, "ItemModifierGroups"));
          batch.set(ref, {
            itemId: editTarget.id,
            groupId,
            displayOrder: ++nextOrder,
          });
        }
      }

      await batch.commit();
    } catch (err) {
      console.error("Failed to update item:", err);
    } finally {
      setSaving(false);
      setEditTarget(null);
    }
  };

  // ── Add Item ──

  const resetAddForm = () => {
    setAddName("");
    setAddPrice("");
    setAddStock("");
    setAddCategoryId("");
    setAddUseCategoryTypes(true);
    setAddOrderTypes(Object.fromEntries(ALL_ORDER_TYPES.map((t) => [t, true])));
    setAddModifiers({});
  };

  const handleAddItem = async () => {
    const name = addName.trim();
    if (!name) return;
    const price = parseFloat(addPrice);
    const stock = stockCountingEnabled ? parseInt(addStock, 10) : 9999;
    if (isNaN(price) || price < 0) return;
    if (stockCountingEnabled && (isNaN(stock) || stock < 0)) return;
    if (!addCategoryId) return;

    setAddSaving(true);
    try {
      const data: Record<string, unknown> = {
        name,
        price,
        stock,
        categoryId: addCategoryId,
      };

      if (!addUseCategoryTypes) {
        data.availableOrderTypes = ALL_ORDER_TYPES.filter((t) => addOrderTypes[t]);
      }

      const newItemRef = await addDoc(collection(db, "MenuItems"), data);

      // Create modifier assignments
      const selectedGroups = Object.entries(addModifiers)
        .filter(([, v]) => v)
        .map(([k]) => k);
      if (selectedGroups.length > 0) {
        const batch = writeBatch(db);
        selectedGroups.forEach((groupId, idx) => {
          const ref = doc(collection(db, "ItemModifierGroups"));
          batch.set(ref, {
            itemId: newItemRef.id,
            groupId,
            displayOrder: idx + 1,
          });
        });
        await batch.commit();
      }

      resetAddForm();
      setAddOpen(false);
    } catch (err) {
      console.error("Failed to add item:", err);
    } finally {
      setAddSaving(false);
    }
  };

  // ── Download Menu as Excel ──

  const handleDownloadMenu = () => {
    const rows = items.map((item) => {
      const row: Record<string, string | number> = {
        Name: item.name,
        Category: item.categoryName,
        Price: item.price,
      };
      if (stockCountingEnabled) {
        row.Stock = item.stock;
      }
      row["Order Types"] = item.effectiveOrderTypes
        .map((t) => ORDER_TYPE_LABELS[t] ?? t)
        .join(", ");
      return row;
    });

    const ws = XLSX.utils.json_to_sheet(rows);

    const colWidths = stockCountingEnabled
      ? [{ wch: 30 }, { wch: 20 }, { wch: 10 }, { wch: 10 }, { wch: 25 }]
      : [{ wch: 30 }, { wch: 20 }, { wch: 10 }, { wch: 25 }];
    ws["!cols"] = colWidths;

    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, "Menu");
    XLSX.writeFile(wb, "menu.xlsx");
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
              {stockCountingEnabled && outOfStock > 0 && (
                <span className="px-2.5 py-1 bg-red-50 rounded-lg border border-red-200 font-medium text-red-600">
                  {outOfStock} out of stock
                </span>
              )}
            </div>
          </div>

          <div className="flex items-center gap-3">
            <button
              onClick={handleDownloadMenu}
              disabled={items.length === 0}
              className="flex items-center gap-2 px-4 py-2 rounded-xl border border-slate-200 bg-white text-sm font-medium text-slate-700 hover:bg-slate-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <Download size={16} />
              Download Menu
            </button>
            <button
              onClick={() => setUploadOpen(true)}
              className="flex items-center gap-2 px-4 py-2 rounded-xl border border-slate-200 bg-white text-sm font-medium text-slate-700 hover:bg-slate-50 transition-colors"
            >
              <Upload size={16} />
              Upload Menu
            </button>
            <button
              onClick={() => { resetAddForm(); setAddOpen(true); }}
              className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
            >
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
                    const inStock = !stockCountingEnabled || item.stock > 0;
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

                        {stockCountingEnabled && (
                        <div className="flex items-center gap-1.5 text-xs mb-2">
                          <Package size={12} className={item.stock > 0 ? "text-emerald-500" : "text-red-400"} />
                          <span
                            className={`font-medium ${
                              item.stock > 0 ? "text-emerald-600" : "text-red-500"
                            }`}
                          >
                            {item.stock > 0
                              ? `Stock: ${item.stock}`
                              : "OUT OF STOCK"}
                          </span>
                        </div>
                        )}

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

      {/* ── Add item modal ── */}
      {addOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !addSaving && setAddOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden max-h-[90vh] overflow-y-auto">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  Add New Item
                </h3>
                <button
                  onClick={() => setAddOpen(false)}
                  disabled={addSaving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Name
                  </label>
                  <input
                    type="text"
                    value={addName}
                    onChange={(e) => setAddName(e.target.value)}
                    placeholder="e.g. Margherita Pizza"
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Category
                  </label>
                  <select
                    value={addCategoryId}
                    onChange={(e) => setAddCategoryId(e.target.value)}
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white"
                  >
                    <option value="">Select a category</option>
                    {categories.map((cat) => (
                      <option key={cat.id} value={cat.id}>
                        {cat.name}
                      </option>
                    ))}
                  </select>
                </div>

                {stockCountingEnabled ? (
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-1.5">
                      Price ($)
                    </label>
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      value={addPrice}
                      onChange={(e) => setAddPrice(e.target.value)}
                      placeholder="0.00"
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
                      value={addStock}
                      onChange={(e) => setAddStock(e.target.value)}
                      placeholder="0"
                      className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                    />
                  </div>
                </div>
                ) : (
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Price ($)
                  </label>
                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={addPrice}
                    onChange={(e) => setAddPrice(e.target.value)}
                    placeholder="0.00"
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>
                )}

                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-2">
                    Order Types
                  </label>
                  <label className="flex items-center gap-2 mb-3 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={addUseCategoryTypes}
                      onChange={(e) => setAddUseCategoryTypes(e.target.checked)}
                      className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                    />
                    <span className="text-sm text-slate-600">
                      Use category availability
                    </span>
                  </label>
                  {!addUseCategoryTypes && (
                    <div className="flex flex-col gap-2 pl-1">
                      {ALL_ORDER_TYPES.map((t) => (
                        <label
                          key={t}
                          className="flex items-center gap-2 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={addOrderTypes[t] ?? false}
                            onChange={(e) =>
                              setAddOrderTypes((prev) => ({
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

                {/* ── Assign Modifiers ── */}
                {modifierGroups.length > 0 && (
                  <div>
                    <label className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-2">
                      <SlidersHorizontal size={15} />
                      Assign Modifiers
                    </label>
                    <div className="flex flex-col gap-2 pl-1 max-h-40 overflow-y-auto">
                      {modifierGroups.map((g) => (
                        <label
                          key={g.id}
                          className="flex items-center gap-2 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={addModifiers[g.id] ?? false}
                            onChange={(e) =>
                              setAddModifiers((prev) => ({
                                ...prev,
                                [g.id]: e.target.checked,
                              }))
                            }
                            className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="text-sm text-slate-700">{g.name}</span>
                          <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${
                            g.groupType === "REMOVE"
                              ? "bg-red-50 text-red-500"
                              : "bg-slate-100 text-slate-400"
                          }`}>
                            {g.groupType === "REMOVE" ? "Remove" : "Add-on"}
                          </span>
                        </label>
                      ))}
                    </div>
                  </div>
                )}
              </div>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setAddOpen(false)}
                  disabled={addSaving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleAddItem}
                  disabled={addSaving || !addName.trim() || !addCategoryId || !addPrice || (stockCountingEnabled && !addStock)}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {addSaving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Adding…
                    </>
                  ) : (
                    "Add Item"
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
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden max-h-[90vh] overflow-y-auto">
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

                {stockCountingEnabled && (
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
                )}

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

                {/* ── Assign Modifiers ── */}
                {modifierGroups.length > 0 && (
                  <div>
                    <label className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-2">
                      <SlidersHorizontal size={15} />
                      Assign Modifiers
                    </label>
                    <div className="flex flex-col gap-2 pl-1 max-h-40 overflow-y-auto">
                      {modifierGroups.map((g) => (
                        <label
                          key={g.id}
                          className="flex items-center gap-2 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={editModifiers[g.id] ?? false}
                            onChange={(e) =>
                              setEditModifiers((prev) => ({
                                ...prev,
                                [g.id]: e.target.checked,
                              }))
                            }
                            className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="text-sm text-slate-700">{g.name}</span>
                          <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${
                            g.groupType === "REMOVE"
                              ? "bg-red-50 text-red-500"
                              : "bg-slate-100 text-slate-400"
                          }`}>
                            {g.groupType === "REMOVE" ? "Remove" : "Add-on"}
                          </span>
                        </label>
                      ))}
                    </div>
                  </div>
                )}
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
