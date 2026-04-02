import * as XLSX from "xlsx";
import {
  collection,
  writeBatch,
  doc,
  getDocs,
  Timestamp,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import {
  formatCategoryDisplayName,
  normalizeCategoryName,
} from "@/lib/categoryNameUtils";

// ─── Parsed types ────────────────────────────────────────────────────

export interface ParsedCategory {
  id: string;
  name: string;
}

export interface ParsedModifierGroup {
  id: string;
  name: string;
  options: { id: string; name: string; price: number }[];
}

export interface ParsedTaxRate {
  id: string;
  name: string;
  rate: number;
}

export interface ParsedItem {
  id: string;
  name: string;
  price: number;
  categoryId: string;
  modifierGroupIds: string[];
  taxIds: string[];
  orderTypes: string[];
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

export interface ImportLogEntry {
  level: "info" | "warn" | "error";
  message: string;
}

export interface ImportResult {
  categories: number;
  items: number;
  modifierGroups: number;
  modifierOptions: number;
  taxRates: number;
  logs: ImportLogEntry[];
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
  let sheet = workbook.Sheets[sheetName];
  if (!sheet) {
    const normalized = sheetName.toLowerCase().replace(/\s+/g, "");
    const actual = workbook.SheetNames.find(
      (n) => n.toLowerCase().replace(/\s+/g, "") === normalized
    );
    if (actual) sheet = workbook.Sheets[actual];
  }
  if (!sheet) return [];
  return XLSX.utils.sheet_to_json<string[]>(sheet, { header: 1, defval: "" });
}

export function parseCloverExcel(buffer: ArrayBuffer): ParsedMenu {
  const workbook = XLSX.read(buffer, { type: "array" });

  const categories = parseCategories(workbook);
  const taxRates = parseTaxRates(workbook);
  const items = parseItems(workbook);

  const hasModifierOptionsSheet = workbook.SheetNames.some(
    (n) => n.toLowerCase().replace(/\s+/g, "") === "modifieroptions"
  );

  let modifierGroups: ParsedModifierGroup[];

  if (hasModifierOptionsSheet) {
    modifierGroups = parseModifierGroupsOnly(workbook);
    const options = parseModifierOptions(workbook);
    for (const opt of options) {
      const group = modifierGroups.find((g) => g.id === opt.groupId);
      if (group) {
        group.options.push({ id: opt.id, name: opt.name, price: opt.price });
      }
    }
  } else {
    modifierGroups = parseModifierGroupsLegacy(workbook);
  }

  return { categories, items, modifierGroups, taxRates };
}

function parseCategories(wb: XLSX.WorkBook): ParsedCategory[] {
  const rows = getSheetRows(wb, "Categories");
  if (rows.length < 2) return [];

  const headers = rows[0].map(String);
  const idCol = findColumn(headers, "categoryid", "id");
  const nameCol = findColumn(headers, "categoryname", "name", "category");
  if (nameCol === -1) return [];

  const result: ParsedCategory[] = [];
  const seenIds = new Set<string>();

  for (let i = 1; i < rows.length; i++) {
    const name = String(rows[i][nameCol] ?? "").trim();
    if (!name) continue;

    let id = idCol >= 0 ? String(rows[i][idCol] ?? "").trim() : "";
    if (!id) id = `cat_${i}`;

    if (seenIds.has(id)) continue;
    seenIds.add(id);

    result.push({ id, name });
  }
  return result;
}

function parseModifierGroupsOnly(wb: XLSX.WorkBook): ParsedModifierGroup[] {
  const rows = getSheetRows(wb, "Modifier Groups");
  if (rows.length < 2) return [];

  const headers = rows[0].map(String);
  const idCol = findColumn(headers, "modifiergroupid", "groupid", "id");
  const nameCol = findColumn(headers, "modifiergroupname", "groupname", "name");

  if (nameCol === -1) return [];

  const groupMap = new Map<string, ParsedModifierGroup>();
  let autoIdx = 0;

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const groupName = String(row[nameCol] ?? "").trim();
    if (!groupName) continue;

    let groupId = idCol >= 0 ? String(row[idCol] ?? "").trim() : "";
    if (!groupId) groupId = `mod_${++autoIdx}`;

    if (!groupMap.has(groupId)) {
      groupMap.set(groupId, { id: groupId, name: groupName, options: [] });
    }
  }

  return Array.from(groupMap.values());
}

function parseModifierOptions(wb: XLSX.WorkBook) {
  const rows = getSheetRows(wb, "Modifier Options");
  if (rows.length < 2) return [];

  const headers = rows[0].map(String);
  const idCol = findColumn(headers, "optionid", "id");
  const groupIdCol = findColumn(headers, "modifiergroupid", "groupid");
  const nameCol = findColumn(headers, "optionname", "name", "option");
  const priceCol = findColumn(headers, "price", "optionprice", "extraprice");

  if (nameCol === -1 || groupIdCol === -1) return [];

  const result: { id: string; groupId: string; name: string; price: number }[] = [];
  let autoIdx = 0;

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const name = String(row[nameCol] ?? "").trim();
    if (!name) continue;

    const groupId = String(row[groupIdCol] ?? "").trim();
    if (!groupId) continue;

    let id = idCol >= 0 ? String(row[idCol] ?? "").trim() : "";
    if (!id) id = `opt_${++autoIdx}`;

    const rawPrice = priceCol >= 0 ? row[priceCol] : 0;
    const price =
      typeof rawPrice === "number"
        ? rawPrice
        : parseFloat(String(rawPrice).replace(/[^0-9.]/g, "")) || 0;

    result.push({ id, groupId, name, price });
  }

  return result;
}

/** Legacy format: options embedded in the Modifier Groups sheet rows */
function parseModifierGroupsLegacy(wb: XLSX.WorkBook): ParsedModifierGroup[] {
  const rows = getSheetRows(wb, "Modifier Groups");
  if (rows.length < 2) return [];

  const headers = rows[0].map(String);
  const idCol = findColumn(headers, "modifiergroupid", "groupid", "id");
  const nameCol = findColumn(headers, "modifiergroupname", "groupname", "name");
  const optNameCol = findColumn(headers, "optionname", "modifiername", "option", "modifier");
  const optPriceCol = findColumn(headers, "optionprice", "modifierprice", "price", "extraprice");

  if (nameCol === -1) return [];

  const groupMap = new Map<string, ParsedModifierGroup>();
  let autoIdx = 0;

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const groupName = String(row[nameCol] ?? "").trim();
    if (!groupName) continue;

    let groupId = idCol >= 0 ? String(row[idCol] ?? "").trim() : "";
    if (!groupId) groupId = `mod_${++autoIdx}`;

    if (!groupMap.has(groupId)) {
      groupMap.set(groupId, { id: groupId, name: groupName, options: [] });
    }

    if (optNameCol >= 0) {
      const optName = String(row[optNameCol] ?? "").trim();
      if (optName) {
        const rawPrice = optPriceCol >= 0 ? row[optPriceCol] : 0;
        const price =
          typeof rawPrice === "number"
            ? rawPrice
            : parseFloat(String(rawPrice).replace(/[^0-9.]/g, "")) || 0;

        const optId = `${groupId}_opt_${groupMap.get(groupId)!.options.length + 1}`;
        groupMap.get(groupId)!.options.push({ id: optId, name: optName, price });
      }
    }
  }

  return Array.from(groupMap.values());
}

function parseTaxRates(wb: XLSX.WorkBook): ParsedTaxRate[] {
  const rows = getSheetRows(wb, "Taxes");
  if (rows.length < 2) {
    const altRows = getSheetRows(wb, "Tax Rates");
    if (altRows.length >= 2) return parseTaxSheet(altRows);
    return [];
  }
  return parseTaxSheet(rows);
}

function parseTaxSheet(rows: string[][]): ParsedTaxRate[] {
  const headers = rows[0].map(String);
  const idCol = findColumn(headers, "taxid", "id");
  const nameCol = findColumn(headers, "taxname", "name", "taxratename");
  const rateCol = findColumn(headers, "rate", "taxrate", "amount", "percentage", "value");

  if (nameCol === -1) return [];

  const result: ParsedTaxRate[] = [];
  const seenIds = new Set<string>();

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const name = String(row[nameCol] ?? "").trim();
    if (!name) continue;

    let id = idCol >= 0 ? String(row[idCol] ?? "").trim() : "";
    if (!id) id = `tax_${i}`;

    if (seenIds.has(id)) continue;
    seenIds.add(id);

    const rawRate = rateCol >= 0 ? row[rateCol] : 0;
    let rate =
      typeof rawRate === "number"
        ? rawRate
        : parseFloat(String(rawRate).replace(/[^0-9.]/g, "")) || 0;

    if (rate > 0 && rate < 1) {
      rate = rate * 100;
    }

    result.push({ id, name, rate });
  }
  return result;
}

function parseItems(wb: XLSX.WorkBook): ParsedItem[] {
  const rows = getSheetRows(wb, "Items");
  if (rows.length < 2) return [];

  const headers = rows[0].map(String);
  const idCol = findColumn(headers, "itemid", "id");
  const nameCol = findColumn(headers, "name", "itemname", "item");
  const priceCol = findColumn(headers, "price", "unitprice", "amount");
  const catCol = findColumn(headers, "categoryid", "category", "categoryname");
  const modCol = findColumn(headers, "modifiergroupids", "modifiergroups", "modifiers");
  const taxCol = findColumn(headers, "taxids", "taxrates", "taxrate", "tax");
  const orderTypesCol = findColumn(headers, "ordertypes", "ordertype", "availableordertypes");

  if (nameCol === -1) return [];

  const result: ParsedItem[] = [];
  const seenIds = new Set<string>();

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const name = String(row[nameCol] ?? "").trim();
    if (!name) continue;

    let id = idCol >= 0 ? String(row[idCol] ?? "").trim() : "";
    if (!id) id = `item_${i}`;

    if (seenIds.has(id)) continue;
    seenIds.add(id);

    const rawPrice = priceCol >= 0 ? row[priceCol] : 0;
    const price =
      typeof rawPrice === "number"
        ? rawPrice
        : parseFloat(String(rawPrice).replace(/[^0-9.]/g, "")) || 0;

    const categoryId = catCol >= 0 ? String(row[catCol] ?? "").trim() : "";

    const modRaw = modCol >= 0 ? String(row[modCol] ?? "").trim() : "";
    const modifierGroupIds = modRaw
      ? modRaw.split(/[;,|]/).map((s) => s.trim()).filter(Boolean)
      : [];

    const taxRaw = taxCol >= 0 ? String(row[taxCol] ?? "").trim() : "";
    const taxIds = taxRaw
      ? taxRaw.split(/[;,|]/).map((s) => s.trim()).filter(Boolean)
      : [];

    const orderTypesRaw = orderTypesCol >= 0 ? String(row[orderTypesCol] ?? "").trim() : "";
    const orderTypes = orderTypesRaw
      ? orderTypesRaw.split(/[;,|]/).map((s) => s.trim()).filter(Boolean)
      : [];

    result.push({ id, name, price, categoryId, modifierGroupIds, taxIds, orderTypes });
  }
  return result;
}

// ─── Firestore import ────────────────────────────────────────────────

const MAX_BATCH_OPS = 450;

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
): Promise<ImportResult> {
  const totalSteps = 5;
  let step = 0;
  const logs: ImportLogEntry[] = [];

  const log = (level: ImportLogEntry["level"], message: string) => {
    logs.push({ level, message });
    console.log(`[IMPORT ${level.toUpperCase()}] ${message}`);
  };

  const report = (stage: string) => {
    step++;
    onProgress?.({ stage, current: step, total: totalSteps });
  };

  // Build lookup sets for validation
  const categoryIdSet = new Set(parsed.categories.map((c) => c.id));
  const modGroupIdSet = new Set(parsed.modifierGroups.map((m) => m.id));
  const taxIdSet = new Set(parsed.taxRates.map((t) => t.id));

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
  {
    let batch = writeBatch(db);
    let count = 0;

    for (const cat of parsed.categories) {
      const ref = doc(db, "Categories", cat.id);
      batch.set(ref, {
        name: cat.name,
        normalizedName: normalizeCategoryName(cat.name),
        availableOrderTypes: ["DINE_IN", "TO_GO", "BAR_TAB"],
      });
      log("info", `Created category "${cat.name}" (${cat.id})`);
      count++;
      if (count >= MAX_BATCH_OPS) {
        await batch.commit();
        batch = writeBatch(db);
        count = 0;
      }
    }
    if (count > 0) await batch.commit();
  }

  // 3 ─ Write modifier groups with embedded options
  report("Importing modifier groups…");
  {
    let batch = writeBatch(db);
    let count = 0;

    for (const group of parsed.modifierGroups) {
      const ref = doc(db, "ModifierGroups", group.id);
      batch.set(ref, {
        name: group.name,
        required: false,
        minSelection: 0,
        maxSelection: group.options.length || 1,
        groupType: "ADD",
        options: group.options.map((opt) => ({
          id: opt.id,
          name: opt.name,
          price: opt.price,
          triggersModifierGroupIds: [],
        })),
      });
      log("info", `Created modifier group "${group.name}" (${group.id}) with ${group.options.length} options`);
      count++;
      if (count >= MAX_BATCH_OPS) {
        await batch.commit();
        batch = writeBatch(db);
        count = 0;
      }
    }
    if (count > 0) await batch.commit();
  }

  // 4 ─ Write taxes
  report("Importing taxes…");
  {
    let batch = writeBatch(db);
    let count = 0;

    for (const tax of parsed.taxRates) {
      const ref = doc(db, "Taxes", tax.id);
      batch.set(ref, {
        name: tax.name,
        type: "PERCENTAGE",
        amount: tax.rate,
        enabled: true,
        createdAt: Timestamp.now(),
      });
      log("info", `Created tax "${tax.name}" (${tax.id}) at ${tax.rate}%`);
      count++;
      if (count >= MAX_BATCH_OPS) {
        await batch.commit();
        batch = writeBatch(db);
        count = 0;
      }
    }
    if (count > 0) await batch.commit();
  }

  // 5 ─ Write menu items with relational IDs
  report("Importing menu items…");
  let importedItems = 0;
  {
    let batch = writeBatch(db);
    let count = 0;

    // Collect all existing category names for backward-compatible name→ID mapping
    const categoryNameToId = new Map<string, string>();
    for (const cat of parsed.categories) {
      categoryNameToId.set(cat.name.toLowerCase(), cat.id);
    }

    for (const item of parsed.items) {
      // Resolve categoryId: try direct ID match first, then name-based fallback
      let resolvedCategoryId = item.categoryId;
      if (resolvedCategoryId && !categoryIdSet.has(resolvedCategoryId)) {
        const nameMatch = categoryNameToId.get(resolvedCategoryId.toLowerCase());
        if (nameMatch) {
          log("info", `Mapped category name "${item.categoryId}" → ID "${nameMatch}" for item "${item.name}"`);
          resolvedCategoryId = nameMatch;
        } else {
          log("error", `Skipping item "${item.name}": Category ID "${item.categoryId}" does not exist`);
          continue;
        }
      }

      // Validate modifier group IDs
      const validModGroupIds: string[] = [];
      for (const mgId of item.modifierGroupIds) {
        if (modGroupIdSet.has(mgId)) {
          validModGroupIds.push(mgId);
          log("info", `Linked modifier group ${mgId} to item ${item.name}`);
        } else {
          log("warn", `Missing modifier group ${mgId} for item ${item.name}`);
        }
      }

      // Validate tax IDs
      const validTaxIds: string[] = [];
      for (const tId of item.taxIds) {
        if (taxIdSet.has(tId)) {
          validTaxIds.push(tId);
        } else {
          log("warn", `Missing tax ID ${tId} for item ${item.name}`);
        }
      }

      // Order types: store exactly as provided, warn on unknown
      const KNOWN_ORDER_TYPES = ["DINE_IN", "TO_GO", "BAR_TAB"];
      for (const ot of item.orderTypes) {
        if (!KNOWN_ORDER_TYPES.includes(ot)) {
          log("warn", `Unknown order type "${ot}" for item "${item.name}" — stored anyway`);
        }
      }
      if (item.orderTypes.length > 0) {
        log("info", `Assigned order types [${item.orderTypes.join(", ")}] to item ${item.name}`);
      }

      const itemData: Record<string, unknown> = {
        name: item.name,
        price: item.price,
        stock: 9999,
        categoryId: resolvedCategoryId,
        modifierGroupIds: validModGroupIds,
        taxIds: validTaxIds,
        externalMappings: {},
      };

      if (item.orderTypes.length > 0) {
        itemData.availableOrderTypes = item.orderTypes;
      }

      const ref = doc(db, "MenuItems", item.id);
      batch.set(ref, itemData);
      importedItems++;
      count++;

      if (count >= MAX_BATCH_OPS) {
        await batch.commit();
        batch = writeBatch(db);
        count = 0;
      }
    }
    if (count > 0) await batch.commit();
  }

  const totalOptions = parsed.modifierGroups.reduce((sum, g) => sum + g.options.length, 0);
  log("info", `Import complete: ${parsed.categories.length} categories, ${importedItems} items, ${parsed.modifierGroups.length} modifier groups, ${totalOptions} modifier options, ${parsed.taxRates.length} taxes`);

  return {
    categories: parsed.categories.length,
    items: importedItems,
    modifierGroups: parsed.modifierGroups.length,
    modifierOptions: totalOptions,
    taxRates: parsed.taxRates.length,
    logs,
  };
}

// ─── Picture scan → Firestore (add-only, does not replace existing menu) ───

export interface ScannedModifierOptionRow {
  id: string;
  name: string;
  price: number;
  triggersModifierGroupNames: string[];
}

export interface ScannedModifierGroupRow {
  id: string;
  name: string;
  required: boolean;
  minSelection: number;
  maxSelection: number;
  options: ScannedModifierOptionRow[];
}

export interface ScannedMenuItemRow {
  id: string;
  name: string;
  price: number | null;
  priceUncertain?: boolean;
  modifierGroups: ScannedModifierGroupRow[];
}

export interface ScannedSubcategoryRow {
  id: string;
  name: string;
  items: ScannedMenuItemRow[];
}

export interface ScannedMenuCategoryRow {
  id: string;
  name: string;
  subcategories: ScannedSubcategoryRow[];
  items: ScannedMenuItemRow[];
}

export interface ScannedMenuImportResult {
  categories: number;
  subcategories: number;
  items: number;
  modifierGroups: number;
  modifierOptions: number;
}

/**
 * Prepares the flat data mapping for Firestore from the nested scan structure.
 * Returns arrays ready for batch writes but does NOT execute them.
 */
export function prepareScannedMenuForFirestore(rows: ScannedMenuCategoryRow[]) {
  const categoryDocs: Array<{ clientId: string; name: string; normalizedName: string }> = [];
  const subcategoryDocs: Array<{ clientId: string; name: string; parentCategoryClientId: string }> = [];
  const itemDocs: Array<{
    clientId: string;
    name: string;
    price: number;
    categoryClientId: string;
    subcategoryClientId: string | null;
    modifierGroupClientIds: string[];
  }> = [];
  const modifierGroupDocs: Array<{
    clientId: string;
    name: string;
    required: boolean;
    minSelection: number;
    maxSelection: number;
    options: Array<{ clientId: string; name: string; price: number; triggersModifierGroupNames: string[] }>;
  }> = [];

  const seenModifierGroups = new Map<string, string>();

  function processItem(
    it: ScannedMenuItemRow,
    categoryClientId: string,
    subcategoryClientId: string | null
  ) {
    const price =
      it.price != null && !Number.isNaN(it.price) ? Math.max(0, it.price) : 0;
    const mgIds: string[] = [];

    for (const mg of it.modifierGroups) {
      const mgName = mg.name.trim();
      if (!mgName) continue;
      const dedupeKey = mgName.toLowerCase();
      let existingId = seenModifierGroups.get(dedupeKey);
      if (!existingId) {
        existingId = mg.id;
        seenModifierGroups.set(dedupeKey, existingId);
        modifierGroupDocs.push({
          clientId: mg.id,
          name: mgName,
          required: mg.required ?? false,
          minSelection: mg.minSelection ?? (mg.required ? 1 : 0),
          maxSelection: mg.maxSelection ?? 1,
          options: mg.options
            .filter((o) => o.name.trim())
            .map((o) => ({
              clientId: o.id,
              name: o.name.trim(),
              price: Math.max(0, o.price),
              triggersModifierGroupNames: o.triggersModifierGroupNames ?? [],
            })),
        });
      }
      mgIds.push(existingId);
    }

    itemDocs.push({
      clientId: it.id,
      name: it.name.trim(),
      price,
      categoryClientId,
      subcategoryClientId,
      modifierGroupClientIds: mgIds,
    });
  }

  for (const cat of rows) {
    const catName = cat.name.trim();
    if (!catName) continue;
    categoryDocs.push({
      clientId: cat.id,
      name: formatCategoryDisplayName(catName),
      normalizedName: normalizeCategoryName(catName) || "general",
    });

    for (const sub of cat.subcategories) {
      const subName = sub.name.trim();
      if (!subName) continue;
      subcategoryDocs.push({
        clientId: sub.id,
        name: formatCategoryDisplayName(subName),
        parentCategoryClientId: cat.id,
      });
      for (const it of sub.items) {
        if (!it.name.trim()) continue;
        processItem(it, cat.id, sub.id);
      }
    }

    for (const it of cat.items) {
      if (!it.name.trim()) continue;
      processItem(it, cat.id, null);
    }
  }

  return { categoryDocs, subcategoryDocs, itemDocs, modifierGroupDocs };
}

export async function importScannedMenuToFirestore(
  rows: ScannedMenuCategoryRow[],
  onProgress?: (p: ImportProgress) => void
): Promise<ScannedMenuImportResult> {
  const prepared = prepareScannedMenuForFirestore(rows);

  const catClientToFirestore = new Map<string, string>();
  const subClientToFirestore = new Map<string, string>();
  const mgClientToFirestore = new Map<string, string>();
  let batch = writeBatch(db);
  let count = 0;

  const commitIfNeeded = async () => {
    if (count >= MAX_BATCH_OPS) {
      await batch.commit();
      batch = writeBatch(db);
      count = 0;
    }
  };

  onProgress?.({ stage: "Loading categories…", current: 0, total: 4 });

  const existingSnap = await getDocs(collection(db, "Categories"));
  const normToFirestoreId = new Map<string, string>();
  for (const d of existingSnap.docs) {
    const name = (d.get("name") as string) || "";
    const stored = d.get("normalizedName") as string | undefined;
    const rawKey =
      stored && String(stored).trim().length > 0 ? String(stored).trim() : name;
    const key = normalizeCategoryName(rawKey);
    if (key.length > 0 && !normToFirestoreId.has(key)) {
      normToFirestoreId.set(key, d.id);
    }
  }

  // 1 ─ Categories
  onProgress?.({ stage: "Creating categories…", current: 1, total: 4 });
  let newCategoriesCreated = 0;

  for (const cat of prepared.categoryDocs) {
    let firestoreId = normToFirestoreId.get(cat.normalizedName);
    if (firestoreId === undefined) {
      const ref = doc(collection(db, "Categories"));
      batch.set(ref, {
        name: cat.name,
        normalizedName: cat.normalizedName,
        availableOrderTypes: ["DINE_IN", "TO_GO", "BAR_TAB"],
        scheduleIds: [],
      });
      firestoreId = ref.id;
      normToFirestoreId.set(cat.normalizedName, firestoreId);
      newCategoriesCreated++;
      count++;
      await commitIfNeeded();
    }
    catClientToFirestore.set(cat.clientId, firestoreId);
  }
  if (count > 0) await batch.commit();
  batch = writeBatch(db);
  count = 0;

  // 2 ─ Subcategories (stored as Categories with parentCategoryId)
  let newSubcategoriesCreated = 0;
  for (const sub of prepared.subcategoryDocs) {
    const parentFid = catClientToFirestore.get(sub.parentCategoryClientId);
    if (!parentFid) continue;
    const norm = normalizeCategoryName(sub.name) || "general";
    let firestoreId = normToFirestoreId.get(norm);
    if (firestoreId === undefined) {
      const ref = doc(collection(db, "Categories"));
      batch.set(ref, {
        name: sub.name,
        normalizedName: norm,
        parentCategoryId: parentFid,
        availableOrderTypes: ["DINE_IN", "TO_GO", "BAR_TAB"],
        scheduleIds: [],
      });
      firestoreId = ref.id;
      normToFirestoreId.set(norm, firestoreId);
      newSubcategoriesCreated++;
      count++;
      await commitIfNeeded();
    }
    subClientToFirestore.set(sub.clientId, firestoreId);
  }
  if (count > 0) await batch.commit();
  batch = writeBatch(db);
  count = 0;

  // 3 ─ Modifier groups (first pass: create docs and collect name→ID map)
  onProgress?.({ stage: "Creating modifier groups…", current: 2, total: 4 });
  let newModifierGroups = 0;
  let totalModifierOptions = 0;

  const mgNameToFirestoreId = new Map<string, string>();
  for (const mg of prepared.modifierGroupDocs) {
    const ref = doc(collection(db, "ModifierGroups"));
    batch.set(ref, {
      name: mg.name,
      required: mg.required,
      minSelection: mg.minSelection,
      maxSelection: mg.maxSelection,
      groupType: "ADD",
      options: mg.options.map((opt) => ({
        id: opt.clientId,
        name: opt.name,
        price: opt.price,
        triggersModifierGroupIds: [],
      })),
    });
    mgClientToFirestore.set(mg.clientId, ref.id);
    mgNameToFirestoreId.set(mg.name.toLowerCase(), ref.id);
    newModifierGroups++;
    totalModifierOptions += mg.options.length;
    count++;
    await commitIfNeeded();
  }
  if (count > 0) await batch.commit();
  batch = writeBatch(db);
  count = 0;

  // 3b ─ Second pass: resolve triggersModifierGroupNames → triggersModifierGroupIds
  for (const mg of prepared.modifierGroupDocs) {
    const firestoreId = mgClientToFirestore.get(mg.clientId);
    if (!firestoreId) continue;
    const needsUpdate = mg.options.some((o) => o.triggersModifierGroupNames.length > 0);
    if (!needsUpdate) continue;
    const resolvedOptions = mg.options.map((opt) => {
      const triggerIds = opt.triggersModifierGroupNames
        .map((n) => mgNameToFirestoreId.get(n.toLowerCase()))
        .filter((id): id is string => !!id);
      return {
        id: opt.clientId,
        name: opt.name,
        price: opt.price,
        triggersModifierGroupIds: triggerIds,
      };
    });
    const ref = doc(db, "ModifierGroups", firestoreId);
    batch.update(ref, { options: resolvedOptions });
    count++;
    await commitIfNeeded();
  }
  if (count > 0) await batch.commit();
  batch = writeBatch(db);
  count = 0;

  // 4 ─ Menu items
  onProgress?.({ stage: "Importing menu items…", current: 3, total: 4 });
  let itemCount = 0;
  for (const it of prepared.itemDocs) {
    const catFid = catClientToFirestore.get(it.categoryClientId);
    if (!catFid) continue;
    const subFid = it.subcategoryClientId
      ? subClientToFirestore.get(it.subcategoryClientId)
      : undefined;

    const resolvedMgIds = it.modifierGroupClientIds
      .map((cid) => mgClientToFirestore.get(cid))
      .filter((id): id is string => !!id);

    const effectiveCategoryId = subFid ?? catFid;

    const ref = doc(collection(db, "MenuItems"));
    batch.set(ref, {
      name: it.name,
      price: it.price,
      prices: { default: it.price },
      pricing: { pos: it.price, online: it.price },
      channels: { pos: true, online: false },
      stock: 9999,
      categoryId: effectiveCategoryId,
      menuId: "",
      menuIds: [],
      isScheduled: false,
      scheduleIds: [],
      modifierGroupIds: resolvedMgIds,
      taxIds: [],
      externalMappings: {},
    });
    itemCount++;
    count++;
    await commitIfNeeded();
  }
  if (count > 0) await batch.commit();

  return {
    categories: newCategoriesCreated,
    subcategories: newSubcategoriesCreated,
    items: itemCount,
    modifierGroups: newModifierGroups,
    modifierOptions: totalModifierOptions,
  };
}
