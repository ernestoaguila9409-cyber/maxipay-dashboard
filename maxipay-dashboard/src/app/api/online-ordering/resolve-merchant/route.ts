import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp } from "@/lib/firebaseAdmin";
import { resolveMerchantIdFromOnlineOrderingSlug } from "@/lib/onlineOrderingServer";

export const runtime = "nodejs";

/**
 * Public: map `/order/{slug}` URL segment to `Merchants/{merchantId}` using
 * `Settings/onlineOrdering.onlineOrderingSlug`.
 */
export async function GET(request: Request) {
  try {
    const { searchParams } = new URL(request.url);
    const slug = searchParams.get("slug")?.trim();
    if (!slug) {
      return NextResponse.json({ error: "slug is required." }, { status: 400 });
    }

    getFirebaseAdminApp();
    const db = admin.firestore();
    const result = await resolveMerchantIdFromOnlineOrderingSlug(db, slug);
    if ("error" in result) {
      return NextResponse.json({ error: result.error }, { status: 404 });
    }
    return NextResponse.json({ merchantId: result.merchantId }, {
      headers: { "Cache-Control": "no-store" },
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error("[online-ordering/resolve-merchant]", msg);
    return NextResponse.json(
      { error: "Could not resolve store.", detail: msg },
      { status: 500 }
    );
  }
}
