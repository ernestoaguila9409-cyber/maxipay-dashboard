"use client";

import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import {
  collection,
  addDoc,
  updateDoc,
  deleteDoc,
  doc,
  onSnapshot,
  query,
  where,
  orderBy,
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
  ArrowLeft,
  GripVertical,
} from "lucide-react";

interface Subcategory {
  id: string;
  name: string;
  categoryId: string;
  order: number;
}

export default function SubcategoriesPage() {
  const { user } = useAuth();
  const searchParams = useSearchParams();
  const categoryId = searchParams.get("categoryId");

  const [categoryName, setCategoryName] = useState("");
  const [subcategories, setSubcategories] = useState<Subcategory[]>([]);
  const [loading, setLoading] = useState(true);

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Subcategory | null>(null);
  const [subName, setSubName] = useState("");
  const [saving, setSaving] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<Subcategory | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    if (!user || !categoryId) return;
    const unsub = onSnapshot(doc(db, "Categories", categoryId), (snap) => {
      if (snap.exists()) {
        setCategoryName(snap.data().name ?? "");
      }
    });
    return () => unsub();
  }, [user, categoryId]);

  useEffect(() => {
    if (!user || !categoryId) return;
    const q = query(
      collection(db, "subcategories"),
      where("categoryId", "==", categoryId),
      orderBy("order", "asc")
    );
    const unsub = onSnapshot(q, (snap) => {
      const list: Subcategory[] = [];
      snap.forEach((d) => {
        const data = d.data();
        list.push({
          id: d.id,
          name: data.name ?? "",
          categoryId: data.categoryId ?? "",
          order: data.order ?? 0,
        });
      });
      setSubcategories(list);
      setLoading(false);
    });
    return () => unsub();
  }, [user, categoryId]);

  const openAdd = () => {
    setEditing(null);
    setSubName("");
    setModalOpen(true);
  };

  const openEdit = (sub: Subcategory) => {
    setEditing(sub);
    setSubName(sub.name);
    setModalOpen(true);
  };

  const handleSave = async () => {
    const name = subName.trim();
    if (!name || !categoryId) return;
    setSaving(true);
    try {
      if (editing) {
        await updateDoc(doc(db, "subcategories", editing.id), {
          name,
          updatedAt: serverTimestamp(),
        });
      } else {
        const nextOrder = subcategories.length > 0
          ? Math.max(...subcategories.map((s) => s.order)) + 1
          : 0;
        await addDoc(collection(db, "subcategories"), {
          name,
          categoryId,
          order: nextOrder,
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
      }
      setModalOpen(false);
    } catch (err) {
      console.error("Failed to save subcategory:", err);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteDoc(doc(db, "subcategories", deleteTarget.id));
      setDeleteTarget(null);
    } catch (err) {
      console.error("Failed to delete subcategory:", err);
    } finally {
      setDeleting(false);
    }
  };

  if (!categoryId) {
    return (
      <>
        <Header title="Subcategories" />
        <div className="px-5 pt-10 pb-6 text-center">
          <AlertTriangle size={40} className="mx-auto text-amber-400 mb-3" />
          <h2 className="text-lg font-semibold text-slate-700 mb-1">No category selected</h2>
          <p className="text-sm text-slate-500 mb-4">
            Please select a category from the menu page to manage its subcategories.
          </p>
          <Link
            href="/dashboard/menu"
            className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
          >
            <ArrowLeft size={16} />
            Back to Menu
          </Link>
        </div>
      </>
    );
  }

  return (
    <>
      <Header title="Subcategories" />
      <div className="px-5 pt-3 pb-6 space-y-4">
        {/* Back link + category name */}
        <div className="flex items-center gap-3">
          <Link
            href="/dashboard/menu"
            className="p-2 rounded-xl hover:bg-slate-100 transition-colors text-slate-500"
          >
            <ArrowLeft size={18} />
          </Link>
          <div>
            <h2 className="text-lg font-semibold text-slate-800">
              {categoryName || "Loading…"}
            </h2>
            <p className="text-xs text-slate-400">Manage subcategories</p>
          </div>
        </div>

        {/* Toolbar */}
        <div className="flex items-center justify-between">
          <p className="text-sm text-slate-500">
            {loading ? "Loading…" : `${subcategories.length} subcategor${subcategories.length === 1 ? "y" : "ies"}`}
          </p>
          <button
            onClick={openAdd}
            className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors shadow-sm"
          >
            <Plus size={16} />
            Add Subcategory
          </button>
        </div>

        {/* Subcategories list */}
        {loading ? (
          <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-8 text-center">
            <div className="w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin mx-auto" />
            <p className="text-sm text-slate-400 mt-3">Loading subcategories…</p>
          </div>
        ) : subcategories.length === 0 ? (
          <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-8 text-center">
            <p className="text-sm text-slate-500">No subcategories yet.</p>
            <p className="text-xs text-slate-400 mt-1">Click &quot;Add Subcategory&quot; to create one.</p>
          </div>
        ) : (
          <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden divide-y divide-slate-100">
            {subcategories.map((sub, idx) => (
              <div
                key={sub.id}
                className="group flex items-center gap-3 px-4 py-3 hover:bg-slate-50 transition-colors"
              >
                <GripVertical size={14} className="text-slate-300 shrink-0" />
                <span className="text-xs text-slate-400 font-medium tabular-nums w-6 shrink-0">{idx + 1}</span>
                <span className="flex-1 text-sm font-medium text-slate-700 truncate">{sub.name}</span>
                <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                  <button
                    onClick={() => openEdit(sub)}
                    className="p-1.5 rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-200/60 transition-colors"
                    title="Edit subcategory"
                  >
                    <Pencil size={14} />
                  </button>
                  <button
                    onClick={() => setDeleteTarget(sub)}
                    className="p-1.5 rounded-lg text-slate-400 hover:text-red-500 hover:bg-red-50 transition-colors"
                    title="Delete subcategory"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
            ))}
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
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  {editing ? "Edit Subcategory" : "Add Subcategory"}
                </h3>
                <button
                  onClick={() => !saving && setModalOpen(false)}
                  disabled={saving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Name
                </label>
                <input
                  type="text"
                  value={subName}
                  onChange={(e) => setSubName(e.target.value)}
                  placeholder="e.g. Soda, Burgers, Appetizers"
                  autoFocus
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && subName.trim()) handleSave();
                  }}
                  className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                />
              </div>
            </div>

            <div className="px-6 py-4 bg-slate-50 border-t border-slate-100 flex gap-3">
              <button
                onClick={() => setModalOpen(false)}
                disabled={saving}
                className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                onClick={handleSave}
                disabled={saving || !subName.trim()}
                className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
              >
                {saving ? (
                  <>
                    <span className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                    Saving…
                  </>
                ) : editing ? (
                  "Save Changes"
                ) : (
                  "Add Subcategory"
                )}
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
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-full bg-red-100 flex items-center justify-center shrink-0">
                  <AlertTriangle size={20} className="text-red-500" />
                </div>
                <div>
                  <h3 className="text-lg font-semibold text-slate-800">
                    Delete Subcategory
                  </h3>
                  <p className="text-sm text-slate-500 mt-0.5">
                    Are you sure you want to delete <strong>{deleteTarget.name}</strong>?
                  </p>
                </div>
              </div>
            </div>
            <div className="px-6 py-4 bg-slate-50 border-t border-slate-100 flex gap-3">
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
                    <span className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                    Deleting…
                  </>
                ) : (
                  "Delete"
                )}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
