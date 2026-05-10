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
  console.error("[terminals]", e);
  return NextResponse.json({ ok: false, error: "server_error" }, { status: 500 });
}

// POST — add terminal to merchant
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    await requireSuperAdmin(req);
    const { id: merchantId } = await params;
    const body = (await req.json().catch(() => ({}))) as Record<string, string>;
    getFirebaseAdminApp();
    const db = admin.firestore();

    const merchantDoc = await db.collection("Merchants").doc(merchantId).get();
    if (!merchantDoc.exists) {
      return NextResponse.json({ ok: false, error: "merchant_not_found" }, { status: 404 });
    }

    const tpn = body.tpn?.trim();
    if (!tpn) {
      return NextResponse.json({ ok: false, error: "missing_tpn" }, { status: 400 });
    }

    const provider = body.provider === "SPIN_P" ? "SPIN_P" : "SPIN_Z";
    const isP = provider === "SPIN_P";
    const baseUrl = "https://spinpos.net/v2";
    const endpoints = {
      auth: "/Payment/Auth",
      capture: "/Payment/Capture",
      tipAdjust: "/Payment/TipAdjust",
      sale: "/Payment/Sale",
      void: "/Payment/Void",
      refund: "/Payment/Return",
      settle: "/Payment/Settle",
      status: "/Payment/Status",
    };
    const capabilities = {
      supportsPreAuth: true,
      supportsCapture: true,
      supportsTipAdjust: true,
      supportsSale: true,
      supportsVoid: true,
      supportsRefund: isP,
      supportsSettle: true,
      supportsStatusCheck: true,
    };

    const config: Record<string, string> = {
      tpn,
      registerId: body.registerId?.trim() || "",
      authKey: body.authKey?.trim() || "",
    };
    if (isP && body.iposTransactAuthToken?.trim()) {
      config.iposTransactAuthToken = body.iposTransactAuthToken.trim();
    }

    const businessName = (merchantDoc.data()?.businessName as string) || "Terminal";
    const ref = await db
      .collection("Merchants")
      .doc(merchantId)
      .collection("payment_terminals")
      .add({
      name: body.terminalName?.trim() || `${businessName} Terminal`,
      provider,
      deviceModel: body.deviceModel?.trim() || "",
      active: true,
      baseUrl,
      endpoints,
      capabilities,
      config,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    return NextResponse.json({ ok: true, terminalId: ref.id });
  } catch (e) {
    return handleError(e);
  }
}

// PATCH — update terminal (toggle active, rotate credentials)
export async function PATCH(req: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    await requireSuperAdmin(req);
    const { id: merchantId } = await params;
    const body = (await req.json().catch(() => ({}))) as Record<string, unknown>;
    getFirebaseAdminApp();
    const db = admin.firestore();

    const terminalId = body.terminalId as string;
    if (!terminalId) {
      return NextResponse.json({ ok: false, error: "missing_terminal_id" }, { status: 400 });
    }

    const termRef = db
      .collection("Merchants")
      .doc(merchantId)
      .collection("payment_terminals")
      .doc(terminalId);
    const termDoc = await termRef.get();
    if (!termDoc.exists) {
      return NextResponse.json({ ok: false, error: "not_found" }, { status: 404 });
    }

    const updates: Record<string, unknown> = {};

    if (typeof body.active === "boolean") updates.active = body.active;
    if (typeof body.name === "string") updates.name = (body.name as string).trim();

    if (body.config && typeof body.config === "object") {
      const c = body.config as Record<string, string>;
      const existing = (termDoc.data()?.config as Record<string, string>) || {};
      const merged = { ...existing };
      if (typeof c.tpn === "string") merged.tpn = c.tpn.trim();
      if (typeof c.registerId === "string") merged.registerId = c.registerId.trim();
      if (typeof c.authKey === "string") merged.authKey = c.authKey.trim();
      if (typeof c.iposTransactAuthToken === "string")
        merged.iposTransactAuthToken = c.iposTransactAuthToken.trim();
      updates.config = merged;
    }

    if (Object.keys(updates).length === 0) {
      return NextResponse.json({ ok: false, error: "no_changes" }, { status: 400 });
    }

    updates.updatedAt = admin.firestore.FieldValue.serverTimestamp();
    await termRef.update(updates);

    return NextResponse.json({ ok: true });
  } catch (e) {
    return handleError(e);
  }
}

// DELETE — remove terminal
export async function DELETE(req: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    await requireSuperAdmin(req);
    const { id: merchantId } = await params;
    const { searchParams } = new URL(req.url);
    const terminalId = searchParams.get("terminalId");
    if (!terminalId) {
      return NextResponse.json({ ok: false, error: "missing_terminal_id" }, { status: 400 });
    }

    getFirebaseAdminApp();
    const db = admin.firestore();
    const termRef = db
      .collection("Merchants")
      .doc(merchantId)
      .collection("payment_terminals")
      .doc(terminalId);
    const termDoc = await termRef.get();
    if (!termDoc.exists) {
      return NextResponse.json({ ok: false, error: "not_found" }, { status: 404 });
    }

    await termRef.delete();
    return NextResponse.json({ ok: true });
  } catch (e) {
    return handleError(e);
  }
}
