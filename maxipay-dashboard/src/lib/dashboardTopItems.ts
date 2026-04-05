import {
  collection,
  getDocs,
  type DocumentData,
  type QuerySnapshot,
} from "firebase/firestore";

import { db } from "@/firebase/firebaseConfig";
import { parseCreatedAt } from "@/lib/orderDisplayUtils";

export interface TopItemRow {
  name: string;
  revenue: number;
  /** null = no yesterday sales for this name (show "New"). */
  changePct: number | null;
}

const DEFAULT_MAX_ORDERS_TO_SCAN = 120;
const CHUNK = 10;
const TOP_N = 8;

function inRange(d: Date, start: Date, end: Date): boolean {
  return d >= start && d <= end;
}

function orderQualifiesForTopItems(status: string): boolean {
  const u = status.toUpperCase();
  return u !== "OPEN" && u !== "VOIDED";
}

async function aggregateLineRevenueByItemName(
  orderIds: string[]
): Promise<Map<string, number>> {
  const map = new Map<string, number>();
  for (let i = 0; i < orderIds.length; i += CHUNK) {
    const slice = orderIds.slice(i, i + CHUNK);
    await Promise.all(
      slice.map(async (oid) => {
        try {
          const snap = await getDocs(collection(db, "Orders", oid, "items"));
          snap.forEach((d) => {
            const x = d.data();
            const name = String(x.name ?? "Item").trim() || "Item";
            const lineTotal =
              typeof x.lineTotalInCents === "number"
                ? x.lineTotalInCents
                : Number(x.lineTotalInCents ?? 0);
            map.set(name, (map.get(name) ?? 0) + lineTotal);
          });
        } catch {
          /* ignore */
        }
      })
    );
  }
  return map;
}

/**
 * Line-item revenue for `primary` window vs `compare` window (e.g. last 7 days vs prior 7 days).
 * Uses the same order snapshot as the dashboard (may miss orders if volume exceeds fetch limit).
 */
export async function buildTopItemsForDashboard(
  snapshot: QuerySnapshot<DocumentData>,
  primaryStart: Date,
  primaryEnd: Date,
  compareStart: Date,
  compareEnd: Date,
  maxOrdersToScan: number = DEFAULT_MAX_ORDERS_TO_SCAN
): Promise<TopItemRow[]> {
  const primaryIds: string[] = [];
  const compareIds: string[] = [];

  snapshot.forEach((docSnap) => {
    const data = docSnap.data() as Record<string, unknown>;
    const createdAt = parseCreatedAt(data);
    if (!createdAt) return;
    const status = String(data.status ?? "");
    if (!orderQualifiesForTopItems(status)) return;

    if (inRange(createdAt, primaryStart, primaryEnd)) {
      primaryIds.push(docSnap.id);
    } else if (inRange(createdAt, compareStart, compareEnd)) {
      compareIds.push(docSnap.id);
    }
  });

  const primaryCapped = primaryIds.slice(0, maxOrdersToScan);
  const compareCapped = compareIds.slice(0, maxOrdersToScan);

  const [primaryMap, compareMap] = await Promise.all([
    aggregateLineRevenueByItemName(primaryCapped),
    aggregateLineRevenueByItemName(compareCapped),
  ]);

  const sorted = Array.from(primaryMap.entries())
    .filter(([, cents]) => cents > 0)
    .sort((a, b) => b[1] - a[1])
    .slice(0, TOP_N);

  return sorted.map(([name, centsPrimary]) => {
    const centsCompare = compareMap.get(name) ?? 0;
    let changePct: number | null = null;
    if (centsCompare > 0) {
      changePct = ((centsPrimary - centsCompare) / centsCompare) * 100;
    }
    return {
      name,
      revenue: centsPrimary / 100,
      changePct,
    };
  });
}
