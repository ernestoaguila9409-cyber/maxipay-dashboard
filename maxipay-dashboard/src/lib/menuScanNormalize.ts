/**
 * Normalizes AI / OCR pipeline output into a stable shape for the UI.
 * Detects flattened group-in-option patterns and splits them into proper
 * separate modifier groups with triggersModifierGroupNames relationships.
 */

export interface NormalizedModifierOption {
  name: string;
  price: number;
  triggersModifierGroupNames: string[];
}

export interface NormalizedModifierGroup {
  name: string;
  required: boolean;
  minSelection: number;
  maxSelection: number;
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

const GROUP_HEADER_RE = /^(choice of|pick a|select|choose|pick your)\s+/i;

function looksLikeGroupHeader(name: string): boolean {
  return GROUP_HEADER_RE.test(name);
}

/**
 * Detects patterns like "Choice of Side: Fries, Onion Rings" inside an option
 * name and splits them into { groupName, optionNames[] }.
 */
function parseInlineGroup(name: string): { groupName: string; optionNames: string[] } | null {
  const colonIdx = name.indexOf(":");
  if (colonIdx === -1) return null;
  const left = name.slice(0, colonIdx).trim();
  const right = name.slice(colonIdx + 1).trim();
  if (!left || !right) return null;
  const parts = right.split(",").map((s) => s.trim()).filter(Boolean);
  if (parts.length < 2) return null;
  return { groupName: left, optionNames: parts };
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
  const triggersModifierGroupNames: string[] = [];
  const rawTriggers = o.triggersModifierGroupNames ?? o.triggersGroups ?? o.triggers;
  if (Array.isArray(rawTriggers)) {
    for (const t of rawTriggers) {
      const s = String(t ?? "").trim();
      if (s) triggersModifierGroupNames.push(s);
    }
  }
  return { name, price, triggersModifierGroupNames };
}

function normalizeModifierGroup(raw: unknown): NormalizedModifierGroup | null {
  if (!raw || typeof raw !== "object") return null;
  const o = raw as Record<string, unknown>;
  const name = String(o.name ?? "").trim();
  if (!name) return null;
  const required = o.required === true;
  const minSelection = typeof o.minSelection === "number" ? o.minSelection : (required ? 1 : 0);
  const maxSelection = typeof o.maxSelection === "number" ? o.maxSelection : 1;
  const optionsRaw = o.options;
  const options: NormalizedModifierOption[] = [];
  if (Array.isArray(optionsRaw)) {
    for (const opt of optionsRaw) {
      const normalized = normalizeModifierOption(opt);
      if (normalized) options.push(normalized);
    }
  }
  return { name, required, minSelection, maxSelection, options };
}

/**
 * Post-process an item's modifier groups to split any flattened
 * "Group: opt1, opt2" options into separate modifier groups.
 */
function splitFlattenedOptions(groups: NormalizedModifierGroup[]): NormalizedModifierGroup[] {
  const result: NormalizedModifierGroup[] = [];
  const newGroups: NormalizedModifierGroup[] = [];

  for (const group of groups) {
    const cleanOptions: NormalizedModifierOption[] = [];

    for (const opt of group.options) {
      const inline = parseInlineGroup(opt.name);
      if (inline) {
        const newGroup: NormalizedModifierGroup = {
          name: inline.groupName,
          required: true,
          minSelection: 1,
          maxSelection: 1,
          options: inline.optionNames.map((n) => ({
            name: n,
            price: 0,
            triggersModifierGroupNames: [],
          })),
        };
        newGroups.push(newGroup);
        continue;
      }

      if (looksLikeGroupHeader(opt.name) && group.options.length > 1) {
        const newGroup: NormalizedModifierGroup = {
          name: opt.name,
          required: true,
          minSelection: 1,
          maxSelection: 1,
          options: [],
        };
        newGroups.push(newGroup);
        continue;
      }

      cleanOptions.push(opt);
    }

    if (cleanOptions.length > 0) {
      result.push({ ...group, options: cleanOptions });
    }
  }

  result.push(...newGroups);
  return result;
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

  let modifierGroups: NormalizedModifierGroup[] = [];
  const mgRaw = o.modifierGroups ?? o.modifier_groups ?? o.modifiers;
  if (Array.isArray(mgRaw)) {
    for (const mg of mgRaw) {
      const normalized = normalizeModifierGroup(mg);
      if (normalized) modifierGroups.push(normalized);
    }
  }

  modifierGroups = splitFlattenedOptions(modifierGroups);

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
