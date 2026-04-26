/** Firestore paths — same as Android `ReceiptSettings` business doc + online ordering settings. */

export const ONLINE_ORDERING_SETTINGS_DOC = "onlineOrdering";
export const SETTINGS_COLLECTION = "Settings";
export const BUSINESS_INFO_DOC = "businessInfo";

/** Firestore `Orders.onlinePaymentChoice` + API `paymentChoice`. */
export type OnlinePaymentChoice = "PAY_AT_STORE" | "REQUEST_TERMINAL_FROM_WEB" | "PAY_ONLINE_HPP";

/** POS listens here to open checkout on the Dejavoo (SPIn). */
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

export interface OnlineOrderingSettings {
  enabled: boolean;
  allowPayInStore: boolean;
  /** Customer asks to pay by card; POS is notified to run SPIn on the terminal. */
  allowRequestTerminalFromWeb: boolean;
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
}

export const DEFAULT_ONLINE_ORDERING_SETTINGS: OnlineOrderingSettings = {
  enabled: false,
  allowPayInStore: true,
  allowRequestTerminalFromWeb: false,
  allowPayOnlineHpp: false,
  onlineOrderingSlug: "",
  onlineMenuCurationEnabled: false,
  onlineMenuCategoryIds: [],
  onlineMenuItemIds: [],
};

function parseStringIdArray(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  return v.filter((x): x is string => typeof x === "string" && x.trim().length > 0);
}

export function parseOnlineOrderingSettings(
  data: Record<string, unknown> | undefined
): OnlineOrderingSettings {
  if (!data) return { ...DEFAULT_ONLINE_ORDERING_SETTINGS };
  const legacyStripe = data.allowPayOnlineStripe === true;
  return {
    enabled: data.enabled === true,
    allowPayInStore: data.allowPayInStore !== false,
    allowRequestTerminalFromWeb:
      data.allowRequestTerminalFromWeb === true ||
      legacyStripe,
    allowPayOnlineHpp: data.allowPayOnlineHpp === true,
    onlineOrderingSlug:
      typeof data.onlineOrderingSlug === "string"
        ? data.onlineOrderingSlug.trim()
        : "",
    onlineMenuCurationEnabled: data.onlineMenuCurationEnabled === true,
    onlineMenuCategoryIds: parseStringIdArray(data.onlineMenuCategoryIds),
    onlineMenuItemIds: parseStringIdArray(data.onlineMenuItemIds),
  };
}
