"use client";

import {
  useEffect,
  useState,
  useRef,
  type PointerEvent,
  type ReactNode,
} from "react";
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
  orderStatusDisplayForUi,
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

const LINE_SWIPE_ACTION_PX = 112;

/**
 * Drag the row to the right to reveal a direct-refund action (underlay on the left).
 */
function SwipeableOrderLine({
  lineId,
  swipeEnabled,
  isOpen,
  rowClassName,
  onOpenChange,
  onDirectRefund,
  children,
}: {
  lineId: string;
  swipeEnabled: boolean;
  isOpen: boolean;
  rowClassName: string;
  onOpenChange: (openLineId: string | null) => void;
  onDirectRefund: () => void;
  children: ReactNode;
}) {
  const [tx, setTx] = useState(0);
  const txRef = useRef(0);
  const setTxClamped = (v: number) => {
    const c = Math.min(LINE_SWIPE_ACTION_PX, Math.max(0, v));
    txRef.current = c;
    setTx(c);
  };

  const [isDragging, setIsDragging] = useState(false);
  const dragRef = useRef({ startClientX: 0, baseTx: 0 });
  const activePointerId = useRef<number | null>(null);

  useEffect(() => {
    if (!swipeEnabled) return;
    const target = isOpen ? LINE_SWIPE_ACTION_PX : 0;
    txRef.current = target;
    setTx(target);
  }, [isOpen, swipeEnabled]);

  if (!swipeEnabled) {
    return <li className={rowClassName}>{children}</li>;
  }

  function handlePointerEnd(e: PointerEvent<HTMLDivElement>) {
    if (activePointerId.current !== e.pointerId) return;
    try {
      e.currentTarget.releasePointerCapture(e.pointerId);
    } catch {
      /* already released */
    }
    activePointerId.current = null;
    setIsDragging(false);
    const final = txRef.current;
    const mid = LINE_SWIPE_ACTION_PX / 2;
    if (final >= mid) {
      setTxClamped(LINE_SWIPE_ACTION_PX);
      onOpenChange(lineId);
    } else {
      setTxClamped(0);
      onOpenChange(null);
    }
  }

  return (
    <li className={`relative overflow-hidden ${rowClassName}`}>
      <div
        className="absolute left-0 top-0 bottom-0 z-0 flex"
        style={{ width: LINE_SWIPE_ACTION_PX }}
      >
        <button
          type="button"
          className="flex-1 bg-indigo-700 px-2 text-center text-[11px] font-semibold leading-tight text-white hover:bg-indigo-800"
          onClick={(e) => {
            e.stopPropagation();
            onOpenChange(null);
            onDirectRefund();
          }}
        >
          Direct refund
        </button>
      </div>
      <div
        className="relative z-10 bg-white shadow-[1px_0_0_rgba(0,0,0,0.04)]"
        style={{
          transform: `translateX(${tx}px)`,
          transition: isDragging ? "none" : "transform 0.2s ease-out",
          touchAction: "none",
        }}
        onPointerDown={(e) => {
          if (e.button !== 0) return;
          e.currentTarget.setPointerCapture(e.pointerId);
          activePointerId.current = e.pointerId;
          setIsDragging(true);
          dragRef.current = {
            startClientX: e.clientX,
            baseTx: txRef.current,
          };
        }}
        onPointerMove={(e) => {
          if (activePointerId.current !== e.pointerId) return;
          const delta = e.clientX - dragRef.current.startClientX;
          setTxClamped(dragRef.current.baseTx + delta);
        }}
        onPointerUp={handlePointerEnd}
        onPointerCancel={handlePointerEnd}
      >
        {children}
      </div>
    </li>
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

  const [directRefundModalOpen, setDirectRefundModalOpen] = useState(false);
  const [directRefundModalAmount, setDirectRefundModalAmount] = useState("");

  const [directRefundSubmitting, setDirectRefundSubmitting] = useState(false);
  const [directRefundResult, setDirectRefundResult] = useState<string | null>(null);
  const [directRefundErr, setDirectRefundErr] = useState<string | null>(null);
  const [directRefundModalErr, setDirectRefundModalErr] = useState<string | null>(null);
  const [openSwipeLineId, setOpenSwipeLineId] = useState<string | null>(null);
  const [directRefundTargetLine, setDirectRefundTargetLine] = useState<LineItem | null>(
    null
  );
  const [orderRefreshNonce, setOrderRefreshNonce] = useState(0);

  const [voidSubmitting, setVoidSubmitting] = useState(false);
  const [voidErr, setVoidErr] = useState<string | null>(null);
  const [voidCmdId, setVoidCmdId] = useState<string | null>(null);
  const [voidCmdStatus, setVoidCmdStatus] = useState<string | null>(null);
  const [voidCmdDetail, setVoidCmdDetail] = useState<string | null>(null);

  useEffect(() => {
    setDirectRefundModalOpen(false);
    setDirectRefundModalAmount("");
    setDirectRefundModalErr(null);
    setDirectRefundSubmitting(false);
    setDirectRefundResult(null);
    setDirectRefundErr(null);
    setOpenSwipeLineId(null);
    setDirectRefundTargetLine(null);
    setOrderRefreshNonce(0);
    setVoidSubmitting(false);
    setVoidErr(null);
    setVoidCmdId(null);
    setVoidCmdStatus(null);
    setVoidCmdDetail(null);
  }, [orderId]);

  useEffect(() => {
    if (!voidCmdId || !db) {
      setVoidCmdStatus(null);
      setVoidCmdDetail(null);
      return;
    }
    const ref = doc(db, REMOTE_PAYMENT_COMMANDS, voidCmdId);
    const unsub = onSnapshot(
      ref,
      (snap) => {
        if (!snap.exists()) {
          setVoidCmdStatus(null);
          setVoidCmdDetail(null);
          return;
        }
        const d = snap.data();
        setVoidCmdStatus(String(d?.status ?? ""));
        const err = typeof d?.errorMessage === "string" ? d.errorMessage : "";
        const ok = typeof d?.resultMessage === "string" ? d.resultMessage : "";
        setVoidCmdDetail(err || ok || null);
        if (String(d?.status ?? "") === "completed") {
          setOrderRefreshNonce((n) => n + 1);
        }
      },
      (e) => console.error("[Order detail] void command listener", e)
    );
    return () => unsub();
  }, [voidCmdId]);

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
          return;
        }
        const data = snap.data() as Record<string, unknown>;
        setOrderData(data);

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
  }, [user, orderId, orderRefreshNonce]);

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
  const statusDisplayLabel = orderData
    ? orderStatusDisplayForUi(orderData as Record<string, unknown>)
    : "";
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

  const isSaleSettled =
    saleTransactionData != null && saleTransactionData.settled === true;

  const canDirectRefund =
    canShowRemoteCardRefundPanel &&
    isSaleSettled &&
    saleTransactionData != null &&
    Array.isArray(saleTransactionData.payments) &&
    (saleTransactionData.payments as Record<string, unknown>[]).some(
      (p) =>
        String(p.pnReferenceId || p.PNReferenceId || "").trim().length > 0 &&
        !String(p.paymentType ?? "").toLowerCase().includes("cash")
    );

  const canVoidUnsettled =
    user != null &&
    orderData != null &&
    saleTransactionData != null &&
    status === "CLOSED" &&
    saleIdForRefund.length > 0 &&
    saleTransactionData.voided !== true &&
    !isSaleSettled &&
    !txRecordHasCashTender(saleTransactionData) &&
    !txRecordIsEcommerce(saleTransactionData);

  const maxDirectRefundCents = Math.min(
    Math.max(0, totalInCents),
    Math.max(0, remainingInCents)
  );

  const lineSwipeRefundEnabled =
    Boolean(user) && termCaps.supportsRefund && canDirectRefund;

  const directRefundModalMaxCents = directRefundTargetLine
    ? Math.min(
        Math.max(0, directRefundTargetLine.lineTotalInCents),
        Math.max(0, remainingInCents),
        Math.max(0, totalInCents)
      )
    : maxDirectRefundCents;

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
                  {statusDisplayLabel}
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
                    const swipeRow =
                      lineSwipeRefundEnabled && !isRefunded;
                    const rowShell = isRefunded ? "bg-[#FFF5F5]" : "";
                    return (
                      <SwipeableOrderLine
                        key={line.id}
                        lineId={line.id}
                        swipeEnabled={swipeRow}
                        isOpen={openSwipeLineId === line.id}
                        rowClassName={rowShell}
                        onOpenChange={setOpenSwipeLineId}
                        onDirectRefund={() => {
                          setDirectRefundTargetLine(line);
                          setDirectRefundModalErr(null);
                          const maxC = Math.min(
                            line.lineTotalInCents,
                            remainingInCents
                          );
                          setDirectRefundModalAmount(
                            maxC > 0 ? (maxC / 100).toFixed(2) : ""
                          );
                          setDirectRefundModalOpen(true);
                        }}
                      >
                        <div className="px-6 py-4">
                          <p className="sr-only">
                            Swipe right on this row to show direct refund for this
                            line only.
                          </p>
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
                        </div>
                      </SwipeableOrderLine>
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

            {termCaps.supportsRefund && canDirectRefund ? (
              <div className="bg-emerald-50/90 rounded-2xl border border-emerald-100 shadow-sm p-4 space-y-3">
                <button
                  type="button"
                  disabled={directRefundSubmitting}
                  onClick={() => {
                    if (!user) return;
                    setDirectRefundTargetLine(null);
                    setDirectRefundModalErr(null);
                    setDirectRefundModalAmount(
                      maxDirectRefundCents > 0
                        ? (maxDirectRefundCents / 100).toFixed(2)
                        : ""
                    );
                    setDirectRefundModalOpen(true);
                  }}
                  className="inline-flex items-center justify-center px-4 py-2.5 rounded-xl bg-indigo-700 text-white text-sm font-semibold hover:bg-indigo-800 disabled:opacity-60"
                >
                  Direct refund (no card)
                </button>
                {directRefundErr ? (
                  <p className="text-xs text-red-700 break-words">{directRefundErr}</p>
                ) : null}
                {directRefundResult ? (
                  <p className="text-xs text-emerald-800 font-medium break-words">{directRefundResult}</p>
                ) : null}
              </div>
            ) : null}

            {canVoidUnsettled ? (
              <div className="bg-amber-50/90 rounded-2xl border border-amber-100 shadow-sm p-4 space-y-3">
                <button
                  type="button"
                  disabled={voidSubmitting}
                  onClick={async () => {
                    if (!user) return;
                    setVoidSubmitting(true);
                    setVoidErr(null);
                    try {
                      const ref = await addDoc(
                        collection(db, REMOTE_PAYMENT_COMMANDS),
                        {
                          type: "voidTransaction",
                          transactionId: saleIdForRefund,
                          status: "pending",
                          requestedByUid: user.uid,
                          requestedByEmail: user.email ?? "",
                          voidedByLabel: `Dashboard: ${user.email ?? user.uid}`,
                          requestedAt: serverTimestamp(),
                        }
                      );
                      setVoidCmdId(ref.id);
                    } catch (err) {
                      console.error("[Order detail] void queue", err);
                      setVoidErr(
                        err instanceof Error
                          ? err.message
                          : "Could not queue void request"
                      );
                    } finally {
                      setVoidSubmitting(false);
                    }
                  }}
                  className="inline-flex items-center justify-center px-4 py-2.5 rounded-xl bg-amber-700 text-white text-sm font-semibold hover:bg-amber-800 disabled:opacity-60"
                >
                  {voidSubmitting ? "Queueing…" : "Void"}
                </button>
                {voidErr ? (
                  <p className="text-xs text-red-700 break-words">{voidErr}</p>
                ) : null}
                {voidCmdStatus ? (
                  <div className="text-xs text-amber-950 space-y-0.5">
                    <p>
                      <span className="font-semibold">Status:</span>{" "}
                      {voidCmdStatus}
                    </p>
                    {voidCmdDetail ? (
                      <p className="text-amber-900/90 break-words">
                        {voidCmdDetail}
                      </p>
                    ) : null}
                  </div>
                ) : null}
              </div>
            ) : null}

            {directRefundModalOpen ? (
              <div
                className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40"
                role="presentation"
                onClick={() => {
                  if (!directRefundSubmitting) {
                    setDirectRefundModalOpen(false);
                    setDirectRefundTargetLine(null);
                  }
                }}
              >
                <div
                  role="dialog"
                  aria-modal="true"
                  aria-labelledby="direct-refund-modal-title"
                  className="bg-white rounded-2xl shadow-xl max-w-sm w-full p-6 space-y-4"
                  onClick={(e) => e.stopPropagation()}
                >
                  <h3
                    id="direct-refund-modal-title"
                    className="text-base font-semibold text-slate-900"
                  >
                    {directRefundTargetLine
                      ? `Refund: ${directRefundTargetLine.name}`
                      : "Refund amount"}
                  </h3>
                  <p className="text-xs text-slate-600 leading-relaxed">
                    {directRefundTargetLine ? (
                      <>
                        Line total{" "}
                        <span className="font-medium text-slate-800">
                          ${centsToMoney(directRefundTargetLine.lineTotalInCents)}
                        </span>
                        . Original order total{" "}
                        <span className="font-medium text-slate-800">
                          ${centsToMoney(totalInCents)}
                        </span>
                        . You may refund up to{" "}
                        <span className="font-medium text-slate-800">
                          ${centsToMoney(directRefundModalMaxCents)}
                        </span>{" "}
                        for this line (not above the line total, original total, or remaining
                        balance).
                      </>
                    ) : (
                      <>
                        Original total{" "}
                        <span className="font-medium text-slate-800">
                          ${centsToMoney(totalInCents)}
                        </span>
                        . You may refund up to{" "}
                        <span className="font-medium text-slate-800">
                          ${centsToMoney(maxDirectRefundCents)}
                        </span>{" "}
                        (not above the original total and not more than the remaining balance).
                      </>
                    )}
                  </p>
                  <label className="block text-sm font-medium text-slate-800">
                    Amount (USD)
                    <input
                      type="number"
                      min="0.01"
                      step="0.01"
                      max={
                        directRefundModalMaxCents > 0
                          ? directRefundModalMaxCents / 100
                          : undefined
                      }
                      value={directRefundModalAmount}
                      onChange={(e) => {
                        setDirectRefundModalAmount(e.target.value);
                        setDirectRefundModalErr(null);
                      }}
                      className="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800"
                      autoFocus
                    />
                  </label>
                  {directRefundModalErr ? (
                    <p className="text-xs text-red-600 break-words">{directRefundModalErr}</p>
                  ) : null}
                  <div className="flex justify-end gap-2 pt-1">
                    <button
                      type="button"
                      disabled={directRefundSubmitting}
                      onClick={() => {
                        setDirectRefundModalOpen(false);
                        setDirectRefundTargetLine(null);
                      }}
                      className="px-4 py-2 rounded-xl border border-slate-200 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      disabled={directRefundSubmitting}
                      onClick={async () => {
                        if (!user) return;
                        setDirectRefundModalErr(null);
                        const dollars = parseFloat(directRefundModalAmount);
                        if (!Number.isFinite(dollars) || dollars <= 0) {
                          setDirectRefundModalErr(
                            "Enter a valid amount greater than zero."
                          );
                          return;
                        }
                        const amountInCents = Math.round(dollars * 100);
                        if (amountInCents > totalInCents) {
                          setDirectRefundModalErr(
                            `Amount cannot exceed the original order total ($${centsToMoney(totalInCents)}).`
                          );
                          return;
                        }
                        if (amountInCents > remainingInCents) {
                          setDirectRefundModalErr(
                            `Amount cannot exceed the remaining balance ($${centsToMoney(remainingInCents)}).`
                          );
                          return;
                        }
                        if (
                          directRefundTargetLine &&
                          amountInCents > directRefundTargetLine.lineTotalInCents
                        ) {
                          setDirectRefundModalErr(
                            `Amount cannot exceed this line total ($${centsToMoney(directRefundTargetLine.lineTotalInCents)}).`
                          );
                          return;
                        }
                        setDirectRefundSubmitting(true);
                        setDirectRefundErr(null);
                        setDirectRefundResult(null);
                        try {
                          const region =
                            process.env.NEXT_PUBLIC_FIREBASE_FUNCTIONS_REGION;
                          const app = getApp();
                          const functions = region
                            ? getFunctions(app, region)
                            : getFunctions(app);
                          const call = httpsCallable(functions, "processServerRefund");
                          const payload: Record<string, unknown> = {
                            transactionId: saleIdForRefund,
                            orderId,
                            amountInCents,
                          };
                          if (directRefundTargetLine) {
                            payload.refundedLineKey = directRefundTargetLine.id;
                            payload.refundedItemName =
                              directRefundTargetLine.name.trim();
                          }
                          const res = await call(payload);
                          const d = res.data as Record<string, unknown>;
                          if (d.success) {
                            setDirectRefundResult(
                              String(d.message ?? "Refund processed.")
                            );
                            setDirectRefundModalOpen(false);
                            setDirectRefundModalAmount("");
                            setDirectRefundTargetLine(null);
                            setOpenSwipeLineId(null);
                            setOrderRefreshNonce((n) => n + 1);
                          } else {
                            setDirectRefundModalErr(String(d.error ?? "Refund failed."));
                          }
                        } catch (err) {
                          console.error("[Order detail] direct refund", err);
                          setDirectRefundModalErr(
                            err instanceof Error
                              ? err.message
                              : "Could not process refund"
                          );
                        } finally {
                          setDirectRefundSubmitting(false);
                        }
                      }}
                      className="px-4 py-2 rounded-xl bg-indigo-700 text-white text-sm font-semibold hover:bg-indigo-800 disabled:opacity-60"
                    >
                      {directRefundSubmitting ? "Processing…" : "Refund"}
                    </button>
                  </div>
                </div>
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
