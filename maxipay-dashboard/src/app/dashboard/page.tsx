"use client";

import { useEffect, useState } from "react";
import {
  collection,
  query,
  where,
  getDocs,
  Timestamp,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import StatCard from "@/components/StatCard";
import OrdersTable, { Order } from "@/components/OrdersTable";
import { DollarSign, Receipt, TrendingUp, Wallet } from "lucide-react";

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
          now.getDate()
        );
        const endOfDay = new Date(
          now.getFullYear(),
          now.getMonth(),
          now.getDate(),
          23,
          59,
          59
        );

        const ordersRef = collection(db, "orders");
        const q = query(
          ordersRef,
          where("merchantId", "==", user.uid),
          where("createdAt", ">=", Timestamp.fromDate(startOfDay)),
          where("createdAt", "<=", Timestamp.fromDate(endOfDay))
        );

        const snapshot = await getDocs(q);

        let totalSales = 0;
        let totalTips = 0;
        const ordersList: Order[] = [];

        snapshot.forEach((doc) => {
          const data = doc.data();
          const total = data.total || 0;
          const tip = data.tip || 0;

          totalSales += total;
          totalTips += tip;

          const createdAt = data.createdAt?.toDate?.() || new Date();

          ordersList.push({
            id: doc.id,
            orderNumber: data.orderNumber || doc.id.slice(-6),
            orderType: data.orderType || "dine-in",
            total,
            status: data.status || "completed",
            date: createdAt.toLocaleDateString(),
            time: createdAt.toLocaleTimeString([], {
              hour: "2-digit",
              minute: "2-digit",
            }),
            source: data.source || "pos",
            externalOrderId: data.externalOrderId || null,
          });
        });

        const txCount = snapshot.size;
        setSalesToday(totalSales);
        setTransactionsToday(txCount);
        setTipsToday(totalTips);
        setAvgTicket(txCount > 0 ? totalSales / txCount : 0);
        setRecentOrders(ordersList.slice(0, 10));
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
