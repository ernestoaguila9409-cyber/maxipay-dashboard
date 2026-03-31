"use client";

import { useEffect, useState } from "react";
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
import StatCard from "@/components/StatCard";
import OrdersTable, { type Order } from "@/components/OrdersTable";
import { DollarSign, Receipt, TrendingUp, Wallet } from "lucide-react";
import { mergeOrderSnapshots } from "@/lib/orderDisplayUtils";

const FETCH_LIMIT = 300;
const OPEN_ORDERS_LIMIT = 500;

export default function DashboardPage() {
  const { user } = useAuth();
  const [salesToday, setSalesToday] = useState(0);
  const [transactionsToday, setTransactionsToday] = useState(0);
  const [tipsToday, setTipsToday] = useState(0);
  const [avgTicket, setAvgTicket] = useState(0);
  const [recentOrders, setRecentOrders] = useState<Order[]>([]);
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
        let totalTipsCents = 0;
        let txToday = 0;

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
          totalTipsCents += Number(data.tipAmountInCents ?? 0);
        });

        const mergedForTable = mergeOrderSnapshots(
          snapshotRecent,
          snapshotOpen
        );

        const salesDollars = totalSalesCents / 100;
        const tipsDollars = totalTipsCents / 100;

        setSalesToday(salesDollars);
        setTransactionsToday(txToday);
        setTipsToday(tipsDollars);
        setAvgTicket(txToday > 0 ? salesDollars / txToday : 0);
        setRecentOrders(mergedForTable.slice(0, 10));
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
      <div className="p-6 space-y-6">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <StatCard
            title="Sales Today"
            value={`$${salesToday.toFixed(2)}`}
            icon={DollarSign}
            color="blue"
          />
          <StatCard
            title="Transactions Today"
            value={transactionsToday.toString()}
            icon={Receipt}
            color="green"
          />
          <StatCard
            title="Tips Today"
            value={`$${tipsToday.toFixed(2)}`}
            icon={Wallet}
            color="purple"
          />
          <StatCard
            title="Average Ticket"
            value={`$${avgTicket.toFixed(2)}`}
            icon={TrendingUp}
            color="orange"
          />
        </div>

        <div>
          <h2 className="text-lg font-semibold text-slate-800 mb-4">
            Recent Orders
          </h2>
          <OrdersTable orders={recentOrders} loading={loading} />
        </div>
      </div>
    </>
  );
}
