/** Shared merchant welcome / password-reset email (Resend). */

import { sendEmailViaResend } from "@/lib/resendEmail";

/** Narrow shape of Firebase `UserRecord` for branching without importing admin in tests. */
export type UserRecordLike = {
  providerData: { providerId: string }[];
  metadata: { creationTime: string; lastSignInTime?: string };
};

/**
 * True when this account likely already signs in with email/password (no first-time
 * password setup). Used to skip `generatePasswordResetLink` on merchant create.
 *
 * - Non–email/password providers (e.g. Google-only): true (do not send password-reset flow).
 * - Email/password: true only if `lastSignInTime` is clearly after `creationTime` (signed in at least once).
 */
export function authUserLikelyHasPasswordSignIn(user: UserRecordLike): boolean {
  const hasPasswordProvider = user.providerData?.some(
    (p) => p.providerId === "password"
  );
  if (!hasPasswordProvider) {
    return true;
  }
  const c = user.metadata?.creationTime;
  const l = user.metadata?.lastSignInTime;
  if (!c || !l) return false;
  const ct = new Date(c).getTime();
  const lt = new Date(l).getTime();
  if (!Number.isFinite(ct) || !Number.isFinite(lt)) return false;
  return lt > ct + 1000;
}

export function normalizeMerchantEmail(email: unknown): string | null {
  if (typeof email !== "string") return null;
  const t = email.trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(t)) return null;
  return t;
}

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

export async function sendMerchantWelcomeEmail(
  to: string,
  businessName: string,
  resetLink: string
): Promise<{ ok: true } | { ok: false; message: string }> {
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

  return sendEmailViaResend({
    to,
    subject: `Welcome to MaxiPay — ${businessName}`,
    html,
  });
}

/** No reset link — account already has a password; new merchant linked to same login. */
export async function sendMerchantCreatedConfirmationEmail(
  to: string,
  businessName: string
): Promise<{ ok: true } | { ok: false; message: string }> {
  const origin = merchantDashboardOrigin();
  const loginUrl = origin ? `${origin}/login` : null;
  const loginBlock = loginUrl
    ? `<p style="margin:0 0 28px;font-size:15px;line-height:1.6;color:#475569;">
         You can sign in to the merchant dashboard with your existing password:
       </p>
       <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 auto 28px;">
         <tr><td style="border-radius:8px;background-color:#2563eb;">
           <a href="${esc(loginUrl)}" target="_blank"
              style="display:inline-block;padding:14px 36px;font-size:16px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;">
             Open dashboard
           </a>
         </td></tr>
       </table>
       <p style="margin:0 0 8px;font-size:13px;line-height:1.5;color:#94a3b8;">
         If the button doesn&apos;t work, copy and paste:
       </p>
       <p style="margin:0 0 24px;font-size:13px;line-height:1.5;color:#2563eb;word-break:break-all;">${esc(loginUrl)}</p>`
    : `<p style="margin:0 0 24px;font-size:15px;line-height:1.6;color:#475569;">
         Sign in to your merchant dashboard using your existing MaxiPay password.
       </p>`;

  const html = `<!DOCTYPE html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"></head>
<body style="margin:0;padding:0;background-color:#f4f4f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f4f4f7;padding:40px 0;">
    <tr><td align="center">
      <table role="presentation" width="520" cellpadding="0" cellspacing="0" style="max-width:520px;width:100%;background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.06);">
        <tr><td style="background:linear-gradient(135deg,#1e293b 0%,#334155 100%);padding:32px 32px 24px;text-align:center;">
          <h1 style="margin:0;color:#ffffff;font-size:22px;font-weight:700;">MaxiPay</h1>
        </td></tr>
        <tr><td style="padding:36px 32px 20px;">
          <h2 style="margin:0 0 16px;font-size:20px;font-weight:600;color:#1e293b;">New merchant created</h2>
          <p style="margin:0 0 14px;font-size:15px;line-height:1.6;color:#475569;">
            We&apos;ve successfully created a new MaxiPay merchant for <strong>${esc(businessName)}</strong>
            and linked it to your account.
          </p>
          ${loginBlock}
        </td></tr>
        <tr><td style="padding:20px 32px 28px;text-align:center;">
          <p style="margin:0;font-size:12px;color:#94a3b8;">&mdash; The MaxiPay Team</p>
        </td></tr>
      </table>
    </td></tr>
  </table>
</body>
</html>`;

  return sendEmailViaResend({
    to,
    subject: `New MaxiPay merchant — ${businessName}`,
    html,
  });
}

function merchantDashboardOrigin(): string {
  const raw =
    process.env.MERCHANT_WEB_APP_URL?.trim() ||
    process.env.NEXT_PUBLIC_MERCHANT_WEB_APP_URL?.trim() ||
    "";
  return raw.replace(/\/$/, "");
}

/**
 * Firebase password-reset "continue" URL: after the merchant sets a password on
 * the branded dashboard page (`/auth/reset-password`), they are redirected here.
 * Must be HTTPS and listed under Firebase Console → Authentication → Settings →
 * Authorized domains.
 *
 * Use the **merchant web dashboard** origin (e.g. https://dashboard.maxipaypos.com),
 * not the admin portal URL.
 *
 * **Password reset email link:** In Firebase Console → Authentication → Templates →
 * Password reset → "Customize action URL", set the action URL to:
 * `{MERCHANT_WEB_APP_URL}/auth/reset-password` (same origin as here, no trailing slash
 * before the path). Until that is set, links may still open the default
 * `*.firebaseapp.com` handler.
 */
export function passwordResetContinueSettings(): { url: string } | undefined {
  const origin = merchantDashboardOrigin();
  if (!origin) return undefined;
  return { url: `${origin}/login` };
}
