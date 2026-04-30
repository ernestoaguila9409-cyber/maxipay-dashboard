"use client";

import { useCallback, useEffect, useState } from "react";
import { doc, serverTimestamp, setDoc } from "firebase/firestore";
import { Clock, Loader2 } from "lucide-react";
import { db } from "@/firebase/firebaseConfig";
import {
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  type OnlineOrderingSettings,
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
          Business hours and prep time.
        </p>
      </div>

      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
          {error}
        </p>
      )}

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
