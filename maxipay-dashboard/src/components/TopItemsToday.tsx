"use client";

import { TrendingDown, TrendingUp } from "lucide-react";

import type { TopItemRow } from "@/lib/dashboardTopItems";

interface TopItemsTodayProps {
  items: TopItemRow[];
  loading?: boolean;
  sectionTitle?: string;
  compareSubtitle?: string;
  emptyHint?: string;
}

function formatChange(row: TopItemRow): { text: string; className: string } {
  if (row.changePct != null) {
    const rounded = Math.round(row.changePct * 10) / 10;
    const sign = rounded > 0 ? "+" : "";
    const text = `${sign}${rounded}%`;
    if (rounded > 0) {
      return { text, className: "text-emerald-600" };
    }
    if (rounded < 0) {
      return { text, className: "text-red-600" };
    }
    return { text: "0%", className: "text-slate-400" };
  }
  if (row.revenue > 0) {
    return { text: "New", className: "text-emerald-600 font-medium" };
  }
  return { text: "—", className: "text-slate-400" };
}

export default function TopItemsToday({
  items,
  loading,
  sectionTitle = "Top items",
  compareSubtitle = "vs comparison period · closed orders only",
  emptyHint = "No line-item sales in this range (closed orders only).",
}: TopItemsTodayProps) {
  if (loading) {
    return (
      <section className="space-y-3">
        <h2 className="text-lg font-semibold text-slate-800">{sectionTitle}</h2>
        <div
          className="bg-white rounded-[12px] border border-slate-100 p-8"
          style={{ boxShadow: "0 2px 12px rgba(15, 23, 42, 0.06)" }}
        >
          <div className="flex items-center justify-center gap-3 text-slate-400">
            <div className="w-5 h-5 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
            Loading top items…
          </div>
        </div>
      </section>
    );
  }

  if (items.length === 0) {
    return (
      <section className="space-y-3">
        <h2 className="text-lg font-semibold text-slate-800">{sectionTitle}</h2>
        <div
          className="bg-white rounded-[12px] border border-slate-100 p-10 text-center"
          style={{ boxShadow: "0 2px 12px rgba(15, 23, 42, 0.06)" }}
        >
          <p className="text-slate-400">{emptyHint}</p>
        </div>
      </section>
    );
  }

  return (
    <section className="space-y-3">
      <div className="flex flex-col sm:flex-row sm:items-end sm:justify-between gap-1">
        <h2 className="text-lg font-semibold text-slate-800">{sectionTitle}</h2>
        <p className="text-xs text-slate-400">{compareSubtitle}</p>
      </div>
      <div
        className="bg-white rounded-[12px] border border-slate-100 overflow-hidden"
        style={{ boxShadow: "0 2px 12px rgba(15, 23, 42, 0.06)" }}
      >
        <ul className="divide-y divide-slate-100">
          {items.map((row) => {
            const ch = formatChange(row);
            const up =
              row.changePct != null && row.changePct > 0;
            const down =
              row.changePct != null && row.changePct < 0;

            return (
              <li
                key={row.name}
                className="flex items-center justify-between gap-4 px-4 sm:px-5 py-3.5 hover:bg-slate-50/60 transition-colors"
              >
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-semibold text-slate-900 truncate">
                    {row.name}
                  </p>
                  <p className="text-xs text-slate-500 mt-0.5 tabular-nums">
                    ${row.revenue.toFixed(2)} revenue
                  </p>
                </div>
                <div
                  className={`shrink-0 flex items-center gap-1 text-sm font-semibold tabular-nums ${ch.className}`}
                >
                  {up ? (
                    <TrendingUp size={16} strokeWidth={2.5} aria-hidden />
                  ) : null}
                  {down ? (
                    <TrendingDown size={16} strokeWidth={2.5} aria-hidden />
                  ) : null}
                  {ch.text}
                </div>
              </li>
            );
          })}
        </ul>
      </div>
    </section>
  );
}
