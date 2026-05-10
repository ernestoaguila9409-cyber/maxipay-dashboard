import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp, verifyIdToken } from "@/lib/firebaseAdmin";
import { normalizeMerchantEmail } from "@/lib/merchantWelcomeEmail";
import { syncSettingsBusinessInfoFromMerchant } from "@/lib/syncMerchantBusinessInfo";

export const runtime = "nodejs";

async function requireSuperAdmin(req: Request): Promise<admin.auth.DecodedIdToken> {
  const decoded = await verifyIdToken(req.headers.get("authorization"));
  if (decoded.role !== "super_admin") throw new Error("Forbidden");
  return decoded;
}

function handleError(e: unknown) {
  const msg = e instanceof Error ? e.message : String(e);
  if (msg === "Unauthorized")
    return NextResponse.json({ ok: false, error: "unauthorized" }, { status: 401 });
  if (msg === "Forbidden")
    return NextResponse.json({ ok: false, error: "forbidden" }, { status: 403 });
  console.error("[merchants/[id]]", e);
  return NextResponse.json({ ok: false, error: "server_error" }, { status: 500 });
}

// GET — single merchant + terminals
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    await requireSuperAdmin(req);
    const { id } = await params;
    getFirebaseAdminApp();
    const db = admin.firestore();

    const doc = await db.collection("Merchants").doc(id).get();
    if (!doc.exists) {
      return NextResponse.json({ ok: false, error: "not_found" }, { status: 404 });
    }

    const termSnap = await db
      .collection("Merchants")
      .doc(id)
      .collection("payment_terminals")
      .get();

    const terminals = termSnap.docs.map((d) => ({ id: d.id, ...d.data() }));

    return NextResponse.json({ ok: true, merchant: { id: doc.id, ...doc.data() }, terminals });
  } catch (e) {
    return handleError(e);
  }
}

// PATCH — update merchant fields
export async function PATCH(req: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    await requireSuperAdmin(req);
    const { id } = await params;
    const body = (await req.json().catch(() => ({}))) as Record<string, unknown>;
    getFirebaseAdminApp();
    const db = admin.firestore();

    const ref = db.collection("Merchants").doc(id);
    const doc = await ref.get();
    if (!doc.exists) {
      return NextResponse.json({ ok: false, error: "not_found" }, { status: 404 });
    }

    const allowed: Record<string, unknown> = {};
    const stringFields = [
      "businessName",
      "ownerFirstName",
      "ownerLastName",
      "phone",
      "merchantNumber",
    ];
    for (const f of stringFields) {
      if (typeof body[f] === "string") allowed[f] = (body[f] as string).trim();
    }
    if (typeof body.status === "string" && ["active", "suspended", "pending"].includes(body.status as string)) {
      allowed.status = body.status;
    }
    if (body.address && typeof body.address === "object") {
      const a = body.address as Record<string, unknown>;
      allowed.address = {
        street: typeof a.street === "string" ? a.street.trim() : "",
        city: typeof a.city === "string" ? a.city.trim() : "",
        state: typeof a.state === "string" ? a.state.trim() : "",
        zip: typeof a.zip === "string" ? a.zip.trim() : "",
      };
    }

    if (typeof body.email === "string") {
      const newEmail = normalizeMerchantEmail(body.email);
      if (!newEmail) {
        return NextResponse.json({ ok: false, message: "Invalid email address." }, { status: 400 });
      }
      const prevRaw = doc.data()?.email;
      const prev =
        typeof prevRaw === "string" ? normalizeMerchantEmail(prevRaw) : null;

      if (newEmail !== prev) {
        const dupSnap = await db.collection("Merchants").where("email", "==", newEmail).get();
        const conflicting = dupSnap.docs.filter((d) => d.id !== id);
        const thisOwner = String(doc.data()?.ownerAuthUid ?? "").trim();
        for (const d of conflicting) {
          const otherOwner = String(d.data()?.ownerAuthUid ?? "").trim();
          if (!otherOwner) continue;
          if (!thisOwner || otherOwner !== thisOwner) {
            return NextResponse.json(
              {
                ok: false,
                message:
                  "Another merchant already uses this email under a different owner account.",
              },
              { status: 409 }
            );
          }
        }

        const authAdmin = admin.auth();
        const ownerUidFromDocRaw = doc.data()?.ownerAuthUid;
        const ownerUidFromDoc =
          typeof ownerUidFromDocRaw === "string" ? ownerUidFromDocRaw.trim() : "";

        if (!prev) {
          allowed.email = newEmail;
        } else {
          let owner: admin.auth.UserRecord;
          try {
            if (ownerUidFromDoc) {
              owner = await authAdmin.getUser(ownerUidFromDoc);
            } else {
              owner = await authAdmin.getUserByEmail(prev);
            }
          } catch (e: unknown) {
            const code =
              e && typeof e === "object" && "code" in e
                ? String((e as { code: string }).code)
                : "";
            if (code === "auth/user-not-found") {
              return NextResponse.json(
                {
                  ok: false,
                  message:
                    "No Firebase Auth user exists for the current email. Update the email in Firebase Console, or keep the original address.",
                },
                { status: 400 }
              );
            }
            throw e;
          }

          const claims = owner.customClaims as Record<string, unknown> | undefined;
          const claimMid = typeof claims?.merchantId === "string" ? claims.merchantId : undefined;
          const emailMatchesDoc = normalizeMerchantEmail(owner.email) === prev;

          const linkedByStoredUid = !!ownerUidFromDoc && owner.uid === ownerUidFromDoc;
          const linkedByClaim = claimMid === id;
          /** Same email as Firestore but claims were replaced (e.g. /api/setup set only role: super_admin). */
          const linkedByEmailOnlyRecovery = !ownerUidFromDoc && emailMatchesDoc && claimMid === undefined;

          if (!linkedByStoredUid && !linkedByClaim && !linkedByEmailOnlyRecovery) {
            return NextResponse.json(
              {
                ok: false,
                message:
                  "The Firebase account is not linked to this merchant (missing merchantId on the account, or ownerAuthUid on the document). If the owner used the same login as a super admin, add field ownerAuthUid on this Merchants doc in Firestore to their Firebase uid, then try again.",
              },
              { status: 403 }
            );
          }

          try {
            const other = await authAdmin.getUserByEmail(newEmail);
            if (other.uid !== owner.uid) {
              return NextResponse.json(
                {
                  ok: false,
                  message: "That email is already used by another Firebase account.",
                },
                { status: 409 }
              );
            }
          } catch (e: unknown) {
            const code =
              e && typeof e === "object" && "code" in e
                ? String((e as { code: string }).code)
                : "";
            if (code !== "auth/user-not-found") throw e;
          }

          await authAdmin.updateUser(owner.uid, { email: newEmail });
          allowed.email = newEmail;
          if (!ownerUidFromDoc) {
            allowed.ownerAuthUid = owner.uid;
          }
        }
      }
    }

    if (Object.keys(allowed).length === 0) {
      return NextResponse.json({ ok: false, error: "no_changes" }, { status: 400 });
    }

    const prevData = doc.data()!;
    const mergedFirst =
      typeof allowed.ownerFirstName === "string"
        ? (allowed.ownerFirstName as string)
        : typeof prevData.ownerFirstName === "string"
          ? prevData.ownerFirstName
          : "";
    const mergedLast =
      typeof allowed.ownerLastName === "string"
        ? (allowed.ownerLastName as string)
        : typeof prevData.ownerLastName === "string"
          ? prevData.ownerLastName
          : "";
    if (
      ("ownerFirstName" in allowed || "ownerLastName" in allowed) &&
      (!String(mergedFirst).trim() || !String(mergedLast).trim())
    ) {
      return NextResponse.json(
        {
          ok: false,
          message:
            "Owner first and last name must both be non-empty (dashboard Employees list uses them).",
        },
        { status: 400 }
      );
    }

    allowed.updatedAt = admin.firestore.FieldValue.serverTimestamp();
    await ref.update(allowed);

    try {
      const fresh = await ref.get();
      if (fresh.exists) {
        await syncSettingsBusinessInfoFromMerchant(db, id, fresh.data()!);
      }
    } catch (syncErr) {
      console.error("[merchants/[id]] Settings/businessInfo sync failed:", syncErr);
    }

    return NextResponse.json({ ok: true });
  } catch (e) {
    return handleError(e);
  }
}
