"use client";

import { useCallback, useEffect, useState } from "react";
import Header from "@/components/Header";
import { db } from "@/firebase/firebaseConfig";
import { doc, onSnapshot, setDoc, serverTimestamp } from "firebase/firestore";
import {
  Save,
  Check,
  Loader2,
  Store,
  MapPin,
  Phone,
  Mail,
  Image,
  AlertCircle,
} from "lucide-react";

interface BusinessData {
  businessName: string;
  address: string;
  phone: string;
  email: string;
  logoUrl: string;
}

const EMPTY: BusinessData = {
  businessName: "",
  address: "",
  phone: "",
  email: "",
  logoUrl: "",
};

const DOC_REF = "Settings";
const DOC_ID = "businessInfo";

type SaveStatus = "idle" | "saving" | "saved" | "error";

export default function BusinessInformationPage() {
  const [data, setData] = useState<BusinessData>(EMPTY);
  const [loading, setLoading] = useState(true);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>("idle");
  const [saveError, setSaveError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    console.log("Business page loaded");

    const unsub = onSnapshot(
      doc(db, DOC_REF, DOC_ID),
      (snap) => {
        if (snap.exists()) {
          const d = snap.data();
          if (!dirty) {
            setData({
              businessName: d.businessName ?? "",
              address: d.address ?? "",
              phone: d.phone ?? "",
              email: d.email ?? "",
              logoUrl: d.logoUrl ?? "",
            });
          }
        }
        setLoading(false);
        setLoadError(null);
      },
      (err) => {
        console.error("[Business] snapshot error:", err);
        setLoadError("Could not load business details. Check your connection.");
        setLoading(false);
      }
    );

    return () => unsub();
  }, [dirty]);

  const update = useCallback(
    (field: keyof BusinessData, value: string) => {
      setData((prev) => ({ ...prev, [field]: value }));
      setDirty(true);
    },
    []
  );

  const handleSave = useCallback(async () => {
    setSaveStatus("saving");
    setSaveError(null);
    try {
      await setDoc(
        doc(db, DOC_REF, DOC_ID),
        {
          businessName: data.businessName.trim(),
          address: data.address.trim(),
          phone: data.phone.trim(),
          email: data.email.trim(),
          logoUrl: data.logoUrl.trim(),
          updatedAt: serverTimestamp(),
        },
        { merge: true }
      );
      setSaveStatus("saved");
      setDirty(false);
      setTimeout(() => setSaveStatus("idle"), 3500);
    } catch (e) {
      console.error("[Business] save error:", e);
      setSaveStatus("error");
      setSaveError("Save failed. Check your connection and try again.");
      setTimeout(() => setSaveStatus("idle"), 5000);
    }
  }, [data]);

  if (loading) {
    return (
      <>
        <Header title="Business Information" />
        <div className="p-6 flex items-center justify-center min-h-[50vh]">
          <div className="flex flex-col items-center gap-3">
            <Loader2 className="w-8 h-8 text-blue-600 animate-spin" />
            <p className="text-sm text-slate-500">Loading business details…</p>
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      <Header title="Business Information" />
      <div className="p-6 space-y-6 max-w-2xl">
        {loadError && (
          <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 flex items-center gap-2">
            <AlertCircle size={16} className="shrink-0" />
            {loadError}
          </div>
        )}

        {/* Business Details Card */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6">
          <div className="flex items-start gap-3 mb-6">
            <div className="p-2 rounded-xl bg-blue-50">
              <Store size={20} className="text-blue-600" />
            </div>
            <div>
              <h2 className="font-semibold text-slate-800">Business Details</h2>
              <p className="text-sm text-slate-500 mt-0.5">
                This information appears on receipts and syncs with the POS app.
              </p>
            </div>
          </div>

          <div className="space-y-5">
            {/* Business Name */}
            <div>
              <label
                htmlFor="businessName"
                className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-1.5"
              >
                <Store size={14} className="text-slate-400" />
                Business Name
              </label>
              <input
                id="businessName"
                value={data.businessName}
                onChange={(e) => update("businessName", e.target.value)}
                placeholder="My Restaurant"
                className="w-full px-4 py-2.5 rounded-xl border border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none text-slate-800 placeholder:text-slate-400 transition-colors"
              />
            </div>

            {/* Address */}
            <div>
              <label
                htmlFor="address"
                className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-1.5"
              >
                <MapPin size={14} className="text-slate-400" />
                Address
              </label>
              <textarea
                id="address"
                value={data.address}
                onChange={(e) => update("address", e.target.value)}
                placeholder={"123 Main Street\nCity, ST 12345"}
                rows={3}
                className="w-full px-4 py-2.5 rounded-xl border border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none text-slate-800 placeholder:text-slate-400 resize-y min-h-[88px] transition-colors"
              />
            </div>

            {/* Phone */}
            <div>
              <label
                htmlFor="phone"
                className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-1.5"
              >
                <Phone size={14} className="text-slate-400" />
                Phone
              </label>
              <input
                id="phone"
                value={data.phone}
                onChange={(e) => update("phone", e.target.value)}
                placeholder="(555) 123-4567"
                type="tel"
                className="w-full px-4 py-2.5 rounded-xl border border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none text-slate-800 placeholder:text-slate-400 transition-colors"
              />
            </div>

            {/* Email */}
            <div>
              <label
                htmlFor="email"
                className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-1.5"
              >
                <Mail size={14} className="text-slate-400" />
                Email
              </label>
              <input
                id="email"
                value={data.email}
                onChange={(e) => update("email", e.target.value)}
                placeholder="contact@mybusiness.com"
                type="email"
                className="w-full px-4 py-2.5 rounded-xl border border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none text-slate-800 placeholder:text-slate-400 transition-colors"
              />
            </div>

            {/* Logo URL */}
            <div>
              <label
                htmlFor="logoUrl"
                className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-1.5"
              >
                <Image size={14} className="text-slate-400" />
                Logo URL
                <span className="text-slate-400 font-normal">(optional)</span>
              </label>
              <input
                id="logoUrl"
                value={data.logoUrl}
                onChange={(e) => update("logoUrl", e.target.value)}
                placeholder="https://…"
                type="url"
                className="w-full px-4 py-2.5 rounded-xl border border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none text-slate-800 placeholder:text-slate-400 transition-colors"
              />
              {data.logoUrl.trim() && (
                <div className="mt-3 p-3 rounded-xl bg-slate-50 border border-slate-100 flex items-center gap-3">
                  <img
                    src={data.logoUrl.trim()}
                    alt="Logo preview"
                    className="w-12 h-12 rounded-lg object-contain bg-white border border-slate-200"
                    onError={(e) => {
                      (e.target as HTMLImageElement).style.display = "none";
                    }}
                  />
                  <span className="text-xs text-slate-500">Logo preview</span>
                </div>
              )}
            </div>
          </div>

          {/* Save bar */}
          <div className="mt-8 pt-6 border-t border-slate-100 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
            <div className="min-h-[24px] text-sm">
              {saveStatus === "saving" && (
                <span className="text-slate-500 flex items-center gap-2">
                  <Loader2 size={16} className="animate-spin shrink-0" />
                  Saving to Firebase…
                </span>
              )}
              {saveStatus === "saved" && (
                <span className="text-green-600 font-medium flex items-center gap-1.5">
                  <Check size={16} />
                  Saved successfully — changes will sync to the POS app
                </span>
              )}
              {saveStatus === "error" && saveError && (
                <span className="text-red-600 flex items-center gap-1.5">
                  <AlertCircle size={16} />
                  {saveError}
                </span>
              )}
              {saveStatus === "idle" && dirty && (
                <span className="text-amber-600 font-medium">
                  You have unsaved changes
                </span>
              )}
            </div>
            <button
              type="button"
              onClick={handleSave}
              disabled={saveStatus === "saving" || !dirty}
              className={`inline-flex items-center justify-center gap-2 px-6 py-2.5 rounded-xl text-sm font-semibold text-white transition-all ${
                saveStatus === "saving" || !dirty
                  ? "bg-slate-300 cursor-not-allowed"
                  : "bg-blue-600 hover:bg-blue-700 shadow-sm hover:shadow-md"
              }`}
            >
              <Save size={16} />
              {saveStatus === "saving" ? "Saving…" : "Save Changes"}
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
