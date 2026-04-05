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

import type { Order } from "@/components/OrdersTable";
import { orderTypeBadgeStyle } from "@/lib/orderDisplayUtils";

interface RecentOrdersGridProps {
  orders: Order[];
  loading?: boolean;
  linkBase?: string;
  emptyMessage?: string;
  emptySubMessage?: string;
}

const statusSolid: Record<
  string,
  { icon: React.ElementType; className: string; label: string }
> = {
  OPEN: {
    icon: Clock,
    className: "bg-amber-500 text-white",
    label: "Open",
  },
  CLOSED: {
    icon: CheckCircle2,
    className: "bg-emerald-600 text-white",
    label: "Closed",
  },
  VOIDED: {
    icon: Ban,
    className: "bg-slate-400 text-white",
    label: "Voided",
  },
  REFUNDED: {
    icon: RotateCcw,
    className: "bg-violet-600 text-white",
    label: "Refunded",
  },
  PARTIALLY_REFUNDED: {
    icon: AlertCircle,
    className: "bg-orange-500 text-white",
    label: "Partial refund",
  },
  completed: {
    icon: CheckCircle2,
    className: "bg-emerald-600 text-white",
    label: "Completed",
  },
  pending: {
    icon: Clock,
    className: "bg-amber-500 text-white",
    label: "Pending",
  },
  cancelled: {
    icon: XCircle,
    className: "bg-red-500 text-white",
    label: "Cancelled",
  },
  refunded: {
    icon: AlertCircle,
    className: "bg-slate-500 text-white",
    label: "Refunded",
  },
};

function statusPill(status: string) {
  const u = status.toUpperCase();
  return (
    statusSolid[u] ||
    statusSolid[status] || {
      icon: AlertCircle,
      className: "bg-slate-500 text-white",
      label: status || "Unknown",
    }
  );
}

export default function RecentOrdersGrid({
  orders,
  loading,
  linkBase = "/dashboard/orders",
  emptyMessage,
  emptySubMessage,
}: RecentOrdersGridProps) {
  const router = useRouter();

  if (loading) {
    return (
      <div
        className="bg-white rounded-[12px] border border-slate-100 p-8"
        style={{ boxShadow: "0 2px 12px rgba(15, 23, 42, 0.06)" }}
      >
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
    const sub = emptySubMessage === undefined ? subDefault : emptySubMessage;
    return (
      <div
        className="bg-white rounded-[12px] border border-slate-100 p-12 text-center"
        style={{ boxShadow: "0 2px 12px rgba(15, 23, 42, 0.06)" }}
      >
        <p className="text-slate-400 text-lg">{title}</p>
        {sub ? <p className="text-sm text-slate-400 mt-2">{sub}</p> : null}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4 gap-4">
      {orders.map((order) => {
        const status = statusPill(order.status);
        const StatusIcon = status.icon;
        const typeBadge = orderTypeBadgeStyle(order.orderTypeRaw ?? "");
        const href = `${linkBase}/${encodeURIComponent(order.id)}`;

        return (
          <button
            key={order.id}
            type="button"
            onClick={() => router.push(href)}
            className="text-left w-full bg-white rounded-[12px] border border-slate-100 p-4 transition-all hover:border-slate-200 hover:-translate-y-0.5 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500/40"
            style={{ boxShadow: "0 2px 12px rgba(15, 23, 42, 0.06)" }}
          >
            <div className="flex items-start justify-between gap-2 mb-3">
              <span className="text-base font-bold text-slate-900 tabular-nums">
                #{order.orderNumber}
              </span>
              <span className="text-base font-semibold text-slate-900 tabular-nums">
                ${order.total.toFixed(2)}
              </span>
            </div>

            <div className="flex flex-wrap items-center gap-2 mb-3">
              <span
                className="inline-flex items-center text-[11px] font-bold px-2.5 py-1 rounded-md tracking-wide text-white uppercase"
                style={{ backgroundColor: typeBadge.backgroundColor }}
              >
                {typeBadge.label}
              </span>
              <span
                className={`inline-flex items-center gap-1 text-[11px] font-semibold px-2.5 py-1 rounded-md ${status.className}`}
              >
                <StatusIcon size={12} strokeWidth={2.5} />
                {status.label}
              </span>
            </div>

            <p className="text-sm text-slate-500 tabular-nums">{order.time}</p>
          </button>
        );
      })}
    </div>
  );
}
