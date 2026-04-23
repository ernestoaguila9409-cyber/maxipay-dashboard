import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp } from "@/lib/firebaseAdmin";
import {
  loadOnlineMenu,
  loadPublicOnlineOrderingConfig,
} from "@/lib/onlineOrderingServer";

export const runtime = "nodejs";

/** Public menu: items with `channels.online === true` and categories for grouping. */
export async function GET() {
  try {
    getFirebaseAdminApp();
    const db = admin.firestore();
    const cfg = await loadPublicOnlineOrderingConfig(db);
    if (!cfg.enabled) {
      return NextResponse.json({ error: "Online ordering is disabled." }, { status: 403 });
    }
    const menu = await loadOnlineMenu(db);
    return NextResponse.json(menu, {
      headers: { "Cache-Control": "no-store" },
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error("[online-ordering/menu]", msg);
    return NextResponse.json(
      { error: "Could not load menu.", detail: msg },
      { status: 500 }
    );
  }
}
