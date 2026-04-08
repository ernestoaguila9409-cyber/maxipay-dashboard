"use client";

import { useEffect, useMemo, useState } from "react";
import { doc, serverTimestamp, updateDoc } from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import {
  DEFAULT_KITCHEN_TICKET_STYLE,
  KITCHEN_FONT_SIZE_LABELS,
  kitchenPreviewLineWidth,
  kitchenTicketStyleForFirestore,
  repeatDash,
  type KitchenFontSize,
  type KitchenTicketStyleState,
} from "@/lib/kitchenTicketStyle";
import type { PrinterViewRow } from "@/hooks/usePrintersStatus";
import { X } from "lucide-react";

function SectionRow({
  label,
  fontKey,
  boldKey,
  state,
  setState,
}: {
  label: string;
  fontKey: keyof KitchenTicketStyleState;
  boldKey: keyof KitchenTicketStyleState;
  state: KitchenTicketStyleState;
  setState: React.Dispatch<React.SetStateAction<KitchenTicketStyleState>>;
}) {
  const fontSize = state[fontKey] as KitchenFontSize;
  const bold = state[boldKey] as boolean;

  return (
    <div className="flex flex-col sm:flex-row sm:items-center gap-2 sm:gap-4 py-3 border-b border-slate-100 last:border-0">
      <span className="text-sm font-medium text-slate-700 sm:w-44 shrink-0">{label}</span>
      <div className="flex flex-wrap items-center gap-3 flex-1">
        <select
          value={fontSize}
          onChange={(e) =>
            setState((s) => ({ ...s, [fontKey]: Number(e.target.value) as KitchenFontSize }))
          }
          className="text-sm border border-slate-200 rounded-lg px-2 py-1.5 bg-white text-slate-800"
        >
          {([0, 1, 2] as const).map((v) => (
            <option key={v} value={v}>
              {KITCHEN_FONT_SIZE_LABELS[v]}
            </option>
          ))}
        </select>
        <label className="inline-flex items-center gap-2 text-sm text-slate-600 cursor-pointer">
          <input
            type="checkbox"
            checked={bold}
            onChange={(e) => setState((s) => ({ ...s, [boldKey]: e.target.checked }))}
            className="rounded border-slate-300 text-blue-600 focus:ring-blue-500/30"
          />
          Bold
        </label>
      </div>
    </div>
  );
}

function KitchenTicketPreview({
  printerName,
  style,
}: {
  printerName: string;
  style: KitchenTicketStyleState;
}) {
  const sample = useMemo(() => {
    const dw = kitchenPreviewLineWidth(style.dividerFontSize);
    const title = printerName.trim().toUpperCase() || "KITCHEN";
    const lines: { text: string; size: KitchenFontSize; bold: boolean; center?: boolean }[] = [];

    const add = (
      text: string,
      size: KitchenFontSize,
      bold: boolean,
      center = false
    ) => lines.push({ text, size, bold, center });

    add(repeatDash(dw), style.dividerFontSize, style.dividerBold);
    add(title, style.titleFontSize, style.titleBold, true);
    add(`Order #265`, style.metaFontSize, style.metaBold);
    add(`Type: TO GO`, style.metaFontSize, style.metaBold);
    add(`Table: -`, style.metaFontSize, style.metaBold);
    add(`Time: 9:08 PM`, style.metaFontSize, style.metaBold);
    add(repeatDash(dw), style.dividerFontSize, style.dividerBold);
    add("", 0, false);
    add(`1x Sprite`, style.itemFontSize, style.itemBold);
    add(`   - Small`, style.modifierFontSize, style.modifierBold);
    add(`[drinks]`, style.stationTagFontSize, style.stationTagBold);
    add("", 0, false);
    add(repeatDash(dw), style.dividerFontSize, style.dividerBold);
    add(`Notes:`, style.notesHeadingFontSize, style.notesHeadingBold);
    add(`Extra napkins please`, style.notesBodyFontSize, style.notesBodyBold);
    add(repeatDash(dw), style.dividerFontSize, style.dividerBold);

    return lines;
  }, [printerName, style]);

  const fontPx = (sz: KitchenFontSize) => (sz === 2 ? 17 : sz === 1 ? 13.5 : 11);

  return (
    <div className="rounded-xl border border-slate-200 bg-white shadow-inner p-4 overflow-auto max-h-[min(70vh,520px)]">
      <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-3">Preview</p>
      <div
        className="mx-auto bg-white text-black border border-dashed border-slate-300 p-3 max-w-[220px]"
        style={{ fontFamily: "ui-monospace, monospace" }}
      >
        {sample.map((line, i) => (
          <div
            key={i}
            className={line.center ? "text-center" : ""}
            style={{
              fontSize: `${fontPx(line.size)}px`,
              fontWeight: line.bold ? 700 : 400,
              lineHeight: 1.35,
              minHeight: line.text === "" ? "0.5em" : undefined,
              whiteSpace: "pre-wrap",
              wordBreak: "break-word",
            }}
          >
            {line.text || "\u00a0"}
          </div>
        ))}
      </div>
      <p className="text-[11px] text-slate-400 mt-3">
        Approximate layout. Physical printers may differ slightly. X-Large uses half the characters per line on paper.
      </p>
    </div>
  );
}

const SECTIONS: {
  label: string;
  fontKey: keyof KitchenTicketStyleState;
  boldKey: keyof KitchenTicketStyleState;
}[] = [
  { label: "Station title (e.g. BAR)", fontKey: "titleFontSize", boldKey: "titleBold" },
  { label: "Order info (order #, type, table, time)", fontKey: "metaFontSize", boldKey: "metaBold" },
  { label: "Divider lines", fontKey: "dividerFontSize", boldKey: "dividerBold" },
  { label: "Item name & qty", fontKey: "itemFontSize", boldKey: "itemBold" },
  { label: "Modifiers", fontKey: "modifierFontSize", boldKey: "modifierBold" },
  { label: "Routing tag ([drinks])", fontKey: "stationTagFontSize", boldKey: "stationTagBold" },
  { label: "Notes heading (Notes:)", fontKey: "notesHeadingFontSize", boldKey: "notesHeadingBold" },
  { label: "Notes body", fontKey: "notesBodyFontSize", boldKey: "notesBodyBold" },
];

export function KitchenTicketStyleModal({
  row,
  open,
  onClose,
}: {
  row: PrinterViewRow | null;
  open: boolean;
  onClose: () => void;
}) {
  const [state, setState] = useState<KitchenTicketStyleState>(DEFAULT_KITCHEN_TICKET_STYLE);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (open && row) {
      setState({ ...row.kitchenTicketStyle });
      setErr(null);
      setSaving(false);
    }
  }, [open, row]);

  if (!open || !row) return null;

  const handleSave = async () => {
    setSaving(true);
    setErr(null);
    try {
      await updateDoc(doc(db, "Printers", row.id), {
        kitchenTicketStyle: kitchenTicketStyleForFirestore(state),
        updatedAt: serverTimestamp(),
      });
      onClose();
    } catch (e) {
      console.error("[KitchenTicketStyle] save:", e);
      setErr(e instanceof Error ? e.message : "Save failed");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />
      <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-5xl max-h-[90vh] overflow-hidden flex flex-col">
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100 shrink-0">
          <div>
            <h2 className="text-lg font-bold text-slate-900">Kitchen ticket style</h2>
            <p className="text-sm text-slate-500 mt-0.5">
              {row.name} · {row.ipAddress}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="p-2 rounded-lg hover:bg-slate-100 text-slate-400 hover:text-slate-600"
          >
            <X size={20} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto flex flex-col lg:flex-row min-h-0">
          <div className="flex-1 px-5 py-4 border-b lg:border-b-0 lg:border-r border-slate-100">
            {err && (
              <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded-lg mb-4">{err}</p>
            )}
            <p className="text-sm text-slate-600 mb-2">
              Font size and bold apply only to <strong>kitchen chits</strong> sent to this printer from the POS.
            </p>
            <div className="rounded-xl border border-slate-100 bg-slate-50/50 p-1">
              {SECTIONS.map(({ label, fontKey, boldKey }) => (
                <SectionRow
                  key={String(fontKey)}
                  label={label}
                  fontKey={fontKey}
                  boldKey={boldKey}
                  state={state}
                  setState={setState}
                />
              ))}
            </div>
            <button
              type="button"
              className="mt-3 text-sm text-blue-600 hover:text-blue-800 font-medium"
              onClick={() => setState({ ...DEFAULT_KITCHEN_TICKET_STYLE })}
            >
              Reset all to defaults
            </button>
          </div>
          <div className="lg:w-[280px] shrink-0 px-5 py-4 bg-slate-50/80">
            <KitchenTicketPreview printerName={row.name} style={state} />
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-4 border-t border-slate-100 bg-slate-50/60 shrink-0">
          <button
            type="button"
            onClick={onClose}
            disabled={saving}
            className="px-4 py-2 rounded-lg text-sm font-medium text-slate-600 hover:bg-slate-100"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={saving}
            className="px-5 py-2 rounded-lg text-sm font-semibold text-white bg-blue-600 hover:bg-blue-700 disabled:opacity-60"
          >
            {saving ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </div>
  );
}
