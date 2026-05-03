import { NextResponse } from "next/server";
import { verifyIdToken } from "@/lib/firebaseAdmin";

export const runtime = "nodejs";

export interface BrandfetchResult {
  brandId: string;
  name: string;
  domain: string;
  icon: string;
  /** Full logo via CDN – use as sourceUrl for commit endpoint. */
  logoUrl: string;
}

/**
 * POST body: { query: string }
 * Searches Brandfetch for real brand logos by name.
 */
export async function POST(req: Request) {
  try {
    await verifyIdToken(req.headers.get("authorization"));

    const clientId =
      process.env.BRANDFETCH_CLIENT_ID?.trim() ||
      process.env.NEXT_PUBLIC_BRANDFETCH_CLIENT_ID?.trim();
    if (!clientId) {
      return NextResponse.json(
        {
          error:
            "Brandfetch is not configured. Set BRANDFETCH_CLIENT_ID (or NEXT_PUBLIC_BRANDFETCH_CLIENT_ID) " +
            "in .env.local for local dev, and in your host (e.g. Vercel → Environment Variables) for production, then restart / redeploy.",
        },
        { status: 500 },
      );
    }

    const body = (await req.json()) as { query?: string };
    const query = typeof body.query === "string" ? body.query.trim() : "";
    if (!query) {
      return NextResponse.json(
        { error: "Provide a query." },
        { status: 400 },
      );
    }

    const url = `https://api.brandfetch.io/v2/search/${encodeURIComponent(query)}?c=${encodeURIComponent(clientId)}`;
    const res = await fetch(url);
    if (!res.ok) {
      const t = await res.text().catch(() => "");
      throw new Error(`Brandfetch API error (${res.status}): ${t.slice(0, 200)}`);
    }

    const data = (await res.json()) as Array<{
      brandId?: string;
      name?: string;
      domain?: string;
      icon?: string;
      claimed?: boolean;
    }>;

    const results: BrandfetchResult[] = (Array.isArray(data) ? data : [])
      .filter((b) => b.domain)
      .map((b) => ({
        brandId: b.brandId ?? "",
        name: b.name ?? b.domain ?? "",
        domain: b.domain!,
        icon: b.icon ?? "",
        logoUrl: `https://cdn.brandfetch.io/${b.domain}?c=${encodeURIComponent(clientId)}&t=icon&w=512&h=512`,
      }));

    return NextResponse.json({ results });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const status =
      msg === "Unauthorized" || msg.includes("Unauthorized") ? 401 : 500;
    console.error("[api/menu/brandfetch-search]", e);
    return NextResponse.json({ error: msg }, { status });
  }
}
