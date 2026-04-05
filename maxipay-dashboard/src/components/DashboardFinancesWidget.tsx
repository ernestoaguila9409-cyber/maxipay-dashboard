"use client";

import Link from "next/link";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { ChevronRight } from "lucide-react";

import type { PaymentBreakdownTotals } from "@/components/PaymentBreakdown";

export interface OpenBatchSummary {
  id: string;
  createdAt: Date | null;
  transactionCounter: number;
  /** Optional running fields sometimes present on open batch docs */
  total: number;
  count: number;
}

interface DashboardFinancesWidgetProps {
  totals: PaymentBreakdownTotals;
  /** e.g. "$70.02 collected today" */
  collectedSubtitle: string;
  openBatch: OpenBatchSummary | null;
  loading?: boolean;
  emptyHint?: string;
}

function formatMoney(n: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(n);
}

function yAxisMax(card: number, cash: number, other: number): number {
  const m = Math.max(card, cash, other, 0);
  if (m <= 0) return 100;
  const headroom = m * 1.12;
  const step = headroom > 500 ? 100 : headroom > 100 ? 25 : headroom > 20 ? 5 : 1;
  return Math.ceil(headroom / step) * step;
}

const BAR_COLORS = {
  Card: "#2563eb",
  Cash: "#3b82f6",
  Other: "#94a3b8",
} as const;

export default function DashboardFinancesWidget({
  totals,
  collectedSubtitle,
  openBatch,
  loading,
  emptyHint = "No card or cash totals for this range yet — they come from sale transactions linked to orders (same as the POS).",
}: DashboardFinancesWidgetProps) {
  const { card, cash, other } = totals;
  const sum = card + cash + other;

  const chartData = [
    { name: "Card" as const, amount: card, fill: BAR_COLORS.Card },
    { name: "Cash" as const, amount: cash, fill: BAR_COLORS.Cash },
    { name: "Other" as const, amount: other, fill: BAR_COLORS.Other },
  ];

  const yMax = yAxisMax(card, cash, other);

  if (loading) {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6">
        <div className="flex items-center gap-3 text-slate-400 text-sm">
          <div className="w-4 h-4 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          Loading finances…
        </div>
      </div>
    );
  }

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-slate-100 overflow-hidden">
      <div className="p-5 sm:p-6 border-b border-slate-100">
        <div className="flex items-start justify-between gap-3">
          <div>
            <Link
              href="/dashboard/orders"
              className="inline-flex items-center gap-1 text-base font-semibold text-slate-900 hover:text-blue-600 transition-colors"
            >
              Finances
              <ChevronRight className="w-4 h-4 text-slate-400" aria-hidden />
            </Link>
            <p className="text-sm text-slate-500 mt-1.5">{collectedSubtitle}</p>
          </div>
        </div>

        <div className="mt-4 rounded-xl bg-slate-50 border border-slate-100 px-4 py-3">
          <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
            Current open batch
          </p>
          {openBatch ? (
            <div className="mt-2 space-y-1">
              <p className="font-mono text-sm font-medium text-slate-900 break-all">
                {openBatch.id}
              </p>
              <p className="text-xs text-slate-600">
                {openBatch.createdAt
                  ? `Opened ${openBatch.createdAt.toLocaleString(undefined, {
                      dateStyle: "medium",
                      timeStyle: "short",
                    })}`
                  : "Opened —"}
                {openBatch.transactionCounter > 0
                  ? ` · Counter ${openBatch.transactionCounter}`
                  : null}
              </p>
            </div>
          ) : (
            <p className="text-sm text-amber-800 mt-2">
              No open batch in Firestore. The POS normally creates one when you start
              selling; check Settle batch on the terminal if this looks wrong.
            </p>
          )}
        </div>
      </div>

      <div className="p-5 sm:p-6 pt-4">
        <p className="text-sm font-semibold text-slate-800 mb-4">Amount collected</p>

        {sum <= 0 ? (
          <p className="text-sm text-slate-400 py-8 text-center">{emptyHint}</p>
        ) : (
          <>
            <div className="h-[260px] w-full min-w-0">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart
                  data={chartData}
                  margin={{ top: 8, right: 8, left: 4, bottom: 8 }}
                >
                  <CartesianGrid
                    strokeDasharray="4 4"
                    stroke="#e2e8f0"
                    vertical={false}
                  />
                  <XAxis
                    dataKey="name"
                    tick={{ fontSize: 12, fill: "#64748b" }}
                    tickLine={false}
                    axisLine={{ stroke: "#e2e8f0" }}
                  />
                  <YAxis
                    domain={[0, yMax]}
                    tick={{ fontSize: 11, fill: "#64748b" }}
                    tickLine={false}
                    axisLine={false}
                    tickFormatter={(v) =>
                      v >= 1000 ? `$${(v / 1000).toFixed(1)}k` : `$${v}`
                    }
                  />
                  <Tooltip
                    cursor={{ fill: "rgba(241, 245, 249, 0.6)" }}
                    contentStyle={{
                      borderRadius: "12px",
                      border: "1px solid #e2e8f0",
                      boxShadow: "0 4px 6px -1px rgb(0 0 0 / 0.06)",
                    }}
                    formatter={(value) => [
                      formatMoney(value as number),
                      "Collected",
                    ]}
                  />
                  <Bar dataKey="amount" radius={[8, 8, 0, 0]} maxBarSize={72}>
                    {chartData.map((entry) => (
                      <Cell key={entry.name} fill={entry.fill} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>

            <ul className="mt-5 space-y-2.5 pt-4 border-t border-slate-100">
              {chartData.map((row) => (
                <li
                  key={row.name}
                  className="flex items-center justify-between gap-4 text-sm"
                >
                  <span className="flex items-center gap-2 text-slate-600">
                    <span
                      className="w-2.5 h-2.5 rounded-full shrink-0"
                      style={{ backgroundColor: row.fill }}
                    />
                    {row.name}
                  </span>
                  <span className="font-medium text-slate-900 tabular-nums">
                    {formatMoney(row.amount)}
                  </span>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </div>
  );
}
