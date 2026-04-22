import {
  collection,
  getDocs,
  query,
  where,
  Timestamp,
  type DocumentData,
  type QueryDocumentSnapshot,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { orderRevenueCentsForMetrics } from "@/lib/dashboardFinance";

export interface DailySalesSummary {
  grossSalesCents: number;
  taxCollectedCents: number;
  tipsCollectedCents: number;
  netSalesCents: number;
  totalTransactions: number;
  averageTicketCents: number;
  refundsCents: number;
  discountsCents: number;
  itemsSold: number;
  voidedItems: number;
  cashPaymentsCents: number;
  creditPaymentsCents: number;
  debitPaymentsCents: number;
}

export interface SalesByOrderType {
  dineInCents: number;
  toGoCents: number;
  barCents: number;
}

export interface HourlySale {
  hour: number;
  totalCents: number;
  orderCount: number;
}

export interface CardBrandSale {
  brand: string;
  totalCents: number;
  txCount: number;
}

export interface EmployeeMetrics {
  name: string;
  salesCents: number;
  orderCount: number;
  tipsCents: number;
  tipsCount: number;
  refundsCents: number;
  refundsCount: number;
  voidsCount: number;
}

function startOfLocalDay(base: Date, dayOffset = 0): Date {
  const d = new Date(base);
  d.setDate(d.getDate() + dayOffset);
  d.setHours(0, 0, 0, 0);
  return d;
}

function dayAfter(d: Date): Date {
  return startOfLocalDay(d, 1);
}

export function periodRange(
  period: "today" | "week" | "month"
): { start: Date; end: Date } {
  const now = new Date();
  let start: Date;
  switch (period) {
    case "week":
      start = startOfLocalDay(now, -7);
      break;
    case "month":
      start = new Date(now.getFullYear(), now.getMonth(), 1, 0, 0, 0, 0);
      break;
    default:
      start = startOfLocalDay(now);
  }
  return { start, end: dayAfter(now) };
}

type TxDoc = QueryDocumentSnapshot<DocumentData>;
type OrderDoc = QueryDocumentSnapshot<DocumentData>;

function sumPaymentsInCents(doc: TxDoc): number {
  const data = doc.data();
  const payments = data.payments as Array<Record<string, unknown>> | undefined;
  if (Array.isArray(payments) && payments.length > 0) {
    let total = 0;
    for (const p of payments) {
      if (p.status === "VOIDED") continue;
      total += Math.round(Number(p.amountInCents ?? 0));
    }
    if (total > 0) return total;
  }
  const paid = Number(data.totalPaidInCents ?? 0);
  if (paid > 0) return paid;
  return Math.round(Number(data.totalPaid ?? 0) * 100);
}

function paymentsByMethod(doc: TxDoc): Record<string, number> {
  const data = doc.data();
  const payments = data.payments as Array<Record<string, unknown>> | undefined;
  const result: Record<string, number> = {};
  if (!Array.isArray(payments)) return result;
  for (const p of payments) {
    if (p.status === "VOIDED") continue;
    const method = String(
      p.paymentType ?? p.paymentMethod ?? "OTHER"
    ).toUpperCase();
    const cents = Math.round(Number(p.amountInCents ?? 0));
    result[method] = (result[method] ?? 0) + cents;
  }
  return result;
}

function normalizeCardBrand(raw: string): string {
  const u = raw.toUpperCase();
  if (u.includes("VISA")) return "Visa";
  if (u.includes("MASTER")) return "Mastercard";
  if (u.includes("AMEX") || u.includes("AMERICAN")) return "Amex";
  if (u.includes("DISCOVER")) return "Discover";
  if (!raw.trim()) return "Other";
  return raw;
}

async function fetchTransactions(
  start: Date,
  end: Date
): Promise<TxDoc[]> {
  const q = query(
    collection(db, "Transactions"),
    where("createdAt", ">=", Timestamp.fromDate(start)),
    where("createdAt", "<", Timestamp.fromDate(end))
  );
  const snap = await getDocs(q);
  return snap.docs;
}

async function fetchOrders(
  start: Date,
  end: Date
): Promise<OrderDoc[]> {
  const q = query(
    collection(db, "Orders"),
    where("createdAt", ">=", Timestamp.fromDate(start)),
    where("createdAt", "<", Timestamp.fromDate(end))
  );
  const snap = await getDocs(q);
  return snap.docs;
}

async function fetchOrderItems(
  orderId: string
): Promise<Array<Record<string, unknown>>> {
  const snap = await getDocs(collection(db, "Orders", orderId, "items"));
  return snap.docs.map((d) => d.data() as Record<string, unknown>);
}

export async function getDailySalesSummary(
  start: Date,
  end: Date
): Promise<DailySalesSummary> {
  const [txDocs, orderDocs] = await Promise.all([
    fetchTransactions(start, end),
    fetchOrders(start, end),
  ]);

  let grossCents = 0;
  let cashCents = 0;
  let creditCents = 0;
  let debitCents = 0;
  let refundCents = 0;
  let saleCount = 0;

  for (const doc of txDocs) {
    const data = doc.data();
    if (data.voided === true) continue;
    const type = String(data.type ?? "");
    if (type === "SALE" || type === "CAPTURE") {
      const txTotal = sumPaymentsInCents(doc);
      if (txTotal > 0) {
        grossCents += txTotal;
        saleCount++;
      }
      const methods = paymentsByMethod(doc);
      cashCents += methods["CASH"] ?? 0;
      creditCents += methods["CREDIT"] ?? 0;
      debitCents += methods["DEBIT"] ?? 0;
    } else if (type === "REFUND") {
      const amt =
        Math.round(Number(data.amountInCents ?? 0)) ||
        Math.round(Number(data.amount ?? 0) * 100);
      refundCents += amt;
    }
  }

  let taxCents = 0;
  let tipCents = 0;
  let discountCents = 0;
  let itemsSold = 0;
  let voidedItems = 0;

  const itemPromises: Promise<void>[] = [];

  for (const doc of orderDocs) {
    const data = doc.data();
    const status = String(data.status ?? "");

    if (status === "CLOSED") {
      const taxBreakdown = data.taxBreakdown as
        | Array<Record<string, unknown>>
        | undefined;
      if (Array.isArray(taxBreakdown)) {
        for (const entry of taxBreakdown) {
          taxCents += Math.round(Number(entry.amountInCents ?? 0));
        }
      }
      tipCents += Math.round(Number(data.tipAmountInCents ?? 0));
      discountCents += Math.round(Number(data.discountInCents ?? 0));
    }

    if (status === "CLOSED" || status === "VOIDED") {
      const orderId = doc.id;
      const orderStatus = status;
      itemPromises.push(
        fetchOrderItems(orderId).then((items) => {
          for (const item of items) {
            const qty = Math.round(Number(item.quantity ?? 1));
            if (orderStatus === "VOIDED") {
              voidedItems += qty;
            } else {
              itemsSold += qty;
            }
          }
        })
      );
    }
  }

  await Promise.all(itemPromises);

  const netSalesCents = grossCents - taxCents - discountCents;
  const averageTicketCents = saleCount > 0 ? Math.round(grossCents / saleCount) : 0;

  return {
    grossSalesCents: grossCents,
    taxCollectedCents: taxCents,
    tipsCollectedCents: tipCents,
    netSalesCents,
    totalTransactions: saleCount,
    averageTicketCents,
    refundsCents: refundCents,
    discountsCents: discountCents,
    itemsSold,
    voidedItems,
    cashPaymentsCents: cashCents,
    creditPaymentsCents: creditCents,
    debitPaymentsCents: debitCents,
  };
}

export async function getSalesByOrderType(
  start: Date,
  end: Date
): Promise<SalesByOrderType> {
  const orderDocs = await fetchOrders(start, end);
  let dineInCents = 0;
  let toGoCents = 0;
  let barCents = 0;

  for (const doc of orderDocs) {
    const data = doc.data();
    if (String(data.status ?? "") === "VOIDED") continue;
    const orderType = String(data.orderType ?? "");
    const paid = orderRevenueCentsForMetrics(data);
    if (paid <= 0) continue;
    switch (orderType) {
      case "DINE_IN":
        dineInCents += paid;
        break;
      case "TO_GO":
        toGoCents += paid;
        break;
      case "BAR":
      case "BAR_TAB":
        barCents += paid;
        break;
    }
  }
  return { dineInCents, toGoCents, barCents };
}

export async function getHourlySales(
  start: Date,
  end: Date
): Promise<HourlySale[]> {
  const q = query(
    collection(db, "Orders"),
    where("status", "==", "CLOSED"),
    where("createdAt", ">=", Timestamp.fromDate(start)),
    where("createdAt", "<", Timestamp.fromDate(end))
  );
  const snap = await getDocs(q);
  const hourMap = new Map<number, { cents: number; count: number }>();

  snap.forEach((doc) => {
    const data = doc.data();
    const createdAt = data.createdAt?.toDate?.() ?? new Date();
    const paid = orderRevenueCentsForMetrics(data);
    if (paid <= 0) return;
    const hour = createdAt.getHours();
    const cur = hourMap.get(hour) ?? { cents: 0, count: 0 };
    hourMap.set(hour, { cents: cur.cents + paid, count: cur.count + 1 });
  });

  return Array.from(hourMap.entries())
    .map(([hour, v]) => ({ hour, totalCents: v.cents, orderCount: v.count }))
    .sort((a, b) => a.hour - b.hour);
}

export async function getCardBrandSales(
  start: Date,
  end: Date
): Promise<CardBrandSale[]> {
  const txDocs = await fetchTransactions(start, end);
  const brandMap = new Map<string, { cents: number; count: number }>();

  for (const doc of txDocs) {
    const data = doc.data();
    if (data.voided === true) continue;
    const type = String(data.type ?? "");
    if (type !== "SALE" && type !== "CAPTURE") continue;
    const payments = data.payments as Array<Record<string, unknown>> | undefined;
    if (!Array.isArray(payments)) continue;
    for (const p of payments) {
      if (p.status === "VOIDED") continue;
      const pt = String(p.paymentType ?? "").toUpperCase();
      if (pt === "CASH") continue;
      const brand = normalizeCardBrand(String(p.cardBrand ?? ""));
      const cents = Math.round(Number(p.amountInCents ?? 0));
      const cur = brandMap.get(brand) ?? { cents: 0, count: 0 };
      brandMap.set(brand, { cents: cur.cents + cents, count: cur.count + 1 });
    }
  }

  return Array.from(brandMap.entries())
    .map(([brand, v]) => ({ brand, totalCents: v.cents, txCount: v.count }))
    .sort((a, b) => b.totalCents - a.totalCents);
}

export async function getEmployeeMetrics(
  start: Date,
  end: Date
): Promise<EmployeeMetrics[]> {
  const [txDocs, orderDocs] = await Promise.all([
    fetchTransactions(start, end),
    fetchOrders(start, end),
  ]);

  const map = new Map<
    string,
    {
      salesCents: number;
      orderCount: number;
      tipsCents: number;
      tipsCount: number;
      refundsCents: number;
      refundsCount: number;
      voidsCount: number;
    }
  >();

  function accum(name: string) {
    if (!map.has(name))
      map.set(name, {
        salesCents: 0,
        orderCount: 0,
        tipsCents: 0,
        tipsCount: 0,
        refundsCents: 0,
        refundsCount: 0,
        voidsCount: 0,
      });
    return map.get(name)!;
  }

  const orderEmployee = new Map<string, string>();

  for (const doc of orderDocs) {
    const data = doc.data();
    const status = String(data.status ?? "");
    if (status === "CLOSED") {
      const emp = String(data.employeeName ?? "").trim();
      if (!emp) continue;
      orderEmployee.set(doc.id, emp);
      const a = accum(emp);
      const paid = orderRevenueCentsForMetrics(data);
      a.salesCents += paid;
      a.orderCount++;
      const tip = Math.round(Number(data.tipAmountInCents ?? 0));
      if (tip > 0) {
        a.tipsCents += tip;
        a.tipsCount++;
      }
    } else if (status === "VOIDED") {
      const emp = String(data.voidedBy ?? "").trim();
      if (emp) accum(emp).voidsCount++;
    }
  }

  for (const doc of txDocs) {
    const data = doc.data();
    if (data.voided === true) continue;
    if (String(data.type ?? "") === "REFUND") {
      const emp = String(data.refundedBy ?? "").trim();
      if (!emp) continue;
      const a = accum(emp);
      const amt =
        Math.round(Number(data.amountInCents ?? 0)) ||
        Math.round(Number(data.amount ?? 0) * 100);
      a.refundsCents += amt;
      a.refundsCount++;
    }
  }

  return Array.from(map.entries())
    .map(([name, v]) => ({ name, ...v }))
    .sort((a, b) => b.salesCents - a.salesCents);
}
