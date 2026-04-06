import type { Timestamp } from "firebase/firestore";

/** Client refresh interval for recomputing "last seen ago" from wall clock. */
export const PRINTER_STATUS_TICK_MS = 5_000;

export type PrinterStatus = "ONLINE" | "OFFLINE" | "UNKNOWN";

export interface PrinterDocFields {
  id: string;
  name: string;
  ipAddress: string;
  port: number;
  labels: string[];
  status: PrinterStatus;
  lastSeenMs: number | null;
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

  return {
    id,
    name,
    ipAddress,
    port,
    labels: parseLabels(data.labels),
    status: parseStatus(data.status),
    lastSeenMs: parseFirestoreLastSeenMillis(data),
  };
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
