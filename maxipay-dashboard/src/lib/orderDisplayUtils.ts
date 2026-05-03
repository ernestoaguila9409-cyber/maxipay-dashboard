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
    case "ONLINE_PICKUP":
      return "Online Order";
    case "BAR_TAB":
    case "BAR":
      return "Bar";
    default:
      return orderType?.trim() ? orderType.replace(/_/g, " ") : "—";
  }
}

/** Web / MaxiPay online ordering — same signal as Android `orderSource` + `orderType`. */
export function isWebOnlineOrder(data: Record<string, unknown>): boolean {
  const src = String(data.orderSource ?? "").trim();
  const ot = String(data.orderType ?? "").trim();
  return src === "online_ordering" || ot === "ONLINE_PICKUP";
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
        : u === "ONLINE_PICKUP"
          ? "ONLINE ORDER"
          : u === "UBER_EATS"
            ? "UBER EATS"
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
        : u === "ONLINE_PICKUP"
          ? "#43A047"
          : u === "UBER_EATS"
            ? "#06C167"
            : u === "BAR" || u === "BAR_TAB"
              ? "#00897B"
              : "#64748B";
  return { backgroundColor, label };
}

/** Left accent strip — matches Android `OrdersAdapter.bindStatusBar`. */
export function orderListStatusBarColor(statusRaw: string): string {
  const u = statusRaw.trim().toUpperCase();
  switch (u) {
    case "OPEN":
      return "#2196F3";
    case "UNPAID":
      return "#F57C00";
    case "ACCEPTED":
      return "#2E7D32";
    case "READY":
      return "#1565C0";
    case "DENIED":
      return "#C62828";
    case "CANCELLED":
      return "#795548";
    case "CLOSED":
      return "#9E9E9E";
    case "VOIDED":
      return "#F44336";
    case "REFUNDED":
    case "PARTIALLY_REFUNDED":
    case "REFUNDED_FULLY":
      return "#FF9800";
    default:
      return "#BDBDBD";
  }
}

/** Pill on row 2 — matches Android `OrdersAdapter.bindStatusBadge`. */
export function orderListStatusBadgeStyle(statusRaw: string): {
  label: string;
  backgroundColor: string;
  color: string;
} {
  const label = statusRaw.trim().toUpperCase() || "—";
  const u = label;
  const table: Record<string, readonly [string, string]> = {
    OPEN: ["#E3F2FD", "#1565C0"],
    UNPAID: ["#FFF3E0", "#E65100"],
    ACCEPTED: ["#E8F5E9", "#2E7D32"],
    READY: ["#E3F2FD", "#1565C0"],
    DENIED: ["#FFEBEE", "#C62828"],
    CANCELLED: ["#EFEBE9", "#795548"],
    CLOSED: ["#F5F5F5", "#616161"],
    VOIDED: ["#FFEBEE", "#C62828"],
    REFUNDED: ["#FFF3E0", "#E65100"],
    PARTIALLY_REFUNDED: ["#FFF3E0", "#E65100"],
    REFUNDED_FULLY: ["#FFF3E0", "#E65100"],
  };
  const pair = table[u] ?? (["#F5F5F5", "#424242"] as const);
  return { label, backgroundColor: pair[0], color: pair[1] };
}

/**
 * Primary title on order list cards (`OrdersAdapter` / `item_order.xml`):
 * `#392 · Server` for POS, or `#392 · N items` when `orderSource` is set (online / third-party).
 */
export function buildAndroidOrdersListTitle(data: Record<string, unknown>): string {
  const isOnline = String(data.orderSource ?? "").trim().length > 0;
  const orderNumber = Math.round(Number(data.orderNumber ?? 0));
  let s = "";
  if (orderNumber > 0) s += `#${orderNumber}`;

  if (!isOnline) {
    const rawStatus = effectivePosOrderStatus(data).toUpperCase();
    const empRaw = String(data.employeeName ?? "").trim();
    const voidedBy = String(data.voidedBy ?? "").trim();
    const displayName =
      rawStatus === "VOIDED" && voidedBy ? voidedBy : empRaw;
    if (displayName && displayName !== "—") {
      if (s) s += " \u00b7 ";
      s += displayName;
    }
  } else {
    const itemsCount = Math.round(Number(data.itemsCount ?? 0));
    if (itemsCount > 0) {
      if (s) s += " \u00b7 ";
      s += `${itemsCount} item${itemsCount !== 1 ? "s" : ""}`;
    }
  }
  return s;
}

/** Net $ shown on list card: `totalInCents - totalRefundedInCents`, floored at 0 — matches `OrderRow.netCents`. */
export function orderListNetCents(data: Record<string, unknown>): number {
  const total = Math.round(Number(data.totalInCents ?? 0));
  const refunded = Math.round(Number(data.totalRefundedInCents ?? 0));
  return Math.max(0, total - refunded);
}

/** Same pattern as Android `DateFormat.format("MMM dd · h:mm a", …)`. */
export function formatOrdersListTime(d: Date): string {
  const md = d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
  const tm = d.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit", hour12: true });
  return `${md} \u00b7 ${tm}`;
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

/**
 * Status for dashboard lists: if Firestore still says OPEN but the order is
 * fully paid (e.g. online HPP before a POS tax recompute), show Closed.
 */
export function effectivePosOrderStatus(data: Record<string, unknown>): string {
  const raw = String(data.status ?? "OPEN").trim();
  const u = raw.toUpperCase();
  if (u === "VOIDED" || u === "REFUNDED") return raw;
  const total = Math.round(Number(data.totalInCents ?? 0));
  const paid = Math.round(Number(data.totalPaidInCents ?? 0));
  if (total > 0 && paid >= total) return "CLOSED";
  return raw;
}

/**
 * When [effectiveStatus] is OPEN, maps web online checkout `onlinePaymentChoice`
 * to UNPAID (pay at store, or pay online before fully paid). Matches Android
 * [OnlineOrderStatusDisplay.listBadgeStatus].
 */
export function onlineOpenPaymentBadgeFromEffective(
  effectiveStatus: string,
  data: Record<string, unknown>
): string {
  const u = effectiveStatus.trim().toUpperCase();
  if (u !== "OPEN") return u;
  if (!isWebOnlineOrder(data)) return u;
  const choice = String(data.onlinePaymentChoice ?? "").trim();
  const total = Math.round(Number(data.totalInCents ?? 0));
  const paid = Math.round(Number(data.totalPaidInCents ?? 0));
  if (choice === "PAY_AT_STORE" || choice === "") return "UNPAID";
  if (choice === "PAY_ONLINE_HPP" && total > 0 && paid < total) return "UNPAID";
  return u;
}

/** Header / pill label: effective POS status with online unpaid override. */
export function orderStatusDisplayForUi(data: Record<string, unknown>): string {
  return onlineOpenPaymentBadgeFromEffective(
    effectivePosOrderStatus(data),
    data
  );
}

/**
 * Sales activity (web): voiding a card sale updates `Transactions` (`voided`, `voidedBy`)
 * but the linked `Orders` row often stays `CLOSED` without `status: VOIDED`.
 * Merge void state from the sale/capture/pre-auth transaction so Orders tab badges
 * match the Transactions tab.
 */
export function mergeSalesActivityOrderWithVoidedSaleTx(
  orderId: string,
  orderData: Record<string, unknown>,
  txDocs: Array<{ id: string; data: Record<string, unknown> }>
): Record<string, unknown> {
  const saleTxId = String(orderData.saleTransactionId ?? "").trim();
  const candidates = txDocs.filter(({ id, data }) => {
    if (data.voided !== true) return false;
    const t = String(data.type ?? "");
    if (t !== "SALE" && t !== "CAPTURE" && t !== "PRE_AUTH") return false;
    if (String(data.orderId ?? "").trim() !== orderId) return false;
    if (saleTxId && id !== saleTxId) return false;
    return true;
  });
  if (candidates.length === 0) return orderData;
  const voidedByTx = String(candidates[0].data.voidedBy ?? "").trim();
  const voidedByOrder = String(orderData.voidedBy ?? "").trim();
  return {
    ...orderData,
    status: "VOIDED",
    voidedBy: voidedByTx || voidedByOrder || orderData.voidedBy,
  };
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
  const online = isWebOnlineOrder(data);
  const canonical = effectivePosOrderStatus(data);
  const badge = onlineOpenPaymentBadgeFromEffective(canonical, data);
  return {
    id: docId,
    orderNumber,
    orderType: formatOrderTypeLabel(otRaw),
    orderTypeRaw: otRaw,
    total: totalInCents / 100,
    status: canonical,
    statusDisplay:
      badge !== canonical.trim().toUpperCase() ? badge : undefined,
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
    /** List UIs: do not show guest name in the employee column for web online orders (detail shows `customerName`). */
    employeeName: online ? "—" : String(data.employeeName ?? "—"),
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
