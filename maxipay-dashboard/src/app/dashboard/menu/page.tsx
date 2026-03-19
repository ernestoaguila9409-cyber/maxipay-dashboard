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
  deleteDoc,
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
  FolderPlus,
  Receipt,
  CheckSquare,
  LayoutGrid,
  List,
  Clock,
} from "lucide-react";
import * as XLSX from "xlsx";

const ALL_ORDER_TYPES = ["DINE_IN", "TO_GO", "BAR_TAB"] as const;
const ORDER_TYPE_LABELS: Record<string, string> = {
  DINE_IN: "DINE IN",
  TO_GO: "TO GO",
  BAR_TAB: "BAR",
};

interface Schedule {
  id: string;
  name: string;
}

interface Category {
  id: string;
  name: string;
  availableOrderTypes: string[];
  scheduleIds: string[];
}

interface MenuEntity {
  id: string;
  name: string;
  isActive: boolean;
}

interface MenuItem {
  id: string;
  name: string;
  price: number;
  prices: Record<string, number>;
  stock: number;
  categoryId: string;
  categoryName: string;
  availableOrderTypes: string[] | null;
  effectiveOrderTypes: string[];
  modifierGroupIds: string[];
  taxIds: string[];
  menuId: string;
  isScheduled: boolean;
  scheduleIds: string[];
  categoryScheduled: boolean;
}

interface ModifierGroup {
  id: string;
  name: string;
  groupType: string;
  options: { id: string; name: string; price: number }[];
}

interface TaxEntry {
  id: string;
  name: string;
  amount: number;
  type: string;
  enabled: boolean;
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
  const [editPrices, setEditPrices] = useState<Record<string, string>>({});
  const [editStock, setEditStock] = useState("");
  const [editUseCategoryTypes, setEditUseCategoryTypes] = useState(true);
  const [editOrderTypes, setEditOrderTypes] = useState<Record<string, boolean>>({});
  const [saving, setSaving] = useState(false);

  const [addOpen, setAddOpen] = useState(false);
  const [addName, setAddName] = useState("");
  const [addPrices, setAddPrices] = useState<Record<string, string>>({});
  const [addStock, setAddStock] = useState("");
  const [addCategoryId, setAddCategoryId] = useState("");
  const [addUseCategoryTypes, setAddUseCategoryTypes] = useState(true);
  const [addOrderTypes, setAddOrderTypes] = useState<Record<string, boolean>>(
    () => Object.fromEntries(ALL_ORDER_TYPES.map((t) => [t, true]))
  );
  const [addSaving, setAddSaving] = useState(false);
  const [addModifiers, setAddModifiers] = useState<Record<string, boolean>>({});
  const [addTaxes, setAddTaxes] = useState<Record<string, boolean>>({});
  const [stockCountingEnabled, setStockCountingEnabled] = useState(true);

  const [addMenuId, setAddMenuId] = useState("");
  const [menuEntities, setMenuEntities] = useState<MenuEntity[]>([]);

  const [addCategoryOpen, setAddCategoryOpen] = useState(false);
  const [addCategoryName, setAddCategoryName] = useState("");
  const [addCategoryOrderTypes, setAddCategoryOrderTypes] = useState<Record<string, boolean>>(
    () => Object.fromEntries(ALL_ORDER_TYPES.map((t) => [t, true]))
  );
  const [addCategorySaving, setAddCategorySaving] = useState(false);
  const [addCategoryError, setAddCategoryError] = useState("");

  const [editCategoryTarget, setEditCategoryTarget] = useState<Category | null>(null);
  const [editCategoryOrderTypes, setEditCategoryOrderTypes] = useState<Record<string, boolean>>({});
  const [editCategorySchedules, setEditCategorySchedules] = useState<Record<string, boolean>>({});
  const [editCategorySaving, setEditCategorySaving] = useState(false);

  const [addCategorySchedules, setAddCategorySchedules] = useState<Record<string, boolean>>({});
  const [allSchedules, setAllSchedules] = useState<Schedule[]>([]);

  const [deleteCategoryTarget, setDeleteCategoryTarget] = useState<Category | null>(null);
  const [deletingCategory, setDeletingCategory] = useState(false);

  const [modifierGroups, setModifierGroups] = useState<ModifierGroup[]>([]);
  const [taxes, setTaxes] = useState<TaxEntry[]>([]);
  const [editModifiers, setEditModifiers] = useState<Record<string, boolean>>({});
  const [editTaxes, setEditTaxes] = useState<Record<string, boolean>>({});
  const [editMenuId, setEditMenuId] = useState("");

  const [selectMode, setSelectMode] = useState(false);
  const [selectedItems, setSelectedItems] = useState<Set<string>>(new Set());
  const [bulkDeleting, setBulkDeleting] = useState(false);
  const [bulkDeleteConfirm, setBulkDeleteConfirm] = useState(false);
  const [viewMode, setViewMode] = useState<"compact" | "card">("compact");
  const [menuTypeFilter, setMenuTypeFilter] = useState<string | null>(null);

  const catSnap = useRef<Map<string, { name: string; availableOrderTypes: string[]; scheduleIds: string[] }>>(new Map());
  const itemSnap = useRef<
    { id: string; name: string; price: number; prices: Record<string, number>; stock: number; categoryId: string; availableOrderTypes: string[] | null; modifierGroupIds: string[]; taxIds: string[]; menuId: string; isScheduled: boolean; scheduleIds: string[] }[]
  >([]);
  const bothReady = useRef({ cats: false, items: false });

  useEffect(() => {
    if (!user) return;

    function rebuild() {
      if (!bothReady.current.cats || !bothReady.current.items) return;

      const catList: Category[] = [];
      catSnap.current.forEach((val, id) =>
        catList.push({ id, name: val.name, availableOrderTypes: val.availableOrderTypes, scheduleIds: val.scheduleIds })
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
          categoryScheduled: (cat?.scheduleIds.length ?? 0) > 0,
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
            scheduleIds: Array.isArray(data.scheduleIds) ? data.scheduleIds : [],
          });
        }
      });
      bothReady.current.cats = true;
      rebuild();
    });

    const unsubSchedules = onSnapshot(collection(db, "menuSchedules"), (snap) => {
      const list: Schedule[] = [];
      snap.forEach((d) => {
        const data = d.data();
        if (data.name) list.push({ id: d.id, name: data.name });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setAllSchedules(list);
    });

    const unsubItems = onSnapshot(collection(db, "MenuItems"), (snap) => {
      const list: typeof itemSnap.current = [];
      snap.forEach((d) => {
        const data = d.data();
        const rawPrices = (typeof data.prices === "object" && data.prices !== null)
          ? Object.fromEntries(
              Object.entries(data.prices as Record<string, unknown>).map(
                ([k, v]) => [k, typeof v === "number" ? v : 0]
              )
            )
          : {};
        const legacyPrice: number = data.price ?? 0;
        const prices = Object.keys(rawPrices).length > 0 ? rawPrices : { default: legacyPrice };
        const displayPrice = Object.values(prices)[0] ?? 0;
        list.push({
          id: d.id,
          name: data.name || "Unnamed",
          price: displayPrice,
          prices,
          stock: data.stock ?? 0,
          categoryId: data.categoryId ?? "",
          availableOrderTypes: Array.isArray(data.availableOrderTypes) ? data.availableOrderTypes : null,
          modifierGroupIds: Array.isArray(data.modifierGroupIds) ? data.modifierGroupIds : [],
          taxIds: Array.isArray(data.taxIds) ? data.taxIds : [],
          menuId: data.menuId ?? "",
          isScheduled: data.isScheduled ?? false,
          scheduleIds: Array.isArray(data.scheduleIds) ? data.scheduleIds : [],
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
          list.push({
            id: d.id,
            name: data.name,
            groupType: data.groupType ?? "ADD",
            options: Array.isArray(data.options)
              ? data.options.map((o: Record<string, unknown>) => ({
                  id: String(o.id ?? ""),
                  name: String(o.name ?? ""),
                  price: typeof o.price === "number" ? o.price : 0,
                }))
              : [],
          });
        }
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setModifierGroups(list);
    });

    const unsubTaxes = onSnapshot(collection(db, "Taxes"), (snap) => {
      const list: TaxEntry[] = [];
      snap.forEach((d) => {
        const data = d.data();
        if (data.name) {
          list.push({
            id: d.id,
            name: data.name,
            amount: data.amount ?? 0,
            type: data.type ?? "PERCENTAGE",
            enabled: data.enabled ?? true,
          });
        }
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setTaxes(list);
    });

    const unsubMenuEntities = onSnapshot(collection(db, "menus"), (snap) => {
      const list: MenuEntity[] = [];
      snap.forEach((d) => {
        const data = d.data();
        if (data.name) {
          list.push({
            id: d.id,
            name: data.name,
            isActive: data.isActive ?? true,
          });
        }
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setMenuEntities(list);
    });

    return () => {
      unsubCats();
      unsubSchedules();
      unsubItems();
      unsubSettings();
      unsubModGroups();
      unsubTaxes();
      unsubMenuEntities();
      bothReady.current = { cats: false, items: false };
    };
  }, [user]);

  // ── Filter + group ──

  const itemsForMenuType = items.filter((item) => {
    if (menuTypeFilter === "POS" && (item.categoryScheduled || item.isScheduled))
      return false;
    if (menuTypeFilter && menuTypeFilter !== "POS" && item.menuId !== menuTypeFilter)
      return false;
    return true;
  });

  const visibleCategoryIds = new Set(itemsForMenuType.map((i) => i.categoryId));

  const filtered = itemsForMenuType.filter((item) => {
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
      await deleteDoc(doc(db, "MenuItems", deleteTarget.id));
    } catch (err) {
      console.error("Failed to delete item:", err);
    } finally {
      setDeleting(false);
      setDeleteTarget(null);
    }
  };

  // ── Bulk Delete ──

  const toggleSelectItem = (id: string) => {
    setSelectedItems((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (selectedItems.size === filtered.length) {
      setSelectedItems(new Set());
    } else {
      setSelectedItems(new Set(filtered.map((i) => i.id)));
    }
  };

  const exitSelectMode = () => {
    setSelectMode(false);
    setSelectedItems(new Set());
  };

  const handleBulkDelete = async () => {
    if (selectedItems.size === 0) return;
    setBulkDeleting(true);
    try {
      const batch = writeBatch(db);
      for (const id of selectedItems) {
        batch.delete(doc(db, "MenuItems", id));
      }
      await batch.commit();
      exitSelectMode();
    } catch (err) {
      console.error("Failed to bulk delete items:", err);
    } finally {
      setBulkDeleting(false);
      setBulkDeleteConfirm(false);
    }
  };

  // ── Edit ──

  const openEdit = (item: MenuItem) => {
    setEditTarget(item);
    const priceStrings: Record<string, string> = {};
    if (menuEntities.length > 0) {
      for (const m of menuEntities) {
        priceStrings[m.id] = item.prices[m.id]?.toFixed(2) ?? "";
      }
    } else {
      priceStrings["default"] = item.price.toFixed(2);
    }
    setEditPrices(priceStrings);
    setEditStock(String(item.stock));
    setEditMenuId(item.menuId ?? "");

    const useCat = item.availableOrderTypes === null;
    setEditUseCategoryTypes(useCat);

    const types: Record<string, boolean> = {};
    const active = item.availableOrderTypes ?? item.effectiveOrderTypes;
    for (const t of ALL_ORDER_TYPES) {
      types[t] = active.includes(t);
    }
    setEditOrderTypes(types);

    const mods: Record<string, boolean> = {};
    for (const gId of item.modifierGroupIds) {
      mods[gId] = true;
    }
    setEditModifiers(mods);

    const txs: Record<string, boolean> = {};
    for (const tId of item.taxIds) {
      txs[tId] = true;
    }
    setEditTaxes(txs);
  };

  const handleSave = async () => {
    if (!editTarget) return;
    const prices: Record<string, number> = {};
    for (const [key, val] of Object.entries(editPrices)) {
      const num = parseFloat(val);
      if (!isNaN(num) && num >= 0) prices[key] = num;
    }
    if (Object.keys(prices).length === 0) return;
    const price = Object.values(prices)[0];

    const update: Record<string, unknown> = { prices, price };

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

      update.modifierGroupIds = Object.entries(editModifiers)
        .filter(([, v]) => v)
        .map(([k]) => k);

      update.taxIds = Object.entries(editTaxes)
        .filter(([, v]) => v)
        .map(([k]) => k);

      if (editMenuId) update.menuId = editMenuId;

      await updateDoc(doc(db, "MenuItems", editTarget.id), update);
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
    setAddPrices({});
    setAddStock("");
    setAddCategoryId("");
    setAddMenuId("");
    setAddUseCategoryTypes(true);
    setAddOrderTypes(Object.fromEntries(ALL_ORDER_TYPES.map((t) => [t, true])));
    setAddModifiers({});
    setAddTaxes({});
  };

  // ── Add Category ──

  const openEditCategory = (cat: Category) => {
    setEditCategoryTarget(cat);
    setEditCategoryOrderTypes(
      Object.fromEntries(
        ALL_ORDER_TYPES.map((t) => [t, cat.availableOrderTypes.includes(t)])
      )
    );
    setEditCategorySchedules(
      Object.fromEntries(
        allSchedules.map((s) => [s.id, cat.scheduleIds.includes(s.id)])
      )
    );
  };

  const handleSaveCategory = async () => {
    if (!editCategoryTarget) return;
    const availableOrderTypes = ALL_ORDER_TYPES.filter((t) => editCategoryOrderTypes[t]);
    if (availableOrderTypes.length === 0) return;

    const scheduleIds = Object.entries(editCategorySchedules)
      .filter(([, v]) => v)
      .map(([k]) => k);

    setEditCategorySaving(true);
    try {
      await updateDoc(doc(db, "Categories", editCategoryTarget.id), {
        availableOrderTypes,
        scheduleIds,
      });
      setEditCategoryTarget(null);
    } catch (err) {
      console.error("Failed to update category:", err);
    } finally {
      setEditCategorySaving(false);
    }
  };

  const handleDeleteCategory = async () => {
    if (!deleteCategoryTarget) return;
    setDeletingCategory(true);
    try {
      await deleteDoc(doc(db, "Categories", deleteCategoryTarget.id));
      setDeleteCategoryTarget(null);
    } catch (err) {
      console.error("Failed to delete category:", err);
    } finally {
      setDeletingCategory(false);
    }
  };

  const handleAddCategory = async () => {
    const name = addCategoryName.trim();
    setAddCategoryError("");
    if (!name) return;

    const normalized = name.toLowerCase();
    const exists = categories.some((c) => c.name.toLowerCase() === normalized);
    if (exists) {
      setAddCategoryError("A category with this name already exists.");
      return;
    }

    const availableOrderTypes = ALL_ORDER_TYPES.filter((t) => addCategoryOrderTypes[t]);
    if (availableOrderTypes.length === 0) {
      setAddCategoryError("Select at least one order type.");
      return;
    }

    const scheduleIds = Object.entries(addCategorySchedules)
      .filter(([, v]) => v)
      .map(([k]) => k);

    setAddCategorySaving(true);
    try {
      await addDoc(collection(db, "Categories"), {
        name,
        availableOrderTypes,
        scheduleIds,
      });
      setAddCategoryName("");
      setAddCategoryOrderTypes(Object.fromEntries(ALL_ORDER_TYPES.map((t) => [t, true])));
      setAddCategorySchedules({});
      setAddCategoryOpen(false);
    } catch (err) {
      console.error("Failed to add category:", err);
    } finally {
      setAddCategorySaving(false);
    }
  };

  const handleAddItem = async () => {
    const name = addName.trim();
    if (!name) return;
    const prices: Record<string, number> = {};
    for (const [key, val] of Object.entries(addPrices)) {
      const num = parseFloat(val);
      if (!isNaN(num) && num >= 0) prices[key] = num;
    }
    if (Object.keys(prices).length === 0) return;
    const price = Object.values(prices)[0];
    const stock = stockCountingEnabled ? parseInt(addStock, 10) : 9999;
    if (stockCountingEnabled && (isNaN(stock) || stock < 0)) return;
    if (!addCategoryId) return;

    setAddSaving(true);
    try {
      const data: Record<string, unknown> = {
        name,
        prices,
        price,
        stock,
        categoryId: addCategoryId,
        menuId: addMenuId || "",
        isScheduled: false,
        scheduleIds: [],
        modifierGroupIds: Object.entries(addModifiers)
          .filter(([, v]) => v)
          .map(([k]) => k),
        taxIds: Object.entries(addTaxes)
          .filter(([, v]) => v)
          .map(([k]) => k),
      };

      if (!addUseCategoryTypes) {
        data.availableOrderTypes = ALL_ORDER_TYPES.filter((t) => addOrderTypes[t]);
      }

      await addDoc(collection(db, "MenuItems"), data);

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
    const wb = XLSX.utils.book_new();

    // 1. Items
    const itemRows = items.map((item) => {
      const row: Record<string, string | number> = {
        "Item ID": item.id,
        "Name": item.name,
        "Price (default)": item.price,
      };
      for (const m of menuEntities) {
        row[`Price (${m.name})`] = item.prices[m.id] ?? "";
      }
      row["Category ID"] = item.categoryId;
      row["Modifier Group IDs"] = item.modifierGroupIds.join(", ");
      row["Tax IDs"] = item.taxIds.join(", ");
      row["Order Types"] = item.effectiveOrderTypes.join(", ");
      return row;
    });
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(itemRows), "Items");

    // 2. Categories
    const catRows = categories.map((c) => ({
      "Category ID": c.id,
      "Category Name": c.name,
    }));
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(catRows), "Categories");

    // 3. Modifier Groups
    const modGroupRows = modifierGroups.map((g) => ({
      "Modifier Group ID": g.id,
      "Modifier Group Name": g.name,
    }));
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(modGroupRows), "Modifier Groups");

    // 4. Modifier Options
    const modOptionRows: Record<string, string | number>[] = [];
    for (const group of modifierGroups) {
      for (const opt of group.options) {
        modOptionRows.push({
          "Option ID": opt.id,
          "Modifier Group ID": group.id,
          "Option Name": opt.name,
          "Price": opt.price,
        });
      }
    }
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(modOptionRows), "Modifier Options");

    // 5. Taxes
    const taxRows = taxes.map((t) => ({
      "Tax ID": t.id,
      "Tax Name": t.name,
      "Rate": t.amount,
    }));
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(taxRows), "Taxes");

    XLSX.writeFile(wb, "menu.xlsx");
  };

  // ── Stats ──
  const totalItems = items.length;
  const totalCategories = categories.length;
  const outOfStock = items.filter((i) => i.stock <= 0).length;

  // ── Helper to get names for badges ──
  const modGroupMap = new Map(modifierGroups.map((g) => [g.id, g.name]));
  const taxMap = new Map(taxes.map((t) => [t.id, t.name]));
  const menuEntityMap = new Map(menuEntities.map((m) => [m.id, m.name]));
  const scheduleMap = new Map(allSchedules.map((s) => [s.id, s.name]));

  const addSelectedCategory = categories.find((c) => c.id === addCategoryId);
  const addCategoryHasSchedule = (addSelectedCategory?.scheduleIds.length ?? 0) > 0;

  const editSelectedCategory = editTarget ? categories.find((c) => c.id === editTarget.categoryId) : null;
  const editCategoryHasSchedule = (editSelectedCategory?.scheduleIds.length ?? 0) > 0;

  return (
    <>
      <Header title="Menu" />
      <div className="px-4 pt-3 pb-4 space-y-3">
        {/* ── Top bar ── */}
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <div className="flex items-center gap-3">
            <div className="relative">
              <Search size={15} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
              <input
                type="text"
                placeholder="Search items..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="pl-8 pr-3 py-1.5 rounded-lg bg-white border border-slate-200 text-sm text-slate-700 focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-400/20 w-52"
              />
            </div>
            <div className="hidden md:flex items-center gap-1.5">
              <div className="flex items-center bg-slate-100 rounded-lg p-0.5 gap-0.5">
                <button
                  onClick={() => { setMenuTypeFilter(null); setActiveCategory(null); }}
                  className={`px-2.5 py-1 rounded-md text-[11px] font-medium transition-all ${
                    menuTypeFilter === null ? "bg-white shadow-sm text-slate-700" : "text-slate-400 hover:text-slate-600"
                  }`}
                >
                  All
                </button>
                <button
                  onClick={() => { setMenuTypeFilter(menuTypeFilter === "POS" ? null : "POS"); setActiveCategory(null); }}
                  className={`px-2.5 py-1 rounded-md text-[11px] font-medium transition-all ${
                    menuTypeFilter === "POS" ? "bg-white shadow-sm text-emerald-600" : "text-slate-400 hover:text-slate-600"
                  }`}
                >
                  POS
                </button>
                {menuEntities.filter((m) => m.isActive).map((m) => (
                  <button
                    key={m.id}
                    onClick={() => { setMenuTypeFilter(menuTypeFilter === m.id ? null : m.id); setActiveCategory(null); }}
                    className={`px-2.5 py-1 rounded-md text-[11px] font-medium transition-all ${
                      menuTypeFilter === m.id ? "bg-white shadow-sm text-purple-600" : "text-slate-400 hover:text-slate-600"
                    }`}
                  >
                    {m.name}
                  </button>
                ))}
              </div>
            </div>
            <div className="hidden lg:flex items-center text-xs text-slate-400 gap-1.5">
              <span className="font-medium text-slate-500">{totalItems} items</span>
              <span>·</span>
              <span className="font-medium text-slate-500">{totalCategories} categories</span>
              {stockCountingEnabled && outOfStock > 0 && (
                <>
                  <span>·</span>
                  <span className="font-medium text-red-500">{outOfStock} out of stock</span>
                </>
              )}
            </div>
          </div>

          <div className="flex items-center gap-2">
            <div className="flex items-center bg-slate-100 rounded-lg p-0.5 gap-0.5">
              <button
                onClick={() => setViewMode("compact")}
                className={`p-1.5 rounded-md transition-all ${viewMode === "compact" ? "bg-white shadow-sm text-slate-700" : "text-slate-400 hover:text-slate-600"}`}
                title="Compact view"
              >
                <List size={15} />
              </button>
              <button
                onClick={() => setViewMode("card")}
                className={`p-1.5 rounded-md transition-all ${viewMode === "card" ? "bg-white shadow-sm text-slate-700" : "text-slate-400 hover:text-slate-600"}`}
                title="Card view"
              >
                <LayoutGrid size={15} />
              </button>
            </div>

            <div className="w-px h-5 bg-slate-200" />

            {selectMode ? (
              <>
                <button
                  onClick={toggleSelectAll}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-700 hover:bg-slate-50 transition-colors"
                >
                  <CheckSquare size={14} />
                  {selectedItems.size === filtered.length ? "Deselect All" : "Select All"}
                </button>
                <button
                  onClick={() => setBulkDeleteConfirm(true)}
                  disabled={selectedItems.size === 0}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-red-600 text-white text-xs font-medium hover:bg-red-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <Trash2 size={14} />
                  Delete{selectedItems.size > 0 ? ` (${selectedItems.size})` : ""}
                </button>
                <button
                  onClick={exitSelectMode}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-700 hover:bg-slate-50 transition-colors"
                >
                  <X size={14} />
                  Cancel
                </button>
              </>
            ) : (
              <>
                <button
                  onClick={() => setSelectMode(true)}
                  disabled={items.length === 0}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  title="Select items"
                >
                  <CheckSquare size={14} />
                  <span className="hidden lg:inline">Select</span>
                </button>
                <button
                  onClick={handleDownloadMenu}
                  disabled={items.length === 0}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  title="Download Menu"
                >
                  <Download size={14} />
                  <span className="hidden lg:inline">Download</span>
                </button>
                <button
                  onClick={() => setUploadOpen(true)}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors"
                  title="Upload Menu"
                >
                  <Upload size={14} />
                  <span className="hidden lg:inline">Upload</span>
                </button>
                <button
                  onClick={() => {
                    setAddCategoryName("");
                    setAddCategoryOrderTypes(Object.fromEntries(ALL_ORDER_TYPES.map((t) => [t, true])));
                    setAddCategorySchedules({});
                    setAddCategoryError("");
                    setAddCategoryOpen(true);
                  }}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors"
                  title="Add Category"
                >
                  <FolderPlus size={14} />
                  <span className="hidden lg:inline">Category</span>
                </button>
                <button
                  onClick={() => { resetAddForm(); setAddOpen(true); }}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-medium hover:bg-blue-700 transition-colors"
                >
                  <Plus size={14} />
                  Add Item
                </button>
              </>
            )}
          </div>
        </div>

        {/* ── Main layout ── */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="w-5 h-5 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          </div>
        ) : (
          <div className="flex gap-4">
            {/* ── Category sidebar ── */}
            {categories.length > 0 && (
              <div className="w-48 shrink-0 hidden md:block">
                <div className="sticky top-4 bg-white rounded-lg border border-slate-200 overflow-hidden">
                  <div className="px-3.5 py-2 border-b border-slate-100">
                    <p className="text-[11px] font-semibold text-slate-400 uppercase tracking-wider">Categories</p>
                  </div>
                  <nav className="flex flex-col max-h-[calc(100vh-10rem)] overflow-y-auto py-0.5">
                    <button
                      onClick={() => setActiveCategory(null)}
                      className={`w-full flex items-center justify-between h-[42px] px-3.5 text-[14px] transition-colors ${
                        activeCategory === null
                          ? "bg-blue-50/70 text-blue-700 font-semibold border-l-[3px] border-blue-600"
                          : "text-slate-600 font-medium hover:bg-slate-50 border-l-[3px] border-transparent"
                      }`}
                    >
                      <span>All Items</span>
                      <span className="text-[11px] text-slate-400 font-normal tabular-nums">{itemsForMenuType.length}</span>
                    </button>
                    {categories.filter((cat) => !menuTypeFilter || visibleCategoryIds.has(cat.id)).map((cat) => {
                      const catItemCount = itemsForMenuType.filter((i) => i.categoryId === cat.id).length;
                      return (
                        <div
                          key={cat.id}
                          className={`group/cat flex items-center h-[42px] transition-colors ${
                            activeCategory === cat.id
                              ? "bg-blue-50/70 border-l-[3px] border-blue-600"
                              : "hover:bg-slate-50 border-l-[3px] border-transparent"
                          }`}
                        >
                          <button
                            onClick={() => setActiveCategory(activeCategory === cat.id ? null : cat.id)}
                            className={`flex-1 flex items-center justify-between h-full px-3.5 text-[14px] truncate ${
                              activeCategory === cat.id ? "text-blue-700 font-semibold" : "text-slate-600 font-medium"
                            }`}
                          >
                            <span className="flex items-center gap-1.5 truncate">
                              <span className="truncate">{cat.name}</span>
                              {cat.scheduleIds.length > 0 && (
                                <span className="text-[8px] px-1 py-0.5 rounded bg-blue-50 text-blue-500 font-semibold leading-none shrink-0" title={cat.scheduleIds.map((id) => scheduleMap.get(id) ?? id).join(", ")}>
                                  Sched
                                </span>
                              )}
                            </span>
                            <span className="text-[11px] text-slate-400 font-normal ml-2 tabular-nums">{catItemCount}</span>
                          </button>
                          <div className="flex items-center gap-0.5 pr-1.5 opacity-0 group-hover/cat:opacity-100 transition-opacity">
                            <button
                              onClick={(e) => { e.stopPropagation(); openEditCategory(cat); }}
                              className="p-1 rounded text-slate-400 hover:text-slate-600 hover:bg-slate-200/60 transition-colors"
                              title="Edit category"
                            >
                              <Pencil size={11} />
                            </button>
                            <button
                              onClick={(e) => { e.stopPropagation(); setDeleteCategoryTarget(cat); }}
                              className="p-1 rounded text-slate-400 hover:text-red-500 hover:bg-red-50 transition-colors"
                              title="Delete category"
                            >
                              <Trash2 size={11} />
                            </button>
                          </div>
                        </div>
                      );
                    })}
                  </nav>
                </div>
              </div>
            )}

            {/* ── Mobile category bar ── */}
            {categories.length > 0 && (
              <div className="md:hidden flex gap-1.5 overflow-x-auto pb-1 -mt-1 mb-1 w-full">
                <button
                  onClick={() => setActiveCategory(null)}
                  className={`shrink-0 px-3 py-1 rounded-lg text-xs font-medium transition-colors ${
                    activeCategory === null ? "bg-blue-600 text-white" : "bg-white border border-slate-200 text-slate-600"
                  }`}
                >
                  All
                </button>
                {categories.filter((cat) => !menuTypeFilter || visibleCategoryIds.has(cat.id)).map((cat) => (
                  <button
                    key={cat.id}
                    onClick={() => setActiveCategory(activeCategory === cat.id ? null : cat.id)}
                    className={`shrink-0 px-3 py-1 rounded-lg text-xs font-medium transition-colors ${
                      activeCategory === cat.id ? "bg-blue-600 text-white" : "bg-white border border-slate-200 text-slate-600"
                    }`}
                  >
                    {cat.name}
                  </button>
                ))}
              </div>
            )}

            {/* ── Content ── */}
            <div className="flex-1 min-w-0">
              {filtered.length === 0 ? (
                <div className="bg-white rounded-lg border border-slate-100 p-10 text-center">
                  <p className="text-slate-400 text-sm">No menu items found</p>
                  <p className="text-slate-300 text-xs mt-1">Items from your POS will appear here</p>
                </div>
              ) : viewMode === "compact" ? (
                /* ── Compact list view ── */
                <div className="space-y-4">
                  {sortedGroups.map(([categoryName, groupItems]) => (
                    <section key={categoryName}>
                      <div className="flex items-center gap-2 mb-1 px-1">
                        <h2 className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">{categoryName}</h2>
                        <span className="text-[11px] text-slate-300">({groupItems.length})</span>
                      </div>
                      <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
                        <div className="hidden sm:flex items-center gap-3 px-3 py-1.5 bg-slate-50/80 border-b border-slate-100">
                          {selectMode && <div className="w-4 shrink-0" />}
                          <div className="flex-1 min-w-0 text-[11px] font-medium text-slate-400 uppercase tracking-wider">Item</div>
                          <div className="w-28 text-center shrink-0 text-[11px] font-medium text-slate-400 uppercase tracking-wider">Type</div>
                          <div className="w-12 text-center shrink-0 text-[11px] font-medium text-slate-400 uppercase tracking-wider">Info</div>
                          <div className="w-[72px] text-right shrink-0 text-[11px] font-medium text-slate-400 uppercase tracking-wider">Price</div>
                          {!selectMode && <div className="w-16 shrink-0" />}
                        </div>
                        <div className="divide-y divide-slate-50">
                          {groupItems.map((item) => {
                            const inStock = !stockCountingEnabled || item.stock > 0;
                            const isSelected = selectedItems.has(item.id);
                            return (
                              <div
                                key={item.id}
                                onClick={selectMode ? () => toggleSelectItem(item.id) : undefined}
                                className={`flex items-center gap-3 px-3 h-[50px] transition-colors group ${
                                  selectMode ? "cursor-pointer" : ""
                                } ${
                                  isSelected
                                    ? "bg-blue-50/60"
                                    : !inStock
                                    ? "bg-red-50/20"
                                    : "hover:bg-slate-50/60"
                                }`}
                              >
                                {selectMode && (
                                  <input
                                    type="checkbox"
                                    checked={isSelected}
                                    readOnly
                                    className="w-3.5 h-3.5 rounded border-slate-300 text-blue-600 focus:ring-blue-500 pointer-events-none shrink-0"
                                  />
                                )}
                                <div className="flex-1 min-w-0 flex items-center gap-2">
                                  <span className={`text-[13px] font-semibold truncate ${inStock ? "text-slate-800" : "text-slate-400 line-through"}`}>
                                    {item.name}
                                  </span>
                                  {!inStock && (
                                    <span className="text-[9px] px-1 py-0.5 rounded bg-red-100 text-red-600 font-bold leading-none shrink-0">OUT</span>
                                  )}
                                  {stockCountingEnabled && inStock && item.stock <= 10 && (
                                    <span className="text-[10px] text-amber-500 font-medium shrink-0">{item.stock} left</span>
                                  )}
                                </div>
                                <div className="w-28 hidden sm:flex items-center justify-center gap-1 shrink-0 flex-wrap">
                                  {item.effectiveOrderTypes.map((t) => (
                                    <span key={t} className="text-[9px] px-1.5 py-0.5 rounded bg-blue-50 text-blue-600 font-semibold leading-none">
                                      {ORDER_TYPE_LABELS[t] ?? t}
                                    </span>
                                  ))}
                                </div>
                                <div className="w-20 hidden sm:flex items-center justify-center gap-1 shrink-0 flex-wrap">
                                  {!item.categoryScheduled && !item.isScheduled && (
                                    <span className="text-[8px] px-1 py-0.5 rounded bg-emerald-50 text-emerald-600 font-semibold leading-none">
                                      POS
                                    </span>
                                  )}
                                  {item.menuId && (
                                    <span
                                      className="text-[8px] px-1 py-0.5 rounded bg-purple-50 text-purple-600 font-semibold leading-none truncate max-w-full"
                                      title={menuEntityMap.get(item.menuId) ?? ""}
                                    >
                                      {menuEntityMap.get(item.menuId) ?? "Menu"}
                                    </span>
                                  )}
                                  {item.isScheduled && (
                                    <span className="text-[8px] px-1 py-0.5 rounded bg-blue-50 text-blue-600 font-semibold leading-none">
                                      Sched
                                    </span>
                                  )}
                                </div>
                                <div className="w-12 hidden sm:flex items-center justify-center gap-1.5 shrink-0">
                                  {item.modifierGroupIds.length > 0 && (
                                    <span
                                      className="w-2 h-2 rounded-full bg-purple-400 shrink-0"
                                      title={item.modifierGroupIds.map((id) => modGroupMap.get(id) ?? id).join(", ")}
                                    />
                                  )}
                                  {item.taxIds.length > 0 && (
                                    <span
                                      className="w-2 h-2 rounded-full bg-amber-400 shrink-0"
                                      title={item.taxIds.map((id) => taxMap.get(id) ?? id).join(", ")}
                                    />
                                  )}
                                </div>
                                <span className="w-[72px] text-right text-[13px] font-semibold text-slate-700 shrink-0 tabular-nums">
                                  {Object.keys(item.prices).length > 1 ? (
                                    <span title={Object.entries(item.prices).map(([k, v]) => `${menuEntityMap.get(k) ?? k}: $${v.toFixed(2)}`).join(", ")}>
                                      ${Math.min(...Object.values(item.prices)).toFixed(2)}+
                                    </span>
                                  ) : (
                                    <>${item.price.toFixed(2)}</>
                                  )}
                                </span>
                                {!selectMode && (
                                  <div className="w-16 flex items-center justify-end gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity shrink-0">
                                    <button
                                      onClick={() => openEdit(item)}
                                      className="p-1.5 rounded text-slate-400 hover:text-blue-600 hover:bg-blue-50 transition-colors"
                                      title="Edit"
                                    >
                                      <Pencil size={13} />
                                    </button>
                                    <button
                                      onClick={() => setDeleteTarget(item)}
                                      className="p-1.5 rounded text-slate-400 hover:text-red-500 hover:bg-red-50 transition-colors"
                                      title="Delete"
                                    >
                                      <Trash2 size={13} />
                                    </button>
                                  </div>
                                )}
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    </section>
                  ))}
                </div>
              ) : (
                /* ── Card view ── */
                <div className="space-y-5">
                  {sortedGroups.map(([categoryName, groupItems]) => (
                    <section key={categoryName}>
                      <div className="flex items-center gap-2 mb-2 px-0.5">
                        <h2 className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">{categoryName}</h2>
                        <span className="text-[11px] text-slate-300">({groupItems.length})</span>
                      </div>
                      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-3">
                        {groupItems.map((item) => {
                          const inStock = !stockCountingEnabled || item.stock > 0;
                          const isSelected = selectedItems.has(item.id);
                          return (
                            <div
                              key={item.id}
                              onClick={selectMode ? () => toggleSelectItem(item.id) : undefined}
                              className={`group bg-white rounded-lg p-3.5 border hover:shadow-sm transition-all ${
                                selectMode ? "cursor-pointer" : ""
                              } ${
                                isSelected
                                  ? "border-blue-400 bg-blue-50/40 ring-1 ring-blue-400/30"
                                  : inStock
                                  ? "border-slate-200"
                                  : "border-red-200 bg-red-50/30"
                              }`}
                            >
                              <div className="flex items-start justify-between mb-2">
                                <div className="flex items-center gap-2 min-w-0">
                                  {selectMode && (
                                    <input
                                      type="checkbox"
                                      checked={isSelected}
                                      readOnly
                                      className="w-3.5 h-3.5 rounded border-slate-300 text-blue-600 focus:ring-blue-500 pointer-events-none shrink-0"
                                    />
                                  )}
                                  <h3 className={`text-sm font-semibold truncate ${inStock ? "text-slate-800" : "text-slate-400"}`}>
                                    {item.name}
                                  </h3>
                                </div>
                                {!selectMode && (
                                  <div className="flex items-center gap-0.5 shrink-0">
                                    <button
                                      onClick={() => openEdit(item)}
                                      className="p-1 rounded text-slate-300 opacity-0 group-hover:opacity-100 hover:bg-blue-50 hover:text-blue-500 transition-all"
                                      title="Edit"
                                    >
                                      <Pencil size={13} />
                                    </button>
                                    <button
                                      onClick={() => setDeleteTarget(item)}
                                      className="p-1 rounded text-slate-300 opacity-0 group-hover:opacity-100 hover:bg-red-50 hover:text-red-500 transition-all"
                                      title="Delete"
                                    >
                                      <Trash2 size={13} />
                                    </button>
                                  </div>
                                )}
                              </div>

                              <div className="flex items-center justify-between mb-2">
                                <div>
                                  {Object.keys(item.prices).length > 1 ? (
                                    <div className="flex flex-col">
                                      {Object.entries(item.prices).map(([k, v]) => (
                                        <span key={k} className="text-sm font-semibold text-slate-700 tabular-nums">
                                          <span className="text-[10px] text-slate-400 font-medium">{menuEntityMap.get(k) ?? k}: </span>
                                          ${v.toFixed(2)}
                                        </span>
                                      ))}
                                    </div>
                                  ) : (
                                    <p className="text-lg font-bold text-slate-800 tabular-nums">${item.price.toFixed(2)}</p>
                                  )}
                                </div>
                                {stockCountingEnabled && (
                                  <div className="flex items-center gap-1 text-xs">
                                    <Package size={11} className={item.stock > 0 ? "text-emerald-500" : "text-red-400"} />
                                    <span className={`font-medium ${item.stock > 0 ? "text-emerald-600" : "text-red-500"}`}>
                                      {item.stock > 0 ? item.stock : "OUT"}
                                    </span>
                                  </div>
                                )}
                              </div>

                              <div className="flex items-center gap-1 flex-wrap">
                                {item.menuId && (
                                  <span className="text-[9px] px-1.5 py-0.5 rounded bg-purple-50 text-purple-600 font-semibold leading-none">
                                    {menuEntityMap.get(item.menuId) ?? "Menu"}
                                  </span>
                                )}
                                {!item.categoryScheduled && !item.isScheduled && (
                                  <span className="text-[9px] px-1.5 py-0.5 rounded bg-emerald-50 text-emerald-600 font-semibold leading-none">
                                    POS
                                  </span>
                                )}
                                {item.isScheduled && (
                                  <span className="text-[9px] px-1.5 py-0.5 rounded bg-blue-50 text-blue-600 font-semibold leading-none">
                                    Scheduled
                                  </span>
                                )}
                                {item.effectiveOrderTypes.map((t) => (
                                  <span key={t} className="text-[9px] px-1.5 py-0.5 rounded bg-blue-50 text-blue-600 font-semibold leading-none">
                                    {ORDER_TYPE_LABELS[t] ?? t}
                                  </span>
                                ))}
                                {item.modifierGroupIds.length > 0 && item.modifierGroupIds.map((id) => (
                                  <span key={id} className="text-[9px] px-1.5 py-0.5 rounded bg-purple-50 text-purple-600 font-medium leading-none">
                                    {modGroupMap.get(id) ?? id}
                                  </span>
                                ))}
                                {item.taxIds.length > 0 && item.taxIds.map((id) => (
                                  <span key={id} className="text-[9px] px-1.5 py-0.5 rounded bg-amber-50 text-amber-600 font-medium leading-none">
                                    {taxMap.get(id) ?? id}
                                  </span>
                                ))}
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
          </div>
        )}
      </div>

      <MenuUploadModal
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onImportComplete={() => {}}
      />

      {/* ── Add category modal ── */}
      {addCategoryOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !addCategorySaving && setAddCategoryOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  Add Category
                </h3>
                <button
                  onClick={() => setAddCategoryOpen(false)}
                  disabled={addCategorySaving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <p className="text-sm text-slate-500">
                Choose which order types can use this category. Check all for every order type, or uncheck to limit where it appears — same as the app.
              </p>

              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Category Name
                </label>
                <input
                  type="text"
                  value={addCategoryName}
                  onChange={(e) => {
                    setAddCategoryName(e.target.value);
                    setAddCategoryError("");
                  }}
                  placeholder="e.g. Desserts, Drinks"
                  className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                />
                {addCategoryError && (
                  <p className="mt-1.5 text-sm text-red-600">{addCategoryError}</p>
                )}
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-700 mb-2">
                  Show in order types
                </label>
                <p className="text-xs text-slate-500 mb-2">
                  All checked = shows everywhere. Uncheck to limit to Dine In, To Go, and/or Bar only.
                </p>
                <div className="flex flex-col gap-2 pl-1">
                  {ALL_ORDER_TYPES.map((t) => (
                    <label
                      key={t}
                      className="flex items-center gap-2 cursor-pointer"
                    >
                      <input
                        type="checkbox"
                        checked={addCategoryOrderTypes[t] ?? false}
                        onChange={(e) =>
                          setAddCategoryOrderTypes((prev) => ({
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
              </div>

              {allSchedules.length > 0 && (
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-2">
                    Schedule (optional)
                  </label>
                  <p className="text-xs text-slate-500 mb-2">
                    If a schedule is assigned, this category and its items will only appear during that time window. Leave unchecked for always available.
                  </p>
                  <div className="flex flex-col gap-2 pl-1">
                    {allSchedules.map((s) => (
                      <label
                        key={s.id}
                        className="flex items-center gap-2 cursor-pointer"
                      >
                        <input
                          type="checkbox"
                          checked={addCategorySchedules[s.id] ?? false}
                          onChange={(e) =>
                            setAddCategorySchedules((prev) => ({
                              ...prev,
                              [s.id]: e.target.checked,
                            }))
                          }
                          className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                        />
                        <span className="text-sm text-slate-700">{s.name}</span>
                      </label>
                    ))}
                  </div>
                </div>
              )}

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setAddCategoryOpen(false)}
                  disabled={addCategorySaving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleAddCategory}
                  disabled={
                    addCategorySaving ||
                    !addCategoryName.trim() ||
                    ALL_ORDER_TYPES.every((t) => !addCategoryOrderTypes[t])
                  }
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {addCategorySaving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Adding…
                    </>
                  ) : (
                    "Add Category"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Edit category modal ── */}
      {editCategoryTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !editCategorySaving && setEditCategoryTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  Edit Category
                </h3>
                <button
                  onClick={() => setEditCategoryTarget(null)}
                  disabled={editCategorySaving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <p className="text-sm text-slate-600 font-medium">
                {editCategoryTarget.name}
              </p>

              <p className="text-sm text-slate-500">
                Choose which order types can use this category. All checked = everywhere. Uncheck to limit — syncs to the app.
              </p>

              <div>
                <label className="block text-sm font-medium text-slate-700 mb-2">
                  Show in order types
                </label>
                <div className="flex flex-col gap-2 pl-1">
                  {ALL_ORDER_TYPES.map((t) => (
                    <label
                      key={t}
                      className="flex items-center gap-2 cursor-pointer"
                    >
                      <input
                        type="checkbox"
                        checked={editCategoryOrderTypes[t] ?? false}
                        onChange={(e) =>
                          setEditCategoryOrderTypes((prev) => ({
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
              </div>

              {allSchedules.length > 0 && (
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-2">
                    Schedule (optional)
                  </label>
                  <p className="text-xs text-slate-500 mb-2">
                    If a schedule is assigned, this category and its items will only appear during that time window. Leave unchecked for always available.
                  </p>
                  <div className="flex flex-col gap-2 pl-1">
                    {allSchedules.map((s) => (
                      <label
                        key={s.id}
                        className="flex items-center gap-2 cursor-pointer"
                      >
                        <input
                          type="checkbox"
                          checked={editCategorySchedules[s.id] ?? false}
                          onChange={(e) =>
                            setEditCategorySchedules((prev) => ({
                              ...prev,
                              [s.id]: e.target.checked,
                            }))
                          }
                          className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                        />
                        <span className="text-sm text-slate-700">{s.name}</span>
                      </label>
                    ))}
                  </div>
                </div>
              )}

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setEditCategoryTarget(null)}
                  disabled={editCategorySaving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSaveCategory}
                  disabled={
                    editCategorySaving ||
                    ALL_ORDER_TYPES.every((t) => !editCategoryOrderTypes[t])
                  }
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {editCategorySaving ? (
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

      {/* ── Delete category confirmation modal ── */}
      {deleteCategoryTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !deletingCategory && setDeleteCategoryTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex flex-col items-center gap-3">
                <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center">
                  <AlertTriangle size={24} className="text-red-500" />
                </div>
                <h3 className="text-lg font-semibold text-slate-800">
                  Delete Category
                </h3>
              </div>

              {(() => {
                const itemCount = items.filter(
                  (i) => i.categoryId === deleteCategoryTarget.id
                ).length;
                return (
                  <p className="text-sm text-slate-500 text-center">
                    {itemCount > 0 ? (
                      <>
                        <strong className="text-slate-700">
                          {deleteCategoryTarget.name}
                        </strong>{" "}
                        contains <strong>{itemCount} item{itemCount !== 1 ? "s" : ""}</strong>.
                        They will become uncategorized. Are you sure you want to delete this
                        category?
                      </>
                    ) : (
                      <>
                        Delete <strong className="text-slate-700">{deleteCategoryTarget.name}</strong>?
                        This cannot be undone.
                      </>
                    )}
                  </p>
                );
              })()}

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setDeleteCategoryTarget(null)}
                  disabled={deletingCategory}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleDeleteCategory}
                  disabled={deletingCategory}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {deletingCategory ? (
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

      {/* ── Bulk delete confirmation modal ── */}
      {bulkDeleteConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !bulkDeleting && setBulkDeleteConfirm(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex flex-col items-center gap-3">
                <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center">
                  <AlertTriangle size={24} className="text-red-500" />
                </div>
                <h3 className="text-lg font-semibold text-slate-800">
                  Delete {selectedItems.size} Item{selectedItems.size !== 1 ? "s" : ""}
                </h3>
              </div>
              <p className="text-sm text-slate-500 text-center">
                Are you sure you want to delete{" "}
                <strong className="text-slate-700">
                  {selectedItems.size} item{selectedItems.size !== 1 ? "s" : ""}
                </strong>
                ? This will permanently remove them from Firestore and the POS app.
              </p>
              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setBulkDeleteConfirm(false)}
                  disabled={bulkDeleting}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleBulkDelete}
                  disabled={bulkDeleting}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {bulkDeleting ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Deleting…
                    </>
                  ) : (
                    "Delete All"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Delete item confirmation modal ── */}
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
                    onChange={(e) => {
                      const catId = e.target.value;
                      setAddCategoryId(catId);
                      const cat = categories.find((c) => c.id === catId);
                      if (cat && cat.scheduleIds.length > 0) {
                        setAddMenuId("");
                      }
                    }}
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

                {!addCategoryHasSchedule && (
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-1.5">
                      Menu
                    </label>
                    <select
                      value={addMenuId}
                      onChange={(e) => setAddMenuId(e.target.value)}
                      className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white"
                    >
                      <option value="">POS (always available)</option>
                      {menuEntities.filter((m) => m.isActive).map((m) => (
                        <option key={m.id} value={m.id}>
                          {m.name}
                        </option>
                      ))}
                    </select>
                    <p className="text-[10px] text-slate-400 mt-1">
                      POS items are always available. Select a menu to assign to a specific time-based menu.
                    </p>
                  </div>
                )}

                {addCategoryHasSchedule && (
                  <div className="flex items-center gap-2 px-3 py-2.5 rounded-xl bg-blue-50 border border-blue-100">
                    <Clock size={14} className="text-blue-500 shrink-0" />
                    <p className="text-xs text-blue-600">
                      This category is scheduled ({addSelectedCategory!.scheduleIds.map((id) => scheduleMap.get(id) ?? id).join(", ")}). Items will follow the category&apos;s schedule automatically.
                    </p>
                  </div>
                )}

                {addMenuId ? (
                  <div>
                    <label className="block text-xs font-medium text-slate-500 mb-1">
                      {menuEntities.find((m) => m.id === addMenuId)?.name ?? "Menu"} Price ($)
                    </label>
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      value={addPrices[addMenuId] ?? ""}
                      onChange={(e) => setAddPrices((prev) => ({ ...prev, [addMenuId]: e.target.value }))}
                      placeholder="0.00"
                      className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                    />
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
                      value={addPrices["default"] ?? ""}
                      onChange={(e) => setAddPrices((prev) => ({ ...prev, default: e.target.value }))}
                      placeholder="0.00"
                      className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                    />
                  </div>
                )}

                {stockCountingEnabled && (
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

                {/* ── Assign Taxes ── */}
                {taxes.length > 0 && (
                  <div>
                    <label className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-2">
                      <Receipt size={15} />
                      Assign Taxes
                    </label>
                    <div className="flex flex-col gap-2 pl-1 max-h-40 overflow-y-auto">
                      {taxes.filter((t) => t.enabled).map((t) => (
                        <label
                          key={t.id}
                          className="flex items-center gap-2 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={addTaxes[t.id] ?? false}
                            onChange={(e) =>
                              setAddTaxes((prev) => ({
                                ...prev,
                                [t.id]: e.target.checked,
                              }))
                            }
                            className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="text-sm text-slate-700">{t.name}</span>
                          <span className="text-[10px] px-1.5 py-0.5 rounded font-medium bg-amber-50 text-amber-600">
                            {t.amount}%
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
                  disabled={addSaving || !addName.trim() || !addCategoryId || Object.values(addPrices).every((v) => !v) || (stockCountingEnabled && !addStock)}
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
                {editMenuId ? (
                  <div>
                    <label className="block text-xs font-medium text-slate-500 mb-1">
                      {menuEntities.find((m) => m.id === editMenuId)?.name ?? "Menu"} Price ($)
                    </label>
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      value={editPrices[editMenuId] ?? ""}
                      onChange={(e) => setEditPrices((prev) => ({ ...prev, [editMenuId]: e.target.value }))}
                      className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                    />
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
                      value={editPrices["default"] ?? ""}
                      onChange={(e) => setEditPrices((prev) => ({ ...prev, default: e.target.value }))}
                      className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                    />
                  </div>
                )}

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

                {/* ── Menu ── */}
                {editCategoryHasSchedule ? (
                  <div className="flex items-center gap-2 px-3 py-2.5 rounded-xl bg-blue-50 border border-blue-100">
                    <Clock size={14} className="text-blue-500 shrink-0" />
                    <p className="text-xs text-blue-600">
                      This category is scheduled ({editSelectedCategory!.scheduleIds.map((id) => scheduleMap.get(id) ?? id).join(", ")}). Items follow the category&apos;s schedule automatically.
                    </p>
                  </div>
                ) : (
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-1.5">
                      Menu
                    </label>
                    <select
                      value={editMenuId}
                      onChange={(e) => setEditMenuId(e.target.value)}
                      className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white"
                    >
                      <option value="">POS (always available)</option>
                      {menuEntities.filter((m) => m.isActive).map((m) => (
                        <option key={m.id} value={m.id}>
                          {m.name}
                        </option>
                      ))}
                    </select>
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

                {/* ── Assign Taxes ── */}
                {taxes.length > 0 && (
                  <div>
                    <label className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-2">
                      <Receipt size={15} />
                      Assign Taxes
                    </label>
                    <div className="flex flex-col gap-2 pl-1 max-h-40 overflow-y-auto">
                      {taxes.filter((t) => t.enabled).map((t) => (
                        <label
                          key={t.id}
                          className="flex items-center gap-2 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={editTaxes[t.id] ?? false}
                            onChange={(e) =>
                              setEditTaxes((prev) => ({
                                ...prev,
                                [t.id]: e.target.checked,
                              }))
                            }
                            className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="text-sm text-slate-700">{t.name}</span>
                          <span className="text-[10px] px-1.5 py-0.5 rounded font-medium bg-amber-50 text-amber-600">
                            {t.amount}%
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
