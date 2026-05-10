"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import { auth } from "@/firebase/firebaseConfig";
import {
  ArrowLeft,
  Loader2,
  KeyRound,
  Users,
  Eye,
  EyeOff,
} from "lucide-react";

type EmployeeRow = {
  kind: "owner" | "employee";
  id: string | null;
  name: string;
  email: string;
  passwordStatus: string;
  passwordStatusLabel: string;
  pin: string | null;
  role: string | null;
  active: boolean | null;
  authUid: string | null;
};

async function apiCall(path: string, options?: RequestInit) {
  const user = auth.currentUser;
  if (!user) throw new Error("Not signed in");
  const token = await user.getIdToken();
  const res = await fetch(path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      ...(options?.headers || {}),
    },
  });
  const data = await res.json();
  if (!res.ok || !data.ok) throw new Error(data.message || data.error || "Request failed");
  return data;
}

export default function MerchantEmployeesPage() {
  const params = useParams();
  const merchantId = params.id as string;

  const [rows, setRows] = useState<EmployeeRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<{ type: "success" | "error"; msg: string } | null>(null);

  const [pwdTarget, setPwdTarget] = useState<{
    kind: "owner" | "employee";
    employeeId: string | null;
    label: string;
  } | null>(null);
  const [newPwd, setNewPwd] = useState("");
  const [confirmPwd, setConfirmPwd] = useState("");
  const [showPwd, setShowPwd] = useState(false);
  const [savingPwd, setSavingPwd] = useState(false);

  const showToast = useCallback((type: "success" | "error", msg: string) => {
    setToast({ type, msg });
    setTimeout(() => setToast(null), 4000);
  }, []);

  const load = useCallback(async () => {
    setError(null);
    setLoading(true);
    try {
      const data = await apiCall(`/api/merchants/${merchantId}/employees`);
      setRows((data.rows || []) as EmployeeRow[]);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load employees.");
    } finally {
      setLoading(false);
    }
  }, [merchantId]);

  useEffect(() => {
    load();
  }, [load]);

  const openSetPassword = (row: EmployeeRow) => {
    setPwdTarget({
      kind: row.kind,
      employeeId: row.id,
      label: row.kind === "owner" ? `Owner · ${row.name}` : `${row.name} (${row.email || "no email"})`,
    });
    setNewPwd("");
    setConfirmPwd("");
    setShowPwd(false);
  };

  const submitSetPassword = async () => {
    if (!pwdTarget) return;
    const t = newPwd.trim();
    if (t.length < 6) {
      showToast("error", "Password must be at least 6 characters.");
      return;
    }
    if (t !== confirmPwd.trim()) {
      showToast("error", "Passwords do not match.");
      return;
    }
    setSavingPwd(true);
    try {
      await apiCall(`/api/merchants/${merchantId}/employees/set-password`, {
        method: "POST",
        body: JSON.stringify({
          target: pwdTarget.kind,
          ...(pwdTarget.kind === "employee" && pwdTarget.employeeId
            ? { employeeId: pwdTarget.employeeId }
            : {}),
          newPassword: t,
        }),
      });
      showToast("success", "Password updated. They can sign in with the new password.");
      setPwdTarget(null);
      await load();
    } catch (e) {
      showToast("error", e instanceof Error ? e.message : "Failed to set password.");
    } finally {
      setSavingPwd(false);
    }
  };

  return (
    <div className="p-8 max-w-5xl mx-auto">
      {toast && (
        <div
          className={`fixed top-6 right-6 z-50 px-5 py-3 rounded-xl text-sm font-medium shadow-lg ${
            toast.type === "success" ? "bg-green-600 text-white" : "bg-red-600 text-white"
          }`}
        >
          {toast.msg}
        </div>
      )}

      <Link
        href={`/merchants/${merchantId}`}
        className="inline-flex items-center gap-1.5 text-sm text-slate-500 hover:text-slate-700 mb-6"
      >
        <ArrowLeft size={16} />
        Back to merchant
      </Link>

      <div className="flex items-center gap-4 mb-8">
        <div className="w-12 h-12 rounded-2xl bg-blue-50 flex items-center justify-center">
          <Users size={24} className="text-blue-600" />
        </div>
        <div>
          <h1 className="text-2xl font-bold text-slate-900">Employees information</h1>
          <p className="text-slate-500 text-sm">
            Owner and staff for this merchant. Dashboard password status is from Firebase Auth (we
            never show the actual password). POS PIN is stored for Android login.
          </p>
        </div>
      </div>

      {error && (
        <div className="mb-6 px-4 py-3 bg-red-50 border border-red-200 text-red-700 text-sm rounded-xl">
          {error}
        </div>
      )}

      {loading ? (
        <div className="flex justify-center py-20">
          <Loader2 className="w-10 h-10 text-blue-600 animate-spin" />
        </div>
      ) : (
        <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
          <div className="hidden md:grid md:grid-cols-[100px_minmax(0,1fr)_minmax(0,1.2fr)_minmax(0,1.2fr)_100px_140px] gap-3 px-4 py-3 bg-slate-50 border-b border-slate-200 text-xs font-semibold uppercase tracking-wide text-slate-500">
            <span>Role</span>
            <span>Name</span>
            <span>Email</span>
            <span>Password</span>
            <span>PIN</span>
            <span className="text-right">Actions</span>
          </div>
          <ul className="divide-y divide-slate-100">
            {rows.map((row, idx) => (
              <li key={row.kind === "owner" ? "owner" : row.id ?? `emp-${idx}`}>
                <div className="px-4 py-4 md:grid md:grid-cols-[100px_minmax(0,1fr)_minmax(0,1.2fr)_minmax(0,1.2fr)_100px_140px] md:gap-3 md:items-center space-y-2 md:space-y-0">
                  <div className="flex flex-wrap gap-2 items-center">
                    <span
                      className={`text-xs font-semibold rounded-full px-2.5 py-0.5 ${
                        row.kind === "owner"
                          ? "bg-blue-100 text-blue-800"
                          : "bg-slate-100 text-slate-700"
                      }`}
                    >
                      {row.kind === "owner" ? "Owner" : row.role || "Staff"}
                    </span>
                  </div>
                  <p className="font-medium text-slate-900 break-words">{row.name}</p>
                  <p className="text-sm text-slate-600 break-all">{row.email || "—"}</p>
                  <p className="text-sm text-slate-700">{row.passwordStatusLabel}</p>
                  <p className="text-sm font-mono text-slate-800">{row.pin ?? "—"}</p>
                  <div className="md:text-right">
                    <button
                      type="button"
                      onClick={() => openSetPassword(row)}
                      disabled={
                        row.kind === "employee" && !(row.email && row.email.includes("@"))
                      }
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-blue-700 bg-blue-50 border border-blue-200 rounded-lg hover:bg-blue-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                    >
                      <KeyRound size={14} />
                      Set password
                    </button>
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}

      {pwdTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-md overflow-hidden">
            <div className="px-6 py-4 border-b border-slate-100">
              <h2 className="text-lg font-semibold text-slate-800">Set dashboard password</h2>
              <p className="text-sm text-slate-500 mt-1">{pwdTarget.label}</p>
            </div>
            <div className="px-6 py-4 space-y-4">
              <p className="text-xs text-amber-800 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
                This updates their <strong>Firebase email/password</strong> for the merchant
                dashboard. Share the new password securely; they should change it after logging in.
              </p>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">New password</label>
                <div className="relative">
                  <input
                    type={showPwd ? "text" : "password"}
                    value={newPwd}
                    onChange={(e) => setNewPwd(e.target.value)}
                    className="w-full px-3 py-2 pr-10 border border-slate-200 rounded-xl text-sm"
                    autoComplete="new-password"
                    minLength={6}
                  />
                  <button
                    type="button"
                    className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-slate-400 hover:text-slate-700"
                    onClick={() => setShowPwd(!showPwd)}
                    aria-label={showPwd ? "Hide password" : "Show password"}
                  >
                    {showPwd ? <EyeOff size={18} /> : <Eye size={18} />}
                  </button>
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1">
                  Confirm password
                </label>
                <input
                  type={showPwd ? "text" : "password"}
                  value={confirmPwd}
                  onChange={(e) => setConfirmPwd(e.target.value)}
                  className="w-full px-3 py-2 border border-slate-200 rounded-xl text-sm"
                  autoComplete="new-password"
                  minLength={6}
                />
              </div>
            </div>
            <div className="flex justify-end gap-2 px-6 py-4 border-t border-slate-100 bg-slate-50">
              <button
                type="button"
                onClick={() => setPwdTarget(null)}
                className="px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-200 rounded-lg"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={savingPwd || newPwd.trim().length < 6}
                onClick={submitSetPassword}
                className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 inline-flex items-center gap-2"
              >
                {savingPwd && <Loader2 size={16} className="animate-spin" />}
                Save password
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
