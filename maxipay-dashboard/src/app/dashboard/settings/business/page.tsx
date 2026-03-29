"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Header from "@/components/Header";
import { db, storage } from "@/firebase/firebaseConfig";
import { doc, onSnapshot, setDoc, serverTimestamp } from "firebase/firestore";
import { ref, uploadBytes, getDownloadURL } from "firebase/storage";
import { useAuth } from "@/context/AuthContext";
import {
  Save,
  Check,
  Loader2,
  Store,
  MapPin,
  Phone,
  Mail,
  ImagePlus,
  Camera,
  Upload,
  Trash2,
  X,
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
const MAX_LOGO_WIDTH = 512;

type SaveStatus = "idle" | "saving" | "saved" | "error";

function resizeImage(file: File): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const img = new window.Image();
    const url = URL.createObjectURL(file);

    img.onload = () => {
      URL.revokeObjectURL(url);

      let w = img.naturalWidth;
      let h = img.naturalHeight;

      if (w > MAX_LOGO_WIDTH) {
        h = Math.round(h * (MAX_LOGO_WIDTH / w));
        w = MAX_LOGO_WIDTH;
      }

      const canvas = document.createElement("canvas");
      canvas.width = w;
      canvas.height = h;

      const ctx = canvas.getContext("2d");
      if (!ctx) return reject(new Error("Canvas context unavailable"));

      ctx.drawImage(img, 0, 0, w, h);
      canvas.toBlob(
        (blob) => {
          if (blob) resolve(blob);
          else reject(new Error("Failed to compress image"));
        },
        "image/png",
        0.85
      );
    };

    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("Could not read image file"));
    };

    img.src = url;
  });
}

export default function BusinessInformationPage() {
  const { user } = useAuth();
  const [data, setData] = useState<BusinessData>(EMPTY);
  const [loading, setLoading] = useState(true);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>("idle");
  const [saveError, setSaveError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);

  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [preview, setPreview] = useState<string | null>(null);
  const [resizedBlob, setResizedBlob] = useState<Blob | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const cameraInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
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

  const processFile = useCallback(async (file: File) => {
    setUploadError(null);
    try {
      const blob = await resizeImage(file);
      const previewUrl = URL.createObjectURL(blob);
      setPreview(previewUrl);
      setResizedBlob(blob);
    } catch (err) {
      console.error("[Business] resize error:", err);
      setUploadError("Could not process image. Try a different file.");
    }
  }, []);

  const handleFileChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (file) processFile(file);
      e.target.value = "";
    },
    [processFile]
  );

  const handleConfirmUpload = useCallback(async () => {
    if (!resizedBlob || !user) return;

    setUploading(true);
    setUploadError(null);
    try {
      const storageRef = ref(storage, `businesses/${user.uid}/logo.png`);
      await uploadBytes(storageRef, resizedBlob);
      const downloadURL = await getDownloadURL(storageRef);
      setData((prev) => ({ ...prev, logoUrl: downloadURL }));
      setDirty(true);
      closeModal();
    } catch (err) {
      console.error("[Business] logo upload error:", err);
      setUploadError("Upload failed. Please try again.");
    } finally {
      setUploading(false);
    }
  }, [resizedBlob, user]);

  const handleRemoveLogo = useCallback(() => {
    setData((prev) => ({ ...prev, logoUrl: "" }));
    setDirty(true);
  }, []);

  const closeModal = useCallback(() => {
    setModalOpen(false);
    setPreview(null);
    setResizedBlob(null);
    setUploadError(null);
  }, []);

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

  const hasLogo = data.logoUrl.trim().length > 0;
  const displayName = data.businessName.trim() || "Your Business";
  const displayAddress = data.address.trim() || "123 Main Street";
  const displayPhone = data.phone.trim() || "(555) 123-4567";

  return (
    <>
      <Header title="Business Information" />
      <div className="p-6 flex flex-col lg:flex-row gap-6">
        {/* Left column — form */}
        <div className="flex-1 min-w-0 max-w-2xl space-y-6">
          {loadError && (
            <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 flex items-center gap-2">
              <AlertCircle size={16} className="shrink-0" />
              {loadError}
            </div>
          )}

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

              {/* Logo */}
              <div>
                <label className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-1.5">
                  <ImagePlus size={14} className="text-slate-400" />
                  Logo
                  <span className="text-slate-400 font-normal">(optional)</span>
                </label>

                {hasLogo ? (
                  <div className="flex items-center gap-4 p-4 rounded-xl bg-slate-50 border border-slate-100">
                    <img
                      src={data.logoUrl.trim()}
                      alt="Business logo"
                      className="w-20 h-20 rounded-lg object-contain bg-white border border-slate-200 p-1"
                      onError={(e) => {
                        (e.target as HTMLImageElement).style.display = "none";
                      }}
                    />
                    <div className="flex flex-col gap-2">
                      <button
                        type="button"
                        onClick={() => setModalOpen(true)}
                        className="inline-flex items-center gap-1.5 text-sm font-medium text-blue-600 hover:text-blue-700 transition-colors"
                      >
                        <ImagePlus size={14} />
                        Change
                      </button>
                      <button
                        type="button"
                        onClick={handleRemoveLogo}
                        className="inline-flex items-center gap-1.5 text-sm font-medium text-red-500 hover:text-red-600 transition-colors"
                      >
                        <Trash2 size={14} />
                        Remove
                      </button>
                    </div>
                  </div>
                ) : (
                  <button
                    type="button"
                    onClick={() => setModalOpen(true)}
                    className="inline-flex items-center gap-2 px-4 py-2.5 rounded-xl border border-dashed border-slate-300 text-sm font-medium text-slate-600 hover:border-blue-400 hover:text-blue-600 hover:bg-blue-50/50 transition-colors"
                  >
                    <ImagePlus size={16} />
                    Add Logo
                  </button>
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
                disabled={saveStatus === "saving" || uploading || !dirty}
                className={`inline-flex items-center justify-center gap-2 px-6 py-2.5 rounded-xl text-sm font-semibold text-white transition-all ${
                  saveStatus === "saving" || uploading || !dirty
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

        {/* Right column — receipt preview */}
        <div className="lg:w-[340px] shrink-0">
          <div className="lg:sticky lg:top-6">
            <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-3">
              Receipt Preview
            </p>
            <div className="bg-white rounded-2xl shadow-sm border border-slate-100 overflow-hidden">
              <div
                className="px-8 py-8 text-center"
                style={{ fontFamily: "'Courier New', Courier, monospace" }}
              >
                {/* Logo */}
                {hasLogo && (
                  <div className="flex justify-center mb-3">
                    <img
                      src={data.logoUrl.trim()}
                      alt="Logo"
                      className="h-16 max-w-[160px] object-contain"
                      onError={(e) => {
                        (e.target as HTMLImageElement).style.display = "none";
                      }}
                    />
                  </div>
                )}

                {/* Business header */}
                <p className="text-lg font-bold text-slate-800 leading-tight">
                  {displayName}
                </p>
                <p className="text-xs text-slate-500 mt-1 whitespace-pre-line">
                  {displayAddress}
                </p>
                <p className="text-xs text-slate-500">{displayPhone}</p>

                {/* Receipt title */}
                <p className="text-sm font-bold text-slate-700 mt-4 mb-1">
                  RECEIPT
                </p>
                <div className="text-[11px] text-slate-500 leading-relaxed">
                  <p>Order #1042</p>
                  <p>Type: Dine In</p>
                  <p>Server: Maria</p>
                  <p>Date: 03/29/2026 12:32 AM</p>
                </div>

                {/* Divider */}
                <p className="text-slate-300 text-[10px] my-3 select-none overflow-hidden whitespace-nowrap">
                  - - - - - - - - - - - - - - - - - - - - - - - - - -
                </p>

                {/* Items */}
                <div className="text-[11px] text-slate-700 space-y-0.5 text-left">
                  <div className="flex justify-between">
                    <span>2x Burger</span><span>$19.98</span>
                  </div>
                  <div className="flex justify-between pl-3 text-slate-500">
                    <span>+ Extra Cheese</span><span>$1.50</span>
                  </div>
                  <div className="flex justify-between">
                    <span>1x Caesar Salad</span><span>$12.50</span>
                  </div>
                  <div className="flex justify-between">
                    <span>1x Fries</span><span>$5.99</span>
                  </div>
                  <div className="flex justify-between">
                    <span>2x Iced Tea</span><span>$7.98</span>
                  </div>
                  <div className="flex justify-between">
                    <span>1x Chocolate Cake</span><span>$8.50</span>
                  </div>
                </div>

                {/* Divider */}
                <p className="text-slate-300 text-[10px] my-3 select-none overflow-hidden whitespace-nowrap">
                  - - - - - - - - - - - - - - - - - - - - - - - - - -
                </p>

                {/* Totals */}
                <div className="text-[11px] text-slate-700 space-y-0.5 text-left">
                  <div className="flex justify-between">
                    <span>Subtotal</span><span>$56.45</span>
                  </div>
                  <div className="flex justify-between">
                    <span>Tax (8.25%)</span><span>$4.66</span>
                  </div>
                  <div className="flex justify-between">
                    <span>Tip</span><span>$8.47</span>
                  </div>
                </div>

                {/* Total divider */}
                <p className="text-slate-400 text-[10px] my-2 select-none overflow-hidden whitespace-nowrap font-bold">
                  ══════════════════════════════
                </p>

                {/* Grand total */}
                <div className="flex justify-between text-sm font-bold text-slate-800 text-left">
                  <span>TOTAL</span><span>$69.58</span>
                </div>

                {/* Payment info */}
                <div className="text-[11px] text-slate-500 mt-4 space-y-0.5">
                  <p>Visa **** 1234</p>
                  <p>Auth: 123456</p>
                  <p>Type: Credit</p>
                </div>

                {/* Footer */}
                <p className="text-[11px] text-slate-500 mt-5 italic">
                  Thank you for dining with us!
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Hidden file inputs */}
      <input
        ref={fileInputRef}
        type="file"
        accept="image/*"
        onChange={handleFileChange}
        className="hidden"
      />
      <input
        ref={cameraInputRef}
        type="file"
        accept="image/*"
        capture="environment"
        onChange={handleFileChange}
        className="hidden"
      />

      {/* Logo Modal */}
      {modalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={!uploading ? closeModal : undefined}
          />

          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md overflow-hidden">
            {/* Modal header */}
            <div className="flex items-center justify-between px-6 pt-5 pb-3">
              <h3 className="text-lg font-semibold text-slate-800">
                {preview ? "Preview Logo" : "Add Logo"}
              </h3>
              {!uploading && (
                <button
                  type="button"
                  onClick={closeModal}
                  className="p-1.5 rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
                >
                  <X size={20} />
                </button>
              )}
            </div>

            <div className="px-6 pb-6">
              {!preview ? (
                <div className="space-y-3">
                  <p className="text-sm text-slate-500 mb-4">
                    Choose how you want to add your business logo.
                  </p>

                  <button
                    type="button"
                    onClick={() => fileInputRef.current?.click()}
                    className="w-full flex items-center gap-3 px-4 py-3.5 rounded-xl border border-slate-200 text-left hover:border-blue-400 hover:bg-blue-50/50 transition-colors group"
                  >
                    <div className="p-2 rounded-lg bg-blue-50 group-hover:bg-blue-100 transition-colors">
                      <Upload size={20} className="text-blue-600" />
                    </div>
                    <div>
                      <p className="text-sm font-medium text-slate-700">Upload from device</p>
                      <p className="text-xs text-slate-400">JPG, PNG, or WebP</p>
                    </div>
                  </button>

                  <button
                    type="button"
                    onClick={() => cameraInputRef.current?.click()}
                    className="w-full flex items-center gap-3 px-4 py-3.5 rounded-xl border border-slate-200 text-left hover:border-blue-400 hover:bg-blue-50/50 transition-colors group"
                  >
                    <div className="p-2 rounded-lg bg-purple-50 group-hover:bg-purple-100 transition-colors">
                      <Camera size={20} className="text-purple-600" />
                    </div>
                    <div>
                      <p className="text-sm font-medium text-slate-700">Take a photo</p>
                      <p className="text-xs text-slate-400">Use your device camera</p>
                    </div>
                  </button>
                </div>
              ) : (
                <div className="space-y-4">
                  <div className="flex justify-center p-4 rounded-xl bg-slate-50 border border-slate-100">
                    <img
                      src={preview}
                      alt="Logo preview"
                      className="max-h-48 max-w-full object-contain rounded-lg"
                    />
                  </div>

                  <p className="text-xs text-center text-slate-400">
                    Resized to {MAX_LOGO_WIDTH}px max width &middot; PNG
                  </p>

                  {uploadError && (
                    <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600 flex items-center gap-2">
                      <AlertCircle size={14} className="shrink-0" />
                      {uploadError}
                    </div>
                  )}

                  <div className="flex gap-3">
                    <button
                      type="button"
                      onClick={() => {
                        setPreview(null);
                        setResizedBlob(null);
                        setUploadError(null);
                      }}
                      disabled={uploading}
                      className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                    >
                      Choose another
                    </button>
                    <button
                      type="button"
                      onClick={handleConfirmUpload}
                      disabled={uploading}
                      className={`flex-1 inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold text-white transition-all ${
                        uploading
                          ? "bg-blue-400 cursor-not-allowed"
                          : "bg-blue-600 hover:bg-blue-700"
                      }`}
                    >
                      {uploading ? (
                        <>
                          <Loader2 size={16} className="animate-spin" />
                          Uploading…
                        </>
                      ) : (
                        <>
                          <Upload size={16} />
                          Use this logo
                        </>
                      )}
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
