"use client";

import type { ReactNode } from "react";
import {
  FONT_SIZE_LABELS,
  LANDI_CHARS_PER_LINE,
  clampMultiline,
  clampSingleLine,
  landiCharsPerLine,
} from "@/lib/receiptThermal";

const ADDRESS_MAX_LINES = 4;

function CharRulers({ activeFontSize }: { activeFontSize: number }) {
  return (
    <div className="mt-2 space-y-1">
      {FONT_SIZE_LABELS.map((label, sizeIndex) => {
        const count = LANDI_CHARS_PER_LINE[sizeIndex];
        const active = sizeIndex === activeFontSize;
        return (
          <div
            key={label}
            className={`flex items-end gap-2 min-w-0 ${
              active ? "opacity-100" : "opacity-45"
            }`}
          >
            <span
              className={`shrink-0 w-[4.5rem] text-[10px] font-medium leading-none pb-0.5 ${
                active ? "text-blue-600" : "text-slate-400"
              }`}
            >
              {label}
            </span>
            <div className="min-w-0 flex-1 overflow-x-auto pb-0.5">
              <span
                className={`inline-block font-mono text-[10px] leading-none whitespace-nowrap select-none ${
                  active ? "text-blue-500" : "text-slate-300"
                }`}
                style={{ letterSpacing: "0.02em" }}
              >
                {"_".repeat(count)}
              </span>
            </div>
          </div>
        );
      })}
    </div>
  );
}

type ReceiptBusinessFieldProps = {
  id: string;
  label: ReactNode;
  value: string;
  onChange: (value: string) => void;
  activeFontSize: number;
  mode: "single" | "multiline";
  placeholder?: string;
  type?: "text" | "tel" | "email";
  rows?: number;
  showGuide?: boolean;
};

export function ReceiptBusinessField({
  id,
  label,
  value,
  onChange,
  activeFontSize,
  mode,
  placeholder,
  type = "text",
  rows = 3,
  showGuide = true,
}: ReceiptBusinessFieldProps) {
  const maxPerLine = landiCharsPerLine(activeFontSize);
  const activeLabel = FONT_SIZE_LABELS[activeFontSize] ?? "Normal";

  const handleChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>
  ) => {
    const raw = e.target.value;
    if (mode === "multiline") {
      onChange(clampMultiline(raw, maxPerLine, ADDRESS_MAX_LINES));
    } else {
      onChange(clampSingleLine(raw, maxPerLine));
    }
  };

  const lineCount =
    mode === "multiline"
      ? value.replace(/\r\n/g, "\n").split("\n").length
      : 1;

  const longestLine =
    mode === "multiline"
      ? value
          .replace(/\r\n/g, "\n")
          .split("\n")
          .reduce((m, l) => Math.max(m, l.length), 0)
      : value.length;

  const inputClass =
    "w-full px-4 py-2.5 rounded-xl border border-slate-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none text-slate-800 placeholder:text-slate-400 transition-colors font-mono text-sm";

  return (
    <div>
      <label
        htmlFor={id}
        className="flex items-center justify-between gap-2 text-sm font-medium text-slate-700 mb-1.5"
      >
        <span className="flex items-center gap-2 min-w-0">{label}</span>
        <span className="shrink-0 text-[11px] font-normal text-slate-400">
          {longestLine}/{maxPerLine}
          {mode === "multiline"
            ? ` · ${lineCount}/${ADDRESS_MAX_LINES} lines`
            : ""}{" "}
          ({activeLabel})
        </span>
      </label>

      {mode === "multiline" ? (
        <textarea
          id={id}
          value={value}
          onChange={handleChange}
          placeholder={placeholder}
          rows={rows}
          className={`${inputClass} resize-y min-h-[72px]`}
        />
      ) : (
        <input
          id={id}
          type={type}
          value={value}
          onChange={handleChange}
          placeholder={placeholder}
          className={inputClass}
        />
      )}

      <CharRulers activeFontSize={activeFontSize} />

      {showGuide ? (
        <p className="mt-1.5 text-[11px] text-slate-400 leading-snug">
          Highlighted row matches Print Settings font size for this section.
          Each underline is one character on the Landi receipt printer.
        </p>
      ) : null}
    </div>
  );
}

export { ADDRESS_MAX_LINES };
