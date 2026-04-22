import {
  collection,
  getDocs,
  limit,
  orderBy,
  query,
  Timestamp,
  where,
  type DocumentData,
  type QuerySnapshot,
} from "firebase/firestore";

import {
  buildDailySalesPoints,
  orderRevenueCentsForMetrics,
} from "@/lib/dashboardFinance";
import { parseCreatedAt } from "@/lib/orderDisplayUtils";
import { db } from "@/firebase/firebaseConfig";
import { formatCategoryDisplayName } from "@/lib/categoryNameUtils";
import {
  getDailySalesSummary,
  type DailySalesSummary,
} from "@/lib/reportEngine";

import type { HourlySalesPoint } from "@/components/SalesChart";

/** Max closed orders scanned for line-item detail (summary KPIs still use full tx data). */
export const SALES_REPORT_ORDER_SCAN_LIMIT = 2500;
const ITEM_FETCH_CHUNK = 12;

export interface ItemSalesRow {
  itemId: string;
  itemName: string;
  categoryId: string;
  categoryName: string;
  quantity: number;
  grossCents: number;
  netCents: number;
}

export interface CategorySalesRow {
  categoryId: string;
  categoryName: string;
  grossCents: number;
}

export interface SalesReportPayload {
  summary: DailySalesSummary;
  refundCount: number;
  itemRows: ItemSalesRow[];
  categoryRows: CategorySalesRow[];
  /** For Sales Over Time toggles; null when using mock data. */
  ordersSnapshot: QuerySnapshot<DocumentData> | null;
}

function orderPaidCents(data: Record<string, unknown>): number {
  return orderRevenueCentsForMetrics(data as DocumentData);
}

function inRangeExclusiveEnd(createdAt: Date, start: Date, endExclusive: Date): boolean {
  return createdAt >= start && createdAt < endExclusive;
}

function formatHourCompact(hour: number): string {
  const am = hour < 12;
  const display = hour % 12 === 0 ? 12 : hour % 12;
  return `${display}${am ? "a" : "p"}`;
}

/** Sum CLOSED order revenue by clock hour (0–23) across the window. */
export function buildRevenueByHourPoints(
  snapshot: QuerySnapshot<DocumentData>,
  start: Date,
  endExclusive: Date
): HourlySalesPoint[] {
  const amounts = new Array(24).fill(0) as number[];
  snapshot.forEach((docSnap) => {
    const data = docSnap.data() as Record<string, unknown>;
    if (String(data.status ?? "").toUpperCase() === "VOIDED") return;
    const createdAt = parseCreatedAt(data);
    if (!createdAt || !inRangeExclusiveEnd(createdAt, start, endExclusive)) return;
    const cents = orderPaidCents(data);
    if (cents <= 0) return;
    const hour = createdAt.getHours();
    amounts[hour] += cents / 100;
  });
  return amounts.map((amount, hour) => ({
    amount,
    label: formatHourCompact(hour),
  }));
}

function startOfWeekMonday(base: Date): Date {
  const d = new Date(base);
  const day = d.getDay();
  const diff = day === 0 ? -6 : 1 - day;
  d.setDate(d.getDate() + diff);
  d.setHours(0, 0, 0, 0);
  return d;
}

function enumerateWeekStarts(rangeStart: Date, rangeEndInclusive: Date): Date[] {
  const out: Date[] = [];
  let cur = startOfWeekMonday(rangeStart);
  const last = startOfWeekMonday(rangeEndInclusive);
  while (cur.getTime() <= last.getTime()) {
    out.push(new Date(cur));
    cur.setDate(cur.getDate() + 7);
  }
  return out;
}

function weekRangeLabel(weekStart: Date): string {
  const end = new Date(weekStart);
  end.setDate(end.getDate() + 6);
  const o: Intl.DateTimeFormatOptions = { month: "short", day: "numeric" };
  return `${weekStart.toLocaleDateString("en-US", o)} – ${end.toLocaleDateString("en-US", o)}`;
}

/** Sum CLOSED revenue by calendar week (Monday start) within range. */
export function buildRevenueByWeekPoints(
  snapshot: QuerySnapshot<DocumentData>,
  start: Date,
  endExclusive: Date
): HourlySalesPoint[] {
  const inclusiveEnd = new Date(endExclusive.getTime() - 1);
  const weekStarts = enumerateWeekStarts(start, inclusiveEnd);
  const keyToAmount = new Map<string, number>();
  for (const ws of weekStarts) {
    keyToAmount.set(ws.getTime().toString(), 0);
  }

  snapshot.forEach((docSnap) => {
    const data = docSnap.data() as Record<string, unknown>;
    if (String(data.status ?? "").toUpperCase() === "VOIDED") return;
    const createdAt = parseCreatedAt(data);
    if (!createdAt || !inRangeExclusiveEnd(createdAt, start, endExclusive)) return;
    const cents = orderPaidCents(data);
    if (cents <= 0) return;
    const ws = startOfWeekMonday(createdAt);
    const k = ws.getTime().toString();
    if (!keyToAmount.has(k)) {
      keyToAmount.set(k, 0);
    }
    keyToAmount.set(k, (keyToAmount.get(k) ?? 0) + cents / 100);
  });

  return weekStarts.map((ws) => ({
    label: weekRangeLabel(ws),
    amount: keyToAmount.get(ws.getTime().toString()) ?? 0,
  }));
}

async function loadCategoryAndMenuMaps(): Promise<{
  categoryNameById: Map<string, string>;
  itemMetaById: Map<string, { categoryId: string; name: string }>;
}> {
  const [catSnap, itemSnap] = await Promise.all([
    getDocs(collection(db, "Categories")),
    getDocs(collection(db, "MenuItems")),
  ]);
  const categoryNameById = new Map<string, string>();
  catSnap.forEach((d) => {
    const n = String(d.get("name") ?? "").trim();
    categoryNameById.set(d.id, n || "Category");
  });
  const itemMetaById = new Map<string, { categoryId: string; name: string }>();
  itemSnap.forEach((d) => {
    itemMetaById.set(d.id, {
      categoryId: String(d.get("categoryId") ?? ""),
      name: String(d.get("name") ?? "").trim() || "Item",
    });
  });
  return { categoryNameById, itemMetaById };
}

async function fetchRefundCount(start: Date, endExclusive: Date): Promise<number> {
  try {
    const snap = await getDocs(
      query(
        collection(db, "Transactions"),
        where("createdAt", ">=", Timestamp.fromDate(start)),
        where("createdAt", "<", Timestamp.fromDate(endExclusive))
      )
    );
    let n = 0;
    snap.forEach((d) => {
      const data = d.data();
      if (data.voided === true) return;
      if (String(data.type ?? "") === "REFUND") n += 1;
    });
    return n;
  } catch {
    return 0;
  }
}

type LineAgg = {
  itemId: string;
  itemName: string;
  categoryId: string;
  categoryName: string;
  quantity: number;
  grossCents: number;
  netCents: number;
};

function resolveCategoryName(
  categoryId: string,
  categoryNameById: Map<string, string>
): string {
  if (!categoryId) return "Uncategorized";
  const raw = categoryNameById.get(categoryId);
  return raw ? formatCategoryDisplayName(raw) : "Uncategorized";
}

async function aggregateItemRows(
  orderIds: string[],
  orderDiscountById: Map<string, number>,
  categoryNameById: Map<string, string>,
  itemMetaById: Map<string, { categoryId: string; name: string }>
): Promise<Map<string, LineAgg>> {
  const byKey = new Map<string, LineAgg>();

  for (let i = 0; i < orderIds.length; i += ITEM_FETCH_CHUNK) {
    const slice = orderIds.slice(i, i + ITEM_FETCH_CHUNK);
    await Promise.all(
      slice.map(async (orderId) => {
        const discountCents = orderDiscountById.get(orderId) ?? 0;
        let subtotalCents = 0;
        const lines: Array<{ itemId: string; name: string; qty: number; lineTotal: number }> =
          [];
        try {
          const snap = await getDocs(collection(db, "Orders", orderId, "items"));
          snap.forEach((d) => {
            const x = d.data();
            const name = String(x.name ?? "Item").trim() || "Item";
            const itemId = String(x.itemId ?? "").trim();
            const qty = Math.round(Number(x.quantity ?? 1)) || 1;
            const lineTotal = Math.round(
              typeof x.lineTotalInCents === "number"
                ? x.lineTotalInCents
                : Number(x.lineTotalInCents ?? 0)
            );
            if (lineTotal <= 0) return;
            lines.push({ itemId, name, qty, lineTotal });
            subtotalCents += lineTotal;
          });
        } catch {
          return;
        }

        for (const line of lines) {
          const share =
            subtotalCents > 0 ? line.lineTotal / subtotalCents : 0;
          const netLine = Math.max(
            0,
            Math.round(line.lineTotal - discountCents * share)
          );

          const meta = line.itemId ? itemMetaById.get(line.itemId) : undefined;
          const categoryId = meta?.categoryId ?? "";
          const categoryName = resolveCategoryName(categoryId, categoryNameById);
          const displayName = meta?.name ?? line.name;
          const rowKey = line.itemId || `name:${line.name.toLowerCase()}`;

          const cur = byKey.get(rowKey);
          if (cur) {
            cur.quantity += line.qty;
            cur.grossCents += line.lineTotal;
            cur.netCents += netLine;
          } else {
            byKey.set(rowKey, {
              itemId: line.itemId,
              itemName: displayName,
              categoryId,
              categoryName,
              quantity: line.qty,
              grossCents: line.lineTotal,
              netCents: netLine,
            });
          }
        }
      })
    );
  }

  return byKey;
}

export async function loadSalesReportData(
  start: Date,
  endExclusive: Date
): Promise<SalesReportPayload> {
  const [summary, categoryMaps, refundCount] = await Promise.all([
    getDailySalesSummary(start, endExclusive),
    loadCategoryAndMenuMaps(),
    fetchRefundCount(start, endExclusive),
  ]);

  let ordersSnap: QuerySnapshot<DocumentData>;
  try {
    ordersSnap = await getDocs(
      query(
        collection(db, "Orders"),
        where("createdAt", ">=", Timestamp.fromDate(start)),
        where("createdAt", "<", Timestamp.fromDate(endExclusive)),
        orderBy("createdAt", "desc"),
        limit(SALES_REPORT_ORDER_SCAN_LIMIT)
      )
    );
  } catch {
    ordersSnap = await getDocs(
      query(collection(db, "Orders"), orderBy("createdAt", "desc"), limit(800))
    );
  }

  const { categoryNameById, itemMetaById } = categoryMaps;

  const closedOrderIds: string[] = [];
  const orderDiscountById = new Map<string, number>();
  ordersSnap.forEach((docSnap) => {
    const data = docSnap.data() as Record<string, unknown>;
    const createdAt = parseCreatedAt(data);
    if (!createdAt || !inRangeExclusiveEnd(createdAt, start, endExclusive)) return;
    if (String(data.status ?? "") !== "CLOSED") return;
    if (closedOrderIds.length >= SALES_REPORT_ORDER_SCAN_LIMIT) return;
    closedOrderIds.push(docSnap.id);
    orderDiscountById.set(
      docSnap.id,
      Math.round(Number(data.discountInCents ?? 0))
    );
  });

  const itemMap = await aggregateItemRows(
    closedOrderIds,
    orderDiscountById,
    categoryNameById,
    itemMetaById
  );

  const itemRows: ItemSalesRow[] = Array.from(itemMap.values()).sort(
    (a, b) => b.grossCents - a.grossCents
  );

  const catMap = new Map<string, CategorySalesRow>();
  for (const row of itemRows) {
    const cid = row.categoryId || "uncat";
    const cur = catMap.get(cid);
    if (cur) {
      cur.grossCents += row.grossCents;
    } else {
      catMap.set(cid, {
        categoryId: cid,
        categoryName: row.categoryName,
        grossCents: row.grossCents,
      });
    }
  }
  const categoryRows = Array.from(catMap.values()).sort(
    (a, b) => b.grossCents - a.grossCents
  );

  return {
    summary,
    refundCount,
    itemRows,
    categoryRows,
    ordersSnapshot: ordersSnap,
  };
}

export function buildChartPoints(
  mode: "hourly" | "daily" | "weekly",
  snapshot: QuerySnapshot<DocumentData>,
  start: Date,
  endExclusive: Date
): HourlySalesPoint[] {
  const inclusiveEnd = new Date(endExclusive.getTime() - 1);
  if (mode === "hourly") {
    return buildRevenueByHourPoints(snapshot, start, endExclusive);
  }
  if (mode === "daily") {
    return buildDailySalesPoints(snapshot, start, inclusiveEnd);
  }
  return buildRevenueByWeekPoints(snapshot, start, endExclusive);
}

export function buildMockSalesReportPayload(): SalesReportPayload {
  const itemRows: ItemSalesRow[] = [
    {
      itemId: "m1",
      itemName: "Latte",
      categoryId: "c1",
      categoryName: "Coffee",
      quantity: 186,
      grossCents: 837_00,
      netCents: 812_00,
    },
    {
      itemId: "m2",
      itemName: "Breakfast Burrito",
      categoryId: "c2",
      categoryName: "Food",
      quantity: 94,
      grossCents: 1_128_00,
      netCents: 1_090_00,
    },
    {
      itemId: "m3",
      itemName: "Cold Brew",
      categoryId: "c1",
      categoryName: "Coffee",
      quantity: 142,
      grossCents: 568_00,
      netCents: 550_00,
    },
    {
      itemId: "m4",
      itemName: "Croissant",
      categoryId: "c3",
      categoryName: "Pastry",
      quantity: 210,
      grossCents: 630_00,
      netCents: 615_00,
    },
    {
      itemId: "m5",
      itemName: "Iced Tea",
      categoryId: "c4",
      categoryName: "Drinks",
      quantity: 88,
      grossCents: 264_00,
      netCents: 258_00,
    },
  ];
  const categoryRows: CategorySalesRow[] = [
    { categoryId: "c2", categoryName: "Food", grossCents: 1_128_00 },
    { categoryId: "c1", categoryName: "Coffee", grossCents: 1_405_00 },
    { categoryId: "c3", categoryName: "Pastry", grossCents: 630_00 },
    { categoryId: "c4", categoryName: "Drinks", grossCents: 264_00 },
  ];
  const summary: DailySalesSummary = {
    grossSalesCents: 342_700,
    netSalesCents: 312_500,
    taxCollectedCents: 19_800,
    tipsCollectedCents: 41_200,
    totalTransactions: 142,
    averageTicketCents: Math.round(342_700 / 142),
    refundsCents: 4_500,
    discountsCents: 18_000,
    itemsSold: 720,
    voidedItems: 2,
    cashPaymentsCents: 98_000,
    creditPaymentsCents: 189_000,
    debitPaymentsCents: 55_700,
  };
  return {
    summary,
    refundCount: 6,
    itemRows,
    categoryRows,
    ordersSnapshot: null,
  };
}

export function buildMockChartPoints(mode: "hourly" | "daily" | "weekly"): HourlySalesPoint[] {
  if (mode === "hourly") {
    const amounts = [
      0, 0, 0, 0, 0, 120, 280, 190, 210, 340, 520, 680, 890, 640, 410, 380, 450, 720, 910,
      860, 540, 320, 140, 60,
    ];
    return Array.from({ length: 24 }, (_, h) => ({
      label: formatHourCompact(h),
      amount: amounts[h] ?? 0,
    }));
  }
  if (mode === "daily") {
    return ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((label, i) => ({
      label,
      amount: 2100 + i * 380 + (i === 5 ? 2400 : 0),
    }));
  }
  return [
    { label: "Mar 3 – Mar 9", amount: 18200 },
    { label: "Mar 10 – Mar 16", amount: 21400 },
    { label: "Mar 17 – Mar 23", amount: 19800 },
    { label: "Mar 24 – Mar 30", amount: 23600 },
  ];
}
