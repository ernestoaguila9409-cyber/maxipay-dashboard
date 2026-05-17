"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { auth } from "@/firebase/firebaseConfig";
import { ArrowLeft, Store, Loader2 } from "lucide-react";
import Link from "next/link";

type ProviderType = "SPIN_Z" | "SPIN_P";
type DeviceType = "pos" | "mobile" | "none";

const PROVIDER_OPTIONS: { id: ProviderType; label: string }[] = [
  { id: "SPIN_Z", label: "SPIn Z-series (Z8, QD3, QD4)" },
  { id: "SPIN_P", label: "SPIn P-series (P17, P20)" },
];

const DEVICE_TYPE_OPTIONS: { id: DeviceType; label: string; description: string }[] = [
  { id: "none", label: "Skip for now", description: "Set up devices later from the merchant dashboard" },
  { id: "pos", label: "POS App", description: "Tablet (e.g. Landi C20 Pro) + external PIN pad (e.g. Dejavoo P17)" },
  { id: "mobile", label: "Mobile App", description: "MaxiMobile on Dejavoo P8 — no external PIN pad needed" },
];

interface FormState {
  merchantNumber: string;
  businessName: string;
  ownerFirstName: string;
  ownerLastName: string;
  email: string;
  phone: string;
  street: string;
  city: string;
  state: string;
  zip: string;
  deviceType: DeviceType;
  payProvider: ProviderType;
  payDeviceModel: string;
  payTerminalName: string;
  payTpn: string;
  payRegisterId: string;
  payAuthKey: string;
  payIposTransactToken: string;
}

const initialForm: FormState = {
  merchantNumber: "",
  businessName: "",
  ownerFirstName: "",
  ownerLastName: "",
  email: "",
  phone: "",
  street: "",
  city: "",
  state: "",
  zip: "",
  deviceType: "none",
  payProvider: "SPIN_Z",
  payDeviceModel: "",
  payTerminalName: "",
  payTpn: "",
  payRegisterId: "",
  payAuthKey: "",
  payIposTransactToken: "",
};

export default function CreateMerchantPage() {
  const router = useRouter();
  const [form, setForm] = useState<FormState>(initialForm);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [emailSent, setEmailSent] = useState(true);
  const [emailHint, setEmailHint] = useState<string | null>(null);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);

    try {
      const user = auth.currentUser;
      if (!user) {
        setError("You must be signed in.");
        setSubmitting(false);
        return;
      }

      const token = await user.getIdToken();
      const hasPayment = form.deviceType === "pos" && form.payTpn.trim().length > 0;
      const payload: Record<string, unknown> = {
        merchantNumber: form.merchantNumber,
        businessName: form.businessName,
        ownerFirstName: form.ownerFirstName,
        ownerLastName: form.ownerLastName,
        email: form.email,
        phone: form.phone,
        address: {
          street: form.street,
          city: form.city,
          state: form.state,
          zip: form.zip,
        },
      };
      if (hasPayment) {
        payload.payment = {
          provider: form.payProvider,
          deviceModel: form.payDeviceModel,
          terminalName: form.payTerminalName,
          tpn: form.payTpn,
          registerId: form.payRegisterId,
          authKey: form.payAuthKey,
          iposTransactAuthToken: form.payIposTransactToken,
        };
      }
      const res = await fetch("/api/merchants", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(payload),
      });

      const data = await res.json();
      if (!res.ok || !data.ok) {
        setError(data.message || "Failed to create merchant.");
        setSubmitting(false);
        return;
      }

      setEmailSent(data.emailSent === true);
      setEmailHint(typeof data.emailHint === "string" ? data.emailHint : null);
      setSuccess(true);
      setTimeout(() => router.push("/merchants"), data.emailSent === true ? 2000 : 5000);
    } catch {
      setError("Something went wrong. Please try again.");
      setSubmitting(false);
    }
  };

  if (success) {
    return (
      <div className="p-8">
        <div className="max-w-2xl mx-auto">
          <div className="bg-white rounded-2xl border border-slate-200 p-8 text-center">
            <div className="w-16 h-16 rounded-full bg-green-100 flex items-center justify-center mx-auto mb-4">
              <Store size={28} className="text-green-600" />
            </div>
            <h2 className="text-xl font-bold text-slate-900 mb-2">Merchant Created</h2>
            <p className="text-slate-500 mb-1">
              <strong>{form.businessName}</strong> has been set up successfully.
            </p>
            {emailSent ? (
              <p className="text-sm text-slate-400">
                A welcome email with a password link was sent to <strong>{form.email}</strong>.
                Check spam and promotions folders.
              </p>
            ) : (
              <div className="mt-3 text-left max-w-md mx-auto">
                <p className="text-sm text-amber-800 bg-amber-50 border border-amber-200 rounded-xl px-4 py-3">
                  The merchant account was created, but <strong>no email was sent</strong>. Add{" "}
                  <code className="text-xs bg-amber-100 px-1 rounded">RESEND_API_KEY</code> and a
                  verified <code className="text-xs bg-amber-100 px-1 rounded">RESEND_FROM_EMAIL</code>{" "}
                  to the <strong>admin</strong> app on Vercel, then create a password from Firebase
                  Console → Authentication → select user → Reset password (or try again after fixing
                  Resend).
                </p>
                {emailHint && (
                  <p className="text-xs text-slate-600 mt-2 font-mono break-words">{emailHint}</p>
                )}
              </div>
            )}
            <p className="text-sm text-slate-400 mt-4">Redirecting to merchants list...</p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="p-8">
      <div className="max-w-2xl mx-auto">
        <Link
          href="/merchants"
          className="inline-flex items-center gap-1.5 text-sm text-slate-500 hover:text-slate-700 mb-6"
        >
          <ArrowLeft size={16} />
          Back to Merchants
        </Link>

        <div className="mb-6">
          <h1 className="text-2xl font-bold text-slate-900">Create Merchant</h1>
          <p className="text-slate-500 mt-1">
            Set up a new merchant account. The owner will receive an email to set their password.
          </p>
          <p className="text-slate-400 text-sm mt-2 max-w-xl">
            Every new merchant uses the <strong className="font-medium text-slate-500">same</strong> merchant
            web dashboard (one URL, one deployed app). You do not enable features per merchant on this form.
            After you create the account, the owner gets the same capabilities as any other store— including
            menu import from Excel or picture, the Menu QR (view-only public menu with preview cart),
            multi-select bulk actions on menu items (taxes, kitchen label, KDS, modifiers), and scanning
            modifier lists from a picture on the Modifiers page—whenever those
            exist in your <strong className="font-medium text-slate-500">current</strong> merchant dashboard
            deployment. Deploy the latest <code className="text-xs bg-slate-100 px-1 rounded">maxipay-dashboard</code>{" "}
            so all merchants, including new ones, receive updates.
          </p>
        </div>

        {error && (
          <div className="mb-6 px-4 py-3 bg-red-50 border border-red-200 text-red-700 text-sm rounded-xl">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="bg-white rounded-2xl border border-slate-200 p-6 mb-6">
            <h3 className="text-sm font-semibold text-slate-800 uppercase tracking-wider mb-4">
              Business Information
            </h3>
            <Field
              label="Merchant #"
              name="merchantNumber"
              value={form.merchantNumber}
              onChange={handleChange}
              required
              placeholder="e.g. 10042"
            />
            <div className="my-5 border-t border-slate-200" aria-hidden />
            <Field
              label="Business Name"
              name="businessName"
              value={form.businessName}
              onChange={handleChange}
              required
              placeholder="e.g. Joe's Pizza"
            />
          </div>

          <div className="bg-white rounded-2xl border border-slate-200 p-6 mb-6">
            <h3 className="text-sm font-semibold text-slate-800 uppercase tracking-wider mb-4">
              Owner Information
            </h3>
            <div className="grid grid-cols-2 gap-4">
              <Field
                label="First Name"
                name="ownerFirstName"
                value={form.ownerFirstName}
                onChange={handleChange}
                required
                placeholder="John"
              />
              <Field
                label="Last Name"
                name="ownerLastName"
                value={form.ownerLastName}
                onChange={handleChange}
                required
                placeholder="Doe"
              />
            </div>
            <div className="grid grid-cols-2 gap-4 mt-4">
              <Field label="Email" name="email" type="email" value={form.email} onChange={handleChange} required placeholder="owner@business.com" />
              <Field label="Phone" name="phone" type="tel" value={form.phone} onChange={handleChange} placeholder="(555) 123-4567" />
            </div>
          </div>

          <div className="bg-white rounded-2xl border border-slate-200 p-6 mb-6">
            <h3 className="text-sm font-semibold text-slate-800 uppercase tracking-wider mb-4">
              Address
            </h3>
            <Field label="Street" name="street" value={form.street} onChange={handleChange} placeholder="123 Main St" />
            <div className="grid grid-cols-3 gap-4 mt-4">
              <Field label="City" name="city" value={form.city} onChange={handleChange} placeholder="New York" />
              <Field label="State" name="state" value={form.state} onChange={handleChange} placeholder="NY" />
              <Field label="ZIP" name="zip" value={form.zip} onChange={handleChange} placeholder="10001" />
            </div>
          </div>

          <div className="bg-white rounded-2xl border border-slate-200 p-6 mb-6">
            <h3 className="text-sm font-semibold text-slate-800 uppercase tracking-wider mb-4">
              Device Setup
            </h3>
            <p className="text-xs text-slate-500 mb-4 -mt-1">
              Choose how this merchant will process payments. You can always add or change devices later
              from <strong>Settings → Payments &amp; Devices</strong> in the merchant dashboard.
            </p>

            <div className="space-y-2 mb-4">
              {DEVICE_TYPE_OPTIONS.map((opt) => (
                <label
                  key={opt.id}
                  className={`flex items-start gap-3 p-3 rounded-xl border-2 cursor-pointer transition-all ${
                    form.deviceType === opt.id
                      ? "border-blue-400 bg-blue-50/30"
                      : "border-slate-200 hover:border-slate-300"
                  }`}
                >
                  <input
                    type="radio"
                    name="deviceType"
                    value={opt.id}
                    checked={form.deviceType === opt.id}
                    onChange={handleChange}
                    className="mt-0.5"
                  />
                  <div>
                    <div className="text-sm font-medium text-slate-800">{opt.label}</div>
                    <div className="text-xs text-slate-500">{opt.description}</div>
                  </div>
                </label>
              ))}
            </div>

            {form.deviceType === "pos" && (
              <>
                <div className="my-5 border-t border-slate-200" aria-hidden />
                <h4 className="text-xs font-semibold text-slate-600 uppercase tracking-wider mb-3">
                  PIN pad / SPIn credentials
                </h4>
                <Field label="Terminal Name" name="payTerminalName" value={form.payTerminalName} onChange={handleChange} placeholder="e.g. Main Register" />
                <div className="grid grid-cols-2 gap-4 mt-4">
                  <div>
                    <label htmlFor="payProvider" className="block text-sm font-medium text-slate-700 mb-1.5">
                      Provider
                    </label>
                    <select
                      id="payProvider"
                      name="payProvider"
                      value={form.payProvider}
                      onChange={handleChange}
                      className="w-full px-3.5 py-2.5 text-sm border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent bg-white"
                    >
                      {PROVIDER_OPTIONS.map((p) => (
                        <option key={p.id} value={p.id}>{p.label}</option>
                      ))}
                    </select>
                  </div>
                  <Field
                    label="Device model"
                    name="payDeviceModel"
                    value={form.payDeviceModel}
                    onChange={handleChange}
                    placeholder="e.g. Dejavoo P17, Z8, QD4"
                  />
                </div>
                <div className="my-5 border-t border-slate-200" aria-hidden />
                <div className="grid grid-cols-2 gap-4">
                  <Field label="TPN" name="payTpn" value={form.payTpn} onChange={handleChange} placeholder="e.g. 11881706541A" />
                  <Field label="Register ID" name="payRegisterId" value={form.payRegisterId} onChange={handleChange} placeholder="e.g. 134909005" />
                </div>
                <div className="mt-4">
                  <Field label="Auth Key" name="payAuthKey" value={form.payAuthKey} onChange={handleChange} placeholder="e.g. Qt9N7CxhDs" />
                </div>
                {form.payProvider === "SPIN_P" && (
                  <div className="mt-4">
                    <Field label="iPOS Transact Auth Token" name="payIposTransactToken" value={form.payIposTransactToken} onChange={handleChange} placeholder="Optional — for card-not-present refunds" />
                  </div>
                )}
              </>
            )}

            {form.deviceType === "mobile" && (
              <div className="mt-4 rounded-xl bg-blue-50 border border-blue-100 px-4 py-3 text-sm text-blue-800">
                No payment credentials needed. The Dejavoo P8 uses DvPayLite (deeplink) — it is provisioned
                directly on the device. After creating the merchant, generate an activation code from
                <strong> Settings → Payments &amp; Devices → Add Device → Mobile App</strong> in the dashboard.
              </div>
            )}
          </div>

          <div className="flex items-center justify-end gap-3">
            <Link
              href="/merchants"
              className="px-5 py-2.5 text-sm font-medium text-slate-600 bg-white border border-slate-200 rounded-xl hover:bg-slate-50 transition-colors"
            >
              Cancel
            </Link>
            <button
              type="submit"
              disabled={submitting}
              className="inline-flex items-center gap-2 px-6 py-2.5 text-sm font-medium text-white bg-blue-600 rounded-xl hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {submitting ? (
                <>
                  <Loader2 size={18} className="animate-spin" />
                  Creating...
                </>
              ) : (
                "Create Merchant"
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function Field({
  label,
  name,
  value,
  onChange,
  type = "text",
  required = false,
  placeholder,
}: {
  label: string;
  name: string;
  value: string;
  onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => void;
  type?: string;
  required?: boolean;
  placeholder?: string;
}) {
  return (
    <div>
      <label htmlFor={name} className="block text-sm font-medium text-slate-700 mb-1.5">
        {label}
        {required && <span className="text-red-500 ml-0.5">*</span>}
      </label>
      <input
        id={name}
        name={name}
        type={type}
        value={value}
        onChange={onChange}
        required={required}
        placeholder={placeholder}
        className="w-full px-3.5 py-2.5 text-sm border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent placeholder:text-slate-300"
      />
    </div>
  );
}
