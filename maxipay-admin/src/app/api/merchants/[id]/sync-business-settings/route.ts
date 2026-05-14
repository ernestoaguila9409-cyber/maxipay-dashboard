import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp, verifyIdToken } from "@/lib/firebaseAdmin";
import { syncSettingsBusinessInfoFromMerchant } from "@/lib/syncMerchantBusinessInfo";
import { syncMerchantOwnerClaimsForMerchant } from "@/lib/syncMerchantOwnerClaims";

export const runtime = "nodejs";

async function requireSuperAdmin(req: Request): Promise<void> {
  const decoded = await verifyIdToken(req.headers.get("authorization"));
  if (decoded.role !== "super_admin") throw new Error("Forbidden");
}

function handleError(e: unknown) {
  const msg = e instanceof Error ? e.message : String(e);
  if (msg === "Unauthorized")
    return NextResponse.json({ ok: false, error: "unauthorized" }, { status: 401 });
  if (msg === "Forbidden")
    return NextResponse.json({ ok: false, error: "forbidden" }, { status: 403 });
  console.error("[merchants/sync-business-settings]", e);
  return NextResponse.json({ ok: false, error: "server_error" }, { status: 500 });
}

/** Pushes Merchants/{id} into Merchants/{id}/settings/businessInfo for POS + web dashboard (no body). */
export async function POST(_req: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    await requireSuperAdmin(_req);
    const { id } = await params;
    getFirebaseAdminApp();
    const db = admin.firestore();
    const ref = db.collection("Merchants").doc(id);
    const snap = await ref.get();
    if (!snap.exists) {
      return NextResponse.json({ ok: false, error: "not_found" }, { status: 404 });
    }
    await syncSettingsBusinessInfoFromMerchant(db, id, snap.data()!);
    const authAdmin = admin.auth();
    await syncMerchantOwnerClaimsForMerchant(authAdmin, db, id);
    return NextResponse.json({ ok: true });
  } catch (e) {
    return handleError(e);
  }
}
