import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp, verifyIdToken } from "@/lib/firebaseAdmin";

export const runtime = "nodejs";

interface PaymentInput {
  provider?: string;
  deviceModel?: string;
  terminalName?: string;
  tpn?: string;
  registerId?: string;
  authKey?: string;
  iposTransactAuthToken?: string;
}

interface CreateMerchantBody {
  merchantNumber?: string;
  businessName?: string;
  ownerFirstName?: string;
  ownerLastName?: string;
  email?: string;
  phone?: string;
  address?: {
    street?: string;
    city?: string;
    state?: string;
    zip?: string;
  };
  payment?: PaymentInput;
}

async function requireSuperAdmin(req: Request): Promise<admin.auth.DecodedIdToken> {
  const decoded = await verifyIdToken(req.headers.get("authorization"));
  if (decoded.role !== "super_admin") throw new Error("Forbidden");
  return decoded;
}

function normalizeEmail(email: unknown): string | null {
  if (typeof email !== "string") return null;
  const t = email.trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(t)) return null;
  return t;
}

// ---------------------------------------------------------------------------
// GET — list all merchants
// ---------------------------------------------------------------------------

export async function GET(req: Request) {
  try {
    await requireSuperAdmin(req);
    getFirebaseAdminApp();
    const dbAdmin = admin.firestore();

    const snap = await dbAdmin
      .collection("Merchants")
      .orderBy("createdAt", "desc")
      .get();

    const merchants = snap.docs.map((doc) => ({
      id: doc.id,
      ...doc.data(),
    }));

    return NextResponse.json({ ok: true, merchants });
  } catch (e) {
    return handleError(e);
  }
}

// ---------------------------------------------------------------------------
// POST — create a new merchant
// ---------------------------------------------------------------------------

export async function POST(req: Request) {
  try {
    const decoded = await requireSuperAdmin(req);
    const body = (await req.json().catch(() => ({}))) as CreateMerchantBody;

    const email = normalizeEmail(body.email);
    if (!email) {
      return NextResponse.json(
        { ok: false, error: "invalid_email", message: "A valid email is required." },
        { status: 400 }
      );
    }

    const merchantNumber = body.merchantNumber?.trim();
    if (!merchantNumber) {
      return NextResponse.json(
        { ok: false, error: "missing_field", message: "Merchant # is required." },
        { status: 400 }
      );
    }

    const businessName = body.businessName?.trim();
    if (!businessName) {
      return NextResponse.json(
        { ok: false, error: "missing_field", message: "Business name is required." },
        { status: 400 }
      );
    }

    getFirebaseAdminApp();
    const dbAdmin = admin.firestore();
    const authAdmin = admin.auth();

    const existing = await dbAdmin
      .collection("Merchants")
      .where("email", "==", email)
      .limit(1)
      .get();

    if (!existing.empty) {
      return NextResponse.json(
        { ok: false, error: "duplicate", message: "A merchant with this email already exists." },
        { status: 409 }
      );
    }

    const dupNumber = await dbAdmin
      .collection("Merchants")
      .where("merchantNumber", "==", merchantNumber)
      .limit(1)
      .get();

    if (!dupNumber.empty) {
      return NextResponse.json(
        {
          ok: false,
          error: "duplicate_merchant_number",
          message: "That Merchant # is already in use. Choose a different number.",
        },
        { status: 409 }
      );
    }

    const merchantRef = dbAdmin.collection("Merchants").doc();
    const merchantId = merchantRef.id;

    await merchantRef.set({
      merchantNumber,
      businessName,
      ownerFirstName: body.ownerFirstName?.trim() || "",
      ownerLastName: body.ownerLastName?.trim() || "",
      email,
      phone: body.phone?.trim() || "",
      address: {
        street: body.address?.street?.trim() || "",
        city: body.address?.city?.trim() || "",
        state: body.address?.state?.trim() || "",
        zip: body.address?.zip?.trim() || "",
      },
      status: "active",
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      createdBy: decoded.uid,
    });

    const pay = body.payment;
    const hasTpn = !!pay?.tpn?.trim();
    if (hasTpn) {
      const provider = pay!.provider === "SPIN_P" ? "SPIN_P" : "SPIN_Z";
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

      const terminalConfig: Record<string, string> = {
        tpn: pay!.tpn!.trim(),
        registerId: pay!.registerId?.trim() || "",
        authKey: pay!.authKey?.trim() || "",
      };
      if (isP && pay!.iposTransactAuthToken?.trim()) {
        terminalConfig.iposTransactAuthToken = pay!.iposTransactAuthToken!.trim();
      }

      await dbAdmin.collection("payment_terminals").add({
        merchantId,
        name: pay!.terminalName?.trim() || `${businessName} Terminal`,
        provider,
        deviceModel: pay!.deviceModel?.trim() || "",
        active: true,
        baseUrl,
        endpoints,
        capabilities,
        config: terminalConfig,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    }

    let authUser: admin.auth.UserRecord;
    let createdNewUser = false;
    try {
      authUser = await authAdmin.getUserByEmail(email);
    } catch (e: unknown) {
      const code =
        e && typeof e === "object" && "code" in e
          ? String((e as { code: string }).code)
          : "";
      if (code === "auth/user-not-found") {
        authUser = await authAdmin.createUser({
          email,
          emailVerified: false,
          displayName:
            `${body.ownerFirstName?.trim() || ""} ${body.ownerLastName?.trim() || ""}`.trim() ||
            businessName,
        });
        createdNewUser = true;
      } else {
        throw e;
      }
    }

    await authAdmin.setCustomUserClaims(authUser.uid, {
      role: "merchant_owner",
      merchantId,
    });

    let emailSent = false;
    let emailHint: string | undefined;
    try {
      const appOrigin = process.env.NEXT_PUBLIC_APP_URL?.replace(/\/$/, "") ?? "";
      const continueUrl = appOrigin ? `${appOrigin}/login` : undefined;
      const settings = continueUrl ? { url: continueUrl } : undefined;
      const resetLink = await authAdmin.generatePasswordResetLink(email, settings);
      const sendResult = await sendWelcomeEmail(email, businessName, resetLink);
      emailSent = sendResult.ok;
      if (!sendResult.ok) {
        console.error("[merchants] SendGrid:", sendResult.message);
        emailHint = sendResult.message.slice(0, 400);
      }
    } catch (emailErr) {
      const msg = emailErr instanceof Error ? emailErr.message : String(emailErr);
      console.error("[merchants] Email error:", emailErr);
      emailHint = msg.includes("OPERATION_NOT_ALLOWED")
        ? "Firebase Authentication: enable Email/Password and password reset for this project."
        : `Password reset link or email failed: ${msg.slice(0, 300)}`;
    }

    return NextResponse.json({
      ok: true,
      merchantId,
      authUid: authUser.uid,
      createdNewUser,
      emailSent,
      ...(emailHint ? { emailHint } : {}),
    });
  } catch (e) {
    return handleError(e);
  }
}

// ---------------------------------------------------------------------------
// SendGrid welcome email
// ---------------------------------------------------------------------------

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

async function sendWelcomeEmail(
  to: string,
  businessName: string,
  resetLink: string
): Promise<{ ok: true } | { ok: false; message: string }> {
  const apiKey = process.env.SENDGRID_API_KEY;
  if (!apiKey) return { ok: false, message: "SENDGRID_API_KEY not configured." };

  const fromEmail = process.env.SENDGRID_FROM_EMAIL || "noreply@maxipaypos.com";

  const html = `<!DOCTYPE html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"></head>
<body style="margin:0;padding:0;background-color:#f4f4f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f4f4f7;padding:40px 0;">
    <tr><td align="center">
      <table role="presentation" width="520" cellpadding="0" cellspacing="0" style="max-width:520px;width:100%;background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.06);">
        <tr><td style="background:linear-gradient(135deg,#1e293b 0%,#334155 100%);padding:32px 32px 24px;text-align:center;">
          <h1 style="margin:0;color:#ffffff;font-size:22px;font-weight:700;">Welcome to MaxiPay</h1>
        </td></tr>
        <tr><td style="padding:36px 32px 20px;">
          <h2 style="margin:0 0 16px;font-size:20px;font-weight:600;color:#1e293b;">Your Merchant Account is Ready</h2>
          <p style="margin:0 0 14px;font-size:15px;line-height:1.6;color:#475569;">
            A MaxiPay merchant account has been created for <strong>${esc(businessName)}</strong>.
          </p>
          <p style="margin:0 0 28px;font-size:15px;line-height:1.6;color:#475569;">
            Click the button below to set your password and access the dashboard:
          </p>
          <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 auto 28px;">
            <tr><td style="border-radius:8px;background-color:#2563eb;">
              <a href="${esc(resetLink)}" target="_blank"
                 style="display:inline-block;padding:14px 36px;font-size:16px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;">
                Set Password
              </a>
            </td></tr>
          </table>
          <p style="margin:0 0 8px;font-size:13px;line-height:1.5;color:#94a3b8;">
            If the button doesn't work, copy and paste this link:
          </p>
          <p style="margin:0 0 24px;font-size:13px;line-height:1.5;color:#2563eb;word-break:break-all;">${esc(resetLink)}</p>
        </td></tr>
        <tr><td style="padding:20px 32px 28px;text-align:center;">
          <p style="margin:0;font-size:12px;color:#94a3b8;">&mdash; The MaxiPay Team</p>
        </td></tr>
      </table>
    </td></tr>
  </table>
</body>
</html>`;

  const res = await fetch("https://api.sendgrid.com/v3/mail/send", {
    method: "POST",
    headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      personalizations: [{ to: [{ email: to }] }],
      from: { email: fromEmail, name: "MaxiPay" },
      subject: `Welcome to MaxiPay — ${businessName}`,
      content: [{ type: "text/html", value: html }],
    }),
  });

  if (res.status >= 200 && res.status < 300) return { ok: true };
  const body = await res.text().catch(() => "");
  return { ok: false, message: `SendGrid ${res.status}: ${body.slice(0, 300)}` };
}

// ---------------------------------------------------------------------------
// Error handler
// ---------------------------------------------------------------------------

function handleError(e: unknown) {
  const msg = e instanceof Error ? e.message : String(e);
  if (msg === "Unauthorized") {
    return NextResponse.json({ ok: false, error: "unauthorized", message: "Sign in first." }, { status: 401 });
  }
  if (msg === "Forbidden") {
    return NextResponse.json({ ok: false, error: "forbidden", message: "Super admin access required." }, { status: 403 });
  }
  console.error("[merchants]", e);
  return NextResponse.json({ ok: false, error: "server_error", message: "Something went wrong." }, { status: 500 });
}
