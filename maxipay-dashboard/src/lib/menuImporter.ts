import * as XLSX from "xlsx";
import {
  collection,
  writeBatch,
  doc,
  getDocs,
  deleteDoc,
  Timestamp,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";

// ─── Parsed types from the Excel sheets ──────────────────────────────

export interface ParsedCategory {
  name: string;
}

export interface ParsedItem {
  name: string;
  price: number;
  categoryName: string;
  taxRateName: string;
  modifierGroupNames: string[];
}

export interface ParsedModifierGroup {
  name: string;
  options: { name: string; price: number }[];
}

export interface ParsedTaxRate {
  name: string;
  type: "FIXED" | "PERCENTAGE";
  amount: number;
}

export interface ParsedMenu {
  categories: ParsedCategory[];
  items: ParsedItem[];
  modifierGroups: ParsedModifierGroup[];
  taxRates: ParsedTaxRate[];
}

export interface ImportProgress {
  stage: string;
  current: number;
  total: number;
}

// ─── Excel parsing ───────────────────────────────────────────────────

function normalizeHeader(raw: string): string {
  return raw.toString().trim().toLowerCase().replace(/[^a-z0-9]/g, "");
}

function findColumn(headers: string[], ...candidates: string[]): number {
  for (const c of candidates) {
    const idx = headers.findIndex((h) => normalizeHeader(h) === c);
    if (idx !== -1) return idx;
  }
  return -1;
}

function getSheetRows(workbook: XLSX.WorkBook, sheetName: string): string[][] {
  const sheet = workbook.Sheets[sheetName];
  if (!sheet) return [];
  return XLSX.utils.sheet_to_json<string[]>(sheet, { header: 1, defval: "" });
}

export function parseCloverExcel(buffer: ArrayBuffer): ParsedMenu {
  const workbook = XLSX.read(buffer, { type: "array" });

  const categories = parseCategories(workbook);
  const items = parseItems(workbook);
  const modifierGroups = parseModifierGroups(workbook);
  const taxRates = parseTaxRates(workbook);

  return { categories, items, modifierGroups, taxRates };
}

function parseCategories(wb: XLSX.WorkBook): ParsedCategory[] {
  const rows = getSheetRows(wb, "Categories");
  if (rows.length < 2) return [];

  const headers = rows[0].map(String);
  const nameCol = findColumn(headers, "name", "categoryname", "category");
  if (nameCol === -1) return [];

  const result: ParsedCategory[] = [];
  for (let i = 1; i < rows.length; i++) {
    const name = String(rows[i][nameCol] ?? "").trim();
    if (name) result.push({ name });
  }
  return result;
}

function parseItems(wb: XLSX.WorkBook): ParsedItem[] {
  const rows = getSheetRows(wb, "Items");
  if (rows.length < 2) return [];

  const headers = rows[0].map(String);
  const nameCol = findColumn(headers, "name", "itemname", "item");
  const priceCol = findColumn(headers, "price", "unitprice", "amount");
  const catCol = findColumn(headers, "category", "categoryname", "categories");
  const taxCol = findColumn(headers, "taxrate", "taxrates", "tax", "taxname");
  const modCol = findColumn(
    headers,
    "modifiergroups",
    "modifiergroup",
    "modifiers"
  );

  if (nameCol === -1) return [];

  const result: ParsedItem[] = [];
  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const name = String(row[nameCol] ?? "").trim();
    if (!name) continue;

    const rawPrice = priceCol >= 0 ? row[priceCol] : 0;
    const price = typeof rawPrice === "number" ? rawPrice : parseFloat(String(rawPrice).replace(/[^0-9.]/g, "")) || 0;

    const categoryName = catCol >= 0 ? String(row[catCol] ?? "").trim() : "";
    const taxRateName = taxCol >= 0 ? String(row[taxCol] ?? "").trim() : "";

    const modRaw = modCol >= 0 ? String(row[modCol] ?? "").trim() : "";
    const modifierGroupNames = modRaw
      ? modRaw.split(/[;,|]/).map((s) => s.trim()).filter(Boolean)
      : [];

    result.push({ name, price, categoryName, taxRateName, modifierGroupNames });
  }
  return result;
}

function parseModifierGroups(wb: XLSX.WorkBook): ParsedModifierGroup[] {
  const rows = getSheetRows(wb, "Modifier Groups");
  if (rows.length < 2) return [];

  const headers = rows[0].map(String);
  const nameCol = findColumn(headers, "name", "groupname", "modifiergroupname", "modifiergroup");
  const optNameCol = findColumn(headers, "modifiername", "optionname", "option", "options", "modifier");
  const optPriceCol = findColumn(headers, "modifierprice", "optionprice", "price", "extraprice");

  if (nameCol === -1) return [];

  const groupMap = new Map<string, { name: string; price: number }[]>();

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const groupName = String(row[nameCol] ?? "").trim();
    if (!groupName) continue;

    if (!groupMap.has(groupName)) {
      groupMap.set(groupName, []);
    }

    if (optNameCol >= 0) {
      const optName = String(row[optNameCol] ?? "").trim();
      if (optName) {
        const rawPrice = optPriceCol >= 0 ? row[optPriceCol] : 0;
        const price = typeof rawPrice === "number" ? rawPrice : parseFloat(String(rawPrice).replace(/[^0-9.]/g, "")) || 0;
        groupMap.get(groupName)!.push({ name: optName, price });
      }
    }
  }

  return Array.from(groupMap.entries()).map(([name, options]) => ({
    name,
    options,
  }));
}

function parseTaxRates(wb: XLSX.WorkBook): ParsedTaxRate[] {
  const rows = getSheetRows(wb, "Tax Rates");
  if (rows.length < 2) return [];

  const headers = rows[0].map(String);
  const nameCol = findColumn(headers, "name", "taxname", "taxratename");
  const rateCol = findColumn(headers, "rate", "taxrate", "amount", "percentage", "value");
  const typeCol = findColumn(headers, "type", "taxtype");

  if (nameCol === -1) return [];

  const result: ParsedTaxRate[] = [];
  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const name = String(row[nameCol] ?? "").trim();
    if (!name) continue;

    const rawRate = rateCol >= 0 ? row[rateCol] : 0;
    let amount = typeof rawRate === "number" ? rawRate : parseFloat(String(rawRate).replace(/[^0-9.]/g, "")) || 0;

    let type: "FIXED" | "PERCENTAGE" = "PERCENTAGE";
    if (typeCol >= 0) {
      const t = String(row[typeCol] ?? "").trim().toUpperCase();
      if (t === "FIXED" || t === "FLAT") type = "FIXED";
    }

    // Clover exports percentages as decimals (e.g. 0.08 for 8%)
    if (type === "PERCENTAGE" && amount > 0 && amount < 1) {
      amount = amount * 100;
    }

    result.push({ name, type, amount });
  }
  return result;
}

// ─── Firestore import ────────────────────────────────────────────────

const MAX_BATCH_OPS = 450; // Firestore limit is 500; keep margin

async function clearCollection(collectionName: string) {
  const snap = await getDocs(collection(db, collectionName));
  const batches: ReturnType<typeof writeBatch>[] = [];
  let batch = writeBatch(db);
  let count = 0;

  snap.docs.forEach((d) => {
    batch.delete(d.ref);
    count++;
    if (count >= MAX_BATCH_OPS) {
      batches.push(batch);
      batch = writeBatch(db);
      count = 0;
    }
  });

  if (count > 0) batches.push(batch);
  for (const b of batches) await b.commit();
}

export async function importMenuToFirestore(
  parsed: ParsedMenu,
  onProgress?: (p: ImportProgress) => void
) {
  const totalSteps = 6;
  let step = 0;

  const report = (stage: string) => {
    step++;
    onProgress?.({ stage, current: step, total: totalSteps });
  };

  // 1 ─ Clear existing data
  report("Clearing existing menu data…");
  await Promise.all([
    clearCollection("Categories"),
    clearCollection("MenuItems"),
    clearCollection("ModifierGroups"),
    clearCollection("ModifierOptions"),
    clearCollection("ItemModifierGroups"),
    clearCollection("Taxes"),
  ]);

  // 2 ─ Write categories
  report("Importing categories…");
  const categoryIdMap = new Map<string, string>(); // name → docId
  {
    let batch = writeBatch(db);
    let count = 0;

    for (const cat of parsed.categories) {
      const ref = doc(collection(db, "Categories"));
      categoryIdMap.set(cat.name.toLowerCase(), ref.id);
      batch.set(ref, {
        name: cat.name,
        availableOrderTypes: ["DINE_IN", "TO_GO", "BAR_TAB"],
      });
      count++;
      if (count >= MAX_BATCH_OPS) {
        await batch.commit();
        batch = writeBatch(db);
        count = 0;
      }
    }
    if (count > 0) await batch.commit();
  }

  // 3 ─ Write modifier groups + options
  report("Importing modifier groups…");
  const modGroupIdMap = new Map<string, string>(); // name → docId
  {
    let batch = writeBatch(db);
    let count = 0;

    for (const group of parsed.modifierGroups) {
      const groupRef = doc(collection(db, "ModifierGroups"));
      modGroupIdMap.set(group.name.toLowerCase(), groupRef.id);

      batch.set(groupRef, {
        name: group.name,
        required: false,
        maxSelection: group.options.length || 1,
        groupType: "ADD",
      });
      count++;

      for (const opt of group.options) {
        const optRef = doc(collection(db, "ModifierOptions"));
        batch.set(optRef, {
          groupId: groupRef.id,
          name: opt.name,
          price: opt.price,
        });
        count++;

        if (count >= MAX_BATCH_OPS) {
          await batch.commit();
          batch = writeBatch(db);
          count = 0;
        }
      }
    }
    if (count > 0) await batch.commit();
  }

  // 4 ─ Write tax rates
  report("Importing tax rates…");
  {
    let batch = writeBatch(db);
    let count = 0;

    for (const tax of parsed.taxRates) {
      const ref = doc(collection(db, "Taxes"));
      batch.set(ref, {
        name: tax.name,
        type: tax.type,
        amount: tax.amount,
        enabled: true,
        createdAt: Timestamp.now(),
      });
      count++;
      if (count >= MAX_BATCH_OPS) {
        await batch.commit();
        batch = writeBatch(db);
        count = 0;
      }
    }
    if (count > 0) await batch.commit();
  }

  // 5 ─ Write menu items
  report("Importing menu items…");
  const itemIdMap = new Map<string, string>(); // itemName → docId
  {
    let batch = writeBatch(db);
    let count = 0;

    for (const item of parsed.items) {
      const categoryId =
        categoryIdMap.get(item.categoryName.toLowerCase()) ?? "";

      // Auto-create categories referenced by items but missing from the Categories sheet
      if (!categoryId && item.categoryName) {
        const ref = doc(collection(db, "Categories"));
        categoryIdMap.set(item.categoryName.toLowerCase(), ref.id);
        batch.set(ref, {
          name: item.categoryName,
          availableOrderTypes: ["DINE_IN", "TO_GO", "BAR_TAB"],
        });
        count++;
      }

      const resolvedCategoryId =
        categoryIdMap.get(item.categoryName.toLowerCase()) ?? "";

      const ref = doc(collection(db, "MenuItems"));
      itemIdMap.set(item.name.toLowerCase(), ref.id);

      batch.set(ref, {
        name: item.name,
        price: item.price,
        stock: 9999,
        categoryId: resolvedCategoryId,
      });
      count++;

      if (count >= MAX_BATCH_OPS) {
        await batch.commit();
        batch = writeBatch(db);
        count = 0;
      }
    }
    if (count > 0) await batch.commit();
  }

  // 6 ─ Write item ↔ modifier group links
  report("Linking items to modifier groups…");
  {
    let batch = writeBatch(db);
    let count = 0;

    for (const item of parsed.items) {
      const itemId = itemIdMap.get(item.name.toLowerCase());
      if (!itemId) continue;

      for (let i = 0; i < item.modifierGroupNames.length; i++) {
        const groupId = modGroupIdMap.get(
          item.modifierGroupNames[i].toLowerCase()
        );
        if (!groupId) continue;

        const ref = doc(collection(db, "ItemModifierGroups"));
        batch.set(ref, {
          itemId,
          groupId,
          displayOrder: i + 1,
        });
        count++;

        if (count >= MAX_BATCH_OPS) {
          await batch.commit();
          batch = writeBatch(db);
          count = 0;
        }
      }
    }
    if (count > 0) await batch.commit();
  }

  return {
    categories: categoryIdMap.size,
    items: itemIdMap.size,
    modifierGroups: modGroupIdMap.size,
    taxRates: parsed.taxRates.length,
  };
}
