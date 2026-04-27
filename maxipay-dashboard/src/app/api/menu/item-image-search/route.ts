import { NextResponse } from "next/server";
import OpenAI from "openai";
import { verifyIdToken } from "@/lib/firebaseAdmin";

export const runtime = "nodejs";

const OPENAI_MODEL = process.env.OPENAI_ITEM_IMAGE_MODEL || "gpt-4o-mini";

function getOpenAI(): OpenAI {
  const key = process.env.OPENAI_API_KEY;
  if (!key) throw new Error("OPENAI_API_KEY is not configured");
  return new OpenAI({ apiKey: key });
}

async function buildSearchQueryFromItemName(itemName: string): Promise<string> {
  const openai = getOpenAI();
  const completion = await openai.chat.completions.create({
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
  });
  const raw = completion.choices[0]?.message?.content?.trim() ?? "";
  const cleaned = raw.replace(/^["']|["']$/g, "").replace(/\s+/g, " ").trim();
  return cleaned || itemName.trim();
}

export interface PexelsImageHit {
  id: number;
  /** Medium-sized preview URL (picker grid). */
  previewUrl: string;
  /** Best URL to send to commit endpoint (large JPEG). */
  sourceUrl: string;
  photographer: string;
}

async function searchPexels(query: string): Promise<PexelsImageHit[]> {
  const apiKey = process.env.PEXELS_API_KEY;
  if (!apiKey) throw new Error("PEXELS_API_KEY is not configured");

  const url = new URL("https://api.pexels.com/v1/search");
  url.searchParams.set("query", query);
  url.searchParams.set("per_page", "12");

  const res = await fetch(url.toString(), {
    // Pexels expects the raw API key in Authorization (not Bearer).
    headers: { Authorization: apiKey },
  });
  if (!res.ok) {
    const t = await res.text().catch(() => "");
    throw new Error(`Pexels API error (${res.status}): ${t.slice(0, 200)}`);
  }
  const data = (await res.json()) as {
    photos?: Array<{
      id: number;
      src?: { medium?: string; large?: string; large2x?: string; original?: string };
      photographer?: string;
    }>;
  };
  const photos = data.photos ?? [];
  return photos.map((p) => {
    const src = p.src ?? {};
    const previewUrl = src.medium || src.large || src.large2x || "";
    const sourceUrl = src.large2x || src.large || src.original || src.medium || "";
    return {
      id: p.id,
      previewUrl,
      sourceUrl,
      photographer: typeof p.photographer === "string" ? p.photographer : "",
    };
  }).filter((x) => x.previewUrl && x.sourceUrl);
}

/**
 * POST body: { itemName?: string, query?: string }
 * - If `query` is non-empty, searches Pexels directly (manual refine).
 * - Else uses OpenAI to turn `itemName` into a query, then Pexels.
 */
export async function POST(req: Request) {
  try {
    await verifyIdToken(req.headers.get("authorization"));
    const body = (await req.json()) as { itemName?: string; query?: string };
    const manual = typeof body.query === "string" ? body.query.trim() : "";
    const itemName = typeof body.itemName === "string" ? body.itemName.trim() : "";

    let finalQuery: string;
    if (manual) {
      finalQuery = manual;
    } else if (itemName) {
      finalQuery = await buildSearchQueryFromItemName(itemName);
    } else {
      return NextResponse.json(
        { error: "Provide itemName or query." },
        { status: 400 }
      );
    }

    const images = await searchPexels(finalQuery);
    return NextResponse.json({ query: finalQuery, images });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const status =
      msg === "Unauthorized" || msg.includes("Unauthorized") ? 401 : 500;
    console.error("[api/menu/item-image-search]", e);
    return NextResponse.json({ error: msg }, { status });
  }
}
