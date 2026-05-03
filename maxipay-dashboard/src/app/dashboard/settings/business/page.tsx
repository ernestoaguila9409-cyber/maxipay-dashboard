"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Header from "@/components/Header";
import { db, storage } from "@/firebase/firebaseConfig";
import { doc, getDoc, onSnapshot, setDoc, serverTimestamp } from "firebase/firestore";
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
  Receipt,
  Settings2,
  Search,
} from "lucide-react";
import { ImageSearchModal } from "@/components/menu-item-image/ImageSearchModal";
import {
  thermalCharsPerLine,
  wrapThermalText,
} from "@/lib/receiptThermal";
import { slugify, ONLINE_ORDERING_SETTINGS_DOC } from "@/lib/onlineOrderingShared";

/* ══════════════════════════════════════════════
   Types — mirrors Android ReceiptSettings.kt
   ══════════════════════════════════════════════ */

interface BusinessData {
  businessName: string;
  address: string;
  phone: string;
  email: string;
  logoUrl: string;
}

interface PrintSettings {
  showServerName: boolean;
  showDateTime: boolean;
  showLogo: boolean;
  showEmail: boolean;
  boldBizName: boolean;
  boldAddress: boolean;
  boldOrderInfo: boolean;
  boldItems: boolean;
  boldTotals: boolean;
  boldGrandTotal: boolean;
  boldFooter: boolean;
  fontSizeBizName: number;   // 0=Normal 1=Large 2=X-Large
  fontSizeAddress: number;
  fontSizeOrderInfo: number;
  fontSizeItems: number;
  fontSizeTotals: number;
  fontSizeGrandTotal: number;
  fontSizeFooter: number;
}

const EMPTY: BusinessData = {
  businessName: "",
  address: "",
  phone: "",
  email: "",
  logoUrl: "",
};

const DEFAULT_PRINT: PrintSettings = {
  showServerName: true,
  showDateTime: true,
  showLogo: true,
  showEmail: false,
  boldBizName: true,
  boldAddress: false,
  boldOrderInfo: true,
  boldItems: false,
  boldTotals: false,
  boldGrandTotal: true,
  boldFooter: false,
  fontSizeBizName: 2,
  fontSizeAddress: 2,
  fontSizeOrderInfo: 2,
  fontSizeItems: 0,
  fontSizeTotals: 0,
  fontSizeGrandTotal: 1,
  fontSizeFooter: 0,
};

const FONT_LABELS = ["Normal", "Large", "X-Large"] as const;
const FONT_PX: Record<number, number> = { 0: 12, 1: 15, 2: 19 };
/** Makes the receipt preview easier to read on large screens (does not affect POS print). */
const PREVIEW_FONT_SCALE = 1.28;

const DOC_REF = "Settings";
const DOC_ID = "businessInfo";
const MAX_LOGO_WIDTH = 512;

type SaveStatus = "idle" | "saving" | "saved" | "error";
type RightTab = "preview" | "print";
type ReceiptVariant = "original" | "refund" | "void";

/* ── image resize ── */

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
        (blob) =>
          blob ? resolve(blob) : reject(new Error("Compress failed")),
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

/* ══════════════════════════════════════════════
   Small reusable sub-components
   ══════════════════════════════════════════════ */

function Toggle({
  checked,
  onChange,
  label,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  label: string;
}) {
  return (
    <label className="flex items-center justify-between gap-3 cursor-pointer group py-1">
      <span className="text-[13px] text-slate-700 group-hover:text-slate-900 transition-colors">
        {label}
      </span>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors ${
          checked ? "bg-blue-600" : "bg-slate-200"
        }`}
      >
        <span
          className={`inline-block h-3.5 w-3.5 rounded-full bg-white shadow transition-transform ${
            checked ? "translate-x-[18px]" : "translate-x-[3px]"
          }`}
        />
      </button>
    </label>
  );
}

function FontSizePicker({
  value,
  onChange,
  label,
}: {
  value: number;
  onChange: (v: number) => void;
  label: string;
}) {
  return (
    <div className="py-1">
      <span className="text-[13px] text-slate-700 block mb-1.5">{label}</span>
      <div className="flex gap-1.5 bg-slate-100 rounded-lg p-0.5">
        {FONT_LABELS.map((lbl, i) => (
          <button
            key={i}
            type="button"
            onClick={() => onChange(i)}
            className={`flex-1 py-1 text-[11px] font-medium rounded-md transition-all ${
              value === i
                ? "bg-white text-slate-800 shadow-sm"
                : "text-slate-500 hover:text-slate-700"
            }`}
          >
            {lbl}
          </button>
        ))}
      </div>
    </div>
  );
}

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <p className="text-[11px] font-semibold text-slate-400 uppercase tracking-wider mb-2">
        {title}
      </p>
      <div className="space-y-1">{children}</div>
    </div>
  );
}

/* ══════════════════════════════════════════════
   Main page component
   ══════════════════════════════════════════════ */

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
  const [imageSearchOpen, setImageSearchOpen] = useState(false);
  const [imgPreview, setImgPreview] = useState<string | null>(null);
  const [resizedBlob, setResizedBlob] = useState<Blob | null>(null);

  const [rightTab, setRightTab] = useState<RightTab>("preview");
  const [receiptVariant, setReceiptVariant] =
    useState<ReceiptVariant>("original");
  const [ps, setPs] = useState<PrintSettings>(DEFAULT_PRINT);

  const [psDirty, setPsDirty] = useState(false);
  const psTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const cameraInputRef = useRef<HTMLInputElement>(null);

  /* ── Firestore: business info ── */

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

  /* ── Firestore: receipt / print settings ── */

  useEffect(() => {
    const unsub = onSnapshot(
      doc(db, DOC_REF, "receiptSettings"),
      (snap) => {
        if (snap.exists() && !psDirty) {
          const d = snap.data();
          setPs({
            showServerName: d.showServerName ?? DEFAULT_PRINT.showServerName,
            showDateTime: d.showDateTime ?? DEFAULT_PRINT.showDateTime,
            showLogo: d.showLogo ?? DEFAULT_PRINT.showLogo,
            showEmail: d.showEmail ?? DEFAULT_PRINT.showEmail,
            boldBizName: d.boldBizName ?? DEFAULT_PRINT.boldBizName,
            boldAddress: d.boldAddress ?? DEFAULT_PRINT.boldAddress,
            boldOrderInfo: d.boldOrderInfo ?? DEFAULT_PRINT.boldOrderInfo,
            boldItems: d.boldItems ?? DEFAULT_PRINT.boldItems,
            boldTotals: d.boldTotals ?? DEFAULT_PRINT.boldTotals,
            boldGrandTotal: d.boldGrandTotal ?? DEFAULT_PRINT.boldGrandTotal,
            boldFooter: d.boldFooter ?? DEFAULT_PRINT.boldFooter,
            fontSizeBizName: d.fontSizeBizName ?? DEFAULT_PRINT.fontSizeBizName,
            fontSizeAddress: d.fontSizeAddress ?? DEFAULT_PRINT.fontSizeAddress,
            fontSizeOrderInfo: d.fontSizeOrderInfo ?? DEFAULT_PRINT.fontSizeOrderInfo,
            fontSizeItems: d.fontSizeItems ?? DEFAULT_PRINT.fontSizeItems,
            fontSizeTotals: d.fontSizeTotals ?? DEFAULT_PRINT.fontSizeTotals,
            fontSizeGrandTotal: d.fontSizeGrandTotal ?? DEFAULT_PRINT.fontSizeGrandTotal,
            fontSizeFooter: d.fontSizeFooter ?? DEFAULT_PRINT.fontSizeFooter,
          });
        }
      },
      (err) => {
        console.error("[PrintSettings] snapshot error:", err);
      }
    );
    return () => unsub();
  }, [psDirty]);

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

      const ooRef = doc(db, DOC_REF, ONLINE_ORDERING_SETTINGS_DOC);
      const ooSnap = await getDoc(ooRef);
      const existingSlug = ooSnap.exists() ? ooSnap.get("onlineOrderingSlug") : null;
      if (!existingSlug || typeof existingSlug !== "string" || !existingSlug.trim()) {
        const generated = slugify(data.businessName.trim());
        if (generated) {
          await setDoc(ooRef, { onlineOrderingSlug: generated, updatedAt: serverTimestamp() }, { merge: true });
        }
      }

      setSaveStatus("saved");
      setDirty(false);
      setTimeout(() => setSaveStatus("idle"), 3500);
    } catch {
      setSaveStatus("error");
      setSaveError("Save failed. Check your connection and try again.");
      setTimeout(() => setSaveStatus("idle"), 5000);
    }
  }, [data]);

  /* ── logo ── */

  const processFile = useCallback(async (file: File) => {
    setUploadError(null);
    try {
      const blob = await resizeImage(file);
      setImgPreview(URL.createObjectURL(blob));
      setResizedBlob(blob);
    } catch {
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
    } catch {
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
    setImgPreview(null);
    setResizedBlob(null);
    setUploadError(null);
  }, []);

  const getIdToken = useCallback(async () => {
    if (!user) throw new Error("Not signed in");
    return user.getIdToken();
  }, [user]);

  const savePsToFirestore = useCallback((updated: PrintSettings) => {
    if (psTimerRef.current) clearTimeout(psTimerRef.current);
    setPsDirty(true);
    psTimerRef.current = setTimeout(async () => {
      try {
        await setDoc(doc(db, DOC_REF, "receiptSettings"), updated, { merge: true });
        setPsDirty(false);
      } catch (e) {
        console.error("[PrintSettings] save error:", e);
        setPsDirty(false);
      }
    }, 800);
  }, []);

  const pSet = useCallback(
    <K extends keyof PrintSettings>(k: K, v: PrintSettings[K]) =>
      setPs((prev) => {
        const next = { ...prev, [k]: v };
        savePsToFirestore(next);
        return next;
      }),
    [savePsToFirestore]
  );

  /* ── loading ── */

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

  /* ── derived ── */

  const hasLogo = data.logoUrl.trim().length > 0;
  const displayName = data.businessName.trim() || "Your Business";
  const displayAddress = data.address.trim() || "123 Main Street";
  const displayPhone = data.phone.trim() || "(555) 123-4567";
  const displayEmail = data.email.trim();

  const variantLabel =
    receiptVariant === "refund"
      ? "REFUND"
      : receiptVariant === "void"
        ? "VOID"
        : "RECEIPT";
  const variantColor =
    receiptVariant === "refund"
      ? "text-amber-600"
      : receiptVariant === "void"
        ? "text-red-600"
        : "text-slate-700";

  const px = (key: number) =>
    Math.round((FONT_PX[key] ?? 12) * PREVIEW_FONT_SCALE);

  const bizNameChars = thermalCharsPerLine(ps.fontSizeBizName);
  const addrChars = thermalCharsPerLine(ps.fontSizeAddress);

  const nameLines = wrapThermalText(displayName, bizNameChars);
  const addressLines = wrapThermalText(displayAddress, addrChars);
  const phoneLines = wrapThermalText(displayPhone, addrChars);
  const emailLines = displayEmail.trim()
    ? wrapThermalText(displayEmail.trim(), thermalCharsPerLine(0))
    : [];

  const addressLinesRaw = data.address.split(/\r?\n/);
  const addressLongestLineLen = addressLinesRaw.reduce(
    (m, l) => Math.max(m, l.length),
    0
  );
  const addressLineOverLimit = addressLongestLineLen > addrChars;

  /* ══════════════════════════════════════════════
     Render
     ══════════════════════════════════════════════ */

  return (
    <>
      <Header title="Business Information" />

      <div className="p-6 flex flex-col xl:flex-row gap-6 items-start">
        {/* ════════ LEFT — FORM (55%) ════════ */}
        <div className="w-full xl:w-[55%] min-w-0">
          {loadError && (
            <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 flex items-center gap-2 mb-6">
              <AlertCircle size={16} className="shrink-0" />
              {loadError}
            </div>
          )}

          <div className="bg-white rounded-2xl shadow-sm border border-slate-100 flex flex-col">
            <div className="p-6 pb-0">
              <div className="flex items-start gap-3 mb-6">
                <div className="p-2 rounded-xl bg-blue-50">
                  <Store size={20} className="text-blue-600" />
                </div>
                <div>
                  <h2 className="font-semibold text-slate-800">
                    Business Details
                  </h2>
                  <p className="text-sm text-slate-500 mt-0.5">
                    This information appears on receipts and syncs with the POS
                    app.
                  </p>
                </div>
              </div>
            </div>

            <div className="px-6 space-y-5 flex-1">
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
                  rows={2}
                  className="w-full px-4 py-2.5 rounded-xl border border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none text-slate-800 placeholder:text-slate-400 resize-y min-h-[64px] transition-colors"
                />
                <p className="mt-1.5 text-[11px] text-slate-400 leading-snug">
                  Receipt width matches the POS thermal printer (~{addrChars}{" "}
                  characters per line
                  {ps.fontSizeAddress === 2
                    ? " at X-Large address font"
                    : " at Normal/Large address font"}
                  ). Long lines wrap on the preview like on paper.
                </p>
                {addressLineOverLimit ? (
                  <p className="mt-1 text-[11px] text-amber-700 flex items-start gap-1.5">
                    <AlertCircle
                      size={14}
                      className="shrink-0 mt-0.5"
                      aria-hidden
                    />
                    Longest line is {addressLongestLineLen} characters — it will
                    wrap to multiple lines on the receipt (max ~{addrChars} chars
                    per line for this font size).
                  </p>
                ) : null}
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
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
              </div>

              <div>
                <label className="flex items-center gap-2 text-sm font-medium text-slate-700 mb-1.5">
                  <ImagePlus size={14} className="text-slate-400" />
                  Logo
                  <span className="text-slate-400 font-normal">(optional)</span>
                </label>
                {hasLogo ? (
                  <div className="flex items-center gap-4 p-3 rounded-xl bg-slate-50 border border-slate-100">
                    <img
                      src={data.logoUrl.trim()}
                      alt="Business logo"
                      className="w-14 h-14 rounded-lg object-contain bg-white border border-slate-200 p-1"
                      referrerPolicy="no-referrer"
                      onError={(e) => {
                        console.warn("[Business] logo preview failed", data.logoUrl.trim());
                        (e.target as HTMLImageElement).style.opacity = "0.35";
                      }}
                    />
                    <div className="flex gap-3">
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

            {/* Sticky save */}
            <div className="sticky bottom-0 bg-white border-t border-slate-100 px-6 py-4 rounded-b-2xl mt-6">
              <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
                <div className="min-h-[20px] text-sm">
                  {saveStatus === "saving" && (
                    <span className="text-slate-500 flex items-center gap-2">
                      <Loader2
                        size={14}
                        className="animate-spin shrink-0"
                      />
                      Saving…
                    </span>
                  )}
                  {saveStatus === "saved" && (
                    <span className="text-green-600 font-medium flex items-center gap-1.5">
                      <Check size={14} />
                      Saved — syncing to POS
                    </span>
                  )}
                  {saveStatus === "error" && saveError && (
                    <span className="text-red-600 flex items-center gap-1.5">
                      <AlertCircle size={14} />
                      {saveError}
                    </span>
                  )}
                  {saveStatus === "idle" && dirty && (
                    <span className="text-amber-600 font-medium text-xs">
                      Unsaved changes
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
        </div>

        {/* ════════ RIGHT — TABS (45%) ════════ */}
        <div className="w-full xl:w-[45%] min-w-0">
          <div className="xl:sticky xl:top-6">
            <div className="bg-white rounded-2xl shadow-sm border border-slate-100 overflow-hidden">
              {/* Tab bar */}
              <div className="flex border-b border-slate-100">
                <button
                  type="button"
                  onClick={() => setRightTab("preview")}
                  className={`flex-1 flex items-center justify-center gap-2 px-4 py-3 text-sm font-medium transition-colors ${
                    rightTab === "preview"
                      ? "text-blue-600 border-b-2 border-blue-600 bg-blue-50/40"
                      : "text-slate-500 hover:text-slate-700 hover:bg-slate-50"
                  }`}
                >
                  <Receipt size={16} />
                  Receipt Preview
                </button>
                <button
                  type="button"
                  onClick={() => setRightTab("print")}
                  className={`flex-1 flex items-center justify-center gap-2 px-4 py-3 text-sm font-medium transition-colors ${
                    rightTab === "print"
                      ? "text-blue-600 border-b-2 border-blue-600 bg-blue-50/40"
                      : "text-slate-500 hover:text-slate-700 hover:bg-slate-50"
                  }`}
                >
                  <Settings2 size={16} />
                  Print Settings
                </button>
              </div>

              {/* ── Receipt Preview Tab ── */}
              {rightTab === "preview" ? (
                <div className="p-5 sm:p-6">
                  {/* Variant selector */}
                  <div className="flex gap-1.5 mb-5 bg-slate-100 rounded-lg p-1">
                    {(
                      [
                        ["original", "Original"],
                        ["refund", "Refund"],
                        ["void", "Void"],
                      ] as const
                    ).map(([key, label]) => (
                      <button
                        key={key}
                        type="button"
                        onClick={() => setReceiptVariant(key)}
                        className={`flex-1 py-1.5 text-xs font-medium rounded-md transition-all ${
                          receiptVariant === key
                            ? "bg-white text-slate-800 shadow-sm"
                            : "text-slate-500 hover:text-slate-700"
                        }`}
                      >
                        {label}
                      </button>
                    ))}
                  </div>

                  {/* Receipt paper — wider + scaled fonts so preview is readable */}
                  <div className="flex justify-center overflow-x-auto pb-4">
                    <div
                      className="w-full max-w-[min(100%,440px)] min-w-[280px] bg-white rounded-lg shadow-[0_2px_20px_rgba(0,0,0,0.08)] border border-slate-100 px-8 py-9 sm:px-10 sm:py-10 text-center overflow-hidden"
                      style={{
                        fontFamily:
                          "'Courier New', Courier, monospace",
                      }}
                    >
                      {/* Logo — show whenever a URL is set; print toggle only affects printed receipts */}
                      {hasLogo && (
                        <div className="flex flex-col items-center mb-3 gap-1">
                          <div className="flex justify-center w-full">
                            <img
                              src={data.logoUrl.trim()}
                              alt="Logo"
                              className="h-16 sm:h-[4.5rem] max-w-[168px] object-contain"
                              referrerPolicy="no-referrer"
                              onError={(e) => {
                                const el = e.target as HTMLImageElement;
                                el.style.display = "none";
                              }}
                            />
                          </div>
                          {!ps.showLogo && (
                            <p className="text-[10px] text-amber-700 max-w-[200px] leading-snug">
                              &ldquo;Show logo&rdquo; is off in Print Settings &mdash; receipts won&apos;t print the logo until you turn it on.
                            </p>
                          )}
                        </div>
                      )}

                      {/* Business name — wrapped to thermal line width */}
                      <div
                        className="mx-auto"
                        style={{ maxWidth: `${bizNameChars}ch` }}
                      >
                        {nameLines.map((line, i) => (
                          <p
                            key={`name-${i}`}
                            className="text-slate-800 leading-tight [overflow-wrap:anywhere] break-words"
                            style={{
                              fontSize: `${px(ps.fontSizeBizName)}px`,
                              fontWeight: ps.boldBizName ? 700 : 400,
                            }}
                          >
                            {line || "\u00a0"}
                          </p>
                        ))}
                      </div>

                      {/* Address + phone — wrapped like ESC/POS */}
                      <div
                        className="text-slate-500 mt-1 mx-auto"
                        style={{ maxWidth: `${addrChars}ch` }}
                      >
                        {addressLines.map((line, i) => (
                          <p
                            key={`addr-${i}`}
                            className="leading-snug [overflow-wrap:anywhere] break-words"
                            style={{
                              fontSize: `${px(ps.fontSizeAddress)}px`,
                              fontWeight: ps.boldAddress ? 700 : 400,
                            }}
                          >
                            {line || "\u00a0"}
                          </p>
                        ))}
                        {phoneLines.map((line, i) => (
                          <p
                            key={`ph-${i}`}
                            className="leading-snug [overflow-wrap:anywhere] break-words"
                            style={{
                              fontSize: `${px(ps.fontSizeAddress)}px`,
                              fontWeight: ps.boldAddress ? 700 : 400,
                            }}
                          >
                            {line || "\u00a0"}
                          </p>
                        ))}
                      </div>

                      {/* Email */}
                      {ps.showEmail && displayEmail && (
                        <div
                          className="text-slate-400 mt-0.5 mx-auto"
                          style={{
                            maxWidth: `${thermalCharsPerLine(0)}ch`,
                            fontSize: `${Math.round(11 * PREVIEW_FONT_SCALE)}px`,
                          }}
                        >
                          {emailLines.map((line, i) => (
                            <p
                              key={`em-${i}`}
                              className="[overflow-wrap:anywhere] break-words"
                            >
                              {line || "\u00a0"}
                            </p>
                          ))}
                        </div>
                      )}

                      {/* Receipt label */}
                      <p
                        className={`mt-4 mb-0.5 ${variantColor}`}
                        style={{
                          fontSize: `${px(ps.fontSizeOrderInfo)}px`,
                          fontWeight: ps.boldOrderInfo ? 700 : 400,
                        }}
                      >
                        {variantLabel}
                      </p>

                      {/* Order info */}
                      <div
                        className="text-slate-500 leading-relaxed"
                        style={{
                          fontSize: `${Math.max(
                            px(ps.fontSizeOrderInfo) -
                              Math.round(3 * PREVIEW_FONT_SCALE),
                            Math.round(10 * PREVIEW_FONT_SCALE),
                          )}px`,
                          fontWeight: ps.boldOrderInfo ? 600 : 400,
                        }}
                      >
                        <p>Order #1042</p>
                        <p>Type: Dine In</p>
                        {ps.showServerName && <p>Server: Maria</p>}
                        {ps.showDateTime && <p>03/29/2026 12:32 AM</p>}
                      </div>

                      {/* Divider */}
                      <div className="border-t border-dashed border-slate-200 my-3" />

                      {/* Items */}
                      <div
                        className="text-slate-700 text-left space-y-px"
                        style={{
                          fontSize: `${px(ps.fontSizeItems)}px`,
                          fontWeight: ps.boldItems ? 600 : 400,
                        }}
                      >
                        <div className="flex justify-between">
                          <span>2x Burger</span>
                          <span>$19.98</span>
                        </div>
                        <div className="flex justify-between pl-3 text-slate-400 font-normal">
                          <span>+ Extra Cheese</span>
                          <span>$1.50</span>
                        </div>
                        <div className="flex justify-between">
                          <span>1x Caesar Salad</span>
                          <span>$12.50</span>
                        </div>
                        <div className="flex justify-between">
                          <span>1x Fries</span>
                          <span>$5.99</span>
                        </div>
                        <div className="flex justify-between">
                          <span>2x Iced Tea</span>
                          <span>$7.98</span>
                        </div>
                        <div className="flex justify-between">
                          <span>1x Chocolate Cake</span>
                          <span>$8.50</span>
                        </div>
                      </div>

                      {/* Divider */}
                      <div className="border-t border-dashed border-slate-200 my-3" />

                      {/* Totals */}
                      <div
                        className="text-slate-700 text-left space-y-px"
                        style={{
                          fontSize: `${px(ps.fontSizeTotals)}px`,
                          fontWeight: ps.boldTotals ? 600 : 400,
                        }}
                      >
                        <div className="flex justify-between">
                          <span>Subtotal</span>
                          <span>$56.45</span>
                        </div>
                        <div className="flex justify-between">
                          <span>Tax (8.25%)</span>
                          <span>$4.66</span>
                        </div>
                        <div className="flex justify-between">
                          <span>Tip</span>
                          <span>$8.47</span>
                        </div>
                      </div>

                      {/* Grand total */}
                      <div className="border-t-2 border-slate-300 mt-3 pt-2">
                        <div
                          className="flex justify-between text-slate-800 text-left"
                          style={{
                            fontSize: `${px(ps.fontSizeGrandTotal)}px`,
                            fontWeight: ps.boldGrandTotal ? 700 : 400,
                          }}
                        >
                          <span>TOTAL</span>
                          <span>$69.58</span>
                        </div>
                      </div>

                      {/* Payment */}
                      <div
                        className="text-slate-500 mt-4 space-y-0.5"
                        style={{
                          fontSize: `${Math.round(10 * PREVIEW_FONT_SCALE)}px`,
                        }}
                      >
                        <p>Visa **** 1234</p>
                        <p>Auth: 123456 &middot; Type: Credit</p>
                      </div>

                      {/* Footer */}
                      <p
                        className="text-slate-400 mt-5 italic"
                        style={{
                          fontSize: `${px(ps.fontSizeFooter)}px`,
                          fontWeight: ps.boldFooter ? 600 : 400,
                        }}
                      >
                        Thank you for dining with us!
                      </p>
                    </div>
                  </div>
                </div>
              ) : (
                /* ── Print Settings Tab ── */
                <div className="p-5 space-y-5 max-h-[calc(100vh-120px)] overflow-y-auto">
                  {/* Display Options */}
                  <Section title="Display Options">
                    <Toggle
                      label="Show Server Name"
                      checked={ps.showServerName}
                      onChange={(v) => pSet("showServerName", v)}
                    />
                    <Toggle
                      label="Show Date/Time"
                      checked={ps.showDateTime}
                      onChange={(v) => pSet("showDateTime", v)}
                    />
                    <Toggle
                      label="Show Logo"
                      checked={ps.showLogo}
                      onChange={(v) => pSet("showLogo", v)}
                    />
                    <Toggle
                      label="Show Email"
                      checked={ps.showEmail}
                      onChange={(v) => pSet("showEmail", v)}
                    />
                  </Section>

                  <div className="border-t border-slate-100" />

                  {/* Bold */}
                  <Section title="Bold">
                    <Toggle
                      label="Business Name"
                      checked={ps.boldBizName}
                      onChange={(v) => pSet("boldBizName", v)}
                    />
                    <Toggle
                      label="Address"
                      checked={ps.boldAddress}
                      onChange={(v) => pSet("boldAddress", v)}
                    />
                    <Toggle
                      label="Order Info"
                      checked={ps.boldOrderInfo}
                      onChange={(v) => pSet("boldOrderInfo", v)}
                    />
                    <Toggle
                      label="Items"
                      checked={ps.boldItems}
                      onChange={(v) => pSet("boldItems", v)}
                    />
                    <Toggle
                      label="Totals"
                      checked={ps.boldTotals}
                      onChange={(v) => pSet("boldTotals", v)}
                    />
                    <Toggle
                      label="Grand Total"
                      checked={ps.boldGrandTotal}
                      onChange={(v) => pSet("boldGrandTotal", v)}
                    />
                    <Toggle
                      label="Footer"
                      checked={ps.boldFooter}
                      onChange={(v) => pSet("boldFooter", v)}
                    />
                  </Section>

                  <div className="border-t border-slate-100" />

                  {/* Font Size */}
                  <Section title="Font Size">
                    <FontSizePicker
                      label="Business Name"
                      value={ps.fontSizeBizName}
                      onChange={(v) => pSet("fontSizeBizName", v)}
                    />
                    <FontSizePicker
                      label="Address"
                      value={ps.fontSizeAddress}
                      onChange={(v) => pSet("fontSizeAddress", v)}
                    />
                    <FontSizePicker
                      label="Order Info"
                      value={ps.fontSizeOrderInfo}
                      onChange={(v) => pSet("fontSizeOrderInfo", v)}
                    />
                    <FontSizePicker
                      label="Items"
                      value={ps.fontSizeItems}
                      onChange={(v) => pSet("fontSizeItems", v)}
                    />
                    <FontSizePicker
                      label="Totals"
                      value={ps.fontSizeTotals}
                      onChange={(v) => pSet("fontSizeTotals", v)}
                    />
                    <FontSizePicker
                      label="Grand Total"
                      value={ps.fontSizeGrandTotal}
                      onChange={(v) => pSet("fontSizeGrandTotal", v)}
                    />
                    <FontSizePicker
                      label="Footer"
                      value={ps.fontSizeFooter}
                      onChange={(v) => pSet("fontSizeFooter", v)}
                    />
                  </Section>

                  <div className="pt-1 pb-2">
                    <p className="text-[11px] text-slate-400 text-center">
                      Changes auto-save and sync with the POS app in real-time.
                    </p>
                  </div>
                </div>
              )}
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
      {user && (
        <ImageSearchModal
          mode="businessLogo"
          open={imageSearchOpen}
          onClose={() => setImageSearchOpen(false)}
          businessName={data.businessName.trim() || "restaurant"}
          getIdToken={getIdToken}
          onCommitted={async (url) => {
            setData((prev) => ({ ...prev, logoUrl: url }));
            setDirty(true);
          }}
        />
      )}

      {modalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={!uploading ? closeModal : undefined}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md overflow-hidden">
            <div className="flex items-center justify-between px-6 pt-5 pb-3">
              <h3 className="text-lg font-semibold text-slate-800">
                {imgPreview ? "Preview Logo" : "Add Logo"}
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
              {!imgPreview ? (
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
                      <p className="text-sm font-medium text-slate-700">
                        Upload from device
                      </p>
                      <p className="text-xs text-slate-400">
                        JPG, PNG, or WebP
                      </p>
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
                      <p className="text-sm font-medium text-slate-700">
                        Take a photo
                      </p>
                      <p className="text-xs text-slate-400">
                        Use your device camera
                      </p>
                    </div>
                  </button>
                  <button
                    type="button"
                    disabled={!user}
                    onClick={() => {
                      setModalOpen(false);
                      setImageSearchOpen(true);
                    }}
                    className="w-full flex items-center gap-3 px-4 py-3.5 rounded-xl border border-slate-200 text-left hover:border-blue-400 hover:bg-blue-50/50 transition-colors group disabled:opacity-50 disabled:pointer-events-none"
                  >
                    <div className="p-2 rounded-lg bg-emerald-50 group-hover:bg-emerald-100 transition-colors">
                      <Search size={20} className="text-emerald-600" />
                    </div>
                    <div>
                      <p className="text-sm font-medium text-slate-700">
                        Search for image
                      </p>
                      <p className="text-xs text-slate-400">
                        Describe what you want &mdash; AI suggests a query, then pick from Pexels (saved to your
                        storage).
                      </p>
                    </div>
                  </button>
                </div>
              ) : (
                <div className="space-y-4">
                  <div className="flex justify-center p-4 rounded-xl bg-slate-50 border border-slate-100">
                    <img
                      src={imgPreview}
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
                        setImgPreview(null);
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
