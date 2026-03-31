"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import {
  doc,
  getDoc,
  collection,
  getDocs,
  query,
  where,
  type Timestamp,
} from "firebase/firestore";
import { ArrowLeft, Loader2 } from "lucide-react";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
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

export default function OrderDetailPage() {
  const params = useParams();
  const router = useRouter();
  const orderId = typeof params.orderId === "string" ? params.orderId : "";
  const { user } = useAuth();

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
          return;
        }
        const data = snap.data() as Record<string, unknown>;
        setOrderData(data);

        const saleId = String(
          data.saleTransactionId ?? data.transactionId ?? ""
        ).trim();
        setSaleTransactionData(null);
        setRefundActivity([]);

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
              if (!cancelled) setRefundActivity(rows);
            } catch (err) {
              console.error(
                "[Order detail] Refund transactions query failed (index may be required):",
                err
              );
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
            name: String(x.name ?? "Item"),
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
          onClick={() => router.push("/dashboard/orders")}
          className="inline-flex items-center gap-2 text-sm text-slate-600 hover:text-slate-900"
        >
          <ArrowLeft size={18} />
          Back to orders
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
                  {lines.map((line) => (
                    <li key={line.id} className="px-6 py-4">
                      <div className="flex justify-between gap-4">
                        <div>
                          <p className="font-medium text-slate-800">
                            {line.name}{" "}
                            <span className="text-slate-500 font-normal">
                              ×{line.quantity}
                            </span>
                          </p>
                          {line.modifiers.length > 0 && (
                            <ul className="mt-1 text-xs text-slate-600 space-y-0.5">
                              {line.modifiers.map((m, i) => (
                                <li key={i}>
                                  {m.action === "ADD" ? "+" : m.action === "NO" ? "−" : ""}{" "}
                                  {m.name}
                                  {m.price != null && m.price !== 0
                                    ? ` ($${Number(m.price).toFixed(2)})`
                                    : ""}
                                </li>
                              ))}
                            </ul>
                          )}
                        </div>
                        <div className="text-right shrink-0">
                          <p className="text-sm font-semibold text-slate-800">
                            ${centsToMoney(line.lineTotalInCents)}
                          </p>
                          <p className="text-xs text-slate-400">
                            @ ${centsToMoney(line.unitPriceInCents)} each
                          </p>
                        </div>
                      </div>
                    </li>
                  ))}
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
                    <div className="flex justify-between gap-4">
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
