import admin from "firebase-admin";

const ONLINE_ORDERING_DOC = "onlineOrdering";
const RECEIPT_SETTINGS_DOC = "receiptSettings";

/**
 * Default print/receipt options for new merchants.
 * Keep in sync with `DEFAULT_PRINT` in maxipay-dashboard `settings/business/page.tsx`.
 */
export const DEFAULT_RECEIPT_SETTINGS: Record<string, boolean | number> = {
  showServerName: true,
  showDateTime: true,
  showLogo: true,
  /** Landi C20 Pro: 0 = Standard logo width, 1 = Large (full paper). */
  logoSize: 0,
  showEmail: false,
  boldBizName: true,
  boldAddress: false,
  boldOrderInfo: true,
  boldItems: false,
  boldTotals: false,
  boldGrandTotal: true,
  boldFooter: false,
  fontSizeBizName: 2,
  fontSizeAddress: 2,
  fontSizeOrderInfo: 2,
  fontSizeItems: 0,
  fontSizeTotals: 0,
  fontSizeGrandTotal: 1,
  fontSizeFooter: 0,
};

/** Same rules as maxipay-dashboard `slugify` — stable public menu URLs. */
export function slugifyBusinessName(text: string): string {
  return text
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/['']/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
}

function defaultWeeklyBusinessHours() {
  return Array.from({ length: 7 }, () => ({
    openForDay: true,
    openTime: "09:00",
    closeTime: "21:00",
  }));
}

/**
 * Seeds `Merchants/{id}/settings/onlineOrdering` for new stores so Menu QR (`mode=view`),
 * slug resolution, and storefront config work without manual setup.
 * Merges only — never overwrites an existing slug or enabled flag.
 */
export async function seedOnlineOrderingSettingsForNewMerchant(
  db: admin.firestore.Firestore,
  merchantId: string,
  businessName: string,
): Promise<void> {
  const ref = db
    .collection("Merchants")
    .doc(merchantId)
    .collection("settings")
    .doc(ONLINE_ORDERING_DOC);

  const snap = await ref.get();
  const existing = snap.data();
  if (existing && typeof existing.onlineOrderingSlug === "string" && existing.onlineOrderingSlug.trim()) {
    return;
  }

  const slug = slugifyBusinessName(businessName);
  await ref.set(
    {
      enabled: false,
      allowPayInStore: true,
      allowPayOnlineHpp: false,
      onlineOrderingSlug: slug,
      onlineMenuCurationEnabled: false,
      onlineMenuCategoryIds: [],
      onlineMenuItemIds: [],
      prepTimeMinutes: 25,
      openStatusOverride: "AUTO",
      businessHoursEnforced: false,
      businessHoursTimezone: "America/New_York",
      businessHoursWeekly: defaultWeeklyBusinessHours(),
      featuredItemIds: [],
      onlineRoutingPrinterIds: [],
      onlineRoutingKdsDeviceIds: [],
      requireStaffConfirmOrder: false,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    },
    { merge: true },
  );
}

/**
 * Seeds `Merchants/{id}/settings/receiptSettings` so POS + merchant dashboard sync
 * (logo size, show logo, fonts, etc.) without waiting for the owner to open Print Settings.
 * Skips if the document already exists.
 */
export async function seedReceiptSettingsForNewMerchant(
  db: admin.firestore.Firestore,
  merchantId: string,
): Promise<void> {
  const ref = db
    .collection("Merchants")
    .doc(merchantId)
    .collection("settings")
    .doc(RECEIPT_SETTINGS_DOC);

  const snap = await ref.get();
  if (snap.exists) return;

  await ref.set({
    ...DEFAULT_RECEIPT_SETTINGS,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
}
