/**
 * Normalizes AI / OCR pipeline output into a stable shape for the UI.
 */

export interface NormalizedScanItem {
  name: string;
  price: number | null;
  priceUncertain: boolean;
}

export interface NormalizedScanCategory {
  name: string;
  items: NormalizedScanItem[];
}

export interface NormalizedScanMenu {
  categories: NormalizedScanCategory[];
}

function parsePrice(v: unknown): { value: number | null; uncertain: boolean } {
  if (v === null || v === undefined) return { value: null, uncertain: true };
  if (typeof v === "number" && !Number.isNaN(v)) {
    return { value: v < 0 ? 0 : v, uncertain: false };
  }
  const s = String(v).trim();
  if (!s) return { value: null, uncertain: true };
  const n = parseFloat(s.replace(/[^0-9.]/g, ""));
  if (Number.isNaN(n)) return { value: null, uncertain: true };
  return { value: n, uncertain: false };
}

function normalizeItem(raw: unknown): NormalizedScanItem | null {
  if (!raw || typeof raw !== "object") return null;
  const o = raw as Record<string, unknown>;
  const name = String(o.name ?? "").trim();
  if (!name) return null;
  const explicitUncertain =
    o.priceUncertain === true ||
    o.uncertain === true ||
    o.priceMissing === true;
  const { value, uncertain } = parsePrice(o.price);
  return {
    name,
    price: value,
    priceUncertain: explicitUncertain || uncertain,
  };
}

export function normalizeStructuredMenu(parsed: unknown): NormalizedScanMenu {
  if (!parsed || typeof parsed !== "object") {
    return { categories: [{ name: "General", items: [] }] };
  }
  const root = parsed as Record<string, unknown>;

  let categoriesRaw = root.categories;
  if (!Array.isArray(categoriesRaw) || categoriesRaw.length === 0) {
    const flatItems = root.items;
    if (Array.isArray(flatItems)) {
      const items = flatItems
        .map(normalizeItem)
        .filter((x): x is NormalizedScanItem => x !== null);
      return { categories: [{ name: "General", items }] };
    }
    return { categories: [{ name: "General", items: [] }] };
  }

  const categories: NormalizedScanCategory[] = [];

  for (const c of categoriesRaw) {
    if (!c || typeof c !== "object") continue;
    const co = c as Record<string, unknown>;
    const catName = String(co.name ?? "General").trim() || "General";
    const itemsRaw = co.items;
    const items: NormalizedScanItem[] = [];
    if (Array.isArray(itemsRaw)) {
      for (const it of itemsRaw) {
        const ni = normalizeItem(it);
        if (ni) items.push(ni);
      }
    }
    categories.push({ name: catName, items });
  }

  if (categories.length === 0) {
    return { categories: [{ name: "General", items: [] }] };
  }

  return { categories };
}
