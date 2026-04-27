"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { doc, serverTimestamp, setDoc } from "firebase/firestore";
import {
  deleteObject,
  getDownloadURL,
  ref as storageRef,
  uploadBytes,
} from "firebase/storage";
import {
  Clock,
  Image as ImageIcon,
  Loader2,
  Power,
  Store,
  Trash2,
  Upload,
} from "lucide-react";
import { db, storage } from "@/firebase/firebaseConfig";
import { resizeImageToBlob } from "@/lib/imageUpload";
import {
  BUSINESS_INFO_DOC,
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  type OnlineOrderingSettings,
  type StoreOpenOverride,
} from "@/lib/onlineOrderingShared";

interface StorefrontSettingsManagerProps {
  settings: OnlineOrderingSettings;
  businessName: string;
  logoUrl: string;
  /** Set when [logoUrl] points at a Firebase Storage object so we can replace/delete it. */
  logoStoragePath: string | null;
  /** Optional UID for namespacing the Storage path (matches existing logo upload pattern). */
  uid: string | null;
}

export default function StorefrontSettingsManager({
  settings,
  businessName,
  logoUrl,
  logoStoragePath,
  uid,
}: StorefrontSettingsManagerProps) {
  const [savingSettings, setSavingSettings] = useState(false);
  const [savingLogo, setSavingLogo] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [prepDraft, setPrepDraft] = useState(settings.prepTimeMinutes.toString());
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => setPrepDraft(settings.prepTimeMinutes.toString()), [settings.prepTimeMinutes]);

  const persistSettings = useCallback(
    async (patch: Partial<OnlineOrderingSettings>) => {
      setError(null);
      setSavingSettings(true);
      try {
        await setDoc(
          doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC),
          { ...patch, updatedAt: serverTimestamp() },
          { merge: true }
        );
      } catch (e) {
        const msg = e instanceof Error ? e.message : "Save failed";
        setError(msg);
      } finally {
        setSavingSettings(false);
      }
    },
    []
  );

  const onPickOpenOverride = useCallback(
    (v: StoreOpenOverride) => {
      void persistSettings({ openStatusOverride: v });
    },
    [persistSettings]
  );

  const onPickEnabled = useCallback(
    (enabled: boolean) => {
      void persistSettings({ enabled });
    },
    [persistSettings]
  );

  const onSavePrep = useCallback(() => {
    const n = parseInt(prepDraft, 10);
    if (!Number.isFinite(n) || n <= 0) {
      setError("Prep time must be a positive number of minutes.");
      return;
    }
    const clamped = Math.max(1, Math.min(240, n));
    void persistSettings({ prepTimeMinutes: clamped });
  }, [persistSettings, prepDraft]);

  const handleLogoFile = useCallback(
    async (file: File) => {
      setError(null);
      setSavingLogo(true);
      try {
        const blob = await resizeImageToBlob(file, {
          maxEdge: 512,
          mimeType: "image/png",
        });
        const path = uid
          ? `businesses/${uid}/logo.png`
          : `online-ordering/logo.png`;
        const sref = storageRef(storage, path);
        await uploadBytes(sref, blob, { contentType: "image/png" });
        const url = await getDownloadURL(sref);
        await setDoc(
          doc(db, SETTINGS_COLLECTION, BUSINESS_INFO_DOC),
          { logoUrl: url, updatedAt: serverTimestamp() },
          { merge: true }
        );
      } catch (e) {
        const msg = e instanceof Error ? e.message : "Upload failed";
        setError(msg);
      } finally {
        setSavingLogo(false);
      }
    },
    [uid]
  );

  const onPickFile = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const f = e.target.files?.[0];
      e.target.value = "";
      if (f) void handleLogoFile(f);
    },
    [handleLogoFile]
  );

  const onRemoveLogo = useCallback(async () => {
    if (!logoUrl) return;
    if (!confirm("Remove your storefront logo?")) return;
    setError(null);
    setSavingLogo(true);
    try {
      if (logoStoragePath) {
        try {
          await deleteObject(storageRef(storage, logoStoragePath));
        } catch {
          // Ignore — object may already be missing.
        }
      }
      await setDoc(
        doc(db, SETTINGS_COLLECTION, BUSINESS_INFO_DOC),
        { logoUrl: "", updatedAt: serverTimestamp() },
        { merge: true }
      );
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Delete failed";
      setError(msg);
    } finally {
      setSavingLogo(false);
    }
  }, [logoStoragePath, logoUrl]);

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-slate-900">Storefront settings</h2>
        <p className="text-sm text-slate-500 mt-0.5">
          Logo, store name, prep time, and open/closed control.
        </p>
      </div>

      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
          {error}
        </p>
      )}

      {/* Logo */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5">
        <div className="flex items-start gap-4">
          <div className="w-16 h-16 rounded-full overflow-hidden bg-slate-100 ring-1 ring-slate-200 grid place-items-center text-slate-400 shrink-0">
            {logoUrl ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={logoUrl} alt="" className="w-full h-full object-cover" />
            ) : (
              <ImageIcon size={22} />
            )}
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-sm font-semibold text-slate-800 flex items-center gap-2">
              <Store size={16} className="text-slate-400" /> Logo &amp; name
            </p>
            <p className="text-xs text-slate-500 mt-0.5">
              Store name is managed in{" "}
              <a
                href="/dashboard/settings/business"
                className="text-blue-600 underline-offset-2 hover:underline"
              >
                Settings → Business Information
              </a>
              . Currently set to <span className="font-medium text-slate-700">&ldquo;{businessName || "—"}&rdquo;</span>.
            </p>
            <input
              ref={fileRef}
              type="file"
              accept="image/*"
              className="hidden"
              onChange={onPickFile}
            />
            <div className="flex flex-wrap gap-2 mt-3">
              <button
                type="button"
                disabled={savingLogo}
                onClick={() => fileRef.current?.click()}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-slate-700 text-xs font-medium hover:bg-slate-50 disabled:opacity-50"
              >
                {savingLogo ? (
                  <Loader2 size={14} className="animate-spin" />
                ) : (
                  <Upload size={14} />
                )}
                {logoUrl ? "Replace logo" : "Upload logo"}
              </button>
              {logoUrl && (
                <button
                  type="button"
                  disabled={savingLogo}
                  onClick={() => void onRemoveLogo()}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-rose-600 hover:bg-rose-50 disabled:opacity-50"
                >
                  <Trash2 size={14} /> Remove
                </button>
              )}
            </div>
          </div>
        </div>
      </section>

      {/* Open / closed */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5">
        <div className="flex items-center gap-2 mb-3">
          <Power size={16} className="text-slate-400" />
          <h3 className="text-sm font-semibold text-slate-800">Open / closed</h3>
        </div>
        <p className="text-xs text-slate-500 mb-3">
          Online ordering is{" "}
          <span className="font-medium text-slate-700">{settings.enabled ? "enabled" : "disabled"}</span>.
          Use the override below to temporarily stop accepting orders without disabling the feature.
        </p>
        <div className="flex flex-col sm:flex-row gap-3">
          <label className="inline-flex items-center gap-2 text-sm text-slate-700 cursor-pointer">
            <input
              type="checkbox"
              checked={settings.enabled}
              disabled={savingSettings}
              onChange={(e) => onPickEnabled(e.target.checked)}
              className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
            />
            Enable online ordering
          </label>
          <div className="flex-1 grid grid-cols-3 gap-1 bg-slate-100 rounded-lg p-1">
            {(["AUTO", "OPEN", "CLOSED"] as const).map((v) => (
              <button
                key={v}
                type="button"
                disabled={savingSettings}
                onClick={() => onPickOpenOverride(v)}
                className={`text-xs font-medium rounded-md py-1.5 transition-colors ${
                  settings.openStatusOverride === v
                    ? v === "CLOSED"
                      ? "bg-rose-600 text-white shadow-sm"
                      : v === "OPEN"
                      ? "bg-emerald-600 text-white shadow-sm"
                      : "bg-white text-slate-900 shadow-sm"
                    : "text-slate-500 hover:text-slate-800"
                }`}
              >
                {v === "AUTO"
                  ? "Auto (follow toggle)"
                  : v === "OPEN"
                  ? "Force open"
                  : "Force closed"}
              </button>
            ))}
          </div>
        </div>
      </section>

      {/* Prep time */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5">
        <div className="flex items-center gap-2 mb-3">
          <Clock size={16} className="text-slate-400" />
          <h3 className="text-sm font-semibold text-slate-800">Estimated prep time</h3>
        </div>
        <p className="text-xs text-slate-500 mb-3">
          Customers see this as a range like &ldquo;20–30 min&rdquo; on the store header and cart.
        </p>
        <div className="flex items-center gap-2">
          <input
            type="number"
            min={1}
            max={240}
            value={prepDraft}
            onChange={(e) => setPrepDraft(e.target.value)}
            onBlur={onSavePrep}
            className="w-24 rounded-lg border border-slate-200 px-3 py-2 text-sm tabular-nums outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20"
          />
          <span className="text-sm text-slate-500">minutes</span>
          {savingSettings && <Loader2 size={14} className="animate-spin text-slate-400" />}
        </div>
      </section>
    </div>
  );
}
