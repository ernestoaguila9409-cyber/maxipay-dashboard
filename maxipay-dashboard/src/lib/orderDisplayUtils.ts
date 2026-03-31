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
