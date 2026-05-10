"use client";

import { useEffect, useState, useCallback } from "react";
import { useParams, useRouter } from "next/navigation";
import { auth } from "@/firebase/firebaseConfig";
import Link from "next/link";
import {
  ArrowLeft,
  Store,
  Loader2,
  Save,
  CreditCard,
  Plus,
  Trash2,
  Power,
  RotateCw,
  Eye,
  EyeOff,
  X,
} from "lucide-react";

type ProviderType = "SPIN_Z" | "SPIN_P";

const PROVIDER_OPTIONS: { id: ProviderType; label: string }[] = [
  { id: "SPIN_Z", label: "SPIn Z-series (Z8, QD3, QD4)" },
  { id: "SPIN_P", label: "SPIn P-series (P17, P20)" },
];

interface Address {
  street: string;
  city: string;
  state: string;
  zip: string;
}

interface MerchantData {
  id: string;
  merchantNumber: string;
  businessName: string;
  ownerFirstName: string;
  ownerLastName: string;
  email: string;
  phone: string;
  address: Address;
  status: "active" | "suspended" | "pending";
}

interface TerminalConfig {
  tpn: string;
  registerId: string;
  authKey: string;
  iposTransactAuthToken?: string;
}

interface Terminal {
  id: string;
  name: string;
  provider: string;
  deviceModel: string;
  active: boolean;
  config: TerminalConfig;
}

const statusOptions = [
  { value: "active", label: "Active", className: "bg-green-100 text-green-700" },
  { value: "suspended", label: "Suspended", className: "bg-red-100 text-red-700" },
  { value: "pending", label: "Pending", className: "bg-amber-100 text-amber-700" },
];

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

export default function MerchantDetailPage() {
  const params = useParams();
  const router = useRouter();
  const merchantId = params.id as string;

  const [merchant, setMerchant] = useState<MerchantData | null>(null);
  const [terminals, setTerminals] = useState<Terminal[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [toast, setToast] = useState<{ type: "success" | "error"; msg: string } | null>(null);

  const [editBiz, setEditBiz] = useState("");
  const [editFirst, setEditFirst] = useState("");
  const [editLast, setEditLast] = useState("");
  const [editPhone, setEditPhone] = useState("");
  const [editMerchantNum, setEditMerchantNum] = useState("");
  const [editStatus, setEditStatus] = useState<string>("active");
  const [editAddress, setEditAddress] = useState<Address>({ street: "", city: "", state: "", zip: "" });

  const [showAddTerminal, setShowAddTerminal] = useState(false);
  const [rotateTerminal, setRotateTerminal] = useState<Terminal | null>(null);

  const showToast = useCallback((type: "success" | "error", msg: string) => {
    setToast({ type, msg });
    setTimeout(() => setToast(null), 3500);
  }, []);

  const loadMerchant = useCallback(async () => {
    try {
      const data = await apiCall(`/api/merchants/${merchantId}`);
      const m = data.merchant as MerchantData;
      setMerchant(m);
      setTerminals((data.terminals || []) as Terminal[]);
      setEditBiz(m.businessName || "");
      setEditFirst(m.ownerFirstName || "");
      setEditLast(m.ownerLastName || "");
      setEditPhone(m.phone || "");
      setEditMerchantNum(m.merchantNumber || "");
      setEditStatus(m.status || "active");
      setEditAddress(m.address || { street: "", city: "", state: "", zip: "" });
    } catch {
      showToast("error", "Failed to load merchant.");
    } finally {
      setLoading(false);
    }
  }, [merchantId, showToast]);

  useEffect(() => {
    loadMerchant();
  }, [loadMerchant]);

  const handleSave = async () => {
    setSaving(true);
    try {
      await apiCall(`/api/merchants/${merchantId}`, {
        method: "PATCH",
        body: JSON.stringify({
          businessName: editBiz,
          ownerFirstName: editFirst,
          ownerLastName: editLast,
          phone: editPhone,
          merchantNumber: editMerchantNum,
          status: editStatus,
          address: editAddress,
        }),
      });
      showToast("success", "Merchant updated.");
      await loadMerchant();
    } catch (err) {
      showToast("error", err instanceof Error ? err.message : "Save failed.");
    } finally {
      setSaving(false);
    }
  };

  const toggleTerminal = async (t: Terminal) => {
    try {
      await apiCall(`/api/merchants/${merchantId}/terminals`, {
        method: "PATCH",
        body: JSON.stringify({ terminalId: t.id, active: !t.active }),
      });
      setTerminals((prev) =>
        prev.map((x) => (x.id === t.id ? { ...x, active: !x.active } : x))
      );
      showToast("success", `Terminal ${t.active ? "disabled" : "enabled"}.`);
    } catch {
      showToast("error", "Failed to toggle terminal.");
    }
  };

  const deleteTerminal = async (t: Terminal) => {
    if (!confirm(`Delete "${t.name}"? This cannot be undone.`)) return;
    try {
      await apiCall(`/api/merchants/${merchantId}/terminals?terminalId=${t.id}`, {
        method: "DELETE",
      });
      setTerminals((prev) => prev.filter((x) => x.id !== t.id));
      showToast("success", "Terminal deleted.");
    } catch {
      showToast("error", "Failed to delete terminal.");
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[60vh]">
        <Loader2 size={32} className="animate-spin text-blue-600" />
      </div>
    );
  }

  if (!merchant) {
    return (
      <div className="p-8 text-center">
        <p className="text-slate-500 mb-4">Merchant not found.</p>
        <Link href="/merchants" className="text-blue-600 hover:underline text-sm">
          Back to merchants
        </Link>
      </div>
    );
  }

  return (
    <div className="p-8 max-w-4xl mx-auto">
      {toast && (
        <div
          className={`fixed top-6 right-6 z-50 px-5 py-3 rounded-xl text-sm font-medium shadow-lg ${
            toast.type === "success"
              ? "bg-green-600 text-white"
              : "bg-red-600 text-white"
          }`}
        >
          {toast.msg}
        </div>
      )}

      <Link
        href="/merchants"
        className="inline-flex items-center gap-1.5 text-sm text-slate-500 hover:text-slate-700 mb-6"
      >
        <ArrowLeft size={16} />
        Back to Merchants
      </Link>

      <div className="flex items-center gap-4 mb-8">
        <div className="w-12 h-12 rounded-2xl bg-slate-100 flex items-center justify-center">
          <Store size={24} className="text-slate-600" />
        </div>
        <div>
          <h1 className="text-2xl font-bold text-slate-900">{merchant.businessName}</h1>
          <p className="text-slate-500 text-sm">
            Merchant #{merchant.merchantNumber} &middot; {merchant.email}
          </p>
        </div>
      </div>

      {/* ─── Business Info ─── */}
      <div className="bg-white rounded-2xl border border-slate-200 p-6 mb-6">
        <div className="flex items-center justify-between mb-5">
          <h3 className="text-sm font-semibold text-slate-800 uppercase tracking-wider">
            Business Information
          </h3>
          <div className="flex items-center gap-2">
            <select
              value={editStatus}
              onChange={(e) => setEditStatus(e.target.value)}
              className="text-xs font-semibold rounded-full px-3 py-1.5 border border-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              {statusOptions.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <EditField label="Merchant #" value={editMerchantNum} onChange={setEditMerchantNum} />
          <EditField label="Business Name" value={editBiz} onChange={setEditBiz} />
          <EditField label="Owner First Name" value={editFirst} onChange={setEditFirst} />
          <EditField label="Owner Last Name" value={editLast} onChange={setEditLast} />
          <EditField label="Email" value={merchant.email} onChange={() => {}} disabled />
          <EditField label="Phone" value={editPhone} onChange={setEditPhone} />
        </div>

        <div className="mt-4 pt-4 border-t border-slate-100">
          <p className="text-xs font-semibold text-slate-500 uppercase tracking-wider mb-3">Address</p>
          <div className="grid grid-cols-1 gap-3">
            <EditField
              label="Street"
              value={editAddress.street}
              onChange={(v) => setEditAddress((p) => ({ ...p, street: v }))}
            />
            <div className="grid grid-cols-3 gap-3">
              <EditField
                label="City"
                value={editAddress.city}
                onChange={(v) => setEditAddress((p) => ({ ...p, city: v }))}
              />
              <EditField
                label="State"
                value={editAddress.state}
                onChange={(v) => setEditAddress((p) => ({ ...p, state: v }))}
              />
              <EditField
                label="ZIP"
                value={editAddress.zip}
                onChange={(v) => setEditAddress((p) => ({ ...p, zip: v }))}
              />
            </div>
          </div>
        </div>

        <div className="mt-5 flex justify-end">
          <button
            onClick={handleSave}
            disabled={saving}
            className="inline-flex items-center gap-2 px-5 py-2.5 text-sm font-medium text-white bg-blue-600 rounded-xl hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {saving ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />}
            Save Changes
          </button>
        </div>
      </div>

      {/* ─── Payment Terminals ─── */}
      <div className="bg-white rounded-2xl border border-slate-200 p-6 mb-6">
        <div className="flex items-center justify-between mb-5">
          <div className="flex items-center gap-2">
            <CreditCard size={18} className="text-slate-600" />
            <h3 className="text-sm font-semibold text-slate-800 uppercase tracking-wider">
              Payment Terminals
            </h3>
          </div>
          <button
            onClick={() => setShowAddTerminal(true)}
            className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-medium text-blue-600 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors"
          >
            <Plus size={14} />
            Add Terminal
          </button>
        </div>

        {terminals.length === 0 ? (
          <p className="text-sm text-slate-400 text-center py-8">
            No payment terminals configured.
          </p>
        ) : (
          <div className="space-y-3">
            {terminals.map((t) => (
              <TerminalCard
                key={t.id}
                terminal={t}
                onToggle={() => toggleTerminal(t)}
                onDelete={() => deleteTerminal(t)}
                onRotate={() => setRotateTerminal(t)}
              />
            ))}
          </div>
        )}
      </div>

      {/* ─── Add Terminal Modal ─── */}
      {showAddTerminal && (
        <AddTerminalModal
          merchantId={merchantId}
          onClose={() => setShowAddTerminal(false)}
          onAdded={(t) => {
            setTerminals((prev) => [...prev, t]);
            showToast("success", "Terminal added.");
            setShowAddTerminal(false);
          }}
          onError={(msg) => showToast("error", msg)}
        />
      )}

      {/* ─── Rotate Credentials Modal ─── */}
      {rotateTerminal && (
        <RotateCredentialsModal
          merchantId={merchantId}
          terminal={rotateTerminal}
          onClose={() => setRotateTerminal(null)}
          onUpdated={() => {
            showToast("success", "Credentials updated.");
            setRotateTerminal(null);
            loadMerchant();
          }}
          onError={(msg) => showToast("error", msg)}
        />
      )}
    </div>
  );
}

/* ─── Sub-components ─── */

function EditField({
  label,
  value,
  onChange,
  disabled,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  disabled?: boolean;
  placeholder?: string;
}) {
  return (
    <div>
      <label className="block text-xs font-medium text-slate-500 mb-1">{label}</label>
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        placeholder={placeholder}
        className={`w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 placeholder:text-slate-300 ${
          disabled ? "bg-slate-50 text-slate-400 cursor-not-allowed" : ""
        }`}
      />
    </div>
  );
}

function TerminalCard({
  terminal: t,
  onToggle,
  onDelete,
  onRotate,
}: {
  terminal: Terminal;
  onToggle: () => void;
  onDelete: () => void;
  onRotate: () => void;
}) {
  const [showCreds, setShowCreds] = useState(false);
  const cfg = t.config || ({} as TerminalConfig);

  return (
    <div
      className={`border rounded-xl p-4 transition-colors ${
        t.active ? "border-slate-200 bg-white" : "border-slate-100 bg-slate-50"
      }`}
    >
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-3">
          <div
            className={`w-2.5 h-2.5 rounded-full ${t.active ? "bg-green-500" : "bg-slate-300"}`}
          />
          <span className="text-sm font-semibold text-slate-800">{t.name}</span>
          <span className="text-xs text-slate-400 font-mono">
            {t.provider} &middot; {t.deviceModel || "Unknown"}
          </span>
        </div>
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => setShowCreds(!showCreds)}
            className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 hover:text-slate-600 transition-colors"
            title={showCreds ? "Hide credentials" : "Show credentials"}
          >
            {showCreds ? <EyeOff size={15} /> : <Eye size={15} />}
          </button>
          <button
            onClick={onRotate}
            className="p-1.5 rounded-lg hover:bg-blue-50 text-slate-400 hover:text-blue-600 transition-colors"
            title="Rotate credentials"
          >
            <RotateCw size={15} />
          </button>
          <button
            onClick={onToggle}
            className={`p-1.5 rounded-lg transition-colors ${
              t.active
                ? "hover:bg-amber-50 text-slate-400 hover:text-amber-600"
                : "hover:bg-green-50 text-slate-400 hover:text-green-600"
            }`}
            title={t.active ? "Disable" : "Enable"}
          >
            <Power size={15} />
          </button>
          <button
            onClick={onDelete}
            className="p-1.5 rounded-lg hover:bg-red-50 text-slate-400 hover:text-red-600 transition-colors"
            title="Delete terminal"
          >
            <Trash2 size={15} />
          </button>
        </div>
      </div>

      {showCreds && (
        <div className="mt-3 pt-3 border-t border-slate-100 grid grid-cols-2 gap-3">
          <CredField label="TPN" value={cfg.tpn} />
          <CredField label="Register ID" value={cfg.registerId} />
          <CredField label="Auth Key" value={cfg.authKey} />
          {cfg.iposTransactAuthToken && (
            <CredField label="iPOS Token" value={cfg.iposTransactAuthToken} />
          )}
        </div>
      )}
    </div>
  );
}

function CredField({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span className="text-[10px] font-semibold text-slate-400 uppercase tracking-wider">{label}</span>
      <p className="text-xs font-mono text-slate-700 mt-0.5 break-all">{value || "—"}</p>
    </div>
  );
}

/* ─── Add Terminal Modal ─── */

function AddTerminalModal({
  merchantId,
  onClose,
  onAdded,
  onError,
}: {
  merchantId: string;
  onClose: () => void;
  onAdded: (t: Terminal) => void;
  onError: (msg: string) => void;
}) {
  const [provider, setProvider] = useState<ProviderType>("SPIN_Z");
  const [deviceModel, setDeviceModel] = useState("");
  const [terminalName, setTerminalName] = useState("");
  const [tpn, setTpn] = useState("");
  const [registerId, setRegisterId] = useState("");
  const [authKey, setAuthKey] = useState("");
  const [iposToken, setIposToken] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const handleAdd = async () => {
    if (!tpn.trim()) {
      onError("TPN is required.");
      return;
    }
    setSubmitting(true);
    try {
      const data = await apiCall(`/api/merchants/${merchantId}/terminals`, {
        method: "POST",
        body: JSON.stringify({
          provider,
          deviceModel,
          terminalName,
          tpn,
          registerId,
          authKey,
          iposTransactAuthToken: iposToken,
        }),
      });
      onAdded({
        id: data.terminalId,
        name: terminalName || "Terminal",
        provider,
        deviceModel,
        active: true,
        config: { tpn, registerId, authKey, iposTransactAuthToken: iposToken || undefined },
      });
    } catch (err) {
      onError(err instanceof Error ? err.message : "Failed to add terminal.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ModalShell title="Add Terminal" onClose={onClose}>
      <div className="space-y-4">
        <EditField label="Terminal Name" value={terminalName} onChange={setTerminalName} />
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-xs font-medium text-slate-500 mb-1">Provider</label>
            <select
              value={provider}
              onChange={(e) => setProvider(e.target.value as ProviderType)}
              className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white"
            >
              {PROVIDER_OPTIONS.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.label}
                </option>
              ))}
            </select>
          </div>
          <EditField
            label="Device model"
            value={deviceModel}
            onChange={setDeviceModel}
            placeholder="e.g. Dejavoo P17"
          />
        </div>
        <div className="border-t border-slate-100 pt-4" />
        <div className="grid grid-cols-2 gap-4">
          <EditField label="TPN *" value={tpn} onChange={setTpn} />
          <EditField label="Register ID" value={registerId} onChange={setRegisterId} />
        </div>
        <EditField label="Auth Key" value={authKey} onChange={setAuthKey} />
        {provider === "SPIN_P" && (
          <EditField label="iPOS Transact Auth Token" value={iposToken} onChange={setIposToken} />
        )}
      </div>
      <div className="mt-6 flex justify-end gap-3">
        <button
          onClick={onClose}
          className="px-4 py-2 text-sm font-medium text-slate-600 border border-slate-200 rounded-lg hover:bg-slate-50"
        >
          Cancel
        </button>
        <button
          onClick={handleAdd}
          disabled={submitting}
          className="inline-flex items-center gap-2 px-5 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50"
        >
          {submitting && <Loader2 size={14} className="animate-spin" />}
          Add Terminal
        </button>
      </div>
    </ModalShell>
  );
}

/* ─── Rotate Credentials Modal ─── */

function RotateCredentialsModal({
  merchantId,
  terminal,
  onClose,
  onUpdated,
  onError,
}: {
  merchantId: string;
  terminal: Terminal;
  onClose: () => void;
  onUpdated: () => void;
  onError: (msg: string) => void;
}) {
  const cfg = terminal.config || ({} as TerminalConfig);
  const [tpn, setTpn] = useState(cfg.tpn || "");
  const [registerId, setRegisterId] = useState(cfg.registerId || "");
  const [authKey, setAuthKey] = useState(cfg.authKey || "");
  const [iposToken, setIposToken] = useState(cfg.iposTransactAuthToken || "");
  const [submitting, setSubmitting] = useState(false);

  const handleSave = async () => {
    setSubmitting(true);
    try {
      await apiCall(`/api/merchants/${merchantId}/terminals`, {
        method: "PATCH",
        body: JSON.stringify({
          terminalId: terminal.id,
          config: { tpn, registerId, authKey, iposTransactAuthToken: iposToken },
        }),
      });
      onUpdated();
    } catch (err) {
      onError(err instanceof Error ? err.message : "Failed to update credentials.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ModalShell title={`Rotate Credentials — ${terminal.name}`} onClose={onClose}>
      <div className="space-y-4">
        <div className="grid grid-cols-2 gap-4">
          <EditField label="TPN" value={tpn} onChange={setTpn} />
          <EditField label="Register ID" value={registerId} onChange={setRegisterId} />
        </div>
        <EditField label="Auth Key" value={authKey} onChange={setAuthKey} />
        {terminal.provider === "SPIN_P" && (
          <EditField label="iPOS Transact Auth Token" value={iposToken} onChange={setIposToken} />
        )}
      </div>
      <div className="mt-6 flex justify-end gap-3">
        <button
          onClick={onClose}
          className="px-4 py-2 text-sm font-medium text-slate-600 border border-slate-200 rounded-lg hover:bg-slate-50"
        >
          Cancel
        </button>
        <button
          onClick={handleSave}
          disabled={submitting}
          className="inline-flex items-center gap-2 px-5 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50"
        >
          {submitting && <Loader2 size={14} className="animate-spin" />}
          Update Credentials
        </button>
      </div>
    </ModalShell>
  );
}

/* ─── Modal Shell ─── */

function ModalShell({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-white rounded-2xl shadow-xl w-full max-w-lg mx-4 p-6 relative">
        <button
          onClick={onClose}
          className="absolute top-4 right-4 p-1 rounded-lg hover:bg-slate-100 text-slate-400"
        >
          <X size={18} />
        </button>
        <h3 className="text-lg font-bold text-slate-900 mb-5">{title}</h3>
        {children}
      </div>
    </div>
  );
}
