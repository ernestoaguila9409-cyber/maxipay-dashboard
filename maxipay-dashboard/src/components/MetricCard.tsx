"use client";

import type { LucideIcon } from "lucide-react";

export interface MetricCardProps {
  label: string;
  value: string;
  /** Muted helper line (e.g. trend placeholder). */
  subtext?: string;
  icon?: LucideIcon;
  /** Accent for icon tile only */
  accent?: "blue" | "emerald" | "violet" | "amber" | "slate";
}

const accentMap = {
  blue: { tile: "bg-blue-50", icon: "text-blue-600" },
  emerald: { tile: "bg-emerald-50", icon: "text-emerald-600" },
  violet: { tile: "bg-violet-50", icon: "text-violet-600" },
  amber: { tile: "bg-amber-50", icon: "text-amber-600" },
  slate: { tile: "bg-slate-50", icon: "text-slate-600" },
};

export default function MetricCard({
  label,
  value,
  subtext,
  icon: Icon,
  accent = "blue",
}: MetricCardProps) {
  const a = accentMap[accent];

  return (
    <div className="bg-white rounded-2xl p-5 sm:p-6 shadow-sm border border-slate-100 hover:shadow-md transition-shadow">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
            {label}
          </p>
          <p className="text-2xl sm:text-3xl font-bold text-slate-900 mt-2 tabular-nums">
            {value}
          </p>
          {subtext ? (
            <p className="text-xs text-slate-400 mt-2">{subtext}</p>
          ) : null}
        </div>
        {Icon ? (
          <div className={`shrink-0 p-3 rounded-xl ${a.tile}`}>
            <Icon size={22} className={a.icon} strokeWidth={2} />
          </div>
        ) : null}
      </div>
    </div>
  );
}
