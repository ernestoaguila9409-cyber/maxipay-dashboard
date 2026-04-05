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

const MAX_ORDERS_TO_SCAN = 48;
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
 * Aggregates line-item revenue from `Orders/{id}/items` for today vs yesterday,
 * using the same recent snapshot as the dashboard (may miss older days if volume is high).
 */
export async function buildTopItemsForDashboard(
  snapshot: QuerySnapshot<DocumentData>,
  todayStart: Date,
  todayEnd: Date,
  yesterdayStart: Date,
  yesterdayEnd: Date
): Promise<TopItemRow[]> {
  const todayIds: string[] = [];
  const yesterdayIds: string[] = [];

  snapshot.forEach((docSnap) => {
    const data = docSnap.data() as Record<string, unknown>;
    const createdAt = parseCreatedAt(data);
    if (!createdAt) return;
    const status = String(data.status ?? "");
    if (!orderQualifiesForTopItems(status)) return;

    if (inRange(createdAt, todayStart, todayEnd)) {
      todayIds.push(docSnap.id);
    } else if (inRange(createdAt, yesterdayStart, yesterdayEnd)) {
      yesterdayIds.push(docSnap.id);
    }
  });

  const todayCapped = todayIds.slice(0, MAX_ORDERS_TO_SCAN);
  const yesterdayCapped = yesterdayIds.slice(0, MAX_ORDERS_TO_SCAN);

  const [todayMap, yesterdayMap] = await Promise.all([
    aggregateLineRevenueByItemName(todayCapped),
    aggregateLineRevenueByItemName(yesterdayCapped),
  ]);

  const sorted = Array.from(todayMap.entries())
    .filter(([, cents]) => cents > 0)
    .sort((a, b) => b[1] - a[1])
    .slice(0, TOP_N);

  return sorted.map(([name, centsToday]) => {
    const centsYesterday = yesterdayMap.get(name) ?? 0;
    let changePct: number | null = null;
    if (centsYesterday > 0) {
      changePct = ((centsToday - centsYesterday) / centsYesterday) * 100;
    }
    return {
      name,
      revenue: centsToday / 100,
      changePct,
    };
  });
}
