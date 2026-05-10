import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp, verifyIdToken } from "@/lib/firebaseAdmin";
import {
  normalizeMerchantEmail,
  passwordResetContinueSettings,
  sendMerchantWelcomeEmail,
} from "@/lib/merchantWelcomeEmail";

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
  console.error("[welcome-email]", e);
  return NextResponse.json({ ok: false, error: "server_error" }, { status: 500 });
}

/** Resend welcome / set-password email to the merchant owner (current email on Merchants doc). */
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    await requireSuperAdmin(req);
    const { id } = await params;
    getFirebaseAdminApp();
    const db = admin.firestore();
    const authAdmin = admin.auth();

    const snap = await db.collection("Merchants").doc(id).get();
    if (!snap.exists) {
      return NextResponse.json({ ok: false, error: "not_found" }, { status: 404 });
    }

    const businessName = String(snap.get("businessName") ?? "").trim() || "MaxiPay";
    const email = normalizeMerchantEmail(snap.get("email"));
    if (!email) {
      return NextResponse.json(
        { ok: false, message: "This merchant has no valid email on file." },
        { status: 400 }
      );
    }

    let emailSent = false;
    let emailHint: string | undefined;
    try {
      const settings = passwordResetContinueSettings();
      const resetLink = await authAdmin.generatePasswordResetLink(
        email,
        settings ? { url: settings.url } : undefined
      );
      const sendResult = await sendMerchantWelcomeEmail(email, businessName, resetLink);
      emailSent = sendResult.ok;
      if (!sendResult.ok) {
        console.error("[welcome-email] Resend:", sendResult.message);
        emailHint = sendResult.message.slice(0, 400);
      }
    } catch (emailErr) {
      const msg = emailErr instanceof Error ? emailErr.message : String(emailErr);
      console.error("[welcome-email]", emailErr);
      emailHint = msg.includes("OPERATION_NOT_ALLOWED")
        ? "Firebase Authentication: enable Email/Password and password reset for this project."
        : msg.slice(0, 300);
    }

    return NextResponse.json({
      ok: true,
      emailSent,
      ...(emailHint ? { emailHint } : {}),
    });
  } catch (e) {
    return handleError(e);
  }
}
