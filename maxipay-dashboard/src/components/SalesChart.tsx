"use client";

import type { ReactNode } from "react";
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

export interface HourlySalesPoint {
  label: string;
  amount: number;
}

interface SalesChartProps {
  data: HourlySalesPoint[];
  loading?: boolean;
  emptyTitle?: string;
  emptyDescription?: string;
  /** Recharts XAxis `interval` — use `0` to show every tick (e.g. 7-day view). */
  xAxisTickInterval?: number;
  /** Outer chrome; use `embedded` when the chart sits inside another card. */
  variant?: "card" | "embedded";
  /** Line interpolation — `natural` / `basis` for a smoother curve. */
  lineType?: "monotone" | "natural" | "basis";
  /** Chart area min height in px */
  height?: number;
}

function formatMoney(n: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  }).format(n);
}

export default function SalesChart({
  data,
  loading,
  emptyTitle = "No sales yet today",
  emptyDescription = "Start taking orders to see hourly sales here.",
  xAxisTickInterval = 2,
  variant = "card",
  lineType = "monotone",
  height = 280,
}: SalesChartProps) {
  const total = data.reduce((s, d) => s + d.amount, 0);
  const isEmpty = !loading && total <= 0;

  const shell = (className: string, inner: ReactNode) =>
    variant === "card" ? (
      <div
        className={`rounded-2xl border border-slate-100 bg-white shadow-sm ${className}`}
      >
        {inner}
      </div>
    ) : (
      <div className={className}>{inner}</div>
    );

  if (loading) {
    return shell(
      "flex min-h-[280px] items-center justify-center p-6",
      <div className="flex items-center gap-3 text-slate-400">
        <div className="h-5 w-5 animate-spin rounded-full border-2 border-slate-300 border-t-blue-600" />
        Loading chart…
      </div>
    );
  }

  if (isEmpty) {
    return shell(
      "flex min-h-[280px] flex-col items-center justify-center p-8 text-center",
      <>
        <p className="font-medium text-slate-700">{emptyTitle}</p>
        <p className="mt-2 max-w-sm text-sm text-slate-400">{emptyDescription}</p>
      </>
    );
  }

  const padClass = variant === "card" ? "p-4 sm:p-6" : "p-0";

  return shell(
    padClass,
    <div className="w-full min-w-0" style={{ height }}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart
          data={data}
          margin={{ top: 8, right: 8, left: 0, bottom: 0 }}
        >
          <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" vertical={false} />
          <XAxis
            dataKey="label"
            tick={{ fontSize: 11, fill: "#64748b" }}
            tickLine={false}
            axisLine={{ stroke: "#e2e8f0" }}
            interval={xAxisTickInterval}
          />
          <YAxis
            tick={{ fontSize: 11, fill: "#64748b" }}
            tickLine={false}
            axisLine={false}
            tickFormatter={(v) => (v >= 1000 ? `$${(v / 1000).toFixed(1)}k` : `$${v}`)}
          />
          <Tooltip
            contentStyle={{
              borderRadius: "12px",
              border: "1px solid #e2e8f0",
              boxShadow: "0 4px 6px -1px rgb(0 0 0 / 0.06)",
            }}
            formatter={(value) => {
              const v = value as number | string | undefined;
              const text =
                typeof v === "number"
                  ? formatMoney(v)
                  : v != null
                    ? String(v)
                    : "—";
              return [text, "Sales"];
            }}
          />
          <Line
            type={lineType}
            dataKey="amount"
            name="Sales"
            stroke="#2563eb"
            strokeWidth={2}
            dot={false}
            activeDot={{ r: 4, fill: "#2563eb" }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
