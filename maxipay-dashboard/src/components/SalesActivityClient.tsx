"use client";

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import {
  Timestamp,
  addDoc,
  collection,
  doc,
  limit,
  onSnapshot,
  orderBy,
  query,
  serverTimestamp,
  where,
  type DocumentData,
} from "firebase/firestore";
import { getApp } from "firebase/app";
import { getFunctions, httpsCallable } from "firebase/functions";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import { Banknote, CreditCard, Layers, Loader2, Plus, Search, X } from "lucide-react";
import { startOfLocalDay } from "@/lib/dashboardFinance";
import {
  effectivePosOrderStatus,
  isWebOnlineOrder,
  orderTypeBadgeStyle,
} from "@/lib/orderDisplayUtils";

function fmtMoney(cents: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
  }).format(cents / 100);
}

function docDate(data: DocumentData): Date | null {
  const c = data.createdAt;
  if (c && typeof c.toDate === "function") return c.toDate();
  if (data.timestamp && typeof (data.timestamp as Timestamp).toDate === "function") {
    return (data.timestamp as Timestamp).toDate();
  }
  return null;
}

function statusPill(statusRaw: string): { label: string; className: string } {
  const u = String(statusRaw || "").trim().toUpperCase();
  if (u === "OPEN") {
    return { label: "OPEN", className: "bg-orange-100 text-orange-800" };
  }
  if (u === "VOIDED") {
    return { label: "VOIDED", className: "bg-red-100 text-red-800" };
  }
  if (u === "REFUNDED" || u === "PARTIALLY_REFUNDED" || u === "REFUNDED_FULLY") {
    return { label: "REFUNDED", className: "bg-violet-100 text-violet-800" };
  }
  if (!u) {
    return { label: "—", className: "bg-slate-100 text-slate-700" };
  }
  return { label: u, className: "bg-emerald-100 text-emerald-800" };
}

type TabId = "orders" | "transactions" | "cash";
type DatePreset = "today" | "yesterday" | "7days" | "custom";
type PaymentFilter = "all" | "cash" | "credit" | "debit";

interface BatchOption {
  id: string | null;
  label: string;
}

function rangeFromPreset(
  preset: DatePreset,
  customStart: string,
  customEnd: string
): { start: Date; endExclusive: Date } {
  const now = new Date();
  if (preset === "today") {
    const start = startOfLocalDay(now, 0);
    const endExclusive = startOfLocalDay(now, 1);
    return { start, endExclusive };
  }
  if (preset === "yesterday") {
    const start = startOfLocalDay(now, -1);
    const endExclusive = startOfLocalDay(now, 0);
    return { start, endExclusive };
  }
  if (preset === "7days") {
    const endExclusive = startOfLocalDay(now, 1);
    const start = startOfLocalDay(now, -6);
    return { start, endExclusive };
  }
  const [ys, ms, ds] = customStart.split("-").map(Number);
  const [ye, me, de] = customEnd.split("-").map(Number);
  const start = new Date(ys, ms - 1, ds, 0, 0, 0, 0);
  const endDay = new Date(ye, me - 1, de, 0, 0, 0, 0);
  const endExclusive = startOfLocalDay(endDay, 1);
  return { start, endExclusive };
}

function txAmountCents(data: DocumentData, type: string): number {
  if (type === "REFUND") {
    const c = Number(data.amountInCents ?? 0);
    if (c !== 0) return Math.abs(c);
    return Math.round(Math.abs(Number(data.amount ?? 0)) * 100);
  }
  const paid = Number(data.totalPaidInCents ?? 0);
  if (paid !== 0) return Math.abs(paid);
  return Math.round(Math.abs(Number(data.totalPaid ?? 0)) * 100);
}

function primaryPaymentType(data: DocumentData): string {
  const pays = data.payments as unknown[] | undefined;
  if (Array.isArray(pays) && pays.length > 0) {
    const p0 = pays[0] as Record<string, unknown>;
    return String(p0.paymentType ?? "");
  }
  return String(data.paymentType ?? "");
}

function paymentsLast4s(data: DocumentData): string[] {
  const pays = data.payments as unknown[] | undefined;
  if (!Array.isArray(pays)) return [];
  return pays
    .map((p) => String((p as Record<string, unknown>).last4 ?? "").trim().toLowerCase())
    .filter(Boolean);
}

const REMOTE_PAYMENT_COMMANDS = "RemotePaymentCommands";

function transactionHasCashTender(data: DocumentData): boolean {
  const pays = data.payments as unknown[] | undefined;
  if (!Array.isArray(pays)) return false;
  for (const p of pays) {
    const o = p as Record<string, unknown>;
    const pt = String(o.paymentType ?? "").trim().toLowerCase();
    const cents = Number(o.amountInCents ?? 0);
    if (pt === "cash" && cents > 0) return true;
  }
  return false;
}

/** Cash tender on a sale/capture — same idea as POS Cash Flow. */
function cashTenderCentsForSale(data: DocumentData): number {
  const type = String(data.type ?? "");
  if (type !== "SALE" && type !== "CAPTURE") return 0;
  if (data.voided === true) return 0;
  const pays = data.payments as Record<string, unknown>[] | undefined;
  if (Array.isArray(pays) && pays.length > 0) {
    let c = 0;
    for (const p of pays) {
      if (String(p.status) === "VOIDED") continue;
      const pt = String(p.paymentType ?? "").toLowerCase();
      const ac = Number(p.amountInCents ?? 0);
      if (pt === "cash") c += ac;
    }
    if (c !== 0) return Math.round(Math.abs(c));
    if (primaryPaymentType(data).toLowerCase() === "cash") {
      return txAmountCents(data, type);
    }
    return 0;
  }
  if (primaryPaymentType(data).toLowerCase() === "cash") {
    return txAmountCents(data, type);
  }
  return 0;
}

/** Cash refunded to customer — matches POS cash-out from refunds. */
function cashTenderCentsForRefund(data: DocumentData): number {
  if (String(data.type ?? "") !== "REFUND") return 0;
  if (data.voided === true) return 0;
  const pays = data.payments as Record<string, unknown>[] | undefined;
  if (Array.isArray(pays) && pays.length > 0) {
    let c = 0;
    for (const p of pays) {
      if (String(p.status) === "VOIDED") continue;
      if (String(p.paymentType ?? "").toLowerCase() === "cash") {
        c += Math.abs(Number(p.amountInCents ?? 0));
      }
    }
    if (c !== 0) return Math.round(c);
  }
  if (String(data.paymentType ?? "").toLowerCase() === "cash") {
    return Math.abs(txAmountCents(data, "REFUND"));
  }
  return 0;
}

/** Card-only unsettled sales suitable for dashboard → POS void queue. */
function canRequestRemoteVoid(data: DocumentData): boolean {
  if (!inFinancialTx(data)) return false;
  const type = String(data.type ?? "");
  if (!["SALE", "CAPTURE", "PRE_AUTH"].includes(type)) return false;
  if (data.voided === true) return false;
  if (data.settled === true) return false;
  if (transactionHasCashTender(data)) return false;
  return true;
}

function inFinancialTx(data: DocumentData): boolean {
  const type = String(data.type ?? "");
  if (type === "CASH_ADD" || type === "PAID_OUT") return false;
  if (type === "SALE" && data.totalPaid == null && data.totalPaidInCents == null) return false;
  if (type === "PRE_AUTH" && data.totalPaidInCents == null) return false;
  return ["SALE", "CAPTURE", "PRE_AUTH", "REFUND"].includes(type);
}

type ReceiptKind = "standard" | "void" | "refund";

function receiptKindForTransaction(data: DocumentData): {
  receiptKind: ReceiptKind;
  saleTransactionId: string;
} {
  const t = String(data.type ?? "");
  const voided = data.voided === true;
  if (t === "REFUND") {
    return {
      receiptKind: "refund",
      saleTransactionId: String(data.originalReferenceId ?? "").trim(),
    };
  }
  if (voided && (t === "SALE" || t === "CAPTURE" || t === "PRE_AUTH")) {
    return { receiptKind: "void", saleTransactionId: "" };
  }
  return { receiptKind: "standard", saleTransactionId: "" };
}

export default function SalesActivityClient() {
  const { user } = useAuth();
  const [tab, setTab] = useState<TabId>("orders");
  const [datePreset, setDatePreset] = useState<DatePreset>("today");
  const [customStart, setCustomStart] = useState(() => {
    const d = new Date();
    d.setDate(d.getDate() - 7);
    return d.toISOString().slice(0, 10);
  });
  const [customEnd, setCustomEnd] = useState(() => new Date().toISOString().slice(0, 10));
  const [batchId, setBatchId] = useState<string | null>(null);
  const [batches, setBatches] = useState<BatchOption[]>([{ id: null, label: "All batches" }]);
  const [search, setSearch] = useState("");
  const [paymentFilter, setPaymentFilter] = useState<PaymentFilter>("all");

  const [orderDocs, setOrderDocs] = useState<{ id: string; data: DocumentData }[]>([]);
  const [txDocs, setTxDocs] = useState<{ id: string; data: DocumentData }[]>([]);
  const [cashLogDocs, setCashLogDocs] = useState<{ id: string; data: DocumentData }[]>([]);
  const [loadErr, setLoadErr] = useState<string | null>(null);

  const [txModal, setTxModal] = useState<{ id: string; data: DocumentData } | null>(null);
  const [receiptPreview, setReceiptPreview] = useState<{
    html: string;
    orderId: string;
  } | null>(null);
  const [receiptLoading, setReceiptLoading] = useState(false);
  const [receiptErr, setReceiptErr] = useState<string | null>(null);
  const receiptIframeRef = useRef<HTMLIFrameElement>(null);
  const receiptResizeObserverRef = useRef<ResizeObserver | null>(null);
  const [cashPickOpen, setCashPickOpen] = useState(false);
  const [cashModal, setCashModal] = useState<"IN" | "OUT" | "START" | "DROP" | null>(null);

  useEffect(() => {
    if (tab !== "cash") setCashPickOpen(false);
  }, [tab]);

  useEffect(() => {
    setReceiptPreview(null);
    setReceiptErr(null);
  }, [txModal?.id]);

  useEffect(() => {
    if (!receiptPreview) {
      receiptResizeObserverRef.current?.disconnect();
      receiptResizeObserverRef.current = null;
    }
  }, [receiptPreview]);

  const applyReceiptIframeHeight = useCallback(() => {
    const iframe = receiptIframeRef.current;
    const doc = iframe?.contentDocument;
    if (!iframe || !doc?.documentElement) return;
    const h = Math.max(
      doc.documentElement.scrollHeight,
      doc.body?.scrollHeight ?? 0
    );
    iframe.style.height = `${Math.ceil(h + 12)}px`;
  }, []);

  const attachReceiptIframeSizing = useCallback(() => {
    const iframe = receiptIframeRef.current;
    const doc = iframe?.contentDocument;
    if (!iframe || !doc?.body) return;
    applyReceiptIframeHeight();
    receiptResizeObserverRef.current?.disconnect();
    const ro = new ResizeObserver(() => applyReceiptIframeHeight());
    ro.observe(doc.body);
    if (doc.documentElement) ro.observe(doc.documentElement);
    receiptResizeObserverRef.current = ro;
  }, [applyReceiptIframeHeight]);

  useLayoutEffect(() => {
    return () => {
      receiptResizeObserverRef.current?.disconnect();
      receiptResizeObserverRef.current = null;
    };
  }, []);
  const [cashAmount, setCashAmount] = useState("");
  const [cashReason, setCashReason] = useState("");
  const [cashSaving, setCashSaving] = useState(false);

  const [voidCmdId, setVoidCmdId] = useState<string | null>(null);
  const [voidSubmitting, setVoidSubmitting] = useState(false);
  const [voidSubmitErr, setVoidSubmitErr] = useState<string | null>(null);
  const [voidCmdStatus, setVoidCmdStatus] = useState<string | null>(null);
  const [voidCmdDetail, setVoidCmdDetail] = useState<string | null>(null);

  useEffect(() => {
    setVoidCmdId(null);
    setVoidSubmitErr(null);
    setVoidCmdStatus(null);
    setVoidCmdDetail(null);
  }, [txModal?.id]);

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
      },
      (e) => console.error("[SalesActivity] void command", e)
    );
    return () => unsub();
  }, [voidCmdId]);

  // Auto-close the transaction modal a beat after a remote void finishes successfully.
  // We wait ~1.2s so the merchant can see the "completed" confirmation before the modal vanishes.
  useEffect(() => {
    if (voidCmdStatus !== "completed") return;
    const t = window.setTimeout(() => {
      setReceiptPreview(null);
      setReceiptErr(null);
      setTxModal(null);
    }, 1200);
    return () => window.clearTimeout(t);
  }, [voidCmdStatus]);

  const { start, endExclusive } = useMemo(
    () => rangeFromPreset(datePreset, customStart, customEnd),
    [datePreset, customStart, customEnd]
  );

  const tsStart = useMemo(() => Timestamp.fromDate(start), [start]);
  const tsEnd = useMemo(() => Timestamp.fromDate(endExclusive), [endExclusive]);

  const qLower = search.trim().toLowerCase();

  useEffect(() => {
    if (typeof window === "undefined" || !db) return;
    const unsubs: Array<() => void> = [];

    const bq = query(collection(db, "Batches"), orderBy("createdAt", "desc"), limit(100));
    unsubs.push(
      onSnapshot(
        bq,
        (snap) => {
          const opts: BatchOption[] = [{ id: null, label: "All batches" }];
          snap.forEach((d) => {
            const closed = d.get("closed") === true;
            const created = d.get("createdAt")?.toDate?.() ?? new Date();
            opts.push({
              id: d.id,
              label: closed
                ? `Closed ${created.toLocaleString()}`
                : `Open ${created.toLocaleString()}`,
            });
          });
          setBatches(opts);
        },
        (e) => console.error("[SalesActivity] batches", e)
      )
    );

    // Date range OR batch (not both): with a batch selected, load everything for that batch;
    // with "All batches", constrain by createdAt only.
    // NOTE: When filtering by batchId we intentionally omit server-side orderBy so the query
    // doesn't require a (batchId, createdAt) composite index. Results are sorted client-side.
    const ordersQ = batchId
      ? query(
          collection(db, "Orders"),
          where("batchId", "==", batchId),
          limit(400)
        )
      : query(
          collection(db, "Orders"),
          where("createdAt", ">=", tsStart),
          where("createdAt", "<", tsEnd),
          orderBy("createdAt", "desc"),
          limit(400)
        );

    const txQ = batchId
      ? query(
          collection(db, "Transactions"),
          where("batchId", "==", batchId),
          limit(900)
        )
      : query(
          collection(db, "Transactions"),
          where("createdAt", ">=", tsStart),
          where("createdAt", "<", tsEnd),
          orderBy("createdAt", "desc"),
          limit(900)
        );

    const byCreatedAtDesc = (
      a: { data: DocumentData },
      b: { data: DocumentData }
    ): number => {
      const ad = docDate(a.data)?.getTime() ?? 0;
      const bd = docDate(b.data)?.getTime() ?? 0;
      return bd - ad;
    };

    try {
      unsubs.push(
        onSnapshot(
          ordersQ,
          (snap) => {
            setLoadErr(null);
            const rows: { id: string; data: DocumentData }[] = [];
            snap.forEach((d) => rows.push({ id: d.id, data: d.data() }));
            rows.sort(byCreatedAtDesc);
            setOrderDocs(rows);
          },
          (e) => {
            console.error(e);
            setLoadErr((e as Error).message ?? String(e));
            setOrderDocs([]);
          }
        )
      );

      // Include orders voided *within* the selected date range even if the order was created earlier.
      // This keeps dashboard Sales Activity aligned with POS behavior (voids show on the day they occur).
      if (!batchId) {
        const voidedOrdersQ = query(
          collection(db, "Orders"),
          where("status", "==", "VOIDED"),
          where("voidedAt", ">=", tsStart),
          where("voidedAt", "<", tsEnd),
          orderBy("voidedAt", "desc"),
          limit(400)
        );
        unsubs.push(
          onSnapshot(
            voidedOrdersQ,
            (snap) => {
              setLoadErr(null);
              const voidedRows: { id: string; data: DocumentData }[] = [];
              snap.forEach((d) => voidedRows.push({ id: d.id, data: d.data() }));
              setOrderDocs((prev) => {
                if (voidedRows.length === 0) return prev;
                const byId = new Map<string, { id: string; data: DocumentData }>();
                prev.forEach((r) => byId.set(r.id, r));
                voidedRows.forEach((r) => byId.set(r.id, r));
                const merged = Array.from(byId.values());
                merged.sort(byCreatedAtDesc);
                return merged;
              });
            },
            (e) => {
              console.error(e);
              setLoadErr((e as Error).message ?? String(e));
            }
          )
        );
      }

      unsubs.push(
        onSnapshot(
          txQ,
          (snap) => {
            setLoadErr(null);
            const rows: { id: string; data: DocumentData }[] = [];
            snap.forEach((d) => rows.push({ id: d.id, data: d.data() }));
            rows.sort(byCreatedAtDesc);
            setTxDocs(rows);
          },
          (e) => {
            console.error(e);
            setLoadErr((e as Error).message ?? String(e));
            setTxDocs([]);
          }
        )
      );

      // Include transactions voided *within* the selected date range even if the sale/capture was created earlier.
      if (!batchId) {
        const voidedTxQ = query(
          collection(db, "Transactions"),
          where("voided", "==", true),
          where("voidedAt", ">=", tsStart),
          where("voidedAt", "<", tsEnd),
          orderBy("voidedAt", "desc"),
          limit(900)
        );
        unsubs.push(
          onSnapshot(
            voidedTxQ,
            (snap) => {
              setLoadErr(null);
              const voidedRows: { id: string; data: DocumentData }[] = [];
              snap.forEach((d) => voidedRows.push({ id: d.id, data: d.data() }));
              setTxDocs((prev) => {
                if (voidedRows.length === 0) return prev;
                const byId = new Map<string, { id: string; data: DocumentData }>();
                prev.forEach((r) => byId.set(r.id, r));
                voidedRows.forEach((r) => byId.set(r.id, r));
                const merged = Array.from(byId.values());
                merged.sort(byCreatedAtDesc);
                return merged;
              });
            },
            (e) => {
              console.error(e);
              setLoadErr((e as Error).message ?? String(e));
            }
          )
        );
      }

      const cashQ = batchId
        ? query(
            collection(db, "cashLogs"),
            where("batchId", "==", batchId),
            limit(400)
          )
        : query(
            collection(db, "cashLogs"),
            where("createdAt", ">=", tsStart),
            where("createdAt", "<", tsEnd),
            orderBy("createdAt", "desc"),
            limit(400)
          );
      unsubs.push(
        onSnapshot(
          cashQ,
          (snap) => {
            const rows: { id: string; data: DocumentData }[] = [];
            snap.forEach((d) => rows.push({ id: d.id, data: d.data() }));
            rows.sort(byCreatedAtDesc);
            setCashLogDocs(rows);
          },
          () => setCashLogDocs([])
        )
      );
    } catch (e) {
      setLoadErr((e as Error).message ?? String(e));
    }

    return () => unsubs.forEach((u) => u());
  }, [tsStart, tsEnd, batchId]);

  const orderIdsMatchingSearch = useMemo(() => {
    if (!qLower) return null as Set<string> | null;
    const s = new Set<string>();
    orderDocs.forEach(({ id, data }) => {
      const num = String(data.orderNumber ?? "");
      const cust = String(data.customerName ?? "").toLowerCase();
      if (
        id.toLowerCase().includes(qLower) ||
        num.includes(qLower) ||
        cust.includes(qLower)
      ) {
        s.add(id);
      }
    });
    return s;
  }, [orderDocs, qLower]);

  const filteredOrders = useMemo(() => {
    return orderDocs.filter(({ id, data }) => {
      if (batchId && String(data.batchId ?? "") !== batchId) return false;
      if (!qLower) return true;
      if (orderIdsMatchingSearch?.has(id)) return true;
      const num = String(data.orderNumber ?? "");
      const cust = String(data.customerName ?? "").toLowerCase();
      const table = String(data.tableName ?? "").toLowerCase();
      return (
        id.toLowerCase().includes(qLower) ||
        num.includes(qLower) ||
        cust.includes(qLower) ||
        table.includes(qLower)
      );
    });
  }, [orderDocs, batchId, qLower, orderIdsMatchingSearch]);

  const txnCountByOrder = useMemo(() => {
    const m = new Map<string, number>();
    txDocs.forEach(({ data }) => {
      const oid = String(data.orderId ?? "").trim();
      if (!oid) return;
      const t = String(data.type ?? "");
      if (["SALE", "CAPTURE", "REFUND"].includes(t)) {
        m.set(oid, (m.get(oid) ?? 0) + 1);
      }
    });
    return m;
  }, [txDocs]);

  const filteredTx = useMemo(() => {
    return txDocs.filter(({ id, data }) => {
      if (!inFinancialTx(data)) return false;
      if (batchId && String(data.batchId ?? "") !== batchId) return false;
      const type = String(data.type ?? "");
      if (paymentFilter !== "all" && tab === "transactions") {
        if (type === "REFUND") return false;
        const pt = primaryPaymentType(data);
        if (paymentFilter === "cash" && !pt.toLowerCase().includes("cash")) return false;
        if (paymentFilter === "credit" && !pt.toLowerCase().includes("credit")) return false;
        if (paymentFilter === "debit" && !pt.toLowerCase().includes("debit")) return false;
      }
      if (!qLower) return true;
      const oid = String(data.orderId ?? "").toLowerCase();
      const onum = String(data.orderNumber ?? "");
      if (id.toLowerCase().includes(qLower) || oid.includes(qLower) || onum.includes(qLower))
        return true;
      if (oid && orderIdsMatchingSearch?.has(String(data.orderId))) return true;
      return paymentsLast4s(data).some((l4) => l4.includes(qLower));
    });
  }, [txDocs, batchId, qLower, paymentFilter, tab, orderIdsMatchingSearch]);

  const summaryTx = useMemo(() => {
    return txDocs.filter(({ data }) => {
      if (!inFinancialTx(data)) return false;
      if (batchId && String(data.batchId ?? "") !== batchId) return false;
      if (!qLower) {
        if (tab === "transactions" && paymentFilter !== "all") {
          const type = String(data.type ?? "");
          if (type === "REFUND") return false;
          const pt = primaryPaymentType(data);
          if (paymentFilter === "cash" && !pt.toLowerCase().includes("cash")) return false;
          if (paymentFilter === "credit" && !pt.toLowerCase().includes("credit")) return false;
          if (paymentFilter === "debit" && !pt.toLowerCase().includes("debit")) return false;
        }
        return true;
      }
      const oid = String(data.orderId ?? "");
      const idMatch =
        String(data.orderNumber ?? "").includes(qLower) ||
        oid.toLowerCase().includes(qLower) ||
        paymentsLast4s(data).some((l4) => l4.includes(qLower));
      const orderMatch = oid && orderIdsMatchingSearch?.has(oid);
      if (!(idMatch || orderMatch)) return false;
      if (tab === "transactions" && paymentFilter !== "all") {
        const type = String(data.type ?? "");
        if (type === "REFUND") return false;
        const pt = primaryPaymentType(data);
        if (paymentFilter === "cash" && !pt.toLowerCase().includes("cash")) return false;
        if (paymentFilter === "credit" && !pt.toLowerCase().includes("credit")) return false;
        if (paymentFilter === "debit" && !pt.toLowerCase().includes("debit")) return false;
      }
      return true;
    });
  }, [txDocs, batchId, qLower, tab, paymentFilter, orderIdsMatchingSearch]);

  const totals = useMemo(() => {
    let totalSales = 0;
    let cash = 0;
    let card = 0;
    let refunds = 0;
    for (const { data } of summaryTx) {
      const type = String(data.type ?? "");
      const voided = data.voided === true;
      if (voided && type !== "REFUND") continue;
      if (type === "SALE" || type === "CAPTURE") {
        const cents = txAmountCents(data, type);
        totalSales += cents;
        const pays = data.payments as Record<string, unknown>[] | undefined;
        if (Array.isArray(pays) && pays.length > 0) {
          let c = 0;
          let k = 0;
          for (const p of pays) {
            if (String(p.status) === "VOIDED") continue;
            const pt = String(p.paymentType ?? "");
            const ac = Number(p.amountInCents ?? 0);
            if (pt.toLowerCase() === "cash") c += ac;
            else if (pt.toLowerCase() === "credit" || pt.toLowerCase() === "debit") k += ac;
            else k += ac;
          }
          if (c === 0 && k === 0) {
            if (primaryPaymentType(data).toLowerCase() === "cash") cash += cents;
            else card += cents;
          } else {
            cash += c;
            card += k;
          }
        } else {
          if (primaryPaymentType(data).toLowerCase() === "cash") cash += cents;
          else card += cents;
        }
      } else if (type === "REFUND") {
        refunds += Math.abs(txAmountCents(data, type));
      }
    }
    return {
      totalSales,
      cash,
      card,
      refunds,
      net: totalSales - refunds,
    };
  }, [summaryTx]);

  const cashRows = useMemo(() => {
    const rows: {
      key: string;
      sort: number;
      type: string;
      amount: number;
      reason: string;
      employee: string;
      time: string;
      batchLine: string;
    }[] = [];

    const batchLabel = (bid: string | undefined) => {
      if (!bid) return "All batches";
      const b = batches.find((x) => x.id === bid);
      return b?.label ?? `Batch ${bid.slice(0, 6)}…`;
    };

    cashLogDocs.forEach(({ id, data }) => {
      if (batchId && String(data.batchId ?? "") !== batchId) return;
      const t = String(data.type ?? "").toUpperCase();
      const ts = data.createdAt?.toDate?.() ?? new Date();
      if (qLower) {
        const blob = `${t} ${data.reason ?? ""} ${data.note ?? ""} ${data.employeeName ?? ""}`.toLowerCase();
        if (!blob.includes(qLower)) return;
      }
      const cents = Number(data.amountInCents ?? 0) || Math.round(Number(data.amount ?? 0) * 100);
      rows.push({
        key: id,
        sort: ts.getTime(),
        type: t,
        amount: cents,
        reason: String(data.reason ?? data.note ?? "—"),
        employee: String(data.employeeName ?? data.userId ?? "—"),
        time: ts.toLocaleString(),
        batchLine: `Batch: ${batchLabel(String(data.batchId ?? ""))}`,
      });
    });

    txDocs.forEach(({ id, data }) => {
      if (data.voided === true) return;
      const t = String(data.type ?? "");
      if (batchId && String(data.batchId ?? "") !== batchId) return;
      const ts = docDate(data);
      if (!ts) return;
      if (!batchId && (ts < start || ts >= endExclusive)) return;

      if (t === "CASH_ADD" || t === "PAID_OUT") {
        if (qLower) {
          const blob = `${t} ${data.note ?? ""} ${data.userId ?? ""}`.toLowerCase();
          if (!blob.includes(qLower)) return;
        }
        const cents =
          Number(data.amountInCents ?? 0) ||
          Math.round(Number(data.amount ?? 0) * 100);
        rows.push({
          key: `tx-${id}`,
          sort: ts.getTime(),
          type: t === "CASH_ADD" ? "IN (drawer)" : "OUT (drawer)",
          amount: t === "PAID_OUT" ? -Math.abs(cents) : Math.abs(cents),
          reason: String(data.note ?? "—"),
          employee: String(data.employeeName ?? data.userId ?? "—"),
          time: ts.toLocaleString(),
          batchLine: `Batch: ${batchLabel(String(data.batchId ?? ""))}`,
        });
        return;
      }

      if (t === "SALE" || t === "CAPTURE") {
        const cashCents = cashTenderCentsForSale(data);
        if (cashCents <= 0) return;
        if (qLower) {
          const blob = `cash sale ${t} ${data.orderNumber ?? ""} ${data.orderId ?? ""} ${data.employeeName ?? ""}`.toLowerCase();
          if (!blob.includes(qLower)) return;
        }
        rows.push({
          key: `tx-cashsale-${id}`,
          sort: ts.getTime(),
          type: "Cash sale",
          amount: cashCents,
          reason: `Order #${String(data.orderNumber ?? "—")}`,
          employee: String(data.employeeName ?? data.userId ?? "—"),
          time: ts.toLocaleString(),
          batchLine: `Batch: ${batchLabel(String(data.batchId ?? ""))}`,
        });
        return;
      }

      if (t === "REFUND") {
        const cashCents = cashTenderCentsForRefund(data);
        if (cashCents <= 0) return;
        if (qLower) {
          const blob = `cash refund ${data.orderNumber ?? ""} ${data.orderId ?? ""} ${data.employeeName ?? ""}`.toLowerCase();
          if (!blob.includes(qLower)) return;
        }
        rows.push({
          key: `tx-cashrefund-${id}`,
          sort: ts.getTime(),
          type: "Cash refund",
          amount: -Math.abs(cashCents),
          reason: `Order #${String(data.orderNumber ?? "—")}`,
          employee: String(data.employeeName ?? data.userId ?? "—"),
          time: ts.toLocaleString(),
          batchLine: `Batch: ${batchLabel(String(data.batchId ?? ""))}`,
        });
      }
    });

    rows.sort((a, b) => b.sort - a.sort);
    return rows;
  }, [cashLogDocs, txDocs, batches, batchId, start, endExclusive, qLower]);

  const saveCashLog = useCallback(async () => {
    const v = parseFloat(cashAmount);
    if (!cashModal || !v || v <= 0 || !db) return;
    setCashSaving(true);
    try {
      await addDoc(collection(db, "cashLogs"), {
        type: cashModal,
        amountInCents: Math.round(v * 100),
        reason: cashReason.trim(),
        employeeName: "Web dashboard",
        createdAt: Timestamp.now(),
        source: "maxipay_web_sales_activity",
        ...(batchId ? { batchId } : {}),
      });
      setCashModal(null);
      setCashAmount("");
      setCashReason("");
    } catch (e) {
      console.error(e);
      alert((e as Error).message ?? "Failed to save");
    } finally {
      setCashSaving(false);
    }
  }, [cashModal, cashAmount, cashReason, batchId]);

  const openReceiptPreview = useCallback(async () => {
    if (!txModal) return;
    const orderId = String(txModal.data.orderId ?? "").trim();
    if (!orderId) return;
    const { receiptKind, saleTransactionId } = receiptKindForTransaction(txModal.data);
    setReceiptLoading(true);
    setReceiptErr(null);
    try {
      // If your v2 functions use a non-default region, set NEXT_PUBLIC_FIREBASE_FUNCTIONS_REGION (e.g. us-central1).
      const region = process.env.NEXT_PUBLIC_FIREBASE_FUNCTIONS_REGION;
      const app = getApp();
      const functions = region ? getFunctions(app, region) : getFunctions(app);
      const call = httpsCallable(functions, "getReceiptEmailPreview");
      const res = await call({
        orderId,
        receiptKind,
        ...(saleTransactionId ? { saleTransactionId } : {}),
      });
      const out = res.data as { success?: boolean; html?: string; error?: string };
      if (!out.success || !out.html) {
        setReceiptErr(out.error ?? "Could not load receipt preview.");
        return;
      }
      setReceiptPreview({ html: out.html, orderId });
    } catch (e) {
      setReceiptErr((e as Error).message ?? String(e));
    } finally {
      setReceiptLoading(false);
    }
  }, [txModal]);

  return (
    <>
      <div className="p-6 space-y-6 max-w-[1600px]">
        {loadErr ? (
          <div className="rounded-xl border border-amber-200 bg-amber-50 text-amber-900 text-sm px-4 py-3">
            Firestore: {loadErr}. If this mentions an index, create the composite index in the
            Firebase console for the query shown in the error.
          </div>
        ) : null}

        <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm space-y-4">
          <p className="text-xs text-slate-500">
            Use <span className="font-medium text-slate-700">date range</span> or{" "}
            <span className="font-medium text-slate-700">a specific batch</span> — not both. Choosing
            a batch loads all orders, transactions, and the cash log (drawer moves, cash sales, and
            cash refunds — same sources as POS Cash Flow).
          </p>
          <div className="flex flex-wrap gap-3 items-end">
            <div>
              <label className="text-xs text-slate-500 block mb-1">Date range</label>
              <select
                value={datePreset}
                onChange={(e) => setDatePreset(e.target.value as DatePreset)}
                disabled={!!batchId}
                title={batchId ? "Clear batch selection to filter by date" : undefined}
                className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <option value="today">Today</option>
                <option value="yesterday">Yesterday</option>
                <option value="7days">Last 7 days</option>
                <option value="custom">Custom</option>
              </select>
            </div>
            {datePreset === "custom" ? (
              <div className="flex gap-2 items-center">
                <input
                  type="date"
                  value={customStart}
                  onChange={(e) => setCustomStart(e.target.value)}
                  disabled={!!batchId}
                  title={batchId ? "Clear batch selection to filter by date" : undefined}
                  className="rounded-xl border border-slate-200 px-3 py-2 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
                />
                <span className="text-slate-400">to</span>
                <input
                  type="date"
                  value={customEnd}
                  onChange={(e) => setCustomEnd(e.target.value)}
                  disabled={!!batchId}
                  title={batchId ? "Clear batch selection to filter by date" : undefined}
                  className="rounded-xl border border-slate-200 px-3 py-2 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
                />
              </div>
            ) : null}
            <div>
              <label className="text-xs text-slate-500 block mb-1">Batch</label>
              <select
                value={batchId ?? ""}
                onChange={(e) => setBatchId(e.target.value || null)}
                className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-sm min-w-[200px]"
              >
                {batches.map((b) => (
                  <option key={b.id ?? "all"} value={b.id ?? ""}>
                    {b.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex-1 min-w-[200px]">
              <label className="text-xs text-slate-500 block mb-1">Search</label>
              <div className="relative">
                <Search
                  size={16}
                  className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
                />
                <input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Order id, customer, last 4…"
                  className="w-full pl-9 pr-3 py-2 rounded-xl border border-slate-200 text-sm"
                />
              </div>
            </div>
          </div>

          {tab === "transactions" ? (
            <div className="flex flex-wrap gap-2">
              <span className="text-xs text-slate-500 self-center mr-1">Payment</span>
              {(
                [
                  ["all", "All"],
                  ["cash", "Cash"],
                  ["credit", "Credit"],
                  ["debit", "Debit"],
                ] as const
              ).map(([k, lab]) => (
                <button
                  key={k}
                  type="button"
                  onClick={() => setPaymentFilter(k)}
                  className={`px-3 py-1.5 rounded-full text-xs font-medium border ${
                    paymentFilter === k
                      ? "bg-violet-600 text-white border-violet-600"
                      : "bg-white text-slate-600 border-slate-200 hover:bg-slate-50"
                  }`}
                >
                  {lab}
                </button>
              ))}
            </div>
          ) : null}
        </div>

        <div className="flex gap-3 overflow-x-auto pb-1">
          {[
            ["Total sales", fmtMoney(totals.totalSales), "slate" as const],
            ["Cash", fmtMoney(totals.cash), "emerald" as const],
            ["Card", fmtMoney(totals.card), "blue" as const],
            ["Refunds", fmtMoney(totals.refunds), "amber" as const],
            ["Net sales", fmtMoney(totals.net), "violet" as const],
          ].map(([label, val, accent]) => (
            <div
              key={String(label)}
              className="shrink-0 rounded-2xl border border-slate-200 bg-white px-4 py-3 min-w-[130px] shadow-sm"
            >
              <p className="text-[11px] font-medium uppercase tracking-wide text-slate-500">
                {label}
              </p>
              <p
                className={`text-lg font-bold tabular-nums mt-1 ${
                  accent === "emerald"
                    ? "text-emerald-700"
                    : accent === "blue"
                      ? "text-blue-700"
                      : accent === "amber"
                        ? "text-amber-700"
                        : accent === "violet"
                          ? "text-violet-700"
                          : "text-slate-900"
                }`}
              >
                {val}
              </p>
            </div>
          ))}
        </div>

        <div className="flex gap-1 border-b border-slate-200">
          {(
            [
              ["orders", "Orders", Layers],
              ["transactions", "Transactions", CreditCard],
              ["cash", "Cash log", Banknote],
            ] as const
          ).map(([id, lab, Icon]) => (
            <button
              key={id}
              type="button"
              onClick={() => setTab(id)}
              className={`flex items-center gap-2 px-4 py-3 text-sm font-medium border-b-2 -mb-px ${
                tab === id
                  ? "border-violet-600 text-violet-700"
                  : "border-transparent text-slate-500 hover:text-slate-700"
              }`}
            >
              <Icon size={16} />
              {lab}
            </button>
          ))}
        </div>

        <div className="relative">
          {tab === "orders" ? (
            <div className="space-y-2">
              {filteredOrders.length === 0 ? (
                <p className="text-slate-500 text-sm py-8 text-center">No orders</p>
              ) : (
                filteredOrders.map(({ id, data }) => {
                  const rec = data as Record<string, unknown>;
                  const status = effectivePosOrderStatus(rec);
                  const pill = statusPill(status);
                  const num = data.orderNumber as number | undefined;
                  const refunded = Number(data.totalRefundedInCents ?? 0) > 0;
                  const multi = (txnCountByOrder.get(id) ?? 0) >= 2;
                  const table = String(data.tableName ?? "").trim();
                  const cust = String(data.customerName ?? "").trim();
                  const emp = String(data.employeeName ?? "").trim();
                  const online = isWebOnlineOrder(rec);
                  const sub = online
                    ? [table].filter(Boolean).join(" · ") || "Online order"
                    : (() => {
                        const parts: string[] = [];
                        if (emp) parts.push(emp);
                        if (table) parts.push(table);
                        if (cust && cust !== emp) parts.push(cust);
                        return parts.join(" · ") || "—";
                      })();
                  const typeBadge = orderTypeBadgeStyle(String(data.orderType ?? ""));
                  const total = Number(data.totalInCents ?? 0);
                  const ts = data.createdAt?.toDate?.() ?? new Date();
                  return (
                    <Link
                      key={id}
                      href={`/dashboard/orders/${id}?from=sales-activity`}
                      className="block rounded-2xl border border-slate-200 bg-white p-4 shadow-sm hover:border-violet-200 hover:shadow transition-all"
                    >
                      <div className="flex justify-between gap-2">
                        <span className="font-semibold text-slate-900">
                          {num ? `#${num}` : id.slice(0, 8)}
                        </span>
                        <span className="font-semibold">{fmtMoney(total)}</span>
                      </div>
                      <p className="text-sm text-slate-600 mt-1">{sub}</p>
                      <div className="flex flex-wrap gap-2 mt-2 items-center text-xs">
                        <span
                          className="px-2 py-0.5 rounded-full font-semibold text-white"
                          style={{ backgroundColor: typeBadge.backgroundColor }}
                        >
                          {typeBadge.label}
                        </span>
                        <span
                          className={`px-2 py-0.5 rounded-full font-semibold ${pill.className}`}
                        >
                          {pill.label}
                        </span>
                        <span className="text-slate-400">{ts.toLocaleString()}</span>
                        {multi ? (
                          <span className="text-indigo-600 bg-indigo-50 px-2 py-0.5 rounded">
                            Multiple payments
                          </span>
                        ) : null}
                        {refunded ? (
                          <span className="text-red-700 bg-red-50 px-2 py-0.5 rounded">Refund</span>
                        ) : null}
                      </div>
                    </Link>
                  );
                })
              )}
            </div>
          ) : null}

          {tab === "transactions" ? (
            <div className="space-y-2">
              {filteredTx.length === 0 ? (
                <p className="text-slate-500 text-sm py-8 text-center">No transactions</p>
              ) : (
                filteredTx.map(({ id, data }) => {
                  const type = String(data.type ?? "");
                  const voided = data.voided === true;
                  const cents = txAmountCents(data, type);
                  const display =
                    type === "REFUND" ? `-${fmtMoney(Math.abs(cents))}` : fmtMoney(cents);
                  const pt = primaryPaymentType(data);
                  const icon =
                    pt.toLowerCase() === "cash"
                      ? "💵"
                      : type === "REFUND"
                        ? "↩"
                        : "💳";
                  let st = "APPROVED";
                  let stCls = "bg-emerald-100 text-emerald-800";
                  if (voided && type !== "REFUND") {
                    st = "VOIDED";
                    stCls = "bg-red-100 text-red-800";
                  } else if (type === "REFUND") {
                    st = "REFUNDED";
                    stCls = "bg-red-100 text-red-800";
                  } else if (type === "PRE_AUTH") {
                    st = "PENDING";
                    stCls = "bg-orange-100 text-orange-800";
                  }
                  const last4 = paymentsLast4s(data)[0] ?? String(data.last4 ?? "");
                  const ts = docDate(data) ?? new Date();
                  return (
                    <button
                      key={id}
                      type="button"
                      onClick={() => setTxModal({ id, data })}
                      className="w-full text-left rounded-2xl border border-slate-200 bg-white p-4 shadow-sm hover:border-violet-200"
                    >
                      <div className="flex gap-3">
                        <span className="text-2xl">{icon}</span>
                        <div className="flex-1 min-w-0">
                          <div className="flex justify-between gap-2">
                            <span className="font-bold text-slate-900">{display}</span>
                            <span className={`text-xs font-bold px-2 py-0.5 rounded-full ${stCls}`}>
                              {st}
                            </span>
                          </div>
                          <p className="text-sm text-slate-600 mt-1">
                            {last4 ? `•••• ${last4}` : "—"} · {type}
                          </p>
                          <p className="text-xs text-slate-400 mt-1">{ts.toLocaleString()}</p>
                        </div>
                      </div>
                    </button>
                  );
                })
              )}
            </div>
          ) : null}

          {tab === "cash" ? (
            <>
              <div className="space-y-2 pb-20">
                {cashRows.length === 0 ? (
                  <p className="text-slate-500 text-sm py-8 text-center">No cash log entries</p>
                ) : (
                  cashRows.map((r) => (
                    <div
                      key={r.key}
                      className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm"
                    >
                      <div className="flex justify-between gap-2">
                        <span className="font-semibold text-slate-900">{r.type}</span>
                        <span
                          className={`font-semibold tabular-nums shrink-0 ${
                            r.amount < 0 ? "text-rose-600" : "text-emerald-800"
                          }`}
                        >
                          {fmtMoney(r.amount)}
                        </span>
                      </div>
                      <p className="text-sm text-slate-600 mt-1">{r.reason}</p>
                      <div className="flex justify-between text-xs text-slate-400 mt-2">
                        <span>{r.employee}</span>
                        <span>{r.time}</span>
                      </div>
                      <p className="text-xs text-violet-600 mt-1">{r.batchLine}</p>
                    </div>
                  ))
                )}
              </div>
              <div className="fixed bottom-8 right-8 z-30 flex flex-col items-end gap-2">
                {cashPickOpen ? (
                  <div className="rounded-xl border border-slate-200 bg-white shadow-lg p-2 flex flex-col gap-1 min-w-[180px]">
                    {(
                      [
                        ["IN", "Cash in"],
                        ["OUT", "Cash out"],
                        ["START", "Starting cash"],
                        ["DROP", "Cash drop"],
                      ] as const
                    ).map(([t, lab]) => (
                      <button
                        key={t}
                        type="button"
                        className="text-left text-sm px-3 py-2 rounded-lg hover:bg-slate-50"
                        onClick={() => {
                          setCashPickOpen(false);
                          setCashModal(t);
                        }}
                      >
                        {lab}
                      </button>
                    ))}
                  </div>
                ) : null}
                <button
                  type="button"
                  onClick={() => setCashPickOpen((o) => !o)}
                  className="w-14 h-14 rounded-full bg-violet-600 text-white shadow-lg flex items-center justify-center hover:bg-violet-700"
                  title="Add cash entry"
                >
                  <Plus size={24} />
                </button>
              </div>
            </>
          ) : null}
        </div>
      </div>

      {txModal ? (
        <div className="fixed inset-0 z-40 flex items-center justify-center p-4 bg-black/40">
          <div className="bg-white rounded-2xl max-w-md w-full shadow-xl p-6 space-y-4">
            <div className="flex justify-between items-center">
              <h3 className="text-lg font-semibold">Transaction</h3>
              <button
                type="button"
                onClick={() => {
                  setReceiptPreview(null);
                  setReceiptErr(null);
                  setTxModal(null);
                }}
                className="p-2 rounded-lg hover:bg-slate-100"
              >
                <X size={20} />
              </button>
            </div>
            <p className="text-xs text-slate-500 font-mono break-all">{txModal.id}</p>
            <dl className="text-sm space-y-2">
              <div className="flex justify-between">
                <dt className="text-slate-500">Type</dt>
                <dd>{String(txModal.data.type)}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-500">Amount</dt>
                <dd className="font-semibold">
                  {fmtMoney(Math.abs(txAmountCents(txModal.data, String(txModal.data.type))))}
                </dd>
              </div>
            </dl>
            {String(txModal.data.orderId ?? "").trim() ? (
              <div className="space-y-2">
                <Link
                  href={`/dashboard/orders/${String(txModal.data.orderId).trim()}?from=sales-activity`}
                  className="block text-center py-2 rounded-xl bg-violet-600 text-white text-sm font-medium hover:bg-violet-700"
                >
                  View order
                </Link>
                <button
                  type="button"
                  disabled={receiptLoading}
                  onClick={() => void openReceiptPreview()}
                  className="w-full text-center py-2 rounded-xl border border-slate-200 bg-white text-slate-800 text-sm font-medium hover:bg-slate-50 disabled:opacity-60 inline-flex items-center justify-center gap-2"
                >
                  {receiptLoading ? (
                    <>
                      <Loader2 size={16} className="animate-spin shrink-0" />
                      Loading receipt…
                    </>
                  ) : (
                    "View receipt"
                  )}
                </button>
              </div>
            ) : null}
            {receiptErr ? (
              <p className="text-xs text-red-600">{receiptErr}</p>
            ) : null}

            {txModal && canRequestRemoteVoid(txModal.data) ? (
              <div className="rounded-xl border border-amber-100 bg-amber-50/80 px-3 py-3 space-y-2">
                <p className="text-xs font-medium text-amber-900">Remote card void</p>
                <p className="text-[11px] text-amber-800/90 leading-snug">
                  Queues a void for the signed-in POS (Dejavoo / SpinPOS). The store tablet must be online
                  with MaxiPay open; mixed cash + card must still be voided on the POS.
                </p>
                {!user ? (
                  <p className="text-xs text-amber-900">Sign in to request a void.</p>
                ) : (
                  <>
                    <button
                      type="button"
                      disabled={voidSubmitting}
                      onClick={async () => {
                        if (!user || !txModal) return;
                        setVoidSubmitting(true);
                        setVoidSubmitErr(null);
                        try {
                          const ref = await addDoc(collection(db, REMOTE_PAYMENT_COMMANDS), {
                            type: "voidTransaction",
                            transactionId: txModal.id,
                            status: "pending",
                            requestedByUid: user.uid,
                            requestedByEmail: user.email ?? "",
                            voidedByLabel: `Dashboard: ${user.email ?? user.uid}`,
                            requestedAt: serverTimestamp(),
                          });
                          setVoidCmdId(ref.id);
                        } catch (err) {
                          console.error("[SalesActivity] remote void queue", err);
                          setVoidSubmitErr(
                            err instanceof Error ? err.message : "Could not queue void request"
                          );
                        } finally {
                          setVoidSubmitting(false);
                        }
                      }}
                      className="w-full py-2.5 rounded-xl bg-amber-700 text-white text-sm font-semibold hover:bg-amber-800 disabled:opacity-60"
                    >
                      {voidSubmitting ? "Queueing…" : "Request void on POS"}
                    </button>
                    {voidSubmitErr ? (
                      <p className="text-xs text-red-700">{voidSubmitErr}</p>
                    ) : null}
                    {voidCmdStatus ? (
                      <div className="text-xs text-amber-950 space-y-0.5">
                        <p>
                          <span className="font-semibold">Status:</span> {voidCmdStatus}
                        </p>
                        {voidCmdDetail ? (
                          <p className="text-amber-900/90 break-words">{voidCmdDetail}</p>
                        ) : null}
                      </div>
                    ) : null}
                  </>
                )}
              </div>
            ) : null}

            <p className="text-xs text-slate-500">
              Partial and full refunds (and voids that include cash) are done on the POS. Card-only voids
              can also be queued above when the POS is online.
            </p>
          </div>
        </div>
      ) : null}

      {receiptPreview ? (
        <div className="fixed inset-0 z-50 overflow-y-auto bg-black/50 p-4 flex items-start justify-center">
          <div className="bg-white rounded-2xl max-w-lg w-full my-6 sm:my-10 shadow-xl flex flex-col overflow-visible">
            <div className="flex justify-between items-center px-4 py-3 border-b border-slate-200 shrink-0 sticky top-0 bg-white rounded-t-2xl z-10">
              <h3 className="text-lg font-semibold">Receipt</h3>
              <button
                type="button"
                onClick={() => setReceiptPreview(null)}
                className="p-2 rounded-lg hover:bg-slate-100"
                aria-label="Close receipt"
              >
                <X size={20} />
              </button>
            </div>
            <iframe
              ref={receiptIframeRef}
              title="Email receipt preview"
              srcDoc={receiptPreview.html}
              sandbox="allow-same-origin"
              onLoad={attachReceiptIframeSizing}
              className="block w-full border-0 bg-[#f4f4f4] min-h-[120px]"
              style={{ height: "auto" }}
            />
          </div>
        </div>
      ) : null}

      {cashModal ? (
        <div className="fixed inset-0 z-40 flex items-center justify-center p-4 bg-black/40">
          <div className="bg-white rounded-2xl max-w-md w-full shadow-xl p-6 space-y-4">
            <h3 className="text-lg font-semibold">Cash log — {cashModal}</h3>
            <input
              type="number"
              step="0.01"
              min="0"
              placeholder="Amount"
              value={cashAmount}
              onChange={(e) => setCashAmount(e.target.value)}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            />
            <input
              placeholder="Reason"
              value={cashReason}
              onChange={(e) => setCashReason(e.target.value)}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            />
            <div className="flex gap-2 justify-end">
              <button
                type="button"
                onClick={() => setCashModal(null)}
                className="px-4 py-2 rounded-xl border border-slate-200 text-sm"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={cashSaving}
                onClick={saveCashLog}
                className="px-4 py-2 rounded-xl bg-violet-600 text-white text-sm disabled:opacity-50"
              >
                {cashSaving ? "Saving…" : "Save"}
              </button>
            </div>
            <div className="flex flex-wrap gap-2 text-xs">
              {(["IN", "OUT", "START", "DROP"] as const).map((t) => (
                <button
                  key={t}
                  type="button"
                  onClick={() => setCashModal(t)}
                  className="px-2 py-1 rounded border border-slate-200 hover:bg-slate-50"
                >
                  {t}
                </button>
              ))}
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
