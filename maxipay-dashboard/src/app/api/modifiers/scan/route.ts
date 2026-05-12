import { NextResponse } from "next/server";
import { ImageAnnotatorClient } from "@google-cloud/vision";
import OpenAI from "openai";
import {
  getFirebaseAdminApp,
  verifyIdToken,
  getServiceAccountCredentials,
} from "@/lib/firebaseAdmin";
import { getStorage } from "firebase-admin/storage";
import { normalizeStructuredModifierScan } from "@/lib/menuScanNormalize";
import type { NormalizedModifierGroup } from "@/lib/menuScanNormalize";

export const runtime = "nodejs";
export const maxDuration = 120;

const MODIFIERS_JSON_INSTRUCTION = `Extract modifier / add-on / customization data ONLY from this text (e.g. a modifier list, pizza toppings sheet, or "extras" section — not a full food menu unless modifiers are all that is present).

Return ONLY valid JSON (no markdown, no explanation) in this exact shape:
{
  "modifierGroups": [
    {
      "name": "string",
      "required": boolean,
      "minSelection": number,
      "maxSelection": number,
      "options": [
        {
          "name": "string",
          "price": number,
          "triggersModifierGroupNames": ["string"]
        }
      ]
    }
  ]
}

CRITICAL — group vs option:
- A modifier GROUP is a heading or container (e.g. "Crust", "SIZE", "Add toppings", "Choice of side").
- An OPTION is one selectable line (e.g. "Thin", "Large", "Pepperoni", "+$2.00").
- NEVER put a group name as an option. NEVER use one option string with comma-separated items — split into separate options.
- NEVER flatten "Choice of Side: Fries, Onion Rings" into a single option; split into separate options OR emit a group "Choice of Side" with options Fries, Onion Rings.

Triggered / nested groups:
- If an option unlocks further required picks (e.g. "Combo" → must pick side and drink), list those other GROUP names in triggersModifierGroupNames on that option, and include those groups separately in modifierGroups with required: true, minSelection: 1, maxSelection: 1 as appropriate.
- If no nesting, use triggersModifierGroupNames: [].

Selection rules:
- required: true if customer must pick from this group.
- minSelection / maxSelection: e.g. optional multi-select might be min 0 max 3; single choice required is min 1 max 1.

Prices:
- Numbers only (no $). Missing price → use 0.

Do not invent content not in the text. If the text has no clear modifier structure, return { "modifierGroups": [] }.
Return only JSON.`;

function getOpenAI(): OpenAI {
  const key = process.env.OPENAI_API_KEY;
  if (!key) throw new Error("OPENAI_API_KEY is not configured");
  return new OpenAI({ apiKey: key });
}

function getVisionClient(): ImageAnnotatorClient {
  return new ImageAnnotatorClient({
    credentials: getServiceAccountCredentials() as Record<string, unknown>,
  });
}

async function runAiModifierStructuring(menuText: string): Promise<NormalizedModifierGroup[]> {
  const openai = getOpenAI();
  const completion = await openai.chat.completions.create({
    model: process.env.OPENAI_MENU_MODEL || "gpt-4o-mini",
    response_format: { type: "json_object" },
    messages: [
      { role: "system", content: MODIFIERS_JSON_INSTRUCTION },
      {
        role: "user",
        content: `Text from OCR (modifier / add-on list):\n\n${menuText.slice(0, 120000)}`,
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
  return normalizeStructuredModifierScan(parsed);
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
      const body = (await req.json()) as { text?: string };
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
      storagePath = `modifier-scans/${user.uid}/${Date.now()}.${ext}`;
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

    const groups = await runAiModifierStructuring(rawText);

    return NextResponse.json({
      success: true,
      rawText,
      storagePath: storagePath ?? null,
      groups,
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : "Scan failed";
    const status =
      msg === "Unauthorized" || msg.includes("Unauthorized") ? 401 : 500;
    console.error("[api/modifiers/scan]", e);
    return NextResponse.json({ success: false, error: msg }, { status });
  }
}
