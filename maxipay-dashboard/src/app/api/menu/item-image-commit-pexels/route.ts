import { randomUUID } from "crypto";
import { NextResponse } from "next/server";
import { getStorage } from "firebase-admin/storage";
import { getFirebaseAdminApp, verifyIdToken } from "@/lib/firebaseAdmin";
import { HERO_STORAGE_PREFIX } from "@/lib/storefrontShared";

export const runtime = "nodejs";

function isAllowedPexelsUrl(urlStr: string): boolean {
  try {
    const u = new URL(urlStr);
    if (u.protocol !== "https:") return false;
    const h = u.hostname.toLowerCase();
    return h === "images.pexels.com" || h.endsWith(".pexels.com");
  } catch {
    return false;
  }
}

function isValidHeroSlideId(id: string): boolean {
  return /^[a-zA-Z0-9_-]{1,128}$/.test(id);
}

/**
 * Downloads a Pexels image (validated host), uploads to Firebase Storage, returns a Firebase
 * download URL (never the Pexels URL) for storing on MenuItems or online-ordering hero slides.
 *
 * POST body: { itemId: string, sourceUrl: string } | { heroSlideId: string, sourceUrl: string }
 */
export async function POST(req: Request) {
  try {
    await verifyIdToken(req.headers.get("authorization"));
    getFirebaseAdminApp();

    const body = (await req.json()) as {
      itemId?: string;
      heroSlideId?: string;
      sourceUrl?: string;
    };
    const itemId = typeof body.itemId === "string" ? body.itemId.trim() : "";
    const heroSlideId =
      typeof body.heroSlideId === "string" ? body.heroSlideId.trim() : "";
    const sourceUrl = typeof body.sourceUrl === "string" ? body.sourceUrl.trim() : "";

    const heroMode = Boolean(heroSlideId);
    if (!sourceUrl || !isAllowedPexelsUrl(sourceUrl)) {
      return NextResponse.json(
        { error: "Invalid sourceUrl (must be a https://images.pexels.com/… URL)." },
        { status: 400 }
      );
    }
    if (heroMode) {
      if (!isValidHeroSlideId(heroSlideId)) {
        return NextResponse.json({ error: "Invalid heroSlideId." }, { status: 400 });
      }
    } else if (!itemId) {
      return NextResponse.json(
        { error: "Provide itemId for menu images, or heroSlideId for storefront banner." },
        { status: 400 }
      );
    }

    const imgRes = await fetch(sourceUrl, { redirect: "follow" });
    if (!imgRes.ok) {
      return NextResponse.json(
        { error: `Could not download image (${imgRes.status}).` },
        { status: 502 }
      );
    }
    const buf = Buffer.from(await imgRes.arrayBuffer());
    if (buf.length < 500 || buf.length > 15 * 1024 * 1024) {
      return NextResponse.json({ error: "Image size invalid." }, { status: 400 });
    }

    const contentType =
      imgRes.headers.get("content-type")?.split(";")[0]?.trim() || "image/jpeg";
    if (!contentType.startsWith("image/")) {
      return NextResponse.json({ error: "URL did not return an image." }, { status: 400 });
    }

    const bucketName =
      process.env.FIREBASE_STORAGE_BUCKET ||
      process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET;
    if (!bucketName) {
      throw new Error("Storage bucket not configured");
    }

    const ext = contentType.includes("png") ? "png" : "jpg";
    const storagePath = heroMode
      ? `${HERO_STORAGE_PREFIX}/${heroSlideId}.${ext}`
      : `menuItems/${itemId}_${Date.now()}.${ext}`;
    const bucket = getStorage().bucket(bucketName);
    const file = bucket.file(storagePath);
    const token = randomUUID();

    await file.save(buf, {
      resumable: false,
      metadata: {
        contentType: ext === "png" ? "image/png" : "image/jpeg",
        cacheControl: "public, max-age=31536000",
        metadata: {
          firebaseStorageDownloadTokens: token,
        },
      },
    });

    const enc = encodeURIComponent(storagePath);
    const imageUrl = `https://firebasestorage.googleapis.com/v0/b/${bucketName}/o/${enc}?alt=media&token=${token}`;

    return NextResponse.json({ imageUrl, storagePath });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const status =
      msg === "Unauthorized" || msg.includes("Unauthorized") ? 401 : 500;
    console.error("[api/menu/item-image-commit-pexels]", e);
    return NextResponse.json({ error: msg }, { status });
  }
}
