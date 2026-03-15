"use client";

import { useEffect, useState } from "react";
import {
  collection,
  query,
  where,
  getDocs,
  orderBy,
  limit,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import OrdersTable, { Order } from "@/components/OrdersTable";
import { Filter, Download } from "lucide-react";

export default function OrdersPage() {
  const { user } = useAuth();
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState<string>("all");

  useEffect(() => {
    if (!user) return;

    const fetchOrders = async () => {
      setLoading(true);
      try {
        const ordersRef = collection(db, "orders");
        let q = query(
          ordersRef,
          where("merchantId", "==", user.uid),
          orderBy("createdAt", "desc"),
          limit(50)
        );

        if (statusFilter !== "all") {
          q = query(
            ordersRef,
            where("merchantId", "==", user.uid),
            where("status", "==", statusFilter),
            orderBy("createdAt", "desc"),
            limit(50)
          );
        }

        const snapshot = await getDocs(q);
        const ordersList: Order[] = [];

        snapshot.forEach((doc) => {
          const data = doc.data();
          const createdAt = data.createdAt?.toDate?.() || new Date();

          ordersList.push({
            id: doc.id,
            orderNumber: data.orderNumber || doc.id.slice(-6),
            orderType: data.orderType || "dine-in",
            total: data.total || 0,
            status: data.status || "completed",
            date: createdAt.toLocaleDateString(),
            time: createdAt.toLocaleTimeString([], {
              hour: "2-digit",
              minute: "2-digit",
            }),
          });
        });

        setOrders(ordersList);
      } catch (error) {
        console.error("Error fetching orders:", error);
      } finally {
        setLoading(false);
      }
    };

    fetchOrders();
  }, [user, statusFilter]);

  return (
    <>
      <Header title="Orders" />
      <div className="p-6 space-y-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="relative">
              <Filter
                size={16}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
              />
              <select
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
                className="pl-9 pr-8 py-2 rounded-xl bg-white border border-slate-200 text-sm text-slate-700 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 appearance-none cursor-pointer"
              >
                <option value="all">All Status</option>
                <option value="completed">Completed</option>
                <option value="pending">Pending</option>
                <option value="cancelled">Cancelled</option>
                <option value="refunded">Refunded</option>
              </select>
            </div>
          </div>

          <button className="flex items-center gap-2 px-4 py-2 rounded-xl bg-white border border-slate-200 text-sm text-slate-700 hover:bg-slate-50 transition-colors">
            <Download size={16} />
            Export
          </button>
        </div>

        <OrdersTable orders={orders} loading={loading} />

        <div className="flex items-center justify-between text-sm text-slate-500">
          <p>Showing {orders.length} orders</p>
        </div>
      </div>
    </>
  );
}
