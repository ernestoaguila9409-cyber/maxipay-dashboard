import admin from "firebase-admin";

const ONLINE_ORDERING_DOC = "onlineOrdering";

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
