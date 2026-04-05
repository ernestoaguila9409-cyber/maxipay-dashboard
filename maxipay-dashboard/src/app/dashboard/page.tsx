"use client";

import { useEffect, useMemo, useState } from "react";
import {
  collection,
  query,
  orderBy,
  limit,
  where,
  getDocs,
  Timestamp,
  type DocumentData,
  type QueryDocumentSnapshot,
  type QuerySnapshot,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import MetricCard from "@/components/MetricCard";
import SalesChart, { type HourlySalesPoint } from "@/components/SalesChart";
import DashboardFinancesWidget, {
  type OpenBatchSummary,
} from "@/components/DashboardFinancesWidget";
import TopItemsToday from "@/components/TopItemsToday";
import { buildTopItemsForDashboard, type TopItemRow } from "@/lib/dashboardTopItems";
import { DollarSign, Receipt, TrendingUp, RotateCcw } from "lucide-react";
import {
  aggregateDashboardPeriod,
  aggregatePaymentBreakdownFromTransactionIds,
  buildDailySalesPoints,
  buildHourlySalesPoints,
  endOfLocalDay,
  formatTrendVsPrior,
  startOfLocalDay,
} from "@/lib/dashboardFinance";

/** Orders fetched for dashboard (14-day lookback for 7d + compare + top items). */
const ORDERS_FETCH_LIMIT = 2500;

type DashboardPeriod = "today" | "yesterday" | "7d";

interface PeriodRanges {
  primaryStart: Date;
  primaryEnd: Date;
  compareStart: Date;
  compareEnd: Date;
  priorPhrase: string;
  isHourlyChart: boolean;
}

function getPeriodRanges(period: DashboardPeriod, now: Date): PeriodRanges {
  const todayEnd = endOfLocalDay(now, 0);
  const todayStart = startOfLocalDay(now, 0);
  const yesterdayStart = startOfLocalDay(now, -1);
  const yesterdayEnd = endOfLocalDay(now, -1);
  const dayBeforeStart = startOfLocalDay(now, -2);
  const dayBeforeEnd = endOfLocalDay(now, -2);

  if (period === "today") {
    return {
      primaryStart: todayStart,
      primaryEnd: todayEnd,
      compareStart: yesterdayStart,
      compareEnd: yesterdayEnd,
      priorPhrase: "vs yesterday",
      isHourlyChart: true,
    };
  }
  if (period === "yesterday") {
    return {
      primaryStart: yesterdayStart,
      primaryEnd: yesterdayEnd,
      compareStart: dayBeforeStart,
      compareEnd: dayBeforeEnd,
      priorPhrase: "vs prior day",
      isHourlyChart: true,
    };
  }
  return {
    primaryStart: startOfLocalDay(now, -6),
    primaryEnd: todayEnd,
    compareStart: startOfLocalDay(now, -13),
    compareEnd: endOfLocalDay(now, -7),
    priorPhrase: "vs prior 7 days",
    isHourlyChart: false,
  };
}

function parseOpenBatchDoc(
  docSnap: QueryDocumentSnapshot<DocumentData>
): OpenBatchSummary {
  const d = docSnap.data();
  const createdAt =
    d.createdAt && typeof d.createdAt.toDate === "function"
      ? d.createdAt.toDate()
      : null;
  return {
    id: docSnap.id,
    createdAt,
    transactionCounter: Math.round(
      typeof d.transactionCounter === "number"
        ? d.transactionCounter
        : Number(d.transactionCounter ?? 0)
    ),
    total: typeof d.total === "number" ? d.total : Number(d.total ?? 0),
    count: typeof d.count === "number" ? d.count : Number(d.count ?? 0),
  };
}

interface MetricTrends {
  sales: string;
  orders: string;
  avgTicket: string;
  refunds: string;
}

const emptyTrend = "—";

export default function DashboardPage() {
  const { user } = useAuth();
  const [period, setPeriod] = useState<DashboardPeriod>("today");
  const [netSales, setNetSales] = useState(0);
  const [ordersCount, setOrdersCount] = useState(0);
  const [avgTicket, setAvgTicket] = useState(0);
  const [refundsTotal, setRefundsTotal] = useState(0);
  const [trends, setTrends] = useState<MetricTrends>({
    sales: emptyTrend,
    orders: emptyTrend,
    avgTicket: emptyTrend,
    refunds: emptyTrend,
  });
  const [topItems, setTopItems] = useState<TopItemRow[]>([]);
  const [hourlySales, setHourlySales] = useState<HourlySalesPoint[]>([]);
  const [paymentBreakdown, setPaymentBreakdown] = useState({
    card: 0,
    cash: 0,
    other: 0,
  });
  const [openBatch, setOpenBatch] = useState<OpenBatchSummary | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!user) return;

    let cancelled = false;

    const run = async () => {
      setLoading(true);
      try {
        const now = new Date();
        const ranges = getPeriodRanges(period, now);
        const fetchStart = startOfLocalDay(now, -13);
        const fetchEnd = endOfLocalDay(now, 0);

        let snapshotRecent: QuerySnapshot<DocumentData>;
        try {
          snapshotRecent = await getDocs(
            query(
              collection(db, "Orders"),
              where("createdAt", ">=", Timestamp.fromDate(fetchStart)),
              where("createdAt", "<=", Timestamp.fromDate(fetchEnd)),
              orderBy("createdAt", "desc"),
              limit(ORDERS_FETCH_LIMIT)
            )
          );
        } catch (boundedErr) {
          console.warn(
            "Date-bounded Orders query failed; falling back to recent limit:",
            boundedErr
          );
          snapshotRecent = await getDocs(
            query(
              collection(db, "Orders"),
              orderBy("createdAt", "desc"),
              limit(800)
            )
          );
        }

        if (cancelled) return;

        const current = aggregateDashboardPeriod(
          snapshotRecent,
          ranges.primaryStart,
          ranges.primaryEnd
        );
        const previous = aggregateDashboardPeriod(
          snapshotRecent,
          ranges.compareStart,
          ranges.compareEnd
        );

        const salesDollars = current.totalSalesCents / 100;
        const refundsDollars = current.totalRefundsCents / 100;
        const prevSales = previous.totalSalesCents / 100;
        const prevRefunds = previous.totalRefundsCents / 100;

        const curAvg =
          current.orderCount > 0
            ? current.totalSalesCents / current.orderCount / 100
            : 0;
        const prevAvg =
          previous.orderCount > 0
            ? previous.totalSalesCents / previous.orderCount / 100
            : 0;

        const phrase = ranges.priorPhrase;

        setNetSales(salesDollars);
        setOrdersCount(current.orderCount);
        setAvgTicket(curAvg);
        setRefundsTotal(refundsDollars);
        setTrends({
          sales: formatTrendVsPrior(salesDollars, prevSales, phrase),
          orders: formatTrendVsPrior(
            current.orderCount,
            previous.orderCount,
            phrase
          ),
          avgTicket: formatTrendVsPrior(curAvg, prevAvg, phrase),
          refunds: formatTrendVsPrior(refundsDollars, prevRefunds, phrase),
        });

        if (ranges.isHourlyChart) {
          setHourlySales(
            buildHourlySalesPoints(
              snapshotRecent,
              ranges.primaryStart,
              ranges.primaryEnd
            )
          );
        } else {
          setHourlySales(
            buildDailySalesPoints(
              snapshotRecent,
              ranges.primaryStart,
              ranges.primaryEnd
            )
          );
        }

        const [payments, batchesSnap] = await Promise.all([
          aggregatePaymentBreakdownFromTransactionIds(
            current.saleTransactionIds
          ),
          getDocs(
            query(
              collection(db, "Batches"),
              where("closed", "==", false),
              limit(1)
            )
          ),
        ]);

        if (cancelled) return;

        setPaymentBreakdown(payments);
        const b0 = batchesSnap.docs[0];
        setOpenBatch(b0 ? parseOpenBatchDoc(b0) : null);

        try {
          const top = await buildTopItemsForDashboard(
            snapshotRecent,
            ranges.primaryStart,
            ranges.primaryEnd,
            ranges.compareStart,
            ranges.compareEnd,
            period === "7d" ? 200 : 120
          );
          if (!cancelled) setTopItems(top);
        } catch (e) {
          console.error("Top items aggregation failed:", e);
          if (!cancelled) setTopItems([]);
        }
      } catch (error) {
        console.error("Error fetching dashboard data:", error);
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    void run();
    return () => {
      cancelled = true;
    };
  }, [user, period]);

  const periodHeadline =
    period === "today"
      ? "today"
      : period === "yesterday"
        ? "yesterday"
        : "the last 7 days";

  const periodSubtext =
    period === "today"
      ? "Figures below are for today (local time), aligned with POS order timestamps."
      : period === "yesterday"
        ? "Figures below are for yesterday (local time)."
        : "Figures below are for the last 7 calendar days including today (local time).";

  const chartTitle = period === "7d" ? "Sales by day" : "Sales by hour";
  const chartEmptyDesc =
    "Try another date range or record sales on the POS — data uses the same Firestore Orders as the app.";

  const topSectionTitle =
    period === "today"
      ? "Top items today"
      : period === "yesterday"
        ? "Top items yesterday"
        : "Top items (last 7 days)";
  const topCompareSubtitle =
    period === "today"
      ? "vs yesterday · closed orders only"
      : period === "yesterday"
        ? "vs prior day · closed orders only"
        : "vs previous 7 days · closed orders only";

  const collectedSubtitle = useMemo(() => {
    const sum =
      paymentBreakdown.card + paymentBreakdown.cash + paymentBreakdown.other;
    const money = new Intl.NumberFormat("en-US", {
      style: "currency",
      currency: "USD",
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(sum);
    const when =
      period === "today"
        ? "today"
        : period === "yesterday"
          ? "yesterday"
          : "in the last 7 days";
    return `${money} collected ${when}`;
  }, [paymentBreakdown, period]);

  return (
    <>
      <Header title="Dashboard" />
      <div className="p-4 sm:p-6 space-y-6 max-w-7xl mx-auto">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div>
            <p className="text-sm text-slate-500">
              How is the business doing{" "}
              <span className="font-medium text-slate-700">{periodHeadline}</span>
              ?
            </p>
            <p className="text-xs text-slate-400 mt-1">{periodSubtext}</p>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <label htmlFor="dashboard-period" className="sr-only">
              Date range
            </label>
            <select
              id="dashboard-period"
              value={period}
              onChange={(e) =>
                setPeriod(e.target.value as DashboardPeriod)
              }
              className="text-sm border border-slate-200 rounded-lg px-3 py-2 bg-white text-slate-700 shadow-sm focus:outline-none focus:ring-2 focus:ring-blue-500/30"
            >
              <option value="today">Today</option>
              <option value="yesterday">Yesterday</option>
              <option value="7d">Last 7 days</option>
            </select>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          <MetricCard
            label="Net sales"
            value={`$${netSales.toFixed(2)}`}
            subtext={trends.sales}
            icon={DollarSign}
            accent="blue"
          />
          <MetricCard
            label="Orders"
            value={ordersCount.toString()}
            subtext={trends.orders}
            icon={Receipt}
            accent="emerald"
          />
          <MetricCard
            label="Average ticket"
            value={`$${avgTicket.toFixed(2)}`}
            subtext={trends.avgTicket}
            icon={TrendingUp}
            accent="violet"
          />
          <MetricCard
            label="Refunds"
            value={`$${refundsTotal.toFixed(2)}`}
            subtext={trends.refunds}
            icon={RotateCcw}
            accent="amber"
          />
        </div>

        <section className="space-y-3">
          <h2 className="text-lg font-semibold text-slate-800">{chartTitle}</h2>
          <SalesChart
            data={hourlySales}
            loading={loading}
            emptyTitle="No sales in this range"
            emptyDescription={chartEmptyDesc}
            xAxisTickInterval={period === "7d" ? 0 : 2}
          />
        </section>

        <TopItemsToday
          items={topItems}
          loading={loading}
          sectionTitle={topSectionTitle}
          compareSubtitle={topCompareSubtitle}
        />

        <section>
          <DashboardFinancesWidget
            totals={paymentBreakdown}
            collectedSubtitle={collectedSubtitle}
            openBatch={openBatch}
            loading={loading}
          />
        </section>
      </div>
    </>
  );
}
