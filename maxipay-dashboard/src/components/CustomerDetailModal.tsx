"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import {
  collection,
  doc,
  getDoc,
  getDocs,
  orderBy,
  query,
  where,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { orderTypeBadgeStyle, parseCreatedAt } from "@/lib/orderDisplayUtils";
import {
  partitionReservationRows,
  type CustomerReservationRow,
} from "@/lib/customerReservationUtils";
import { X, Loader2, ExternalLink } from "lucide-react";

const RESERVATIONS_COLLECTION = "Reservations";

function displayNameFromDoc(data: Record<string, unknown>): string {
  const firstName = String(data.firstName ?? "").trim();
  const lastName = String(data.lastName ?? "").trim();
  const name = String(data.name ?? "").trim();
  if (firstName || lastName) return `${firstName} ${lastName}`.trim();
  return name;
}

function formatPhone(phone: string): string {
  const digits = phone.replace(/\D/g, "");
  if (digits.length === 10) {
    return `${digits.slice(0, 3)}-${digits.slice(3, 6)}-${digits.slice(6)}`;
  }
  if (digits.length === 11 && digits.startsWith("1")) {
    return `${digits.slice(1, 4)}-${digits.slice(4, 7)}-${digits.slice(7)}`;
  }
  return phone;
}

function centsToDisplay(cents: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
  }).format(cents / 100);
}

function formatOrderHistoryDate(d: Date): string {
  return new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "numeric",
  }).format(d);
}

interface OrderHistoryRow {
  orderId: string;
  orderNumber: number;
  orderType: string;
  totalInCents: number;
  dateStr: string;
  status: string;
}

function reservationStatusStyle(status: string): { bg: string; fg: string } {
  const u = status.trim().toUpperCase();
  if (u === "ACTIVE") return { bg: "#E8F5E9", fg: "#2E7D32" };
  if (u === "CANCELLED") return { bg: "#FFEBEE", fg: "#C62828" };
  return { bg: "#ECEFF1", fg: "#546E7A" };
}

function ReservationCard({ row }: { row: CustomerReservationRow }) {
  const { bg, fg } = reservationStatusStyle(row.displayStatus);
  return (
    <div
      className={`rounded-xl border p-4 ${
        row.emphasize ? "border-violet-400 ring-2 ring-violet-200" : "border-slate-200"
      } bg-white`}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="font-semibold text-slate-800">{row.tableNames}</p>
          {row.partySize > 0 ? (
            <p className="text-sm text-slate-500 mt-0.5">
              Party of {row.partySize}
            </p>
          ) : null}
          <p className="text-sm text-slate-600 mt-1">{row.dateDisplay}</p>
        </div>
        <span
          className="text-xs font-semibold px-2.5 py-1 rounded-full shrink-0"
          style={{ backgroundColor: bg, color: fg }}
        >
          {row.displayStatus}
        </span>
      </div>
    </div>
  );
}

export interface CustomerDetailModalProps {
  customerId: string | null;
  onClose: () => void;
}

export default function CustomerDetailModal({
  customerId,
  onClose,
}: CustomerDetailModalProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [profileName, setProfileName] = useState("");
  const [profilePhone, setProfilePhone] = useState("");
  const [profileEmail, setProfileEmail] = useState("");
  const [lifetimeSpendCents, setLifetimeSpendCents] = useState(0);
  const [visits, setVisits] = useState(0);
  const [lastVisitStr, setLastVisitStr] = useState("—");
  const [orders, setOrders] = useState<OrderHistoryRow[]>([]);
  const [upcomingRes, setUpcomingRes] = useState<CustomerReservationRow[]>([]);
  const [pastRes, setPastRes] = useState<CustomerReservationRow[]>([]);
  const [reservationsEmpty, setReservationsEmpty] = useState(false);

  const load = useCallback(async (cid: string) => {
    setLoading(true);
    setError(null);
    setReservationsEmpty(false);
    try {
      const custSnap = await getDoc(doc(db, "Customers", cid));
      if (custSnap.exists()) {
        const d = custSnap.data() as Record<string, unknown>;
        const full = displayNameFromDoc(d);
        setProfileName(full.trim() || "Customer");
        const ph = String(d.phone ?? "").trim();
        const em = String(d.email ?? "").trim();
        setProfilePhone(ph ? formatPhone(ph) : "");
        setProfileEmail(em);
      } else {
        setProfileName("Customer");
        setProfilePhone("");
        setProfileEmail("");
      }

      const ordersQ = query(
        collection(db, "Orders"),
        where("customerId", "==", cid),
        orderBy("createdAt", "desc")
      );
      const ordersSnap = await getDocs(ordersQ);
      const orderRows: OrderHistoryRow[] = [];
      let spend = 0;
      let latest: Date | null = null;

      ordersSnap.forEach((od) => {
        const data = od.data() as Record<string, unknown>;
        const orderNumber = Number(data.orderNumber ?? 0);
        const orderType = String(data.orderType ?? "");
        const totalInCents = Math.round(
          typeof data.totalInCents === "number"
            ? data.totalInCents
            : Number(data.totalInCents ?? 0)
        );
        const status = String(data.status ?? "");
        const created = parseCreatedAt(data);

        if (status !== "VOIDED") {
          spend += totalInCents;
        }
        if (created && (!latest || created > latest)) {
          latest = created;
        }

        const dateStr = created ? formatOrderHistoryDate(created) : "";
        orderRows.push({
          orderId: od.id,
          orderNumber,
          orderType,
          totalInCents,
          dateStr,
          status,
        });
      });

      setOrders(orderRows);
      setVisits(orderRows.length);
      setLifetimeSpendCents(spend);
      setLastVisitStr(latest ? formatOrderHistoryDate(latest) : "—");

      const resQ = query(
        collection(db, RESERVATIONS_COLLECTION),
        where("customerId", "==", cid)
      );
      const resSnap = await getDocs(resQ);
      const docs = resSnap.docs.map((d) => ({ id: d.id, data: () => d.data() as Record<string, unknown> }));
      if (docs.length === 0) {
        setReservationsEmpty(true);
        setUpcomingRes([]);
        setPastRes([]);
      } else {
        setReservationsEmpty(false);
        const { upcoming, past } = partitionReservationRows(docs, Date.now());
        setUpcomingRes(upcoming);
        setPastRes(past);
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : "Failed to load";
      setError(msg);
      console.error("[CustomerDetailModal]", e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!customerId) return;
    void load(customerId);
  }, [customerId, load]);

  if (!customerId) return null;

  return (
    <div
      className="fixed inset-0 z-[100] flex items-end sm:items-center justify-center bg-black/45 p-0 sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="customer-detail-title"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        className="bg-white w-full sm:max-w-2xl sm:rounded-2xl shadow-xl max-h-[92vh] sm:max-h-[88vh] flex flex-col overflow-hidden rounded-t-2xl sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100 shrink-0">
          <h2
            id="customer-detail-title"
            className="text-lg font-semibold text-slate-800 truncate pr-2"
          >
            {profileName || "Customer"}
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="p-2 rounded-xl hover:bg-slate-100 text-slate-500"
            aria-label="Close"
          >
            <X size={22} />
          </button>
        </div>

        <div className="overflow-y-auto flex-1 px-5 py-4 space-y-6">
          {loading ? (
            <div className="flex justify-center py-16">
              <Loader2 className="w-8 h-8 text-violet-600 animate-spin" />
            </div>
          ) : error ? (
            <p className="text-red-600 text-sm text-center py-8">{error}</p>
          ) : (
            <>
              <div className="space-y-2 text-sm">
                {profilePhone ? (
                  <p className="text-slate-600">
                    <span className="text-slate-400">Phone</span>{" "}
                    <span className="text-slate-800">{profilePhone}</span>
                  </p>
                ) : null}
                {profileEmail ? (
                  <p className="text-slate-600 break-all">
                    <span className="text-slate-400">Email</span>{" "}
                    <span className="text-slate-800">{profileEmail}</span>
                  </p>
                ) : null}
              </div>

              <div className="grid grid-cols-3 gap-3">
                <div className="rounded-xl bg-slate-50 border border-slate-100 p-3 text-center">
                  <p className="text-xs text-slate-500 uppercase tracking-wide">
                    Lifetime spend
                  </p>
                  <p className="text-sm font-semibold text-slate-800 mt-1">
                    {centsToDisplay(lifetimeSpendCents)}
                  </p>
                </div>
                <div className="rounded-xl bg-slate-50 border border-slate-100 p-3 text-center">
                  <p className="text-xs text-slate-500 uppercase tracking-wide">
                    Visits
                  </p>
                  <p className="text-sm font-semibold text-slate-800 mt-1">
                    {visits}
                  </p>
                </div>
                <div className="rounded-xl bg-slate-50 border border-slate-100 p-3 text-center">
                  <p className="text-xs text-slate-500 uppercase tracking-wide">
                    Last visit
                  </p>
                  <p className="text-sm font-semibold text-slate-800 mt-1">
                    {lastVisitStr}
                  </p>
                </div>
              </div>

              <section>
                <h3 className="text-sm font-bold text-slate-700 uppercase tracking-wide mb-3">
                  Order history
                </h3>
                {orders.length === 0 ? (
                  <p className="text-slate-500 text-sm py-4">No orders yet</p>
                ) : (
                  <div className="rounded-xl border border-slate-200 overflow-hidden divide-y divide-slate-100">
                    {orders.map((o) => {
                      const badge = orderTypeBadgeStyle(o.orderType);
                      return (
                        <div
                          key={o.orderId}
                          className="flex flex-wrap items-center gap-3 px-4 py-3 bg-white hover:bg-slate-50/80"
                        >
                          <Link
                            href={`/dashboard/orders/${o.orderId}`}
                            className="font-semibold text-violet-700 hover:underline flex items-center gap-1"
                          >
                            #{o.orderNumber}
                            <ExternalLink size={12} className="opacity-60" />
                          </Link>
                          <span
                            className="text-xs font-bold px-2 py-0.5 rounded text-white shrink-0"
                            style={{ backgroundColor: badge.backgroundColor }}
                          >
                            {badge.label}
                          </span>
                          <span className="text-sm text-slate-600 ml-auto">
                            {o.dateStr}
                          </span>
                          <span className="text-sm font-semibold text-slate-800 w-full sm:w-auto sm:ml-0 text-right sm:text-left">
                            {centsToDisplay(o.totalInCents)}
                          </span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </section>

              <section>
                <h3 className="text-sm font-bold text-slate-700 uppercase tracking-wide mb-3">
                  Reservation history
                </h3>
                {reservationsEmpty ? (
                  <p className="text-slate-500 text-sm py-2">
                    No reservations for this customer
                  </p>
                ) : (
                  <div className="space-y-5">
                    <div>
                      <h4 className="text-xs font-semibold text-slate-500 uppercase mb-2">
                        Upcoming
                      </h4>
                      {upcomingRes.length === 0 ? (
                        <p className="text-slate-400 text-sm">None</p>
                      ) : (
                        <div className="space-y-2">
                          {upcomingRes.map((r) => (
                            <ReservationCard key={r.reservationId} row={r} />
                          ))}
                        </div>
                      )}
                    </div>
                    <div>
                      <h4 className="text-xs font-semibold text-slate-500 uppercase mb-2">
                        Past
                      </h4>
                      {pastRes.length === 0 ? (
                        <p className="text-slate-400 text-sm">None</p>
                      ) : (
                        <div className="space-y-2">
                          {pastRes.map((r) => (
                            <ReservationCard key={r.reservationId} row={r} />
                          ))}
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </section>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
