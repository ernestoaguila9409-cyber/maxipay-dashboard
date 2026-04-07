"use client";

import type { ReactNode } from "react";
import { useCallback, useEffect, useMemo, useState } from "react";
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
import { ArrowDown, ArrowUp, Search } from "lucide-react";
import Header from "@/components/Header";
import SalesChart, { type HourlySalesPoint } from "@/components/SalesChart";
import { useAuth } from "@/context/AuthContext";
import { periodRange } from "@/lib/reportEngine";
import {
  buildChartPoints,
  buildMockChartPoints,
  buildMockSalesReportPayload,
  loadSalesReportData,
  SALES_REPORT_ORDER_SCAN_LIMIT,
  type ItemSalesRow,
  type SalesReportPayload,
} from "@/lib/salesReportData";

type ReportPeriod = "today" | "week" | "month";
type ChartGranularity = "hourly" | "daily" | "weekly";
type SortKey = "itemName" | "categoryName" | "quantity" | "grossCents" | "netCents";
type SortDir = "asc" | "desc";

function formatMoney(n: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
  }).format(n);
}

function KpiCard({ value, label }: { value: string; label: string }) {
  return (
    <div className="rounded-2xl border border-slate-100/90 bg-white p-5 shadow-sm shadow-slate-200/40">
      <p className="text-2xl font-bold tabular-nums tracking-tight text-slate-900 sm:text-[1.65rem]">
        {value}
      </p>
      <p className="mt-1.5 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
        {label}
      </p>
    </div>
  );
}

function Panel({
  title,
  children,
  action,
}: {
  title: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <section className="rounded-2xl border border-slate-100/90 bg-white p-5 shadow-sm shadow-slate-200/40">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-sm font-semibold text-slate-800">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

function SortTh({
  label,
  active,
  dir,
  onClick,
  align = "left",
}: {
  label: string;
  active: boolean;
  dir: SortDir;
  onClick: () => void;
  align?: "left" | "right";
}) {
  return (
    <th
      className={`select-none px-4 py-3 text-xs font-semibold uppercase tracking-wide text-slate-500 ${
        align === "right" ? "text-right" : "text-left"
      }`}
    >
      <div className={align === "right" ? "flex justify-end" : "inline-flex"}>
        <button
          type="button"
          onClick={onClick}
          className="inline-flex items-center gap-1 rounded-lg px-1 py-0.5 hover:bg-slate-100"
        >
          {label}
          {active &&
            (dir === "asc" ? (
              <ArrowUp className="h-3.5 w-3.5 text-blue-600" />
            ) : (
              <ArrowDown className="h-3.5 w-3.5 text-blue-600" />
            ))}
        </button>
      </div>
    </th>
  );
}

const CATEGORY_BAR_COLORS = [
  "#2563eb",
  "#0d9488",
  "#d97706",
  "#7c3aed",
  "#db2777",
  "#0891b2",
  "#4f46e5",
  "#ca8a04",
];

export default function SalesReportClient() {
  const { user } = useAuth();
  const [period, setPeriod] = useState<ReportPeriod>("today");
  const [chartMode, setChartMode] = useState<ChartGranularity>("hourly");
  const [loading, setLoading] = useState(true);
  const [usingMock, setUsingMock] = useState(false);
  const [payload, setPayload] = useState<SalesReportPayload | null>(null);
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("grossCents");
  const [sortDir, setSortDir] = useState<SortDir>("desc");

  const load = useCallback(async () => {
    setLoading(true);
    const enginePeriod = period === "today" ? "today" : period === "week" ? "week" : "month";
    const { start, end: endExclusive } = periodRange(enginePeriod);

    if (!user) {
      setPayload(buildMockSalesReportPayload());
      setUsingMock(true);
      setLoading(false);
      return;
    }

    try {
      const data = await loadSalesReportData(start, endExclusive);
      setPayload(data);
      setUsingMock(false);
    } catch (e) {
      console.error("Sales report failed:", e);
      setPayload(buildMockSalesReportPayload());
      setUsingMock(true);
    } finally {
      setLoading(false);
    }
  }, [user, period]);

  useEffect(() => {
    load();
  }, [load]);

  const enginePeriod = period === "today" ? "today" : period === "week" ? "week" : "month";
  const { start, end: endExclusive } = periodRange(enginePeriod);

  const chartData: HourlySalesPoint[] = useMemo(() => {
    if (!payload) return [];
    if (usingMock || !payload.ordersSnapshot) {
      return buildMockChartPoints(chartMode);
    }
    return buildChartPoints(chartMode, payload.ordersSnapshot, start, endExclusive);
  }, [payload, usingMock, chartMode, start, endExclusive]);

  const topSelling = useMemo(() => {
    if (!payload) return [];
    return [...payload.itemRows]
      .sort((a, b) => b.grossCents - a.grossCents)
      .slice(0, 8);
  }, [payload]);

  const categoryChartData = useMemo(() => {
    if (!payload) return [];
    return payload.categoryRows.map((c) => ({
      name: c.categoryName,
      revenue: c.grossCents / 100,
    }));
  }, [payload]);

  const filteredSortedRows = useMemo(() => {
    if (!payload) return [];
    const q = search.trim().toLowerCase();
    let rows = payload.itemRows.filter(
      (r) =>
        !q ||
        r.itemName.toLowerCase().includes(q) ||
        r.categoryName.toLowerCase().includes(q)
    );
    rows = [...rows].sort((a, b) => {
      const mul = sortDir === "asc" ? 1 : -1;
      switch (sortKey) {
        case "itemName":
          return mul * a.itemName.localeCompare(b.itemName);
        case "categoryName":
          return mul * a.categoryName.localeCompare(b.categoryName);
        case "quantity":
          return mul * (a.quantity - b.quantity);
        case "grossCents":
          return mul * (a.grossCents - b.grossCents);
        case "netCents":
          return mul * (a.netCents - b.netCents);
        default:
          return 0;
      }
    });
    return rows;
  }, [payload, search, sortKey, sortDir]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir(key === "itemName" || key === "categoryName" ? "asc" : "desc");
    }
  };

  const s = payload?.summary;

  return (
    <>
      <Header title="Sales Report" />

      <div className="space-y-5 p-5 sm:space-y-6 sm:p-6">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="inline-flex rounded-xl bg-slate-100/90 p-1 shadow-inner">
            {(
              [
                ["today", "Today"],
                ["week", "Last 7 Days"],
                ["month", "This Month"],
              ] as const
            ).map(([id, label]) => (
              <button
                key={id}
                type="button"
                onClick={() => setPeriod(id)}
                className={`rounded-lg px-4 py-2 text-sm font-medium transition-all ${
                  period === id
                    ? "bg-white text-slate-900 shadow-sm"
                    : "text-slate-600 hover:text-slate-900"
                }`}
              >
                {label}
              </button>
            ))}
          </div>
          {usingMock && (
            <p className="text-xs font-medium text-amber-700">
              Sample data — sign in to load live Firestore sales.
            </p>
          )}
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5 lg:gap-5">
          <KpiCard
            value={s ? formatMoney(s.grossSalesCents / 100) : "—"}
            label="Gross sales"
          />
          <KpiCard
            value={s ? formatMoney(s.netSalesCents / 100) : "—"}
            label="Net sales"
          />
          <KpiCard
            value={s ? formatMoney(s.discountsCents / 100) : "—"}
            label="Discounts"
          />
          <KpiCard
            value={s ? formatMoney(s.refundsCents / 100) : "—"}
            label="Refunds"
          />
          <KpiCard
            value={s ? formatMoney(s.taxCollectedCents / 100) : "—"}
            label="Taxes collected"
          />
        </div>

        <Panel
          title="Sales Over Time"
          action={
            <div className="inline-flex rounded-lg bg-slate-100/80 p-0.5">
              {(
                [
                  ["hourly", "Hourly"],
                  ["daily", "Daily"],
                  ["weekly", "Weekly"],
                ] as const
              ).map(([id, label]) => (
                <button
                  key={id}
                  type="button"
                  onClick={() => setChartMode(id)}
                  className={`rounded-md px-3 py-1.5 text-xs font-medium transition-all ${
                    chartMode === id
                      ? "bg-white text-slate-900 shadow-sm"
                      : "text-slate-600 hover:text-slate-900"
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
          }
        >
          <SalesChart
            data={chartData}
            loading={loading}
            variant="embedded"
            lineType="natural"
            height={320}
            xAxisTickInterval={chartMode === "daily" ? 0 : chartMode === "weekly" ? 0 : 2}
            emptyTitle="No revenue in this view"
            emptyDescription="Adjust the period or granularity, or record closed orders in the POS."
          />
        </Panel>

        <div className="grid grid-cols-1 gap-5 lg:grid-cols-2 lg:gap-6">
          <Panel title="Sales by Category">
            {loading ? (
              <div className="flex h-[280px] items-center justify-center">
                <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-200 border-t-blue-600" />
              </div>
            ) : categoryChartData.length === 0 ? (
              <p className="py-12 text-center text-sm text-slate-500">No category sales yet.</p>
            ) : (
              <div className="h-[300px] w-full min-w-0">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart
                    data={categoryChartData}
                    layout="vertical"
                    margin={{ top: 4, right: 16, left: 4, bottom: 4 }}
                  >
                    <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" horizontal={false} />
                    <XAxis type="number" hide />
                    <YAxis
                      type="category"
                      dataKey="name"
                      width={100}
                      tick={{ fontSize: 11, fill: "#64748b" }}
                      tickLine={false}
                      axisLine={false}
                    />
                    <Tooltip
                      formatter={(v) =>
                        formatMoney(typeof v === "number" ? v : Number(v) || 0)
                      }
                      contentStyle={{
                        borderRadius: "12px",
                        border: "1px solid #e2e8f0",
                        fontSize: "13px",
                      }}
                    />
                    <Bar dataKey="revenue" radius={[0, 6, 6, 0]} barSize={26}>
                      {categoryChartData.map((_, i) => (
                        <Cell
                          key={i}
                          fill={CATEGORY_BAR_COLORS[i % CATEGORY_BAR_COLORS.length]}
                        />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </Panel>

          <Panel title="Top Selling Items">
            {loading ? (
              <div className="flex h-48 items-center justify-center">
                <div className="h-7 w-7 animate-spin rounded-full border-2 border-slate-200 border-t-blue-600" />
              </div>
            ) : topSelling.length === 0 ? (
              <p className="py-10 text-center text-sm text-slate-500">No line items in range.</p>
            ) : (
              <ul className="divide-y divide-slate-100">
                {topSelling.map((row, i) => (
                  <li
                    key={`${row.itemId}-${row.itemName}-${i}`}
                    className="flex items-center justify-between gap-3 py-3 first:pt-0"
                  >
                    <div className="min-w-0">
                      <p className="truncate font-medium text-slate-900">{row.itemName}</p>
                      <p className="text-xs text-slate-500">{row.categoryName}</p>
                    </div>
                    <div className="shrink-0 text-right">
                      <p className="text-sm font-semibold tabular-nums text-slate-900">
                        {formatMoney(row.grossCents / 100)}
                      </p>
                      <p className="text-xs text-slate-500">{row.quantity} sold</p>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>

        <Panel title="Adjustments">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <div className="rounded-xl border border-slate-100 bg-slate-50/80 px-4 py-3">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                Total discounts
              </p>
              <p className="mt-1 text-lg font-bold text-slate-900">
                {s ? formatMoney(s.discountsCents / 100) : "—"}
              </p>
            </div>
            <div className="rounded-xl border border-slate-100 bg-slate-50/80 px-4 py-3">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                Total refunds
              </p>
              <p className="mt-1 text-lg font-bold text-red-600">
                {s ? formatMoney(s.refundsCents / 100) : "—"}
              </p>
            </div>
            <div className="rounded-xl border border-slate-100 bg-slate-50/80 px-4 py-3">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                Refund transactions
              </p>
              <p className="mt-1 text-lg font-bold text-slate-900">
                {payload != null ? String(payload.refundCount) : "—"}
              </p>
            </div>
          </div>
        </Panel>

        <Panel
          title="Item sales detail"
          action={
            <div className="relative w-full min-w-[200px] max-w-xs sm:w-64">
              <Search
                className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400"
                aria-hidden
              />
              <input
                type="search"
                placeholder="Search items or categories…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="w-full rounded-xl border border-slate-200 bg-slate-50 py-2 pl-9 pr-3 text-sm text-slate-800 placeholder:text-slate-400 focus:border-blue-400 focus:outline-none focus:ring-2 focus:ring-blue-400/20"
              />
            </div>
          }
        >
          <div className="overflow-x-auto rounded-xl border border-slate-100">
            <table className="w-full min-w-[640px] text-left text-sm">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50/80">
                  <SortTh
                    label="Item name"
                    active={sortKey === "itemName"}
                    dir={sortDir}
                    onClick={() => toggleSort("itemName")}
                  />
                  <SortTh
                    label="Category"
                    active={sortKey === "categoryName"}
                    dir={sortDir}
                    onClick={() => toggleSort("categoryName")}
                  />
                  <SortTh
                    label="Qty sold"
                    active={sortKey === "quantity"}
                    dir={sortDir}
                    onClick={() => toggleSort("quantity")}
                    align="right"
                  />
                  <SortTh
                    label="Gross sales"
                    active={sortKey === "grossCents"}
                    dir={sortDir}
                    onClick={() => toggleSort("grossCents")}
                    align="right"
                  />
                  <SortTh
                    label="Net sales"
                    active={sortKey === "netCents"}
                    dir={sortDir}
                    onClick={() => toggleSort("netCents")}
                    align="right"
                  />
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr>
                    <td colSpan={5} className="px-4 py-16 text-center text-slate-400">
                      Loading…
                    </td>
                  </tr>
                ) : filteredSortedRows.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="px-4 py-12 text-center text-slate-500">
                      No rows match your filters.
                    </td>
                  </tr>
                ) : (
                  filteredSortedRows.map((row: ItemSalesRow) => (
                    <tr
                      key={`${row.itemId}-${row.itemName}-${row.categoryId}`}
                      className="border-b border-slate-50 hover:bg-slate-50/60"
                    >
                      <td className="px-4 py-3 font-medium text-slate-900">{row.itemName}</td>
                      <td className="px-4 py-3 text-slate-600">{row.categoryName}</td>
                      <td className="px-4 py-3 text-right tabular-nums text-slate-800">
                        {row.quantity}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums font-medium text-slate-900">
                        {formatMoney(row.grossCents / 100)}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-slate-800">
                        {formatMoney(row.netCents / 100)}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
          {payload && !usingMock && (
            <p className="mt-3 text-xs text-slate-500">
              Line-item breakdown scans up to {SALES_REPORT_ORDER_SCAN_LIMIT.toLocaleString()} closed
              orders in this period. Top KPIs use full transaction and order totals.
            </p>
          )}
        </Panel>
      </div>
    </>
  );
}
