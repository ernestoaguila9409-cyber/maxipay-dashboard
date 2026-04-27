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
 * `AUTO` follows the `enabled` flag (and any future business-hours logic). `OPEN` / `CLOSED`
 * are manual overrides the owner can toggle from the dashboard so they can stop accepting
 * online orders without flipping the whole feature off.
 */
export type StoreOpenOverride = "AUTO" | "OPEN" | "CLOSED";

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
   * MenuItem ids the owner explicitly featured in the storefront's "Featured" row.
   * Empty = auto-pick (server falls back to first 6 visible items).
   */
  featuredItemIds: string[];
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
  featuredItemIds: [],
};

function parseStringIdArray(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  return v.filter((x): x is string => typeof x === "string" && x.trim().length > 0);
}

function parseOpenOverride(v: unknown): StoreOpenOverride {
  if (v === "OPEN" || v === "CLOSED") return v;
  return "AUTO";
}

export function parseOnlineOrderingSettings(
  data: Record<string, unknown> | undefined
): OnlineOrderingSettings {
  if (!data) return { ...DEFAULT_ONLINE_ORDERING_SETTINGS };
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
    featuredItemIds: parseStringIdArray(data.featuredItemIds),
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

/** Resolves the effective open/closed boolean from `enabled` + `openStatusOverride`. */
export function isStoreCurrentlyOpen(s: OnlineOrderingSettings): boolean {
  if (s.openStatusOverride === "OPEN") return true;
  if (s.openStatusOverride === "CLOSED") return false;
  return s.enabled;
}
