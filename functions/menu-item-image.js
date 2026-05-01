const { onCall, HttpsError } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const logger = require("firebase-functions/logger");
const { randomUUID } = require("crypto");

const OPENAI_MODEL = process.env.OPENAI_ITEM_IMAGE_MODEL || "gpt-4o-mini";

async function buildSearchQueryFromItemName(itemName) {
  const key = process.env.OPENAI_API_KEY?.trim();
  if (!key) {
    return itemName.trim();
  }
  try {
    const res = await fetch("https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${key}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: OPENAI_MODEL,
        messages: [
          {
            role: "system",
            content:
              "Convert restaurant menu item names into short English stock-photo search queries for food imagery. " +
              "Output ONLY the query phrase: 4–12 words, descriptive, no quotes, no brand names, no punctuation at the end.",
          },
          {
            role: "user",
            content: `Convert this menu item into a high-quality food image search query: ${itemName.trim()}`,
          },
        ],
        temperature: 0.4,
        max_tokens: 80,
      }),
    });
    if (!res.ok) {
      const t = await res.text().catch(() => "");
      logger.warn("[menuItemImageSearch] OpenAI failed", { status: res.status, body: t.slice(0, 200) });
      return itemName.trim();
    }
    const data = await res.json();
    const raw = data.choices?.[0]?.message?.content?.trim() ?? "";
    const cleaned = String(raw).replace(/^["']|["']$/g, "").replace(/\s+/g, " ").trim();
    return cleaned || itemName.trim();
  } catch (e) {
    logger.warn("[menuItemImageSearch] OpenAI exception", { err: e.message });
    return itemName.trim();
  }
}

async function searchPexels(query) {
  const apiKey = process.env.PEXELS_API_KEY?.trim();
  if (!apiKey) {
    throw new HttpsError(
      "failed-precondition",
      "PEXELS_API_KEY is not configured. Set it for Cloud Functions (same as dashboard .env).",
    );
  }
  const url = new URL("https://api.pexels.com/v1/search");
  url.searchParams.set("query", query);
  url.searchParams.set("per_page", "12");

  const res = await fetch(url.toString(), {
    headers: { Authorization: apiKey },
  });
  if (!res.ok) {
    const t = await res.text().catch(() => "");
    throw new HttpsError("internal", `Pexels API error (${res.status}): ${t.slice(0, 200)}`);
  }
  const data = await res.json();
  const photos = data.photos ?? [];
  return photos
    .map((p) => {
      const src = p.src ?? {};
      const previewUrl = src.medium || src.large || src.large2x || "";
      const sourceUrl = src.large2x || src.large || src.original || src.medium || "";
      return {
        id: p.id,
        previewUrl,
        sourceUrl,
        photographer: typeof p.photographer === "string" ? p.photographer : "",
      };
    })
    .filter((x) => x.previewUrl && x.sourceUrl);
}

function isAllowedPexelsUrl(urlStr) {
  try {
    const u = new URL(urlStr);
    if (u.protocol !== "https:") return false;
    const h = u.hostname.toLowerCase();
    return h === "images.pexels.com" || h.endsWith(".pexels.com");
  } catch {
    return false;
  }
}

const MENU_IMAGE_REGION = "us-central1";

/**
 * Callable: { itemName?: string, query?: string }
 * If query is non-empty, searches Pexels directly. Else uses itemName (OpenAI-assisted when configured).
 */
// invoker: "public" = Cloud Run allows the HTTPS entrypoint (required for client SDKs).
// Firebase Auth is still enforced below via request.auth / ID token verification.
exports.menuItemImageSearch = onCall(
  { region: MENU_IMAGE_REGION, invoker: "public" },
  async (request) => {
  if (!request.auth?.uid) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }

  const manual = typeof request.data?.query === "string" ? request.data.query.trim() : "";
  const itemName = typeof request.data?.itemName === "string" ? request.data.itemName.trim() : "";

  let finalQuery;
  if (manual) {
    finalQuery = manual;
  } else if (itemName) {
    finalQuery = await buildSearchQueryFromItemName(itemName);
  } else {
    throw new HttpsError("invalid-argument", "Provide itemName or query.");
  }

  const images = await searchPexels(finalQuery);
  return { query: finalQuery, images };
  },
);

/**
 * Callable: { itemId: string, sourceUrl: string }
 * Downloads from Pexels (validated host), uploads to Firebase Storage, returns imageUrl for MenuItems.
 */
exports.menuItemImageCommitPexels = onCall(
  { region: MENU_IMAGE_REGION, invoker: "public" },
  async (request) => {
  if (!request.auth?.uid) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }

  const itemId = typeof request.data?.itemId === "string" ? request.data.itemId.trim() : "";
  const sourceUrl = typeof request.data?.sourceUrl === "string" ? request.data.sourceUrl.trim() : "";
  if (!itemId || !sourceUrl || !isAllowedPexelsUrl(sourceUrl)) {
    throw new HttpsError(
      "invalid-argument",
      "Invalid itemId or sourceUrl (must be a https://images.pexels.com/… URL).",
    );
  }

  const imgRes = await fetch(sourceUrl, { redirect: "follow" });
  if (!imgRes.ok) {
    throw new HttpsError("internal", `Could not download image (${imgRes.status}).`);
  }
  const buf = Buffer.from(await imgRes.arrayBuffer());
  if (buf.length < 500 || buf.length > 15 * 1024 * 1024) {
    throw new HttpsError("invalid-argument", "Image size invalid.");
  }

  const contentType =
    imgRes.headers.get("content-type")?.split(";")[0]?.trim() || "image/jpeg";
  if (!contentType.startsWith("image/")) {
    throw new HttpsError("invalid-argument", "URL did not return an image.");
  }

  const bucket = admin.storage().bucket();
  const ext = contentType.includes("png") ? "png" : "jpg";
  const storagePath = `menuItems/${itemId}_${Date.now()}.${ext}`;
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
  const bucketName = bucket.name;
  const imageUrl = `https://firebasestorage.googleapis.com/v0/b/${bucketName}/o/${enc}?alt=media&token=${token}`;

  return { imageUrl, storagePath };
  },
);
