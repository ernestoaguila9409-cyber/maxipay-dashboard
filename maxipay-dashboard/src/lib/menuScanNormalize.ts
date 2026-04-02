/**
 * Normalizes AI / OCR pipeline output into a stable shape for the UI.
 */

export interface NormalizedModifierOption {
  name: string;
  price: number;
}

export interface NormalizedModifierGroup {
  name: string;
  options: NormalizedModifierOption[];
}

export interface NormalizedScanItem {
  name: string;
  price: number | null;
  priceUncertain: boolean;
  modifierGroups: NormalizedModifierGroup[];
}

export interface NormalizedSubcategory {
  name: string;
  items: NormalizedScanItem[];
}

export interface NormalizedScanCategory {
  name: string;
  subcategories: NormalizedSubcategory[];
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

function normalizeModifierOption(raw: unknown): NormalizedModifierOption | null {
  if (!raw || typeof raw !== "object") return null;
  const o = raw as Record<string, unknown>;
  const name = String(o.name ?? "").trim();
  if (!name) return null;
  const rawPrice = o.price;
  let price = 0;
  if (rawPrice !== null && rawPrice !== undefined) {
    if (typeof rawPrice === "number" && !Number.isNaN(rawPrice)) {
      price = Math.max(0, rawPrice);
    } else {
      const parsed = parseFloat(String(rawPrice).replace(/[^0-9.]/g, ""));
      if (!Number.isNaN(parsed)) price = Math.max(0, parsed);
    }
  }
  return { name, price };
}

function normalizeModifierGroup(raw: unknown): NormalizedModifierGroup | null {
  if (!raw || typeof raw !== "object") return null;
  const o = raw as Record<string, unknown>;
  const name = String(o.name ?? "").trim();
  if (!name) return null;
  const optionsRaw = o.options;
  const options: NormalizedModifierOption[] = [];
  if (Array.isArray(optionsRaw)) {
    for (const opt of optionsRaw) {
      const normalized = normalizeModifierOption(opt);
      if (normalized) options.push(normalized);
    }
  }
  return { name, options };
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

  const modifierGroups: NormalizedModifierGroup[] = [];
  const mgRaw = o.modifierGroups ?? o.modifier_groups ?? o.modifiers;
  if (Array.isArray(mgRaw)) {
    for (const mg of mgRaw) {
      const normalized = normalizeModifierGroup(mg);
      if (normalized) modifierGroups.push(normalized);
    }
  }

  return {
    name,
    price: value,
    priceUncertain: explicitUncertain || uncertain,
    modifierGroups,
  };
}

function normalizeSubcategory(raw: unknown): NormalizedSubcategory | null {
  if (!raw || typeof raw !== "object") return null;
  const o = raw as Record<string, unknown>;
  const name = String(o.name ?? "").trim();
  if (!name) return null;
  const itemsRaw = o.items;
  const items: NormalizedScanItem[] = [];
  if (Array.isArray(itemsRaw)) {
    for (const it of itemsRaw) {
      const ni = normalizeItem(it);
      if (ni) items.push(ni);
    }
  }
  return { name, items };
}

export function normalizeStructuredMenu(parsed: unknown): NormalizedScanMenu {
  if (!parsed || typeof parsed !== "object") {
    return { categories: [{ name: "General", subcategories: [], items: [] }] };
  }
  const root = parsed as Record<string, unknown>;

  let categoriesRaw = root.categories;
  if (!Array.isArray(categoriesRaw) || categoriesRaw.length === 0) {
    const flatItems = root.items;
    if (Array.isArray(flatItems)) {
      const items = flatItems
        .map(normalizeItem)
        .filter((x): x is NormalizedScanItem => x !== null);
      return { categories: [{ name: "General", subcategories: [], items }] };
    }
    return { categories: [{ name: "General", subcategories: [], items: [] }] };
  }

  const categories: NormalizedScanCategory[] = [];

  for (const c of categoriesRaw) {
    if (!c || typeof c !== "object") continue;
    const co = c as Record<string, unknown>;
    const catName = String(co.name ?? "General").trim() || "General";

    const subcategories: NormalizedSubcategory[] = [];
    const subRaw = co.subcategories ?? co.sub_categories;
    if (Array.isArray(subRaw)) {
      for (const sub of subRaw) {
        const ns = normalizeSubcategory(sub);
        if (ns) subcategories.push(ns);
      }
    }

    const items: NormalizedScanItem[] = [];
    const itemsRaw = co.items;
    if (Array.isArray(itemsRaw)) {
      for (const it of itemsRaw) {
        const ni = normalizeItem(it);
        if (ni) items.push(ni);
      }
    }

    categories.push({ name: catName, subcategories, items });
  }

  if (categories.length === 0) {
    return { categories: [{ name: "General", subcategories: [], items: [] }] };
  }

  return { categories };
}
