"use client";

import { Clock, CheckCircle2, XCircle, AlertCircle } from "lucide-react";

export type OrderSource = "pos" | "kitchenhub" | "ubereats" | "doordash";

export interface Order {
  id: string;
  orderNumber: string;
  orderType: string;
  total: number;
  status: string;
  date: string;
  time: string;
  source?: OrderSource;
  externalOrderId?: string | null;
  rawPayload?: unknown;
}

interface OrdersTableProps {
  orders: Order[];
  loading?: boolean;
}

const sourceBadgeConfig: Record<string, { label: string; className: string } | null> = {
  pos: null,
  kitchenhub: { label: "Delivery", className: "bg-violet-50 text-violet-700" },
  ubereats: { label: "Uber Eats", className: "bg-green-50 text-green-700" },
  doordash: { label: "DoorDash", className: "bg-red-50 text-red-700" },
};

const statusConfig: Record<
  string,
  { icon: React.ElementType; className: string; label: string }
> = {
  completed: {
    icon: CheckCircle2,
    className: "bg-emerald-50 text-emerald-700",
    label: "Completed",
  },
  pending: {
    icon: Clock,
    className: "bg-amber-50 text-amber-700",
    label: "Pending",
  },
  cancelled: {
    icon: XCircle,
    className: "bg-red-50 text-red-700",
    label: "Cancelled",
  },
  refunded: {
    icon: AlertCircle,
    className: "bg-slate-50 text-slate-600",
    label: "Refunded",
  },
};

export default function OrdersTable({ orders, loading }: OrdersTableProps) {
  if (loading) {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-8">
        <div className="flex items-center justify-center gap-3 text-slate-400">
          <div className="w-5 h-5 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          Loading orders...
        </div>
      </div>
    );
  }

  if (orders.length === 0) {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-12 text-center">
        <p className="text-slate-400 text-lg">No orders found</p>
      </div>
    );
  }

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-slate-100 overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="border-b border-slate-100">
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                Order #
              </th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                Source
              </th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                Type
              </th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                Total
              </th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                Status
              </th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                Date
              </th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                Time
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-50">
            {orders.map((order) => {
              const status = statusConfig[order.status] || statusConfig.pending;
              const StatusIcon = status.icon;
              return (
                <tr
                  key={order.id}
                  className="hover:bg-slate-50/50 transition-colors"
                >
                  <td className="px-6 py-4">
                    <span className="text-sm font-semibold text-slate-800">
                      #{order.orderNumber}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    {(() => {
                      const badge = sourceBadgeConfig[order.source || "pos"];
                      if (!badge) return <span className="text-xs text-slate-400">POS</span>;
                      return (
                        <span className={`inline-flex items-center text-xs font-medium px-2 py-0.5 rounded-full ${badge.className}`}>
                          {badge.label}
                        </span>
                      );
                    })()}
                  </td>
                  <td className="px-6 py-4">
                    <span className="text-sm text-slate-600 capitalize">
                      {order.orderType}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    <span className="text-sm font-medium text-slate-800">
                      ${order.total.toFixed(2)}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    <span
                      className={`inline-flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-full ${status.className}`}
                    >
                      <StatusIcon size={14} />
                      {status.label}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    <span className="text-sm text-slate-500">{order.date}</span>
                  </td>
                  <td className="px-6 py-4">
                    <span className="text-sm text-slate-500">{order.time}</span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
