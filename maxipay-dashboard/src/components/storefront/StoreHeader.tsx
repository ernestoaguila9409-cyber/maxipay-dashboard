"use client";

import { Clock } from "lucide-react";

/**
 * Storefront header (name, open/closed pill, prep time). Renders inline so it can be
 * placed inside the public ordering page header AND the dashboard live-preview pane.
 */
export interface StoreHeaderProps {
  businessName: string;
  isOpen: boolean;
  prepTimeLabel: string;
  /** Compact = shrink for live preview pane. */
  compact?: boolean;
}

export function StoreHeader({
  businessName,
  isOpen,
  prepTimeLabel,
  compact = false,
}: StoreHeaderProps) {
  return (
    <div className="min-w-0 flex-1">
      <h1
        className={`font-bold text-neutral-900 leading-tight truncate ${
          compact ? "text-[15px]" : "text-lg sm:text-xl"
        }`}
      >
        {businessName || "Restaurant"}
      </h1>
      <div className={`flex items-center gap-2 mt-0.5 ${compact ? "text-[11px]" : "text-xs"}`}>
        <span
          className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 font-medium ${
            isOpen
              ? "bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200"
              : "bg-rose-50 text-rose-700 ring-1 ring-rose-200"
          }`}
        >
          <span
            className={`w-1.5 h-1.5 rounded-full ${
              isOpen ? "bg-emerald-500" : "bg-rose-500"
            }`}
          />
          {isOpen ? "Open" : "Closed"}
        </span>
        {prepTimeLabel && (
          <span className="inline-flex items-center gap-1 text-neutral-500">
            <Clock size={compact ? 11 : 12} />
            {prepTimeLabel}
          </span>
        )}
      </div>
    </div>
  );
}
