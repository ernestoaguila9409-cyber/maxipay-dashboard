"use client";

import { useCallback, useEffect, useState } from "react";
import { doc, onSnapshot, setDoc } from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import {
  adjustKdsTextField,
  argbToCss,
  coerceKdsTextUi,
  kdsTextUiToFirestore,
  KDS_MODIFIER_COLOR_SWATCHES,
  KDS_TEXT_MAX_SP_MODIFIERS,
  KDS_TEXT_MIN_SP,
  type KdsTextFieldKey,
  type KdsTextUiSettings,
  parseKdsTextUi,
} from "@/lib/kdsTextSettings";
import { Minus, Plus, Type } from "lucide-react";

const KDS_DEVICES_COLLECTION = "kds_devices";
const SETTINGS_SUB = "settings";
const UI_DOC = "ui";

/** Matches KDS app: … Modifiers, then modifier colors, then Buttons. */
const FONT_ROWS_BEFORE_MODIFIERS: { key: KdsTextFieldKey; label: string; max?: number }[] = [
  { key: "headerSp", label: "Header" },
  { key: "tableInfoSp", label: "Table info" },
  { key: "customerNameSp", label: "Customer name" },
  { key: "timerSp", label: "Timer" },
  { key: "itemNameSp", label: "Item name" },
  { key: "modifiersSp", label: "Modifiers", max: KDS_TEXT_MAX_SP_MODIFIERS },
];

const FONT_ROWS_AFTER_COLORS: { key: KdsTextFieldKey; label: string }[] = [
  { key: "buttonsSp", label: "Buttons" },
];

type Props = {
  deviceId: string;
};

function Stepper({
  label,
  valueSp,
  atMin,
  atMax,
  onDec,
  onInc,
}: {
  label: string;
  valueSp: number;
  atMin: boolean;
  atMax: boolean;
  onDec: () => void;
  onInc: () => void;
}) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white px-3 py-2.5">
      <span className="text-sm font-medium text-slate-800">{label}</span>
      <div className="flex items-center gap-2">
        <span className="min-w-[3.25rem] text-right text-sm tabular-nums text-slate-600">
          {valueSp % 1 === 0 ? `${Math.round(valueSp)}` : valueSp.toFixed(1)} sp
        </span>
        <button
          type="button"
          disabled={atMin}
          onClick={onDec}
          className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-slate-50 text-slate-700 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-35"
          aria-label={`Decrease ${label}`}
        >
          <Minus size={18} />
        </button>
        <button
          type="button"
          disabled={atMax}
          onClick={onInc}
          className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-slate-50 text-slate-700 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-35"
          aria-label={`Increase ${label}`}
        >
          <Plus size={18} />
        </button>
      </div>
    </div>
  );
}

export function KdsDeviceTextSettingsPanel({ deviceId }: Props) {
  const [ui, setUi] = useState<KdsTextUiSettings>(() => coerceKdsTextUi(parseKdsTextUi(null)));
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!deviceId.trim()) {
      setLoading(false);
      return;
    }
    setLoading(true);
    const ref = doc(db, KDS_DEVICES_COLLECTION, deviceId, SETTINGS_SUB, UI_DOC);
    const unsub = onSnapshot(
      ref,
      (snap) => {
        setUi(parseKdsTextUi(snap.data() as Record<string, unknown> | undefined));
        setLoading(false);
        setError(null);
      },
      (err) => {
        console.error("[KDS text ui]", err);
        setError("Could not load display settings for this device.");
        setLoading(false);
      }
    );
    return () => unsub();
  }, [deviceId]);

  const persist = useCallback(
    async (next: KdsTextUiSettings) => {
      const id = deviceId.trim();
      if (!id) return;
      const coerced = coerceKdsTextUi(next);
      setError(null);
      try {
        await setDoc(
          doc(db, KDS_DEVICES_COLLECTION, id, SETTINGS_SUB, UI_DOC),
          kdsTextUiToFirestore(coerced)
        );
      } catch (e) {
        console.error("[KDS text ui save]", e);
        setError("Save failed. Check your connection and Firestore rules.");
      }
    },
    [deviceId]
  );

  const bump = (key: KdsTextFieldKey, delta: number) => {
    void persist(adjustKdsTextField(ui, key, delta));
  };

  if (!deviceId.trim()) {
    return (
      <p className="text-sm text-slate-500">
        Add a KDS device above, then pick it here to edit ticket fonts and modifier colors.
      </p>
    );
  }

  if (loading) {
    return (
      <div className="flex justify-center py-6">
        <div className="h-6 w-6 animate-spin rounded-full border-2 border-slate-300 border-t-blue-600" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {error && (
        <p className="rounded-lg border border-red-100 bg-red-50 px-3 py-2 text-sm text-red-800">{error}</p>
      )}

      <div className="flex items-center gap-2 text-slate-600">
        <Type size={18} aria-hidden />
        <p className="text-sm font-medium text-slate-700">Font sizes (sp)</p>
      </div>
      <div className="space-y-2">
        {FONT_ROWS_BEFORE_MODIFIERS.map(({ key, label, max }) => {
          const valueSp = ui[key];
          const atMin = valueSp <= KDS_TEXT_MIN_SP;
          const atMax = valueSp >= (max ?? 44);
          return (
            <Stepper
              key={key}
              label={label}
              valueSp={valueSp}
              atMin={atMin}
              atMax={atMax}
              onDec={() => bump(key, -1)}
              onInc={() => bump(key, 1)}
            />
          );
        })}
      </div>

      <div className="pt-2">
        <p className="text-sm font-semibold text-slate-800">Modifier colors</p>
        <p className="mt-1 text-xs text-slate-500">Add (options &amp; extras)</p>
        <div className="mt-2 flex flex-wrap gap-2">
          {KDS_MODIFIER_COLOR_SWATCHES.map((argb) => {
            const selected = (ui.modifierAddColorArgb >>> 0) === (argb >>> 0);
            return (
              <button
                key={`add-${argb}`}
                type="button"
                onClick={() => void persist({ ...ui, modifierAddColorArgb: argb })}
                className={`h-9 w-9 shrink-0 rounded-full border-2 transition-transform hover:scale-105 ${
                  selected ? "border-blue-600 ring-2 ring-blue-200" : "border-black/20"
                }`}
                style={{ backgroundColor: argbToCss(argb) }}
                aria-label={`Add modifier color ${argb.toString(16)}`}
              />
            );
          })}
        </div>
        <p className="mt-3 text-xs text-slate-500">Remove (hold / No …)</p>
        <div className="mt-2 flex flex-wrap gap-2">
          {KDS_MODIFIER_COLOR_SWATCHES.map((argb) => {
            const selected = (ui.modifierRemoveColorArgb >>> 0) === (argb >>> 0);
            return (
              <button
                key={`rm-${argb}`}
                type="button"
                onClick={() => void persist({ ...ui, modifierRemoveColorArgb: argb })}
                className={`h-9 w-9 shrink-0 rounded-full border-2 transition-transform hover:scale-105 ${
                  selected ? "border-blue-600 ring-2 ring-blue-200" : "border-black/20"
                }`}
                style={{ backgroundColor: argbToCss(argb) }}
                aria-label={`Remove modifier color ${argb.toString(16)}`}
              />
            );
          })}
        </div>
      </div>

      <div className="space-y-2 pt-2">
        {FONT_ROWS_AFTER_COLORS.map(({ key, label }) => {
          const valueSp = ui[key];
          const atMin = valueSp <= KDS_TEXT_MIN_SP;
          const atMax = valueSp >= 44;
          return (
            <Stepper
              key={key}
              label={label}
              valueSp={valueSp}
              atMin={atMin}
              atMax={atMax}
              onDec={() => bump(key, -1)}
              onInc={() => bump(key, 1)}
            />
          );
        })}
      </div>

      <p className="text-xs leading-relaxed text-slate-500">
        Same fields as the KDS app (<code className="rounded bg-slate-100 px-1">settings/ui</code> on
        this device). Changes apply on the tablet after sync.
      </p>
    </div>
  );
}
