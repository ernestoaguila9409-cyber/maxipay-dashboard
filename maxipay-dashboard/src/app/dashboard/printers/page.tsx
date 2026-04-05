"use client";

import { memo } from "react";
import Header from "@/components/Header";
import { useAuth } from "@/context/AuthContext";
import {
  usePrintersStatus,
  type PrinterSort,
  type PrinterViewRow,
} from "@/hooks/usePrintersStatus";
import {
  PRINTER_ONLINE_THRESHOLD_MS,
  PRINTER_STATUS_TICK_MS,
} from "@/lib/printerStatusUtils";

const PrinterTableRow = memo(function PrinterTableRow({ row }: { row: PrinterViewRow }) {
  return (
    <tr className="border-b border-slate-100 hover:bg-slate-50/80 transition-colors">
      <td className="px-4 py-3.5 text-sm font-medium text-slate-900">{row.name}</td>
      <td className="px-4 py-3.5 text-sm font-mono text-slate-600 tabular-nums">
        {row.ip}
      </td>
      <td className="px-4 py-3.5 text-sm text-slate-600">{row.typeLabel}</td>
      <td className="px-4 py-3.5 font-mono text-xs text-slate-500">
        {row.assignedDeviceId ?? "—"}
      </td>
      <td className="px-4 py-3.5 text-sm text-slate-500">{row.lastSeenAgo}</td>
      <td className="px-4 py-3.5">
        <span
          className={`inline-flex items-center gap-2 text-xs font-semibold px-2.5 py-1 rounded-full ${
            row.isOnline
              ? "bg-emerald-50 text-emerald-800"
              : "bg-red-50 text-red-800"
          }`}
        >
          <span
            className={`w-2 h-2 rounded-full shrink-0 ${
              row.isOnline ? "bg-emerald-500" : "bg-red-500"
            }`}
            aria-hidden
          />
          {row.isOnline ? "Online" : "Offline"}
        </span>
      </td>
    </tr>
  );
});

function PrinterCard({ row }: { row: PrinterViewRow }) {
  return (
    <div className="rounded-xl border border-slate-100 bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between gap-2">
        <h3 className="text-sm font-semibold text-slate-900">{row.name}</h3>
        <span
          className={`inline-flex items-center gap-1.5 text-xs font-semibold px-2 py-0.5 rounded-full shrink-0 ${
            row.isOnline
              ? "bg-emerald-50 text-emerald-800"
              : "bg-red-50 text-red-800"
          }`}
        >
          <span
            className={`w-1.5 h-1.5 rounded-full ${
              row.isOnline ? "bg-emerald-500" : "bg-red-500"
            }`}
          />
          {row.isOnline ? "Online" : "Offline"}
        </span>
      </div>
      <dl className="mt-3 space-y-2 text-sm">
        <div className="flex justify-between gap-2">
          <dt className="text-slate-500">IP</dt>
          <dd className="font-mono text-slate-800 tabular-nums">{row.ip}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-slate-500">Type</dt>
          <dd className="text-slate-800">{row.typeLabel}</dd>
        </div>
        {row.assignedDeviceId ? (
          <div className="flex justify-between gap-2">
            <dt className="text-slate-500">Device</dt>
            <dd className="font-mono text-xs text-slate-700 break-all text-right">
              {row.assignedDeviceId}
            </dd>
          </div>
        ) : null}
        <div className="flex justify-between gap-2">
          <dt className="text-slate-500">Last seen</dt>
          <dd className="text-slate-700">{row.lastSeenAgo}</dd>
        </div>
      </dl>
    </div>
  );
}

export default function PrintersPage() {
  const { user } = useAuth();
  const { printers, loading, error, filter, setFilter, sort, setSort } =
    usePrintersStatus(!!user);

  if (!user) {
    return (
      <>
        <Header title="Printers" />
        <div className="p-6 text-slate-500">Sign in to view printers.</div>
      </>
    );
  }

  return (
    <>
      <Header title="Printers" />
      <div className="p-4 sm:p-6 max-w-6xl mx-auto space-y-6">
        <div>
          <p className="text-sm text-slate-600 max-w-2xl">
            Status is computed from <code className="text-xs bg-slate-100 px-1 rounded">lastSeen</code>{" "}
            in Firestore (updated by the POS). This page does{" "}
            <strong className="font-medium text-slate-800">not</strong> connect to printer IPs.
            Online = last seen within {PRINTER_ONLINE_THRESHOLD_MS / 1000}s. Refreshes every{" "}
            {PRINTER_STATUS_TICK_MS / 1000}s.
          </p>
        </div>

        {error ? (
          <div
            className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800"
            role="alert"
          >
            {error}
          </div>
        ) : null}

        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs font-medium text-slate-500 uppercase tracking-wide">
              Filter
            </span>
            {(
              [
                ["all", "All"],
                ["online", "Online"],
                ["offline", "Offline"],
              ] as const
            ).map(([value, label]) => (
              <button
                key={value}
                type="button"
                onClick={() => setFilter(value)}
                className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                  filter === value
                    ? "bg-blue-600 text-white"
                    : "bg-slate-100 text-slate-600 hover:bg-slate-200"
                }`}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs font-medium text-slate-500 uppercase tracking-wide">
              Sort
            </span>
            <select
              value={sort}
              onChange={(e) => setSort(e.target.value as PrinterSort)}
              className="text-sm border border-slate-200 rounded-lg px-3 py-2 bg-white text-slate-700 shadow-sm focus:outline-none focus:ring-2 focus:ring-blue-500/30"
              aria-label="Sort printers"
            >
              <option value="name">Name</option>
              <option value="status">Status (online first)</option>
            </select>
          </div>
        </div>

        {loading ? (
          <div className="flex items-center justify-center gap-3 py-16 text-slate-400">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
            Loading printers…
          </div>
        ) : printers.length === 0 ? (
          <div className="rounded-2xl border border-slate-100 bg-white p-10 text-center text-slate-500">
            {error
              ? "Could not load printers."
              : filter === "all"
                ? "No printers in Firestore yet. Add printers from the POS."
                : `No ${filter} printers.`}
          </div>
        ) : (
          <>
            <div className="hidden md:block bg-white rounded-2xl border border-slate-100 shadow-sm overflow-hidden">
              <div className="overflow-x-auto">
                <table className="w-full text-left">
                  <thead>
                    <tr className="border-b border-slate-100 bg-slate-50/80">
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                        Name
                      </th>
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                        IP address
                      </th>
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                        Type
                      </th>
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                        Assigned device
                      </th>
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                        Last seen
                      </th>
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">
                        Status
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {printers.map((row) => (
                      <PrinterTableRow key={row.id} row={row} />
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 md:hidden">
              {printers.map((row) => (
                <PrinterCard key={row.id} row={row} />
              ))}
            </div>
          </>
        )}
      </div>
    </>
  );
}
