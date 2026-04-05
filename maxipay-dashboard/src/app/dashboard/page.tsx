"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  collection,
  query,
  orderBy,
  limit,
  where,
  getDocs,
  type DocumentData,
  type QuerySnapshot,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import MetricCard from "@/components/MetricCard";
import SalesChart, { type HourlySalesPoint } from "@/components/SalesChart";
import PaymentBreakdown from "@/components/PaymentBreakdown";
import RecentOrdersGrid from "@/components/RecentOrdersGrid";
import TopItemsToday from "@/components/TopItemsToday";
import { type Order } from "@/components/OrdersTable";
import { buildTopItemsForDashboard, type TopItemRow } from "@/lib/dashboardTopItems";
import { DollarSign, Receipt, TrendingUp, RotateCcw } from "lucide-react";
import { mergeOrderSnapshots } from "@/lib/orderDisplayUtils";
import {
  aggregatePaymentBreakdownFromTransactionIds,
  buildHourlySalesPoints,
} from "@/lib/dashboardFinance";

const FETCH_LIMIT = 300;
const OPEN_ORDERS_LIMIT = 500;
const PLACEHOLDER_TREND = "+5% vs yesterday";

type DashboardPeriod = "today" | "yesterday" | "7d";

export default function DashboardPage() {
  const { user } = useAuth();
  const [period, setPeriod] = useState<DashboardPeriod>("today");
  const [netSales, setNetSales] = useState(0);
  const [ordersToday, setOrdersToday] = useState(0);
  const [avgTicket, setAvgTicket] = useState(0);
  const [refundsToday, setRefundsToday] = useState(0);
  const [recentOrders, setRecentOrders] = useState<Order[]>([]);
  const [topItemsToday, setTopItemsToday] = useState<TopItemRow[]>([]);
  const [hourlySales, setHourlySales] = useState<HourlySalesPoint[]>([]);
  const [paymentBreakdown, setPaymentBreakdown] = useState({
    card: 0,
    cash: 0,
    other: 0,
  });
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!user) return;

    const fetchDashboardData = async () => {
      try {
        const now = new Date();
        const startOfDay = new Date(
          now.getFullYear(),
          now.getMonth(),
          now.getDate(),
          0,
          0,
          0,
          0
        );
        const endOfDay = new Date(
          now.getFullYear(),
          now.getMonth(),
          now.getDate(),
          23,
          59,
          59,
          999
        );
        const yesterdayStart = new Date(
          now.getFullYear(),
          now.getMonth(),
          now.getDate() - 1,
          0,
          0,
          0,
          0
        );
        const yesterdayEnd = new Date(
          now.getFullYear(),
          now.getMonth(),
          now.getDate() - 1,
          23,
          59,
          59,
          999
        );

        const qRecent = query(
          collection(db, "Orders"),
          orderBy("createdAt", "desc"),
          limit(FETCH_LIMIT)
        );
        const qOpen = query(
          collection(db, "Orders"),
          where("status", "==", "OPEN"),
          orderBy("createdAt", "desc"),
          limit(OPEN_ORDERS_LIMIT)
        );

        const snapshotRecent = await getDocs(qRecent);
        let snapshotOpen: QuerySnapshot<DocumentData> | null = null;
        try {
          snapshotOpen = await getDocs(qOpen);
        } catch (e) {
          console.error(
            "OPEN orders query failed (deploy firestore.indexes.json):",
            e
          );
        }

        let totalSalesCents = 0;
        let totalRefundsCents = 0;
        let txToday = 0;
        const saleTransactionIds: string[] = [];

        snapshotRecent.forEach((docSnap) => {
          const data = docSnap.data();
          const createdAt = data.createdAt?.toDate?.() ?? new Date();
          if (createdAt < startOfDay || createdAt > endOfDay) {
            return;
          }

          const status = String(data.status ?? "");
          if (status === "VOIDED") {
            return;
          }

          txToday += 1;

          const paid = Number(data.totalPaidInCents ?? 0);
          const totalIn = Number(data.totalInCents ?? 0);
          totalSalesCents += paid > 0 ? paid : totalIn;
          totalRefundsCents += Number(data.totalRefundedInCents ?? 0);

          const sid = String(data.saleTransactionId ?? "").trim();
          if (sid) {
            saleTransactionIds.push(sid);
          }
        });

        const mergedForTable = mergeOrderSnapshots(
          snapshotRecent,
          snapshotOpen
        );

        const salesDollars = totalSalesCents / 100;
        const refundsDollars = totalRefundsCents / 100;

        setNetSales(salesDollars);
        setOrdersToday(txToday);
        setAvgTicket(txToday > 0 ? salesDollars / txToday : 0);
        setRefundsToday(refundsDollars);
        setRecentOrders(mergedForTable.slice(0, 15));

        setHourlySales(
          buildHourlySalesPoints(snapshotRecent, startOfDay, endOfDay)
        );

        const payments = await aggregatePaymentBreakdownFromTransactionIds(
          saleTransactionIds
        );
        setPaymentBreakdown(payments);

        try {
          const top = await buildTopItemsForDashboard(
            snapshotRecent,
            startOfDay,
            endOfDay,
            yesterdayStart,
            yesterdayEnd
          );
          setTopItemsToday(top);
        } catch (e) {
          console.error("Top items aggregation failed:", e);
          setTopItemsToday([]);
        }
      } catch (error) {
        console.error("Error fetching dashboard data:", error);
      } finally {
        setLoading(false);
      }
    };

    fetchDashboardData();
  }, [user]);

  return (
    <>
      <Header title="Dashboard" />
      <div className="p-4 sm:p-6 space-y-6 max-w-7xl mx-auto">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div>
            <p className="text-sm text-slate-500">
              How is the business doing{" "}
              <span className="font-medium text-slate-700">today</span>?
            </p>
            <p className="text-xs text-slate-400 mt-1">
              {period === "today" && "Figures below are for today."}
              {period === "yesterday" &&
                "Yesterday view is not wired yet — still showing today."}
              {period === "7d" &&
                "Last 7 days view is not wired yet — still showing today."}
            </p>
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
            subtext={PLACEHOLDER_TREND}
            icon={DollarSign}
            accent="blue"
          />
          <MetricCard
            label="Orders"
            value={ordersToday.toString()}
            subtext={PLACEHOLDER_TREND}
            icon={Receipt}
            accent="emerald"
          />
          <MetricCard
            label="Average ticket"
            value={`$${avgTicket.toFixed(2)}`}
            subtext={PLACEHOLDER_TREND}
            icon={TrendingUp}
            accent="violet"
          />
          <MetricCard
            label="Refunds"
            value={`$${refundsToday.toFixed(2)}`}
            subtext={PLACEHOLDER_TREND}
            icon={RotateCcw}
            accent="amber"
          />
        </div>

        <section className="space-y-3">
          <h2 className="text-lg font-semibold text-slate-800">Sales today</h2>
          <SalesChart
            data={hourlySales}
            loading={loading}
            emptyTitle="No sales yet today"
            emptyDescription="Start taking orders to see data here."
          />
        </section>

        <TopItemsToday items={topItemsToday} loading={loading} />

        <section className="space-y-3">
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <h2 className="text-lg font-semibold text-slate-800">
              Recent orders
            </h2>
            <Link
              href="/dashboard/orders"
              className="inline-flex items-center justify-center text-sm font-medium text-blue-600 hover:text-blue-700"
            >
              View all orders →
            </Link>
          </div>
          <RecentOrdersGrid
            orders={recentOrders}
            loading={loading}
            emptyMessage="No orders yet today"
            emptySubMessage="Start taking orders on the POS — they appear here in real time."
          />
        </section>

        <section>
          <PaymentBreakdown totals={paymentBreakdown} loading={loading} />
        </section>
      </div>
    </>
  );
}
