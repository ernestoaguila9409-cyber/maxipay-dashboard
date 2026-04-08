import type { Timestamp } from "firebase/firestore";
import {
  DEFAULT_KITCHEN_TICKET_STYLE,
  parseKitchenTicketStyle,
  type KitchenTicketStyleState,
} from "@/lib/kitchenTicketStyle";

/** Online if `lastSeen` is within this many milliseconds of client `Date.now()`. */
export const PRINTER_ONLINE_THRESHOLD_MS = 15_000;

/** Client refresh interval for recomputing "last seen ago" from wall clock. */
export const PRINTER_STATUS_TICK_MS = 5_000;

export type PrinterStatus = "ONLINE" | "OFFLINE" | "UNKNOWN";

export interface PrinterDocFields {
  id: string;
  name: string;
  ipAddress: string;
  port: number;
  labels: string[];
  /** Raw status from Firestore (may be UNKNOWN if field is missing). */
  rawStatus: PrinterStatus;
  lastSeenMs: number | null;
  /** Kitchen chit typography; defaults when absent in Firestore. */
  kitchenTicketStyle: KitchenTicketStyleState;
}

function hasToDate(v: unknown): v is Timestamp {
  return (
    typeof v === "object" &&
    v !== null &&
    "toDate" in v &&
    typeof (v as Timestamp).toDate === "function"
  );
}

export function parseFirestoreLastSeenMillis(
  data: Record<string, unknown>
): number | null {
  const v = data.lastSeen;
  if (v == null) return null;

  if (hasToDate(v)) {
    try {
      return v.toDate().getTime();
    } catch {
      return null;
    }
  }

  if (v instanceof Date) {
    return v.getTime();
  }

  if (typeof v === "number" && Number.isFinite(v)) {
    if (v > 0 && v < 1e12) return Math.round(v * 1000);
    return v;
  }

  if (typeof v === "object" && v !== null && "seconds" in v) {
    const s = Number((v as { seconds: unknown }).seconds);
    if (Number.isFinite(s)) return s * 1000;
  }

  return null;
}

function parseStatus(raw: unknown): PrinterStatus {
  if (typeof raw !== "string") return "UNKNOWN";
  const u = raw.trim().toUpperCase();
  if (u === "ONLINE") return "ONLINE";
  if (u === "OFFLINE") return "OFFLINE";
  return "UNKNOWN";
}

function parseLabels(raw: unknown): string[] {
  if (Array.isArray(raw)) {
    return raw
      .map((v) => (typeof v === "string" ? v.trim() : ""))
      .filter(Boolean);
  }
  return [];
}

export function mapPrinterDocument(
  id: string,
  data: Record<string, unknown>
): PrinterDocFields {
  const ipAddress = String(data.ipAddress ?? data.ip ?? "").trim() || "—";
  const name = String(data.name ?? "").trim() || "Unnamed printer";
  const port = typeof data.port === "number" && Number.isFinite(data.port) ? data.port : 9100;

  const parsedStyle = parseKitchenTicketStyle(data.kitchenTicketStyle);
  const kitchenTicketStyle = parsedStyle ?? DEFAULT_KITCHEN_TICKET_STYLE;

  return {
    id,
    name,
    ipAddress,
    port,
    labels: parseLabels(data.labels),
    rawStatus: parseStatus(data.status),
    lastSeenMs: parseFirestoreLastSeenMillis(data),
    kitchenTicketStyle,
  };
}

/**
 * Resolve the effective status for display.
 * If the POS wrote an explicit `status` field (ONLINE/OFFLINE), use that.
 * Otherwise fall back to computing from `lastSeen` vs wall-clock threshold.
 */
export function resolveStatus(
  rawStatus: PrinterStatus,
  lastSeenMs: number | null,
  nowMs: number
): PrinterStatus {
  if (rawStatus !== "UNKNOWN") return rawStatus;
  if (lastSeenMs == null || !Number.isFinite(lastSeenMs)) return "UNKNOWN";
  return (nowMs - lastSeenMs) <= PRINTER_ONLINE_THRESHOLD_MS ? "ONLINE" : "OFFLINE";
}

/** Human-readable age since lastSeen; `null` lastSeen → "Never". */
export function formatLastSeenAgo(
  lastSeenMs: number | null,
  nowMs: number
): string {
  if (lastSeenMs == null || !Number.isFinite(lastSeenMs)) return "Never";
  const sec = Math.max(0, Math.floor((nowMs - lastSeenMs) / 1000));
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 48) return `${hr}h ago`;
  const d = Math.floor(hr / 24);
  return `${d}d ago`;
}
