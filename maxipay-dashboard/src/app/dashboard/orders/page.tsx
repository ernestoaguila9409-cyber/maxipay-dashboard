"use client";

import { useEffect, useMemo, useState } from "react";
import {
  collection,
  query,
  orderBy,
  limit,
  where,
  onSnapshot,
  type DocumentData,
  type QuerySnapshot,
} from "firebase/firestore";
import { getApp } from "firebase/app";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import OrdersTable, { type Order } from "@/components/OrdersTable";
import { Calendar, Filter, Download } from "lucide-react";
import {
  mapFirestoreOrderDoc,
  mergeOrderSnapshots,
} from "@/lib/orderDisplayUtils";

/** Expected Firebase project (dashboard env must match POS). */
const EXPECTED_FIREBASE_PROJECT_ID = "restaurantapp-180da";

/**
 * Fetch strategy — flip after confirming console logs:
 * - `debug_raw`: collection("Orders") only — no where/orderBy/limit
 * - `step_a`: orderBy("createdAt", "desc") only
 * - `step_b`: recent window + OPEN merge (production-style), with index error logging
 */
type OrdersFetchMode = "debug_raw" | "step_a" | "step_b";
const ORDERS_FETCH_MODE: OrdersFetchMode = "debug_raw";

const ORDERS_LIMIT = 200;
const OPEN_ORDERS_LIMIT = 500;

function isIndexRequiredError(err: unknown): boolean {
  const e = err as { code?: string; message?: string };
  const code = e?.code;
  const msg = e?.message ?? "";
  return (
    code === "failed-precondition" ||
    /requires an index|create a composite index|The query requires an index/i.test(
      msg
    )
  );
}

function logOrdersFirestoreError(context: string, err: unknown) {
  const e = err as { code?: string; message?: string };
  console.error(`[Orders] ${context} — code:`, e?.code, "| message:", e?.message ?? err);
  if (isIndexRequiredError(err)) {
    console.error(
      "[Orders] INDEX REQUIRED — Firestore composite index missing. " +
        "Typical fix: collection `Orders`, fields `status` (Ascending) + `createdAt` (Descending). " +
        "Deploy `firestore.indexes.json` or use the index-creation URL from the error message above."
    );
  }
}

function logFirebaseProject() {
  try {
    const app = getApp();
    const projectId = app.options.projectId ?? "MISSING";
    console.log("[Orders] Firebase projectId (dashboard):", projectId);
    if (projectId !== EXPECTED_FIREBASE_PROJECT_ID) {
      console.error(
        "[Orders] Project mismatch — expected:",
        EXPECTED_FIREBASE_PROJECT_ID,
        "| actual:",
        projectId
      );
    } else {
      console.log("[Orders] projectId matches expected:", EXPECTED_FIREBASE_PROJECT_ID);
    }
  } catch (e) {
    console.error("[Orders] getApp() failed — Firebase may not be initialized:", e);
  }
}

function mapSnapshotToSortedOrders(
  snapshot: QuerySnapshot<DocumentData>
): Order[] {
  const list: Order[] = [];
  snapshot.forEach((docSnap) => {
    list.push(
      mapFirestoreOrderDoc(
        docSnap.id,
        docSnap.data() as Record<string, unknown>
      )
    );
  });
  return list.sort((a, b) => (b.createdAtMs ?? 0) - (a.createdAtMs ?? 0));
}

export type DateFilterId = "today" | "yesterday" | "7days" | "all" | "custom";

function getDateRange(
  filter: DateFilterId
): { start: Date; end: Date } | null {
  const now = new Date();

  if (filter === "all" || filter === "custom") {
    return null;
  }

  if (filter === "today") {
    const start = new Date(now);
    start.setHours(0, 0, 0, 0);
    return { start, end: now };
  }

  if (filter === "yesterday") {
    const start = new Date(now);
    start.setDate(start.getDate() - 1);
    start.setHours(0, 0, 0, 0);

    const end = new Date(start);
    end.setHours(23, 59, 59, 999);

    return { start, end };
  }

  if (filter === "7days") {
    const start = new Date(now);
    start.setDate(start.getDate() - 7);
    return { start, end: now };
  }

  return null;
}

function orderCreatedAt(order: Order): Date | null {
  if (order.createdAt) return order.createdAt;
  if (order.createdAtMs && order.createdAtMs > 0) {
    return new Date(order.createdAtMs);
  }
  return null;
}

/** POS order statuses (Android OrderEngine / Firestore). */
export type PosStatusFilter =
  | "all"
  | "OPEN"
  | "CLOSED"
  | "VOIDED"
  | "REFUNDED";

export type PosOrderTypeFilter = "all" | "DINE_IN" | "TO_GO" | "BAR";

function passesStatusFilter(status: string, filter: PosStatusFilter): boolean {
  if (filter === "all") return true;
  if (filter === "REFUNDED") {
    return (
      status === "REFUNDED" || status === "PARTIALLY_REFUNDED"
    );
  }
  return status === filter;
}

function passesOrderTypeFilter(
  orderTypeRaw: string,
  filter: PosOrderTypeFilter
): boolean {
  if (filter === "all") return true;
  if (filter === "BAR") {
    return orderTypeRaw === "BAR" || orderTypeRaw === "BAR_TAB";
  }
  return orderTypeRaw === filter;
}

export default function OrdersPage() {
  const { user } = useAuth();
  const [rawOrders, setRawOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);
  const [dateFilter, setDateFilter] = useState<DateFilterId>("today");
  const [statusFilter, setStatusFilter] = useState<PosStatusFilter>("all");
  const [orderTypeFilter, setOrderTypeFilter] =
    useState<PosOrderTypeFilter>("all");

  useEffect(() => {
    if (!user) {
      setRawOrders([]);
      setLoading(false);
      console.log("[Orders] Skipping Firestore — no authenticated user.");
      return;
    }

    logFirebaseProject();
    console.log("[Orders] ORDERS_FETCH_MODE =", ORDERS_FETCH_MODE);

    setLoading(true);

    const unsubscribers: (() => void)[] = [];

    if (ORDERS_FETCH_MODE === "debug_raw") {
      const col = collection(db, "Orders");
      console.log(
        "[Orders debug] Subscribing to collection(db, \"Orders\") — no where/orderBy/limit"
      );

      const unsub = onSnapshot(
        col,
        (snapshot) => {
          console.log(
            "[Orders debug] total documents returned:",
            snapshot.size
          );
          const first = snapshot.docs[0];
          if (first) {
            console.log("[Orders debug] first document id:", first.id);
            console.log("[Orders debug] first document data:", first.data());
          } else {
            console.log("[Orders debug] first document: (none — collection empty)");
          }

          const list = mapSnapshotToSortedOrders(snapshot);
          setRawOrders(list);
          setLoading(false);
          console.log("[Orders debug] mapped rows:", list.length);
        },
        (err) => {
          logOrdersFirestoreError("debug_raw onSnapshot", err);
          setRawOrders([]);
          setLoading(false);
        }
      );
      unsubscribers.push(unsub);
    } else if (ORDERS_FETCH_MODE === "step_a") {
      const q = query(collection(db, "Orders"), orderBy("createdAt", "desc"));
      console.log(
        "[Orders step_a] query: orderBy(createdAt desc) only — no where/limit"
      );

      const unsub = onSnapshot(
        q,
        (snapshot) => {
          console.log("[Orders step_a] total documents:", snapshot.size);
          const list = mapSnapshotToSortedOrders(snapshot);
          setRawOrders(list);
          setLoading(false);
          console.log("[Orders step_a] mapped rows:", list.length);
        },
        (err) => {
          logOrdersFirestoreError("step_a onSnapshot", err);
          setRawOrders([]);
          setLoading(false);
        }
      );
      unsubscribers.push(unsub);
    } else {
      // step_b: recent + OPEN merge
      console.log(
        "[Orders step_b] recent query + OPEN query (merge); OPEN failure logs index requirement"
      );

      const qRecent = query(
        collection(db, "Orders"),
        orderBy("createdAt", "desc"),
        limit(ORDERS_LIMIT)
      );
      const qOpen = query(
        collection(db, "Orders"),
        where("status", "==", "OPEN"),
        orderBy("createdAt", "desc"),
        limit(OPEN_ORDERS_LIMIT)
      );

      let recent: QuerySnapshot<DocumentData> | null = null;
      let openSnap: QuerySnapshot<DocumentData> | null = null;

      const applyMerge = () => {
        if (!recent) return;
        const merged = mergeOrderSnapshots(recent, openSnap);
        setRawOrders(merged);
        setLoading(false);
        console.log(
          "[Orders step_b] merged row count:",
          merged.length,
          "| recent snapshot size:",
          recent.size,
          "| open snapshot size:",
          openSnap?.size ?? "(not loaded)"
        );
      };

      unsubscribers.push(
        onSnapshot(
          qRecent,
          (snapshot) => {
            recent = snapshot;
            applyMerge();
          },
          (err) => {
            logOrdersFirestoreError("step_b qRecent onSnapshot", err);
            setRawOrders([]);
            setLoading(false);
          }
        )
      );

      unsubscribers.push(
        onSnapshot(
          qOpen,
          (snapshot) => {
            openSnap = snapshot;
            applyMerge();
          },
          (err) => {
            logOrdersFirestoreError("step_b qOpen (OPEN + orderBy) onSnapshot", err);
            console.error(
              "[Orders step_b] OPEN query failed — continuing with recent-only merge. Fix index and reload."
            );
            openSnap = null;
            if (recent) applyMerge();
          }
        )
      );
    }

    return () => {
      unsubscribers.forEach((u) => u());
    };
  }, [user]);

  const dateFiltered = useMemo(() => {
    const range = getDateRange(dateFilter);
    if (!range) {
      return rawOrders;
    }
    return rawOrders.filter((order) => {
      const d = orderCreatedAt(order);
      if (!d) return false;
      return d >= range.start && d <= range.end;
    });
  }, [rawOrders, dateFilter]);

  const orders = useMemo(() => {
    if (ORDERS_FETCH_MODE === "debug_raw") {
      return dateFiltered;
    }
    return dateFiltered.filter((o) => {
      if (!passesStatusFilter(o.status, statusFilter)) return false;
      if (!passesOrderTypeFilter(o.orderTypeRaw ?? "", orderTypeFilter))
        return false;
      return true;
    });
  }, [dateFiltered, statusFilter, orderTypeFilter]);

  const noOrdersForSelectedDate =
    !loading &&
    orders.length === 0 &&
    rawOrders.length > 0 &&
    dateFilter !== "all" &&
    dateFilter !== "custom" &&
    dateFiltered.length === 0;

  const filteredOutByStatusType =
    !loading &&
    orders.length === 0 &&
    rawOrders.length > 0 &&
    ORDERS_FETCH_MODE !== "debug_raw" &&
    dateFiltered.length > 0;

  const dateFilterButtonClass = (value: DateFilterId) =>
    `px-3 py-2 rounded-xl border text-sm transition-colors ${
      dateFilter === value
        ? "border-blue-500 bg-blue-50 text-blue-900 font-medium"
        : "border-slate-200 bg-white text-slate-700 hover:bg-slate-50"
    }`;

  return (
    <>
      <Header title="Orders" />
      <div className="p-6 space-y-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2 gap-y-3">
            <button
              type="button"
              onClick={() => setDateFilter("today")}
              className={dateFilterButtonClass("today")}
            >
              Today
            </button>
            <button
              type="button"
              onClick={() => setDateFilter("yesterday")}
              className={dateFilterButtonClass("yesterday")}
            >
              Yesterday
            </button>
            <button
              type="button"
              onClick={() => setDateFilter("7days")}
              className={dateFilterButtonClass("7days")}
            >
              7 Days
            </button>
            <button
              type="button"
              onClick={() => setDateFilter("all")}
              className={dateFilterButtonClass("all")}
            >
              All
            </button>
            <div className="relative">
              <Calendar
                size={16}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
              />
              <select
                value={dateFilter === "custom" ? "all" : dateFilter}
                onChange={(e) =>
                  setDateFilter(e.target.value as DateFilterId)
                }
                className="pl-9 pr-8 py-2 rounded-xl bg-white border border-slate-200 text-sm text-slate-700 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 appearance-none cursor-pointer min-w-[160px]"
              >
                <option value="today">Today</option>
                <option value="yesterday">Yesterday</option>
                <option value="7days">Last 7 days</option>
                <option value="all">All time</option>
                <option value="custom" disabled>
                  Custom (coming soon)
                </option>
              </select>
            </div>
            <div className="relative">
              <Filter
                size={16}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
              />
              <select
                value={statusFilter}
                onChange={(e) =>
                  setStatusFilter(e.target.value as PosStatusFilter)
                }
                className="pl-9 pr-8 py-2 rounded-xl bg-white border border-slate-200 text-sm text-slate-700 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 appearance-none cursor-pointer min-w-[140px]"
              >
                <option value="all">All status</option>
                <option value="OPEN">Open</option>
                <option value="CLOSED">Closed</option>
                <option value="VOIDED">Voided</option>
                <option value="REFUNDED">Paid / refunded</option>
              </select>
            </div>
            <div className="relative">
              <Filter
                size={16}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
              />
              <select
                value={orderTypeFilter}
                onChange={(e) =>
                  setOrderTypeFilter(e.target.value as PosOrderTypeFilter)
                }
                className="pl-9 pr-8 py-2 rounded-xl bg-white border border-slate-200 text-sm text-slate-700 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 appearance-none cursor-pointer min-w-[160px]"
              >
                <option value="all">All order types</option>
                <option value="DINE_IN">Dine In</option>
                <option value="TO_GO">To Go</option>
                <option value="BAR">Bar</option>
              </select>
            </div>
          </div>

          <button
            type="button"
            className="flex items-center gap-2 px-4 py-2 rounded-xl bg-white border border-slate-200 text-sm text-slate-700 hover:bg-slate-50 transition-colors"
          >
            <Download size={16} />
            Export
          </button>
        </div>

        <OrdersTable
          orders={orders}
          loading={loading}
          linkBase="/dashboard/orders"
          emptyMessage={
            noOrdersForSelectedDate
              ? "No orders for selected date"
              : filteredOutByStatusType
                ? "No orders match current filters"
                : undefined
          }
          emptySubMessage={
            noOrdersForSelectedDate || filteredOutByStatusType ? "" : undefined
          }
        />

        <div className="flex items-center justify-between text-sm text-slate-500">
          <p>
            Showing {orders.length} order{orders.length === 1 ? "" : "s"}
            {dateFilter !== "all" ||
            ORDERS_FETCH_MODE !== "debug_raw" ||
            statusFilter !== "all" ||
            orderTypeFilter !== "all"
              ? ` (${rawOrders.length} loaded from Firestore)`
              : ""}
          </p>
          <p className="text-xs text-slate-400 max-w-md text-right">
            Synced live from Firestore <code className="text-slate-500">Orders</code>{" "}
            (same as the POS app). Click a row for details.
          </p>
        </div>
      </div>
    </>
  );
}
