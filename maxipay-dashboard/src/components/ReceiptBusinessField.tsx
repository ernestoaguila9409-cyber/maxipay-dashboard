"use client";

import type { ReactNode } from "react";
import {
  clampMultiline,
  clampSingleLine,
  landiCharsPerLine,
} from "@/lib/receiptThermal";

const ADDRESS_MAX_LINES = 4;

type ReceiptBusinessFieldProps = {
  id: string;
  label: ReactNode;
  value: string;
  onChange: (value: string) => void;
  activeFontSize: number;
  /** When provided, overrides the Landi font-size-based character limit. */
  maxCharsPerLine?: number;
  mode: "single" | "multiline";
  placeholder?: string;
  type?: "text" | "tel" | "email";
  rows?: number;
};

export function ReceiptBusinessField({
  id,
  label,
  value,
  onChange,
  activeFontSize,
  maxCharsPerLine,
  mode,
  placeholder,
  type = "text",
  rows = 3,
}: ReceiptBusinessFieldProps) {
  const maxPerLine = maxCharsPerLine ?? landiCharsPerLine(activeFontSize);

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
            : ""}
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
    </div>
  );
}

export { ADDRESS_MAX_LINES };
