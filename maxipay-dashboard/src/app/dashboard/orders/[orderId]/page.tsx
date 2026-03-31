"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import {
  doc,
  getDoc,
  collection,
  getDocs,
  type Timestamp,
} from "firebase/firestore";
import { ArrowLeft, Loader2 } from "lucide-react";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import { firestoreDate, formatOrderTypeLabel } from "@/lib/orderDisplayUtils";

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
                  <dd className="font-medium text-slate-800">
                    {formatOrderTypeLabel(orderTypeRaw)}
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
              </dl>
              <div className="pt-4 border-t border-slate-100 flex flex-wrap gap-6 text-sm">
                <div>
                  <span className="text-slate-500">Total </span>
                  <span className="font-semibold text-slate-900">
                    ${centsToMoney(totalInCents)}
                  </span>
                </div>
                <div>
                  <span className="text-slate-500">Paid </span>
                  <span className="font-medium text-slate-800">
                    ${centsToMoney(totalPaidInCents)}
                  </span>
                </div>
                {totalRefundedInCents > 0 && (
                  <div>
                    <span className="text-slate-500">Refunded </span>
                    <span className="font-medium text-slate-800">
                      ${centsToMoney(totalRefundedInCents)}
                    </span>
                  </div>
                )}
              </div>
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
