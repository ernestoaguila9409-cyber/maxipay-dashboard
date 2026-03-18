"use client";

import { useEffect, useState } from "react";
import {
  collection,
  addDoc,
  updateDoc,
  deleteDoc,
  doc,
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
} from "lucide-react";

interface Discount {
  id: string;
  name: string;
  type: "PERCENTAGE" | "FIXED";
  value: number;
  applyTo: "ITEM" | "ORDER";
  active: boolean;
}

export default function DiscountsPage() {
  const { user } = useAuth();
  const [discounts, setDiscounts] = useState<Discount[]>([]);
  const [loading, setLoading] = useState(true);

  // Modal state
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Discount | null>(null);
  const [name, setName] = useState("");
  const [type, setType] = useState<"PERCENTAGE" | "FIXED">("PERCENTAGE");
  const [value, setValue] = useState("");
  const [applyTo, setApplyTo] = useState<"ITEM" | "ORDER">("ORDER");
  const [active, setActive] = useState(true);
  const [saving, setSaving] = useState(false);

  // Delete state
  const [deleteTarget, setDeleteTarget] = useState<Discount | null>(null);
  const [deleting, setDeleting] = useState(false);

  // Real-time listener
  useEffect(() => {
    if (!user) return;
    const unsub = onSnapshot(collection(db, "discounts"), (snap) => {
      const list: Discount[] = [];
      snap.forEach((d) => {
        const data = d.data();
        list.push({
          id: d.id,
          name: data.name ?? "",
          type: data.type ?? "PERCENTAGE",
          value: data.value ?? 0,
          applyTo: data.applyTo ?? "ORDER",
          active: data.active ?? true,
        });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setDiscounts(list);
      setLoading(false);
    });
    return () => unsub();
  }, [user]);

  // ── CRUD ──

  const openAdd = () => {
    setEditing(null);
    setName("");
    setType("PERCENTAGE");
    setValue("");
    setApplyTo("ORDER");
    setActive(true);
    setModalOpen(true);
  };

  const openEdit = (d: Discount) => {
    setEditing(d);
    setName(d.name);
    setType(d.type);
    setValue(String(d.value));
    setApplyTo(d.applyTo);
    setActive(d.active);
    setModalOpen(true);
  };

  const handleSave = async () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    const numValue = parseFloat(value) || 0;
    if (numValue <= 0) return;
    if (type === "PERCENTAGE" && numValue > 100) return;

    setSaving(true);
    try {
      const data = {
        name: trimmed,
        type,
        value: numValue,
        applyTo,
        active,
      };
      if (editing) {
        await updateDoc(doc(db, "discounts", editing.id), data);
      } else {
        await addDoc(collection(db, "discounts"), {
          ...data,
          createdAt: serverTimestamp(),
        });
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

  const formatValue = (d: Discount) =>
    d.type === "PERCENTAGE" ? `${d.value}%` : `$${d.value.toFixed(2)}`;

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
            {/* Header bar */}
            <div className="flex items-center justify-between mb-6">
              <div>
                <p className="text-sm text-slate-500">
                  {discounts.length} discount{discounts.length !== 1 ? "s" : ""}
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

            {/* Cards grid */}
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
                    {/* Card header */}
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
                            {d.type === "PERCENTAGE" ? "Percentage" : "Fixed Amount"}
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

                    {/* Value */}
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

                    {/* Footer */}
                    <div className="flex items-center justify-between pt-3 border-t border-slate-100">
                      <div className="flex items-center gap-1.5">
                        {d.applyTo === "ORDER" ? (
                          <ShoppingCart size={14} className="text-slate-400" />
                        ) : (
                          <Tag size={14} className="text-slate-400" />
                        )}
                        <span className="text-xs font-medium text-slate-500">
                          {d.applyTo === "ORDER" ? "Entire Order" : "Per Item"}
                        </span>
                      </div>

                      {/* Active toggle */}
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
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-5">
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
                    placeholder={type === "PERCENTAGE" ? "e.g. 10" : "e.g. 5.00"}
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>

                {/* Applies To */}
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Applies To
                  </label>
                  <div className="grid grid-cols-2 gap-2">
                    <button
                      type="button"
                      onClick={() => setApplyTo("ORDER")}
                      className={`flex items-center justify-center gap-2 px-3 py-2.5 rounded-xl border text-sm font-medium transition-all ${
                        applyTo === "ORDER"
                          ? "border-blue-400 bg-blue-50 text-blue-700 ring-2 ring-blue-400/20"
                          : "border-slate-200 text-slate-600 hover:bg-slate-50"
                      }`}
                    >
                      <ShoppingCart size={16} />
                      Order
                    </button>
                    <button
                      type="button"
                      onClick={() => setApplyTo("ITEM")}
                      className={`flex items-center justify-center gap-2 px-3 py-2.5 rounded-xl border text-sm font-medium transition-all ${
                        applyTo === "ITEM"
                          ? "border-blue-400 bg-blue-50 text-blue-700 ring-2 ring-blue-400/20"
                          : "border-slate-200 text-slate-600 hover:bg-slate-50"
                      }`}
                    >
                      <Tag size={16} />
                      Item
                    </button>
                  </div>
                </div>

                {/* Active toggle */}
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
                  disabled={saving || !name.trim() || !(parseFloat(value) > 0)}
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
                <strong className="text-slate-700">{deleteTarget.name}</strong>?
                This cannot be undone.
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
