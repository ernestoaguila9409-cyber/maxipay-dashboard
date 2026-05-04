/** Firestore paths — same as Android `ReceiptSettings` business doc + online ordering settings. */

export const ONLINE_ORDERING_SETTINGS_DOC = "onlineOrdering";
export const SETTINGS_COLLECTION = "Settings";
export const BUSINESS_INFO_DOC = "businessInfo";

/** Firestore `Orders.onlinePaymentChoice` + API `paymentChoice`. */
export type OnlinePaymentChoice = "PAY_AT_STORE" | "PAY_ONLINE_HPP";

/** @deprecated Legacy collection for web-triggered terminal pay; no longer created by MaxiPay web ordering. */
export const ONLINE_TERMINAL_PAYMENT_REQUESTS = "OnlineTerminalPaymentRequests";

/**
 * Converts a business name into a URL-safe slug.
 * "Joe's Pizza & Grill" → "joes-pizza-grill"
 */
export function slugify(text: string): string {
  return text
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/['']/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
}

/**
 * Item-level modifier groups plus any groups triggered by the current picks (transitive).
 * Order: [baseGroupIds] in given order, then newly discovered groups appended per sweep.
 */
export function expandModifierGroupIdsFromPicks(
  baseGroupIds: readonly string[],
  picksByGroup: Readonly<Record<string, readonly string[]>>,
  hasGroup: (groupId: string) => boolean,
  triggersForOption: (groupId: string, optionId: string) => readonly string[],
): string[] {
  const order: string[] = [];
  const seen = new Set<string>();
  for (const id of baseGroupIds) {
    if (hasGroup(id) && !seen.has(id)) {
      seen.add(id);
      order.push(id);
    }
  }
  let changed = true;
  while (changed) {
    changed = false;
    for (const gid of order) {
      const oids = picksByGroup[gid];
      if (!oids?.length) continue;
      for (const oid of oids) {
        for (const tid of triggersForOption(gid, oid)) {
          if (hasGroup(tid) && !seen.has(tid)) {
            seen.add(tid);
            order.push(tid);
            changed = true;
          }
        }
      }
    }
  }
  return order;
}

/**
 * `AUTO` follows the `enabled` flag and, when enabled, optional business hours.
 * `OPEN` / `CLOSED` are manual overrides (OPEN ignores the schedule; CLOSED blocks orders).
 */
export type StoreOpenOverride = "AUTO" | "OPEN" | "CLOSED";

/** One row per calendar weekday, index `0` = Sunday … `6` = Saturday (same as `Date#getDay()`). */
export interface OnlineOrderingDayHours {
  /** When business hours are enforced, unchecked days are treated as closed all day. */
  openForDay: boolean;
  /** Start of the ordering window in [businessHoursTimezone], 24-hour `HH:mm`. */
  openTime: string;
  /** End of the window (exclusive). If earlier than [openTime], the window spans midnight. */
  closeTime: string;
}

export interface OnlineOrderingSettings {
  enabled: boolean;
  allowPayInStore: boolean;
  /** Customer pays online via iPOSpays Hosted Payment Page. */
  allowPayOnlineHpp: boolean;
  /**
   * URL-safe slug derived from the business name.
   * Used in the public ordering URL: `/order/{slug}`.
   */
  onlineOrderingSlug: string;
  /**
   * When true, the public online menu only lists items allowed by
   * [onlineMenuCategoryIds] / [onlineMenuItemIds]. When false, legacy
   * `MenuItems.channels.online` controls visibility.
   */
  onlineMenuCurationEnabled: boolean;
  /** Selecting a category includes every item placed in that category. */
  onlineMenuCategoryIds: string[];
  /** Extra items to include when their category is not fully selected. */
  onlineMenuItemIds: string[];
  /**
   * iPOSpays Hosted Payment Page — merchant TPN (CloudPOS terminal processing number).
   * Stored per tenant in Firestore; server routes read via Admin SDK (never exposed on public config API).
   */
  iposHppTpn: string;
  /** iPOSpays merchant auth token from the portal (rotates per merchant). */
  iposHppAuthToken: string;
  /** Estimated prep time (minutes), shown on the store header. e.g. 25 → "20–30 min". */
  prepTimeMinutes: number;
  /** Manual open/closed override (defaults to `AUTO`). */
  openStatusOverride: StoreOpenOverride;
  /**
   * When true and override is `AUTO`, the storefront and checkout only show "open"
   * during [businessHoursWeekly] in [businessHoursTimezone].
   */
  businessHoursEnforced: boolean;
  /** IANA time zone used to interpret weekly hours (e.g. `America/Chicago`). */
  businessHoursTimezone: string;
  /** Length 7: Sunday at index 0 through Saturday at index 6. */
  businessHoursWeekly: OnlineOrderingDayHours[];
  /**
   * MenuItem ids the owner explicitly featured in the storefront's "Featured" row.
   * Empty = auto-pick (server falls back to first 6 visible items).
   */
  featuredItemIds: string[];
  /** Printer document IDs that should also receive kitchen chits for every online order (additive to labels). */
  onlineRoutingPrinterIds: string[];
  /** KDS device document IDs that should also show every online order (additive to menu assignment). */
  onlineRoutingKdsDeviceIds: string[];
  /**
   * When true, new online orders expect staff confirmation on the POS before they are treated as
   * accepted for the kitchen (same flag as Android Order Types → Online Ordering).
   */
  requireStaffConfirmOrder: boolean;
}

export function defaultWeeklyBusinessHours(): OnlineOrderingDayHours[] {
  return Array.from({ length: 7 }, () => ({
    openForDay: true,
    openTime: "09:00",
    closeTime: "21:00",
  }));
}

export const DEFAULT_ONLINE_ORDERING_SETTINGS: OnlineOrderingSettings = {
  enabled: false,
  allowPayInStore: true,
  allowPayOnlineHpp: false,
  onlineOrderingSlug: "",
  onlineMenuCurationEnabled: false,
  onlineMenuCategoryIds: [],
  onlineMenuItemIds: [],
  iposHppTpn: "",
  iposHppAuthToken: "",
  prepTimeMinutes: 25,
  openStatusOverride: "AUTO",
  businessHoursEnforced: false,
  businessHoursTimezone: "America/New_York",
  businessHoursWeekly: defaultWeeklyBusinessHours(),
  featuredItemIds: [],
  onlineRoutingPrinterIds: [],
  onlineRoutingKdsDeviceIds: [],
  requireStaffConfirmOrder: false,
};

const WEEKDAY_LONG_TO_INDEX: Record<string, number> = {
  sunday: 0,
  monday: 1,
  tuesday: 2,
  wednesday: 3,
  thursday: 4,
  friday: 5,
  saturday: 6,
};

/** Normalizes `H:mm` / `HH:mm` / `HH:mm:ss` (from `<input type="time">`) to `HH:mm`. */
export function normalizeTimeHm(raw: string): string | null {
  const m = /^(\d{1,2}):(\d{2})(?::\d{2})?$/.exec(raw.trim());
  if (!m) return null;
  const h = parseInt(m[1], 10);
  const min = parseInt(m[2], 10);
  if (!Number.isFinite(h) || !Number.isFinite(min) || h < 0 || h > 23 || min < 0 || min > 59) {
    return null;
  }
  return `${String(h).padStart(2, "0")}:${String(min).padStart(2, "0")}`;
}

function parseHmToMinutes(s: string): number | null {
  const n = normalizeTimeHm(s);
  if (!n) return null;
  const [h, min] = n.split(":").map((x) => parseInt(x, 10));
  return h * 60 + min;
}

/**
 * Current instant [now] expressed as weekday + minutes-from-midnight in [timeZone].
 * Returns `null` if [timeZone] is invalid for `Intl`.
 */
export function zonedWeekdayAndMinutes(
  now: Date,
  timeZone: string
): { weekday: number; minutes: number } | null {
  const tz = timeZone.trim();
  if (!tz) return null;
  try {
    const dtf = new Intl.DateTimeFormat("en-US", {
      timeZone: tz,
      weekday: "long",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    });
    const parts = dtf.formatToParts(now);
    const wdRaw = parts.find((p) => p.type === "weekday")?.value ?? "";
    const hourRaw = parts.find((p) => p.type === "hour")?.value ?? "";
    const minRaw = parts.find((p) => p.type === "minute")?.value ?? "";
    const weekday = WEEKDAY_LONG_TO_INDEX[wdRaw.toLowerCase()];
    const hour = parseInt(hourRaw, 10);
    const minute = parseInt(minRaw, 10);
    if (weekday === undefined || !Number.isFinite(hour) || !Number.isFinite(minute)) return null;
    const h = hour === 24 ? 0 : hour;
    const minutes = h * 60 + minute;
    return { weekday, minutes };
  } catch {
    return null;
  }
}

function parseWeeklyHoursFromFirestore(v: unknown): OnlineOrderingDayHours[] {
  const base = defaultWeeklyBusinessHours();
  if (!Array.isArray(v)) return base;
  const out: OnlineOrderingDayHours[] = [];
  for (let i = 0; i < 7; i++) {
    const row = v[i];
    const def = base[i]!;
    if (!row || typeof row !== "object") {
      out.push({ ...def });
      continue;
    }
    const o = row as Record<string, unknown>;
    const openT =
      typeof o.openTime === "string" ? normalizeTimeHm(o.openTime) : null;
    const closeT =
      typeof o.closeTime === "string" ? normalizeTimeHm(o.closeTime) : null;
    out.push({
      openForDay: o.openForDay !== false,
      openTime: openT ?? def.openTime,
      closeTime: closeT ?? def.closeTime,
    });
  }
  return out;
}

/**
 * Whether [now] falls inside the configured weekly window (ignores [openStatusOverride] / [enabled]).
 */
export function isWithinWeeklyBusinessHours(
  s: OnlineOrderingSettings,
  now: Date
): boolean {
  const tz = (s.businessHoursTimezone || "").trim();
  if (!tz) return false;
  const zoned = zonedWeekdayAndMinutes(now, tz);
  if (!zoned) return false;
  const row = s.businessHoursWeekly[zoned.weekday];
  if (!row?.openForDay) return false;
  const openM = parseHmToMinutes(row.openTime);
  const closeM = parseHmToMinutes(row.closeTime);
  if (openM === null || closeM === null) return false;
  if (openM === closeM) return false;
  const c = zoned.minutes;
  if (openM < closeM) {
    return c >= openM && c < closeM;
  }
  return c >= openM || c < closeM;
}

function parseStringIdArray(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  return v.filter((x): x is string => typeof x === "string" && x.trim().length > 0);
}

function parseOpenOverride(v: unknown): StoreOpenOverride {
  if (v === "OPEN" || v === "CLOSED") return v;
  return "AUTO";
}

function cloneDefaultOnlineOrderingSettings(): OnlineOrderingSettings {
  const d = DEFAULT_ONLINE_ORDERING_SETTINGS;
  return {
    ...d,
    featuredItemIds: [...d.featuredItemIds],
    onlineMenuCategoryIds: [...d.onlineMenuCategoryIds],
    onlineMenuItemIds: [...d.onlineMenuItemIds],
    businessHoursWeekly: d.businessHoursWeekly.map((r) => ({ ...r })),
    onlineRoutingPrinterIds: [...d.onlineRoutingPrinterIds],
    onlineRoutingKdsDeviceIds: [...d.onlineRoutingKdsDeviceIds],
  };
}

export function parseOnlineOrderingSettings(
  data: Record<string, unknown> | undefined
): OnlineOrderingSettings {
  if (!data) return cloneDefaultOnlineOrderingSettings();
  const rawPrep =
    typeof data.prepTimeMinutes === "number" && Number.isFinite(data.prepTimeMinutes)
      ? Math.max(0, Math.min(240, Math.round(data.prepTimeMinutes)))
      : DEFAULT_ONLINE_ORDERING_SETTINGS.prepTimeMinutes;
  return {
    enabled: data.enabled === true,
    allowPayInStore: data.allowPayInStore !== false,
    allowPayOnlineHpp: data.allowPayOnlineHpp === true,
    onlineOrderingSlug:
      typeof data.onlineOrderingSlug === "string"
        ? data.onlineOrderingSlug.trim()
        : "",
    onlineMenuCurationEnabled: data.onlineMenuCurationEnabled === true,
    onlineMenuCategoryIds: parseStringIdArray(data.onlineMenuCategoryIds),
    onlineMenuItemIds: parseStringIdArray(data.onlineMenuItemIds),
    iposHppTpn:
      typeof data.iposHppTpn === "string" ? data.iposHppTpn.trim() : "",
    iposHppAuthToken:
      typeof data.iposHppAuthToken === "string" ? data.iposHppAuthToken.trim() : "",
    prepTimeMinutes: rawPrep,
    openStatusOverride: parseOpenOverride(data.openStatusOverride),
    businessHoursEnforced: data.businessHoursEnforced === true,
    businessHoursTimezone:
      typeof data.businessHoursTimezone === "string"
        ? data.businessHoursTimezone.trim()
        : DEFAULT_ONLINE_ORDERING_SETTINGS.businessHoursTimezone,
    businessHoursWeekly: parseWeeklyHoursFromFirestore(data.businessHoursWeekly),
    featuredItemIds: parseStringIdArray(data.featuredItemIds),
    onlineRoutingPrinterIds: parseStringIdArray(data.onlineRoutingPrinterIds),
    onlineRoutingKdsDeviceIds: parseStringIdArray(data.onlineRoutingKdsDeviceIds),
    requireStaffConfirmOrder: data.requireStaffConfirmOrder === true,
  };
}

/** Renders a single prep-time number as a friendly range like "20–30 min". */
export function formatPrepTimeRange(minutes: number): string {
  if (!Number.isFinite(minutes) || minutes <= 0) return "";
  const m = Math.max(5, Math.round(minutes));
  const lower = Math.max(5, m - 5);
  const upper = m + 5;
  return `${lower}–${upper} min`;
}

/**
 * Resolves whether the storefront should show open and whether checkout is allowed
 * (`OPEN` / `CLOSED` override; `AUTO` uses `enabled` plus optional business hours).
 */
export function isStoreCurrentlyOpen(s: OnlineOrderingSettings, now: Date = new Date()): boolean {
  if (s.openStatusOverride === "OPEN") return true;
  if (s.openStatusOverride === "CLOSED") return false;
  if (!s.enabled) return false;
  if (!s.businessHoursEnforced) return true;
  return isWithinWeeklyBusinessHours(s, now);
}
