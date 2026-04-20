"use client";

import { useEffect, useMemo, useState } from "react";
import { collection, onSnapshot } from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import CustomerDetailModal from "@/components/CustomerDetailModal";
import { Contact, Search } from "lucide-react";

/** Matches POS Customers list display rules. */
interface CustomerRow {
  id: string;
  name: string;
  phone: string;
  email: string;
  visitCount: number;
}

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

export default function CustomersPage() {
  const { user } = useAuth();
  const [customers, setCustomers] = useState<CustomerRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);

  useEffect(() => {
    if (!user) return;

    const unsub = onSnapshot(
      collection(db, "Customers"),
      (snap) => {
        const list: CustomerRow[] = [];
        snap.forEach((d) => {
          const data = d.data() as Record<string, unknown>;
          const fullName = displayNameFromDoc(data);
          const phone = String(data.phone ?? "");
          const email = String(data.email ?? "");
          const visitRaw = data.visitCount;
          let visitCount = 0;
          if (typeof visitRaw === "number" && Number.isFinite(visitRaw)) {
            visitCount = Math.max(0, Math.floor(visitRaw));
          } else if (typeof visitRaw === "string" && visitRaw.trim()) {
            const n = parseInt(visitRaw, 10);
            if (!Number.isNaN(n)) visitCount = Math.max(0, n);
          }

          list.push({
            id: d.id,
            name: fullName,
            phone,
            email,
            visitCount,
          });
        });
        list.sort((a, b) =>
          a.name.localeCompare(b.name, undefined, { sensitivity: "base" })
        );
        setCustomers(list);
        setLoading(false);
      },
      (err) => {
        console.error("[Customers] onSnapshot error:", err);
        setLoading(false);
      }
    );

    return () => unsub();
  }, [user]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return customers;
    return customers.filter((c) => {
      const blob = `${c.name} ${c.phone} ${c.email}`.toLowerCase();
      return blob.includes(q);
    });
  }, [customers, query]);

  return (
    <>
      <Header title="Customers" />
      <div className="p-6 space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <p className="text-slate-500 text-sm">
            {customers.length} customer{customers.length !== 1 ? "s" : ""}{" "}
            registered
            {query.trim() ? ` · ${filtered.length} shown` : ""}
          </p>
          <div className="relative max-w-md w-full sm:w-72">
            <Search
              size={16}
              className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none"
            />
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search name, phone, email…"
              className="w-full pl-9 pr-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 placeholder:text-slate-400 focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none"
            />
          </div>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          </div>
        ) : customers.length === 0 ? (
          <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-12 text-center">
            <div className="w-16 h-16 rounded-full bg-slate-100 flex items-center justify-center mx-auto mb-4">
              <Contact size={28} className="text-slate-400" />
            </div>
            <p className="text-slate-500 text-lg font-medium">No customers yet</p>
            <p className="text-slate-400 text-sm mt-1">
              Customers created on the POS will appear here.
            </p>
          </div>
        ) : (
          <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm text-left">
                <thead>
                  <tr className="bg-slate-50 border-b border-slate-200 text-slate-600">
                    <th className="px-4 py-3 font-semibold whitespace-nowrap">
                      Customer
                    </th>
                    <th className="px-4 py-3 font-semibold whitespace-nowrap min-w-[140px]">
                      Phone
                    </th>
                    <th className="px-4 py-3 font-semibold min-w-[200px]">Email</th>
                    <th className="px-4 py-3 font-semibold text-center w-16">
                      Frequent
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {filtered.map((c) => {
                    const showStar = c.visitCount > 5;
                    const displayName = c.name.trim() || "No name";
                    return (
                      <tr
                        key={c.id}
                        className="hover:bg-violet-50/40 cursor-pointer transition-colors"
                        onClick={() => setSelectedId(c.id)}
                        onKeyDown={(e) => {
                          if (e.key === "Enter" || e.key === " ") {
                            e.preventDefault();
                            setSelectedId(c.id);
                          }
                        }}
                        tabIndex={0}
                        role="button"
                      >
                        <td className="px-4 py-3 font-medium text-slate-900">
                          {displayName}
                        </td>
                        <td className="px-4 py-3 text-slate-600 whitespace-nowrap">
                          {c.phone.trim() ? (
                            <span className="tabular-nums">
                              {formatPhone(c.phone)}
                            </span>
                          ) : (
                            <span className="text-slate-400">—</span>
                          )}
                        </td>
                        <td className="px-4 py-3 text-slate-600 break-all max-w-md">
                          {c.email.trim() ? (
                            c.email.trim()
                          ) : (
                            <span className="text-slate-400">—</span>
                          )}
                        </td>
                        <td className="px-4 py-3 text-center text-lg">
                          {showStar ? (
                            <span className="text-[#F5B700]" aria-hidden>
                              ★
                            </span>
                          ) : (
                            <span className="text-slate-300">—</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <p className="px-4 py-2 text-xs text-slate-500 border-t border-slate-100 bg-slate-50/80">
              Tap a row for order history and reservation history (same as the
              POS customer profile).
            </p>
          </div>
        )}

        {!loading && customers.length > 0 && filtered.length === 0 && (
          <p className="text-center text-slate-500 text-sm py-8">
            No customers match your search.
          </p>
        )}
      </div>

      <CustomerDetailModal
        customerId={selectedId}
        onClose={() => setSelectedId(null)}
      />
    </>
  );
}
