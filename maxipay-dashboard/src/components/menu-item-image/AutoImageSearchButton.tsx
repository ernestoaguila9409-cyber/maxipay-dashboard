"use client";

import { Search } from "lucide-react";

export interface AutoImageSearchButtonProps {
  onClick: () => void;
  disabled?: boolean;
}

export function AutoImageSearchButton({ onClick, disabled }: AutoImageSearchButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-xl border border-slate-200 bg-white text-slate-800 text-xs font-medium hover:bg-slate-50 disabled:opacity-50 transition-colors"
    >
      <Search size={14} className="text-blue-600" />
      Auto find image
    </button>
  );
}
