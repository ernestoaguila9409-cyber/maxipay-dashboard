/** Shared merchant welcome / password-reset email (SendGrid). */

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
  return { ok: false, message: friendlySendGridError(res.status, body) };
}

/** Turns SendGrid JSON errors into short, actionable text for admins. */
export function friendlySendGridError(status: number, body: string): string {
  let firstMsg = "";
  try {
    const j = JSON.parse(body) as { errors?: { message?: string }[] };
    firstMsg = (j.errors?.[0]?.message ?? "").trim();
  } catch {
    /* ignore */
  }

  if (firstMsg === "Maximum credits exceeded") {
    return (
      "SendGrid: your plan has no email credits left (free tier limit reached). " +
      "Log in at sendgrid.com → Billing / upgrade the plan, or attach a new API key with credits in Vercel (admin app)."
    );
  }
  if (/Maximum credits exceeded/i.test(body)) {
    return (
      "SendGrid: email credits exhausted. Upgrade SendGrid or use an API key from an account with available sends."
    );
  }
  if (status === 401 && firstMsg.toLowerCase().includes("authorization")) {
    return "SendGrid: invalid API key (401). Check SENDGRID_API_KEY in Vercel for the admin app.";
  }
  if (status === 403 && /verified Sender Identity|sender/i.test(body)) {
    return "SendGrid: verify SENDGRID_FROM_EMAIL as a sender in SendGrid (Sender Authentication).";
  }

  const snippet = body.replace(/\s+/g, " ").trim().slice(0, 220);
  return snippet ? `SendGrid ${status}: ${snippet}` : `SendGrid returned HTTP ${status}.`;
}

export function passwordResetContinueSettings(): { url: string } | undefined {
  const appOrigin = process.env.NEXT_PUBLIC_APP_URL?.replace(/\/$/, "") ?? "";
  if (!appOrigin) return undefined;
  return { url: `${appOrigin}/login` };
}
