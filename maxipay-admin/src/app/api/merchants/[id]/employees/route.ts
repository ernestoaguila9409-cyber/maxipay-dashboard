import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp, verifyIdToken } from "@/lib/firebaseAdmin";
import { normalizeMerchantEmail } from "@/lib/merchantWelcomeEmail";
import {
  passwordStatusFromUserRecord,
  passwordStatusLabel,
  type PasswordStatus,
} from "@/lib/authPasswordStatus";

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
  console.error("[merchants/[id]/employees]", e);
  return NextResponse.json({ ok: false, error: "server_error" }, { status: 500 });
}

async function loadAuthUser(
  auth: admin.auth.Auth,
  opts: { uid?: string; email?: string | null }
): Promise<admin.auth.UserRecord | null> {
  if (opts.uid?.trim()) {
    try {
      return await auth.getUser(opts.uid.trim());
    } catch (e: unknown) {
      const code =
        e && typeof e === "object" && "code" in e ? String((e as { code: string }).code) : "";
      if (code === "auth/user-not-found") return null;
      throw e;
    }
  }
  const em = normalizeMerchantEmail(opts.email ?? "");
  if (!em) return null;
  try {
    return await auth.getUserByEmail(em);
  } catch (e: unknown) {
    const code =
      e && typeof e === "object" && "code" in e ? String((e as { code: string }).code) : "";
    if (code === "auth/user-not-found") return null;
    throw e;
  }
}

type EmployeeRow = {
  kind: "owner" | "employee";
  id: string | null;
  name: string;
  email: string;
  passwordStatus: PasswordStatus;
  passwordStatusLabel: string;
  pin: string | null;
  role: string | null;
  active: boolean | null;
  authUid: string | null;
};

// GET — list owner + employees with password status (super admin only)
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    await requireSuperAdmin(req);
    const { id: merchantId } = await params;
    getFirebaseAdminApp();
    const db = admin.firestore();
    const auth = admin.auth();

    const mref = db.collection("Merchants").doc(merchantId);
    const msnap = await mref.get();
    if (!msnap.exists) {
      return NextResponse.json({ ok: false, error: "not_found" }, { status: 404 });
    }
    const m = msnap.data() as Record<string, unknown>;
    const ownerFirst =
      typeof m.ownerFirstName === "string" ? m.ownerFirstName.trim() : "";
    const ownerLast =
      typeof m.ownerLastName === "string" ? m.ownerLastName.trim() : "";
    const biz = typeof m.businessName === "string" ? m.businessName.trim() : "";
    const ownerName =
      [ownerFirst, ownerLast].filter(Boolean).join(" ").trim() || biz || "Owner";
    const ownerEmail = normalizeMerchantEmail(m.email) ?? "";
    const ownerUidRaw = m.ownerAuthUid;
    const ownerUid =
      typeof ownerUidRaw === "string" && ownerUidRaw.trim() ? ownerUidRaw.trim() : "";

    const ownerUser = await loadAuthUser(auth, { uid: ownerUid || undefined, email: ownerEmail });
    const ownerPw = passwordStatusFromUserRecord(ownerUser);
    const rows: EmployeeRow[] = [
      {
        kind: "owner",
        id: null,
        name: ownerName,
        email: ownerEmail,
        passwordStatus: ownerPw,
        passwordStatusLabel: passwordStatusLabel(ownerPw),
        pin: null,
        role: "merchant_owner",
        active: true,
        authUid: ownerUser?.uid ?? (ownerUid || null),
      },
    ];

    const empSnap = await mref.collection("employees").get();
    const empDocs = [...empSnap.docs].sort((a, b) => {
      const na = String((a.data() as Record<string, unknown>).name ?? "").toLowerCase();
      const nb = String((b.data() as Record<string, unknown>).name ?? "").toLowerCase();
      return na.localeCompare(nb);
    });
    for (const d of empDocs) {
      const data = d.data() as Record<string, unknown>;
      const name = typeof data.name === "string" ? data.name.trim() : "Unknown";
      const emailNorm = normalizeMerchantEmail(data.email);
      const email = emailNorm ?? "";
      const pin = typeof data.pin === "string" && data.pin.trim() ? data.pin.trim() : null;
      const role = typeof data.role === "string" ? data.role : null;
      const active = typeof data.active === "boolean" ? data.active : null;
      const empUser = emailNorm ? await loadAuthUser(auth, { email: emailNorm }) : null;
      const pw = passwordStatusFromUserRecord(empUser);
      rows.push({
        kind: "employee",
        id: d.id,
        name,
        email,
        passwordStatus: pw,
        passwordStatusLabel: passwordStatusLabel(pw),
        pin,
        role,
        active,
        authUid: empUser?.uid ?? null,
      });
    }

    return NextResponse.json({ ok: true, rows });
  } catch (e) {
    return handleError(e);
  }
}
