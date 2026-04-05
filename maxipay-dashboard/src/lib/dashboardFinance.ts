import {
  collection,
  documentId,
  getDocs,
  query,
  where,
  type DocumentData,
  type QuerySnapshot,
} from "firebase/firestore";

import type { HourlySalesPoint } from "@/components/SalesChart";
import type { PaymentBreakdownTotals } from "@/components/PaymentBreakdown";
import { db } from "@/firebase/firebaseConfig";

/** Start of local calendar day for `base` (00:00:00.000). */
export function startOfLocalDay(base: Date, dayOffset = 0): Date {
  const d = new Date(base);
  d.setDate(d.getDate() + dayOffset);
  d.setHours(0, 0, 0, 0);
  return d;
}

/** End of local calendar day for `base` (23:59:59.999). */
export function endOfLocalDay(base: Date, dayOffset = 0): Date {
  const d = new Date(base);
  d.setDate(d.getDate() + dayOffset);
  d.setHours(23, 59, 59, 999);
  return d;
}

function localDateKey(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

function enumerateLocalDays(rangeStart: Date, rangeEnd: Date): { key: string; label: string }[] {
  const out: { key: string; label: string }[] = [];
  const cur = startOfLocalDay(rangeStart, 0);
  const last = startOfLocalDay(rangeEnd, 0);
  while (cur.getTime() <= last.getTime()) {
    const key = localDateKey(cur);
    const label = cur.toLocaleDateString("en-US", {
      weekday: "short",
      month: "numeric",
      day: "numeric",
    });
    out.push({ key, label });
    cur.setDate(cur.getDate() + 1);
  }
  return out;
}

export interface DashboardPeriodTotals {
  totalSalesCents: number;
  totalRefundsCents: number;
  orderCount: number;
  saleTransactionIds: string[];
}

/**
 * Same rules as the POS dashboard: orders in [start, end] by `createdAt`, excluding VOIDED;
 * net sales uses totalPaidInCents if &gt; 0 else totalInCents.
 */
export function aggregateDashboardPeriod(
  snapshot: QuerySnapshot<DocumentData>,
  start: Date,
  end: Date
): DashboardPeriodTotals {
  let totalSalesCents = 0;
  let totalRefundsCents = 0;
  let orderCount = 0;
  const saleTransactionIds: string[] = [];

  snapshot.forEach((docSnap) => {
    const data = docSnap.data();
    const createdAt = data.createdAt?.toDate?.() ?? new Date();
    if (createdAt < start || createdAt > end) {
      return;
    }
    const status = String(data.status ?? "");
    if (status === "VOIDED") {
      return;
    }

    orderCount += 1;
    const paid = Number(data.totalPaidInCents ?? 0);
    const totalIn = Number(data.totalInCents ?? 0);
    totalSalesCents += paid > 0 ? paid : totalIn;
    totalRefundsCents += Number(data.totalRefundedInCents ?? 0);
    const sid = String(data.saleTransactionId ?? "").trim();
    if (sid) {
      saleTransactionIds.push(sid);
    }
  });

  return {
    totalSalesCents,
    totalRefundsCents,
    orderCount,
    saleTransactionIds,
  };
}

/** Trend line for metric cards, e.g. "+12.3% vs yesterday". */
export function formatTrendVsPrior(
  current: number,
  previous: number,
  priorPhrase: string
): string {
  if (previous <= 0) {
    return current > 0 ? `New ${priorPhrase}` : `— ${priorPhrase}`;
  }
  const pct = ((current - previous) / previous) * 100;
  const r = Math.round(pct * 10) / 10;
  const sign = r >= 0 ? "+" : "";
  return `${sign}${r}% ${priorPhrase}`;
}

function classifyPaymentType(pt: string): keyof PaymentBreakdownTotals {
  const t = pt.trim();
  if (!t) return "other";
  const u = t.toUpperCase();
  if (u === "CASH") return "cash";
  if (u === "CREDIT" || u === "DEBIT") return "card";
  const lower = t.toLowerCase();
  if (lower === "cash") return "cash";
  if (lower === "credit" || lower === "debit") return "card";
  return "other";
}

/** Hour labels: 12a, 1a, … 11p (compact). */
function formatHourLabel(hour: number): string {
  const am = hour < 12;
  const display = hour % 12 === 0 ? 12 : hour % 12;
  return `${display}${am ? "a" : "p"}`;
}

/**
 * Buckets gross sales by hour of day (order `createdAt`) for orders in [startOfDay, endOfDay], excluding voided.
 */
export function buildHourlySalesPoints(
  snapshot: QuerySnapshot<DocumentData>,
  startOfDay: Date,
  endOfDay: Date
): HourlySalesPoint[] {
  const amounts = new Array(24).fill(0) as number[];
  snapshot.forEach((docSnap) => {
    const data = docSnap.data();
    const createdAt = data.createdAt?.toDate?.() ?? new Date();
    if (createdAt < startOfDay || createdAt > endOfDay) {
      return;
    }
    if (String(data.status ?? "") === "VOIDED") {
      return;
    }
    const paid = Number(data.totalPaidInCents ?? 0);
    const totalIn = Number(data.totalInCents ?? 0);
    const cents = paid > 0 ? paid : totalIn;
    const hour = createdAt.getHours();
    amounts[hour] += cents / 100;
  });
  return amounts.map((amount, hour) => ({
    amount,
    label: formatHourLabel(hour),
  }));
}

/**
 * One point per local calendar day in [rangeStart, rangeEnd] (inclusive days).
 */
export function buildDailySalesPoints(
  snapshot: QuerySnapshot<DocumentData>,
  rangeStart: Date,
  rangeEnd: Date
): HourlySalesPoint[] {
  const days = enumerateLocalDays(rangeStart, rangeEnd);
  const amounts = new Map<string, number>();
  for (const d of days) {
    amounts.set(d.key, 0);
  }

  snapshot.forEach((docSnap) => {
    const data = docSnap.data();
    const createdAt = data.createdAt?.toDate?.() ?? new Date();
    if (String(data.status ?? "") === "VOIDED") {
      return;
    }
    const key = localDateKey(createdAt);
    if (!amounts.has(key)) {
      return;
    }
    const paid = Number(data.totalPaidInCents ?? 0);
    const totalIn = Number(data.totalInCents ?? 0);
    const cents = paid > 0 ? paid : totalIn;
    amounts.set(key, (amounts.get(key) ?? 0) + cents / 100);
  });

  return days.map((d) => ({
    label: d.label,
    amount: amounts.get(d.key) ?? 0,
  }));
}

/**
 * Loads `Transactions` by id (chunks of 10) and sums payment rows into card / cash / other.
 * Matches Android ReportEngine-style payment types (Cash, Credit, Debit, …).
 */
export async function aggregatePaymentBreakdownFromTransactionIds(
  transactionIds: string[]
): Promise<PaymentBreakdownTotals> {
  const result: PaymentBreakdownTotals = { card: 0, cash: 0, other: 0 };
  const unique = [...new Set(transactionIds.filter(Boolean))];
  for (let i = 0; i < unique.length; i += 10) {
    const chunk = unique.slice(i, i + 10);
    const q = query(
      collection(db, "Transactions"),
      where(documentId(), "in", chunk)
    );
    try {
      const snap = await getDocs(q);
      snap.forEach((docSnap) => {
        const data = docSnap.data() as Record<string, unknown>;
        const payments = data.payments;
        if (Array.isArray(payments) && payments.length > 0) {
          for (const p of payments) {
            const row = p as Record<string, unknown>;
            if (row.status === "VOIDED") continue;
            const pt = String(row.paymentType ?? row.paymentMethod ?? "");
            const cents = Math.round(Number(row.amountInCents ?? 0));
            const bucket = classifyPaymentType(pt);
            result[bucket] += cents / 100;
          }
        } else {
          const legacy = String(data.paymentType ?? data.paymentMethod ?? "");
          const totalPaid = Math.round(Number(data.totalPaidInCents ?? 0));
          if (totalPaid > 0 && legacy.trim()) {
            const bucket = classifyPaymentType(legacy);
            result[bucket] += totalPaid / 100;
          }
        }
      });
    } catch (e) {
      console.error("Payment breakdown fetch failed:", e);
    }
  }
  return result;
}
