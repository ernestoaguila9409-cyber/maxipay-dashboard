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
import {
  DollarSign,
  Receipt,
  TrendingUp,
  Wallet,
  Calendar,
} from "lucide-react";

export default function ReportsPage() {
  const { user } = useAuth();
  const [period, setPeriod] = useState<"today" | "week" | "month">("today");
  const [totalSales, setTotalSales] = useState(0);
  const [totalOrders, setTotalOrders] = useState(0);
  const [totalTips, setTotalTips] = useState(0);
  const [avgTicket, setAvgTicket] = useState(0);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!user) return;

    const fetchReports = async () => {
      setLoading(true);
      try {
        const now = new Date();
        let startDate: Date;

        switch (period) {
          case "week":
            startDate = new Date(now);
            startDate.setDate(now.getDate() - 7);
            startDate.setHours(0, 0, 0, 0);
            break;
          case "month":
            startDate = new Date(now.getFullYear(), now.getMonth(), 1);
            break;
          default:
            startDate = new Date(
              now.getFullYear(),
              now.getMonth(),
              now.getDate()
            );
        }

        const ordersRef = collection(db, "orders");
        const q = query(
          ordersRef,
          where("merchantId", "==", user.uid),
          where("createdAt", ">=", Timestamp.fromDate(startDate))
        );

        const snapshot = await getDocs(q);

        let sales = 0;
        let tips = 0;

        snapshot.forEach((doc) => {
          const data = doc.data();
          sales += data.total || 0;
          tips += data.tip || 0;
        });

        const count = snapshot.size;
        setTotalSales(sales);
        setTotalOrders(count);
        setTotalTips(tips);
        setAvgTicket(count > 0 ? sales / count : 0);
      } catch (error) {
        console.error("Error fetching reports:", error);
      } finally {
        setLoading(false);
      }
    };

    fetchReports();
  }, [user, period]);

  const periodLabels = { today: "Today", week: "Last 7 Days", month: "This Month" };

  return (
    <>
      <Header title="Reports" />
      <div className="p-6 space-y-6">
        <div className="flex items-center gap-2">
          <Calendar size={18} className="text-slate-400" />
          <div className="flex bg-white rounded-xl border border-slate-200 p-1">
            {(["today", "week", "month"] as const).map((p) => (
              <button
                key={p}
                onClick={() => setPeriod(p)}
                className={`px-4 py-1.5 rounded-lg text-sm font-medium transition-all ${
                  period === p
                    ? "bg-blue-600 text-white"
                    : "text-slate-600 hover:bg-slate-50"
                }`}
              >
                {periodLabels[p]}
              </button>
            ))}
          </div>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          </div>
        ) : (
          <>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
              <StatCard
                title="Total Sales"
                value={`$${totalSales.toFixed(2)}`}
                icon={DollarSign}
                color="blue"
              />
              <StatCard
                title="Total Orders"
                value={totalOrders.toString()}
                icon={Receipt}
                color="green"
              />
              <StatCard
                title="Total Tips"
                value={`$${totalTips.toFixed(2)}`}
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

            <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-8 text-center">
              <p className="text-slate-400">
                Detailed charts and graphs coming soon
              </p>
              <p className="text-sm text-slate-300 mt-1">
                Revenue trends, category breakdowns, and more
              </p>
            </div>
          </>
        )}
      </div>
    </>
  );
}
