import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp, verifyIdToken } from "@/lib/firebaseAdmin";

export const runtime = "nodejs";

async function requireSuperAdmin(req: Request): Promise<admin.auth.DecodedIdToken> {
  const decoded = await verifyIdToken(req.headers.get("authorization"));
  if (decoded.role !== "super_admin") throw new Error("Forbidden");
  return decoded;
}

function handleError(e: unknown) {
  const msg = e instanceof Error ? e.message : String(e);
  if (msg === "Unauthorized")
    return NextResponse.json({ ok: false, error: "unauthorized" }, { status: 401 });
  if (msg === "Forbidden")
    return NextResponse.json({ ok: false, error: "forbidden" }, { status: 403 });
  console.error("[merchants/[id]]", e);
  return NextResponse.json({ ok: false, error: "server_error" }, { status: 500 });
}

// GET — single merchant + terminals
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    await requireSuperAdmin(req);
    const { id } = await params;
    getFirebaseAdminApp();
    const db = admin.firestore();

    const doc = await db.collection("Merchants").doc(id).get();
    if (!doc.exists) {
      return NextResponse.json({ ok: false, error: "not_found" }, { status: 404 });
    }

    const termSnap = await db
      .collection("payment_terminals")
      .where("merchantId", "==", id)
      .get();

    const terminals = termSnap.docs.map((d) => ({ id: d.id, ...d.data() }));

    return NextResponse.json({ ok: true, merchant: { id: doc.id, ...doc.data() }, terminals });
  } catch (e) {
    return handleError(e);
  }
}

// PATCH — update merchant fields
export async function PATCH(req: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    await requireSuperAdmin(req);
    const { id } = await params;
    const body = (await req.json().catch(() => ({}))) as Record<string, unknown>;
    getFirebaseAdminApp();
    const db = admin.firestore();

    const ref = db.collection("Merchants").doc(id);
    const doc = await ref.get();
    if (!doc.exists) {
      return NextResponse.json({ ok: false, error: "not_found" }, { status: 404 });
    }

    const allowed: Record<string, unknown> = {};
    const stringFields = [
      "businessName",
      "ownerFirstName",
      "ownerLastName",
      "phone",
      "merchantNumber",
    ];
    for (const f of stringFields) {
      if (typeof body[f] === "string") allowed[f] = (body[f] as string).trim();
    }
    if (typeof body.status === "string" && ["active", "suspended", "pending"].includes(body.status as string)) {
      allowed.status = body.status;
    }
    if (body.address && typeof body.address === "object") {
      const a = body.address as Record<string, unknown>;
      allowed.address = {
        street: typeof a.street === "string" ? a.street.trim() : "",
        city: typeof a.city === "string" ? a.city.trim() : "",
        state: typeof a.state === "string" ? a.state.trim() : "",
        zip: typeof a.zip === "string" ? a.zip.trim() : "",
      };
    }

    if (Object.keys(allowed).length === 0) {
      return NextResponse.json({ ok: false, error: "no_changes" }, { status: 400 });
    }

    allowed.updatedAt = admin.firestore.FieldValue.serverTimestamp();
    await ref.update(allowed);

    return NextResponse.json({ ok: true });
  } catch (e) {
    return handleError(e);
  }
}
