import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp } from "@/lib/firebaseAdmin";
import { loadPublicOnlineOrderingConfig } from "@/lib/onlineOrderingServer";

export const runtime = "nodejs";

/**
 * Public read: whether online ordering is on, business name (Settings/businessInfo),
 * and which payment paths are enabled.
 */
export async function GET(request: Request) {
  try {
    const { searchParams } = new URL(request.url);
    const merchantId = searchParams.get("merchantId")?.trim();
    if (!merchantId) {
      return NextResponse.json({ error: "merchantId is required." }, { status: 400 });
    }

    getFirebaseAdminApp();
    const db = admin.firestore();
    const cfg = await loadPublicOnlineOrderingConfig(db, merchantId);
    return NextResponse.json(cfg, {
      headers: { "Cache-Control": "no-store" },
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error("[online-ordering/config]", msg);
    return NextResponse.json(
      { error: "Server configuration error.", detail: msg },
      { status: 500 }
    );
  }
}
