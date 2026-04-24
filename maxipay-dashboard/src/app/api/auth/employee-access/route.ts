import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp } from "@/lib/firebaseAdmin";

export const runtime = "nodejs";

const PUBLIC_MESSAGE =
  "If that exact email is saved on your employee profile, you should receive a message within a few minutes with instructions to set your password. Check spam and junk folders. If nothing arrives, ask an administrator to confirm your profile email matches what you entered (including spelling).";

const RATE_WINDOW_MS = 15 * 60 * 1000;
const RATE_MAX = 8;
const rateBuckets = new Map<string, number[]>();

function getClientIp(req: Request): string {
  const xf = req.headers.get("x-forwarded-for");
  if (xf) {
    const first = xf.split(",")[0]?.trim();
    if (first) return first;
  }
  return req.headers.get("x-real-ip")?.trim() || "unknown";
}

function allowRate(ip: string): boolean {
  const now = Date.now();
  const prev = rateBuckets.get(ip) ?? [];
  const windowed = prev.filter((t) => now - t < RATE_WINDOW_MS);
  if (windowed.length >= RATE_MAX) return false;
  windowed.push(now);
  rateBuckets.set(ip, windowed);
  return true;
}

function normalizeEmail(email: unknown): string | null {
  if (typeof email !== "string") return null;
  const t = email.trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(t)) return null;
  return t;
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

// ---------------------------------------------------------------------------
// Business info from Firestore (mirrors functions/index.js)
// ---------------------------------------------------------------------------

async function fetchBusinessInfo(
  db: admin.firestore.Firestore
): Promise<Record<string, unknown>> {
  const snap = await db.collection("Settings").doc("businessInfo").get();
  if (!snap.exists) return {};
  return (snap.data() as Record<string, unknown>) ?? {};
}

function resolveLogoUrl(biz: Record<string, unknown>): string | null {
  const raw = typeof biz.logoUrl === "string" ? biz.logoUrl.trim() : "";
  if (!raw) return null;
  const lower = raw.toLowerCase();
  if (lower.includes("your-server-or-storage")) return null;
  if (lower.startsWith("https://") || lower.startsWith("http://")) return raw;
  return null;
}

function esc(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

// ---------------------------------------------------------------------------
// Branded password-reset email HTML
// ---------------------------------------------------------------------------

function composeResetEmailHtml(
  businessName: string,
  logoUrl: string | null,
  resetLink: string
): string {
  const logoBlock = logoUrl
    ? `<img src="${esc(logoUrl)}" alt="${esc(businessName)}"
           style="max-width:140px;height:auto;margin-bottom:14px;display:block;margin-left:auto;margin-right:auto;border:0;outline:none;text-decoration:none;">`
    : "";

  return `<!DOCTYPE html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"></head>
<body style="margin:0;padding:0;background-color:#f4f4f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f4f4f7;padding:40px 0;">
    <tr><td align="center">
      <table role="presentation" width="520" cellpadding="0" cellspacing="0" style="max-width:520px;width:100%;background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.06);">

        <!-- Header -->
        <tr><td style="background:linear-gradient(135deg,#1e293b 0%,#334155 100%);padding:32px 32px 24px;text-align:center;">
          ${logoBlock}
          <h1 style="margin:0;color:#ffffff;font-size:22px;font-weight:700;letter-spacing:0.3px;">${esc(businessName)}</h1>
        </td></tr>

        <!-- Body -->
        <tr><td style="padding:36px 32px 20px;">
          <h2 style="margin:0 0 16px;font-size:20px;font-weight:600;color:#1e293b;">Reset Your Password</h2>
          <p style="margin:0 0 14px;font-size:15px;line-height:1.6;color:#475569;">
            We received a request to reset the password for your account at <strong>${esc(businessName)}</strong>.
          </p>
          <p style="margin:0 0 28px;font-size:15px;line-height:1.6;color:#475569;">
            Click the button below to choose a new password:
          </p>
          <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 auto 28px;">
            <tr><td style="border-radius:8px;background-color:#2563eb;">
              <a href="${esc(resetLink)}"
                 target="_blank"
                 style="display:inline-block;padding:14px 36px;font-size:16px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;">
                Reset Password
              </a>
            </td></tr>
          </table>
          <p style="margin:0 0 8px;font-size:13px;line-height:1.5;color:#94a3b8;">
            If the button doesn't work, copy and paste this link into your browser:
          </p>
          <p style="margin:0 0 24px;font-size:13px;line-height:1.5;color:#2563eb;word-break:break-all;">
            ${esc(resetLink)}
          </p>
          <hr style="border:none;border-top:1px solid #e2e8f0;margin:0 0 20px;">
          <p style="margin:0;font-size:13px;line-height:1.5;color:#94a3b8;">
            If you didn't request a password reset, you can safely ignore this email. Your password will not change.
          </p>
        </td></tr>

        <!-- Footer -->
        <tr><td style="padding:20px 32px 28px;text-align:center;">
          <p style="margin:0;font-size:12px;color:#94a3b8;">
            &mdash; The ${esc(businessName)} Team
          </p>
        </td></tr>

      </table>
    </td></tr>
  </table>
</body>
</html>`;
}

// ---------------------------------------------------------------------------
// SendGrid HTTP API (no extra dependency — uses global fetch)
// ---------------------------------------------------------------------------

type SendResult = { ok: true } | { ok: false; message: string };

async function sendViaSendGrid(
  to: string,
  fromEmail: string,
  fromName: string,
  subject: string,
  html: string
): Promise<SendResult> {
  const apiKey = process.env.SENDGRID_API_KEY;
  if (!apiKey) {
    return {
      ok: false,
      message:
        "SENDGRID_API_KEY is not configured on the dashboard. Add it to your Vercel / hosting environment variables.",
    };
  }

  const res = await fetch("https://api.sendgrid.com/v3/mail/send", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      personalizations: [{ to: [{ email: to }] }],
      from: { email: fromEmail, name: fromName },
      subject,
      content: [{ type: "text/html", value: html }],
    }),
  });

  if (res.status >= 200 && res.status < 300) return { ok: true };

  const body = await res.text().catch(() => "");
  return { ok: false, message: `SendGrid ${res.status}: ${body.slice(0, 300)}` };
}

// ---------------------------------------------------------------------------
// Generate reset link with retries (Admin SDK)
// ---------------------------------------------------------------------------

async function generateResetLinkWithRetries(
  email: string,
  continueUrl: string | undefined
): Promise<{ ok: true; link: string } | { ok: false; message: string }> {
  const auth = admin.auth();
  const settings = continueUrl ? { url: continueUrl } : undefined;

  let lastMsg = "no_attempt";
  for (let i = 0; i < 3; i++) {
    if (i > 0) await sleep(1000);
    try {
      const link = await auth.generatePasswordResetLink(email, settings);
      return { ok: true, link };
    } catch (e: unknown) {
      const msg =
        e && typeof e === "object" && "message" in e
          ? String((e as { message: string }).message)
          : "unknown";
      lastMsg = msg;
      console.error(`[employee-access] generatePasswordResetLink attempt ${i + 1}:`, msg);
      if (msg.includes("OPERATION_NOT_ALLOWED")) break;
    }
  }
  return { ok: false, message: lastMsg };
}

// ---------------------------------------------------------------------------
// POST handler
// ---------------------------------------------------------------------------

/**
 * Ensures a Firebase Auth user exists for this email (create on first use),
 * generates a password-reset link via Admin SDK, then sends a branded email
 * through SendGrid using the merchant's business name from Firestore.
 */
export async function POST(req: Request) {
  const ip = getClientIp(req);
  if (!allowRate(ip)) {
    return NextResponse.json(
      { ok: false, error: "rate_limited", message: "Too many attempts. Try again later." },
      { status: 429 }
    );
  }

  try {
    const body = (await req.json().catch(() => ({}))) as { email?: string };
    const normalized = normalizeEmail(body.email);
    if (!normalized) {
      return NextResponse.json(
        { ok: false, error: "invalid_email", message: "Enter a valid email address." },
        { status: 400 }
      );
    }

    getFirebaseAdminApp();
    const db = admin.firestore();
    const snap = await db
      .collection("Employees")
      .where("email", "==", normalized)
      .limit(2)
      .get();

    if (snap.empty) {
      console.info(
        "[employee-access] No Firestore Employees document with field email =",
        normalized,
        "(password reset is not sent; fix the profile email or spelling.)"
      );
      return NextResponse.json({ ok: true, message: PUBLIC_MESSAGE });
    }

    if (snap.size > 1) {
      console.error(
        "[employee-access] Multiple Employees share email; refusing to send:",
        normalized
      );
      return NextResponse.json({ ok: true, message: PUBLIC_MESSAGE });
    }

    // Ensure Firebase Auth user exists
    const auth = admin.auth();
    let createdAuthUser = false;
    try {
      await auth.getUserByEmail(normalized);
    } catch (e: unknown) {
      const code =
        e && typeof e === "object" && "code" in e
          ? String((e as { code: string }).code)
          : "";
      if (code === "auth/user-not-found") {
        try {
          await auth.createUser({
            email: normalized,
            emailVerified: false,
          });
          createdAuthUser = true;
        } catch (e2: unknown) {
          const c2 =
            e2 && typeof e2 === "object" && "code" in e2
              ? String((e2 as { code: string }).code)
              : "";
          if (c2 !== "auth/email-already-exists") throw e2;
        }
      } else {
        throw e;
      }
    }

    if (createdAuthUser) {
      await sleep(800);
    }

    // Generate password-reset link via Admin SDK
    const appOrigin = process.env.NEXT_PUBLIC_APP_URL?.replace(/\/$/, "") ?? "";
    const continueUrl = appOrigin ? `${appOrigin}/login` : undefined;
    const linkResult = await generateResetLinkWithRetries(normalized, continueUrl);

    if (!linkResult.ok) {
      console.error("[employee-access] Password reset link generation failed:", linkResult.message);
      const userMsg = linkResult.message.includes("OPERATION_NOT_ALLOWED")
        ? "Password sign-in or password reset is disabled in Firebase. An administrator must enable Email/Password in Firebase Authentication."
        : "We could not generate the reset link. Please try again or contact support.";
      return NextResponse.json(
        { ok: false, error: "send_failed", message: userMsg, detail: linkResult.message },
        { status: 503 }
      );
    }

    // Fetch business branding from Firestore
    const biz = await fetchBusinessInfo(db);
    const businessName =
      (typeof biz.businessName === "string" && biz.businessName.trim()) || "MaxiPay";
    const logoUrl = resolveLogoUrl(biz);

    // Compose and send branded email via SendGrid
    const fromEmail =
      process.env.SENDGRID_AUTH_FROM_EMAIL ||
      process.env.SENDGRID_FROM_EMAIL ||
      "noreply@maxipaypos.com";
    const subject = `Reset your password — ${businessName}`;
    const html = composeResetEmailHtml(businessName, logoUrl, linkResult.link);
    const sent = await sendViaSendGrid(normalized, fromEmail, businessName, subject, html);

    if (!sent.ok) {
      console.error("[employee-access] SendGrid error:", sent.message);
      return NextResponse.json(
        {
          ok: false,
          error: "send_failed",
          message: "We could not send the reset email. Please try again or contact support.",
          detail: sent.message,
        },
        { status: 503 }
      );
    }

    return NextResponse.json({ ok: true, message: PUBLIC_MESSAGE });
  } catch (e) {
    console.error("[employee-access]", e);
    return NextResponse.json(
      {
        ok: false,
        error: "server_error",
        message: "Something went wrong. Please try again later.",
      },
      { status: 500 }
    );
  }
}
