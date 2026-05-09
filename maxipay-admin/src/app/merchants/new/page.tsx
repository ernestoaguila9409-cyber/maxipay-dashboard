"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { auth } from "@/firebase/firebaseConfig";
import { ArrowLeft, Store, Loader2 } from "lucide-react";
import Link from "next/link";

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
};

export default function CreateMerchantPage() {
  const router = useRouter();
  const [form, setForm] = useState<FormState>(initialForm);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
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
      const res = await fetch("/api/merchants", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
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
        }),
      });

      const data = await res.json();
      if (!res.ok || !data.ok) {
        setError(data.message || "Failed to create merchant.");
        setSubmitting(false);
        return;
      }

      setSuccess(true);
      setTimeout(() => router.push("/merchants"), 2000);
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
            <p className="text-sm text-slate-400">
              A welcome email has been sent to <strong>{form.email}</strong>.
            </p>
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
              <Field label="First Name" name="ownerFirstName" value={form.ownerFirstName} onChange={handleChange} placeholder="John" />
              <Field label="Last Name" name="ownerLastName" value={form.ownerLastName} onChange={handleChange} placeholder="Doe" />
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
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
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
