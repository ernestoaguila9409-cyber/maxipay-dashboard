"use client";

import { useEffect, useState } from "react";
import {
  collection,
  addDoc,
  updateDoc,
  deleteDoc,
  doc,
  onSnapshot,
  Timestamp,
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
  ToggleLeft,
  ToggleRight,
} from "lucide-react";

interface TaxEntry {
  id: string;
  name: string;
  type: "PERCENTAGE" | "FIXED";
  amount: number;
  enabled: boolean;
}

export default function TaxesPage() {
  const { user } = useAuth();
  const [taxes, setTaxes] = useState<TaxEntry[]>([]);
  const [loading, setLoading] = useState(true);

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<TaxEntry | null>(null);
  const [taxName, setTaxName] = useState("");
  const [taxType, setTaxType] = useState<"PERCENTAGE" | "FIXED">("PERCENTAGE");
  const [taxAmount, setTaxAmount] = useState("");
  const [saving, setSaving] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<TaxEntry | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    if (!user) return;
    const unsub = onSnapshot(collection(db, "Taxes"), (snap) => {
      const list: TaxEntry[] = [];
      snap.forEach((d) => {
        const data = d.data();
        list.push({
          id: d.id,
          name: data.name ?? "",
          type: data.type ?? "PERCENTAGE",
          amount: data.amount ?? 0,
          enabled: data.enabled ?? true,
        });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setTaxes(list);
      setLoading(false);
    });
    return () => unsub();
  }, [user]);

  const openAdd = () => {
    setEditing(null);
    setTaxName("");
    setTaxType("PERCENTAGE");
    setTaxAmount("");
    setModalOpen(true);
  };

  const openEdit = (t: TaxEntry) => {
    setEditing(t);
    setTaxName(t.name);
    setTaxType(t.type);
    setTaxAmount(String(t.amount));
    setModalOpen(true);
  };

  const handleSave = async () => {
    const name = taxName.trim();
    if (!name) return;
    const amount = parseFloat(taxAmount);
    if (isNaN(amount) || amount < 0) return;

    setSaving(true);
    try {
      if (editing) {
        await updateDoc(doc(db, "Taxes", editing.id), {
          name,
          type: taxType,
          amount,
        });
      } else {
        await addDoc(collection(db, "Taxes"), {
          name,
          type: taxType,
          amount,
          enabled: true,
          createdAt: Timestamp.now(),
        });
      }
      setModalOpen(false);
    } catch (err) {
      console.error("Failed to save tax:", err);
    } finally {
      setSaving(false);
    }
  };

  const handleToggle = async (t: TaxEntry) => {
    try {
      await updateDoc(doc(db, "Taxes", t.id), { enabled: !t.enabled });
    } catch (err) {
      console.error("Failed to toggle tax:", err);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteDoc(doc(db, "Taxes", deleteTarget.id));
    } catch (err) {
      console.error("Failed to delete tax:", err);
    } finally {
      setDeleting(false);
      setDeleteTarget(null);
    }
  };

  return (
    <>
      <Header title="Taxes" />
      <div className="p-6 space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm text-slate-500">
              Manage tax rates. Assign taxes to menu items from the Menu page.
            </p>
          </div>
          <button
            onClick={openAdd}
            className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
          >
            <Plus size={16} />
            Add Tax
          </button>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          </div>
        ) : taxes.length === 0 ? (
          <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-12 text-center">
            <p className="text-slate-400 text-lg">No taxes configured</p>
            <p className="text-slate-400 text-sm mt-1">
              Add a tax rate to get started
            </p>
          </div>
        ) : (
          <div className="bg-white rounded-2xl shadow-sm border border-slate-100 overflow-hidden">
            <table className="w-full">
              <thead>
                <tr className="border-b border-slate-100">
                  <th className="text-left text-xs font-medium text-slate-400 uppercase tracking-wider px-6 py-4">
                    Name
                  </th>
                  <th className="text-left text-xs font-medium text-slate-400 uppercase tracking-wider px-6 py-4 w-32">
                    Type
                  </th>
                  <th className="text-left text-xs font-medium text-slate-400 uppercase tracking-wider px-6 py-4 w-32">
                    Rate
                  </th>
                  <th className="text-center text-xs font-medium text-slate-400 uppercase tracking-wider px-6 py-4 w-24">
                    Status
                  </th>
                  <th className="text-right text-xs font-medium text-slate-400 uppercase tracking-wider px-6 py-4 w-28">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody>
                {taxes.map((t) => (
                  <tr
                    key={t.id}
                    className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors group/row"
                  >
                    <td className="px-6 py-4">
                      <span className={`text-sm font-medium ${t.enabled ? "text-slate-800" : "text-slate-400"}`}>
                        {t.name}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <span className="text-xs px-2 py-1 rounded-full font-medium bg-slate-100 text-slate-500">
                        {t.type}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <span className="text-sm text-slate-600 font-medium">
                        {t.type === "PERCENTAGE" ? `${t.amount}%` : `$${t.amount.toFixed(2)}`}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-center">
                      <button
                        onClick={() => handleToggle(t)}
                        className="inline-flex items-center"
                        title={t.enabled ? "Disable" : "Enable"}
                      >
                        {t.enabled ? (
                          <ToggleRight size={28} className="text-emerald-500" />
                        ) : (
                          <ToggleLeft size={28} className="text-slate-300" />
                        )}
                      </button>
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="flex items-center justify-end gap-1 opacity-0 group-hover/row:opacity-100 transition-opacity">
                        <button
                          onClick={() => openEdit(t)}
                          className="p-1.5 rounded-lg text-slate-400 hover:bg-blue-50 hover:text-blue-600 transition-colors"
                          title="Edit tax"
                        >
                          <Pencil size={15} />
                        </button>
                        <button
                          onClick={() => setDeleteTarget(t)}
                          className="p-1.5 rounded-lg text-slate-400 hover:bg-red-50 hover:text-red-500 transition-colors"
                          title="Delete tax"
                        >
                          <Trash2 size={15} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* ── Add/Edit Modal ── */}
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
                  {editing ? "Edit Tax" : "Add Tax"}
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
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Tax Name
                  </label>
                  <input
                    type="text"
                    value={taxName}
                    onChange={(e) => setTaxName(e.target.value)}
                    placeholder="e.g. Sales Tax, State Tax"
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Type
                  </label>
                  <select
                    value={taxType}
                    onChange={(e) => setTaxType(e.target.value as "PERCENTAGE" | "FIXED")}
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white"
                  >
                    <option value="PERCENTAGE">Percentage</option>
                    <option value="FIXED">Fixed Amount</option>
                  </select>
                </div>

                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    {taxType === "PERCENTAGE" ? "Rate (%)" : "Amount ($)"}
                  </label>
                  <input
                    type="number"
                    min="0"
                    step={taxType === "PERCENTAGE" ? "0.01" : "0.01"}
                    value={taxAmount}
                    onChange={(e) => setTaxAmount(e.target.value)}
                    placeholder={taxType === "PERCENTAGE" ? "e.g. 8.25" : "e.g. 1.50"}
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
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
                  disabled={saving || !taxName.trim() || !taxAmount}
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
                    "Add Tax"
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
                  Delete Tax
                </h3>
              </div>
              <p className="text-sm text-slate-500 text-center">
                Are you sure you want to delete{" "}
                <strong className="text-slate-700">{deleteTarget.name}</strong>?
                Items referencing this tax will need to be updated.
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
