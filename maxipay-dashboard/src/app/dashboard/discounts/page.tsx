"use client";

import { useEffect, useState } from "react";
import {
  collection,
  addDoc,
  updateDoc,
  deleteDoc,
  doc,
  getDocs,
  onSnapshot,
  serverTimestamp,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import {
  Plus,
  Pencil,
  Trash2,
  X,
  AlertTriangle,
  Percent,
  DollarSign,
  ShoppingCart,
  Tag,
  CreditCard,
  Clock,
  Zap,
  Check,
  Search,
} from "lucide-react";

interface DiscountSchedule {
  days: string[];
  startTime: string;
  endTime: string;
}

interface Discount {
  id: string;
  name: string;
  type: "PERCENTAGE" | "FIXED";
  value: number;
  applyTo: "ITEM" | "ORDER";
  applyScope: "order" | "item" | "manual";
  active: boolean;
  autoApply: boolean;
  schedule?: DiscountSchedule;
  itemIds: string[];
  itemNames: string[];
}

interface MenuItem {
  id: string;
  name: string;
  price: number;
  categoryId: string;
}

interface Category {
  id: string;
  name: string;
}

const DAY_OPTIONS = [
  { key: "MON", label: "M" },
  { key: "TUE", label: "T" },
  { key: "WED", label: "W" },
  { key: "THU", label: "T" },
  { key: "FRI", label: "F" },
  { key: "SAT", label: "S" },
  { key: "SUN", label: "S" },
];

export default function DiscountsPage() {
  const { user } = useAuth();
  const [discounts, setDiscounts] = useState<Discount[]>([]);
  const [loading, setLoading] = useState(true);
  const [menuItems, setMenuItems] = useState<MenuItem[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Discount | null>(null);
  const [name, setName] = useState("");
  const [type, setType] = useState<"PERCENTAGE" | "FIXED">("PERCENTAGE");
  const [value, setValue] = useState("");
  const [applyScope, setApplyScope] = useState<"order" | "item" | "manual">(
    "order"
  );
  const [autoApply, setAutoApply] = useState(true);
  const [active, setActive] = useState(true);
  const [saving, setSaving] = useState(false);
  const [scheduleDays, setScheduleDays] = useState<string[]>([]);
  const [startTime, setStartTime] = useState("");
  const [endTime, setEndTime] = useState("");
  const [selectedItemIds, setSelectedItemIds] = useState<string[]>([]);
  const [selectedItemNames, setSelectedItemNames] = useState<string[]>([]);

  // Item picker modal
  const [itemPickerOpen, setItemPickerOpen] = useState(false);
  const [pickerSearch, setPickerSearch] = useState("");
  const [pickerCategory, setPickerCategory] = useState<string | null>(null);
  const [pickerSelectedIds, setPickerSelectedIds] = useState<string[]>([]);
  const [pickerSelectedNames, setPickerSelectedNames] = useState<string[]>([]);

  const [deleteTarget, setDeleteTarget] = useState<Discount | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    if (!user) return;
    getDocs(collection(db, "MenuItems"))
      .then((snap) => {
        const items: MenuItem[] = [];
        snap.forEach((d) => {
          const data = d.data();
          if (data.name)
            items.push({
              id: d.id,
              name: data.name,
              price: data.price ?? 0,
              categoryId: data.categoryId ?? "",
            });
        });
        items.sort((a, b) => a.name.localeCompare(b.name));
        setMenuItems(items);
      })
      .catch((err) => console.error("Failed to load menu items:", err));

    getDocs(collection(db, "Categories"))
      .then((snap) => {
        const cats: Category[] = [];
        snap.forEach((d) => {
          const data = d.data();
          if (data.name) cats.push({ id: d.id, name: data.name });
        });
        cats.sort((a, b) => a.name.localeCompare(b.name));
        setCategories(cats);
      })
      .catch((err) => console.error("Failed to load categories:", err));
  }, [user]);

  useEffect(() => {
    if (!user) return;
    const unsub = onSnapshot(collection(db, "discounts"), (snap) => {
      const list: Discount[] = [];
      snap.forEach((d) => {
        const data = d.data();
        const applyTo = data.applyTo ?? "ORDER";
        list.push({
          id: d.id,
          name: data.name ?? "",
          type: data.type ?? "PERCENTAGE",
          value: data.value ?? 0,
          applyTo,
          applyScope:
            data.applyScope ?? (applyTo === "ITEM" ? "item" : "order"),
          active: data.active ?? true,
          autoApply: data.autoApply ?? true,
          schedule: data.schedule ?? undefined,
          itemIds: data.itemIds ?? [],
          itemNames: data.itemNames ?? [],
        });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setDiscounts(list);
      setLoading(false);
    });
    return () => unsub();
  }, [user]);

  // ── Discount CRUD ──

  const openAdd = () => {
    setEditing(null);
    setName("");
    setType("PERCENTAGE");
    setValue("");
    setApplyScope("order");
    setAutoApply(true);
    setActive(true);
    setScheduleDays([]);
    setStartTime("");
    setEndTime("");
    setSelectedItemIds([]);
    setSelectedItemNames([]);
    setModalOpen(true);
  };

  const openEdit = (d: Discount) => {
    setEditing(d);
    setName(d.name);
    setType(d.type);
    setValue(String(d.value));
    setApplyScope(d.applyScope);
    setAutoApply(d.autoApply);
    setActive(d.active);
    setScheduleDays(d.schedule?.days ?? []);
    setStartTime(d.schedule?.startTime ?? "");
    setEndTime(d.schedule?.endTime ?? "");
    setSelectedItemIds(d.itemIds ?? []);
    setSelectedItemNames(d.itemNames ?? []);
    setModalOpen(true);
  };

  const handleSave = async () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    const numValue = parseFloat(value) || 0;
    if (numValue <= 0) return;
    if (type === "PERCENTAGE" && numValue > 100) return;
    if (applyScope === "item" && selectedItemIds.length === 0) return;

    setSaving(true);
    try {
      const applyTo = applyScope === "item" ? "ITEM" : "ORDER";
      const effectiveAutoApply = applyScope === "manual" ? false : autoApply;

      const data: Record<string, unknown> = {
        name: trimmed,
        type,
        value: numValue,
        applyTo,
        applyScope,
        active,
        autoApply: effectiveAutoApply,
        schedule: { days: scheduleDays, startTime, endTime },
        itemIds: applyScope === "item" ? selectedItemIds : [],
        itemNames: applyScope === "item" ? selectedItemNames : [],
      };

      if (editing) {
        data.updatedAt = serverTimestamp();
        await updateDoc(doc(db, "discounts", editing.id), data);
      } else {
        data.createdAt = serverTimestamp();
        await addDoc(collection(db, "discounts"), data);
      }
      setModalOpen(false);
    } catch (err) {
      console.error("Failed to save discount:", err);
    } finally {
      setSaving(false);
    }
  };

  const handleToggleActive = async (d: Discount) => {
    try {
      await updateDoc(doc(db, "discounts", d.id), { active: !d.active });
    } catch (err) {
      console.error("Failed to toggle discount:", err);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteDoc(doc(db, "discounts", deleteTarget.id));
    } catch (err) {
      console.error("Failed to delete discount:", err);
    } finally {
      setDeleting(false);
      setDeleteTarget(null);
    }
  };

  const toggleDay = (day: string) => {
    setScheduleDays((prev) =>
      prev.includes(day) ? prev.filter((d) => d !== day) : [...prev, day]
    );
  };

  const removeSelectedItem = (id: string) => {
    const idx = selectedItemIds.indexOf(id);
    if (idx >= 0) {
      setSelectedItemIds((prev) => prev.filter((x) => x !== id));
      setSelectedItemNames((prev) => prev.filter((_, i) => i !== idx));
    }
  };

  // ── Item picker ──

  const openItemPicker = () => {
    setPickerSelectedIds([...selectedItemIds]);
    setPickerSelectedNames([...selectedItemNames]);
    setPickerSearch("");
    setPickerCategory(null);
    setItemPickerOpen(true);
  };

  const togglePickerItem = (item: MenuItem) => {
    const idx = pickerSelectedIds.indexOf(item.id);
    if (idx >= 0) {
      setPickerSelectedIds((prev) => prev.filter((id) => id !== item.id));
      setPickerSelectedNames((prev) => prev.filter((_, i) => i !== idx));
    } else {
      setPickerSelectedIds((prev) => [...prev, item.id]);
      setPickerSelectedNames((prev) => [...prev, item.name]);
    }
  };

  const applyItemPicker = () => {
    setSelectedItemIds([...pickerSelectedIds]);
    setSelectedItemNames([...pickerSelectedNames]);
    setItemPickerOpen(false);
  };

  const categoryName = (catId: string) =>
    categories.find((c) => c.id === catId)?.name ?? "Uncategorized";

  const pickerFilteredItems = menuItems.filter((item) => {
    if (
      pickerCategory &&
      item.categoryId !== pickerCategory
    )
      return false;
    if (pickerSearch.trim()) {
      const q = pickerSearch.trim().toLowerCase();
      if (!item.name.toLowerCase().includes(q)) return false;
    }
    return true;
  });

  const pickerGrouped = new Map<string, MenuItem[]>();
  for (const item of pickerFilteredItems) {
    const cat = categoryName(item.categoryId);
    if (!pickerGrouped.has(cat)) pickerGrouped.set(cat, []);
    pickerGrouped.get(cat)!.push(item);
  }
  const pickerGroupedSorted = [...pickerGrouped.entries()].sort((a, b) =>
    a[0].localeCompare(b[0])
  );

  // ── Helpers ──

  const formatValue = (d: Discount) =>
    d.type === "PERCENTAGE" ? `${d.value}%` : `$${d.value.toFixed(2)}`;

  const scopeLabel = (scope: string) => {
    switch (scope) {
      case "item":
        return "Specific Items";
      case "manual":
        return "Checkout Option";
      default:
        return "Entire Order";
    }
  };

  const scopeIcon = (scope: string) => {
    switch (scope) {
      case "item":
        return <Tag size={14} className="text-slate-400" />;
      case "manual":
        return <CreditCard size={14} className="text-slate-400" />;
      default:
        return <ShoppingCart size={14} className="text-slate-400" />;
    }
  };

  const canSave =
    name.trim().length > 0 &&
    parseFloat(value) > 0 &&
    (applyScope !== "item" || selectedItemIds.length > 0);

  return (
    <>
      <Header title="Discounts" />
      <div className="p-6 h-[calc(100vh-64px)] flex flex-col">
        {loading ? (
          <div className="flex items-center justify-center flex-1">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          </div>
        ) : (
          <div className="flex-1 flex flex-col min-h-0">
            <div className="flex items-center justify-between mb-6">
              <div>
                <p className="text-sm text-slate-500">
                  {discounts.length} discount
                  {discounts.length !== 1 ? "s" : ""}
                </p>
              </div>
              <button
                onClick={openAdd}
                className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
              >
                <Plus size={16} />
                Add Discount
              </button>
            </div>

            {discounts.length === 0 ? (
              <div className="flex-1 flex flex-col items-center justify-center text-slate-400">
                <Tag size={48} className="mb-4 text-slate-300" />
                <p className="text-lg font-medium">No discounts yet</p>
                <p className="text-sm mt-1">
                  Create your first discount to get started
                </p>
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 overflow-y-auto pb-4">
                {discounts.map((d) => (
                  <div
                    key={d.id}
                    className={`bg-white rounded-2xl border shadow-sm p-5 transition-all ${
                      d.active
                        ? "border-slate-100"
                        : "border-slate-200 opacity-60"
                    }`}
                  >
                    <div className="flex items-start justify-between mb-4">
                      <div className="flex items-center gap-3 min-w-0">
                        <div
                          className={`w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 ${
                            d.type === "PERCENTAGE"
                              ? "bg-purple-50 text-purple-600"
                              : "bg-green-50 text-green-600"
                          }`}
                        >
                          {d.type === "PERCENTAGE" ? (
                            <Percent size={20} />
                          ) : (
                            <DollarSign size={20} />
                          )}
                        </div>
                        <div className="min-w-0">
                          <h3 className="text-sm font-semibold text-slate-800 truncate">
                            {d.name}
                          </h3>
                          <p className="text-xs text-slate-400 mt-0.5">
                            {d.type === "PERCENTAGE"
                              ? "Percentage"
                              : "Fixed Amount"}
                          </p>
                        </div>
                      </div>
                      <div className="flex items-center gap-1 flex-shrink-0 ml-2">
                        <button
                          onClick={() => openEdit(d)}
                          className="p-1.5 rounded-lg text-slate-400 hover:bg-blue-50 hover:text-blue-600 transition-colors"
                          title="Edit"
                        >
                          <Pencil size={15} />
                        </button>
                        <button
                          onClick={() => setDeleteTarget(d)}
                          className="p-1.5 rounded-lg text-slate-400 hover:bg-red-50 hover:text-red-500 transition-colors"
                          title="Delete"
                        >
                          <Trash2 size={15} />
                        </button>
                      </div>
                    </div>

                    <div className="mb-4">
                      <span
                        className={`text-2xl font-bold ${
                          d.type === "PERCENTAGE"
                            ? "text-purple-600"
                            : "text-green-600"
                        }`}
                      >
                        {formatValue(d)}
                      </span>
                    </div>

                    <div className="flex items-center justify-between pt-3 border-t border-slate-100">
                      <div className="flex items-center gap-3">
                        <div className="flex items-center gap-1.5">
                          {scopeIcon(d.applyScope)}
                          <span className="text-xs font-medium text-slate-500">
                            {scopeLabel(d.applyScope)}
                          </span>
                        </div>
                        {d.autoApply && d.applyScope !== "manual" && (
                          <div className="flex items-center gap-1 px-1.5 py-0.5 rounded bg-amber-50">
                            <Zap size={10} className="text-amber-500" />
                            <span className="text-[10px] font-medium text-amber-600">
                              Auto
                            </span>
                          </div>
                        )}
                        {d.schedule?.days && d.schedule.days.length > 0 && (
                          <div className="flex items-center gap-1 px-1.5 py-0.5 rounded bg-blue-50">
                            <Clock size={10} className="text-blue-500" />
                            <span className="text-[10px] font-medium text-blue-600">
                              Scheduled
                            </span>
                          </div>
                        )}
                      </div>
                      <button
                        onClick={() => handleToggleActive(d)}
                        className={`relative w-10 h-[22px] rounded-full transition-colors ${
                          d.active ? "bg-blue-600" : "bg-slate-300"
                        }`}
                      >
                        <span
                          className={`absolute top-[3px] w-4 h-4 rounded-full bg-white shadow-sm transition-transform ${
                            d.active ? "left-[22px]" : "left-[3px]"
                          }`}
                        />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* ── Add / Edit Modal ── */}
      {modalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !saving && setModalOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 max-h-[90vh] flex flex-col overflow-hidden">
            <div className="px-6 py-5 overflow-y-auto space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  {editing ? "Edit Discount" : "Add Discount"}
                </h3>
                <button
                  onClick={() => setModalOpen(false)}
                  disabled={saving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <div className="space-y-4">
                {/* Name */}
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Discount Name
                  </label>
                  <input
                    type="text"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder="e.g. Happy Hour, Employee Discount"
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>

                {/* Type */}
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Type
                  </label>
                  <div className="grid grid-cols-2 gap-2">
                    <button
                      type="button"
                      onClick={() => setType("PERCENTAGE")}
                      className={`flex items-center justify-center gap-2 px-3 py-2.5 rounded-xl border text-sm font-medium transition-all ${
                        type === "PERCENTAGE"
                          ? "border-blue-400 bg-blue-50 text-blue-700 ring-2 ring-blue-400/20"
                          : "border-slate-200 text-slate-600 hover:bg-slate-50"
                      }`}
                    >
                      <Percent size={16} />
                      Percentage
                    </button>
                    <button
                      type="button"
                      onClick={() => setType("FIXED")}
                      className={`flex items-center justify-center gap-2 px-3 py-2.5 rounded-xl border text-sm font-medium transition-all ${
                        type === "FIXED"
                          ? "border-blue-400 bg-blue-50 text-blue-700 ring-2 ring-blue-400/20"
                          : "border-slate-200 text-slate-600 hover:bg-slate-50"
                      }`}
                    >
                      <DollarSign size={16} />
                      Fixed
                    </button>
                  </div>
                </div>

                {/* Value */}
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Value {type === "PERCENTAGE" ? "(%)" : "($)"}
                  </label>
                  <input
                    type="number"
                    min="0"
                    max={type === "PERCENTAGE" ? "100" : undefined}
                    step={type === "PERCENTAGE" ? "1" : "0.01"}
                    value={value}
                    onChange={(e) => setValue(e.target.value)}
                    placeholder={
                      type === "PERCENTAGE" ? "e.g. 10" : "e.g. 5.00"
                    }
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>

                {/* Apply Scope */}
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Apply Scope
                  </label>
                  <div className="grid grid-cols-3 gap-2">
                    <button
                      type="button"
                      onClick={() => setApplyScope("order")}
                      className={`flex flex-col items-center gap-1.5 px-2 py-2.5 rounded-xl border text-xs font-medium transition-all ${
                        applyScope === "order"
                          ? "border-blue-400 bg-blue-50 text-blue-700 ring-2 ring-blue-400/20"
                          : "border-slate-200 text-slate-600 hover:bg-slate-50"
                      }`}
                    >
                      <ShoppingCart size={16} />
                      Order
                    </button>
                    <button
                      type="button"
                      onClick={() => setApplyScope("item")}
                      className={`flex flex-col items-center gap-1.5 px-2 py-2.5 rounded-xl border text-xs font-medium transition-all ${
                        applyScope === "item"
                          ? "border-blue-400 bg-blue-50 text-blue-700 ring-2 ring-blue-400/20"
                          : "border-slate-200 text-slate-600 hover:bg-slate-50"
                      }`}
                    >
                      <Tag size={16} />
                      Items
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setApplyScope("manual");
                        setAutoApply(false);
                      }}
                      className={`flex flex-col items-center gap-1.5 px-2 py-2.5 rounded-xl border text-xs font-medium transition-all ${
                        applyScope === "manual"
                          ? "border-blue-400 bg-blue-50 text-blue-700 ring-2 ring-blue-400/20"
                          : "border-slate-200 text-slate-600 hover:bg-slate-50"
                      }`}
                    >
                      <CreditCard size={16} />
                      Checkout
                    </button>
                  </div>
                </div>

                {/* Item Selector (when scope = item) */}
                {applyScope === "item" && (
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-1.5">
                      Select Items
                    </label>
                    <button
                      type="button"
                      onClick={openItemPicker}
                      className="w-full flex items-center justify-between px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-left hover:bg-slate-50 hover:border-slate-300 transition-colors"
                    >
                      <span
                        className={
                          selectedItemIds.length > 0
                            ? "text-slate-800"
                            : "text-slate-400"
                        }
                      >
                        {selectedItemIds.length > 0
                          ? `${selectedItemIds.length} item${selectedItemIds.length !== 1 ? "s" : ""} selected`
                          : "Choose items…"}
                      </span>
                      <Search size={16} className="text-slate-400" />
                    </button>
                    {selectedItemNames.length > 0 && (
                      <div className="flex flex-wrap gap-1.5 mt-2">
                        {selectedItemNames.map((n, i) => (
                          <span
                            key={selectedItemIds[i]}
                            className="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg bg-blue-50 text-blue-700 text-xs font-medium"
                          >
                            {n}
                            <button
                              type="button"
                              onClick={() =>
                                removeSelectedItem(selectedItemIds[i])
                              }
                              className="text-blue-400 hover:text-blue-600"
                            >
                              <X size={12} />
                            </button>
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                )}

                {/* Schedule */}
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Schedule{" "}
                    <span className="text-slate-400 font-normal">
                      (optional)
                    </span>
                  </label>
                  <div className="flex items-center justify-center gap-1.5 mb-3">
                    {DAY_OPTIONS.map((d) => {
                      const selected = scheduleDays.includes(d.key);
                      return (
                        <button
                          key={d.key}
                          type="button"
                          onClick={() => toggleDay(d.key)}
                          className={`w-9 h-9 rounded-full text-xs font-bold transition-all ${
                            selected
                              ? "bg-blue-600 text-white shadow-sm"
                              : "bg-slate-100 text-slate-500 hover:bg-slate-200"
                          }`}
                        >
                          {d.label}
                        </button>
                      );
                    })}
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="block text-xs text-slate-400 mb-1">
                        Start Time
                      </label>
                      <input
                        type="time"
                        value={startTime}
                        onChange={(e) => setStartTime(e.target.value)}
                        className="w-full px-3 py-2 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                      />
                    </div>
                    <div>
                      <label className="block text-xs text-slate-400 mb-1">
                        End Time
                      </label>
                      <input
                        type="time"
                        value={endTime}
                        onChange={(e) => setEndTime(e.target.value)}
                        className="w-full px-3 py-2 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                      />
                    </div>
                  </div>
                </div>

                {/* Auto Apply */}
                {applyScope !== "manual" && (
                  <div className="flex items-center justify-between py-1">
                    <div>
                      <span className="text-sm font-medium text-slate-700 block">
                        Auto Apply
                      </span>
                      <span className="text-xs text-slate-400">
                        Automatically apply when conditions match
                      </span>
                    </div>
                    <button
                      type="button"
                      onClick={() => setAutoApply(!autoApply)}
                      className={`relative w-10 h-[22px] rounded-full transition-colors flex-shrink-0 ${
                        autoApply ? "bg-blue-600" : "bg-slate-300"
                      }`}
                    >
                      <span
                        className={`absolute top-[3px] w-4 h-4 rounded-full bg-white shadow-sm transition-transform ${
                          autoApply ? "left-[22px]" : "left-[3px]"
                        }`}
                      />
                    </button>
                  </div>
                )}

                {/* Active */}
                <div className="flex items-center justify-between py-1">
                  <span className="text-sm font-medium text-slate-700">
                    Active
                  </span>
                  <button
                    type="button"
                    onClick={() => setActive(!active)}
                    className={`relative w-10 h-[22px] rounded-full transition-colors ${
                      active ? "bg-blue-600" : "bg-slate-300"
                    }`}
                  >
                    <span
                      className={`absolute top-[3px] w-4 h-4 rounded-full bg-white shadow-sm transition-transform ${
                        active ? "left-[22px]" : "left-[3px]"
                      }`}
                    />
                  </button>
                </div>
              </div>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setModalOpen(false)}
                  disabled={saving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSave}
                  disabled={saving || !canSave}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {saving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Saving…
                    </>
                  ) : editing ? (
                    "Save Changes"
                  ) : (
                    "Add Discount"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Item Picker Modal ── */}
      {itemPickerOpen && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/50 backdrop-blur-sm"
            onClick={() => setItemPickerOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-2xl w-full max-w-2xl mx-4 h-[80vh] flex flex-col overflow-hidden">
            {/* Header */}
            <div className="px-6 pt-5 pb-4 border-b border-slate-100 flex-shrink-0">
              <div className="flex items-center justify-between mb-4">
                <h3 className="text-lg font-semibold text-slate-800">
                  Select Items
                </h3>
                <button
                  onClick={() => setItemPickerOpen(false)}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>
              <div className="relative">
                <Search
                  size={16}
                  className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
                />
                <input
                  type="text"
                  value={pickerSearch}
                  onChange={(e) => setPickerSearch(e.target.value)}
                  placeholder="Search items..."
                  className="w-full pl-9 pr-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  autoFocus
                />
              </div>
            </div>

            {/* Two-column body */}
            <div className="flex flex-1 min-h-0">
              {/* Left: Categories */}
              <div className="w-48 border-r border-slate-100 overflow-y-auto flex-shrink-0 bg-slate-50/50">
                <div className="py-2">
                  <button
                    onClick={() => setPickerCategory(null)}
                    className={`w-full text-left px-4 py-2.5 text-sm font-medium transition-colors ${
                      pickerCategory === null
                        ? "bg-blue-50 text-blue-700 border-r-2 border-blue-600"
                        : "text-slate-600 hover:bg-slate-100"
                    }`}
                  >
                    All Items
                    <span className="ml-1.5 text-xs opacity-60">
                      ({menuItems.length})
                    </span>
                  </button>
                  {categories.map((cat) => {
                    const count = menuItems.filter(
                      (i) => i.categoryId === cat.id
                    ).length;
                    if (count === 0) return null;
                    return (
                      <button
                        key={cat.id}
                        onClick={() => setPickerCategory(cat.id)}
                        className={`w-full text-left px-4 py-2.5 text-sm font-medium transition-colors ${
                          pickerCategory === cat.id
                            ? "bg-blue-50 text-blue-700 border-r-2 border-blue-600"
                            : "text-slate-600 hover:bg-slate-100"
                        }`}
                      >
                        {cat.name}
                        <span className="ml-1.5 text-xs opacity-60">
                          ({count})
                        </span>
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* Right: Items */}
              <div className="flex-1 overflow-y-auto">
                {pickerFilteredItems.length === 0 ? (
                  <div className="flex flex-col items-center justify-center h-full text-slate-400">
                    <Search size={32} className="mb-2 text-slate-300" />
                    <p className="text-sm">No items found</p>
                  </div>
                ) : pickerCategory ? (
                  <div className="py-1">
                    {pickerFilteredItems.map((item) => {
                      const selected = pickerSelectedIds.includes(item.id);
                      return (
                        <button
                          key={item.id}
                          onClick={() => togglePickerItem(item)}
                          className={`w-full flex items-center gap-3 px-5 py-3 text-left transition-colors ${
                            selected
                              ? "bg-blue-50/60"
                              : "hover:bg-slate-50"
                          }`}
                        >
                          <div
                            className={`w-5 h-5 rounded-md border-2 flex items-center justify-center flex-shrink-0 transition-colors ${
                              selected
                                ? "bg-blue-600 border-blue-600"
                                : "border-slate-300"
                            }`}
                          >
                            {selected && (
                              <Check size={12} className="text-white" />
                            )}
                          </div>
                          <div className="flex-1 min-w-0">
                            <p
                              className={`text-sm truncate ${selected ? "font-semibold text-slate-800" : "text-slate-700"}`}
                            >
                              {item.name}
                            </p>
                            {item.price > 0 && (
                              <p className="text-xs text-slate-400 mt-0.5">
                                ${item.price.toFixed(2)}
                              </p>
                            )}
                          </div>
                        </button>
                      );
                    })}
                  </div>
                ) : (
                  <div className="py-1">
                    {pickerGroupedSorted.map(([catName, items]) => (
                      <div key={catName}>
                        <div className="px-5 pt-4 pb-2 sticky top-0 bg-white/95 backdrop-blur-sm z-[1]">
                          <p className="text-xs font-bold text-slate-400 uppercase tracking-wider">
                            {catName}
                            <span className="ml-1.5 normal-case tracking-normal font-medium">
                              ({items.length})
                            </span>
                          </p>
                        </div>
                        {items.map((item) => {
                          const selected = pickerSelectedIds.includes(item.id);
                          return (
                            <button
                              key={item.id}
                              onClick={() => togglePickerItem(item)}
                              className={`w-full flex items-center gap-3 px-5 py-3 text-left transition-colors ${
                                selected
                                  ? "bg-blue-50/60"
                                  : "hover:bg-slate-50"
                              }`}
                            >
                              <div
                                className={`w-5 h-5 rounded-md border-2 flex items-center justify-center flex-shrink-0 transition-colors ${
                                  selected
                                    ? "bg-blue-600 border-blue-600"
                                    : "border-slate-300"
                                }`}
                              >
                                {selected && (
                                  <Check size={12} className="text-white" />
                                )}
                              </div>
                              <div className="flex-1 min-w-0">
                                <p
                                  className={`text-sm truncate ${selected ? "font-semibold text-slate-800" : "text-slate-700"}`}
                                >
                                  {item.name}
                                </p>
                                {item.price > 0 && (
                                  <p className="text-xs text-slate-400 mt-0.5">
                                    ${item.price.toFixed(2)}
                                  </p>
                                )}
                              </div>
                            </button>
                          );
                        })}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>

            {/* Footer */}
            <div className="px-6 py-4 border-t border-slate-100 flex items-center justify-between flex-shrink-0 bg-white">
              <p className="text-sm font-medium text-slate-600">
                {pickerSelectedIds.length} item
                {pickerSelectedIds.length !== 1 ? "s" : ""} selected
              </p>
              <button
                onClick={applyItemPicker}
                className="px-6 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
              >
                Apply
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Delete Confirmation ── */}
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
                  Delete Discount
                </h3>
              </div>
              <p className="text-sm text-slate-500 text-center">
                Are you sure you want to delete{" "}
                <strong className="text-slate-700">
                  {deleteTarget.name}
                </strong>
                ? This cannot be undone.
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
    </>
  );
}
