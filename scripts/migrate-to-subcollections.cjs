/**
 * One-time data migration: copies documents from flat root-level collections
 * into Merchants/{merchantId}/<subcollection> subcollections.
 *
 * Usage:
 *   GOOGLE_APPLICATION_CREDENTIALS=path/to/serviceAccountKey.json node scripts/migrate-to-subcollections.cjs [--dry-run]
 *
 * Flags:
 *   --dry-run   Print what would happen without writing anything.
 *   --delete    Delete source docs after successful copy (default: keep originals).
 */

const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

const DRY_RUN = process.argv.includes("--dry-run");
const DELETE_ORIGINALS = process.argv.includes("--delete");
const BATCH_LIMIT = 400; // Firestore batch limit is 500; leave room for subcollections

/**
 * Root-level collection name -> new subcollection name under Merchants/{mid}/.
 */
const COLLECTION_MAP = {
  Orders: "orders",
  Categories: "categories",
  subcategories: "subcategories",
  MenuItems: "menuItems",
  ModifierGroups: "modifierGroups",
  ModifierOptions: "modifierOptions",
  ItemModifierGroups: "itemModifierGroups",
  menus: "menus",
  menuSchedules: "menuSchedules",
  Employees: "employees",
  Customers: "customers",
  Taxes: "taxes",
  discounts: "discounts",
  Printers: "printers",
  Batches: "batches",
  Transactions: "transactions",
  cashLogs: "cashLogs",
  kds_devices: "kds_devices",
  payment_terminals: "payment_terminals",
  Sections: "sections",
  tableLayouts: "tableLayouts",
  Tables: "tables",
  PosDevices: "posDevices",
  DeviceActivations: "deviceActivations",
  Reservations: "reservations",
  Settings: "settings",
  settings: "settings",
  OnlineHeroSlides: "onlineHeroSlides",
  RemotePaymentCommands: "remotePaymentCommands",
  OnlineTerminalPaymentRequests: "onlineTerminalPaymentRequests",
  Counters: "counters",
};

/**
 * Subcollections that live under a parent doc in the OLD structure.
 * We copy these nested subcollections as-is under the new parent.
 */
const KNOWN_SUBCOLLECTIONS = {
  Orders: ["items", "transactions"],
  Printers: ["commands"],
  kds_devices: ["settings"],
  tableLayouts: ["tables"],
};

async function resolveDefaultMerchantId() {
  const snap = await db.collection("Merchants").limit(2).get();
  if (snap.empty) {
    throw new Error("No merchants found in Merchants collection.");
  }
  if (snap.size === 1) {
    return snap.docs[0].id;
  }
  console.log(`Found ${snap.size} merchants. Migration will route each doc by its merchantId field.`);
  return null;
}

async function copySubcollections(sourceDocRef, destDocRef, subcollectionNames) {
  let count = 0;
  for (const subName of subcollectionNames) {
    const subSnap = await sourceDocRef.collection(subName).get();
    if (subSnap.empty) continue;

    const batch = db.batch();
    let batchCount = 0;
    for (const subDoc of subSnap.docs) {
      const destSubRef = destDocRef.collection(subName).doc(subDoc.id);
      if (!DRY_RUN) {
        batch.set(destSubRef, subDoc.data());
      }
      batchCount++;
      count++;
    }
    if (!DRY_RUN && batchCount > 0) {
      await batch.commit();
    }
    console.log(`    ↳ ${subName}: ${batchCount} sub-docs`);
  }
  return count;
}

async function migrateCollection(rootName, newName, defaultMerchantId) {
  console.log(`\n── Migrating ${rootName} → {mid}/${newName} ──`);

  const allDocs = await db.collection(rootName).get();
  if (allDocs.empty) {
    console.log(`  (empty, skipping)`);
    return { docs: 0, subDocs: 0 };
  }
  console.log(`  Found ${allDocs.size} docs`);

  let docCount = 0;
  let subDocCount = 0;
  let batch = db.batch();
  let batchCount = 0;

  for (const docSnap of allDocs.docs) {
    const data = docSnap.data();
    const merchantId = data.merchantId || defaultMerchantId;

    if (!merchantId) {
      console.warn(`  ⚠ Skipping ${rootName}/${docSnap.id} — no merchantId field and no default`);
      continue;
    }

    const destRef = db
      .collection("Merchants")
      .doc(merchantId)
      .collection(newName)
      .doc(docSnap.id);

    if (!DRY_RUN) {
      batch.set(destRef, data);
      batchCount++;
    }
    docCount++;

    if (batchCount >= BATCH_LIMIT) {
      await batch.commit();
      batch = db.batch();
      batchCount = 0;
    }

    const subcols = KNOWN_SUBCOLLECTIONS[rootName];
    if (subcols) {
      subDocCount += await copySubcollections(docSnap.ref, destRef, subcols);
    }
  }

  if (!DRY_RUN && batchCount > 0) {
    await batch.commit();
  }

  console.log(`  ✓ ${docCount} docs copied${subDocCount ? ` + ${subDocCount} sub-docs` : ""}`);

  if (DELETE_ORIGINALS && !DRY_RUN) {
    console.log(`  🗑 Deleting originals...`);
    const deleteBatch = db.batch();
    let delCount = 0;
    for (const docSnap of allDocs.docs) {
      deleteBatch.delete(docSnap.ref);
      delCount++;
      if (delCount >= BATCH_LIMIT) {
        await deleteBatch.commit();
        delCount = 0;
      }
    }
    if (delCount > 0) await deleteBatch.commit();
    console.log(`  ✓ Originals deleted`);
  }

  return { docs: docCount, subDocs: subDocCount };
}

async function main() {
  console.log("=== Firestore Subcollection Migration ===");
  console.log(`Mode: ${DRY_RUN ? "DRY RUN (no writes)" : "LIVE"}`);
  console.log(`Delete originals: ${DELETE_ORIGINALS ? "YES" : "NO (keeping originals)"}\n`);

  const defaultMerchantId = await resolveDefaultMerchantId();
  if (defaultMerchantId) {
    console.log(`Default merchantId: ${defaultMerchantId}\n`);
  }

  let totalDocs = 0;
  let totalSubDocs = 0;

  const alreadyProcessed = new Set();
  for (const [rootName, newName] of Object.entries(COLLECTION_MAP)) {
    const key = `${rootName}→${newName}`;
    if (alreadyProcessed.has(key)) continue;
    alreadyProcessed.add(key);

    const { docs, subDocs } = await migrateCollection(rootName, newName, defaultMerchantId);
    totalDocs += docs;
    totalSubDocs += subDocs;
  }

  console.log(`\n=== Migration Complete ===`);
  console.log(`Total: ${totalDocs} docs + ${totalSubDocs} sub-docs`);
  if (DRY_RUN) console.log("(Dry run — nothing was written)");
}

main().catch((err) => {
  console.error("Migration failed:", err);
  process.exit(1);
});
