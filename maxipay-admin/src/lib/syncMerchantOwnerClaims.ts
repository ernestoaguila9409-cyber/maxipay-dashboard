import * as admin from "firebase-admin";

/**
 * Sets Firebase Auth custom claims for a merchant owner: `merchantId` (active store)
 * and `merchantIds` (all stores linked via `ownerAuthUid` on Merchants docs).
 */
export async function syncMerchantOwnerClaims(
  authAdmin: admin.auth.Auth,
  dbAdmin: admin.firestore.Firestore,
  ownerUid: string,
  activeMerchantId: string
): Promise<{ merchantIds: string[] }> {
  const snap = await dbAdmin.collection("Merchants").where("ownerAuthUid", "==", ownerUid).get();
  const merchantIds = snap.docs.map((d) => d.id);
  if (merchantIds.length === 0) {
    throw new Error("no_merchants_for_owner");
  }
  let active = activeMerchantId.trim();
  if (!merchantIds.includes(active)) {
    active = merchantIds[0];
  }

  const user = await authAdmin.getUser(ownerUid);
  const prev = (user.customClaims ?? {}) as Record<string, unknown>;
  if (prev.role === "super_admin") {
    throw new Error("owner_is_super_admin");
  }

  const next = { ...prev, role: "merchant_owner", merchantId: active, merchantIds };
  await authAdmin.setCustomUserClaims(ownerUid, next);
  return { merchantIds };
}

/**
 * Re-applies owner JWT claims from a merchant document (`ownerAuthUid`).
 * Used after admin create/update, business-settings sync, and owner password setup.
 */
export async function syncMerchantOwnerClaimsForMerchant(
  authAdmin: admin.auth.Auth,
  dbAdmin: admin.firestore.Firestore,
  merchantId: string
): Promise<{ merchantIds: string[] } | null> {
  const snap = await dbAdmin.collection("Merchants").doc(merchantId).get();
  if (!snap.exists) return null;
  const ownerUid = String(snap.data()?.ownerAuthUid ?? "").trim();
  if (!ownerUid) return null;
  return syncMerchantOwnerClaims(authAdmin, dbAdmin, ownerUid, merchantId);
}
