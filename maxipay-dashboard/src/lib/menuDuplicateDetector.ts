import { collection, getDocs } from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";

// ─── Types ──────────────────────────────────────────────────────────────

export interface ExistingMenuItem {
  id: string;
  name: string;
  price: number;
  categoryId: string;
}

export interface ScannedItem {
  id: string;
  name: string;
  price: number | null;
  categoryName: string;
}

export interface PossibleDuplicate {
  scannedItem: ScannedItem;
  matchedItem: ExistingMenuItem;
  similarity: number;
  priceChanged: boolean;
  oldPrice: number;
  newPrice: number | null;
}

export interface DuplicateItem {
  scannedItem: ScannedItem;
  matchedItem: ExistingMenuItem;
  priceChanged: boolean;
  oldPrice: number;
  newPrice: number | null;
}

export interface DuplicateDetectionResult {
  newItems: ScannedItem[];
  duplicateItems: DuplicateItem[];
  possibleDuplicates: PossibleDuplicate[];
}

// ─── Normalization ──────────────────────────────────────────────────────

function singularize(word: string): string {
  if (word.length > 3 && word.endsWith("ies")) {
    return word.slice(0, -3) + "y";
  }
  if (word.length > 2 && word.endsWith("s") && !word.endsWith("ss")) {
    return word.slice(0, -1);
  }
  return word;
}

export function normalizeAdvanced(name: string): string {
  const lower = name.toLowerCase();
  const cleaned = lower.replace(/[^a-z0-9\s]/g, "");
  const words = cleaned.replace(/\s+/g, " ").trim().split(" ").filter(Boolean);
  return words.map(singularize).join(" ");
}

// ─── Levenshtein distance ───────────────────────────────────────────────

export function levenshteinDistance(a: string, b: string): number {
  const m = a.length;
  const n = b.length;

  if (m === 0) return n;
  if (n === 0) return m;

  let prev = new Array<number>(n + 1);
  let curr = new Array<number>(n + 1);

  for (let j = 0; j <= n; j++) prev[j] = j;

  for (let i = 1; i <= m; i++) {
    curr[0] = i;
    for (let j = 1; j <= n; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      curr[j] = Math.min(
        prev[j] + 1,
        curr[j - 1] + 1,
        prev[j - 1] + cost,
      );
    }
    [prev, curr] = [curr, prev];
  }

  return prev[n];
}

export function similarityScore(a: string, b: string): number {
  const maxLen = Math.max(a.length, b.length);
  if (maxLen === 0) return 1;
  return 1 - levenshteinDistance(a, b) / maxLen;
}

// ─── Firestore loader ───────────────────────────────────────────────────

export async function fetchExistingMenuItems(): Promise<ExistingMenuItem[]> {
  const snap = await getDocs(collection(db, "MenuItems"));
  const items: ExistingMenuItem[] = [];

  for (const d of snap.docs) {
    const name = (d.get("name") as string) || "";
    const price =
      typeof d.get("price") === "number" ? (d.get("price") as number) : 0;
    const categoryId = (d.get("categoryId") as string) || "";
    if (name.trim()) {
      items.push({ id: d.id, name, price, categoryId });
    }
  }

  return items;
}

// ─── Main detection ─────────────────────────────────────────────────────

const FUZZY_THRESHOLD = 0.85;

export function detectDuplicates(
  scannedItems: ScannedItem[],
  existingItems: ExistingMenuItem[],
): DuplicateDetectionResult {
  const normalizedMap = new Map<string, ExistingMenuItem>();
  for (const item of existingItems) {
    const key = normalizeAdvanced(item.name);
    if (key && !normalizedMap.has(key)) {
      normalizedMap.set(key, item);
    }
  }

  const existingNormalized = existingItems.map((item) => ({
    item,
    normalized: normalizeAdvanced(item.name),
  }));

  const newItems: ScannedItem[] = [];
  const duplicateItems: DuplicateItem[] = [];
  const possibleDuplicates: PossibleDuplicate[] = [];

  for (const scanned of scannedItems) {
    const scannedNorm = normalizeAdvanced(scanned.name);

    const exactMatch = normalizedMap.get(scannedNorm);
    if (exactMatch) {
      const priceChanged =
        scanned.price != null && scanned.price !== exactMatch.price;
      duplicateItems.push({
        scannedItem: scanned,
        matchedItem: exactMatch,
        priceChanged,
        oldPrice: exactMatch.price,
        newPrice: scanned.price,
      });
      continue;
    }

    let bestMatch: ExistingMenuItem | null = null;
    let bestScore = 0;

    for (const { item, normalized } of existingNormalized) {
      const score = similarityScore(scannedNorm, normalized);
      if (score > bestScore) {
        bestScore = score;
        bestMatch = item;
      }
    }

    if (bestMatch && bestScore > FUZZY_THRESHOLD) {
      const priceChanged =
        scanned.price != null && scanned.price !== bestMatch.price;
      possibleDuplicates.push({
        scannedItem: scanned,
        matchedItem: bestMatch,
        similarity: Math.round(bestScore * 100) / 100,
        priceChanged,
        oldPrice: bestMatch.price,
        newPrice: scanned.price,
      });
    } else {
      newItems.push(scanned);
    }
  }

  return { newItems, duplicateItems, possibleDuplicates };
}

// ─── Convenience: flatten scanned categories into ScannedItem[] ────────

export function flattenScannedCategories(
  categories: Array<{
    id: string;
    name: string;
    items: Array<{ id: string; name: string; price: number | null }>;
    subcategories?: Array<{
      id: string;
      name: string;
      items: Array<{ id: string; name: string; price: number | null }>;
    }>;
  }>,
): ScannedItem[] {
  const result: ScannedItem[] = [];
  for (const cat of categories) {
    for (const item of cat.items) {
      if (item.name.trim()) {
        result.push({
          id: item.id,
          name: item.name,
          price: item.price,
          categoryName: cat.name,
        });
      }
    }
    if (cat.subcategories) {
      for (const sub of cat.subcategories) {
        for (const item of sub.items) {
          if (item.name.trim()) {
            result.push({
              id: item.id,
              name: item.name,
              price: item.price,
              categoryName: `${cat.name} > ${sub.name}`,
            });
          }
        }
      }
    }
  }
  return result;
}
