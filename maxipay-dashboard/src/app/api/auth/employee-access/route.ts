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

type OobResult = { ok: true } | { ok: false; message: string };

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

/**
 * Triggers Firebase's built-in password-reset email (Identity Toolkit REST).
 * Treats any `error` object in the JSON body as failure — some failures still use HTTP 200.
 */
async function sendPasswordResetOobOnce(email: string): Promise<OobResult> {
  const apiKey = process.env.NEXT_PUBLIC_FIREBASE_API_KEY;
  if (!apiKey) {
    console.error("[employee-access] Missing NEXT_PUBLIC_FIREBASE_API_KEY");
    return { ok: false, message: "missing_api_key" };
  }
  const url = `https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=${encodeURIComponent(apiKey)}`;
  const appOrigin = process.env.NEXT_PUBLIC_APP_URL?.replace(/\/$/, "") ?? "";
  const payload: Record<string, string> = {
    requestType: "PASSWORD_RESET",
    email,
  };
  if (appOrigin) {
    payload.continueUrl = `${appOrigin}/login`;
  }

  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  let data: { error?: { message?: string; status?: string }; email?: string };
  try {
    data = (await res.json()) as typeof data;
  } catch {
    console.error("[employee-access] sendOobCode: invalid JSON body", res.status);
    return { ok: false, message: "invalid_response" };
  }

  if (data.error) {
    const msg = data.error.message ?? "unknown_error";
    console.error("[employee-access] sendOobCode rejected:", msg, data.error);
    return { ok: false, message: msg };
  }

  if (!res.ok) {
    console.error("[employee-access] sendOobCode HTTP", res.status, data);
    return { ok: false, message: `http_${res.status}` };
  }

  return { ok: true };
}

async function sendPasswordResetOobWithRetries(email: string): Promise<OobResult> {
  let last: OobResult = { ok: false, message: "no_attempt" };
  for (let i = 0; i < 3; i++) {
    if (i > 0) await sleep(1000);
    last = await sendPasswordResetOobOnce(email);
    if (last.ok) return last;
    if (last.message === "OPERATION_NOT_ALLOWED") break;
  }
  return last;
}

/**
 * Ensures a Firebase Auth user exists for this email (create on first use),
 * then triggers Firebase's password-reset email so the user can set a password.
 * Only runs when the email exists on exactly one Employees document (normalized).
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

    const sent = await sendPasswordResetOobWithRetries(normalized);
    if (!sent.ok) {
      console.error("[employee-access] Password reset email not sent:", sent.message);
      const userMsg =
        sent.message === "OPERATION_NOT_ALLOWED"
          ? "Password sign-in or password reset is disabled in Firebase. An administrator must enable Email/Password in Firebase Authentication."
          : sent.message === "TOO_MANY_ATTEMPTS_TRY_LATER" ||
              sent.message === "RESET_PASSWORD_EXCEED_LIMIT"
            ? "Too many reset attempts for this email. Wait a while and try again."
            : "We could not send the reset email. Confirm NEXT_PUBLIC_FIREBASE_API_KEY matches this Firebase project, then try again or contact support.";
      return NextResponse.json(
        {
          ok: false,
          error: "send_failed",
          message: userMsg,
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
