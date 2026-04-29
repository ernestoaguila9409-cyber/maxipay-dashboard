"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import {
  doc,
  getDoc,
  collection,
  getDocs,
  query,
  where,
  addDoc,
  onSnapshot,
  serverTimestamp,
  type Timestamp,
} from "firebase/firestore";
import { ArrowLeft, Loader2 } from "lucide-react";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import { useActiveTerminalCapabilities } from "@/hooks/useActiveTerminalCapabilities";
import { getApp } from "firebase/app";
import { getFunctions, httpsCallable } from "firebase/functions";
import Header from "@/components/Header";
import {
  firestoreDate,
  formatDiscountReceiptLabel,
  formatOrderTypeLabel,
  formatTaxBreakdownLabel,
  formatTipSummaryLabel,
  groupAppliedDiscounts,
  orderTypeBadgeStyle,
  parseTaxBreakdown,
} from "@/lib/orderDisplayUtils";

interface LineItem {
  id: string;
  name: string;
  quantity: number;
  unitPriceInCents: number;
  lineTotalInCents: number;
  modifiers: { name: string; action?: string; price?: number }[];
}

function centsToMoney(c: number): string {
  return (c / 100).toFixed(2);
}

function formatFirestoreTimestamp(ts: unknown): string | null {
  if (
    ts &&
    typeof ts === "object" &&
    "toDate" in ts &&
    typeof (ts as Timestamp).toDate === "function"
  ) {
    try {
      return (ts as Timestamp).toDate().toLocaleString();
    } catch {
      return null;
    }
  }
  return null;
}

interface RefundActivityRow {
  id: string;
  refundedBy: string;
  amountInCents: number;
  createdAtLabel: string | null;
}

/** Mirrors Android `OrderDetailActivity.loadRefundHistory` + `OrderItemsAdapter` matching. */
interface RefundStrikeIndex {
  lineKeys: Set<string>;
  nameAmountKeys: Set<string>;
  lineKeyMeta: Map<string, { by: string; at: string }>;
  nameAmountMeta: Map<string, { by: string; at: string }>;
  /** Set when a refund tx has no `refundedLineKey` / `refundedItemName` (whole-order refund); last doc wins. */
  wholeOrderLast: { by: string; at: string } | null;
  totalFromRefundTxnsCents: number;
}

function refundTxnAmountCentsAbs(rd: Record<string, unknown>): number {
  const a = rd.amountInCents;
  if (typeof a === "number" && Number.isFinite(a)) return Math.round(Math.abs(a));
  const d = rd.amount;
  if (typeof d === "number" && Number.isFinite(d)) return Math.round(Math.abs(d) * 100);
  return 0;
}

function buildRefundStrikeIndex(
  sortedRefundDocs: Array<{ data: () => Record<string, unknown> }>
): RefundStrikeIndex {
  const lineKeys = new Set<string>();
  const nameAmountKeys = new Set<string>();
  const lineKeyMeta = new Map<string, { by: string; at: string }>();
  const nameAmountMeta = new Map<string, { by: string; at: string }>();
  let wholeOrderLast: { by: string; at: string } | null = null;
  let totalFromRefundTxnsCents = 0;

  for (const d of sortedRefundDocs) {
    const rd = d.data();
    const amountCents = refundTxnAmountCentsAbs(rd);
    totalFromRefundTxnsCents += amountCents;
    const employee = String(rd.refundedBy ?? "").trim() || "—";
    const dateStr = formatFirestoreTimestamp(rd.createdAt) ?? "";
    const lk = String(rd.refundedLineKey ?? "").trim();
    const itemName = String(rd.refundedItemName ?? "").trim();
    if (lk) {
      lineKeys.add(lk);
      lineKeyMeta.set(lk, { by: employee, at: dateStr });
    } else if (itemName) {
      const nak = `${itemName}|${amountCents}`;
      nameAmountKeys.add(nak);
      nameAmountMeta.set(nak, { by: employee, at: dateStr });
    } else {
      wholeOrderLast = { by: employee, at: dateStr };
    }
  }

  return {
    lineKeys,
    nameAmountKeys,
    lineKeyMeta,
    nameAmountMeta,
    wholeOrderLast,
    totalFromRefundTxnsCents,
  };
}

const SALES_ACTIVITY_FROM = "sales-activity";
const REMOTE_PAYMENT_COMMANDS = "RemotePaymentCommands";

function txRecordHasCashTender(tx: Record<string, unknown>): boolean {
  const pays = tx.payments;
  if (Array.isArray(pays)) {
    for (const p of pays) {
      const o = p as Record<string, unknown>;
      const pt = String(o.paymentType ?? "").trim().toLowerCase();
      const cents = Number(o.amountInCents ?? 0);
      if (pt === "cash" && cents > 0) return true;
    }
    return false;
  }
  return String(tx.paymentType ?? "").trim().toLowerCase() === "cash";
}

function txRecordIsEcommerce(tx: Record<string, unknown>): boolean {
  if (tx.ecommerce === true) return true;
  const pays = tx.payments;
  if (!Array.isArray(pays)) return false;
  return pays.some(
    (p) =>
      String((p as Record<string, unknown>).entryType ?? "").toUpperCase() === "ECOMMERCE"
  );
}

export default function OrderDetailPage() {
  const params = useParams();
  const router = useRouter();
  const searchParams = useSearchParams();
  const orderId = typeof params.orderId === "string" ? params.orderId : "";
  const { user } = useAuth();
  const { capabilities: termCaps } = useActiveTerminalCapabilities();

  const fromSalesActivity =
    searchParams.get("from")?.trim().toLowerCase() === SALES_ACTIVITY_FROM;
  const backHref = fromSalesActivity ? "/dashboard/sales-activity" : "/dashboard/orders";
  const backLabel = fromSalesActivity ? "Back to sales activity" : "Back to orders";

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [orderData, setOrderData] = useState<Record<string, unknown> | null>(
    null
  );
  const [lines, setLines] = useState<LineItem[]>([]);
  const [saleTransactionData, setSaleTransactionData] = useState<
    Record<string, unknown> | null
  >(null);
  const [refundActivity, setRefundActivity] = useState<RefundActivityRow[]>([]);
  const [refundLineIndex, setRefundLineIndex] = useState<RefundStrikeIndex | null>(
    null
  );

  const [refundCmdId, setRefundCmdId] = useState<string | null>(null);
  const [refundSubmitting, setRefundSubmitting] = useState(false);
  const [refundSubmitErr, setRefundSubmitErr] = useState<string | null>(null);
  const [refundCmdStatus, setRefundCmdStatus] = useState<string | null>(null);
  const [refundCmdDetail, setRefundCmdDetail] = useState<string | null>(null);
  const [refundAmountInput, setRefundAmountInput] = useState("");

  const [directRefundSubmitting, setDirectRefundSubmitting] = useState(false);
  const [directRefundResult, setDirectRefundResult] = useState<string | null>(null);
  const [directRefundErr, setDirectRefundErr] = useState<string | null>(null);

  useEffect(() => {
    setRefundCmdId(null);
    setRefundSubmitErr(null);
    setRefundCmdStatus(null);
    setRefundCmdDetail(null);
    setRefundAmountInput("");
    setDirectRefundSubmitting(false);
    setDirectRefundResult(null);
    setDirectRefundErr(null);
  }, [orderId]);

  useEffect(() => {
    if (!refundCmdId || !db) {
      setRefundCmdStatus(null);
      setRefundCmdDetail(null);
      return;
    }
    const ref = doc(db, REMOTE_PAYMENT_COMMANDS, refundCmdId);
    const unsub = onSnapshot(
      ref,
      (snap) => {
        if (!snap.exists()) {
          setRefundCmdStatus(null);
          setRefundCmdDetail(null);
          return;
        }
        const d = snap.data();
        setRefundCmdStatus(String(d?.status ?? ""));
        const err = typeof d?.errorMessage === "string" ? d.errorMessage : "";
        const ok = typeof d?.resultMessage === "string" ? d.resultMessage : "";
        setRefundCmdDetail(err || ok || null);
      },
      (e) => console.error("[Order detail] refund command", e)
    );
    return () => unsub();
  }, [refundCmdId]);

  useEffect(() => {
    if (!user || !orderId) {
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    (async () => {
      try {
        const ref = doc(db, "Orders", orderId);
        const snap = await getDoc(ref);
        if (cancelled) return;
        if (!snap.exists()) {
          setError("Order not found.");
          setOrderData(null);
          setLines([]);
          setRefundLineIndex(null);
          setRefundAmountInput("");
          return;
        }
        const data = snap.data() as Record<string, unknown>;
        setOrderData(data);
        if (!cancelled) {
          const totalIn = Number(data.totalInCents ?? 0);
          const refIn = Number(data.totalRefundedInCents ?? 0);
          const remIn = Math.max(0, totalIn - refIn);
          setRefundAmountInput(remIn > 0 ? (remIn / 100).toFixed(2) : "");
        }

        const saleId = String(
          data.saleTransactionId ?? data.transactionId ?? ""
        ).trim();
        setSaleTransactionData(null);
        setRefundActivity([]);
        setRefundLineIndex(null);

        if (saleId) {
          try {
            const txSnap = await getDoc(doc(db, "Transactions", saleId));
            if (!cancelled && txSnap.exists()) {
              setSaleTransactionData(txSnap.data() as Record<string, unknown>);
            }
          } catch {
            /* ignore missing sale transaction */
          }

          const totalRefunded = Number(data.totalRefundedInCents ?? 0);
          const statusStr = String(data.status ?? "");
          const mayHaveRefunds =
            totalRefunded > 0 ||
            statusStr === "REFUNDED" ||
            statusStr === "PARTIALLY_REFUNDED";
          if (mayHaveRefunds) {
            try {
              const refundQ = query(
                collection(db, "Transactions"),
                where("type", "==", "REFUND"),
                where("originalReferenceId", "==", saleId)
              );
              const refundSnap = await getDocs(refundQ);
              const sortedDocs = refundSnap.docs.slice().sort((a, b) => {
                const ta = a.data().createdAt as Timestamp | undefined;
                const tb = b.data().createdAt as Timestamp | undefined;
                const da =
                  ta && typeof ta.toDate === "function"
                    ? ta.toDate().getTime()
                    : 0;
                const dbt =
                  tb && typeof tb.toDate === "function"
                    ? tb.toDate().getTime()
                    : 0;
                return dbt - da;
              });
              const rows: RefundActivityRow[] = sortedDocs.map((d) => {
                const rd = d.data() as Record<string, unknown>;
                const amt = rd.amountInCents;
                const amountInCents = Math.round(
                  typeof amt === "number" ? amt : Number(amt ?? 0)
                );
                return {
                  id: d.id,
                  refundedBy: String(rd.refundedBy ?? "").trim() || "—",
                  amountInCents,
                  createdAtLabel: formatFirestoreTimestamp(rd.createdAt),
                };
              });
              const strikeIndex = buildRefundStrikeIndex(sortedDocs);
              if (!cancelled) {
                setRefundActivity(rows);
                setRefundLineIndex(strikeIndex);
              }
            } catch (err) {
              console.error(
                "[Order detail] Refund transactions query failed (index may be required):",
                err
              );
              if (!cancelled) setRefundLineIndex(null);
            }
          }
        }

        const itemsSnap = await getDocs(collection(ref, "items"));
        if (cancelled) return;
        const parsed: LineItem[] = [];
        itemsSnap.forEach((d) => {
          const x = d.data();
          const qty = typeof x.quantity === "number" ? x.quantity : Number(x.quantity ?? 1);
          const unit = typeof x.unitPriceInCents === "number"
            ? x.unitPriceInCents
            : Number(x.unitPriceInCents ?? 0);
          const lineTotal = typeof x.lineTotalInCents === "number"
            ? x.lineTotalInCents
            : Number(x.lineTotalInCents ?? 0);
          const modsRaw = x.modifiers;
          const modifiers: LineItem["modifiers"] = Array.isArray(modsRaw)
            ? modsRaw.map((m: unknown) => {
                const o = m as Record<string, unknown>;
                return {
                  name: String(o.name ?? ""),
                  action: o.action != null ? String(o.action) : undefined,
                  price: typeof o.price === "number" ? o.price : Number(o.price ?? 0),
                };
              })
            : [];
          parsed.push({
            id: d.id,
            name: String(x.name ?? x.itemName ?? "Item"),
            quantity: Number.isFinite(qty) ? qty : 1,
            unitPriceInCents: unit,
            lineTotalInCents: lineTotal,
            modifiers,
          });
        });
        setLines(parsed);
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : "Failed to load order");
          setRefundLineIndex(null);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [user, orderId]);

  if (!user) {
    return (
      <>
        <Header title="Order" />
        <div className="p-6 text-slate-500">Sign in to view orders.</div>
      </>
    );
  }

  if (!orderId) {
    return (
      <>
        <Header title="Order" />
        <div className="p-6 text-slate-500">Invalid order.</div>
      </>
    );
  }

  const createdAt = orderData
    ? firestoreDate(orderData as { createdAt?: Timestamp })
    : null;
  const status = String(orderData?.status ?? "");
  const orderNumber = orderData?.orderNumber;
  const orderNumStr =
    typeof orderNumber === "number" || typeof orderNumber === "string"
      ? String(orderNumber)
      : orderId.slice(-6);
  const totalInCents = Number(orderData?.totalInCents ?? 0);
  const totalPaidInCents = Number(orderData?.totalPaidInCents ?? 0);
  const totalRefundedInCents = Number(orderData?.totalRefundedInCents ?? 0);
  const employeeName = String(orderData?.employeeName ?? "—");
  const customerName = String(orderData?.customerName ?? "");
  const orderTypeRaw = String(orderData?.orderType ?? "");
  const tableName = String(orderData?.tableName ?? "");
  const batchId = String(orderData?.batchId ?? "");

  const taxEntries = orderData ? parseTaxBreakdown(orderData.taxBreakdown) : [];
  const taxTotalCents = taxEntries.reduce((s, e) => s + e.amountInCents, 0);
  const tipAmountInCents = Number(orderData?.tipAmountInCents ?? 0);
  const discountInCents = Number(orderData?.discountInCents ?? 0);
  const groupedDiscounts = orderData
    ? groupAppliedDiscounts(orderData.appliedDiscounts)
    : [];
  const hasDiscountLines =
    groupedDiscounts.length > 0 || discountInCents > 0;
  const subtotalCents =
    totalInCents + discountInCents - taxTotalCents - tipAmountInCents;
  const tipLabel = formatTipSummaryLabel(tipAmountInCents, subtotalCents);
  const remainingInCents = Math.max(0, totalInCents - totalRefundedInCents);
  const typeBadge = orderTypeBadgeStyle(orderTypeRaw);

  const saleIdForRefund = String(
    orderData?.saleTransactionId ?? orderData?.transactionId ?? ""
  ).trim();
  const canShowRemoteCardRefundPanel =
    user != null &&
    orderData != null &&
    saleTransactionData != null &&
    status === "CLOSED" &&
    remainingInCents > 0 &&
    saleIdForRefund.length > 0 &&
    saleTransactionData.voided !== true &&
    !txRecordHasCashTender(saleTransactionData) &&
    !txRecordIsEcommerce(saleTransactionData);

  const canQueueRemoteCardRefund =
    canShowRemoteCardRefundPanel && saleTransactionData.settled === true;

  const canDirectRefund =
    canQueueRemoteCardRefund &&
    saleTransactionData != null &&
    Array.isArray(saleTransactionData.payments) &&
    (saleTransactionData.payments as Record<string, unknown>[]).some(
      (p) =>
        String(p.pnReferenceId || p.PNReferenceId || "").trim().length > 0 &&
        !String(p.paymentType ?? "").toLowerCase().includes("cash")
    );

  /** Same rule as Android `OrderDetailActivity`: full strike metadata only when order is fully refunded. */
  const fullyRefundedForStrike =
    totalInCents > 0 &&
    (totalRefundedInCents >= totalInCents ||
      (refundLineIndex?.totalFromRefundTxnsCents ?? 0) >= totalInCents);

  function lineRefundStrike(line: LineItem): { by: string; at: string } | null {
    if (!refundLineIndex) return null;
    const idx = refundLineIndex;
    if (idx.lineKeys.has(line.id)) {
      return idx.lineKeyMeta.get(line.id) ?? null;
    }
    const nameKey = `${line.name.trim()}|${line.lineTotalInCents}`;
    if (idx.nameAmountKeys.has(nameKey)) {
      return idx.nameAmountMeta.get(nameKey) ?? null;
    }
    if (fullyRefundedForStrike && idx.wholeOrderLast) {
      return idx.wholeOrderLast;
    }
    return null;
  }

  const voidedByOrder = String(orderData?.voidedBy ?? "").trim();
  const voidedAtOrderLabel = formatFirestoreTimestamp(orderData?.voidedAt);
  const saleVoided = saleTransactionData?.voided === true;
  const saleVoidedBy = String(saleTransactionData?.voidedBy ?? "").trim();
  const saleVoidedAtLabel = formatFirestoreTimestamp(
    saleTransactionData?.voidedAt
  );

  return (
    <>
      <Header title={`Order #${orderNumStr}`} />
      <div className="p-6 max-w-3xl mx-auto space-y-6">
        <button
          type="button"
          onClick={() => router.push(backHref)}
          className="inline-flex items-center gap-2 text-sm text-slate-600 hover:text-slate-900"
        >
          <ArrowLeft size={18} />
          {backLabel}
        </button>

        {loading && (
          <div className="flex items-center gap-2 text-slate-500 py-12 justify-center">
            <Loader2 className="animate-spin" size={22} />
            Loading order…
          </div>
        )}

        {!loading && error && (
          <div className="rounded-xl border border-red-200 bg-red-50 text-red-800 px-4 py-3 text-sm">
            {error}
          </div>
        )}

        {!loading && !error && orderData && (
          <>
            <div className="bg-white rounded-2xl border border-slate-100 shadow-sm p-6 space-y-3">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <h2 className="text-lg font-semibold text-slate-800">
                  Order #{orderNumStr}
                </h2>
                <span className="text-xs font-medium uppercase tracking-wide text-slate-400">
                  {status}
                </span>
              </div>
              <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-8 gap-y-2 text-sm">
                <div>
                  <dt className="text-slate-500">Type</dt>
                  <dd className="mt-1">
                    <span
                      className="inline-block text-xs font-semibold text-white px-2.5 py-1 rounded-full tracking-wide"
                      style={{ backgroundColor: typeBadge.backgroundColor }}
                      aria-label={formatOrderTypeLabel(orderTypeRaw)}
                    >
                      {typeBadge.label}
                    </span>
                  </dd>
                </div>
                <div>
                  <dt className="text-slate-500">Employee</dt>
                  <dd className="font-medium text-slate-800">{employeeName}</dd>
                </div>
                {customerName ? (
                  <div className="sm:col-span-2">
                    <dt className="text-slate-500">Customer</dt>
                    <dd className="font-medium text-slate-800">{customerName}</dd>
                  </div>
                ) : null}
                {tableName ? (
                  <div>
                    <dt className="text-slate-500">Table</dt>
                    <dd className="font-medium text-slate-800">{tableName}</dd>
                  </div>
                ) : null}
                {createdAt && (
                  <div>
                    <dt className="text-slate-500">Created</dt>
                    <dd className="font-medium text-slate-800">
                      {createdAt.toLocaleString()}
                    </dd>
                  </div>
                )}
                {batchId ? (
                  <div>
                    <dt className="text-slate-500">Batch</dt>
                    <dd className="font-mono text-xs text-slate-700 break-all">
                      {batchId}
                    </dd>
                  </div>
                ) : null}
                {status === "VOIDED" && (voidedByOrder || voidedAtOrderLabel) ? (
                  <>
                    {voidedByOrder ? (
                      <div>
                        <dt className="text-slate-500">Voided by</dt>
                        <dd className="font-medium text-slate-800">
                          {voidedByOrder}
                        </dd>
                      </div>
                    ) : null}
                    {voidedAtOrderLabel ? (
                      <div>
                        <dt className="text-slate-500">Voided at</dt>
                        <dd className="font-medium text-slate-800">
                          {voidedAtOrderLabel}
                        </dd>
                      </div>
                    ) : null}
                  </>
                ) : null}
                {saleVoided && saleVoidedBy ? (
                  <div className="sm:col-span-2">
                    <dt className="text-slate-500">Payment void</dt>
                    <dd className="font-medium text-slate-800">
                      Voided by {saleVoidedBy}
                      {saleVoidedAtLabel ? ` · ${saleVoidedAtLabel}` : ""}
                    </dd>
                  </div>
                ) : null}
                {refundActivity.length > 0 ? (
                  <div className="sm:col-span-2">
                    <dt className="text-slate-500">Refunds</dt>
                    <dd className="mt-1 space-y-2 text-sm text-slate-800">
                      {refundActivity.map((r) => (
                        <div key={r.id}>
                          <span className="font-medium">
                            ${centsToMoney(r.amountInCents)}
                          </span>
                          {" refunded by "}
                          <span>{r.refundedBy}</span>
                          {r.createdAtLabel ? (
                            <span className="text-slate-500">
                              {" "}
                              · {r.createdAtLabel}
                            </span>
                          ) : null}
                        </div>
                      ))}
                    </dd>
                  </div>
                ) : null}
              </dl>
            </div>

            <div className="bg-white rounded-2xl border border-slate-100 shadow-sm overflow-hidden">
              <div className="px-6 py-4 border-b border-slate-100">
                <h3 className="text-sm font-semibold text-slate-800">
                  Line items ({lines.length})
                </h3>
              </div>
              {lines.length === 0 ? (
                <p className="px-6 py-8 text-sm text-slate-500 text-center">
                  No line items on this order.
                </p>
              ) : (
                <ul className="divide-y divide-slate-50">
                  {lines.map((line) => {
                    const strike = lineRefundStrike(line);
                    const isRefunded = strike != null;
                    return (
                      <li
                        key={line.id}
                        className={`px-6 py-4 ${isRefunded ? "bg-[#FFF5F5]" : ""}`}
                      >
                        <div className="flex justify-between gap-4">
                          <div className="min-w-0">
                            <p
                              className={`font-medium ${
                                isRefunded
                                  ? "text-[#999999] line-through decoration-[#999999]"
                                  : "text-slate-800"
                              }`}
                            >
                              {line.name}{" "}
                              <span
                                className={
                                  isRefunded
                                    ? "text-[#999999] font-normal"
                                    : "text-slate-500 font-normal"
                                }
                              >
                                ×{line.quantity}
                              </span>
                            </p>
                            {line.modifiers.length > 0 && (
                              <ul className="mt-1 text-xs text-slate-600 space-y-0.5">
                                {line.modifiers.map((m, i) => (
                                  <li key={i}>
                                    {m.action === "ADD"
                                      ? "+"
                                      : m.action === "NO"
                                        ? "−"
                                        : ""}{" "}
                                    {m.name}
                                    {m.price != null && m.price !== 0
                                      ? ` ($${Number(m.price).toFixed(2)})`
                                      : ""}
                                  </li>
                                ))}
                              </ul>
                            )}
                            {isRefunded ? (
                              <div className="mt-2 space-y-0.5">
                                <p className="text-xs font-bold uppercase tracking-wide text-red-600">
                                  Refunded
                                </p>
                                {strike.by && strike.by !== "—" ? (
                                  <p className="text-xs text-red-600/90">
                                    Refunded by {strike.by}
                                  </p>
                                ) : null}
                                {strike.at ? (
                                  <p className="text-xs text-red-600/90">{strike.at}</p>
                                ) : null}
                              </div>
                            ) : null}
                          </div>
                          <div className="text-right shrink-0">
                            <p
                              className={`text-sm font-semibold ${
                                isRefunded
                                  ? "text-[#999999] line-through decoration-[#999999]"
                                  : "text-slate-800"
                              }`}
                            >
                              ${centsToMoney(line.lineTotalInCents)}
                            </p>
                            <p
                              className={`text-xs ${
                                isRefunded
                                  ? "text-[#999999] line-through"
                                  : "text-slate-400"
                              }`}
                            >
                              @ ${centsToMoney(line.unitPriceInCents)} each
                            </p>
                          </div>
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}

              {totalInCents > 0 && (
                <div className="px-6 py-4 border-t border-slate-200 space-y-3 text-sm text-[#555555]">
                  <div className="flex justify-between gap-4">
                    <span>Subtotal</span>
                    <span>${centsToMoney(subtotalCents)}</span>
                  </div>

                  {hasDiscountLines && (
                    <>
                      <p className="text-xs font-semibold text-slate-600 pt-1">
                        Discounts
                      </p>
                      <div className="h-px bg-slate-200" />
                      {groupedDiscounts.length > 0 ? (
                        groupedDiscounts.map((gd, i) => (
                          <div
                            key={`${gd.name}-${i}`}
                            className="flex justify-between gap-4"
                          >
                            <span>
                              •{" "}
                              {formatDiscountReceiptLabel(
                                gd.name,
                                gd.type,
                                gd.value
                              )}
                            </span>
                            <span>-${centsToMoney(gd.totalCents)}</span>
                          </div>
                        ))
                      ) : (
                        <div className="flex justify-between gap-4">
                          <span>• Discount</span>
                          <span>-${centsToMoney(discountInCents)}</span>
                        </div>
                      )}
                    </>
                  )}

                  {taxEntries.length > 0 && (
                    <>
                      <p className="text-xs font-semibold text-slate-600 pt-1">
                        Taxes
                      </p>
                      <div className="h-px bg-slate-200" />
                      {taxEntries.map((entry, i) => (
                        <div
                          key={`${entry.name}-${i}`}
                          className="flex justify-between gap-4"
                        >
                          <span>{formatTaxBreakdownLabel(entry)}</span>
                          <span>${centsToMoney(entry.amountInCents)}</span>
                        </div>
                      ))}
                    </>
                  )}

                  <div className="flex justify-between gap-4 pt-1">
                    <span className="text-[#2E7D32]">{tipLabel}</span>
                    <span className="text-[#2E7D32]">
                      ${centsToMoney(tipAmountInCents)}
                    </span>
                  </div>

                  <div className="h-px bg-slate-300 my-2" />

                  <div className="flex justify-between gap-4">
                    <span>Original Total</span>
                    <span>${centsToMoney(totalInCents)}</span>
                  </div>
                  <div className="flex justify-between gap-4">
                    <span>Paid</span>
                    <span className="font-medium text-slate-900">
                      ${centsToMoney(totalPaidInCents)}
                    </span>
                  </div>
                  {totalRefundedInCents > 0 && (
                    <div className="flex justify-between gap-4 text-red-600 font-medium">
                      <span>Refunded</span>
                      <span>${centsToMoney(totalRefundedInCents)}</span>
                    </div>
                  )}
                  <div className="flex justify-between gap-4 pt-1 text-base">
                    <span className="font-semibold text-slate-800">
                      Remaining
                    </span>
                    <span className="font-bold text-slate-900">
                      ${centsToMoney(remainingInCents)}
                    </span>
                  </div>
                </div>
              )}
            </div>

            {termCaps.supportsRefund && canShowRemoteCardRefundPanel ? (
              <div className="bg-emerald-50/90 rounded-2xl border border-emerald-100 shadow-sm p-6 space-y-3">
                <h3 className="text-sm font-semibold text-emerald-900">
                  Remote card refund (POS)
                </h3>
                {canQueueRemoteCardRefund ? (
                  <p className="text-xs text-emerald-800/95 leading-relaxed">
                    Sends a <span className="font-mono">refundTransaction</span> command to{" "}
                    <span className="font-mono">RemotePaymentCommands</span>. A tablet with MaxiPay
                    signed in runs SPIn <span className="font-mono">/Payment/Return</span> on this sale.
                    The amount is capped to the order&apos;s remaining refundable total and the card
                    sale total on the device.
                  </p>
                ) : (
                  <p className="text-xs text-amber-900/95 leading-relaxed rounded-lg border border-amber-200 bg-amber-50/90 px-3 py-2.5">
                    This card sale is <span className="font-semibold">not settled</span> yet. The POS cannot run a
                    card return until the batch settles. Use <span className="font-semibold">Sales activity</span>{" "}
                    → void on the POS for an unsettled reversal, or queue a refund after settlement.
                  </p>
                )}
                <label className="block text-xs font-medium text-emerald-900">
                  Refund amount (USD)
                  <input
                    type="number"
                    min="0.01"
                    step="0.01"
                    value={refundAmountInput}
                    onChange={(e) => setRefundAmountInput(e.target.value)}
                    disabled={!canQueueRemoteCardRefund}
                    className="mt-1 w-full max-w-xs rounded-lg border border-emerald-200 bg-white px-3 py-2 text-sm text-slate-800 disabled:opacity-50 disabled:cursor-not-allowed"
                  />
                </label>
                <button
                  type="button"
                  disabled={refundSubmitting || !canQueueRemoteCardRefund}
                  onClick={async () => {
                    if (!user) return;
                    const dollars = parseFloat(refundAmountInput);
                    if (!Number.isFinite(dollars) || dollars <= 0) {
                      setRefundSubmitErr("Enter a valid refund amount greater than zero.");
                      return;
                    }
                    const amountInCents = Math.round(dollars * 100);
                    setRefundSubmitting(true);
                    setRefundSubmitErr(null);
                    try {
                      const ref = await addDoc(collection(db, REMOTE_PAYMENT_COMMANDS), {
                        type: "refundTransaction",
                        transactionId: saleIdForRefund,
                        orderId,
                        amountInCents,
                        status: "pending",
                        requestedByUid: user.uid,
                        requestedByEmail: user.email ?? "",
                        refundedByLabel: `Dashboard: ${user.email ?? user.uid}`,
                        requestedAt: serverTimestamp(),
                      });
                      setRefundCmdId(ref.id);
                    } catch (err) {
                      console.error("[Order detail] remote refund queue", err);
                      setRefundSubmitErr(
                        err instanceof Error ? err.message : "Could not queue refund request"
                      );
                    } finally {
                      setRefundSubmitting(false);
                    }
                  }}
                  className="inline-flex items-center justify-center px-4 py-2.5 rounded-xl bg-emerald-700 text-white text-sm font-semibold hover:bg-emerald-800 disabled:opacity-60"
                >
                  {refundSubmitting ? "Queueing…" : "Request refund on POS"}
                </button>
                {refundSubmitErr ? (
                  <p className="text-xs text-red-700">{refundSubmitErr}</p>
                ) : null}
                {refundCmdStatus ? (
                  <div className="text-xs text-emerald-950 space-y-0.5">
                    <p>
                      <span className="font-semibold">Command status:</span> {refundCmdStatus}
                    </p>
                    {refundCmdDetail ? (
                      <p className="text-emerald-900/90 break-words">{refundCmdDetail}</p>
                    ) : null}
                  </div>
                ) : null}

                {canDirectRefund ? (
                  <div className="mt-3 pt-3 border-t border-emerald-200 space-y-2">
                    <p className="text-xs text-emerald-800/90 leading-relaxed">
                      Or refund <span className="font-semibold">without the card present</span> using the
                      processor reference from the original sale. Processed server-side — no POS or terminal needed.
                    </p>
                    <button
                      type="button"
                      disabled={directRefundSubmitting || refundSubmitting}
                      onClick={async () => {
                        if (!user) return;
                        const dollars = parseFloat(refundAmountInput);
                        if (!Number.isFinite(dollars) || dollars <= 0) {
                          setDirectRefundErr("Enter a valid refund amount greater than zero.");
                          return;
                        }
                        const amountInCents = Math.round(dollars * 100);
                        setDirectRefundSubmitting(true);
                        setDirectRefundErr(null);
                        setDirectRefundResult(null);
                        try {
                          const region = process.env.NEXT_PUBLIC_FIREBASE_FUNCTIONS_REGION;
                          const app = getApp();
                          const functions = region ? getFunctions(app, region) : getFunctions(app);
                          const call = httpsCallable(functions, "processServerRefund");
                          const res = await call({
                            transactionId: saleIdForRefund,
                            orderId,
                            amountInCents,
                          });
                          const d = res.data as Record<string, unknown>;
                          if (d.success) {
                            setDirectRefundResult(String(d.message ?? "Refund processed."));
                          } else {
                            setDirectRefundErr(String(d.error ?? "Refund failed."));
                          }
                        } catch (err) {
                          console.error("[Order detail] direct refund", err);
                          setDirectRefundErr(
                            err instanceof Error ? err.message : "Could not process refund"
                          );
                        } finally {
                          setDirectRefundSubmitting(false);
                        }
                      }}
                      className="inline-flex items-center justify-center px-4 py-2.5 rounded-xl bg-indigo-700 text-white text-sm font-semibold hover:bg-indigo-800 disabled:opacity-60"
                    >
                      {directRefundSubmitting ? "Processing…" : "Direct refund (no card)"}
                    </button>
                    {directRefundErr ? (
                      <p className="text-xs text-red-700 break-words">{directRefundErr}</p>
                    ) : null}
                    {directRefundResult ? (
                      <p className="text-xs text-emerald-800 font-medium break-words">{directRefundResult}</p>
                    ) : null}
                  </div>
                ) : null}
              </div>
            ) : null}

            <p className="text-xs text-slate-400">
              Read-only view of Firestore{" "}
              <code className="text-slate-500">Orders/{orderId}</code> and{" "}
              <code className="text-slate-500">items</code> subcollection.
            </p>
          </>
        )}
      </div>
    </>
  );
}
