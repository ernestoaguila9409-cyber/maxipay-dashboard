"use client";

import { useEffect, useMemo, useState } from "react";
import { collection, onSnapshot, type Unsubscribe } from "firebase/firestore";

import { db } from "@/firebase/firebaseConfig";
import {
  PRINTER_STATUS_TICK_MS,
  formatLastSeenAgo,
  mapPrinterDocument,
  type PrinterDocFields,
  type PrinterStatus,
} from "@/lib/printerStatusUtils";

export type PrinterFilter = "all" | "online" | "offline" | "unknown";
export type PrinterSort = "name" | "status";

export interface PrinterViewRow extends PrinterDocFields {
  lastSeenAgo: string;
}

interface UsePrintersStatusResult {
  printers: PrinterViewRow[];
  loading: boolean;
  error: string | null;
  filter: PrinterFilter;
  setFilter: (f: PrinterFilter) => void;
  sort: PrinterSort;
  setSort: (s: PrinterSort) => void;
}

const STATUS_ORDER: Record<PrinterStatus, number> = {
  ONLINE: 0,
  UNKNOWN: 1,
  OFFLINE: 2,
};

/**
 * Real-time `Printers` collection + periodic wall-clock tick.
 * Status comes from the POS via the `status` field — NOT computed here.
 */
export function usePrintersStatus(enabled: boolean): UsePrintersStatusResult {
  const [raw, setRaw] = useState<PrinterDocFields[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const [filter, setFilter] = useState<PrinterFilter>("all");
  const [sort, setSort] = useState<PrinterSort>("name");

  useEffect(() => {
    if (!enabled) {
      setRaw([]);
      setError(null);
      setLoading(false);
      return;
    }

    setLoading(true);
    const col = collection(db, "Printers");
    let unsub: Unsubscribe | undefined;

    try {
      unsub = onSnapshot(
        col,
        (snap) => {
          setError(null);
          const next: PrinterDocFields[] = [];
          snap.forEach((docSnap) => {
            next.push(
              mapPrinterDocument(docSnap.id, docSnap.data() as Record<string, unknown>)
            );
          });
          setRaw(next);
          setLoading(false);
        },
        (err) => {
          console.error("[Printers] onSnapshot error:", err);
          setError(err.message || "Failed to load printers");
          setLoading(false);
        }
      );
    } catch (e) {
      console.error("[Printers] listener setup:", e);
      setError(e instanceof Error ? e.message : "Failed to subscribe");
      setLoading(false);
    }

    return () => {
      unsub?.();
    };
  }, [enabled]);

  useEffect(() => {
    const id = window.setInterval(() => {
      setTick((t) => t + 1);
    }, PRINTER_STATUS_TICK_MS);
    return () => window.clearInterval(id);
  }, []);

  const nowMs = useMemo(() => Date.now(), [tick, raw]);

  const printers = useMemo((): PrinterViewRow[] => {
    const withView: PrinterViewRow[] = raw.map((p) => ({
      ...p,
      lastSeenAgo: formatLastSeenAgo(p.lastSeenMs, nowMs),
    }));

    let filtered = withView;
    if (filter === "online") filtered = withView.filter((p) => p.status === "ONLINE");
    if (filter === "offline") filtered = withView.filter((p) => p.status === "OFFLINE");
    if (filter === "unknown") filtered = withView.filter((p) => p.status === "UNKNOWN");

    const sorted = [...filtered];
    if (sort === "name") {
      sorted.sort((a, b) =>
        a.name.localeCompare(b.name, undefined, { sensitivity: "base" })
      );
    } else {
      sorted.sort((a, b) => {
        const diff = STATUS_ORDER[a.status] - STATUS_ORDER[b.status];
        if (diff !== 0) return diff;
        return a.name.localeCompare(b.name, undefined, { sensitivity: "base" });
      });
    }

    return sorted;
  }, [raw, nowMs, filter, sort]);

  return {
    printers,
    loading,
    error,
    filter,
    setFilter,
    sort,
    setSort,
  };
}
