import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp } from "@/lib/firebaseAdmin";
import { loadPublicStorefront } from "@/lib/onlineOrderingServer";

export const runtime = "nodejs";

/**
 * Public storefront snapshot: hero slides + store header (logo, name, prep time, open status)
 * + featured item ids + payment paths. One round-trip for the customer page top-of-fold.
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
    const data = await loadPublicStorefront(db, merchantId);
    return NextResponse.json(data, {
      headers: { "Cache-Control": "no-store" },
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error("[online-ordering/storefront]", msg);
    return NextResponse.json(
      { error: "Could not load storefront.", detail: msg },
      { status: 500 }
    );
  }
}
