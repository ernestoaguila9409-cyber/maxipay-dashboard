import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp, verifyIdToken } from "@/lib/firebaseAdmin";
import {
  normalizeMerchantEmail,
  passwordResetContinueSettings,
  sendMerchantWelcomeEmail,
} from "@/lib/merchantWelcomeEmail";

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

    const email = normalizeMerchantEmail(body.email);
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

    /** Stable link for admin email updates even if custom claims are later overwritten (e.g. same user promoted super_admin). */
    await merchantRef.update({ ownerAuthUid: authUser.uid });

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
        console.error("[merchants] Resend:", sendResult.message);
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
