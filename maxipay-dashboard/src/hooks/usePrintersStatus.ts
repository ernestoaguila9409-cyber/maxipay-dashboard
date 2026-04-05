"use client";

import { useEffect, useMemo, useState } from "react";
import { collection, onSnapshot, type Unsubscribe } from "firebase/firestore";

import { db } from "@/firebase/firebaseConfig";
import {
  PRINTER_STATUS_TICK_MS,
  formatLastSeenAgo,
  isOnlineFromLastSeen,
  mapPrinterDocument,
  type PrinterDocFields,
} from "@/lib/printerStatusUtils";

export type PrinterFilter = "all" | "online" | "offline";
export type PrinterSort = "name" | "status";

export interface PrinterViewRow extends PrinterDocFields {
  isOnline: boolean;
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

/**
 * Real-time `Printers` collection + periodic wall-clock tick (does not probe IPs).
 * @param enabled Set false when unauthenticated to avoid subscribing.
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
    const withStatus: PrinterViewRow[] = raw.map((p) => ({
      ...p,
      isOnline: isOnlineFromLastSeen(p.lastSeenMs, nowMs),
      lastSeenAgo: formatLastSeenAgo(p.lastSeenMs, nowMs),
    }));

    let filtered = withStatus;
    if (filter === "online") filtered = withStatus.filter((p) => p.isOnline);
    if (filter === "offline") filtered = withStatus.filter((p) => !p.isOnline);

    const sorted = [...filtered];
    if (sort === "name") {
      sorted.sort((a, b) =>
        a.name.localeCompare(b.name, undefined, { sensitivity: "base" })
      );
    } else {
      sorted.sort((a, b) => {
        if (a.isOnline !== b.isOnline) return a.isOnline ? -1 : 1;
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
