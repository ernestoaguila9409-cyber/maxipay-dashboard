import type {
  DocumentData,
  QuerySnapshot,
  Timestamp,
} from "firebase/firestore";
import type { Order } from "@/components/OrdersTable";

/** Human-readable label for POS `orderType` (matches Android). */
export function formatOrderTypeLabel(orderType: string): string {
  switch (orderType) {
    case "DINE_IN":
      return "Dine In";
    case "TO_GO":
      return "To Go";
    case "BAR_TAB":
    case "BAR":
      return "Bar";
    default:
      return orderType?.trim() ? orderType.replace(/_/g, " ") : "—";
  }
}

/** Firestore `taxBreakdown[]` entry (Android OrderEngine / POS). */
export interface TaxBreakdownEntry {
  name: string;
  amountInCents: number;
  rate: number | null;
  taxType: string;
}

export function parseTaxBreakdown(raw: unknown): TaxBreakdownEntry[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((entry) => {
    const e = entry as Record<string, unknown>;
    const rateRaw = e.rate;
    const rateNum =
      typeof rateRaw === "number"
        ? rateRaw
        : rateRaw != null && rateRaw !== ""
          ? Number(rateRaw)
          : NaN;
    const rate = Number.isFinite(rateNum) ? rateNum : null;
    const amt = e.amountInCents;
    const amountInCents = Math.round(
      typeof amt === "number" ? amt : Number(amt ?? 0)
    );
    return {
      name: String(e.name ?? "Tax"),
      amountInCents,
      rate,
      taxType: String(e.taxType ?? ""),
    };
  });
}

/** Same label rules as Android `OrderDetailActivity.renderSummary`. */
export function formatTaxBreakdownLabel(entry: TaxBreakdownEntry): string {
  const { name, rate, taxType } = entry;
  if (taxType === "PERCENTAGE" && rate != null && rate > 0) {
    const pctStr =
      rate % 1 === 0 ? String(Math.trunc(rate)) : rate.toFixed(2);
    return `${name} (${pctStr}%)`;
  }
  return name;
}

/** Mirrors Android `DiscountDisplay.GroupedDiscount` / `groupByName`. */
export interface GroupedDiscount {
  name: string;
  type: string | null;
  value: unknown;
  totalCents: number;
}

function numericToDouble(raw: unknown): number | null {
  if (raw == null) return null;
  if (typeof raw === "number" && Number.isFinite(raw)) return raw;
  if (typeof raw === "string") {
    const n = parseFloat(raw.trim());
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function isPercentageDiscountType(type: string | null | undefined): boolean {
  const t = type?.trim().toLowerCase() ?? "";
  return t === "percentage" || t === "percent";
}

/** Mirrors Android `DiscountDisplay.formatReceiptLabel`. */
export function formatDiscountReceiptLabel(
  discountName: string,
  type: string | null | undefined,
  value: unknown
): string {
  const v = numericToDouble(value);
  if (isPercentageDiscountType(type) && v != null) {
    const pctStr = v % 1 === 0 ? String(Math.trunc(v)) : v.toFixed(1);
    return `${discountName} (${pctStr}%)`;
  }
  return discountName;
}

function amountInCentsFromDiscountMap(ad: Record<string, unknown>): number {
  const raw = ad.amountInCents;
  if (typeof raw === "number") return Math.round(raw);
  if (raw != null) return Math.round(Number(raw));
  return 0;
}

/** Mirrors Android `DiscountDisplay.groupByName`. */
export function groupAppliedDiscounts(raw: unknown): GroupedDiscount[] {
  if (!Array.isArray(raw)) return [];
  const byName = new Map<string, Record<string, unknown>[]>();
  for (const item of raw) {
    const ad = item as Record<string, unknown>;
    const name = String(ad.discountName ?? "Discount");
    const list = byName.get(name) ?? [];
    list.push(ad);
    byName.set(name, list);
  }
  const out: GroupedDiscount[] = [];
  for (const [name, entries] of byName) {
    const first = entries[0];
    const totalCents = entries.reduce(
      (s, e) => s + amountInCentsFromDiscountMap(e),
      0
    );
    if (totalCents <= 0) continue;
    out.push({
      name,
      type: first.type != null ? String(first.type) : null,
      value: first.value,
      totalCents,
    });
  }
  return out;
}

/**
 * Mirrors Android `OrderDetailActivity.renderSummary` tip label:
 * `Tip (X.X%)` when tip and subtotal are both &gt; 0.
 */
export function formatTipSummaryLabel(
  tipAmountInCents: number,
  subtotalCents: number
): string {
  if (tipAmountInCents > 0 && subtotalCents > 0) {
    const tipPct = (tipAmountInCents / subtotalCents) * 100;
    const pctStr =
      tipPct % 1 === 0 ? String(Math.trunc(tipPct)) : tipPct.toFixed(1);
    return `Tip (${pctStr}%)`;
  }
  return "Tip";
}

/**
 * Badge colors match Android: OrderDetail (TO_GO / DINE_IN) + OrdersAdapter (BAR / BAR_TAB).
 */
export function orderTypeBadgeStyle(orderTypeRaw: string): {
  backgroundColor: string;
  label: string;
} {
  const u = orderTypeRaw.trim();
  if (!u) {
    return { backgroundColor: "#64748b", label: "—" };
  }
  const label =
    u === "DINE_IN"
      ? "DINE IN"
      : u === "TO_GO"
        ? "TO-GO"
        : u === "BAR_TAB"
          ? "BAR TAB"
          : u === "BAR"
            ? "BAR"
            : formatOrderTypeLabel(u).toUpperCase();
  const backgroundColor =
    u === "DINE_IN"
      ? "#4CAF50"
      : u === "TO_GO"
        ? "#FF9800"
        : u === "BAR" || u === "BAR_TAB"
          ? "#00897B"
          : "#64748B";
  return { backgroundColor, label };
}

/**
 * Safe createdAt: only Firestore Timestamp with toDate() is accepted; else null.
 */
export function parseCreatedAt(data: Record<string, unknown>): Date | null {
  const v = data.createdAt;
  if (v == null) return null;
  if (
    typeof v === "object" &&
    v !== null &&
    "toDate" in v &&
    typeof (v as Timestamp).toDate === "function"
  ) {
    try {
      return (v as Timestamp).toDate();
    } catch {
      return null;
    }
  }
  return null;
}

/** Order detail / legacy: returns null if createdAt is missing or not a Timestamp. */
export function firestoreDate(data: { createdAt?: Timestamp }): Date | null {
  return parseCreatedAt(data as Record<string, unknown>);
}

/** Maps Firestore `Orders` document to dashboard table row (POS app schema). */
export function mapFirestoreOrderDoc(
  docId: string,
  data: Record<string, unknown>
): Order {
  const createdAt = parseCreatedAt(data);
  const totalInCents =
    typeof data.totalInCents === "number"
      ? data.totalInCents
      : Number(data.totalInCents ?? 0);
  const orderNumberRaw = data.orderNumber;
  const orderNumber =
    typeof orderNumberRaw === "number" || typeof orderNumberRaw === "string"
      ? String(orderNumberRaw)
      : docId.slice(-6);
  const otRaw = String(data.orderType ?? "");
  return {
    id: docId,
    orderNumber,
    orderType: formatOrderTypeLabel(otRaw),
    orderTypeRaw: otRaw,
    total: totalInCents / 100,
    status: String(data.status ?? "OPEN"),
    createdAt: createdAt ?? null,
    createdAtMs: createdAt ? createdAt.getTime() : 0,
    date: createdAt ? createdAt.toLocaleDateString() : "—",
    time: createdAt
      ? createdAt.toLocaleTimeString([], {
          hour: "2-digit",
          minute: "2-digit",
        })
      : "—",
    source: "pos",
    employeeName: String(data.employeeName ?? "—"),
    customerName: String(data.customerName ?? ""),
  };
}

/** Merge two snapshots (e.g. recent + OPEN) so older open tickets still appear in the UI. */
export function mergeOrderSnapshots(
  primary: QuerySnapshot<DocumentData>,
  extra?: QuerySnapshot<DocumentData> | null
): Order[] {
  const map = new Map<string, Order>();
  primary.forEach((docSnap) => {
    map.set(
      docSnap.id,
      mapFirestoreOrderDoc(
        docSnap.id,
        docSnap.data() as Record<string, unknown>
      )
    );
  });
  extra?.forEach((docSnap) => {
    map.set(
      docSnap.id,
      mapFirestoreOrderDoc(
        docSnap.id,
        docSnap.data() as Record<string, unknown>
      )
    );
  });
  return Array.from(map.values()).sort(
    (a, b) => (b.createdAtMs ?? 0) - (a.createdAtMs ?? 0)
  );
}
