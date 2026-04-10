/**
 * Shared table layout model for Web Dashboard + Android POS.
 *
 * Collections (top-level — matches existing app pattern: Orders, MenuItems, …):
 *   tableLayouts/{layoutId}
 *   tableLayouts/{layoutId}/tables/{tableId}
 *
 * Android reads the default layout (isDefault) or lowest sortOrder; falls back to legacy `Tables` if no layouts.
 */
import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDocs,
  limit,
  onSnapshot,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
  writeBatch,
  type DocumentData,
  type Firestore,
  type Timestamp,
  type Unsubscribe,
} from "firebase/firestore";

export const TABLE_LAYOUTS_COLLECTION = "tableLayouts";
export const TABLES_SUBCOLLECTION = "tables";
/** Same collection Android Setup + Dine In use for floor-plan chips. */
export const SECTIONS_COLLECTION = "Sections";

/** Matches Android TableShapeView.shapeToString / shapeFromString (+ CIRCLE → ROUND at save boundary). */
export type TableShape = "SQUARE" | "RECTANGLE" | "ROUND" | "BOOTH";

export type AreaType = "DINING_TABLE" | "BAR_SEAT";

export interface TableLayoutDocument {
  name: string;
  isDefault: boolean;
  /** Logical canvas size (same units as table x/y/width/height). Android scales to screen. */
  canvasWidth: number;
  canvasHeight: number;
  sortOrder: number;
  createdAt?: Timestamp | null;
  updatedAt?: Timestamp | null;
}

export interface TableLayoutTableDocument {
  name: string;
  /** Short code for tickets / reporting (optional). */
  code: string;
  capacity: number;
  shape: TableShape;
  x: number;
  y: number;
  width: number;
  height: number;
  /** Degrees, 0–360; Android may ignore until UI supports rotation. */
  rotation: number;
  section: string;
  isActive: boolean;
  sortOrder: number;
  areaType: AreaType;
  createdAt?: Timestamp | null;
  updatedAt?: Timestamp | null;
}

export const DEFAULT_CANVAS = { width: 1200, height: 800 } as const;

export const DEFAULT_TABLE_SIZE: Pick<TableLayoutTableDocument, "width" | "height"> = {
  width: 100,
  height: 80,
};

function normalizeShape(raw: string | undefined): TableShape {
  const u = (raw ?? "SQUARE").toUpperCase();
  if (u === "CIRCLE") return "ROUND";
  if (u === "ROUND" || u === "RECTANGLE" || u === "BOOTH") return u;
  return "SQUARE";
}

export function layoutDocRef(db: Firestore, layoutId: string) {
  return doc(db, TABLE_LAYOUTS_COLLECTION, layoutId);
}

export function tablesCollectionRef(db: Firestore, layoutId: string) {
  return collection(db, TABLE_LAYOUTS_COLLECTION, layoutId, TABLES_SUBCOLLECTION);
}

export function subscribeTableLayouts(
  db: Firestore,
  onData: (layouts: Array<{ id: string; data: TableLayoutDocument }>) => void,
  onError?: (e: Error) => void
): Unsubscribe {
  return onSnapshot(
    collection(db, TABLE_LAYOUTS_COLLECTION),
    (snap) => {
      const list = snap.docs
        .map((d) => ({
          id: d.id,
          data: parseLayoutDoc(d.id, d.data()),
        }))
        .sort((a, b) => {
          const so = a.data.sortOrder - b.data.sortOrder;
          if (so !== 0) return so;
          return a.data.name.localeCompare(b.data.name);
        });
      onData(list);
    },
    (err) => onError?.(err)
  );
}

export function subscribeLayoutTables(
  db: Firestore,
  layoutId: string,
  onData: (tables: Array<{ id: string; data: TableLayoutTableDocument }>) => void,
  onError?: (e: Error) => void
): Unsubscribe {
  return onSnapshot(
    tablesCollectionRef(db, layoutId),
    (snap) => {
      const list = snap.docs
        .map((d) => ({
          id: d.id,
          data: parseTableDoc(d.data()),
        }))
        .sort((a, b) => {
          const so = a.data.sortOrder - b.data.sortOrder;
          if (so !== 0) return so;
          return a.data.name.localeCompare(b.data.name);
        });
      onData(list);
    },
    (err) => onError?.(err)
  );
}

function parseLayoutDoc(id: string, raw: DocumentData): TableLayoutDocument {
  return {
    name: String(raw.name ?? "Layout"),
    isDefault: raw.isDefault === true,
    canvasWidth: typeof raw.canvasWidth === "number" ? raw.canvasWidth : DEFAULT_CANVAS.width,
    canvasHeight: typeof raw.canvasHeight === "number" ? raw.canvasHeight : DEFAULT_CANVAS.height,
    sortOrder: typeof raw.sortOrder === "number" ? raw.sortOrder : 0,
    createdAt: raw.createdAt ?? null,
    updatedAt: raw.updatedAt ?? null,
  };
}

function parseTableDoc(raw: DocumentData): TableLayoutTableDocument {
  const cap =
    typeof raw.capacity === "number"
      ? raw.capacity
      : typeof raw.seats === "number"
        ? raw.seats
        : Number(raw.capacity ?? raw.seats ?? 4);
  const x =
    typeof raw.x === "number"
      ? raw.x
      : typeof raw.posX === "number"
        ? raw.posX
        : Number(raw.x ?? raw.posX ?? 0);
  const y =
    typeof raw.y === "number"
      ? raw.y
      : typeof raw.posY === "number"
        ? raw.posY
        : Number(raw.y ?? raw.posY ?? 0);
  return {
    name: String(raw.name ?? "Table"),
    code: String(raw.code ?? ""),
    capacity: Number.isFinite(cap) ? Math.round(cap) : 4,
    shape: normalizeShape(String(raw.shape ?? "SQUARE")),
    x,
    y,
    width: typeof raw.width === "number" ? raw.width : DEFAULT_TABLE_SIZE.width,
    height: typeof raw.height === "number" ? raw.height : DEFAULT_TABLE_SIZE.height,
    rotation: typeof raw.rotation === "number" ? raw.rotation : 0,
    section: String(raw.section ?? ""),
    isActive: raw.isActive !== false && raw.active !== false,
    sortOrder: typeof raw.sortOrder === "number" ? raw.sortOrder : 0,
    areaType: String(raw.areaType ?? "DINING_TABLE") === "BAR_SEAT" ? "BAR_SEAT" : "DINING_TABLE",
    createdAt: raw.createdAt ?? null,
    updatedAt: raw.updatedAt ?? null,
  };
}

export async function createTableLayout(
  db: Firestore,
  partial: Partial<TableLayoutDocument> & Pick<TableLayoutDocument, "name">
): Promise<string> {
  const ref = doc(collection(db, TABLE_LAYOUTS_COLLECTION));
  const snap = await getDocs(collection(db, TABLE_LAYOUTS_COLLECTION));
  const sortOrder = snap.size;
  await setDoc(ref, {
    name: partial.name,
    isDefault: partial.isDefault ?? snap.empty,
    canvasWidth: partial.canvasWidth ?? DEFAULT_CANVAS.width,
    canvasHeight: partial.canvasHeight ?? DEFAULT_CANVAS.height,
    sortOrder: partial.sortOrder ?? sortOrder,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  if (snap.empty || partial.isDefault) {
    await setDefaultTableLayout(db, ref.id);
  }
  return ref.id;
}

export async function updateTableLayoutMeta(
  db: Firestore,
  layoutId: string,
  patch: Partial<Pick<TableLayoutDocument, "name" | "canvasWidth" | "canvasHeight" | "sortOrder">>
): Promise<void> {
  await updateDoc(layoutDocRef(db, layoutId), {
    ...patch,
    updatedAt: serverTimestamp(),
  });
}

/** Ensures exactly one default layout (best-effort batch). */
export async function setDefaultTableLayout(db: Firestore, layoutId: string): Promise<void> {
  const snap = await getDocs(collection(db, TABLE_LAYOUTS_COLLECTION));
  const batch = writeBatch(db);
  snap.docs.forEach((d) => {
    batch.update(d.ref, {
      isDefault: d.id === layoutId,
      updatedAt: serverTimestamp(),
    });
  });
  await batch.commit();
}

export async function deleteTableLayout(db: Firestore, layoutId: string): Promise<void> {
  const tablesSnap = await getDocs(tablesCollectionRef(db, layoutId));
  let batch = writeBatch(db);
  let n = 0;
  for (const d of tablesSnap.docs) {
    batch.delete(d.ref);
    n++;
    if (n >= 450) {
      await batch.commit();
      batch = writeBatch(db);
      n = 0;
    }
  }
  if (n > 0) await batch.commit();
  await deleteDoc(layoutDocRef(db, layoutId));
  const remaining = await getDocs(collection(db, TABLE_LAYOUTS_COLLECTION));
  if (!remaining.empty && !remaining.docs.some((d) => d.data().isDefault === true)) {
    await updateDoc(remaining.docs[0].ref, {
      isDefault: true,
      updatedAt: serverTimestamp(),
    });
  }
}

export async function upsertLayoutTable(
  db: Firestore,
  layoutId: string,
  tableId: string | null,
  data: TableLayoutTableDocument
): Promise<string> {
  const col = tablesCollectionRef(db, layoutId);
  const ref = tableId ? doc(col, tableId) : doc(col);
  const payload = {
    name: data.name.trim() || "Table",
    code: data.code.trim(),
    capacity: Math.max(0, Math.round(data.capacity)),
    shape: data.shape,
    x: data.x,
    y: data.y,
    width: Math.max(20, data.width),
    height: Math.max(20, data.height),
    rotation: ((data.rotation % 360) + 360) % 360,
    section: data.section.trim(),
    isActive: data.isActive,
    sortOrder: data.sortOrder,
    areaType: data.areaType,
    updatedAt: serverTimestamp(),
    ...(tableId ? {} : { createdAt: serverTimestamp() }),
  };
  await setDoc(ref, payload, { merge: true });
  return ref.id;
}

export async function deleteLayoutTable(db: Firestore, layoutId: string, tableId: string): Promise<void> {
  await deleteDoc(doc(tablesCollectionRef(db, layoutId), tableId));
}

/** Batch-save table positions/sizes after drag (chunks of 400). */
export async function batchUpdateLayoutTables(
  db: Firestore,
  layoutId: string,
  updates: Array<{ id: string; data: Partial<TableLayoutTableDocument> }>
): Promise<void> {
  for (let i = 0; i < updates.length; i += 400) {
    const slice = updates.slice(i, i + 400);
    const batch = writeBatch(db);
    for (const u of slice) {
      const ref = doc(tablesCollectionRef(db, layoutId), u.id);
      const p: Record<string, unknown> = { updatedAt: serverTimestamp() };
      if (u.data.x !== undefined) p.x = u.data.x;
      if (u.data.y !== undefined) p.y = u.data.y;
      if (u.data.width !== undefined) p.width = u.data.width;
      if (u.data.height !== undefined) p.height = u.data.height;
      if (u.data.rotation !== undefined) p.rotation = u.data.rotation;
      batch.update(ref, p);
    }
    await batch.commit();
  }
  await updateDoc(layoutDocRef(db, layoutId), { updatedAt: serverTimestamp() });
}

/** Copy legacy `Tables` docs into a new layout (one-time migration). */
export async function importLegacyTablesLayout(
  db: Firestore,
  layoutName: string
): Promise<string> {
  const legacy = await getDocs(
    query(collection(db, "Tables"), where("active", "==", true), limit(500))
  );
  const layoutId = await createTableLayout(db, { name: layoutName, isDefault: true });
  let order = 0;
  for (const d of legacy.docs) {
    const raw = d.data();
    if (String(raw.areaType ?? "") === "BAR_SEAT") continue;
    const data: TableLayoutTableDocument = {
      name: String(raw.name ?? "Table"),
      code: String(raw.code ?? ""),
      capacity: Math.round(Number(raw.seats ?? raw.capacity ?? 4)),
      shape: normalizeShape(String(raw.shape ?? "SQUARE")),
      x: Number(raw.x ?? raw.posX ?? 50 + order * 30),
      y: Number(raw.y ?? raw.posY ?? 50 + order * 20),
      width: typeof raw.width === "number" ? raw.width : DEFAULT_TABLE_SIZE.width,
      height: typeof raw.height === "number" ? raw.height : DEFAULT_TABLE_SIZE.height,
      rotation: Number(raw.rotation ?? 0),
      section: String(raw.section ?? ""),
      isActive: true,
      sortOrder: order++,
      areaType: "DINING_TABLE",
    };
    await upsertLayoutTable(db, layoutId, null, data);
  }
  return layoutId;
}

/** Doc id = display name, matching Android `Sections.document(name).set({ name })`. */
export async function upsertFirestoreSection(db: Firestore, displayName: string): Promise<void> {
  const name = displayName.trim();
  if (!name) throw new Error("Section name is required");
  if (name.toLowerCase() === "bar") throw new Error(`"${name}" is reserved`);
  await setDoc(
    doc(db, SECTIONS_COLLECTION, name),
    { name, updatedAt: serverTimestamp() },
    { merge: true }
  );
}

export async function deleteFirestoreSectionDoc(db: Firestore, sectionDocId: string): Promise<void> {
  await deleteDoc(doc(db, SECTIONS_COLLECTION, sectionDocId));
}

export function emptyTable(
  canvasW: number,
  canvasH: number,
  sortOrder: number
): TableLayoutTableDocument {
  return {
    name: `Table ${sortOrder + 1}`,
    code: "",
    capacity: 4,
    shape: "SQUARE",
    x: Math.round(canvasW / 2 - DEFAULT_TABLE_SIZE.width / 2),
    y: Math.round(canvasH / 2 - DEFAULT_TABLE_SIZE.height / 2),
    width: DEFAULT_TABLE_SIZE.width,
    height: DEFAULT_TABLE_SIZE.height,
    rotation: 0,
    section: "",
    isActive: true,
    sortOrder,
    areaType: "DINING_TABLE",
  };
}
