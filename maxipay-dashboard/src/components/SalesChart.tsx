"use client";

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
}: SalesChartProps) {
  const total = data.reduce((s, d) => s + d.amount, 0);
  const isEmpty = !loading && total <= 0;

  if (loading) {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6 min-h-[280px] flex items-center justify-center">
        <div className="flex items-center gap-3 text-slate-400">
          <div className="w-5 h-5 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          Loading chart…
        </div>
      </div>
    );
  }

  if (isEmpty) {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-8 min-h-[280px] flex flex-col items-center justify-center text-center">
        <p className="text-slate-700 font-medium">{emptyTitle}</p>
        <p className="text-sm text-slate-400 mt-2 max-w-sm">{emptyDescription}</p>
      </div>
    );
  }

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-4 sm:p-6">
      <div className="h-[280px] w-full min-w-0">
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
              type="monotone"
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
    </div>
  );
}
