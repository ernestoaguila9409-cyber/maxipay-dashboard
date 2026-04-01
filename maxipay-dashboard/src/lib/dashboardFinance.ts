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
