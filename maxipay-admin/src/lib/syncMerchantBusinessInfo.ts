import admin from "firebase-admin";

function stringFromFirestore(v: unknown): string {
  if (v == null) return "";
  if (typeof v === "string") return v.trim();
  return String(v).trim();
}

/** Flatten Merchants/{id}.address into the single string used by Settings/businessInfo. */
export function formatMerchantAddressForSettings(addr: unknown): string {
  if (!addr || typeof addr !== "object") return "";
  const o = addr as Record<string, unknown>;
  const street = typeof o.street === "string" ? o.street.trim() : "";
  const city = typeof o.city === "string" ? o.city.trim() : "";
  const state = typeof o.state === "string" ? o.state.trim() : "";
  const zip = typeof o.zip === "string" ? o.zip.trim() : "";
  const line2 = [city, state, zip].filter(Boolean).join(" ");
  if (street && line2) return `${street}\n${line2}`;
  return street || line2;
}

/**
 * Copy Merchants profile into Settings/businessInfo so the web dashboard, POS, and
 * receipt templates see the same business name / address / phone / email.
 * Skips if businessInfo is already owned by a different merchantId.
 */
export async function syncSettingsBusinessInfoFromMerchant(
  db: admin.firestore.Firestore,
  merchantId: string,
  merchantData: admin.firestore.DocumentData
): Promise<void> {
  const businessName = stringFromFirestore(merchantData.businessName);
  const phone = stringFromFirestore(merchantData.phone);
  const email = stringFromFirestore(merchantData.email);
  const address = formatMerchantAddressForSettings(merchantData.address);

  const ref = db
    .collection("Merchants")
    .doc(merchantId)
    .collection("settings")
    .doc("businessInfo");
  const snap = await ref.get();

  const logoRaw = snap.exists ? snap.data()?.logoUrl : "";
  const logoUrl = typeof logoRaw === "string" ? logoRaw : "";

  await ref.set(
    {
      businessName,
      address,
      phone,
      email,
      logoUrl,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    },
    { merge: true }
  );
}
