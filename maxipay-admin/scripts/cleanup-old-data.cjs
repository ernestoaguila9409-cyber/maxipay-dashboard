/**
 * One-time script to delete all old/unscoped data from Firestore.
 * Run from maxipay-admin/:  node scripts/cleanup-old-data.cjs
 *
 * Reads FIREBASE_SERVICE_ACCOUNT_JSON from .env.local automatically.
 * Deletes both legacy root-level collections AND merchant-scoped subcollections.
 * Does NOT delete: Merchants doc itself (admin accounts)
 */

const fs = require("fs");
const path = require("path");
const admin = require("firebase-admin");

// ── Load .env.local ──
const envPath = path.join(__dirname, "..", ".env.local");
if (fs.existsSync(envPath)) {
  const lines = fs.readFileSync(envPath, "utf-8").split("\n");
  for (const line of lines) {
    const eq = line.indexOf("=");
    if (eq > 0) {
      const key = line.slice(0, eq).trim();
      const val = line.slice(eq + 1).trim();
      if (key && val && !process.env[key]) {
        process.env[key] = val;
      }
    }
  }
}

// ── Init Firebase Admin ──
const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
if (!raw) {
  console.error("FIREBASE_SERVICE_ACCOUNT_JSON not found. Check .env.local");
  process.exit(1);
}

const sa = JSON.parse(raw);
if (sa.private_key) {
  sa.private_key = sa.private_key.replace(/\\n/g, "\n");
}

admin.initializeApp({ credential: admin.credential.cert(sa) });
const db = admin.firestore();

// ── Collections to wipe ──
const COLLECTIONS_TO_DELETE = [
  "Orders",
  "Transactions",
  "Batches",
  "cashLogs",
  "Categories",
  "subcategories",
  "MenuItems",
  "ModifierGroups",
  "ModifierOptions",
  "ItemModifierGroups",
  "menus",
  "menuSchedules",
  "discounts",
  "Employees",
  "Customers",
  "Taxes",
  "payment_terminals",
  "Printers",
  "PosDevices",
  "DeviceActivations",
  "kds_devices",
  "Sections",
  "tableLayouts",
  "Tables",
  "Reservations",
  "Counters",
  "Settings",
  "settings",
  "OnlineHeroSlides",
  "RemotePaymentCommands",
  "OnlineTerminalPaymentRequests",
  "Terminals",
];

async function deleteCollection(name) {
  const batchSize = 200;
  let deleted = 0;

  while (true) {
    const snap = await db.collection(name).limit(batchSize).get();
    if (snap.empty) break;

    const batch = db.batch();
    for (const doc of snap.docs) {
      batch.delete(doc.ref);
    }
    await batch.commit();
    deleted += snap.size;
    process.stdout.write(`  ${name}: ${deleted} deleted so far...\r`);
  }

  return deleted;
}

const MERCHANT_SUBCOLLECTIONS = [
  "orders", "categories", "subcategories", "menuItems", "modifierGroups",
  "modifierOptions", "itemModifierGroups", "menus", "menuSchedules",
  "employees", "customers", "taxes", "discounts", "printers", "batches",
  "transactions", "cashLogs", "kds_devices", "payment_terminals", "sections",
  "tableLayouts", "tables", "posDevices", "deviceActivations", "reservations",
  "settings", "onlineHeroSlides", "remotePaymentCommands",
  "onlineTerminalPaymentRequests", "counters",
];

async function deleteSubcollection(merchantRef, subName) {
  const batchSize = 200;
  let deleted = 0;
  while (true) {
    const snap = await merchantRef.collection(subName).limit(batchSize).get();
    if (snap.empty) break;
    const batch = db.batch();
    for (const doc of snap.docs) { batch.delete(doc.ref); }
    await batch.commit();
    deleted += snap.size;
  }
  return deleted;
}

async function main() {
  console.log("=== Firestore Cleanup — Old Unscoped Data ===\n");
  console.log(`Project: ${sa.project_id}`);
  console.log(`Root collections to wipe: ${COLLECTIONS_TO_DELETE.length}`);
  console.log("NOT deleting: Merchants doc itself\n");

  let totalDeleted = 0;

  for (const coll of COLLECTIONS_TO_DELETE) {
    const count = await deleteCollection(coll);
    if (count > 0) {
      console.log(`  ${coll}: ${count} documents deleted`);
    } else {
      console.log(`  ${coll}: empty (skipped)`);
    }
    totalDeleted += count;
  }

  console.log("\n── Cleaning merchant subcollections ──");
  const merchants = await db.collection("Merchants").get();
  for (const mDoc of merchants.docs) {
    console.log(`\n  Merchant: ${mDoc.id}`);
    for (const sub of MERCHANT_SUBCOLLECTIONS) {
      const count = await deleteSubcollection(mDoc.ref, sub);
      if (count > 0) {
        console.log(`    ${sub}: ${count} deleted`);
        totalDeleted += count;
      }
    }
  }

  console.log(`\nDone. ${totalDeleted} total documents deleted.`);
  process.exit(0);
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
