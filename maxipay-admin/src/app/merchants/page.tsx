"use client";

import { useEffect, useState, useMemo } from "react";
import { collection, onSnapshot, Timestamp } from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import Link from "next/link";
import { Plus, Search, Store, ChevronRight } from "lucide-react";

interface Merchant {
  id: string;
  merchantNumber: string;
  businessName: string;
  ownerFirstName: string;
  ownerLastName: string;
  email: string;
  phone: string;
  status: "active" | "suspended" | "pending";
  createdAt: Date | null;
}

function parseTimestamp(val: unknown): Date | null {
  if (val instanceof Timestamp) return val.toDate();
  if (val && typeof val === "object" && "seconds" in val) {
    return new Date((val as { seconds: number }).seconds * 1000);
  }
  return null;
}

function parseMerchant(id: string, data: Record<string, unknown>): Merchant {
  return {
    id,
    merchantNumber: (data.merchantNumber as string) || "",
    businessName: (data.businessName as string) || "",
    ownerFirstName: (data.ownerFirstName as string) || "",
    ownerLastName: (data.ownerLastName as string) || "",
    email: (data.email as string) || "",
    phone: (data.phone as string) || "",
    status: (data.status as Merchant["status"]) || "pending",
    createdAt: parseTimestamp(data.createdAt),
  };
}

const statusConfig = {
  active: { label: "Active", className: "bg-green-100 text-green-700" },
  suspended: { label: "Suspended", className: "bg-red-100 text-red-700" },
  pending: { label: "Pending", className: "bg-amber-100 text-amber-700" },
};

export default function MerchantsListPage() {
  const [merchants, setMerchants] = useState<Merchant[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");

  useEffect(() => {
    const unsub = onSnapshot(collection(db, "Merchants"), (snap) => {
      const rows = snap.docs
        .map((d) => parseMerchant(d.id, d.data() as Record<string, unknown>))
        .sort((a, b) => {
          const ta = a.createdAt?.getTime() ?? 0;
          const tb = b.createdAt?.getTime() ?? 0;
          return tb - ta;
        });
      setMerchants(rows);
      setLoading(false);
    });
    return () => unsub();
  }, []);

  const filtered = useMemo(() => {
    if (!search.trim()) return merchants;
    const q = search.toLowerCase();
    return merchants.filter(
      (m) =>
        m.merchantNumber.toLowerCase().includes(q) ||
        m.businessName.toLowerCase().includes(q) ||
        m.email.toLowerCase().includes(q) ||
        `${m.ownerFirstName} ${m.ownerLastName}`.toLowerCase().includes(q)
    );
  }, [merchants, search]);

  return (
    <div className="p-8">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">Merchants</h1>
          <p className="text-slate-500 mt-1">
            {merchants.length} merchant{merchants.length !== 1 ? "s" : ""} registered
          </p>
        </div>
        <Link
          href="/merchants/new"
          className="inline-flex items-center gap-2 px-5 py-2.5 bg-blue-600 text-white text-sm font-medium rounded-xl hover:bg-blue-700 transition-colors"
        >
          <Plus size={18} />
          Create Merchant
        </Link>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden">
        <div className="p-4 border-b border-slate-100">
          <div className="relative">
            <Search size={18} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              placeholder="Search by merchant #, name, email, or owner..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full pl-10 pr-4 py-2.5 text-sm border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          </div>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-16">
            <div className="w-8 h-8 border-3 border-blue-600 border-t-transparent rounded-full animate-spin" />
          </div>
        ) : filtered.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-slate-400">
            <Store size={40} className="mb-3 opacity-50" />
            <p className="text-sm font-medium">
              {search ? "No merchants match your search." : "No merchants yet."}
            </p>
            {!search && (
              <Link href="/merchants/new" className="mt-3 text-sm text-blue-600 hover:underline">
                Create your first merchant
              </Link>
            )}
          </div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-100">
                <th className="text-left px-6 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">Merchant #</th>
                <th className="text-left px-6 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">Business</th>
                <th className="text-left px-6 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">Owner</th>
                <th className="text-left px-6 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">Email</th>
                <th className="text-left px-6 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">Status</th>
                <th className="text-left px-6 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">Created</th>
                <th className="w-10" />
              </tr>
            </thead>
            <tbody>
              {filtered.map((m) => {
                const cfg = statusConfig[m.status] || statusConfig.pending;
                return (
                  <tr
                    key={m.id}
                    className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors cursor-pointer"
                    onClick={() => (window.location.href = `/merchants/${m.id}`)}
                  >
                    <td className="px-6 py-4 text-sm font-mono text-slate-700">
                      {m.merchantNumber || "—"}
                    </td>
                    <td className="px-6 py-4">
                      <span className="text-sm font-medium text-slate-900">{m.businessName}</span>
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-600">
                      {m.ownerFirstName} {m.ownerLastName}
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-600">{m.email}</td>
                    <td className="px-6 py-4">
                      <span className={`inline-block px-2.5 py-1 text-xs font-semibold rounded-full ${cfg.className}`}>
                        {cfg.label}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-500">
                      {m.createdAt
                        ? m.createdAt.toLocaleDateString("en-US", {
                            month: "short",
                            day: "numeric",
                            year: "numeric",
                          })
                        : "—"}
                    </td>
                    <td className="px-4 py-4">
                      <ChevronRight size={18} className="text-slate-400" />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
