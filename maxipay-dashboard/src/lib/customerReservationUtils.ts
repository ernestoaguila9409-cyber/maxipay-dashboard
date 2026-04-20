import type { Timestamp } from "firebase/firestore";

const TERMINAL = new Set(["EXPIRED", "CANCELLED", "COMPLETED", "SEATED"]);

/** Mirrors Android [ReservationFirestoreHelper.isReservationActiveForList]. */
export function isReservationActiveForList(statusRaw: unknown): boolean {
  const st = String(statusRaw ?? "").trim().toUpperCase();
  return !st || !TERMINAL.has(st);
}

/** Mirrors Android [ReservationFirestoreHelper.reservationStatusForCustomerProfile]. */
export function reservationStatusForCustomerProfile(
  statusRaw: unknown
): "ACTIVE" | "COMPLETED" | "CANCELLED" {
  const st = String(statusRaw ?? "").trim().toUpperCase();
  if (st === "CANCELLED") return "CANCELLED";
  if (["COMPLETED", "SEATED", "EXPIRED"].includes(st)) return "COMPLETED";
  return "ACTIVE";
}

function timestampToMillis(v: unknown): number | null {
  if (v == null) return null;
  if (
    typeof v === "object" &&
    v !== null &&
    "toMillis" in v &&
    typeof (v as Timestamp).toMillis === "function"
  ) {
    try {
      return (v as Timestamp).toMillis();
    } catch {
      return null;
    }
  }
  return null;
}

/**
 * Best-effort slot time (Android [reservationSlotMillisForExpiry] — primary path:
 * [reservationTime] timestamp only on web).
 */
export function reservationSlotMillis(data: Record<string, unknown>): number | null {
  return timestampToMillis(data.reservationTime);
}

export function tableNamesForReservation(data: Record<string, unknown>): string {
  const name = String(data.tableName ?? "").trim();
  if (name) return name;
  const tid = String(data.tableId ?? "").trim();
  return tid || "Table";
}

export function formatReservationSlot(ms: number): string {
  const d = new Date(ms);
  const datePart = new Intl.DateTimeFormat("en-US", {
    weekday: "short",
    month: "short",
    day: "numeric",
  }).format(d);
  const timePart = new Intl.DateTimeFormat("en-US", {
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  }).format(d);
  return `${datePart} · ${timePart}`;
}

export interface CustomerReservationRow {
  reservationId: string;
  tableNames: string;
  partySize: number;
  dateDisplay: string;
  displayStatus: string;
  sortKeyMillis: number;
  emphasize: boolean;
}

export function partitionReservationRows(
  docs: Array<{ id: string; data(): Record<string, unknown> }>,
  nowMs: number
): { upcoming: CustomerReservationRow[]; past: CustomerReservationRow[] } {
  const upcomingRaw: CustomerReservationRow[] = [];
  const pastRaw: CustomerReservationRow[] = [];

  for (const d of docs) {
    const data = d.data();
    const slotMs = reservationSlotMillis(data);
    const active = isReservationActiveForList(data.status);
    const inFuture = slotMs != null && slotMs >= nowMs;
    const isUpcoming = active && (slotMs == null || inFuture);
    const sortKey =
      slotMs ?? (isUpcoming ? Number.MAX_SAFE_INTEGER : Number.MIN_SAFE_INTEGER);

    const party = Math.max(0, Math.floor(Number(data.partySize ?? 0)));
    const dateDisplay =
      slotMs != null
        ? formatReservationSlot(slotMs)
        : String(data.whenText ?? "").trim() || "—";
    const displayStatus = reservationStatusForCustomerProfile(data.status);

    const row: CustomerReservationRow = {
      reservationId: d.id,
      tableNames: tableNamesForReservation(data),
      partySize: party,
      dateDisplay,
      displayStatus,
      sortKeyMillis: sortKey,
      emphasize: false,
    };
    if (isUpcoming) upcomingRaw.push(row);
    else pastRaw.push(row);
  }

  upcomingRaw.sort((a, b) => a.sortKeyMillis - b.sortKeyMillis);
  pastRaw.sort((a, b) => b.sortKeyMillis - a.sortKeyMillis);

  const upcoming = upcomingRaw.map((r, i) =>
    i === 0 ? { ...r, emphasize: true } : { ...r, emphasize: false }
  );

  return { upcoming, past: pastRaw };
}
