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
