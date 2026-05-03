"use client";

import { useRouter } from "next/navigation";
import {
  Clock,
  CheckCircle2,
  XCircle,
  AlertCircle,
  Ban,
  RotateCcw,
} from "lucide-react";

import { orderTypeBadgeStyle } from "@/lib/orderDisplayUtils";

export type OrderSource = "pos" | "kitchenhub" | "ubereats" | "doordash";

export interface Order {
  id: string;
  orderNumber: string;
  orderType: string;
  orderTypeRaw?: string;
  total: number;
  /** Canonical POS status for filters (OPEN, CLOSED, …). */
  status: string;
  /** List badge when it differs (e.g. UNPAID for web online pay-at-store). */
  statusDisplay?: string;
  date: string;
  time: string;
  source?: OrderSource;
  externalOrderId?: string | null;
  rawPayload?: unknown;
  employeeName?: string;
  customerName?: string;
  /** Parsed from Firestore when available — used for in-memory date filtering. */
  createdAt?: Date | null;
  /** Unix ms from Firestore `createdAt` — used for merging/sorting lists. */
  createdAtMs?: number;
}

interface OrdersTableProps {
  orders: Order[];
  loading?: boolean;
  linkBase?: string;
  /** Overrides default "No orders found" when the table is empty. */
  emptyMessage?: string;
  /** Overrides default subtext; use "" to hide the second line. */
  emptySubMessage?: string;
}

const statusConfig: Record<
  string,
  { icon: React.ElementType; className: string; label: string }
> = {
  OPEN: {
    icon: Clock,
    className: "bg-amber-50 text-amber-800",
    label: "Open",
  },
  UNPAID: {
    icon: AlertCircle,
    className: "bg-orange-50 text-orange-900",
    label: "Unpaid",
  },
  CLOSED: {
    icon: CheckCircle2,
    className: "bg-emerald-50 text-emerald-700",
    label: "Closed",
  },
  VOIDED: {
    icon: Ban,
    className: "bg-slate-100 text-slate-600",
    label: "Voided",
  },
  REFUNDED: {
    icon: RotateCcw,
    className: "bg-violet-50 text-violet-700",
    label: "Refunded",
  },
  PARTIALLY_REFUNDED: {
    icon: AlertCircle,
    className: "bg-orange-50 text-orange-800",
    label: "Partial refund",
  },
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

function statusRow(status: string) {
  const u = status.toUpperCase();
  return (
    statusConfig[u] ||
    statusConfig[status] || {
      icon: AlertCircle,
      className: "bg-slate-50 text-slate-600",
      label: status || "Unknown",
    }
  );
}

export default function OrdersTable({
  orders,
  loading,
  linkBase = "/dashboard/orders",
  emptyMessage,
  emptySubMessage,
}: OrdersTableProps) {
  const router = useRouter();

  if (loading) {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-8">
        <div className="flex items-center justify-center gap-3 text-slate-400">
          <div className="w-5 h-5 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          Loading orders…
        </div>
      </div>
    );
  }

  if (orders.length === 0) {
    const title = emptyMessage ?? "No orders found";
    const subDefault = "Open orders on the POS — they appear here in real time.";
    const sub =
      emptySubMessage === undefined ? subDefault : emptySubMessage;
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-12 text-center">
        <p className="text-slate-400 text-lg">{title}</p>
        {sub ? (
          <p className="text-sm text-slate-400 mt-2">{sub}</p>
        ) : null}
      </div>
    );
  }

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-slate-100 overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full table-fixed">
          <thead>
            <tr className="border-b border-slate-100">
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider w-[100px]">
                Order #
              </th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                Employee
              </th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider w-[110px]">
                Type
              </th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider w-[90px]">
                Total
              </th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider w-[140px]">
                Status
              </th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider w-[100px]">
                Date
              </th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 uppercase tracking-wider w-[90px]">
                Time
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-50">
            {orders.map((order) => {
              const badgeKey = order.statusDisplay ?? order.status;
              const status = statusRow(badgeKey);
              const StatusIcon = status.icon;
              const typeBadge = orderTypeBadgeStyle(order.orderTypeRaw ?? "");
              const href = `${linkBase}/${encodeURIComponent(order.id)}`;
              return (
                <tr
                  key={order.id}
                  role="button"
                  tabIndex={0}
                  onClick={() => router.push(href)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" || e.key === " ") {
                      e.preventDefault();
                      router.push(href);
                    }
                  }}
                  className="hover:bg-slate-50/80 transition-colors cursor-pointer"
                >
                  <td className="px-6 py-4">
                    <span className="text-sm font-semibold text-slate-800">
                      #{order.orderNumber}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    <span
                      className="text-sm text-slate-600 truncate block max-w-[180px]"
                      title={order.employeeName}
                    >
                      {order.employeeName ?? "—"}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    <span
                      className="inline-block text-xs font-semibold text-white px-2.5 py-1 rounded-full tracking-wide"
                      style={{ backgroundColor: typeBadge.backgroundColor }}
                      aria-label={order.orderType}
                    >
                      {typeBadge.label}
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
