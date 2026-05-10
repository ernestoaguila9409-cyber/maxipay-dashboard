import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp, verifyIdToken } from "@/lib/firebaseAdmin";
import { normalizeMerchantEmail } from "@/lib/merchantWelcomeEmail";

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
  console.error("[merchants/[id]/employees/set-password]", e);
  return NextResponse.json({ ok: false, error: "server_error", message: msg }, { status: 500 });
}

function validatePassword(pw: string): string | null {
  const t = pw.trim();
  if (t.length < 6) return "Password must be at least 6 characters (Firebase minimum).";
  if (t.length > 128) return "Password is too long.";
  return null;
}

type Body = {
  target?: string;
  employeeId?: string;
  newPassword?: string;
};

/**
 * POST — super admin sets Firebase Auth password for merchant owner or an employee (by email).
 */
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    await requireSuperAdmin(req);
    const { id: merchantId } = await params;
    const body = (await req.json().catch(() => ({}))) as Body;
    const target = body.target === "employee" ? "employee" : "owner";
    const newPassword = typeof body.newPassword === "string" ? body.newPassword : "";
    const err = validatePassword(newPassword);
    if (err) {
      return NextResponse.json({ ok: false, message: err }, { status: 400 });
    }

    getFirebaseAdminApp();
    const db = admin.firestore();
    const auth = admin.auth();

    const mref = db.collection("Merchants").doc(merchantId);
    const msnap = await mref.get();
    if (!msnap.exists) {
      return NextResponse.json({ ok: false, error: "not_found" }, { status: 404 });
    }
    const m = msnap.data() as Record<string, unknown>;

    let uidToUpdate: string;

    if (target === "owner") {
      const ownerUidRaw = m.ownerAuthUid;
      const ownerUid =
        typeof ownerUidRaw === "string" && ownerUidRaw.trim() ? ownerUidRaw.trim() : "";
      const ownerEmail = normalizeMerchantEmail(m.email);
      if (!ownerUid && !ownerEmail) {
        return NextResponse.json(
          { ok: false, message: "Merchant has no ownerAuthUid or email on file." },
          { status: 400 }
        );
      }
      let u: admin.auth.UserRecord;
      if (ownerUid) {
        u = await auth.getUser(ownerUid);
      } else {
        u = await auth.getUserByEmail(ownerEmail!);
      }
      const role = (u.customClaims as Record<string, unknown> | undefined)?.role;
      if (role === "super_admin") {
        return NextResponse.json(
          { ok: false, message: "Cannot set password for a super admin account." },
          { status: 403 }
        );
      }
      uidToUpdate = u.uid;
    } else {
      const employeeId = typeof body.employeeId === "string" ? body.employeeId.trim() : "";
      if (!employeeId) {
        return NextResponse.json(
          { ok: false, message: "employeeId is required when target is employee." },
          { status: 400 }
        );
      }
      const esnap = await mref.collection("employees").doc(employeeId).get();
      if (!esnap.exists) {
        return NextResponse.json({ ok: false, message: "Employee not found." }, { status: 404 });
      }
      const ed = esnap.data() as Record<string, unknown>;
      const emailNorm = normalizeMerchantEmail(ed.email);
      if (!emailNorm) {
        return NextResponse.json(
          { ok: false, message: "This employee has no email on file; add an email before setting a password." },
          { status: 400 }
        );
      }
      const ownerEmailNorm = normalizeMerchantEmail(m.email);
      if (ownerEmailNorm && emailNorm === ownerEmailNorm) {
        return NextResponse.json(
          {
            ok: false,
            message:
              "This email is the merchant owner. Use the Owner row → Set password so the correct account is updated.",
          },
          { status: 400 }
        );
      }
      const name = typeof ed.name === "string" ? ed.name.trim() : "Staff";
      try {
        const existing = await auth.getUserByEmail(emailNorm);
        uidToUpdate = existing.uid;
        const role = (existing.customClaims as Record<string, unknown> | undefined)?.role;
        if (role === "super_admin") {
          return NextResponse.json(
            { ok: false, message: "That email belongs to a super admin account." },
            { status: 403 }
          );
        }
      } catch (e: unknown) {
        const code =
          e && typeof e === "object" && "code" in e ? String((e as { code: string }).code) : "";
        if (code !== "auth/user-not-found") throw e;
        const created = await auth.createUser({
          email: emailNorm,
          password: newPassword.trim(),
          emailVerified: false,
          displayName: name || emailNorm,
        });
        uidToUpdate = created.uid;
        return NextResponse.json({ ok: true, uid: uidToUpdate, createdAuthUser: true });
      }
    }

    await auth.updateUser(uidToUpdate, { password: newPassword.trim() });
    return NextResponse.json({ ok: true, uid: uidToUpdate, createdAuthUser: false });
  } catch (e) {
    return handleError(e);
  }
}
