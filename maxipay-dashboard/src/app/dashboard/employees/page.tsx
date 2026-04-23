"use client";

import { useEffect, useMemo, useState } from "react";
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
  deleteField,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import {
  UserPlus,
  Trash2,
  X,
  Shield,
  ShieldCheck,
  User,
  MoreVertical,
  Mail,
  Phone,
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
} from "lucide-react";

interface Employee {
  id: string;
  name: string;
  role: string;
  pin: string;
  active: boolean;
  phone?: string;
  email?: string;
}

function getInitials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length >= 2) {
    return (parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
  }
  const single = parts[0] || "?";
  return single.slice(0, Math.min(2, single.length)).toUpperCase();
}

function dashIfEmpty(value: string | undefined): string {
  const t = value?.trim();
  return t ? t : "—";
}

function isValidEmail(value: string): boolean {
  const t = value.trim();
  if (!t) return true;
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(t);
}

function roleBadgeClass(role: string) {
  return role === "ADMINISTRATOR"
    ? "bg-slate-200 text-slate-800"
    : "bg-slate-100 text-slate-600";
}

/** Lower sorts before higher when sorting ascending by role. */
function roleSortKey(role: string): number {
  if (role === "ADMINISTRATOR") return 0;
  if (role === "EMPLOYEE") return 1;
  return 2;
}

function EmployeeRowMenu({
  emp,
  menuOpen,
  onToggle,
  onEdit,
  onDelete,
  wrapperClassName,
}: {
  emp: Employee;
  menuOpen: boolean;
  onToggle: () => void;
  onEdit: () => void;
  onDelete: () => void;
  wrapperClassName: string;
}) {
  return (
    <div
      className={wrapperClassName}
      data-employee-menu
      onClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => e.stopPropagation()}
    >
      <button
        type="button"
        aria-label={`Actions for ${emp.name}`}
        aria-haspopup="menu"
        aria-expanded={menuOpen}
        onClick={onToggle}
        className="p-2 rounded-lg text-slate-500 hover:bg-slate-100 hover:text-slate-800 transition-colors"
      >
        <MoreVertical size={18} />
      </button>
      {menuOpen && (
        <div
          role="menu"
          className="absolute right-0 top-full mt-1 z-20 min-w-[140px] rounded-lg border border-slate-200 bg-white py-1 shadow-lg"
        >
          <button
            type="button"
            role="menuitem"
            className="w-full px-3 py-2 text-left text-sm text-slate-700 hover:bg-slate-50"
            onClick={onEdit}
          >
            Edit
          </button>
          <button
            type="button"
            role="menuitem"
            className="w-full px-3 py-2 text-left text-sm text-red-600 hover:bg-red-50"
            onClick={onDelete}
          >
            Delete
          </button>
        </div>
      )}
    </div>
  );
}

const ROLES = ["EMPLOYEE", "ADMINISTRATOR"] as const;

export default function EmployeesPage() {
  const { user } = useAuth();
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [loading, setLoading] = useState(true);

  const [modalOpen, setModalOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Employee | null>(null);
  const [formName, setFormName] = useState("");
  const [formEmail, setFormEmail] = useState("");
  const [formPhone, setFormPhone] = useState("");
  const [formPin, setFormPin] = useState("");
  const [formRole, setFormRole] = useState<string>("EMPLOYEE");
  const [saving, setSaving] = useState(false);
  const [pinError, setPinError] = useState("");
  const [emailError, setEmailError] = useState("");

  const [deleteTarget, setDeleteTarget] = useState<Employee | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  /** null = sort by name; asc = Admin → Employee; desc = Employee → Admin */
  const [roleSort, setRoleSort] = useState<null | "asc" | "desc">(null);

  useEffect(() => {
    if (!openMenuId) return;
    const onDocMouseDown = (e: MouseEvent) => {
      const el = e.target as HTMLElement | null;
      if (!el?.closest("[data-employee-menu]")) setOpenMenuId(null);
    };
    document.addEventListener("mousedown", onDocMouseDown);
    return () => document.removeEventListener("mousedown", onDocMouseDown);
  }, [openMenuId]);

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
            phone: typeof data.phone === "string" ? data.phone : undefined,
            email: typeof data.email === "string" ? data.email : undefined,
          });
        });
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

  const sortedEmployees = useMemo(() => {
    const list = [...employees];
    if (roleSort === null) {
      list.sort((a, b) => a.name.localeCompare(b.name));
      return list;
    }
    list.sort((a, b) => {
      const cmp = roleSortKey(a.role) - roleSortKey(b.role);
      if (cmp !== 0) return roleSort === "asc" ? cmp : -cmp;
      return a.name.localeCompare(b.name);
    });
    return list;
  }, [employees, roleSort]);

  const cycleRoleSort = () => {
    setRoleSort((prev) =>
      prev === null ? "asc" : prev === "asc" ? "desc" : null
    );
  };

  const roleSortLabel =
    roleSort === null
      ? "Name A–Z"
      : roleSort === "asc"
        ? "Role · Admin first"
        : "Role · Employee first";

  const openAdd = () => {
    setEditTarget(null);
    setFormName("");
    setFormEmail("");
    setFormPhone("");
    setFormPin("");
    setFormRole("EMPLOYEE");
    setPinError("");
    setEmailError("");
    setModalOpen(true);
  };

  const openEdit = (emp: Employee) => {
    setEditTarget(emp);
    setFormName(emp.name);
    setFormEmail(emp.email?.trim() ?? "");
    setFormPhone(emp.phone?.trim() ?? "");
    setFormPin(emp.pin);
    setFormRole(emp.role);
    setPinError("");
    setEmailError("");
    setModalOpen(true);
  };

  const closeModal = () => {
    setModalOpen(false);
    setEditTarget(null);
    setEmailError("");
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
    const trimEmail = formEmail.trim();
    const trimPhone = formPhone.trim();
    if (!trimName || !trimPin) return;

    if (!isValidEmail(trimEmail)) {
      setEmailError("Enter a valid email address or leave blank");
      return;
    }
    setEmailError("");

    const valid = await validatePin(trimPin, editTarget?.id);
    if (!valid) return;

    setSaving(true);
    try {
      if (editTarget) {
        await updateDoc(doc(db, "Employees", editTarget.id), {
          name: trimName,
          pin: trimPin,
          role: formRole,
          email: trimEmail ? trimEmail : deleteField(),
          phone: trimPhone ? trimPhone : deleteField(),
        });
      } else {
        await addDoc(collection(db, "Employees"), {
          name: trimName,
          pin: trimPin,
          role: formRole,
          active: true,
          ...(trimEmail ? { email: trimEmail } : {}),
          ...(trimPhone ? { phone: trimPhone } : {}),
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

        {!loading && employees.length > 0 && (
          <div className="flex md:hidden items-center gap-2 text-sm text-slate-600">
            <span className="text-slate-500">Sort</span>
            <button
              type="button"
              onClick={cycleRoleSort}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white font-medium text-slate-700 hover:bg-slate-50 transition-colors"
            >
              {roleSort === null && (
                <ArrowUpDown size={14} className="text-slate-400" aria-hidden />
              )}
              {roleSort === "asc" && (
                <ArrowUp size={14} className="text-blue-600" aria-hidden />
              )}
              {roleSort === "desc" && (
                <ArrowDown size={14} className="text-blue-600" aria-hidden />
              )}
              {roleSortLabel}
            </button>
          </div>
        )}

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
          <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
            {/* column headers — hidden on xs, shown md+ for table alignment */}
            <div className="hidden md:grid md:grid-cols-[40px_minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1.3fr)_auto_44px] md:gap-3 md:items-center px-4 py-2.5 bg-slate-50 border-b border-slate-200 text-xs font-semibold uppercase tracking-wide text-slate-500">
              <span />
              <span>Name</span>
              <span>Phone</span>
              <span>Email</span>
              <div className="flex justify-end min-w-0 pr-1">
                <button
                  type="button"
                  onClick={cycleRoleSort}
                  className="inline-flex items-center gap-1 rounded-md px-2 py-1 -my-1 text-right uppercase tracking-wide text-slate-500 hover:text-slate-900 hover:bg-slate-200/80 transition-colors"
                  aria-label={
                    roleSort === null
                      ? "Sort by role: administrators first"
                      : roleSort === "asc"
                        ? "Sort by role: employees first"
                        : "Sort by name A to Z"
                  }
                >
                  Role
                  {roleSort === null && (
                    <ArrowUpDown size={14} className="opacity-60 shrink-0" />
                  )}
                  {roleSort === "asc" && (
                    <ArrowUp size={14} className="text-blue-600 shrink-0" />
                  )}
                  {roleSort === "desc" && (
                    <ArrowDown size={14} className="text-blue-600 shrink-0" />
                  )}
                </button>
              </div>
              <span />
            </div>
            <ul className="divide-y divide-slate-100" role="list">
              {sortedEmployees.map((emp) => {
                const phoneDisplay = dashIfEmpty(emp.phone);
                const emailDisplay = dashIfEmpty(emp.email);
                const menuOpen = openMenuId === emp.id;
                return (
                  <li key={emp.id}>
                    <div
                      tabIndex={0}
                      onClick={() => {
                        setOpenMenuId(null);
                        openEdit(emp);
                      }}
                      onKeyDown={(e) => {
                        if (e.key === "Enter" || e.key === " ") {
                          e.preventDefault();
                          setOpenMenuId(null);
                          openEdit(emp);
                        }
                      }}
                      className="w-full text-left cursor-pointer hover:bg-slate-50/90 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-blue-500/30"
                    >
                      {/* Mobile: stacked scan-friendly block */}
                      <div className="md:hidden px-4 py-3 space-y-2">
                        <div className="flex items-center gap-3">
                          <div
                            className="w-10 h-10 rounded-full bg-blue-100 flex items-center justify-center flex-shrink-0 text-blue-700 text-xs font-bold"
                            aria-hidden
                          >
                            {getInitials(emp.name)}
                          </div>
                          <div className="min-w-0 flex-1">
                            <p className="font-bold text-slate-900 truncate">
                              {emp.name}
                            </p>
                            <p className="text-sm text-slate-500 truncate">
                              {emailDisplay}
                            </p>
                          </div>
                          <EmployeeRowMenu
                            emp={emp}
                            menuOpen={menuOpen}
                            onToggle={() =>
                              setOpenMenuId(menuOpen ? null : emp.id)
                            }
                            onEdit={() => {
                              setOpenMenuId(null);
                              openEdit(emp);
                            }}
                            onDelete={() => {
                              setOpenMenuId(null);
                              setDeleteTarget(emp);
                            }}
                            wrapperClassName="relative flex-shrink-0"
                          />
                        </div>
                        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 pl-[52px] text-sm">
                          <span className="text-slate-600">{phoneDisplay}</span>
                          <span
                            className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ${roleBadgeClass(emp.role)}`}
                          >
                            {emp.role === "ADMINISTRATOR" ? "Admin" : "Employee"}
                          </span>
                        </div>
                      </div>

                      {/* Desktop: single aligned row */}
                      <div className="hidden md:grid md:grid-cols-[40px_minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1.3fr)_auto_44px] md:gap-3 md:items-center px-4 py-3">
                        <div
                          className="w-10 h-10 rounded-full bg-blue-100 flex items-center justify-center text-blue-700 text-xs font-bold"
                          aria-hidden
                        >
                          {getInitials(emp.name)}
                        </div>
                        <span className="font-bold text-slate-900 truncate min-w-0">
                          {emp.name}
                        </span>
                        <span className="text-sm text-slate-600 truncate min-w-0">
                          {phoneDisplay}
                        </span>
                        <span className="text-sm text-slate-500 truncate min-w-0">
                          {emailDisplay}
                        </span>
                        <span className="flex justify-end min-w-0">
                          <span
                            className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ${roleBadgeClass(emp.role)}`}
                          >
                            {emp.role === "ADMINISTRATOR"
                              ? "Admin"
                              : "Employee"}
                          </span>
                        </span>
                        <EmployeeRowMenu
                          emp={emp}
                          menuOpen={menuOpen}
                          onToggle={() =>
                            setOpenMenuId(menuOpen ? null : emp.id)
                          }
                          onEdit={() => {
                            setOpenMenuId(null);
                            openEdit(emp);
                          }}
                          onDelete={() => {
                            setOpenMenuId(null);
                            setDeleteTarget(emp);
                          }}
                          wrapperClassName="relative flex justify-end flex-shrink-0"
                        />
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          </div>
        )}
      </div>

      {/* ── Add / Edit Modal ── */}
      {modalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-lg mx-4 overflow-hidden max-h-[90vh] flex flex-col">
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
            <div className="px-6 py-5 space-y-4 overflow-y-auto flex-1 min-h-0">
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
                  Email
                </label>
                <div className="relative">
                  <Mail
                    className="absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none"
                    size={18}
                    aria-hidden
                  />
                  <input
                    type="email"
                    autoComplete="email"
                    value={formEmail}
                    onChange={(e) => {
                      setFormEmail(e.target.value);
                      if (emailError) setEmailError("");
                    }}
                    placeholder="name@example.com"
                    className={`w-full pl-11 pr-4 py-2.5 rounded-xl border outline-none text-slate-800 placeholder:text-slate-400 ${
                      emailError
                        ? "border-red-300 focus:border-red-500 focus:ring-2 focus:ring-red-500/20"
                        : "border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20"
                    }`}
                  />
                </div>
                {emailError ? (
                  <p className="text-red-500 text-xs mt-1.5">{emailError}</p>
                ) : (
                  <p className="text-slate-400 text-xs mt-1.5">Optional</p>
                )}
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Phone
                </label>
                <div className="relative">
                  <Phone
                    className="absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none"
                    size={18}
                    aria-hidden
                  />
                  <input
                    type="tel"
                    autoComplete="tel"
                    value={formPhone}
                    onChange={(e) => setFormPhone(e.target.value)}
                    placeholder="(555) 123-4567"
                    className="w-full pl-11 pr-4 py-2.5 rounded-xl border border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none text-slate-800 placeholder:text-slate-400"
                  />
                </div>
                <p className="text-slate-400 text-xs mt-1.5">Optional</p>
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
