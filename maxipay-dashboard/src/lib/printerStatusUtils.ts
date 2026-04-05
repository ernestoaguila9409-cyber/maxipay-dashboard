import type { Timestamp } from "firebase/firestore";

/** Online if `lastSeen` is within this many milliseconds of client `Date.now()`. */
export const PRINTER_ONLINE_THRESHOLD_MS = 15_000;

/** Client refresh interval for recomputing online/offline from wall clock. */
export const PRINTER_STATUS_TICK_MS = 5_000;

export interface PrinterDocFields {
  id: string;
  name: string;
  ip: string;
  type: string;
  typeLabel: string;
  assignedDeviceId: string | null;
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

/**
 * Normalize Firestore `lastSeen` to epoch ms (same idea as Android `readLastSeenMillis`).
 */
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
    // Assume seconds if it looks like Unix seconds
    if (v > 0 && v < 1e12) return Math.round(v * 1000);
    return v;
  }

  if (typeof v === "object" && v !== null && "seconds" in v) {
    const s = Number((v as { seconds: unknown }).seconds);
    if (Number.isFinite(s)) return s * 1000;
  }

  return null;
}

function formatTypeLabel(raw: string): string {
  const u = raw.trim().toUpperCase();
  if (u === "RECEIPT") return "Receipt";
  if (u === "KITCHEN") return "Kitchen";
  if (!u) return "—";
  return raw
    .replace(/_/g, " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

export function mapPrinterDocument(
  id: string,
  data: Record<string, unknown>
): PrinterDocFields {
  const ip = String(data.ip ?? data.ipAddress ?? "").trim() || "—";
  const typeRaw = String(data.type ?? "").trim();
  const name = String(data.name ?? "").trim() || "Unnamed printer";
  const assigned =
    data.assignedDeviceId != null && String(data.assignedDeviceId).trim() !== ""
      ? String(data.assignedDeviceId).trim()
      : null;

  return {
    id,
    name,
    ip,
    type: typeRaw || "—",
    typeLabel: formatTypeLabel(typeRaw || ""),
    assignedDeviceId: assigned,
    lastSeenMs: parseFirestoreLastSeenMillis(data),
  };
}

export function isOnlineFromLastSeen(
  lastSeenMs: number | null,
  nowMs: number
): boolean {
  if (lastSeenMs == null || !Number.isFinite(lastSeenMs)) return false;
  return nowMs - lastSeenMs <= PRINTER_ONLINE_THRESHOLD_MS;
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
