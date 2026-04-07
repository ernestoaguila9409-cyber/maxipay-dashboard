"use client";

import type { ReactNode } from "react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { collection, getDocs, query, Timestamp, where } from "firebase/firestore";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import Header from "@/components/Header";
import SalesChart, { type HourlySalesPoint } from "@/components/SalesChart";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import { buildDailySalesPoints } from "@/lib/dashboardFinance";
import {
  getDailySalesSummary,
  getHourlySales,
  getSalesByOrderType,
  periodRange,
  type DailySalesSummary,
  type HourlySale,
  type SalesByOrderType,
} from "@/lib/reportEngine";

type ReportPeriod = "today" | "week" | "month";

function formatMoney(n: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(n);
}

function formatHourLabel(hour: number): string {
  const am = hour < 12;
  const display = hour % 12 === 0 ? 12 : hour % 12;
  return `${display}${am ? "a" : "p"}`;
}

function fillHourlyLinePoints(hourly: HourlySale[]): HourlySalesPoint[] {
  const map = new Map<number, number>();
  for (const h of hourly) {
    map.set(h.hour, h.totalCents / 100);
  }
  return Array.from({ length: 24 }, (_, hour) => ({
    label: formatHourLabel(hour),
    amount: map.get(hour) ?? 0,
  }));
}

const MOCK_SUMMARY: DailySalesSummary = {
  grossSalesCents: 1_284_500,
  taxCollectedCents: 102_760,
  tipsCollectedCents: 182_000,
  netSalesCents: 1_159_740,
  totalTransactions: 142,
  averageTicketCents: 9_045,
  refundsCents: 3_200,
  discountsCents: 22_500,
  itemsSold: 380,
  voidedItems: 4,
  cashPaymentsCents: 321_000,
  creditPaymentsCents: 789_000,
  debitPaymentsCents: 174_500,
};

const MOCK_ORDER_TYPES: SalesByOrderType = {
  dineInCents: 720_000,
  toGoCents: 385_000,
  barCents: 179_500,
};

function mockHourlySales(): HourlySale[] {
  const peaks = [11, 12, 18, 19, 20];
  return Array.from({ length: 24 }, (_, hour) => ({
    hour,
    totalCents: peaks.includes(hour)
      ? Math.round((4000 + Math.random() * 2500) * 100)
      : Math.round((200 + hour * 80) * 100),
    orderCount: peaks.includes(hour) ? 12 + hour : 2 + (hour % 5),
  }));
}

function mockDailyLinePoints(): HourlySalesPoint[] {
  const days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  return days.map((label, i) => ({
    label,
    amount: 3200 + i * 420 + (i === 5 ? 2800 : 0),
  }));
}

function KpiCard({ value, label }: { value: string; label: string }) {
  return (
    <div className="rounded-2xl border border-slate-100/90 bg-white p-5 shadow-sm shadow-slate-200/40">
      <p className="text-2xl font-bold tabular-nums tracking-tight text-slate-900 sm:text-3xl">
        {value}
      </p>
      <p className="mt-1.5 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
        {label}
      </p>
    </div>
  );
}

function ChartPanel({
  title,
  children,
  className = "",
}: {
  title: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={`rounded-2xl border border-slate-100/90 bg-white p-5 shadow-sm shadow-slate-200/40 ${className}`}
    >
      <h2 className="mb-4 text-sm font-semibold text-slate-800">{title}</h2>
      {children}
    </section>
  );
}

const PAYMENT_COLORS = {
  cash: "#0d9488",
  credit: "#2563eb",
  debit: "#7c3aed",
};

function PaymentDonut({
  summary,
  loading,
}: {
  summary: DailySalesSummary | null;
  loading: boolean;
}) {
  const rows = useMemo(() => {
    if (!summary) return [];
    const cash = summary.cashPaymentsCents / 100;
    const credit = summary.creditPaymentsCents / 100;
    const debit = summary.debitPaymentsCents / 100;
    const total = cash + credit + debit;
    return [
      { key: "cash", name: "Cash", value: cash, color: PAYMENT_COLORS.cash },
      { key: "credit", name: "Credit", value: credit, color: PAYMENT_COLORS.credit },
      { key: "debit", name: "Debit", value: debit, color: PAYMENT_COLORS.debit },
    ].filter((r) => r.value > 0 || total === 0);
  }, [summary]);

  const total = rows.reduce((s, r) => s + r.value, 0);

  if (loading) {
    return (
      <div className="flex h-[260px] items-center justify-center text-slate-400">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-200 border-t-blue-600" />
      </div>
    );
  }

  if (!summary || total <= 0) {
    return (
      <p className="py-12 text-center text-sm text-slate-500">No payment data for this period.</p>
    );
  }

  const data = rows.map((r) => ({ ...r, pct: total > 0 ? (r.value / total) * 100 : 0 }));

  return (
    <div className="flex flex-col gap-6 lg:flex-row lg:items-center lg:justify-center">
      <div className="mx-auto h-[200px] w-[200px] shrink-0 lg:mx-0">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              dataKey="value"
              nameKey="name"
              cx="50%"
              cy="50%"
              innerRadius={58}
              outerRadius={82}
              paddingAngle={2}
              strokeWidth={0}
            >
              {data.map((entry) => (
                <Cell key={entry.key} fill={entry.color} />
              ))}
            </Pie>
            <Tooltip
              formatter={(value) =>
                formatMoney(typeof value === "number" ? value : Number(value) || 0)
              }
              contentStyle={{
                borderRadius: "12px",
                border: "1px solid #e2e8f0",
                fontSize: "13px",
              }}
            />
          </PieChart>
        </ResponsiveContainer>
      </div>
      <ul className="min-w-0 flex-1 space-y-3">
        {data.map((r) => (
          <li
            key={r.key}
            className="flex items-center justify-between gap-3 border-b border-slate-50 pb-3 last:border-0 last:pb-0"
          >
            <div className="flex items-center gap-2">
              <span
                className="h-2.5 w-2.5 shrink-0 rounded-full"
                style={{ backgroundColor: r.color }}
              />
              <span className="text-sm font-medium text-slate-700">{r.name}</span>
            </div>
            <div className="text-right">
              <p className="text-sm font-semibold tabular-nums text-slate-900">
                {formatMoney(r.value)}
              </p>
              <p className="text-xs text-slate-500">{r.pct.toFixed(1)}%</p>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

const ORDER_TYPE_META = [
  { key: "dineIn", label: "Dine-in", centsKey: "dineInCents" as const, fill: "#2563eb" },
  { key: "toGo", label: "To-go", centsKey: "toGoCents" as const, fill: "#0d9488" },
  { key: "bar", label: "Bar", centsKey: "barCents" as const, fill: "#d97706" },
];

function OrderTypesChart({
  data,
  loading,
}: {
  data: SalesByOrderType | null;
  loading: boolean;
}) {
  const chartData = useMemo(() => {
    if (!data) return [];
    return ORDER_TYPE_META.map((m) => ({
      name: m.label,
      amount: data[m.centsKey] / 100,
      fill: m.fill,
    })).filter((d) => d.amount > 0);
  }, [data]);

  if (loading) {
    return (
      <div className="flex h-[220px] items-center justify-center text-slate-400">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-200 border-t-blue-600" />
      </div>
    );
  }

  if (chartData.length === 0) {
    return (
      <p className="py-10 text-center text-sm text-slate-500">No orders by type in this period.</p>
    );
  }

  return (
    <div className="h-[240px] w-full min-w-0">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart
          data={chartData}
          layout="vertical"
          margin={{ top: 4, right: 16, left: 8, bottom: 4 }}
        >
          <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" horizontal={false} />
          <XAxis type="number" hide />
          <YAxis
            type="category"
            dataKey="name"
            width={72}
            tick={{ fontSize: 12, fill: "#64748b" }}
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
          <Bar dataKey="amount" radius={[0, 6, 6, 0]} barSize={28}>
            {chartData.map((entry) => (
              <Cell key={entry.name} fill={entry.fill} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

function FinancialBreakdownCard({
  summary,
  loading,
}: {
  summary: DailySalesSummary | null;
  loading: boolean;
}) {
  if (loading || !summary) {
    return (
      <ChartPanel title="Financial Breakdown">
        <div className="flex h-40 items-center justify-center text-slate-400">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-slate-200 border-t-blue-600" />
        </div>
      </ChartPanel>
    );
  }

  const rows = [
    {
      label: "Discounts",
      value: summary.discountsCents / 100,
      className: "text-red-600",
    },
    {
      label: "Refunds",
      value: summary.refundsCents / 100,
      className: "text-red-600",
    },
    {
      label: "Taxes",
      value: summary.taxCollectedCents / 100,
      className: "text-slate-800",
    },
    {
      label: "Tips",
      value: summary.tipsCollectedCents / 100,
      className: "text-slate-800",
    },
  ];

  return (
    <ChartPanel title="Financial Breakdown">
      <ul className="divide-y divide-slate-100">
        {rows.map((r) => (
          <li key={r.label} className="flex items-center justify-between py-3 first:pt-0">
            <span className={`text-sm font-medium ${r.className}`}>{r.label}</span>
            <span className={`text-sm font-semibold tabular-nums ${r.className}`}>
              {formatMoney(r.value)}
            </span>
          </li>
        ))}
      </ul>
    </ChartPanel>
  );
}

function PeriodSegmented({
  value,
  onChange,
}: {
  value: ReportPeriod;
  onChange: (p: ReportPeriod) => void;
}) {
  const options: { id: ReportPeriod; label: string }[] = [
    { id: "today", label: "Today" },
    { id: "week", label: "Last 7 Days" },
    { id: "month", label: "This Month" },
  ];
  return (
    <div className="inline-flex rounded-xl bg-slate-100/90 p-1 shadow-inner">
      {options.map((opt) => (
        <button
          key={opt.id}
          type="button"
          onClick={() => onChange(opt.id)}
          className={`rounded-lg px-4 py-2 text-sm font-medium transition-all ${
            value === opt.id
              ? "bg-white text-slate-900 shadow-sm"
              : "text-slate-600 hover:text-slate-900"
          }`}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}

export default function OverviewReportClient() {
  const { user } = useAuth();
  const [period, setPeriod] = useState<ReportPeriod>("today");
  const [loading, setLoading] = useState(true);
  const [usingMock, setUsingMock] = useState(false);
  const [summary, setSummary] = useState<DailySalesSummary | null>(null);
  const [hourly, setHourly] = useState<HourlySale[]>([]);
  const [orderTypes, setOrderTypes] = useState<SalesByOrderType | null>(null);
  const [lineData, setLineData] = useState<HourlySalesPoint[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    setUsingMock(false);

    const enginePeriod = period === "today" ? "today" : period === "week" ? "week" : "month";
    const { start, end } = periodRange(enginePeriod);

    if (!user) {
      setSummary(MOCK_SUMMARY);
      setHourly(mockHourlySales());
      setOrderTypes(MOCK_ORDER_TYPES);
      setLineData(
        period === "today"
          ? fillHourlyLinePoints(mockHourlySales())
          : mockDailyLinePoints()
      );
      setUsingMock(true);
      setLoading(false);
      return;
    }

    try {
      const [s, h, o] = await Promise.all([
        getDailySalesSummary(start, end),
        getHourlySales(start, end),
        getSalesByOrderType(start, end),
      ]);

      let trend: HourlySalesPoint[];
      if (period === "today") {
        trend = fillHourlyLinePoints(h);
      } else {
        trend = [];
        try {
          const snap = await getDocs(
            query(
              collection(db, "Orders"),
              where("createdAt", ">=", Timestamp.fromDate(start)),
              where("createdAt", "<", Timestamp.fromDate(end))
            )
          );
          const inclusiveEnd = new Date(end.getTime() - 1);
          trend = buildDailySalesPoints(snap, start, inclusiveEnd);
        } catch (e) {
          console.warn("Overview daily sales trend query failed:", e);
        }
      }

      setSummary(s);
      setHourly(h);
      setOrderTypes(o);
      setLineData(trend);
    } catch (e) {
      console.error("Overview report load failed:", e);
      setSummary(MOCK_SUMMARY);
      setHourly(mockHourlySales());
      setOrderTypes(MOCK_ORDER_TYPES);
      setLineData(
        period === "today"
          ? fillHourlyLinePoints(mockHourlySales())
          : mockDailyLinePoints()
      );
      setUsingMock(true);
    } finally {
      setLoading(false);
    }
  }, [user, period]);

  useEffect(() => {
    load();
  }, [load]);

  const hourlyBarData = useMemo(() => {
    const map = new Map<number, number>();
    for (const row of hourly) {
      map.set(row.hour, row.totalCents / 100);
    }
    return Array.from({ length: 24 }, (_, hour) => ({
      label: formatHourLabel(hour),
      revenue: map.get(hour) ?? 0,
    }));
  }, [hourly]);

  const grossDisplay = summary ? formatMoney(summary.grossSalesCents / 100) : "—";
  const netDisplay = summary ? formatMoney(summary.netSalesCents / 100) : "—";
  const ordersDisplay = summary ? String(summary.totalTransactions) : "—";
  const avgDisplay = summary ? formatMoney(summary.averageTicketCents / 100) : "—";

  return (
    <>
      <Header title="Overview Report" />

      <div className="space-y-5 p-5 sm:space-y-6 sm:p-6">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <PeriodSegmented value={period} onChange={setPeriod} />
          {usingMock && (
            <p className="text-xs font-medium text-amber-700">
              Sample data (sign in and sync Firestore to load live numbers).
            </p>
          )}
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4 lg:gap-5">
          <KpiCard value={grossDisplay} label="Gross sales" />
          <KpiCard value={netDisplay} label="Net sales" />
          <KpiCard value={ordersDisplay} label="Total orders" />
          <KpiCard value={avgDisplay} label="Average ticket" />
        </div>

        <ChartPanel title="Sales Over Time">
          <SalesChart
            data={lineData}
            loading={loading}
            variant="embedded"
            lineType="natural"
            height={300}
            xAxisTickInterval={period === "today" ? 2 : 0}
            emptyTitle="No sales in this period"
            emptyDescription="Try another date range or record sales in the POS."
          />
        </ChartPanel>

        <div className="grid grid-cols-1 gap-5 lg:grid-cols-2 lg:gap-6">
          <ChartPanel title="Hourly Sales">
            {loading ? (
              <div className="flex h-[280px] items-center justify-center text-slate-400">
                <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-200 border-t-blue-600" />
              </div>
            ) : hourlyBarData.every((d) => d.revenue <= 0) ? (
              <p className="py-12 text-center text-sm text-slate-500">
                No hourly sales for this period.
              </p>
            ) : (
              <div className="h-[300px] w-full min-w-0">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={hourlyBarData} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" vertical={false} />
                    <XAxis
                      dataKey="label"
                      tick={{ fontSize: 10, fill: "#64748b" }}
                      tickLine={false}
                      axisLine={{ stroke: "#e2e8f0" }}
                      interval={2}
                    />
                    <YAxis
                      tick={{ fontSize: 11, fill: "#64748b" }}
                      tickLine={false}
                      axisLine={false}
                      tickFormatter={(v) =>
                        v >= 1000 ? `$${(v / 1000).toFixed(1)}k` : `$${v}`
                      }
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
                    <Bar
                      dataKey="revenue"
                      fill="#3b82f6"
                      radius={[4, 4, 0, 0]}
                      maxBarSize={32}
                    />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </ChartPanel>

          <ChartPanel title="Payment Methods">
            <PaymentDonut summary={summary} loading={loading} />
          </ChartPanel>

          <ChartPanel title="Order Types">
            <OrderTypesChart data={orderTypes} loading={loading} />
          </ChartPanel>

          <FinancialBreakdownCard summary={summary} loading={loading} />
        </div>
      </div>
    </>
  );
}
