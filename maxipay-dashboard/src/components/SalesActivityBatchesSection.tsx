"use client";

import { useEffect, useMemo, useState } from "react";
import {
  collection,
  limit,
  onSnapshot,
  orderBy,
  query,
  where,
  type DocumentData,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import { Layers, Loader2 } from "lucide-react";

function computeNetAmount(data: DocumentData): number {
  const type = String(data.type ?? "SALE");
  if (type === "SALE" || type === "CAPTURE") {
    const cents = data.totalPaidInCents;
    if (typeof cents === "number") return cents / 100;
    const tp = data.totalPaid;
    if (typeof tp === "number") return tp;
    const amt = data.amount;
    if (typeof amt === "number") return amt;
    return 0;
  }
  if (type === "REFUND") {
    const a = data.amount;
    return typeof a === "number" ? -a : 0;
  }
  return 0;
}

function closedAtMs(d: DocumentData): number {
  const c = d.closedAt;
  if (c && typeof c.toDate === "function") return c.toDate().getTime();
  const cr = d.createdAt;
  if (cr && typeof cr.toDate === "function") return cr.toDate().getTime();
  return 0;
}

interface ClosedBatchRow {
  id: string;
  totalSales: number;
  transactionCount: number;
  closedLabel: string;
}

export default function SalesActivityBatchesSection() {
  const { user } = useAuth();
  const [loadingOpen, setLoadingOpen] = useState(true);
  const [settleable, setSettleable] = useState(0);
  const [openTotal, setOpenTotal] = useState(0);
  const [preAuthCount, setPreAuthCount] = useState(0);
  const [openBatchId, setOpenBatchId] = useState<string | null>(null);
  const [closedRows, setClosedRows] = useState<ClosedBatchRow[]>([]);
  const [loadingClosed, setLoadingClosed] = useState(true);

  useEffect(() => {
    if (!user || !db || typeof (db as { collection?: unknown }).collection !== "function")
      return;

    const qUnsettled = query(
      collection(db, "Transactions"),
      where("settled", "==", false),
      where("voided", "==", false)
    );

    const unsubTx = onSnapshot(
      qUnsettled,
      (snap) => {
        let s = 0;
        let total = 0;
        let pre = 0;
        snap.forEach((doc) => {
          const type = String(doc.data().type ?? "SALE");
          if (type === "PRE_AUTH") {
            pre += 1;
          } else {
            s += 1;
            total += computeNetAmount(doc.data());
          }
        });
        setSettleable(s);
        setOpenTotal(total < 0.005 ? 0 : total);
        setPreAuthCount(pre);
        setLoadingOpen(false);
      },
      () => {
        setLoadingOpen(false);
      }
    );

    const qOpenBatch = query(
      collection(db, "Batches"),
      where("closed", "==", false),
      limit(1)
    );
    const unsubOpenBatch = onSnapshot(
      qOpenBatch,
      (snap) => {
        const d0 = snap.docs[0];
        setOpenBatchId(d0?.id ?? null);
      },
      () => {}
    );

    const qBatches = query(
      collection(db, "Batches"),
      orderBy("createdAt", "desc"),
      limit(120)
    );
    const unsubBatches = onSnapshot(
      qBatches,
      (snap) => {
        type Row = ClosedBatchRow & { sortMs: number };
        const rows: Row[] = [];
        snap.forEach((doc) => {
          const data = doc.data();
          if (data.closed !== true) return;
          const dt = data.closedAt;
          const date =
            dt && typeof dt.toDate === "function"
              ? dt.toDate()
              : data.createdAt && typeof data.createdAt.toDate === "function"
                ? data.createdAt.toDate()
                : null;
          const closedLabel = date
            ? date.toLocaleString(undefined, {
                month: "2-digit",
                day: "2-digit",
                year: "numeric",
                hour: "2-digit",
                minute: "2-digit",
              })
            : "—";
          const totalSales =
            typeof data.totalSales === "number"
              ? data.totalSales
              : Number(data.totalSales ?? 0);
          const transactionCount = Math.round(
            Number(data.transactionCount ?? 0)
          );
          rows.push({
            id: doc.id,
            totalSales,
            transactionCount,
            closedLabel,
            sortMs: closedAtMs(data),
          });
        });
        rows.sort((a, b) => b.sortMs - a.sortMs);
        setClosedRows(
          rows.slice(0, 50).map((r) => ({
            id: r.id,
            totalSales: r.totalSales,
            transactionCount: r.transactionCount,
            closedLabel: r.closedLabel,
          }))
        );
        setLoadingClosed(false);
      },
      () => {
        setLoadingClosed(false);
      }
    );

    return () => {
      unsubTx();
      unsubOpenBatch();
      unsubBatches();
    };
  }, [user]);

  const openSummaryLine = useMemo(() => {
    const parts = [
      `Open transactions: ${settleable}`,
      `Total: $${openTotal.toFixed(2)}`,
    ];
    return parts.join(" · ");
  }, [settleable, openTotal]);

  return (
    <section className="rounded-xl border border-slate-200 bg-white shadow-sm overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-3 border-b border-slate-100 bg-slate-50/80">
        <Layers className="w-5 h-5 text-slate-600 shrink-0" aria-hidden />
        <h2 className="text-lg font-semibold text-slate-800">Batches</h2>
        <span className="text-xs text-slate-500 ml-auto">
          Same view as Settle batch on the POS — read-only here; close batch on
          the device.
        </span>
      </div>

      <div className="p-4 space-y-4">
        <div>
          <h3 className="text-sm font-semibold text-slate-700 mb-2">
            Open batch
          </h3>
          {loadingOpen ? (
            <div className="flex items-center gap-2 text-slate-500 text-sm">
              <Loader2 className="w-4 h-4 animate-spin" />
              Loading…
            </div>
          ) : (
            <div className="rounded-lg bg-slate-50 border border-slate-100 px-3 py-2 text-sm text-slate-800 space-y-1">
              <p className="font-medium">{openSummaryLine}</p>
              {preAuthCount > 0 && (
                <p className="text-amber-800 text-sm">
                  Open pre-auths: {preAuthCount} (capture or void bar tabs before
                  closing on the POS)
                </p>
              )}
              {openBatchId && (
                <p className="text-xs text-slate-500">
                  Current batch document:{" "}
                  <span className="font-mono">{openBatchId}</span>
                </p>
              )}
            </div>
          )}
        </div>

        <div>
          <h3 className="text-sm font-semibold text-slate-700 mb-2">
            Closed batches
          </h3>
          {loadingClosed ? (
            <div className="flex items-center gap-2 text-slate-500 text-sm">
              <Loader2 className="w-4 h-4 animate-spin" />
              Loading…
            </div>
          ) : closedRows.length === 0 ? (
            <p className="text-sm text-slate-500">No closed batches yet.</p>
          ) : (
            <div className="overflow-x-auto rounded-lg border border-slate-200">
              <table className="min-w-full text-sm">
                <thead>
                  <tr className="bg-slate-50 text-left text-slate-600">
                    <th className="px-3 py-2 font-medium">Batch ID</th>
                    <th className="px-3 py-2 font-medium">Date</th>
                    <th className="px-3 py-2 font-medium">Summary</th>
                  </tr>
                </thead>
                <tbody>
                  {closedRows.map((r) => (
                    <tr
                      key={r.id}
                      className="border-t border-slate-100 hover:bg-slate-50/80"
                    >
                      <td
                        className="px-3 py-2 font-mono text-xs text-slate-800 max-w-[220px] break-all"
                        title={r.id}
                      >
                        {r.id}
                      </td>
                      <td className="px-3 py-2 text-slate-700 whitespace-nowrap">
                        {r.closedLabel}
                      </td>
                      <td className="px-3 py-2 text-slate-800">
                        Transactions: {r.transactionCount} · Total: $
                        {r.totalSales.toFixed(2)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
