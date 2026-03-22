"use client";

import { useEffect, useState } from "react";
import {
  collection,
  onSnapshot,
  addDoc,
  updateDoc,
  deleteDoc,
  doc,
  query,
  where,
  getDocs,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import {
  UserPlus,
  Pencil,
  Trash2,
  X,
  Shield,
  ShieldCheck,
  KeyRound,
  User,
} from "lucide-react";

interface Employee {
  id: string;
  name: string;
  role: string;
  pin: string;
  active: boolean;
}

const ROLES = ["EMPLOYEE", "ADMINISTRATOR"] as const;

export default function EmployeesPage() {
  const { user } = useAuth();
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [loading, setLoading] = useState(true);

  const [modalOpen, setModalOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Employee | null>(null);
  const [formName, setFormName] = useState("");
  const [formPin, setFormPin] = useState("");
  const [formRole, setFormRole] = useState<string>("EMPLOYEE");
  const [saving, setSaving] = useState(false);
  const [pinError, setPinError] = useState("");

  const [deleteTarget, setDeleteTarget] = useState<Employee | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    if (!user) return;

    const unsub = onSnapshot(
      collection(db, "Employees"),
      (snap) => {
        const list: Employee[] = [];
        snap.forEach((d) => {
          const data = d.data();
          list.push({
            id: d.id,
            name: data.name || "Unknown",
            role: data.role || "EMPLOYEE",
            pin: data.pin || "",
            active: data.active !== false,
          });
        });
        list.sort((a, b) => a.name.localeCompare(b.name));
        setEmployees(list);
        setLoading(false);
      },
      (err) => {
        console.error("[Employees] onSnapshot error:", err);
        setLoading(false);
      }
    );

    return () => unsub();
  }, [user]);

  const openAdd = () => {
    setEditTarget(null);
    setFormName("");
    setFormPin("");
    setFormRole("EMPLOYEE");
    setPinError("");
    setModalOpen(true);
  };

  const openEdit = (emp: Employee) => {
    setEditTarget(emp);
    setFormName(emp.name);
    setFormPin(emp.pin);
    setFormRole(emp.role);
    setPinError("");
    setModalOpen(true);
  };

  const closeModal = () => {
    setModalOpen(false);
    setEditTarget(null);
  };

  const validatePin = async (pin: string, excludeId?: string): Promise<boolean> => {
    if (pin.length !== 4 || !/^\d{4}$/.test(pin)) {
      setPinError("PIN must be exactly 4 digits");
      return false;
    }
    const q = query(collection(db, "Employees"), where("pin", "==", pin));
    const snap = await getDocs(q);
    const conflict = snap.docs.some((d) => d.id !== excludeId);
    if (conflict) {
      setPinError("PIN already in use by another employee");
      return false;
    }
    setPinError("");
    return true;
  };

  const handleSave = async () => {
    const trimName = formName.trim();
    const trimPin = formPin.trim();
    if (!trimName || !trimPin) return;

    const valid = await validatePin(trimPin, editTarget?.id);
    if (!valid) return;

    setSaving(true);
    try {
      if (editTarget) {
        await updateDoc(doc(db, "Employees", editTarget.id), {
          name: trimName,
          pin: trimPin,
          role: formRole,
        });
      } else {
        await addDoc(collection(db, "Employees"), {
          name: trimName,
          pin: trimPin,
          role: formRole,
          active: true,
        });
      }
      closeModal();
    } catch (err) {
      console.error("Failed to save employee:", err);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteDoc(doc(db, "Employees", deleteTarget.id));
    } catch (err) {
      console.error("Failed to delete employee:", err);
    } finally {
      setDeleting(false);
      setDeleteTarget(null);
    }
  };

  return (
    <>
      <Header title="Employees" />
      <div className="p-6 space-y-6">
        <div className="flex items-center justify-between">
          <p className="text-slate-500 text-sm">
            {employees.length} employee{employees.length !== 1 ? "s" : ""}
          </p>
          <button
            onClick={openAdd}
            className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
          >
            <UserPlus size={16} />
            Add Employee
          </button>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          </div>
        ) : employees.length === 0 ? (
          <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-12 text-center">
            <div className="w-16 h-16 rounded-full bg-slate-100 flex items-center justify-center mx-auto mb-4">
              <User size={28} className="text-slate-400" />
            </div>
            <p className="text-slate-500 text-lg font-medium">No employees yet</p>
            <p className="text-slate-400 text-sm mt-1 mb-6">
              Add your first employee to get started
            </p>
            <button
              onClick={openAdd}
              className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
            >
              <UserPlus size={16} />
              Add Employee
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {employees.map((emp) => (
              <div
                key={emp.id}
                className="bg-white rounded-2xl p-5 shadow-sm border border-slate-100 hover:shadow-md transition-shadow group"
              >
                <div className="flex items-start gap-4">
                  <div className="w-12 h-12 rounded-full bg-blue-100 flex items-center justify-center flex-shrink-0">
                    <span className="text-blue-600 font-semibold text-lg">
                      {emp.name.charAt(0).toUpperCase()}
                    </span>
                  </div>
                  <div className="flex-1 min-w-0">
                    <h3 className="font-semibold text-slate-800 truncate">
                      {emp.name}
                    </h3>
                    <div className="flex items-center gap-1.5 mt-0.5">
                      {emp.role === "ADMINISTRATOR" ? (
                        <ShieldCheck size={14} className="text-amber-500" />
                      ) : (
                        <Shield size={14} className="text-slate-400" />
                      )}
                      <span className="text-sm text-slate-500">{emp.role}</span>
                    </div>
                    <div className="flex items-center gap-1.5 mt-1">
                      <KeyRound size={14} className="text-slate-400" />
                      <span className="text-sm text-slate-400 font-mono">
                        {"••••"}
                      </span>
                    </div>
                  </div>
                  <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                    <button
                      onClick={() => openEdit(emp)}
                      className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 hover:text-blue-600 transition-colors"
                      title="Edit"
                    >
                      <Pencil size={15} />
                    </button>
                    <button
                      onClick={() => setDeleteTarget(emp)}
                      className="p-1.5 rounded-lg hover:bg-red-50 text-slate-400 hover:text-red-600 transition-colors"
                      title="Delete"
                    >
                      <Trash2 size={15} />
                    </button>
                  </div>
                </div>
                <div className="mt-3 pt-3 border-t border-slate-100 flex items-center justify-between">
                  <span
                    className={`text-xs px-2.5 py-1 rounded-full font-medium ${
                      emp.active
                        ? "bg-emerald-50 text-emerald-700"
                        : "bg-slate-100 text-slate-500"
                    }`}
                  >
                    {emp.active ? "Active" : "Inactive"}
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ── Add / Edit Modal ── */}
      {modalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
            <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100">
              <h2 className="text-lg font-semibold text-slate-800">
                {editTarget ? "Edit Employee" : "Add Employee"}
              </h2>
              <button
                onClick={closeModal}
                className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 transition-colors"
              >
                <X size={18} />
              </button>
            </div>
            <div className="px-6 py-5 space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Name
                </label>
                <input
                  value={formName}
                  onChange={(e) => setFormName(e.target.value)}
                  placeholder="Employee name"
                  className="w-full px-4 py-2.5 rounded-xl border border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none text-slate-800 placeholder:text-slate-400"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  PIN (4 digits)
                </label>
                <input
                  value={formPin}
                  onChange={(e) => {
                    const v = e.target.value.replace(/\D/g, "").slice(0, 4);
                    setFormPin(v);
                    if (pinError) setPinError("");
                  }}
                  placeholder="0000"
                  maxLength={4}
                  inputMode="numeric"
                  className={`w-full px-4 py-2.5 rounded-xl border font-mono text-lg tracking-widest text-center outline-none ${
                    pinError
                      ? "border-red-300 focus:border-red-500 focus:ring-red-500/20"
                      : "border-slate-200 focus:border-blue-500 focus:ring-blue-500/20"
                  } focus:ring-2 text-slate-800 placeholder:text-slate-300`}
                />
                {pinError && (
                  <p className="text-red-500 text-xs mt-1.5">{pinError}</p>
                )}
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Role
                </label>
                <div className="grid grid-cols-2 gap-3">
                  {ROLES.map((r) => (
                    <button
                      key={r}
                      type="button"
                      onClick={() => setFormRole(r)}
                      className={`flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl border text-sm font-medium transition-all ${
                        formRole === r
                          ? "border-blue-500 bg-blue-50 text-blue-700"
                          : "border-slate-200 text-slate-600 hover:bg-slate-50"
                      }`}
                    >
                      {r === "ADMINISTRATOR" ? (
                        <ShieldCheck size={16} />
                      ) : (
                        <Shield size={16} />
                      )}
                      {r === "ADMINISTRATOR" ? "Admin" : "Employee"}
                    </button>
                  ))}
                </div>
              </div>
            </div>
            <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-slate-100 bg-slate-50">
              <button
                onClick={closeModal}
                className="px-4 py-2 rounded-xl text-sm font-medium text-slate-600 hover:bg-slate-200 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleSave}
                disabled={saving || !formName.trim() || !formPin.trim()}
                className="px-5 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:bg-blue-300 transition-colors flex items-center gap-2"
              >
                {saving && (
                  <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                )}
                {editTarget ? "Update" : "Add Employee"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Delete Confirmation ── */}
      {deleteTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 p-6 text-center">
            <div className="w-12 h-12 rounded-full bg-red-100 flex items-center justify-center mx-auto mb-4">
              <Trash2 size={22} className="text-red-600" />
            </div>
            <h3 className="text-lg font-semibold text-slate-800 mb-1">
              Delete Employee
            </h3>
            <p className="text-sm text-slate-500 mb-6">
              Are you sure you want to delete{" "}
              <span className="font-medium text-slate-700">
                {deleteTarget.name}
              </span>
              ? This action cannot be undone.
            </p>
            <div className="flex items-center justify-center gap-3">
              <button
                onClick={() => setDeleteTarget(null)}
                className="px-4 py-2 rounded-xl text-sm font-medium text-slate-600 hover:bg-slate-100 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleDelete}
                disabled={deleting}
                className="px-5 py-2 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 disabled:bg-red-300 transition-colors flex items-center gap-2"
              >
                {deleting && (
                  <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                )}
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
