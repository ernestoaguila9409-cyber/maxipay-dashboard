/**
 * Send transactional HTML email via Resend HTTP API.
 * https://resend.com/docs/api-reference/emails/send-email
 */

export function getResendFromAddress(): string {
  return (
    process.env.RESEND_FROM_EMAIL?.trim() ||
    "noreply@maxipaypos.com"
  );
}

export async function sendEmailViaResend(params: {
  to: string;
  subject: string;
  html: string;
  /** Optional override; defaults to RESEND_FROM_EMAIL */
  from?: string;
}): Promise<{ ok: true } | { ok: false; message: string }> {
  const apiKey = process.env.RESEND_API_KEY?.trim();
  if (!apiKey) {
    return { ok: false, message: "RESEND_API_KEY is not configured." };
  }

  const from = params.from?.trim() || getResendFromAddress();

  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      from,
      to: [params.to.trim()],
      subject: params.subject,
      html: params.html,
    }),
  });

  const body = await res.text().catch(() => "");
  if (res.status >= 200 && res.status < 300) return { ok: true };

  return { ok: false, message: friendlyResendError(res.status, body) };
}

function friendlyResendError(status: number, body: string): string {
  try {
    const j = JSON.parse(body) as { message?: string; name?: string };
    const msg = (j.message || "").trim();
    if (msg) {
      if (/domain is not verified|not verified/i.test(msg)) {
        return `Resend: ${msg} Add your domain in the Resend dashboard and set RESEND_FROM_EMAIL to a verified address.`;
      }
      return `Resend (${status}): ${msg}`;
    }
  } catch {
    /* ignore */
  }
  const snippet = body.replace(/\s+/g, " ").trim().slice(0, 200);
  return snippet ? `Resend ${status}: ${snippet}` : `Resend returned HTTP ${status}.`;
}
