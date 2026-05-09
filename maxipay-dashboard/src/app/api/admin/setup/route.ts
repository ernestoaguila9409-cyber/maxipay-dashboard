import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp, verifyIdToken } from "@/lib/firebaseAdmin";

export const runtime = "nodejs";

/**
 * POST /api/admin/setup
 *
 * One-time bootstrap: promotes the calling user to super_admin.
 * Only succeeds when no super_admin exists yet in Firebase Auth,
 * so it can only be used once to create the initial administrator.
 */
export async function POST(req: Request) {
  try {
    const decoded = await verifyIdToken(req.headers.get("authorization"));

    getFirebaseAdminApp();
    const authAdmin = admin.auth();

    const existing = await findExistingSuperAdmin(authAdmin);
    if (existing) {
      return NextResponse.json(
        {
          ok: false,
          error: "already_exists",
          message: "A super admin already exists. This endpoint can only be used once.",
        },
        { status: 409 }
      );
    }

    await authAdmin.setCustomUserClaims(decoded.uid, {
      role: "super_admin",
    });

    return NextResponse.json({
      ok: true,
      message: "You have been promoted to super_admin. Sign out and sign back in to activate.",
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (msg === "Unauthorized") {
      return NextResponse.json(
        { ok: false, error: "unauthorized", message: "Sign in first." },
        { status: 401 }
      );
    }
    console.error("[admin/setup]", e);
    return NextResponse.json(
      { ok: false, error: "server_error", message: "Something went wrong." },
      { status: 500 }
    );
  }
}

async function findExistingSuperAdmin(
  authAdmin: admin.auth.Auth
): Promise<admin.auth.UserRecord | null> {
  let nextPageToken: string | undefined;
  do {
    const result = await authAdmin.listUsers(1000, nextPageToken);
    for (const user of result.users) {
      if (
        user.customClaims &&
        (user.customClaims as Record<string, unknown>).role === "super_admin"
      ) {
        return user;
      }
    }
    nextPageToken = result.pageToken;
  } while (nextPageToken);
  return null;
}
