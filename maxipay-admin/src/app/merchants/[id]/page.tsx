"use client";

import { useEffect, useState, useCallback } from "react";
import { useParams } from "next/navigation";
import { auth } from "@/firebase/firebaseConfig";
import Link from "next/link";
import {
  ArrowLeft,
  Store,
  Loader2,
  Save,
  Mail,
} from "lucide-react";

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
  const merchantId = params.id as string;

  const [merchant, setMerchant] = useState<MerchantData | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [toast, setToast] = useState<{ type: "success" | "error"; msg: string } | null>(null);

  const [editBiz, setEditBiz] = useState("");
  const [editFirst, setEditFirst] = useState("");
  const [editLast, setEditLast] = useState("");
  const [editPhone, setEditPhone] = useState("");
  const [editMerchantNum, setEditMerchantNum] = useState("");
  const [editEmail, setEditEmail] = useState("");
  const [editStatus, setEditStatus] = useState<string>("active");
  const [editAddress, setEditAddress] = useState<Address>({ street: "", city: "", state: "", zip: "" });

  const [resendWelcomeBusy, setResendWelcomeBusy] = useState(false);

  const showToast = useCallback((type: "success" | "error", msg: string) => {
    setToast({ type, msg });
    setTimeout(() => setToast(null), 3500);
  }, []);

  const loadMerchant = useCallback(async () => {
    try {
      const data = await apiCall(`/api/merchants/${merchantId}`);
      const m = data.merchant as MerchantData;
      setMerchant(m);
      setEditBiz(m.businessName || "");
      setEditFirst(m.ownerFirstName || "");
      setEditLast(m.ownerLastName || "");
      setEditPhone(m.phone || "");
      setEditMerchantNum(m.merchantNumber || "");
      setEditEmail(m.email || "");
      setEditStatus(m.status || "active");
      setEditAddress(m.address || { street: "", city: "", state: "", zip: "" });

      try {
        await apiCall(`/api/merchants/${merchantId}/sync-business-settings`, {
          method: "POST",
        });
      } catch {
        /* POS/dashboard sync is best-effort; merchant list still loaded */
      }
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
    if (!editFirst.trim() || !editLast.trim()) {
      showToast("error", "Owner first and last name are required.");
      return;
    }
    setSaving(true);
    try {
      await apiCall(`/api/merchants/${merchantId}`, {
        method: "PATCH",
        body: JSON.stringify({
          businessName: editBiz,
          ownerFirstName: editFirst,
          ownerLastName: editLast,
          email: editEmail,
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

  const handleResendWelcome = async () => {
    const saved = (merchant?.email ?? "").trim().toLowerCase();
    const draft = editEmail.trim().toLowerCase();
    if (!saved) {
      showToast("error", "Set an owner email and click Save changes first.");
      return;
    }
    if (draft !== saved) {
      showToast("error", "Save changes first so the new email is stored, then resend the welcome email.");
      return;
    }
    setResendWelcomeBusy(true);
    try {
      const data = (await apiCall(`/api/merchants/${merchantId}/welcome-email`, {
        method: "POST",
      })) as { emailSent?: boolean; emailHint?: string };
      if (data.emailSent) {
        showToast("success", `Welcome email sent to ${editEmail.trim()}. Check spam folder.`);
      } else {
        showToast(
          "error",
          data.emailHint
            ? `Email not sent: ${data.emailHint}`
            : "Email not sent. Configure RESEND_API_KEY on the admin app (Vercel)."
        );
      }
    } catch (err) {
      showToast("error", err instanceof Error ? err.message : "Could not send email.");
    } finally {
      setResendWelcomeBusy(false);
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

      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4 mb-8">
        <div className="flex items-center gap-4">
          <div className="w-12 h-12 rounded-2xl bg-slate-100 flex items-center justify-center">
            <Store size={24} className="text-slate-600" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-slate-900">{merchant.businessName}</h1>
            <p className="text-slate-500 text-sm">
              Merchant #{merchant.merchantNumber}
              {merchant.email ? ` · ${merchant.email}` : ""}
            </p>
          </div>
        </div>
        <Link
          href={`/merchants/${merchantId}/employees`}
          className="inline-flex items-center justify-center sm:justify-start gap-2 px-4 py-2.5 text-sm font-medium text-blue-700 bg-blue-50 border border-blue-200 rounded-xl hover:bg-blue-100 transition-colors shrink-0"
        >
          Employees information
        </Link>
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
          <EditField
            label="Owner email"
            value={editEmail}
            onChange={setEditEmail}
            placeholder="owner@business.com"
          />
          <EditField label="Phone" value={editPhone} onChange={setEditPhone} />
          <div className="col-span-2">
            <p className="text-xs text-slate-500 mb-2">
              Changing email updates Firebase Authentication for the merchant login (same account, new address).{" "}
              <strong>Resend welcome email</strong> sends a set-password link to the email{" "}
              <em>saved</em> on this page — click <strong>Save changes</strong> first if you edited the email. After they
              set a password, Firebase redirects to the merchant dashboard login when{" "}
              <code className="text-[11px] bg-slate-100 px-1 rounded">MERCHANT_WEB_APP_URL</code> is set on the admin
              server (Vercel) and that host is an authorized domain in Firebase.
            </p>
            <button
              type="button"
              onClick={handleResendWelcome}
              disabled={resendWelcomeBusy}
              className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-slate-700 bg-slate-100 border border-slate-200 rounded-lg hover:bg-slate-200 disabled:opacity-50 transition-colors"
            >
              {resendWelcomeBusy ? (
                <Loader2 size={16} className="animate-spin" />
              ) : (
                <Mail size={16} />
              )}
              Resend welcome email
            </button>
          </div>
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
    </div>
  );
}

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
