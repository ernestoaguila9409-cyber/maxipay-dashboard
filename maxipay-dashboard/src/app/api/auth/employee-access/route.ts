import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp } from "@/lib/firebaseAdmin";

export const runtime = "nodejs";

const PUBLIC_MESSAGE =
  "If that email is linked to an employee account, you will receive a message with instructions to set your password.";

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

async function sendPasswordResetOob(email: string): Promise<boolean> {
  const apiKey = process.env.NEXT_PUBLIC_FIREBASE_API_KEY;
  if (!apiKey) {
    console.error("[employee-access] Missing NEXT_PUBLIC_FIREBASE_API_KEY");
    return false;
  }
  const url = `https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=${encodeURIComponent(apiKey)}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      requestType: "PASSWORD_RESET",
      email,
    }),
  });
  const data = (await res.json()) as { error?: { message?: string } };
  if (!res.ok) {
    console.error("[employee-access] sendOobCode failed", data);
    return false;
  }
  return true;
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

    const sent = await sendPasswordResetOob(normalized);
    if (!sent) {
      return NextResponse.json(
        {
          ok: false,
          error: "send_failed",
          message: "We could not send the email right now. Please try again in a few minutes.",
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
