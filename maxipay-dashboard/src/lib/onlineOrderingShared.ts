/** Firestore paths — same as Android `ReceiptSettings` business doc + online ordering settings. */

export const ONLINE_ORDERING_SETTINGS_DOC = "onlineOrdering";
export const SETTINGS_COLLECTION = "Settings";
export const BUSINESS_INFO_DOC = "businessInfo";

export type OnlinePaymentChoice = "PAY_AT_STORE" | "PAY_ONLINE_STRIPE";

export interface OnlineOrderingSettings {
  enabled: boolean;
  allowPayInStore: boolean;
  allowPayOnlineStripe: boolean;
}

export const DEFAULT_ONLINE_ORDERING_SETTINGS: OnlineOrderingSettings = {
  enabled: false,
  allowPayInStore: true,
  allowPayOnlineStripe: false,
};

export function parseOnlineOrderingSettings(
  data: Record<string, unknown> | undefined
): OnlineOrderingSettings {
  if (!data) return { ...DEFAULT_ONLINE_ORDERING_SETTINGS };
  return {
    enabled: data.enabled === true,
    allowPayInStore: data.allowPayInStore !== false,
    allowPayOnlineStripe: data.allowPayOnlineStripe === true,
  };
}
