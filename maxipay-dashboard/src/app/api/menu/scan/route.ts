import { NextResponse } from "next/server";
import { ImageAnnotatorClient } from "@google-cloud/vision";
import OpenAI from "openai";
import {
  getFirebaseAdminApp,
  verifyIdToken,
  serviceAccountCredentials,
} from "@/lib/firebaseAdmin";
import { getStorage } from "firebase-admin/storage";
import { normalizeStructuredMenu } from "@/lib/menuScanNormalize";

export const runtime = "nodejs";
export const maxDuration = 120;

const MENU_JSON_INSTRUCTION = `Convert this restaurant menu text into structured JSON.
Group items into categories when section headings or layout suggest it.
If a price is missing, unclear, or ambiguous, set price to null and priceUncertain to true.
Use priceUncertain false when the price is clearly readable.
Return ONLY valid JSON (no markdown) in this exact shape:
{
  "categories": [
    {
      "name": "string",
      "items": [
        { "name": "string", "price": number | null, "priceUncertain": boolean }
      ]
    }
  ]
}
If you cannot detect categories, use a single category named "General" with all items.`;

function getOpenAI(): OpenAI {
  const key = process.env.OPENAI_API_KEY;
  if (!key) throw new Error("OPENAI_API_KEY is not configured");
  return new OpenAI({ apiKey: key });
}

function getVisionClient(): ImageAnnotatorClient {
  return new ImageAnnotatorClient({
    credentials: serviceAccountCredentials as Record<string, unknown>,
  });
}

async function runAiStructuring(menuText: string): Promise<ReturnType<typeof normalizeStructuredMenu>> {
  const openai = getOpenAI();
  const completion = await openai.chat.completions.create({
    model: process.env.OPENAI_MENU_MODEL || "gpt-4o-mini",
    response_format: { type: "json_object" },
    messages: [
      { role: "system", content: MENU_JSON_INSTRUCTION },
      {
        role: "user",
        content: `Menu text from OCR:\n\n${menuText.slice(0, 120000)}`,
      },
    ],
    temperature: 0.2,
  });
  const raw = completion.choices[0]?.message?.content;
  if (!raw) throw new Error("AI returned empty response");
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new Error("AI returned invalid JSON");
  }
  return normalizeStructuredMenu(parsed);
}

async function extractTextFromImage(buffer: Buffer): Promise<string> {
  const client = getVisionClient();
  const [result] = await client.textDetection({
    image: { content: buffer },
  });
  const annotations = result.textAnnotations;
  if (!annotations?.length) return "";
  return annotations[0].description?.trim() ?? "";
}

export async function POST(req: Request) {
  try {
    const authHeader = req.headers.get("authorization");
    const user = await verifyIdToken(authHeader);
    const contentType = req.headers.get("content-type") || "";

    let rawText = "";
    let storagePath: string | undefined;

    if (contentType.includes("application/json")) {
      const body = (await req.json()) as { text?: string; reprocess?: boolean };
      rawText = typeof body.text === "string" ? body.text : "";
      if (!rawText.trim()) {
        return NextResponse.json(
          { success: false, error: "Missing text for reprocessing." },
          { status: 400 }
        );
      }
    } else if (contentType.includes("multipart/form-data")) {
      const form = await req.formData();
      const file = form.get("file");
      if (!(file instanceof Blob)) {
        return NextResponse.json(
          { success: false, error: "Missing image file." },
          { status: 400 }
        );
      }
      const mime = file.type || "image/jpeg";
      if (!/^image\/(jpeg|jpg|png)$/i.test(mime)) {
        return NextResponse.json(
          { success: false, error: "Only JPG and PNG images are allowed." },
          { status: 400 }
        );
      }
      const arrayBuf = await file.arrayBuffer();
      const buffer = Buffer.from(arrayBuf);

      rawText = await extractTextFromImage(buffer);
      if (!rawText.trim()) {
        return NextResponse.json(
          {
            success: false,
            error: "No text could be read from the image. Try a clearer photo.",
          },
          { status: 422 }
        );
      }

      getFirebaseAdminApp();
      const bucketName =
        process.env.FIREBASE_STORAGE_BUCKET ||
        process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET;
      if (!bucketName) {
        throw new Error("Storage bucket not configured");
      }
      const ext = mime.includes("png") ? "png" : "jpg";
      storagePath = `menu-scans/${user.uid}/${Date.now()}.${ext}`;
      const bucket = getStorage().bucket(bucketName);
      await bucket.file(storagePath).save(buffer, {
        contentType: mime,
        metadata: { cacheControl: "public, max-age=31536000" },
      });
    } else {
      return NextResponse.json(
        { success: false, error: "Unsupported content type." },
        { status: 415 }
      );
    }

    const normalized = await runAiStructuring(rawText);

    return NextResponse.json({
      success: true,
      rawText,
      storagePath: storagePath ?? null,
      categories: normalized.categories,
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : "Scan failed";
    const status =
      msg === "Unauthorized" || msg.includes("Unauthorized") ? 401 : 500;
    console.error("[api/menu/scan]", e);
    return NextResponse.json({ success: false, error: msg }, { status });
  }
}
