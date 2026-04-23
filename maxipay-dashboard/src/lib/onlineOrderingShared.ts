/** Firestore paths — same as Android `ReceiptSettings` business doc + online ordering settings. */

export const ONLINE_ORDERING_SETTINGS_DOC = "onlineOrdering";
export const SETTINGS_COLLECTION = "Settings";
export const BUSINESS_INFO_DOC = "businessInfo";

/** Firestore `Orders.onlinePaymentChoice` + API `paymentChoice`. */
export type OnlinePaymentChoice = "PAY_AT_STORE" | "REQUEST_TERMINAL_FROM_WEB";

/** POS listens here to open checkout on the Dejavoo (SPIn). */
export const ONLINE_TERMINAL_PAYMENT_REQUESTS = "OnlineTerminalPaymentRequests";

export interface OnlineOrderingSettings {
  enabled: boolean;
  allowPayInStore: boolean;
  /** Customer asks to pay by card; POS is notified to run SPIn on the terminal. */
  allowRequestTerminalFromWeb: boolean;
}

export const DEFAULT_ONLINE_ORDERING_SETTINGS: OnlineOrderingSettings = {
  enabled: false,
  allowPayInStore: true,
  allowRequestTerminalFromWeb: false,
};

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
  };
}
