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

  let msg = "";
  try {
    const j = JSON.parse(body) as { message?: string };
    msg = (j.message || "").trim();
  } catch {
    /* ignore */
  }
  const snippet = msg || body.replace(/\s+/g, " ").trim().slice(0, 200);
  return { ok: false, message: snippet ? `Resend ${res.status}: ${snippet}` : `Resend returned HTTP ${res.status}.` };
}
