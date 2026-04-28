"use client";

import { useCallback, useEffect, useState } from "react";
import { doc, serverTimestamp, setDoc } from "firebase/firestore";
import { Clock, Loader2, Power } from "lucide-react";
import { db } from "@/firebase/firebaseConfig";
import {
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  type OnlineOrderingSettings,
  type StoreOpenOverride,
} from "@/lib/onlineOrderingShared";
import BusinessHoursSection from "@/components/online-ordering-admin/BusinessHoursSection";

interface StorefrontSettingsManagerProps {
  settings: OnlineOrderingSettings;
}

export default function StorefrontSettingsManager({ settings }: StorefrontSettingsManagerProps) {
  const [savingSettings, setSavingSettings] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [prepDraft, setPrepDraft] = useState(settings.prepTimeMinutes.toString());

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

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-slate-900">Storefront settings</h2>
        <p className="text-sm text-slate-500 mt-0.5">
          Business hours, prep time, and open/closed control.
        </p>
      </div>

      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
          {error}
        </p>
      )}

      {/* Open / closed */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5">
        <div className="flex items-center gap-2 mb-3">
          <Power size={16} className="text-slate-400" />
          <h3 className="text-sm font-semibold text-slate-800">Open / closed</h3>
        </div>
        <p className="text-xs text-slate-500 mb-3">
          Online ordering is{" "}
          <span className="font-medium text-slate-700">{settings.enabled ? "enabled" : "disabled"}</span>.
          Use the override below to temporarily stop accepting orders without disabling the feature. When{" "}
          <span className="font-medium text-slate-700">Auto</span> is on and you enforce business hours below,
          the schedule also controls whether the storefront shows open.
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

      <BusinessHoursSection
        settings={settings}
        disabled={savingSettings}
        onPersist={(patch) => void persistSettings(patch)}
      />

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
