"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  arrayRemove,
  arrayUnion,
  collection,
  deleteField,
  doc,
  getDocs,
  query,
  where,
  orderBy,
  writeBatch,
  updateDoc,
  addDoc,
  deleteDoc,
  onSnapshot,
  serverTimestamp,
  setDoc,
  type DocumentReference,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import { collectActivePrinterRoutingLabels } from "@/lib/printerStatusUtils";
import {
  KDS_DEVICES_COLLECTION,
  parseKdsDevicePickerRow,
  shouldHideLegacyKdsAutoDevice,
  type KdsDevicePickerRow,
} from "@/lib/kdsDeviceFirestore";
import { kdsDeviceRoutesMenuItemLine } from "@/lib/kdsMenuAssignment";
import Header from "@/components/Header";
import MenuUploadModal from "@/components/MenuUploadModal";
import { ItemImageSection } from "@/components/menu-item-image/ItemImageSection";
import {
  Search,
  Plus,
  Upload,
  Download,
  Trash2,
  Pencil,
  AlertTriangle,
  Package,
  X,
  SlidersHorizontal,
  FolderPlus,
  Receipt,
  CheckSquare,
  LayoutGrid,
  List,
  Clock,
  Layers,
  ArrowRightLeft,
  ChevronDown,
  Monitor,
  Printer,
} from "lucide-react";
import type * as XLSXType from "xlsx";

const ALL_ORDER_TYPES = ["DINE_IN", "TO_GO", "BAR_TAB"] as const;
const ORDER_TYPE_LABELS: Record<string, string> = {
  DINE_IN: "DINE IN",
  TO_GO: "TO GO",
  BAR_TAB: "BAR",
};

/** Add-item form: POS placement key (not a Firestore `menus` doc id). */
const ADD_ITEM_POS_TARGET = "POS";

interface Schedule {
  id: string;
  name: string;
}

interface Category {
  id: string;
  name: string;
  availableOrderTypes: string[];
  scheduleIds: string[];
}

interface MenuEntity {
  id: string;
  name: string;
  isActive: boolean;
  scheduleIds: string[];
}

function categoriesForMenuTarget(
  targetKey: string,
  cats: Category[],
  menuEntities: MenuEntity[],
): Category[] {
  if (targetKey === ADD_ITEM_POS_TARGET) {
    return cats.filter((c) => c.scheduleIds.length === 0);
  }
  const m = menuEntities.find((e) => e.id === targetKey && e.isActive);
  if (!m) return [];
  return cats.filter(
    (c) => c.scheduleIds.length > 0 && m.scheduleIds.some((sid) => c.scheduleIds.includes(sid)),
  );
}

interface SubcategoryEntry {
  id: string;
  name: string;
  categoryId: string;
  order: number;
}

interface ExternalMappings {
  kitchenhub?: string;
  ubereats?: string;
  doordash?: string;
}

interface Pricing {
  pos: number;
  online: number;
}

interface ChannelAvailability {
  pos: boolean;
  online: boolean;
}

interface MenuItem {
  id: string;
  name: string;
  price: number;
  prices: Record<string, number>;
  stock: number;
  categoryId: string;
  /** When set, item appears under each listed category on POS (see subcategoryByCategoryId). */
  categoryIds?: string[];
  categoryName: string;
  availableOrderTypes: string[] | null;
  effectiveOrderTypes: string[];
  modifierGroupIds: string[];
  taxIds: string[];
  menuId: string;
  menuIds: string[];
  pricing: Pricing;
  channels: ChannelAvailability;
  isScheduled: boolean;
  scheduleIds: string[];
  categoryScheduled: boolean;
  categoryScheduleIds: string[];
  subcategoryId: string;
  subcategoryByCategoryId?: Record<string, string>;
  externalMappings?: ExternalMappings;
  /** Kitchen routing label; must match a label on a kitchen printer (POS). */
  printerLabel?: string;
  /** Firebase Storage download URL (menu / online ordering). */
  imageUrl?: string;
}

/** Firestore MenuItems row held in memory before rebuild() adds category-derived fields. */
type MenuItemSnapRow = Omit<
  MenuItem,
  "categoryName" | "effectiveOrderTypes" | "categoryScheduled" | "categoryScheduleIds"
>;

function placementCategoryIds(item: Pick<MenuItem, "categoryId" | "categoryIds">): string[] {
  if (item.categoryIds && item.categoryIds.length > 0) return item.categoryIds;
  if (item.categoryId) return [item.categoryId];
  return [];
}

function sortKdsDevicesByName(devices: KdsDevicePickerRow[]): KdsDevicePickerRow[] {
  return [...devices].sort((a, b) =>
    a.name.localeCompare(b.name, undefined, { sensitivity: "base" })
  );
}

/** KDS devices that would show this item (explicit ids, assigned categories, or no filter). */
function kdsDevicesThatRouteMenuItem(
  item: Pick<MenuItem, "id" | "categoryId" | "categoryIds">,
  devices: KdsDevicePickerRow[]
): KdsDevicePickerRow[] {
  const placements = placementCategoryIds(item);
  return sortKdsDevicesByName(
    devices.filter((d) =>
      kdsDeviceRoutesMenuItemLine(
        d.assignedCategoryIds,
        d.assignedItemIds,
        item.id,
        placements
      )
    )
  );
}

/** Row in the menu list with category resolved for the active menu filter. */
type MenuItemViewRow = MenuItem & {
  viewCategoryId: string;
  viewCategoryName: string;
  viewSubcategoryId: string;
  viewCategoryScheduled: boolean;
  viewCategoryScheduleIds: string[];
};

/** One titled block of items in the main list (category group or subcategory under a parent). */
type MenuItemSection = {
  key: string;
  title: string;
  items: MenuItemViewRow[];
};

function viewCategoryIdForMenuFilter(
  item: Pick<MenuItem, "categoryId" | "categoryIds">,
  menuTypeFilter: string | null,
  categories: Category[],
  menuEntities: MenuEntity[],
): string {
  const ids = placementCategoryIds(item);
  if (ids.length === 0) return item.categoryId;
  if (!menuTypeFilter || menuTypeFilter === "POS") {
    if (menuTypeFilter === "POS" && ids.length > 1) {
      for (const cid of ids) {
        const c = categories.find((x) => x.id === cid);
        if (c && c.scheduleIds.length === 0) return cid;
      }
    }
    return item.categoryId;
  }
  const menuEntity = menuEntities.find((m) => m.id === menuTypeFilter);
  if (!menuEntity) return item.categoryId;
  const menuSched = new Set(menuEntity.scheduleIds);
  for (const cid of ids) {
    const c = categories.find((x) => x.id === cid);
    if (c && c.scheduleIds.length > 0 && c.scheduleIds.some((sid) => menuSched.has(sid))) {
      return cid;
    }
  }
  return item.categoryId;
}

function viewSubcategoryIdForCategory(
  item: Pick<MenuItem, "categoryId" | "subcategoryId" | "subcategoryByCategoryId">,
  viewCategoryId: string,
): string {
  const m = item.subcategoryByCategoryId;
  if (m && typeof m[viewCategoryId] === "string") return m[viewCategoryId];
  if (item.categoryId === viewCategoryId) return item.subcategoryId ?? "";
  return "";
}

function itemPassesMenuFilter(
  item: MenuItem,
  menuTypeFilter: string | null,
  categories: Category[],
  menuEntities: MenuEntity[],
): boolean {
  if (!menuTypeFilter) return true;

  if (menuTypeFilter === "POS") {
    const ids = placementCategoryIds(item);
    if (ids.length > 0) {
      return ids.some((cid) => {
        const c = categories.find((x) => x.id === cid);
        return c && c.scheduleIds.length === 0;
      });
    }
    return !(item.categoryScheduled || item.isScheduled);
  }

  const allItemMenuIds = item.menuIds.length > 0 ? item.menuIds : (item.menuId ? [item.menuId] : []);
  if (allItemMenuIds.includes(menuTypeFilter)) return true;

  const menuEntity = menuEntities.find((m) => m.id === menuTypeFilter);
  if (!menuEntity) return false;

  if (allItemMenuIds.length === 0 && item.categoryScheduleIds.length > 0) {
    const menuSchedIds = new Set(menuEntity.scheduleIds);
    if (item.categoryScheduleIds.some((sid) => menuSchedIds.has(sid))) return true;
  }

  const ids = placementCategoryIds(item);
  return ids.some((cid) => {
    const c = categories.find((x) => x.id === cid);
    return c && c.scheduleIds.length > 0 && c.scheduleIds.some((sid) => menuEntity.scheduleIds.includes(sid));
  });
}

function withViewPlacement(
  item: MenuItem,
  menuTypeFilter: string | null,
  categories: Category[],
  menuEntities: MenuEntity[],
): MenuItemViewRow {
  const viewCategoryId = viewCategoryIdForMenuFilter(item, menuTypeFilter, categories, menuEntities);
  const viewCat = categories.find((c) => c.id === viewCategoryId);
  const viewSubcategoryId = viewSubcategoryIdForCategory(item, viewCategoryId);
  return {
    ...item,
    viewCategoryId,
    viewCategoryName: viewCat?.name ?? "Uncategorized",
    viewSubcategoryId,
    viewCategoryScheduled: (viewCat?.scheduleIds.length ?? 0) > 0,
    viewCategoryScheduleIds: viewCat?.scheduleIds ?? [],
  };
}

interface ModifierGroup {
  id: string;
  name: string;
  required: boolean;
  minSelection: number;
  maxSelection: number;
  groupType: string;
  options: { id: string; name: string; price: number; triggersModifierGroupIds: string[] }[];
}

interface TaxEntry {
  id: string;
  name: string;
  amount: number;
  type: string;
  enabled: boolean;
}

const FIRESTORE_BATCH_LIMIT = 450;

/**
 * Deletes one category document, its subcategories, and all menu items that use it as the
 * primary category. Items in `categoryIds` are updated to drop this category or deleted if
 * it was their only placement. Commits in chunks under Firestore's batch limit.
 */
async function purgeCategoryFromFirestore(categoryId: string): Promise<void> {
  const subSnap = await getDocs(
    query(collection(db, "subcategories"), where("categoryId", "==", categoryId))
  );
  const deletedSubIds = new Set<string>();
  subSnap.forEach((d) => deletedSubIds.add(d.id));

  const itemPrimarySnap = await getDocs(
    query(collection(db, "MenuItems"), where("categoryId", "==", categoryId))
  );
  const itemMultiSnap = await getDocs(
    query(collection(db, "MenuItems"), where("categoryIds", "array-contains", categoryId))
  );

  const primaryDeleteIds = new Set(itemPrimarySnap.docs.map((d) => d.id));

  type Op =
    | { k: "del"; ref: DocumentReference }
    | { k: "upd"; ref: DocumentReference; data: Record<string, unknown> };

  const ops: Op[] = [];

  itemPrimarySnap.forEach((d) => ops.push({ k: "del", ref: d.ref }));

  itemMultiSnap.forEach((d) => {
    if (primaryDeleteIds.has(d.id)) return;
    const data = d.data();
    const rawIds = Array.isArray(data.categoryIds)
      ? (data.categoryIds as unknown[]).filter((x): x is string => typeof x === "string" && x.length > 0)
      : [];
    const remaining = rawIds.filter((id) => id !== categoryId);
    if (remaining.length === 0) {
      ops.push({ k: "del", ref: d.ref });
    } else {
      const newPrimary = remaining[0];
      const rawMap = data.subcategoryByCategoryId;
      const subMap: Record<string, string> = {};
      if (rawMap && typeof rawMap === "object" && rawMap !== null) {
        for (const [k, v] of Object.entries(rawMap as Record<string, unknown>)) {
          if (k !== categoryId && typeof v === "string") subMap[k] = v;
        }
      }
      let newSubId = typeof data.subcategoryId === "string" ? data.subcategoryId : "";
      if (newSubId && deletedSubIds.has(newSubId)) newSubId = "";
      const updateData: Record<string, unknown> = {
        categoryId: newPrimary,
        categoryIds: remaining,
        subcategoryId: newSubId,
      };
      if (Object.keys(subMap).length > 0) {
        updateData.subcategoryByCategoryId = subMap;
      } else {
        updateData.subcategoryByCategoryId = deleteField();
      }
      ops.push({ k: "upd", ref: d.ref, data: updateData });
    }
  });

  subSnap.forEach((d) => ops.push({ k: "del", ref: d.ref }));
  ops.push({ k: "del", ref: doc(db, "Categories", categoryId) });

  let batch = writeBatch(db);
  let count = 0;
  for (const op of ops) {
    if (op.k === "del") batch.delete(op.ref);
    else batch.update(op.ref, op.data);
    count++;
    if (count >= FIRESTORE_BATCH_LIMIT) {
      await batch.commit();
      batch = writeBatch(db);
      count = 0;
    }
  }
  if (count > 0) await batch.commit();
}

export default function MenuPage() {
  const { user } = useAuth();
  const [categories, setCategories] = useState<Category[]>([]);
  const [items, setItems] = useState<MenuItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [activeCategory, setActiveCategory] = useState<string | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<MenuItem | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [editTarget, setEditTarget] = useState<MenuItem | null>(null);
  const [editPrices, setEditPrices] = useState<Record<string, string>>({});
  const [editStock, setEditStock] = useState("");
  const [editUseCategoryTypes, setEditUseCategoryTypes] = useState(true);
  const [editOrderTypes, setEditOrderTypes] = useState<Record<string, boolean>>({});
  const [saving, setSaving] = useState(false);

  const [addOpen, setAddOpen] = useState(false);
  const [addName, setAddName] = useState("");
  const [addPrices, setAddPrices] = useState<Record<string, string>>({});
  const [addStock, setAddStock] = useState("");
  /** Per menu target (POS or `menus` doc id): selected category and subcategory. */
  const [addPlacementCategory, setAddPlacementCategory] = useState<Record<string, string>>({});
  const [addPlacementSubcategory, setAddPlacementSubcategory] = useState<Record<string, string>>({});
  const [addUseCategoryTypes, setAddUseCategoryTypes] = useState(true);
  const [addOrderTypes, setAddOrderTypes] = useState<Record<string, boolean>>(
    () => Object.fromEntries(ALL_ORDER_TYPES.map((t) => [t, true]))
  );
  const [addSaving, setAddSaving] = useState(false);
  const [addModifiers, setAddModifiers] = useState<Record<string, boolean>>({});
  const [addTaxes, setAddTaxes] = useState<Record<string, boolean>>({});
  const [addModifiersExpanded, setAddModifiersExpanded] = useState(false);
  const [addTaxesExpanded, setAddTaxesExpanded] = useState(false);
  const [addLabelExpanded, setAddLabelExpanded] = useState(false);
  const [addKdsExpanded, setAddKdsExpanded] = useState(false);
  const [addKdsDeviceId, setAddKdsDeviceId] = useState("");
  const [editKdsExpanded, setEditKdsExpanded] = useState(false);
  const [editKdsDeviceId, setEditKdsDeviceId] = useState("");
  const [kdsPickerDevices, setKdsPickerDevices] = useState<KdsDevicePickerRow[]>([]);
  const kdsPickerRowsRef = useRef<KdsDevicePickerRow[]>([]);
  const editKdsTouchedRef = useRef(false);
  const [stockCountingEnabled, setStockCountingEnabled] = useState(true);
  /** From Firestore `Printers`: labels on active printers only (excludes isActive === false). */
  const [activePrinterRoutingLabels, setActivePrinterRoutingLabels] = useState<string[]>([]);
  const [addPrinterLabel, setAddPrinterLabel] = useState("");
  const [editPrinterLabel, setEditPrinterLabel] = useState("");

  const [addMenuId, setAddMenuId] = useState("");
  const [addMenuIds, setAddMenuIds] = useState<Record<string, boolean>>({});
  const [addPosPrice, setAddPosPrice] = useState("");
  const [addPosSelected, setAddPosSelected] = useState(false);
  const [addOnlinePrice, setAddOnlinePrice] = useState("");
  const [addMenuPrices, setAddMenuPrices] = useState<Record<string, string>>({});
  const [menuEntities, setMenuEntities] = useState<MenuEntity[]>([]);

  const [addCategoryOpen, setAddCategoryOpen] = useState(false);
  const [addCategoryName, setAddCategoryName] = useState("");
  const [addCategoryOrderTypes, setAddCategoryOrderTypes] = useState<Record<string, boolean>>(
    () => Object.fromEntries(ALL_ORDER_TYPES.map((t) => [t, true]))
  );
  const [addCategorySaving, setAddCategorySaving] = useState(false);
  const [addCategoryError, setAddCategoryError] = useState("");

  const [editCategoryTarget, setEditCategoryTarget] = useState<Category | null>(null);
  const [editCategoryOrderTypes, setEditCategoryOrderTypes] = useState<Record<string, boolean>>({});
  const [editCategorySchedules, setEditCategorySchedules] = useState<Record<string, boolean>>({});
  const [editCategorySaving, setEditCategorySaving] = useState(false);

  const [addCategorySchedules, setAddCategorySchedules] = useState<Record<string, boolean>>({});
  const [allSchedules, setAllSchedules] = useState<Schedule[]>([]);

  const [deleteCategoryTarget, setDeleteCategoryTarget] = useState<Category | null>(null);
  const [deletingCategory, setDeletingCategory] = useState(false);

  const [modifierGroups, setModifierGroups] = useState<ModifierGroup[]>([]);
  const [taxes, setTaxes] = useState<TaxEntry[]>([]);
  const [editModifiers, setEditModifiers] = useState<Record<string, boolean>>({});
  const [editTaxes, setEditTaxes] = useState<Record<string, boolean>>({});
  const [editLabelExpanded, setEditLabelExpanded] = useState(false);
  const [editModifiersExpanded, setEditModifiersExpanded] = useState(false);
  const [editTaxesExpanded, setEditTaxesExpanded] = useState(false);
  const [editImageUrl, setEditImageUrl] = useState("");
  const [editMenuId, setEditMenuId] = useState("");
  const [editMenuIds, setEditMenuIds] = useState<Record<string, boolean>>({});
  const [editPosPrice, setEditPosPrice] = useState("");
  const [editOnlinePrice, setEditOnlinePrice] = useState("");
  const [editChannelOnline, setEditChannelOnline] = useState(false);
  const [editMenuPrices, setEditMenuPrices] = useState<Record<string, string>>({});

  const [allSubcategories, setAllSubcategories] = useState<SubcategoryEntry[]>([]);
  const [activeSubcategory, setActiveSubcategory] = useState<string | null>(null);

  const [subModalOpen, setSubModalOpen] = useState(false);
  const [subEditing, setSubEditing] = useState<SubcategoryEntry | null>(null);
  const [subName, setSubName] = useState("");
  const [subCategoryId, setSubCategoryId] = useState("");
  const [subItemSelections, setSubItemSelections] = useState<Record<string, boolean>>({});
  const [subSaving, setSubSaving] = useState(false);

  const [deleteSubTarget, setDeleteSubTarget] = useState<SubcategoryEntry | null>(null);
  const [deletingSub, setDeletingSub] = useState(false);

  const [editSubcategoryId, setEditSubcategoryId] = useState("");

  const [selectMode, setSelectMode] = useState(false);
  const [selectedItems, setSelectedItems] = useState<Set<string>>(new Set());
  /** Multi-category filter: check categories/subs in the sidebar to show their items together. */
  const [categoryFilterMode, setCategoryFilterMode] = useState(false);
  const [filterCategoryIds, setFilterCategoryIds] = useState<string[]>([]);
  const [filterSubcategoryIds, setFilterSubcategoryIds] = useState<string[]>([]);
  const [bulkDeleting, setBulkDeleting] = useState(false);
  const [bulkDeleteConfirm, setBulkDeleteConfirm] = useState(false);
  const [bulkCategoriesDeleteConfirm, setBulkCategoriesDeleteConfirm] = useState(false);
  const [bulkCategoriesDeleting, setBulkCategoriesDeleting] = useState(false);

  const [transferMode, setTransferMode] = useState(false);
  const [transferItems, setTransferItems] = useState<Set<string>>(new Set());
  const [transferCategories, setTransferCategories] = useState<Set<string>>(new Set());
  const [transferSubcategories, setTransferSubcategories] = useState<Set<string>>(new Set());
  const [transferModalOpen, setTransferModalOpen] = useState(false);
  const [transferCategoryId, setTransferCategoryId] = useState("");
  const [transferSubcategoryId, setTransferSubcategoryId] = useState("");
  /** "POS" or a `menus` document id — used when promoting subcategories to top-level categories. */
  const [transferScheduleMenuId, setTransferScheduleMenuId] = useState<string>("POS");
  const [transferring, setTransferring] = useState(false);
  const [transferError, setTransferError] = useState("");
  const [viewMode, setViewMode] = useState<"compact" | "card">("compact");
  const [menuTypeFilter, setMenuTypeFilter] = useState<string | null>(null);
  const [selectedItem, setSelectedItem] = useState<MenuItem | null>(null);
  const [subscriptionKey, setSubscriptionKey] = useState(0);

  const catSnap = useRef<Map<string, { name: string; availableOrderTypes: string[]; scheduleIds: string[] }>>(new Map());
  const itemSnap = useRef<MenuItemSnapRow[]>([]);
  const bothReady = useRef({ cats: false, items: false });

  useEffect(() => {
    if (!user) return;
    console.log("[Menu] useEffect fired — subscribing to Firestore. subscriptionKey:", subscriptionKey);
    setLoading(true);
    bothReady.current = { cats: false, items: false };

    function rebuild() {
      if (!bothReady.current.cats || !bothReady.current.items) return;

      const catList: Category[] = [];
      catSnap.current.forEach((val, id) =>
        catList.push({ id, name: val.name, availableOrderTypes: val.availableOrderTypes, scheduleIds: val.scheduleIds })
      );
      catList.sort((a, b) => a.name.localeCompare(b.name));
      setCategories(catList);

      const menuItems: MenuItem[] = itemSnap.current.map((raw) => {
        const cat = catSnap.current.get(raw.categoryId);
        const catTypes = cat?.availableOrderTypes ?? [];
        const effective = raw.availableOrderTypes ?? catTypes;
        return {
          ...raw,
          categoryName: cat?.name ?? "Uncategorized",
          effectiveOrderTypes: effective,
          categoryScheduled: (cat?.scheduleIds.length ?? 0) > 0,
          categoryScheduleIds: cat?.scheduleIds ?? [],
          categoryIds: raw.categoryIds,
          subcategoryByCategoryId: raw.subcategoryByCategoryId,
        };
      });
      menuItems.sort((a, b) => a.name.localeCompare(b.name));
      setItems(menuItems);
      setLoading(false);
    }

    console.log("[Menu] Subscribing to Firestore collections. User UID:", user.uid);

    const unsubCats = onSnapshot(
      collection(db, "Categories"),
      (snap) => {
        console.log("[Menu] Categories snapshot → docs:", snap.size);
        catSnap.current.clear();
        snap.forEach((d) => {
          const data = d.data();
          if (data.name) {
            catSnap.current.set(d.id, {
              name: data.name,
              availableOrderTypes: Array.isArray(data.availableOrderTypes) ? data.availableOrderTypes : [],
              scheduleIds: Array.isArray(data.scheduleIds) ? data.scheduleIds : [],
            });
          }
        });
        bothReady.current.cats = true;
        rebuild();
      },
      (err) => {
        console.error("[Menu] Categories onSnapshot error:", err.code, err.message);
        setLoading(false);
      }
    );

    const unsubSchedules = onSnapshot(
      collection(db, "menuSchedules"),
      (snap) => {
        console.log("[Menu] menuSchedules snapshot → docs:", snap.size);
        const list: Schedule[] = [];
        snap.forEach((d) => {
          const data = d.data();
          if (data.name) list.push({ id: d.id, name: data.name });
        });
        list.sort((a, b) => a.name.localeCompare(b.name));
        setAllSchedules(list);
      },
      (err) => console.error("[Menu] menuSchedules onSnapshot error:", err.code, err.message)
    );

    const unsubItems = onSnapshot(
      collection(db, "MenuItems"),
      (snap) => {
        console.log("[Menu] MenuItems snapshot → docs:", snap.size);
        const list: MenuItemSnapRow[] = [];
        snap.forEach((d) => {
          const data = d.data();
          const rawPrices = (typeof data.prices === "object" && data.prices !== null)
            ? Object.fromEntries(
                Object.entries(data.prices as Record<string, unknown>).map(
                  ([k, v]) => [k, typeof v === "number" ? v : 0]
                )
              )
            : {};
          const legacyPrice: number = data.price ?? 0;
          const prices = Object.keys(rawPrices).length > 0 ? rawPrices : { default: legacyPrice };
          const displayPrice = Object.values(prices)[0] ?? 0;
          const rawPricing = (typeof data.pricing === "object" && data.pricing !== null)
            ? data.pricing as Record<string, unknown>
            : null;
          const pricingPos = typeof rawPricing?.pos === "number" ? rawPricing.pos : null;
          const pricingOnline = typeof rawPricing?.online === "number" ? rawPricing.online : null;
          const pricingObj: Pricing = {
            pos: (pricingPos as number | null) ?? displayPrice,
            online: (pricingOnline as number | null) ?? displayPrice,
          };

          const rawChannels = (typeof data.channels === "object" && data.channels !== null)
            ? data.channels as Record<string, unknown>
            : null;
          const channelsObj: ChannelAvailability = {
            pos: rawChannels?.pos !== false,
            online: rawChannels?.online === true,
          };

          const rawMenuIds = Array.isArray(data.menuIds) ? data.menuIds as string[] : [];
          const menuIdsResolved = rawMenuIds.length > 0
            ? rawMenuIds
            : (data.menuId ? [data.menuId as string] : []);

          const rawCategoryIds = Array.isArray(data.categoryIds)
            ? (data.categoryIds as unknown[]).filter((x): x is string => typeof x === "string" && x.length > 0)
            : undefined;

          let subcategoryByCategoryId: Record<string, string> | undefined;
          const rawSubMap = data.subcategoryByCategoryId;
          if (rawSubMap && typeof rawSubMap === "object" && rawSubMap !== null) {
            const o: Record<string, string> = {};
            for (const [k, v] of Object.entries(rawSubMap as Record<string, unknown>)) {
              if (typeof v === "string") o[k] = v;
            }
            if (Object.keys(o).length > 0) subcategoryByCategoryId = o;
          }

          list.push({
            id: d.id,
            name: data.name || "Unnamed",
            price: displayPrice,
            prices,
            stock: data.stock ?? 0,
            categoryId: data.categoryId ?? "",
            categoryIds: rawCategoryIds && rawCategoryIds.length > 0 ? rawCategoryIds : undefined,
            availableOrderTypes: Array.isArray(data.availableOrderTypes) ? data.availableOrderTypes : null,
            modifierGroupIds: Array.isArray(data.modifierGroupIds) ? data.modifierGroupIds : [],
            taxIds: Array.isArray(data.taxIds) ? data.taxIds : [],
            menuId: data.menuId ?? "",
            menuIds: menuIdsResolved,
            pricing: pricingObj,
            channels: channelsObj,
            isScheduled: data.isScheduled ?? false,
            scheduleIds: Array.isArray(data.scheduleIds) ? data.scheduleIds : [],
            subcategoryId: data.subcategoryId ?? "",
            subcategoryByCategoryId,
            externalMappings: data.externalMappings ?? {},
            printerLabel:
              typeof data.printerLabel === "string" && data.printerLabel.trim()
                ? data.printerLabel.trim()
                : undefined,
            imageUrl:
              typeof data.imageUrl === "string" && data.imageUrl.trim()
                ? data.imageUrl.trim()
                : undefined,
          });
        });
        itemSnap.current = list;
        bothReady.current.items = true;
        rebuild();
      },
      (err) => {
        console.error("[Menu] MenuItems onSnapshot error:", err.code, err.message);
        setLoading(false);
      }
    );

    const unsubSettings = onSnapshot(
      doc(db, "Settings", "inventory"),
      (snap) => {
        console.log("[Menu] Settings/inventory snapshot → exists:", snap.exists());
        const data = snap.data();
        setStockCountingEnabled(data?.stockCountingEnabled ?? true);
      },
      (err) => console.error("[Menu] Settings onSnapshot error:", err.code, err.message)
    );

    const unsubModGroups = onSnapshot(
      collection(db, "ModifierGroups"),
      (snap) => {
        console.log("[Menu] ModifierGroups snapshot → docs:", snap.size);
        const list: ModifierGroup[] = [];
        snap.forEach((d) => {
          const data = d.data();
          if (data.name) {
            list.push({
              id: d.id,
              name: data.name,
              required: data.required ?? false,
              minSelection: data.minSelection ?? (data.required ? 1 : 0),
              maxSelection: data.maxSelection ?? 1,
              groupType: data.groupType ?? "ADD",
              options: Array.isArray(data.options)
                ? data.options.map((o: Record<string, unknown>) => ({
                    id: String(o.id ?? ""),
                    name: String(o.name ?? ""),
                    price: typeof o.price === "number" ? o.price : 0,
                    triggersModifierGroupIds: Array.isArray(o.triggersModifierGroupIds) ? (o.triggersModifierGroupIds as string[]) : [],
                  }))
                : [],
            });
          }
        });
        list.sort((a, b) => a.name.localeCompare(b.name));
        setModifierGroups(list);
      },
      (err) => console.error("[Menu] ModifierGroups onSnapshot error:", err.code, err.message)
    );

    const unsubTaxes = onSnapshot(
      collection(db, "Taxes"),
      (snap) => {
        console.log("[Menu] Taxes snapshot → docs:", snap.size);
        const list: TaxEntry[] = [];
        snap.forEach((d) => {
          const data = d.data();
          if (data.name) {
            list.push({
              id: d.id,
              name: data.name,
              amount: data.amount ?? 0,
              type: data.type ?? "PERCENTAGE",
              enabled: data.enabled ?? true,
            });
          }
        });
        list.sort((a, b) => a.name.localeCompare(b.name));
        setTaxes(list);
      },
      (err) => console.error("[Menu] Taxes onSnapshot error:", err.code, err.message)
    );

    const unsubMenuEntities = onSnapshot(
      collection(db, "menus"),
      (snap) => {
        console.log("[Menu] menus snapshot → docs:", snap.size);
        const list: MenuEntity[] = [];
        snap.forEach((d) => {
          const data = d.data();
          if (data.name) {
            list.push({
              id: d.id,
              name: data.name,
              isActive: data.isActive ?? true,
              scheduleIds: Array.isArray(data.scheduleIds) ? data.scheduleIds : [],
            });
          }
        });
        list.sort((a, b) => a.name.localeCompare(b.name));
        setMenuEntities(list);
      },
      (err) => console.error("[Menu] menus onSnapshot error:", err.code, err.message)
    );

    const unsubSubcategories = onSnapshot(
      query(collection(db, "subcategories"), orderBy("order", "asc")),
      (snap) => {
        const list: SubcategoryEntry[] = [];
        snap.forEach((d) => {
          const data = d.data();
          list.push({
            id: d.id,
            name: data.name ?? "",
            categoryId: data.categoryId ?? "",
            order: data.order ?? 0,
          });
        });
        setAllSubcategories(list);
      },
      (err) => console.error("[Menu] subcategories onSnapshot error:", err.code, err.message)
    );

    const unsubPrintersForLabels = onSnapshot(
      collection(db, "Printers"),
      (snap) => {
        const rows: Record<string, unknown>[] = [];
        snap.forEach((d) => rows.push(d.data() as Record<string, unknown>));
        setActivePrinterRoutingLabels(collectActivePrinterRoutingLabels(rows));
      },
      (err) =>
        console.error("[Menu] Printers (routing labels) onSnapshot error:", err.code, err.message)
    );

    const unsubKdsPicker = onSnapshot(
      collection(db, KDS_DEVICES_COLLECTION),
      (snap) => {
        const rows: KdsDevicePickerRow[] = [];
        snap.forEach((d) => {
          const raw = d.data() as Record<string, unknown>;
          if (shouldHideLegacyKdsAutoDevice(d.id, raw)) return;
          rows.push(parseKdsDevicePickerRow(d.id, raw));
        });
        rows.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" }));
        setKdsPickerDevices(rows);
      },
      (err) =>
        console.error("[Menu] kds_devices (picker) onSnapshot error:", err.code, err.message)
    );

    return () => {
      console.log("[Menu] Cleaning up Firestore listeners");
      unsubCats();
      unsubSchedules();
      unsubItems();
      unsubSettings();
      unsubModGroups();
      unsubTaxes();
      unsubMenuEntities();
      unsubSubcategories();
      unsubPrintersForLabels();
      unsubKdsPicker();
      bothReady.current = { cats: false, items: false };
    };
  }, [user, subscriptionKey]);

  // Fallback: re-subscribe if tab becomes visible with no data loaded
  useEffect(() => {
    function handleVisibilityChange() {
      if (document.visibilityState === "visible" && items.length === 0 && !loading && user) {
        console.log("[Menu] Tab visible with empty data — triggering re-subscribe");
        setSubscriptionKey((k) => k + 1);
      }
    }
    document.addEventListener("visibilitychange", handleVisibilityChange);
    return () => document.removeEventListener("visibilitychange", handleVisibilityChange);
  }, [items.length, loading, user]);

  /** POS printers only; include current draft value so picks stay visible (legacy / orphan labels). */
  const addAssignableLabelOptions = useMemo(() => {
    const cur = addPrinterLabel.trim();
    const norms = new Set(activePrinterRoutingLabels.map((x) => x.toLowerCase()));
    const merged =
      cur && !norms.has(cur.toLowerCase())
        ? [...activePrinterRoutingLabels, cur]
        : activePrinterRoutingLabels;
    return [...merged].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }));
  }, [activePrinterRoutingLabels, addPrinterLabel]);

  const editAssignableLabelOptions = useMemo(() => {
    const cur = editPrinterLabel.trim();
    const norms = new Set(activePrinterRoutingLabels.map((x) => x.toLowerCase()));
    const merged =
      cur && !norms.has(cur.toLowerCase())
        ? [...activePrinterRoutingLabels, cur]
        : activePrinterRoutingLabels;
    return [...merged].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }));
  }, [activePrinterRoutingLabels, editPrinterLabel]);

  useEffect(() => {
    kdsPickerRowsRef.current = kdsPickerDevices;
  }, [kdsPickerDevices]);

  const activeKdsSelectOptions = useMemo(
    () =>
      kdsPickerDevices
        .filter((d) => d.isActive)
        .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" })),
    [kdsPickerDevices]
  );

  const editKdsSelectOptions = useMemo(() => {
    const base = [...activeKdsSelectOptions];
    const sel = editKdsDeviceId.trim();
    if (sel && !base.some((d) => d.id === sel)) {
      const row = kdsPickerDevices.find((d) => d.id === sel);
      if (row) base.push(row);
    }
    return base.sort((a, b) =>
      a.name.localeCompare(b.name, undefined, { sensitivity: "base" })
    );
  }, [activeKdsSelectOptions, editKdsDeviceId, kdsPickerDevices]);

  /** If KDS list loads after the edit modal opens, pre-select a device that routes this item. */
  useEffect(() => {
    if (!editTarget || editKdsTouchedRef.current) return;
    const found = kdsDevicesThatRouteMenuItem(editTarget, kdsPickerDevices)[0];
    if (!found) return;
    setEditKdsDeviceId((cur) => (cur ? cur : found.id));
  }, [editTarget?.id, kdsPickerDevices]);

  // ── Filter + group (per–menu-filter category placement for multi-menu items) ──

  const itemsForMenuType = useMemo((): MenuItemViewRow[] => {
    return items
      .filter((item) => itemPassesMenuFilter(item, menuTypeFilter, categories, menuEntities))
      .map((item) => withViewPlacement(item, menuTypeFilter, categories, menuEntities));
  }, [items, menuTypeFilter, categories, menuEntities]);

  const categoriesVisibleForMenu = useMemo(() => {
    return categories.filter((cat) => {
      if (!menuTypeFilter) return true;
      if (itemsForMenuType.some((i) => i.viewCategoryId === cat.id)) return true;
      if (menuTypeFilter === "POS") return false;
      const menuEntity = menuEntities.find((m) => m.id === menuTypeFilter);
      if (!menuEntity || menuEntity.scheduleIds.length === 0 || cat.scheduleIds.length === 0) {
        return false;
      }
      const menuSched = new Set(menuEntity.scheduleIds);
      return cat.scheduleIds.some((sid) => menuSched.has(sid));
    });
  }, [categories, menuTypeFilter, menuEntities, itemsForMenuType]);

  const filtered = itemsForMenuType.filter((item) => {
    const q = search.trim().toLowerCase();
    if (q) {
      const name = item.name.toLowerCase();
      const words = q.split(/\s+/).filter(Boolean);
      return words.every((w) => name.includes(w));
    }
    if (categoryFilterMode) {
      const cats = filterCategoryIds.length > 0;
      const subs = filterSubcategoryIds.length > 0;
      if (!cats && !subs) return true;
      const matchCat = cats && filterCategoryIds.includes(item.viewCategoryId);
      const matchSub =
        subs && item.viewSubcategoryId && filterSubcategoryIds.includes(item.viewSubcategoryId);
      if (cats && subs) {
        if (!matchCat && !matchSub) return false;
      } else if (cats) {
        if (!matchCat) return false;
      } else if (!matchSub) {
        return false;
      }
      return true;
    }
    if (activeSubcategory && item.viewSubcategoryId !== activeSubcategory) return false;
    if (activeCategory && item.viewCategoryId !== activeCategory) return false;
    return true;
  });

  const subsForActiveCategory =
    activeCategory && !categoryFilterMode && !activeSubcategory
      ? allSubcategories
          .filter((s) => s.categoryId === activeCategory)
          .sort((a, b) => a.order - b.order)
      : [];

  let itemSections: MenuItemSection[];

  if (subsForActiveCategory.length > 1) {
    const bySubId = new Map<string, MenuItemViewRow[]>();
    for (const sub of subsForActiveCategory) {
      bySubId.set(sub.id, []);
    }
    const UNCATEGORIZED = "__uncategorized__";
    for (const item of filtered) {
      const sid = item.viewSubcategoryId;
      if (sid && bySubId.has(sid)) {
        bySubId.get(sid)!.push(item);
      } else {
        if (!bySubId.has(UNCATEGORIZED)) bySubId.set(UNCATEGORIZED, []);
        bySubId.get(UNCATEGORIZED)!.push(item);
      }
    }
    itemSections = [];
    for (const sub of subsForActiveCategory) {
      const arr = bySubId.get(sub.id) ?? [];
      if (arr.length > 0) {
        itemSections.push({ key: `sub-${sub.id}`, title: sub.name, items: arr });
      }
    }
    const uncat = bySubId.get(UNCATEGORIZED);
    if (uncat && uncat.length > 0) {
      itemSections.push({ key: "uncategorized", title: "Uncategorized", items: uncat });
    }
  } else {
    const grouped = new Map<string, MenuItemViewRow[]>();
    for (const item of filtered) {
      const key = item.viewCategoryName;
      if (!grouped.has(key)) grouped.set(key, []);
      grouped.get(key)!.push(item);
    }
    const sorted = Array.from(grouped.entries()).sort(([a], [b]) => {
      if (a === "Uncategorized") return 1;
      if (b === "Uncategorized") return -1;
      return a.localeCompare(b);
    });
    itemSections = sorted.map(([title, items]) => ({
      key: `cat-${title}`,
      title,
      items,
    }));
  }

  // Clear selected item if it's no longer in filtered list
  useEffect(() => {
    if (selectedItem && !filtered.some((i) => i.id === selectedItem.id)) {
      setSelectedItem(null);
    }
  }, [filtered, selectedItem?.id]);

  useEffect(() => {
    if (!addOpen) return;
    const targets = new Set<string>();
    if (addPosSelected) targets.add(ADD_ITEM_POS_TARGET);
    for (const m of menuEntities) {
      if (m.isActive && addMenuIds[m.id]) targets.add(m.id);
    }
    setAddPlacementCategory((prev) => {
      const next = { ...prev };
      for (const k of Object.keys(next)) {
        if (!targets.has(k)) delete next[k];
        else {
          const allowed = new Set(categoriesForMenuTarget(k, categories, menuEntities).map((c) => c.id));
          if (next[k] && !allowed.has(next[k])) delete next[k];
        }
      }
      return next;
    });
    setAddPlacementSubcategory((prev) => {
      const next = { ...prev };
      for (const k of Object.keys(next)) {
        if (!targets.has(k)) delete next[k];
      }
      return next;
    });
  }, [addOpen, addPosSelected, addMenuIds, categories, menuEntities]);

  // ── Delete ──

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteDoc(doc(db, "MenuItems", deleteTarget.id));
    } catch (err) {
      console.error("Failed to delete item:", err);
    } finally {
      setDeleting(false);
      setDeleteTarget(null);
    }
  };

  // ── Bulk Delete ──

  const toggleSelectItem = (id: string) => {
    setSelectedItems((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (selectedItems.size === filtered.length) {
      setSelectedItems(new Set());
    } else {
      setSelectedItems(new Set(filtered.map((i) => i.id)));
    }
  };

  const exitSelectMode = () => {
    setSelectMode(false);
    setSelectedItems(new Set());
  };

  const exitCategoryFilterMode = () => {
    setCategoryFilterMode(false);
    setFilterCategoryIds([]);
    setFilterSubcategoryIds([]);
  };

  const enterCategoryFilterMode = () => {
    setActiveCategory(null);
    setActiveSubcategory(null);
    setFilterCategoryIds([]);
    setFilterSubcategoryIds([]);
    setCategoryFilterMode(true);
  };

  const toggleFilterCategory = (categoryId: string) => {
    setFilterCategoryIds((prev) =>
      prev.includes(categoryId) ? prev.filter((id) => id !== categoryId) : [...prev, categoryId]
    );
  };

  const toggleFilterSubcategory = (subcategoryId: string) => {
    setFilterSubcategoryIds((prev) =>
      prev.includes(subcategoryId)
        ? prev.filter((id) => id !== subcategoryId)
        : [...prev, subcategoryId]
    );
  };

  const toggleFilterSelectAllCategories = () => {
    const ids = [...new Set(categoriesVisibleForMenu.map((c) => c.id))];
    if (ids.length === 0) return;
    setFilterSubcategoryIds([]);
    setFilterCategoryIds((prev) => {
      const allSelected = ids.every((id) => prev.includes(id));
      return allSelected ? [] : [...ids];
    });
  };

  const clearCategoryFilters = () => {
    setFilterCategoryIds([]);
    setFilterSubcategoryIds([]);
  };

  const resetCategoryNavForMenuTarget = () => {
    setActiveCategory(null);
    setActiveSubcategory(null);
    if (categoryFilterMode) {
      setFilterCategoryIds([]);
      setFilterSubcategoryIds([]);
    }
  };

  const toggleTransferItem = (id: string) => {
    setTransferItems((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleTransferCategory = (categoryId: string) => {
    setTransferCategories((prev) => {
      const next = new Set(prev);
      if (next.has(categoryId)) next.delete(categoryId);
      else next.add(categoryId);
      return next;
    });
  };

  const toggleTransferSubcategory = (subcategoryId: string) => {
    setTransferSubcategories((prev) => {
      const next = new Set(prev);
      if (next.has(subcategoryId)) next.delete(subcategoryId);
      else next.add(subcategoryId);
      return next;
    });
  };

  const toggleTransferAll = () => {
    if (transferItems.size === filtered.length) {
      setTransferItems(new Set());
    } else {
      setTransferItems(new Set(filtered.map((i) => i.id)));
    }
  };

  const exitTransferMode = () => {
    setTransferMode(false);
    setTransferItems(new Set());
    setTransferCategories(new Set());
    setTransferSubcategories(new Set());
  };

  const openTransferModal = () => {
    if (transferItems.size === 0 && transferCategories.size === 0 && transferSubcategories.size === 0) return;
    setTransferCategoryId("");
    setTransferSubcategoryId("");
    setTransferScheduleMenuId("POS");
    setTransferError("");
    setTransferModalOpen(true);
  };

  /** Item fields for POS vs a specific scheduled menu (`menus` doc id). Matches Android MenuActivity filtering. */
  const buildScheduleFieldsForMenuTarget = (menuTarget: string) => {
    if (!menuTarget || menuTarget === "POS") {
      return {
        isScheduled: false,
        scheduleIds: [] as string[],
        menuIds: [] as string[],
        menuId: "",
      };
    }
    const ent = menuEntities.find((m) => m.id === menuTarget);
    if (!ent) {
      return {
        isScheduled: false,
        scheduleIds: [] as string[],
        menuIds: [] as string[],
        menuId: "",
      };
    }
    const sids = Array.isArray(ent.scheduleIds) ? ent.scheduleIds : [];
    return {
      isScheduled: sids.length > 0,
      scheduleIds: sids,
      menuIds: [ent.id],
      menuId: ent.id,
    };
  };

  /** Align item menu/schedule fields with a target category (POS vs scheduled menus). */
  const buildScheduleFieldsForTargetCategory = (targetCat: Category) => {
    const addCatHasSchedule = (targetCat.scheduleIds?.length ?? 0) > 0;
    if (addCatHasSchedule) {
      const scheduledMenuIds = menuEntities
        .filter((m) => m.scheduleIds.some((sid) => targetCat.scheduleIds.includes(sid)))
        .map((m) => m.id);
      return {
        isScheduled: true,
        scheduleIds: targetCat.scheduleIds,
        menuIds: scheduledMenuIds,
        menuId: scheduledMenuIds.length > 0 ? scheduledMenuIds[0] : "",
      };
    }
    return {
      isScheduled: false,
      scheduleIds: [] as string[],
      menuIds: [] as string[],
      menuId: "",
    };
  };

  const handleTransfer = async () => {
    const needsTargetCategory = transferCategories.size > 0 || transferItems.size > 0;
    if (needsTargetCategory && !transferCategoryId) return;
    if (transferItems.size === 0 && transferCategories.size === 0 && transferSubcategories.size === 0) return;

    for (const subId of transferSubcategories) {
      const sub = allSubcategories.find((s) => s.id === subId);
      if (sub && transferCategories.has(sub.categoryId)) {
        setTransferError(
          "You cannot promote a subcategory that belongs to a category you are also merging into another category. Complete those transfers separately."
        );
        return;
      }
    }

    if (needsTargetCategory) {
      if (transferCategories.has(transferCategoryId)) {
        setTransferError("Target cannot be one of the categories you are transferring.");
        return;
      }
    }

    const targetCat = needsTargetCategory ? categories.find((c) => c.id === transferCategoryId) : undefined;
    if (needsTargetCategory && !targetCat) return;

    const scheduleFields = targetCat ? buildScheduleFieldsForTargetCategory(targetCat) : buildScheduleFieldsForMenuTarget("POS");
    const promoteScheduleFields = buildScheduleFieldsForMenuTarget(transferScheduleMenuId);

    setTransferError("");
    setTransferring(true);
    const CHUNK = 400;

    try {
      let orderOffset = transferCategoryId
        ? allSubcategories.filter((s) => s.categoryId === transferCategoryId).length
        : 0;

      for (const sourceCatId of transferCategories) {
        if (sourceCatId === transferCategoryId) continue;
        const sourceCat = categories.find((c) => c.id === sourceCatId);
        if (!sourceCat) continue;

        const itemsInSource = items.filter((i) => i.categoryId === sourceCatId);
        const oldSubs = allSubcategories.filter((s) => s.categoryId === sourceCatId);

        const newSubRef = await addDoc(collection(db, "subcategories"), {
          name: sourceCat.name,
          categoryId: transferCategoryId,
          order: orderOffset,
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
        orderOffset += 1;

        const itemPayload = {
          categoryId: transferCategoryId,
          subcategoryId: newSubRef.id,
          ...scheduleFields,
        };

        for (let i = 0; i < itemsInSource.length; i += CHUNK) {
          const batch = writeBatch(db);
          for (const it of itemsInSource.slice(i, i + CHUNK)) {
            batch.update(doc(db, "MenuItems", it.id), itemPayload);
          }
          await batch.commit();
        }

        for (let i = 0; i < oldSubs.length; i += CHUNK) {
          const batch = writeBatch(db);
          for (const sub of oldSubs.slice(i, i + CHUNK)) {
            batch.delete(doc(db, "subcategories", sub.id));
          }
          await batch.commit();
        }

        await deleteDoc(doc(db, "Categories", sourceCatId));
        if (activeCategory === sourceCatId) setActiveCategory(null);
        if (oldSubs.some((s) => s.id === activeSubcategory)) setActiveSubcategory(null);
      }

      const takenNames = new Set(categories.map((c) => c.name.trim().toLowerCase()));
      const pickUniqueCategoryName = (base: string) => {
        let name = base.trim() || "Category";
        let n = 1;
        while (takenNames.has(name.toLowerCase())) {
          n += 1;
          name = `${base.trim() || "Category"} (${n})`;
        }
        takenNames.add(name.toLowerCase());
        return name;
      };

      for (const subId of transferSubcategories) {
        const sub = allSubcategories.find((s) => s.id === subId);
        if (!sub) continue;
        const parent = categories.find((c) => c.id === sub.categoryId);
        const availTypes =
          parent && parent.availableOrderTypes.length > 0 ? parent.availableOrderTypes : [...ALL_ORDER_TYPES];

        const catName = pickUniqueCategoryName(sub.name);
        const newCatRef = await addDoc(collection(db, "Categories"), {
          name: catName,
          availableOrderTypes: availTypes,
          scheduleIds: promoteScheduleFields.scheduleIds,
        });

        const itemsInSub = items.filter((i) => i.categoryId === sub.categoryId && i.subcategoryId === subId);
        const itemPayload = {
          categoryId: newCatRef.id,
          subcategoryId: "",
          ...promoteScheduleFields,
        };

        for (let i = 0; i < itemsInSub.length; i += CHUNK) {
          const batch = writeBatch(db);
          for (const it of itemsInSub.slice(i, i + CHUNK)) {
            batch.update(doc(db, "MenuItems", it.id), itemPayload);
          }
          await batch.commit();
        }

        await deleteDoc(doc(db, "subcategories", subId));
        if (activeSubcategory === subId) setActiveSubcategory(null);
      }

      const transferItemIds = Array.from(transferItems);
      if (transferItemIds.length > 0 && targetCat) {
        const itemPayload = {
          categoryId: transferCategoryId,
          subcategoryId: transferSubcategoryId || "",
          ...scheduleFields,
        };
        for (let i = 0; i < transferItemIds.length; i += CHUNK) {
          const batch = writeBatch(db);
          for (const id of transferItemIds.slice(i, i + CHUNK)) {
            batch.update(doc(db, "MenuItems", id), itemPayload);
          }
          await batch.commit();
        }
      }

      setTransferModalOpen(false);
      exitTransferMode();
    } catch (err) {
      console.error("Failed to transfer:", err);
    } finally {
      setTransferring(false);
    }
  };

  const handleBulkDelete = async () => {
    if (selectedItems.size === 0) return;
    setBulkDeleting(true);
    try {
      const batch = writeBatch(db);
      for (const id of selectedItems) {
        batch.delete(doc(db, "MenuItems", id));
      }
      await batch.commit();
      exitSelectMode();
    } catch (err) {
      console.error("Failed to bulk delete items:", err);
    } finally {
      setBulkDeleting(false);
      setBulkDeleteConfirm(false);
    }
  };

  const handleBulkDeleteCategories = async () => {
    const ids = [...filterCategoryIds];
    if (ids.length === 0) return;
    setBulkCategoriesDeleting(true);
    try {
      for (const categoryId of ids) {
        await purgeCategoryFromFirestore(categoryId);
      }
      exitCategoryFilterMode();
    } catch (err) {
      console.error("Failed to bulk delete categories:", err);
    } finally {
      setBulkCategoriesDeleting(false);
      setBulkCategoriesDeleteConfirm(false);
    }
  };

  // ── Edit ──

  async function mergeKitchenLabelToSettings(label: string) {
    const t = label.trim();
    if (!t) return;
    try {
      await setDoc(
        doc(db, "Settings", "kitchenRoutingLabels"),
        { labels: arrayUnion(t) },
        { merge: true }
      );
    } catch (e) {
      console.error("[Menu] mergeKitchenLabelToSettings:", e);
    }
  }

  /** Updates `kds_devices.assignedItemIds` so at most one device explicitly lists [itemId] (picker choice). */
  async function syncMenuItemKdsDeviceAssignment(
    itemId: string,
    selectedDeviceId: string,
    devices: KdsDevicePickerRow[]
  ) {
    const id = itemId.trim();
    if (!id) return;
    const sel = selectedDeviceId.trim();
    for (const d of devices) {
      const had = d.assignedItemIds.includes(id);
      const want = sel !== "" && d.id === sel;
      if (had === want) continue;
      const ref = doc(db, KDS_DEVICES_COLLECTION, d.id);
      if (want) {
        await updateDoc(ref, {
          assignedItemIds: arrayUnion(id),
          updatedAt: serverTimestamp(),
        });
      } else {
        await updateDoc(ref, {
          assignedItemIds: arrayRemove(id),
          updatedAt: serverTimestamp(),
        });
      }
    }
  }

  const getMenuItemIdToken = useCallback(async () => {
    if (!user) throw new Error("Unauthorized");
    return user.getIdToken();
  }, [user]);

  const persistEditItemImageUrl = useCallback(
    async (url: string) => {
      if (!editTarget) return;
      await updateDoc(doc(db, "MenuItems", editTarget.id), { imageUrl: url });
    },
    [editTarget]
  );

  const openEdit = (item: MenuItem) => {
    setEditTarget(item);
    const priceStrings: Record<string, string> = {};
    for (const [key, val] of Object.entries(item.prices)) {
      priceStrings[key] = (typeof val === "number" ? val : 0).toFixed(2);
    }
    if (!item.menuId && !priceStrings["default"]) {
      priceStrings["default"] = item.price.toFixed(2);
    }
    if (menuEntities.length > 0) {
      for (const m of menuEntities) {
        if (!(m.id in priceStrings)) {
          priceStrings[m.id] = item.prices[m.id]?.toFixed(2) ?? item.price.toFixed(2) ?? "0.00";
        }
      }
    }
    setEditPrices(priceStrings);
    setEditStock(String(item.stock));
    setEditMenuId(item.menuId ?? "");

    setEditPosPrice(item.pricing.pos.toFixed(2));
    setEditOnlinePrice(item.pricing.online.toFixed(2));
    setEditChannelOnline(item.channels.online);
    setEditSubcategoryId(item.subcategoryId ?? "");

    const menuIdSel: Record<string, boolean> = {};
    const menuPrices: Record<string, string> = {};
    for (const m of menuEntities) {
      menuIdSel[m.id] = item.menuIds.includes(m.id);
      if (item.prices[m.id] != null) {
        menuPrices[m.id] = item.prices[m.id].toFixed(2);
      }
    }
    setEditMenuIds(menuIdSel);
    setEditMenuPrices(menuPrices);

    const useCat = item.availableOrderTypes === null;
    setEditUseCategoryTypes(useCat);

    const types: Record<string, boolean> = {};
    const active = item.availableOrderTypes ?? item.effectiveOrderTypes;
    for (const t of ALL_ORDER_TYPES) {
      types[t] = active.includes(t);
    }
    setEditOrderTypes(types);

    const mods: Record<string, boolean> = {};
    for (const gId of item.modifierGroupIds) {
      mods[gId] = true;
    }
    setEditModifiers(mods);

    const txs: Record<string, boolean> = {};
    for (const tId of item.taxIds) {
      txs[tId] = true;
    }
    setEditTaxes(txs);
    setEditPrinterLabel(item.printerLabel?.trim() ?? "");
    setEditImageUrl(typeof item.imageUrl === "string" ? item.imageUrl.trim() : "");
    setEditLabelExpanded(false);
    editKdsTouchedRef.current = false;
    const kdsRoutingMatches = kdsDevicesThatRouteMenuItem(item, kdsPickerRowsRef.current);
    setEditKdsDeviceId(kdsRoutingMatches[0]?.id ?? "");
    setEditKdsExpanded(false);
    setEditModifiersExpanded(false);
    setEditTaxesExpanded(false);
  };

  const handleSave = async () => {
    if (!editTarget) return;

    const prices: Record<string, number> = {};
    for (const [menuId, val] of Object.entries(editMenuPrices)) {
      const num = parseFloat(val);
      if (!isNaN(num) && num >= 0) prices[menuId] = num;
    }

    const parsedPos = parseFloat(editPosPrice);
    const firstMenuPrice = Object.values(prices)[0];
    const posPrice = (!isNaN(parsedPos) && parsedPos >= 0) ? parsedPos : (firstMenuPrice ?? -1);
    if (posPrice < 0) return;

    prices.default = posPrice;
    const price = posPrice;

    const update: Record<string, unknown> = { prices, price };

    update.pricing = {
      pos: posPrice,
      online: posPrice,
    };

    const scheduledMenuIds = editScheduledMenus.map((m) => m.id);
    const selectedMenuIds = editCategoryHasSchedule
      ? scheduledMenuIds
      : Object.entries(editMenuIds).filter(([, v]) => v).map(([k]) => k);
    update.menuIds = selectedMenuIds;
    if (selectedMenuIds.length > 0) {
      update.menuId = selectedMenuIds[0];
    }

    update.channels = {
      pos: true,
      online: editChannelOnline,
    };

    update.subcategoryId = editSubcategoryId;

    if (stockCountingEnabled) {
      const stock = parseInt(editStock, 10);
      if (isNaN(stock) || stock < 0) return;
      update.stock = stock;
    }

    setSaving(true);
    try {
      if (editUseCategoryTypes) {
        update.availableOrderTypes = (await import("firebase/firestore")).deleteField();
      } else {
        update.availableOrderTypes = ALL_ORDER_TYPES.filter((t) => editOrderTypes[t]);
      }

      update.modifierGroupIds = Object.entries(editModifiers)
        .filter(([, v]) => v)
        .map(([k]) => k);

      update.taxIds = Object.entries(editTaxes)
        .filter(([, v]) => v)
        .map(([k]) => k);

      if (editMenuId) update.menuId = editMenuId;

      const pl = editPrinterLabel.trim();
      if (pl) {
        update.printerLabel = pl;
        await mergeKitchenLabelToSettings(pl);
      } else {
        update.printerLabel = deleteField();
      }

      const img = editImageUrl.trim();
      if (img) {
        update.imageUrl = img;
      } else {
        update.imageUrl = deleteField();
      }

      await updateDoc(doc(db, "MenuItems", editTarget.id), update);
      try {
        await syncMenuItemKdsDeviceAssignment(
          editTarget.id,
          editKdsDeviceId,
          kdsPickerDevices
        );
      } catch (kdsErr) {
        console.error("[Menu] KDS assignment sync after edit:", kdsErr);
      }
    } catch (err) {
      console.error("Failed to update item:", err);
    } finally {
      setSaving(false);
      setEditTarget(null);
    }
  };

  // ── Add Item ──

  const resetAddForm = () => {
    setAddName("");
    setAddPrices({});
    setAddStock("");
    setAddPlacementCategory({});
    setAddPlacementSubcategory({});
    setAddMenuId("");
    setAddMenuIds({});
    setAddMenuPrices({});
    setAddPosPrice("");
    setAddPosSelected(false);
    setAddOnlinePrice("");
    setAddUseCategoryTypes(true);
    setAddOrderTypes(Object.fromEntries(ALL_ORDER_TYPES.map((t) => [t, true])));
    setAddModifiers({});
    setAddTaxes({});
    setAddModifiersExpanded(false);
    setAddTaxesExpanded(false);
    setAddLabelExpanded(false);
    setAddPrinterLabel("");
    setAddKdsExpanded(false);
    setAddKdsDeviceId("");
  };

  // ── Add Category ──

  const openEditCategory = (cat: Category) => {
    setEditCategoryTarget(cat);
    setEditCategoryOrderTypes(
      Object.fromEntries(
        ALL_ORDER_TYPES.map((t) => [t, cat.availableOrderTypes.includes(t)])
      )
    );
    setEditCategorySchedules(
      Object.fromEntries(
        allSchedules.map((s) => [s.id, cat.scheduleIds.includes(s.id)])
      )
    );
  };

  const handleSaveCategory = async () => {
    if (!editCategoryTarget) return;
    const availableOrderTypes = ALL_ORDER_TYPES.filter((t) => editCategoryOrderTypes[t]);
    if (availableOrderTypes.length === 0) return;

    const scheduleIds = Object.entries(editCategorySchedules)
      .filter(([, v]) => v)
      .map(([k]) => k);

    setEditCategorySaving(true);
    try {
      await updateDoc(doc(db, "Categories", editCategoryTarget.id), {
        availableOrderTypes,
        scheduleIds,
      });
      setEditCategoryTarget(null);
    } catch (err) {
      console.error("Failed to update category:", err);
    } finally {
      setEditCategorySaving(false);
    }
  };

  const handleDeleteCategory = async () => {
    if (!deleteCategoryTarget) return;
    setDeletingCategory(true);
    try {
      await purgeCategoryFromFirestore(deleteCategoryTarget.id);
      if (activeCategory === deleteCategoryTarget.id) setActiveCategory(null);
      setDeleteCategoryTarget(null);
    } catch (err) {
      console.error("Failed to delete category:", err);
    } finally {
      setDeletingCategory(false);
    }
  };

  const handleAddCategory = async () => {
    const name = addCategoryName.trim();
    setAddCategoryError("");
    if (!name) return;

    const normalized = name.toLowerCase();
    const exists = categories.some((c) => c.name.toLowerCase() === normalized);
    if (exists) {
      setAddCategoryError("A category with this name already exists.");
      return;
    }

    const availableOrderTypes = ALL_ORDER_TYPES.filter((t) => addCategoryOrderTypes[t]);
    if (availableOrderTypes.length === 0) {
      setAddCategoryError("Select at least one order type.");
      return;
    }

    const scheduleIds = Object.entries(addCategorySchedules)
      .filter(([, v]) => v)
      .map(([k]) => k);

    setAddCategorySaving(true);
    try {
      await addDoc(collection(db, "Categories"), {
        name,
        availableOrderTypes,
        scheduleIds,
      });
      setAddCategoryName("");
      setAddCategoryOrderTypes(Object.fromEntries(ALL_ORDER_TYPES.map((t) => [t, true])));
      setAddCategorySchedules({});
      setAddCategoryOpen(false);
    } catch (err) {
      console.error("Failed to add category:", err);
    } finally {
      setAddCategorySaving(false);
    }
  };

  const openSubEdit = (sub: SubcategoryEntry) => {
    setSubEditing(sub);
    setSubName(sub.name);
    setSubCategoryId(sub.categoryId);
    const sel: Record<string, boolean> = {};
    for (const item of items) {
      if (item.categoryId === sub.categoryId) {
        sel[item.id] = item.subcategoryId === sub.id;
      }
    }
    setSubItemSelections(sel);
    setSubModalOpen(true);
  };

  const handleSaveSubcategory = async () => {
    const name = subName.trim();
    if (!name || !subCategoryId) return;
    setSubSaving(true);
    try {
      let subId: string;
      if (subEditing) {
        subId = subEditing.id;
        await updateDoc(doc(db, "subcategories", subId), {
          name,
          categoryId: subCategoryId,
          updatedAt: serverTimestamp(),
        });
      } else {
        const nextOrder = allSubcategories.filter((s) => s.categoryId === subCategoryId).length;
        const ref = await addDoc(collection(db, "subcategories"), {
          name,
          categoryId: subCategoryId,
          order: nextOrder,
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
        subId = ref.id;
      }

      const batch = writeBatch(db);
      for (const [itemId, checked] of Object.entries(subItemSelections)) {
        if (checked) {
          batch.update(doc(db, "MenuItems", itemId), { subcategoryId: subId });
        } else {
          const item = items.find((i) => i.id === itemId);
          if (item && item.subcategoryId === (subEditing?.id ?? subId)) {
            batch.update(doc(db, "MenuItems", itemId), { subcategoryId: "" });
          }
        }
      }
      await batch.commit();

      setSubModalOpen(false);
    } catch (err) {
      console.error("Failed to save subcategory:", err);
    } finally {
      setSubSaving(false);
    }
  };

  const handleDeleteSubcategory = async () => {
    if (!deleteSubTarget) return;
    setDeletingSub(true);
    try {
      const affected = items.filter((i) => i.subcategoryId === deleteSubTarget.id);
      if (affected.length > 0) {
        const batch = writeBatch(db);
        for (const item of affected) {
          batch.update(doc(db, "MenuItems", item.id), { subcategoryId: "" });
        }
        await batch.commit();
      }
      await deleteDoc(doc(db, "subcategories", deleteSubTarget.id));
      setDeleteSubTarget(null);
      if (activeSubcategory === deleteSubTarget.id) setActiveSubcategory(null);
    } catch (err) {
      console.error("Failed to delete subcategory:", err);
    } finally {
      setDeletingSub(false);
    }
  };

  const handleAddItem = async () => {
    const name = addName.trim();
    if (!name) return;

    const addTargets: string[] = [];
    if (addPosSelected) addTargets.push(ADD_ITEM_POS_TARGET);
    for (const m of menuEntities.filter((x) => x.isActive)) {
      if (addMenuIds[m.id]) addTargets.push(m.id);
    }
    if (addTargets.length === 0) return;
    for (const t of addTargets) {
      if (!addPlacementCategory[t]) return;
    }

    const categoryIdsUnique = [...new Set(addTargets.map((t) => addPlacementCategory[t]))];
    const subcategoryByCategoryId: Record<string, string> = {};
    for (const t of addTargets) {
      const cid = addPlacementCategory[t];
      const sub = (addPlacementSubcategory[t] ?? "").trim();
      if (subcategoryByCategoryId[cid] === undefined) {
        subcategoryByCategoryId[cid] = sub;
      } else if (sub.length > 0) {
        subcategoryByCategoryId[cid] = sub;
      }
    }

    const legacyCategoryId =
      addPosSelected && addPlacementCategory[ADD_ITEM_POS_TARGET]
        ? addPlacementCategory[ADD_ITEM_POS_TARGET]
        : categoryIdsUnique[0] ?? "";
    const legacySubcategoryId = subcategoryByCategoryId[legacyCategoryId] ?? "";

    const placedCats = categoryIdsUnique
      .map((id) => categories.find((c) => c.id === id))
      .filter((c): c is Category => c != null);
    const anyScheduled = placedCats.some((c) => c.scheduleIds.length > 0);

    const prices: Record<string, number> = {};
    for (const [menuId, val] of Object.entries(addMenuPrices)) {
      if (!addMenuIds[menuId]) continue;
      const num = parseFloat(val);
      if (!isNaN(num) && num >= 0) prices[menuId] = num;
    }

    const parsedPos = parseFloat(addPosPrice);
    const firstMenuPrice = Object.values(prices)[0];
    let posPrice: number;
    if (anyScheduled) {
      posPrice = (!isNaN(parsedPos) && parsedPos >= 0) ? parsedPos : (firstMenuPrice ?? -1);
    } else {
      if (!addPosSelected && !Object.entries(addMenuIds).some(([, v]) => v)) return;
      posPrice = addPosSelected
        ? ((!isNaN(parsedPos) && parsedPos >= 0) ? parsedPos : (firstMenuPrice ?? -1))
        : (firstMenuPrice ?? -1);
    }
    if (posPrice < 0) return;

    prices.default = posPrice;
    const price = posPrice;

    const stock = stockCountingEnabled ? parseInt(addStock, 10) : 9999;
    if (stockCountingEnabled && (isNaN(stock) || stock < 0)) return;

    const scheduledMenuIdsSet = new Set<string>();
    for (const c of placedCats) {
      if (c.scheduleIds.length === 0) continue;
      for (const m of menuEntities) {
        if (m.scheduleIds.some((sid) => c.scheduleIds.includes(sid))) scheduledMenuIdsSet.add(m.id);
      }
    }
    const scheduledMenuIds = Array.from(scheduledMenuIdsSet);
    const fromCheckboxes = Object.entries(addMenuIds)
      .filter(([, v]) => v)
      .map(([k]) => k);
    let selectedMenuIds = anyScheduled ? scheduledMenuIds : fromCheckboxes;
    if (anyScheduled && selectedMenuIds.length === 0 && fromCheckboxes.length > 0) {
      selectedMenuIds = fromCheckboxes;
    }

    const unionScheduleIds = [...new Set(placedCats.flatMap((c) => c.scheduleIds))];

    setAddSaving(true);
    try {
      const data: Record<string, unknown> = {
        name,
        prices,
        price,
        stock,
        categoryId: legacyCategoryId,
        categoryIds: categoryIdsUnique,
        subcategoryByCategoryId,
        menuId: selectedMenuIds.length > 0 ? selectedMenuIds[0] : "",
        menuIds: selectedMenuIds,
        pricing: { pos: posPrice, online: posPrice },
        channels: { pos: true, online: false },
        subcategoryId: legacySubcategoryId,
        isScheduled: anyScheduled,
        scheduleIds: anyScheduled ? unionScheduleIds : [],
        modifierGroupIds: Object.entries(addModifiers)
          .filter(([, v]) => v)
          .map(([k]) => k),
        taxIds: Object.entries(addTaxes)
          .filter(([, v]) => v)
          .map(([k]) => k),
        externalMappings: {},
      };

      if (!addUseCategoryTypes) {
        data.availableOrderTypes = ALL_ORDER_TYPES.filter((t) => addOrderTypes[t]);
      }

      const addPl = addPrinterLabel.trim();
      if (addPl) {
        data.printerLabel = addPl;
        await mergeKitchenLabelToSettings(addPl);
      }

      const newRef = await addDoc(collection(db, "MenuItems"), data);
      const kdsPick = addKdsDeviceId.trim();
      if (kdsPick) {
        try {
          await updateDoc(doc(db, KDS_DEVICES_COLLECTION, kdsPick), {
            assignedItemIds: arrayUnion(newRef.id),
            updatedAt: serverTimestamp(),
          });
        } catch (kdsErr) {
          console.error("[Menu] KDS assignment after add item:", kdsErr);
        }
      }

      resetAddForm();
      setAddOpen(false);
    } catch (err) {
      console.error("Failed to add item:", err);
    } finally {
      setAddSaving(false);
    }
  };

  // ── Download Menu as Excel ──

  const handleDownloadMenu = async () => {
    const XLSX = await import("xlsx");
    const wb = XLSX.utils.book_new();

    // 1. Items
    const itemRows = items.map((item) => {
      const row: Record<string, string | number> = {
        "Item ID": item.id,
        "Name": item.name,
        "Price (default)": item.price,
      };
      for (const m of menuEntities) {
        row[`Price (${m.name})`] = item.prices[m.id] ?? "";
      }
      row["Category ID"] = item.categoryId;
      row["Modifier Group IDs"] = item.modifierGroupIds.join(", ");
      row["Tax IDs"] = item.taxIds.join(", ");
      row["Order Types"] = item.effectiveOrderTypes.join(", ");
      return row;
    });
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(itemRows), "Items");

    // 2. Categories
    const catRows = categories.map((c) => ({
      "Category ID": c.id,
      "Category Name": c.name,
    }));
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(catRows), "Categories");

    // 3. Modifier Groups
    const modGroupRows = modifierGroups.map((g) => ({
      "Modifier Group ID": g.id,
      "Modifier Group Name": g.name,
    }));
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(modGroupRows), "Modifier Groups");

    // 4. Modifier Options
    const modOptionRows: Record<string, string | number>[] = [];
    for (const group of modifierGroups) {
      for (const opt of group.options) {
        modOptionRows.push({
          "Option ID": opt.id,
          "Modifier Group ID": group.id,
          "Option Name": opt.name,
          "Price": opt.price,
        });
      }
    }
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(modOptionRows), "Modifier Options");

    // 5. Taxes
    const taxRows = taxes.map((t) => ({
      "Tax ID": t.id,
      "Tax Name": t.name,
      "Rate": t.amount,
    }));
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(taxRows), "Taxes");

    XLSX.writeFile(wb, "menu.xlsx");
  };

  // ── Stats ──
  const totalItems = items.length;
  const totalCategories = categories.length;
  const outOfStock = items.filter((i) => i.stock <= 0).length;

  // ── Helper to get names for badges ──
  const modGroupMap = new Map(modifierGroups.map((g) => [g.id, g.name]));
  const modGroupFullMap = new Map(modifierGroups.map((g) => [g.id, g]));
  const taxMap = new Map(taxes.map((t) => [t.id, t.name]));
  const menuEntityMap = new Map(menuEntities.map((m) => [m.id, m.name]));
  const scheduleMap = new Map(allSchedules.map((s) => [s.id, s.name]));

  const addMenuTargetsOrdered: string[] = [];
  if (addPosSelected) addMenuTargetsOrdered.push(ADD_ITEM_POS_TARGET);
  for (const m of menuEntities.filter((x) => x.isActive)) {
    if (addMenuIds[m.id]) addMenuTargetsOrdered.push(m.id);
  }
  const addPlacedCategoriesList = addMenuTargetsOrdered
    .map((t) => categories.find((c) => c.id === addPlacementCategory[t]))
    .filter((c): c is Category => c != null);
  const addCategoryHasSchedule = addPlacedCategoriesList.some((c) => c.scheduleIds.length > 0);
  const addScheduleBannerNames = [...new Set(addPlacedCategoriesList.flatMap((c) => c.scheduleIds))]
    .map((id) => scheduleMap.get(id) ?? id)
    .join(", ");
  const addAllPlacementsFilled =
    addMenuTargetsOrdered.length > 0 &&
    addMenuTargetsOrdered.every((t) => !!addPlacementCategory[t]);

  const editSelectedCategory = editTarget ? categories.find((c) => c.id === editTarget.categoryId) : null;
  const editCategoryHasSchedule = (editSelectedCategory?.scheduleIds.length ?? 0) > 0;
  const editScheduledMenus = editCategoryHasSchedule
    ? menuEntities.filter((m) => m.scheduleIds.some((sid) => editSelectedCategory!.scheduleIds.includes(sid)))
    : [];

  return (
    <>
      <Header title="Menu" searchValue={search} onSearchChange={setSearch} />
      <div className="px-5 pt-3 pb-6 space-y-4">
        {/* ── Top bar ── */}
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <div className="flex items-center gap-3">
            <div className="relative">
              <Search size={15} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
              <input
                type="text"
                placeholder="Search items..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="pl-8 pr-3 py-1.5 rounded-lg bg-white border border-slate-200 text-sm text-slate-700 focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-400/20 w-52"
              />
            </div>
            <div className="hidden md:flex items-center gap-1.5">
              <div className="flex items-center bg-slate-100 rounded-lg p-0.5 gap-0.5">
                <button
                  onClick={() => { setMenuTypeFilter(null); resetCategoryNavForMenuTarget(); }}
                  className={`px-2.5 py-1 rounded-md text-[11px] font-medium transition-all ${
                    menuTypeFilter === null ? "bg-white shadow-sm text-slate-700" : "text-slate-400 hover:text-slate-600"
                  }`}
                >
                  All
                </button>
                <button
                  onClick={() => { setMenuTypeFilter(menuTypeFilter === "POS" ? null : "POS"); resetCategoryNavForMenuTarget(); }}
                  className={`px-2.5 py-1 rounded-md text-[11px] font-medium transition-all ${
                    menuTypeFilter === "POS" ? "bg-white shadow-sm text-emerald-600" : "text-slate-400 hover:text-slate-600"
                  }`}
                >
                  POS
                </button>
                {menuEntities.filter((m) => m.isActive).map((m) => (
                  <button
                    key={m.id}
                    onClick={() => { setMenuTypeFilter(menuTypeFilter === m.id ? null : m.id); resetCategoryNavForMenuTarget(); }}
                    className={`px-2.5 py-1 rounded-md text-[11px] font-medium transition-all ${
                      menuTypeFilter === m.id ? "bg-white shadow-sm text-purple-600" : "text-slate-400 hover:text-slate-600"
                    }`}
                  >
                    {m.name}
                  </button>
                ))}
              </div>
            </div>
            <div className="hidden lg:flex items-center text-xs text-slate-400 gap-1.5">
              <span className="font-medium text-slate-500">{totalItems} items</span>
              <span>·</span>
              <span className="font-medium text-slate-500">{totalCategories} categories</span>
              {stockCountingEnabled && outOfStock > 0 && (
                <>
                  <span>·</span>
                  <span className="font-medium text-red-500">{outOfStock} out of stock</span>
                </>
              )}
            </div>
          </div>

          <div className="flex items-center gap-2">
            <div className="flex items-center bg-slate-100 rounded-lg p-0.5 gap-0.5">
              <button
                onClick={() => setViewMode("compact")}
                className={`p-1.5 rounded-md transition-all ${viewMode === "compact" ? "bg-white shadow-sm text-slate-700" : "text-slate-400 hover:text-slate-600"}`}
                title="Compact view"
              >
                <List size={15} />
              </button>
              <button
                onClick={() => setViewMode("card")}
                className={`p-1.5 rounded-md transition-all ${viewMode === "card" ? "bg-white shadow-sm text-slate-700" : "text-slate-400 hover:text-slate-600"}`}
                title="Card view"
              >
                <LayoutGrid size={15} />
              </button>
            </div>

            <div className="w-px h-5 bg-slate-200" />

            {categoryFilterMode ? (
              <>
                <button
                  type="button"
                  onClick={toggleFilterSelectAllCategories}
                  disabled={categoriesVisibleForMenu.length === 0}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-700 hover:bg-slate-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <CheckSquare size={14} />
                  {categoriesVisibleForMenu.length > 0 &&
                  categoriesVisibleForMenu.every((c) => filterCategoryIds.includes(c.id))
                    ? "Deselect all categories"
                    : "Select all categories"}
                </button>
                <button
                  type="button"
                  onClick={clearCategoryFilters}
                  disabled={filterCategoryIds.length === 0 && filterSubcategoryIds.length === 0}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-700 hover:bg-slate-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  Clear
                </button>
                <button
                  type="button"
                  onClick={() => setBulkCategoriesDeleteConfirm(true)}
                  disabled={filterCategoryIds.length === 0}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-red-600 text-white text-xs font-medium hover:bg-red-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  title="Permanently delete selected categories, their subcategories, and all menu items in those categories"
                >
                  <Trash2 size={14} />
                  Delete categories
                  {filterCategoryIds.length > 0 ? ` (${filterCategoryIds.length})` : ""}
                </button>
                <button
                  type="button"
                  onClick={exitCategoryFilterMode}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-700 hover:bg-slate-50 transition-colors"
                >
                  <X size={14} />
                  Done
                </button>
              </>
            ) : selectMode ? (
              <>
                <button
                  onClick={toggleSelectAll}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-700 hover:bg-slate-50 transition-colors"
                >
                  <CheckSquare size={14} />
                  {selectedItems.size === filtered.length ? "Deselect All" : "Select All"}
                </button>
                <button
                  onClick={() => setBulkDeleteConfirm(true)}
                  disabled={selectedItems.size === 0}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-red-600 text-white text-xs font-medium hover:bg-red-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <Trash2 size={14} />
                  Delete{selectedItems.size > 0 ? ` (${selectedItems.size})` : ""}
                </button>
                <button
                  onClick={exitSelectMode}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-700 hover:bg-slate-50 transition-colors"
                >
                  <X size={14} />
                  Cancel
                </button>
              </>
            ) : transferMode ? (
              <>
                <button
                  onClick={toggleTransferAll}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-700 hover:bg-slate-50 transition-colors"
                >
                  <CheckSquare size={14} />
                  {transferItems.size === filtered.length ? "Deselect All" : "Select All"}
                </button>
                <button
                  onClick={openTransferModal}
                  disabled={
                    transferItems.size === 0 &&
                    transferCategories.size === 0 &&
                    transferSubcategories.size === 0
                  }
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-indigo-600 text-white text-xs font-medium hover:bg-indigo-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <ArrowRightLeft size={14} />
                  Transfer
                  {transferItems.size + transferCategories.size + transferSubcategories.size > 0
                    ? ` (${transferItems.size + transferCategories.size + transferSubcategories.size})`
                    : ""}
                </button>
                <button
                  onClick={exitTransferMode}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-700 hover:bg-slate-50 transition-colors"
                >
                  <X size={14} />
                  Cancel
                </button>
              </>
            ) : (
              <>
                <button
                  onClick={() => setSelectMode(true)}
                  disabled={items.length === 0 || categoryFilterMode}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  title="Select items"
                >
                  <CheckSquare size={14} />
                  <span className="hidden lg:inline">Select</span>
                </button>
                <button
                  onClick={handleDownloadMenu}
                  disabled={items.length === 0}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  title="Download Menu"
                >
                  <Download size={14} />
                  <span className="hidden lg:inline">Download</span>
                </button>
                <button
                  type="button"
                  onClick={() => setUploadOpen(true)}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors"
                  title="Upload menu from Excel"
                >
                  <Upload size={14} />
                  <span className="hidden lg:inline">Upload</span>
                </button>
                <button
                  onClick={() => {
                    setAddCategoryName("");
                    setAddCategoryOrderTypes(Object.fromEntries(ALL_ORDER_TYPES.map((t) => [t, true])));
                    setAddCategorySchedules({});
                    setAddCategoryError("");
                    setAddCategoryOpen(true);
                  }}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors"
                  title="Add Category"
                >
                  <FolderPlus size={14} />
                  <span className="hidden lg:inline">Category</span>
                </button>
                <button
                  onClick={() => {
                    setSubEditing(null);
                    setSubName("");
                    setSubCategoryId("");
                    setSubItemSelections({});
                    setSubModalOpen(true);
                  }}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors"
                  title="Add Subcategory"
                >
                  <Layers size={14} />
                  <span className="hidden lg:inline">Subcategory</span>
                </button>
                <button
                  onClick={() => setTransferMode(true)}
                  disabled={
                    categoryFilterMode ||
                    (items.length === 0 && categories.length < 2 && allSubcategories.length === 0)
                  }
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  title="Transfer items, categories, or promote subcategories to top-level categories"
                >
                  <ArrowRightLeft size={14} />
                  <span className="hidden lg:inline">Transfer</span>
                </button>
                <button
                  onClick={() => { resetAddForm(); setAddOpen(true); }}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-medium hover:bg-blue-700 transition-colors"
                >
                  <Plus size={14} />
                  Add Item
                </button>
              </>
            )}
          </div>
        </div>

        {/* ── Main layout ── */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="w-5 h-5 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          </div>
        ) : (
          <div className="flex gap-5 min-h-[calc(100vh-11rem)]">
            {/* ── Category sidebar ── */}
            {categories.length > 0 && (
              <div className="hidden lg:block w-[320px] shrink-0">
                <div className="sticky top-4 bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
                  <div className="px-4 py-3 border-b border-slate-100">
                    <p className="text-xs font-bold text-slate-400 uppercase tracking-wider">Categories</p>
                    {categoryFilterMode && (
                      <p className="text-[11px] text-slate-500 mt-1.5 leading-snug">
                        Check categories (and optionally subcategories) to filter the list. Use{" "}
                        <span className="font-medium text-slate-600">Delete categories</span> in the toolbar to remove
                        selected categories — all items in those categories are deleted too.
                      </p>
                    )}
                  </div>
                  <nav className="flex flex-col max-h-[calc(100vh-12rem)] overflow-y-auto py-1">
                    <button
                      type="button"
                      onClick={() => {
                        if (categoryFilterMode) {
                          toggleFilterSelectAllCategories();
                        } else {
                          enterCategoryFilterMode();
                        }
                      }}
                      className={`w-full flex items-center justify-between px-3 py-2.5 text-sm transition-all duration-150 ${
                        categoryFilterMode
                          ? categoriesVisibleForMenu.length > 0 && categoriesVisibleForMenu.every((c) => filterCategoryIds.includes(c.id))
                            ? "bg-blue-50 text-blue-700 font-bold border-l-4 border-blue-600"
                            : "text-slate-600 font-semibold hover:bg-slate-50 border-l-4 border-transparent"
                          : "text-slate-600 font-semibold hover:bg-slate-50 border-l-4 border-transparent"
                      }`}
                    >
                      <span>{categoryFilterMode
                        ? categoriesVisibleForMenu.length > 0 && categoriesVisibleForMenu.every((c) => filterCategoryIds.includes(c.id))
                          ? "Deselect All"
                          : "Select All"
                        : "Select"
                      }</span>
                      <CheckSquare size={14} className="text-slate-400 shrink-0" />
                    </button>
                    {categoriesVisibleForMenu.map((cat) => {
                      const catItemCount = itemsForMenuType.filter((i) => i.viewCategoryId === cat.id).length;
                      const catSubs = allSubcategories.filter((s) => s.categoryId === cat.id);
                      return (
                        <div key={cat.id}>
                          <div
                            className={`group/cat flex items-center transition-all duration-150 ${
                              categoryFilterMode
                                ? filterCategoryIds.includes(cat.id)
                                  ? "bg-blue-50 border-l-4 border-blue-600"
                                  : "hover:bg-slate-50 border-l-4 border-transparent"
                                : activeCategory === cat.id && !activeSubcategory
                                  ? "bg-blue-50 border-l-4 border-blue-600"
                                  : "hover:bg-slate-50 border-l-4 border-transparent"
                            }`}
                          >
                            {categoryFilterMode && (
                              <label
                                className="pl-2 pr-0 flex items-center shrink-0 cursor-pointer"
                                onClick={(e) => e.stopPropagation()}
                              >
                                <input
                                  type="checkbox"
                                  checked={filterCategoryIds.includes(cat.id)}
                                  onChange={() => toggleFilterCategory(cat.id)}
                                  className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                                />
                              </label>
                            )}
                            {transferMode && (
                              <label
                                className="pl-2 pr-0 flex items-center shrink-0 cursor-pointer"
                                onClick={(e) => e.stopPropagation()}
                              >
                                <input
                                  type="checkbox"
                                  checked={transferCategories.has(cat.id)}
                                  onChange={() => toggleTransferCategory(cat.id)}
                                  className="w-4 h-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                                />
                              </label>
                            )}
                            <button
                              type="button"
                              onClick={() => {
                                if (categoryFilterMode) toggleFilterCategory(cat.id);
                                else {
                                  setActiveCategory(activeCategory === cat.id ? null : cat.id);
                                  setActiveSubcategory(null);
                                }
                              }}
                              className={`flex-1 flex items-center justify-between px-3 py-2.5 text-sm min-w-0 ${
                                categoryFilterMode
                                  ? filterCategoryIds.includes(cat.id)
                                    ? "text-blue-700 font-bold"
                                    : "text-slate-600 font-semibold"
                                  : activeCategory === cat.id && !activeSubcategory
                                    ? "text-blue-700 font-bold"
                                    : "text-slate-600 font-semibold"
                              }`}
                            >
                              <span className="flex items-center gap-1.5 min-w-0">
                                <span className="break-words leading-snug">{cat.name}</span>
                                {cat.scheduleIds.length > 0 && (
                                  <span className="text-[9px] px-1.5 py-0.5 rounded-full bg-blue-100 text-blue-500 font-bold leading-none shrink-0" title={cat.scheduleIds.map((id) => scheduleMap.get(id) ?? id).join(", ")}>
                                    Sched
                                  </span>
                                )}
                              </span>
                              <span className="text-xs text-slate-400 font-medium ml-2 tabular-nums bg-slate-100 px-1.5 py-0.5 rounded-full shrink-0">{catItemCount}</span>
                            </button>
                            <div className="flex items-center gap-0 pr-1 opacity-0 group-hover/cat:opacity-100 transition-opacity shrink-0">
                              <button
                                onClick={(e) => { e.stopPropagation(); openEditCategory(cat); }}
                                className="p-1 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-200/60 transition-colors"
                                title="Edit category"
                              >
                                <Pencil size={11} />
                              </button>
                              <button
                                onClick={(e) => { e.stopPropagation(); setDeleteCategoryTarget(cat); }}
                                className="p-1 rounded-md text-slate-400 hover:text-red-500 hover:bg-red-50 transition-colors"
                                title="Delete category"
                              >
                                <Trash2 size={11} />
                              </button>
                            </div>
                          </div>
                          {catSubs.length > 0 && (
                            <div className="ml-4 border-l border-slate-200">
                              {catSubs.map((sub) => {
                                const subItemCount = itemsForMenuType.filter((i) => i.viewSubcategoryId === sub.id).length;
                                return (
                                  <div
                                    key={sub.id}
                                    className={`group/sub flex items-center transition-all duration-150 ${
                                      categoryFilterMode
                                        ? filterSubcategoryIds.includes(sub.id)
                                          ? "bg-blue-50/60"
                                          : "hover:bg-slate-50"
                                        : activeSubcategory === sub.id
                                          ? "bg-blue-50/60"
                                          : "hover:bg-slate-50"
                                    }`}
                                  >
                                    {categoryFilterMode && (
                                      <label
                                        className="pl-1 pr-0 flex items-center shrink-0 cursor-pointer"
                                        onClick={(e) => e.stopPropagation()}
                                      >
                                        <input
                                          type="checkbox"
                                          checked={filterSubcategoryIds.includes(sub.id)}
                                          onChange={() => toggleFilterSubcategory(sub.id)}
                                          className="w-3.5 h-3.5 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                                        />
                                      </label>
                                    )}
                                    {transferMode && (
                                      <label
                                        className="pl-1 pr-0 flex items-center shrink-0 cursor-pointer"
                                        onClick={(e) => e.stopPropagation()}
                                      >
                                        <input
                                          type="checkbox"
                                          checked={transferSubcategories.has(sub.id)}
                                          onChange={() => toggleTransferSubcategory(sub.id)}
                                          className="w-3.5 h-3.5 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                                        />
                                      </label>
                                    )}
                                    <button
                                      type="button"
                                      onClick={() => {
                                        if (categoryFilterMode) toggleFilterSubcategory(sub.id);
                                        else {
                                          setActiveCategory(cat.id);
                                          setActiveSubcategory(activeSubcategory === sub.id ? null : sub.id);
                                        }
                                      }}
                                      className={`flex-1 flex items-center justify-between pl-3 pr-2 py-1.5 text-xs min-w-0 ${
                                        categoryFilterMode
                                          ? filterSubcategoryIds.includes(sub.id)
                                            ? "text-blue-600 font-bold"
                                            : "text-slate-500 font-medium"
                                          : activeSubcategory === sub.id
                                            ? "text-blue-600 font-bold"
                                            : "text-slate-500 font-medium"
                                      }`}
                                    >
                                      <span className="truncate">{sub.name}</span>
                                      <span className="text-[10px] text-slate-400 font-medium ml-2 tabular-nums bg-slate-100 px-1.5 py-0.5 rounded-full shrink-0">{subItemCount}</span>
                                    </button>
                                    <div className="flex items-center gap-0 pr-1 opacity-0 group-hover/sub:opacity-100 transition-opacity shrink-0">
                                      <button
                                        onClick={(e) => { e.stopPropagation(); openSubEdit(sub); }}
                                        className="p-0.5 rounded text-slate-400 hover:text-slate-600 hover:bg-slate-200/60 transition-colors"
                                        title="Manage subcategory"
                                      >
                                        <Pencil size={10} />
                                      </button>
                                      <button
                                        onClick={(e) => { e.stopPropagation(); setDeleteSubTarget(sub); }}
                                        className="p-0.5 rounded text-slate-400 hover:text-red-500 hover:bg-red-50 transition-colors"
                                        title="Delete subcategory"
                                      >
                                        <Trash2 size={10} />
                                      </button>
                                    </div>
                                  </div>
                                );
                              })}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </nav>
                </div>
              </div>
            )}

            {/* ── Mobile category bar ── */}
            {categories.length > 0 && (
              <div className="lg:hidden flex flex-col gap-2 pb-1 -mt-1 mb-1 w-full">
                {categoryFilterMode && (
                  <p className="text-[11px] text-slate-500 px-0.5 leading-snug">
                    Select categories to filter. Use Delete categories in the toolbar to remove them (and their items).
                  </p>
                )}
                <div className="flex gap-2 overflow-x-auto">
                  <button
                    type="button"
                    onClick={() => {
                      if (categoryFilterMode) {
                        toggleFilterSelectAllCategories();
                      } else {
                        enterCategoryFilterMode();
                      }
                    }}
                    className={`shrink-0 px-4 py-2 rounded-lg text-sm font-semibold transition-colors flex items-center gap-1.5 ${
                      categoryFilterMode && categoriesVisibleForMenu.length > 0 && categoriesVisibleForMenu.every((c) => filterCategoryIds.includes(c.id))
                        ? "bg-blue-600 text-white shadow-sm"
                        : "bg-white border border-slate-200 text-slate-600"
                    }`}
                  >
                    <CheckSquare size={14} />
                    {categoryFilterMode
                      ? categoriesVisibleForMenu.length > 0 && categoriesVisibleForMenu.every((c) => filterCategoryIds.includes(c.id))
                        ? "Deselect All"
                        : "Select All"
                      : "Select"
                    }
                  </button>
                  {categoriesVisibleForMenu.map((cat) => {
                    const catOn = categoryFilterMode ? filterCategoryIds.includes(cat.id) : activeCategory === cat.id;
                    return (
                      <div
                        key={cat.id}
                        className={`shrink-0 flex items-center gap-1.5 rounded-lg ${
                          catOn ? "bg-blue-600 text-white shadow-sm" : "bg-white border border-slate-200 text-slate-600"
                        }`}
                      >
                        {categoryFilterMode && (
                          <input
                            type="checkbox"
                            checked={filterCategoryIds.includes(cat.id)}
                            onChange={() => toggleFilterCategory(cat.id)}
                            onClick={(e) => e.stopPropagation()}
                            className="ml-2 w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                          />
                        )}
                        {transferMode && (
                          <input
                            type="checkbox"
                            checked={transferCategories.has(cat.id)}
                            onChange={() => toggleTransferCategory(cat.id)}
                            onClick={(e) => e.stopPropagation()}
                            className="ml-2 w-4 h-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                          />
                        )}
                        <button
                          type="button"
                          onClick={() =>
                            categoryFilterMode
                              ? toggleFilterCategory(cat.id)
                              : setActiveCategory(activeCategory === cat.id ? null : cat.id)
                          }
                          className={`shrink-0 px-3 py-2 rounded-lg text-sm font-semibold transition-colors ${
                            catOn ? "text-white" : "text-slate-600"
                          }`}
                        >
                          {cat.name}
                        </button>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* ── Items list (fills all remaining width) ── */}
            <div className="flex-1 min-w-0 overflow-y-auto">
              {filtered.length === 0 ? (
                <div className="bg-white rounded-xl border border-slate-100 p-16 text-center">
                  <p className="text-slate-400 text-base">No menu items found</p>
                  <p className="text-slate-300 text-sm mt-1">Items from your POS will appear here</p>
                </div>
              ) : viewMode === "compact" ? (
                /* ── Compact list view ── */
                <div className="space-y-6">
                  {itemSections.map(({ key: sectionKey, title: sectionTitle, items: groupItems }) => (
                    <section key={sectionKey} className="animate-in fade-in duration-200">
                      <div className="flex items-center gap-3 mb-2 px-1">
                        <h2 className="text-sm font-bold text-slate-500 uppercase tracking-wider">{sectionTitle}</h2>
                        <span className="text-xs text-slate-400 bg-slate-100 px-2 py-0.5 rounded-full font-medium">{groupItems.length}</span>
                      </div>
                      <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
                        <div className="hidden sm:flex items-center gap-4 px-5 py-3 bg-slate-50 border-b border-slate-200">
                          {(selectMode || transferMode) && <div className="w-5 shrink-0" />}
                          <div className="flex-1 min-w-0 text-xs font-bold text-slate-400 uppercase tracking-wider">Item</div>
                          <div className="w-40 text-center shrink-0 text-xs font-bold text-slate-400 uppercase tracking-wider">Type</div>
                          <div className="w-32 text-center shrink-0 text-xs font-bold text-slate-400 uppercase tracking-wider">Info</div>
                          <div className="w-20 text-right shrink-0 text-xs font-bold text-slate-400 uppercase tracking-wider">Price</div>
                          {!selectMode && !transferMode && <div className="w-16 shrink-0" />}
                        </div>
                        <div className="divide-y divide-slate-200">
                          {groupItems.map((item) => {
                            const inStock = !stockCountingEnabled || item.stock > 0;
                            const isSelected = selectMode ? selectedItems.has(item.id) : transferMode ? transferItems.has(item.id) : false;
                            return (
                              <div
                                key={item.id}
                                onClick={selectMode ? () => toggleSelectItem(item.id) : transferMode ? () => toggleTransferItem(item.id) : () => setSelectedItem((prev) => (prev?.id === item.id ? null : item))}
                                className={`flex items-center gap-4 px-5 py-4 transition-all duration-150 group cursor-pointer ${
                                  selectedItem?.id === item.id && !transferMode
                                    ? "bg-blue-50 ring-1 ring-inset ring-blue-200"
                                    : isSelected
                                    ? transferMode ? "bg-indigo-50/60" : "bg-blue-50/60"
                                    : !inStock
                                    ? "bg-red-50/30"
                                    : "hover:bg-slate-50"
                                }`}
                              >
                                {(selectMode || transferMode) && (
                                  <input
                                    type="checkbox"
                                    checked={isSelected}
                                    readOnly
                                    className={`w-4 h-4 rounded border-slate-300 focus:ring-blue-500 pointer-events-none shrink-0 ${transferMode ? "text-indigo-600" : "text-blue-600"}`}
                                  />
                                )}
                                <div className="flex-1 min-w-0 flex items-center gap-3">
                                  <span className={`text-[17px] font-semibold truncate ${inStock ? "text-slate-800" : "text-slate-400 line-through"}`}>
                                    {item.name}
                                  </span>
                                  {!inStock && (
                                    <span className="text-[10px] px-2 py-0.5 rounded-full bg-red-100 text-red-600 font-bold shrink-0">OUT</span>
                                  )}
                                  {stockCountingEnabled && inStock && item.stock <= 10 && (
                                    <span className="text-xs text-amber-500 font-semibold shrink-0">{item.stock} left</span>
                                  )}
                                </div>
                                <div className="w-40 hidden sm:flex items-center justify-center gap-1.5 shrink-0 flex-wrap">
                                  {item.effectiveOrderTypes.map((t) => (
                                    <span key={t} className="text-[11px] px-2 py-0.5 rounded-full bg-blue-50 text-blue-700 font-semibold">
                                      {ORDER_TYPE_LABELS[t] ?? t}
                                    </span>
                                  ))}
                                </div>
                                <div className="w-32 hidden sm:flex items-center justify-center gap-1.5 shrink-0 flex-wrap">
                                  {!item.viewCategoryScheduled && (
                                    <span className="text-[11px] px-2 py-0.5 rounded-md bg-emerald-50 text-emerald-700 font-semibold">
                                      POS
                                    </span>
                                  )}
                                  {(item.menuIds.length > 0 ? item.menuIds : (item.menuId ? [item.menuId] : [])).map((mid) => (
                                    <span
                                      key={mid}
                                      className="text-[11px] px-2 py-0.5 rounded-md bg-purple-50 text-purple-600 font-semibold truncate max-w-[72px]"
                                      title={menuEntityMap.get(mid) ?? ""}
                                    >
                                      {menuEntityMap.get(mid) ?? "Menu"}
                                    </span>
                                  ))}
                                  {item.channels.online && (
                                    <span className="text-[11px] px-2 py-0.5 rounded-md bg-cyan-50 text-cyan-600 font-semibold">
                                      Online
                                    </span>
                                  )}
                                  {(() => {
                                    const sIds = item.viewCategoryScheduleIds.length > 0
                                      ? item.viewCategoryScheduleIds
                                      : item.scheduleIds.length > 0
                                      ? item.scheduleIds
                                      : item.categoryScheduleIds;
                                    const shownMenuIds = item.menuIds.length > 0 ? item.menuIds : (item.menuId ? [item.menuId] : []);
                                    const coveredScheduleIds = new Set(
                                      shownMenuIds.flatMap((mid) => menuEntities.find((m) => m.id === mid)?.scheduleIds ?? [])
                                    );
                                    return sIds.filter((sid) => !coveredScheduleIds.has(sid)).map((sid) => (
                                      <span key={sid} className="text-[11px] px-2 py-0.5 rounded-md bg-blue-50 text-blue-600 font-semibold truncate max-w-[72px]" title={scheduleMap.get(sid) ?? sid}>
                                        {scheduleMap.get(sid) ?? "Sched"}
                                      </span>
                                    ));
                                  })()}
                                  {item.modifierGroupIds.length > 0 && (
                                    <span
                                      className="w-2.5 h-2.5 rounded-full bg-purple-400 shrink-0"
                                      title={item.modifierGroupIds.map((id) => modGroupMap.get(id) ?? id).join(", ")}
                                    />
                                  )}
                                  {item.taxIds.length > 0 && (
                                    <span
                                      className="w-2.5 h-2.5 rounded-full bg-amber-400 shrink-0"
                                      title={item.taxIds.map((id) => taxMap.get(id) ?? id).join(", ")}
                                    />
                                  )}
                                </div>
                                <span className="w-20 text-right text-lg font-bold text-slate-800 shrink-0 tabular-nums">
                                  {Object.keys(item.prices).length > 1 ? (
                                    <span title={Object.entries(item.prices).map(([k, v]) => `${menuEntityMap.get(k) ?? k}: $${v.toFixed(2)}`).join(", ")}>
                                      ${Math.min(...Object.values(item.prices)).toFixed(2)}+
                                    </span>
                                  ) : (
                                    <>${item.price.toFixed(2)}</>
                                  )}
                                </span>
                                {!selectMode && !transferMode && (
                                  <div className="w-16 flex items-center justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity shrink-0">
                                    <button
                                      onClick={(e) => { e.stopPropagation(); openEdit(item); }}
                                      className="p-2 rounded-lg text-slate-400 hover:text-blue-600 hover:bg-blue-50 transition-colors"
                                      title="Edit"
                                    >
                                      <Pencil size={15} />
                                    </button>
                                    <button
                                      onClick={(e) => { e.stopPropagation(); setDeleteTarget(item); }}
                                      className="p-2 rounded-lg text-slate-400 hover:text-red-500 hover:bg-red-50 transition-colors"
                                      title="Delete"
                                    >
                                      <Trash2 size={15} />
                                    </button>
                                  </div>
                                )}
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    </section>
                  ))}
                </div>
              ) : (
                /* ── Card view ── */
                <div className="space-y-6">
                  {itemSections.map(({ key: sectionKey, title: sectionTitle, items: groupItems }) => (
                    <section key={sectionKey} className="animate-in fade-in duration-200">
                      <div className="flex items-center gap-3 mb-3 px-1">
                        <h2 className="text-sm font-bold text-slate-500 uppercase tracking-wider">{sectionTitle}</h2>
                        <span className="text-xs text-slate-400 bg-slate-100 px-2 py-0.5 rounded-full font-medium">{groupItems.length}</span>
                      </div>
                      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4 gap-4">
                        {groupItems.map((item) => {
                          const inStock = !stockCountingEnabled || item.stock > 0;
                          const isSelected = selectMode ? selectedItems.has(item.id) : transferMode ? transferItems.has(item.id) : false;
                          return (
                            <div
                              key={item.id}
                              onClick={selectMode ? () => toggleSelectItem(item.id) : transferMode ? () => toggleTransferItem(item.id) : () => setSelectedItem((prev) => (prev?.id === item.id ? null : item))}
                              className={`group bg-white rounded-xl p-5 border hover:shadow-md transition-all duration-200 cursor-pointer ${
                                selectedItem?.id === item.id && !transferMode
                                  ? "border-blue-400 bg-blue-50/60 ring-2 ring-blue-400/40 shadow-md"
                                  : isSelected
                                  ? transferMode ? "border-indigo-400 bg-indigo-50/40 ring-1 ring-indigo-400/30" : "border-blue-400 bg-blue-50/40 ring-1 ring-blue-400/30"
                                  : inStock
                                  ? "border-slate-200 hover:border-slate-300"
                                  : "border-red-200 bg-red-50/30"
                              }`}
                            >
                              <div className="flex items-start justify-between mb-3">
                                <div className="flex items-center gap-2 min-w-0">
                                  {(selectMode || transferMode) && (
                                    <input
                                      type="checkbox"
                                      checked={isSelected}
                                      readOnly
                                      className={`w-4 h-4 rounded border-slate-300 focus:ring-blue-500 pointer-events-none shrink-0 ${transferMode ? "text-indigo-600" : "text-blue-600"}`}
                                    />
                                  )}
                                  <h3 className={`text-base font-bold truncate ${inStock ? "text-slate-800" : "text-slate-400"}`}>
                                    {item.name}
                                  </h3>
                                </div>
                                {!selectMode && !transferMode && (
                                  <div className="flex items-center gap-0.5 shrink-0">
                                    <button
                                      onClick={(e) => { e.stopPropagation(); openEdit(item); }}
                                      className="p-1.5 rounded-lg text-slate-300 opacity-0 group-hover:opacity-100 hover:bg-blue-50 hover:text-blue-500 transition-all"
                                      title="Edit"
                                    >
                                      <Pencil size={14} />
                                    </button>
                                    <button
                                      onClick={(e) => { e.stopPropagation(); setDeleteTarget(item); }}
                                      className="p-1.5 rounded-lg text-slate-300 opacity-0 group-hover:opacity-100 hover:bg-red-50 hover:text-red-500 transition-all"
                                      title="Delete"
                                    >
                                      <Trash2 size={14} />
                                    </button>
                                  </div>
                                )}
                              </div>

                              <div className="flex items-center justify-between mb-3">
                                <div>
                                  {Object.keys(item.prices).length > 1 ? (
                                    <div className="flex flex-col gap-0.5">
                                      {Object.entries(item.prices).map(([k, v]) => (
                                        <span key={k} className="text-base font-bold text-slate-700 tabular-nums">
                                          <span className="text-xs text-slate-400 font-medium">{menuEntityMap.get(k) ?? k}: </span>
                                          ${v.toFixed(2)}
                                        </span>
                                      ))}
                                    </div>
                                  ) : (
                                    <p className="text-xl font-bold text-slate-800 tabular-nums">${item.price.toFixed(2)}</p>
                                  )}
                                </div>
                                {stockCountingEnabled && (
                                  <div className="flex items-center gap-1.5 text-sm">
                                    <Package size={14} className={item.stock > 0 ? "text-emerald-500" : "text-red-400"} />
                                    <span className={`font-semibold ${item.stock > 0 ? "text-emerald-600" : "text-red-500"}`}>
                                      {item.stock > 0 ? item.stock : "OUT"}
                                    </span>
                                  </div>
                                )}
                              </div>

                              <div className="flex items-center gap-1.5 flex-wrap">
                                {(item.menuIds.length > 0 ? item.menuIds : (item.menuId ? [item.menuId] : [])).map((mid) => (
                                  <span key={mid} className="text-[11px] px-2 py-1 rounded-full bg-purple-50 text-purple-600 font-semibold">
                                    {menuEntityMap.get(mid) ?? "Menu"}
                                  </span>
                                ))}
                                {item.channels.online && (
                                  <span className="text-[11px] px-2 py-1 rounded-full bg-cyan-50 text-cyan-600 font-semibold">
                                    Online
                                  </span>
                                )}
                                {!item.viewCategoryScheduled && (
                                  <span className="text-[11px] px-2 py-1 rounded-full bg-emerald-50 text-emerald-600 font-semibold">
                                    POS
                                  </span>
                                )}
                                {(() => {
                                  const sIds = item.viewCategoryScheduleIds.length > 0
                                    ? item.viewCategoryScheduleIds
                                    : item.scheduleIds.length > 0
                                    ? item.scheduleIds
                                    : item.categoryScheduleIds;
                                  const shownMenuIds = item.menuIds.length > 0 ? item.menuIds : (item.menuId ? [item.menuId] : []);
                                  const coveredScheduleIds = new Set(
                                    shownMenuIds.flatMap((mid) => menuEntities.find((m) => m.id === mid)?.scheduleIds ?? [])
                                  );
                                  return sIds.filter((sid) => !coveredScheduleIds.has(sid)).map((sid) => (
                                    <span key={sid} className="text-[11px] px-2 py-1 rounded-full bg-blue-50 text-blue-600 font-semibold" title={scheduleMap.get(sid) ?? sid}>
                                      {scheduleMap.get(sid) ?? "Scheduled"}
                                    </span>
                                  ));
                                })()}
                                {item.effectiveOrderTypes.map((t) => (
                                  <span key={t} className="text-[11px] px-2 py-1 rounded-full bg-blue-50 text-blue-700 font-semibold">
                                    {ORDER_TYPE_LABELS[t] ?? t}
                                  </span>
                                ))}
                                {item.modifierGroupIds.length > 0 && item.modifierGroupIds.map((id) => {
                                  const grp = modGroupFullMap.get(id);
                                  const hasNested = grp?.options.some((o) => o.triggersModifierGroupIds.length > 0);
                                  return (
                                    <span key={id} className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${hasNested ? "bg-blue-50 text-blue-700 ring-1 ring-blue-200" : "bg-purple-50 text-purple-600"}`}>
                                      {modGroupMap.get(id) ?? id}{hasNested ? " ⤵" : ""}
                                    </span>
                                  );
                                })}
                                {item.taxIds.length > 0 && item.taxIds.map((id) => (
                                  <span key={id} className="text-[10px] px-2 py-0.5 rounded-full bg-amber-50 text-amber-600 font-medium">
                                    {taxMap.get(id) ?? id}
                                  </span>
                                ))}
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    </section>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      <MenuUploadModal
        open={uploadOpen}
        initialTab="excel"
        onClose={() => setUploadOpen(false)}
        onImportComplete={() => {}}
      />

      {/* ── Add category modal ── */}
      {addCategoryOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !addCategorySaving && setAddCategoryOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  Add Category
                </h3>
                <button
                  onClick={() => setAddCategoryOpen(false)}
                  disabled={addCategorySaving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <p className="text-sm text-slate-500">
                Choose which order types can use this category. Check all for every order type, or uncheck to limit where it appears — same as the app.
              </p>

              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Category Name
                </label>
                <input
                  type="text"
                  value={addCategoryName}
                  onChange={(e) => {
                    setAddCategoryName(e.target.value);
                    setAddCategoryError("");
                  }}
                  placeholder="e.g. Desserts, Drinks"
                  className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                />
                {addCategoryError && (
                  <p className="mt-1.5 text-sm text-red-600">{addCategoryError}</p>
                )}
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-700 mb-2">
                  Show in order types
                </label>
                <p className="text-xs text-slate-500 mb-2">
                  All checked = shows everywhere. Uncheck to limit to Dine In, To Go, and/or Bar only.
                </p>
                <div className="flex flex-col gap-2 pl-1">
                  {ALL_ORDER_TYPES.map((t) => (
                    <label
                      key={t}
                      className="flex items-center gap-2 cursor-pointer"
                    >
                      <input
                        type="checkbox"
                        checked={addCategoryOrderTypes[t] ?? false}
                        onChange={(e) =>
                          setAddCategoryOrderTypes((prev) => ({
                            ...prev,
                            [t]: e.target.checked,
                          }))
                        }
                        className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                      />
                      <span className="text-sm text-slate-700">
                        {ORDER_TYPE_LABELS[t]}
                      </span>
                    </label>
                  ))}
                </div>
              </div>

              {allSchedules.length > 0 && (
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-2">
                    Schedule (optional)
                  </label>
                  <p className="text-xs text-slate-500 mb-2">
                    If a schedule is assigned, this category and its items will only appear during that time window. Leave unchecked for always available.
                  </p>
                  <div className="flex flex-col gap-2 pl-1">
                    {allSchedules.map((s) => (
                      <label
                        key={s.id}
                        className="flex items-center gap-2 cursor-pointer"
                      >
                        <input
                          type="checkbox"
                          checked={addCategorySchedules[s.id] ?? false}
                          onChange={(e) =>
                            setAddCategorySchedules((prev) => ({
                              ...prev,
                              [s.id]: e.target.checked,
                            }))
                          }
                          className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                        />
                        <span className="text-sm text-slate-700">{s.name}</span>
                      </label>
                    ))}
                  </div>
                </div>
              )}

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setAddCategoryOpen(false)}
                  disabled={addCategorySaving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleAddCategory}
                  disabled={
                    addCategorySaving ||
                    !addCategoryName.trim() ||
                    ALL_ORDER_TYPES.every((t) => !addCategoryOrderTypes[t])
                  }
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {addCategorySaving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Adding…
                    </>
                  ) : (
                    "Add Category"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Edit category modal ── */}
      {editCategoryTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !editCategorySaving && setEditCategoryTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  Edit Category
                </h3>
                <button
                  onClick={() => setEditCategoryTarget(null)}
                  disabled={editCategorySaving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <p className="text-sm text-slate-600 font-medium">
                {editCategoryTarget.name}
              </p>

              <p className="text-sm text-slate-500">
                Choose which order types can use this category. All checked = everywhere. Uncheck to limit — syncs to the app.
              </p>

              <div>
                <label className="block text-sm font-medium text-slate-700 mb-2">
                  Show in order types
                </label>
                <div className="flex flex-col gap-2 pl-1">
                  {ALL_ORDER_TYPES.map((t) => (
                    <label
                      key={t}
                      className="flex items-center gap-2 cursor-pointer"
                    >
                      <input
                        type="checkbox"
                        checked={editCategoryOrderTypes[t] ?? false}
                        onChange={(e) =>
                          setEditCategoryOrderTypes((prev) => ({
                            ...prev,
                            [t]: e.target.checked,
                          }))
                        }
                        className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                      />
                      <span className="text-sm text-slate-700">
                        {ORDER_TYPE_LABELS[t]}
                      </span>
                    </label>
                  ))}
                </div>
              </div>

              {allSchedules.length > 0 && (
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-2">
                    Schedule (optional)
                  </label>
                  <p className="text-xs text-slate-500 mb-2">
                    If a schedule is assigned, this category and its items will only appear during that time window. Leave unchecked for always available.
                  </p>
                  <div className="flex flex-col gap-2 pl-1">
                    {allSchedules.map((s) => (
                      <label
                        key={s.id}
                        className="flex items-center gap-2 cursor-pointer"
                      >
                        <input
                          type="checkbox"
                          checked={editCategorySchedules[s.id] ?? false}
                          onChange={(e) =>
                            setEditCategorySchedules((prev) => ({
                              ...prev,
                              [s.id]: e.target.checked,
                            }))
                          }
                          className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                        />
                        <span className="text-sm text-slate-700">{s.name}</span>
                      </label>
                    ))}
                  </div>
                </div>
              )}

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setEditCategoryTarget(null)}
                  disabled={editCategorySaving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSaveCategory}
                  disabled={
                    editCategorySaving ||
                    ALL_ORDER_TYPES.every((t) => !editCategoryOrderTypes[t])
                  }
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {editCategorySaving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Saving…
                    </>
                  ) : (
                    "Save"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Delete category confirmation modal ── */}
      {deleteCategoryTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !deletingCategory && setDeleteCategoryTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex flex-col items-center gap-3">
                <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center">
                  <AlertTriangle size={24} className="text-red-500" />
                </div>
                <h3 className="text-lg font-semibold text-slate-800">
                  Delete Category
                </h3>
              </div>

              {(() => {
                const itemCount = items.filter(
                  (i) =>
                    i.categoryId === deleteCategoryTarget.id ||
                    (Array.isArray(i.categoryIds) && i.categoryIds.includes(deleteCategoryTarget.id))
                ).length;
                return (
                  <p className="text-sm text-slate-500 text-center">
                    {itemCount > 0 ? (
                      <>
                        <strong className="text-slate-700">
                          {deleteCategoryTarget.name}
                        </strong>{" "}
                        contains <strong>{itemCount} item{itemCount !== 1 ? "s" : ""}</strong>.
                        All items and subcategories will be permanently deleted. Are you sure?
                      </>
                    ) : (
                      <>
                        Delete <strong className="text-slate-700">{deleteCategoryTarget.name}</strong>?
                        This cannot be undone.
                      </>
                    )}
                  </p>
                );
              })()}

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setDeleteCategoryTarget(null)}
                  disabled={deletingCategory}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleDeleteCategory}
                  disabled={deletingCategory}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {deletingCategory ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Deleting…
                    </>
                  ) : (
                    "Delete"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Bulk delete categories (selection mode) ── */}
      {bulkCategoriesDeleteConfirm &&
        (() => {
          const nCat = filterCategoryIds.length;
          const touchedItems = items.filter((i) =>
            filterCategoryIds.some(
              (cid) =>
                i.categoryId === cid || (Array.isArray(i.categoryIds) && i.categoryIds.includes(cid))
            )
          );
          const itemTouchCount = touchedItems.length;
          const names = filterCategoryIds
            .map((id) => categories.find((c) => c.id === id)?.name ?? id.slice(0, 6))
            .join(", ");
          return (
            <div className="fixed inset-0 z-50 flex items-center justify-center">
              <div
                className="absolute inset-0 bg-black/40 backdrop-blur-sm"
                onClick={() => !bulkCategoriesDeleting && setBulkCategoriesDeleteConfirm(false)}
              />
              <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
                <div className="px-6 py-5 space-y-4">
                  <div className="flex flex-col items-center gap-3">
                    <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center">
                      <AlertTriangle size={24} className="text-red-500" />
                    </div>
                    <h3 className="text-lg font-semibold text-slate-800 text-center">
                      Delete {nCat} {nCat === 1 ? "category" : "categories"}?
                    </h3>
                  </div>
                  <p className="text-sm text-slate-500 text-center leading-relaxed">
                    This permanently removes <strong className="text-slate-800">{names}</strong>, every
                    subcategory under them, and deletes or updates menu items tied to them (about{" "}
                    <strong className="text-slate-800">{itemTouchCount}</strong> row
                    {itemTouchCount !== 1 ? "s" : ""} in your current list). Items that also belong to
                    another category are updated, not removed. This cannot be undone.
                  </p>
                  <div className="flex gap-3 pt-1">
                    <button
                      type="button"
                      onClick={() => setBulkCategoriesDeleteConfirm(false)}
                      disabled={bulkCategoriesDeleting}
                      className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      onClick={handleBulkDeleteCategories}
                      disabled={bulkCategoriesDeleting}
                      className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                    >
                      {bulkCategoriesDeleting ? (
                        <>
                          <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                          Deleting…
                        </>
                      ) : (
                        "Delete all"
                      )}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          );
        })()}

      {/* ── Bulk delete confirmation modal ── */}
      {bulkDeleteConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !bulkDeleting && setBulkDeleteConfirm(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex flex-col items-center gap-3">
                <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center">
                  <AlertTriangle size={24} className="text-red-500" />
                </div>
                <h3 className="text-lg font-semibold text-slate-800">
                  Delete {selectedItems.size} Item{selectedItems.size !== 1 ? "s" : ""}
                </h3>
              </div>
              <p className="text-sm text-slate-500 text-center">
                Are you sure you want to delete{" "}
                <strong className="text-slate-700">
                  {selectedItems.size} item{selectedItems.size !== 1 ? "s" : ""}
                </strong>
                ? This will permanently remove them from Firestore and the POS app.
              </p>
              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setBulkDeleteConfirm(false)}
                  disabled={bulkDeleting}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleBulkDelete}
                  disabled={bulkDeleting}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {bulkDeleting ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Deleting…
                    </>
                  ) : (
                    "Delete All"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Transfer items / categories modal ── */}
      {transferModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !transferring && setTransferModalOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-full bg-indigo-50 flex items-center justify-center">
                    <ArrowRightLeft size={20} className="text-indigo-600" />
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold text-slate-800">
                      {(() => {
                        const bits: string[] = [];
                        if (transferCategories.size > 0) {
                          bits.push(
                            `${transferCategories.size} categor${transferCategories.size !== 1 ? "ies" : "y"}`
                          );
                        }
                        if (transferSubcategories.size > 0) {
                          bits.push(
                            `${transferSubcategories.size} subcategor${transferSubcategories.size !== 1 ? "ies" : "y"}`
                          );
                        }
                        if (transferItems.size > 0) {
                          bits.push(`${transferItems.size} item${transferItems.size !== 1 ? "s" : ""}`);
                        }
                        return bits.length > 0 ? `Transfer ${bits.join(", ")}` : "Transfer";
                      })()}
                    </h3>
                    <p className="text-xs text-slate-400">
                      {transferSubcategories.size > 0
                        ? "Promoted subcategories become top-level categories. Pick POS or a scheduled menu so the POS app shows them correctly."
                        : transferCategories.size > 0
                          ? "Each selected category becomes a subcategory under the target, with all items inside."
                          : "Move to another category or subcategory"}
                    </p>
                  </div>
                </div>
                <button
                  onClick={() => setTransferModalOpen(false)}
                  disabled={transferring}
                  className="p-1.5 rounded-lg hover:bg-slate-100 transition-colors"
                >
                  <X size={18} className="text-slate-400" />
                </button>
              </div>

              {transferSubcategories.size > 0 && (
                <div className="space-y-1">
                  <label className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                    Menu for promoted subcategories
                  </label>
                  <select
                    value={transferScheduleMenuId}
                    onChange={(e) => {
                      setTransferScheduleMenuId(e.target.value);
                      setTransferError("");
                    }}
                    className="w-full px-3 py-2.5 border border-slate-200 rounded-xl text-sm text-slate-700 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none"
                  >
                    <option value="POS">POS (not tied to a scheduled menu)</option>
                    {menuEntities.map((m) => (
                      <option key={m.id} value={m.id}>
                        {m.name}
                        {m.scheduleIds.length === 0 ? " — no schedules" : ""}
                      </option>
                    ))}
                  </select>
                  <p className="text-xs text-slate-400">
                    New categories and items get <code className="text-slate-500">scheduleIds</code>,{" "}
                    <code className="text-slate-500">menuIds</code>, and{" "}
                    <code className="text-slate-500">isScheduled</code> to match this menu — same fields the Android POS reads from Firestore.
                  </p>
                </div>
              )}

              {(transferCategories.size > 0 || transferItems.size > 0) && (
                <div className="space-y-1">
                  <label className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                    Target Category
                  </label>
                  <select
                    value={transferCategoryId}
                    onChange={(e) => {
                      setTransferCategoryId(e.target.value);
                      setTransferSubcategoryId("");
                      setTransferError("");
                    }}
                    className="w-full px-3 py-2.5 border border-slate-200 rounded-xl text-sm text-slate-700 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none"
                  >
                    <option value="">Select a category…</option>
                    {categories
                      .filter((cat) => !transferCategories.has(cat.id))
                      .map((cat) => (
                        <option key={cat.id} value={cat.id}>
                          {cat.name}
                        </option>
                      ))}
                  </select>
                </div>
              )}
              {transferError && (
                <p className="text-xs text-red-600">{transferError}</p>
              )}

              {transferItems.size > 0 &&
                transferCategoryId &&
                allSubcategories.filter((s) => s.categoryId === transferCategoryId).length > 0 && (
                <div className="space-y-1">
                  <label className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                    Target Subcategory <span className="text-slate-400 font-normal">(optional)</span>
                  </label>
                  <select
                    value={transferSubcategoryId}
                    onChange={(e) => setTransferSubcategoryId(e.target.value)}
                    className="w-full px-3 py-2.5 border border-slate-200 rounded-xl text-sm text-slate-700 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none"
                  >
                    <option value="">None (category only)</option>
                    {allSubcategories
                      .filter((s) => s.categoryId === transferCategoryId)
                      .map((sub) => (
                        <option key={sub.id} value={sub.id}>{sub.name}</option>
                      ))}
                  </select>
                </div>
              )}

              <div className="bg-slate-50 rounded-xl p-3 space-y-2">
                {transferSubcategories.size > 0 && (
                  <p className="text-xs text-slate-500">
                    <strong className="text-slate-600">
                      {transferSubcategories.size} subcategor{transferSubcategories.size !== 1 ? "ies" : "y"}
                    </strong>{" "}
                    will become <strong className="text-slate-600">top-level categories</strong> (items keep their data;{" "}
                    <code className="text-slate-500">subcategoryId</code> is cleared). Order-type rules follow the former parent category.
                  </p>
                )}
                {transferCategories.size > 0 && (
                  <p className="text-xs text-slate-500">
                    <strong className="text-slate-600">
                      {transferCategories.size} top-level categor{transferCategories.size !== 1 ? "ies" : "y"}
                    </strong>{" "}
                    will become subcategories under the target (old sub-groups under those categories are removed; all items stay in the new subcategory named like the former category).
                    Items inherit the target&apos;s scheduled menus when the target uses schedules (e.g. POS-only → Brunch).
                  </p>
                )}
                {transferItems.size > 0 && (
                  <p className="text-xs text-slate-500">
                    <strong className="text-slate-600">
                      {transferItems.size} item{transferItems.size !== 1 ? "s" : ""}
                    </strong>{" "}
                    will be moved
                    {transferCategories.size > 0 || transferSubcategories.size > 0
                      ? " (after category / subcategory steps above)"
                      : ""}
                    .
                    Menu and schedule settings will match the target category.
                  </p>
                )}
                <p className="text-xs text-slate-400">
                  Updates Firestore; the Android POS loads the same collections in real time.
                </p>
              </div>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setTransferModalOpen(false)}
                  disabled={transferring}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleTransfer}
                  disabled={
                    transferring ||
                    ((transferCategories.size > 0 || transferItems.size > 0) && !transferCategoryId)
                  }
                  className="flex-1 px-4 py-2.5 rounded-xl bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {transferring ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Transferring…
                    </>
                  ) : (
                    <>
                      <ArrowRightLeft size={14} />
                      Transfer
                    </>
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Delete item confirmation modal ── */}
      {deleteTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !deleting && setDeleteTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex flex-col items-center gap-3">
                <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center">
                  <AlertTriangle size={24} className="text-red-500" />
                </div>
                <h3 className="text-lg font-semibold text-slate-800">
                  Delete Item
                </h3>
              </div>

              <p className="text-sm text-slate-500 text-center">
                Are you sure you want to delete{" "}
                <strong className="text-slate-700">
                  {deleteTarget.name}
                </strong>
                ? This will permanently remove it from Firestore and the POS
                app.
              </p>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setDeleteTarget(null)}
                  disabled={deleting}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleDelete}
                  disabled={deleting}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {deleting ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Deleting…
                    </>
                  ) : (
                    "Delete"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Add item modal ── */}
      {addOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !addSaving && setAddOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden max-h-[90vh] overflow-y-auto">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  Add New Item
                </h3>
                <button
                  onClick={() => setAddOpen(false)}
                  disabled={addSaving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Name
                  </label>
                  <input
                    type="text"
                    value={addName}
                    onChange={(e) => setAddName(e.target.value)}
                    placeholder="e.g. Margherita Pizza"
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-2">
                    Menus
                  </label>
                  <p className="text-[10px] text-slate-400 mb-2">
                    Choose where this item appears first. Categories below are limited to those for POS (always-on) and/or the timed menus you select.
                  </p>
                  <div className="flex flex-col gap-3 pl-1 max-h-48 overflow-y-auto">
                    <div className="space-y-1.5">
                      <label className="flex items-center gap-2 cursor-pointer">
                        <input
                          type="checkbox"
                          checked={addPosSelected}
                          onChange={(e) => {
                            const on = e.target.checked;
                            setAddPosSelected(on);
                            if (!on) setAddPosPrice("");
                          }}
                          className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                        />
                        <span className="text-sm font-medium text-slate-700">POS</span>
                      </label>
                      {addPosSelected && (
                        <div className="ml-6">
                          <label className="block text-xs text-slate-500 mb-1">POS Price ($)</label>
                          <input
                            type="number"
                            min="0"
                            step="0.01"
                            value={addPosPrice}
                            onChange={(e) => setAddPosPrice(e.target.value)}
                            placeholder="0.00"
                            className="w-full px-3 py-2 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                          />
                        </div>
                      )}
                    </div>
                    {menuEntities.filter((m) => m.isActive).map((m) => (
                      <div key={m.id} className="space-y-1.5">
                        <label className="flex items-center gap-2 cursor-pointer">
                          <input
                            type="checkbox"
                            checked={addMenuIds[m.id] ?? false}
                            onChange={(e) => {
                              setAddMenuIds((prev) => ({ ...prev, [m.id]: e.target.checked }));
                              if (!e.target.checked) {
                                setAddMenuPrices((prev) => {
                                  const next = { ...prev };
                                  delete next[m.id];
                                  return next;
                                });
                              }
                            }}
                            className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="text-sm font-medium text-slate-700">{m.name}</span>
                        </label>
                        {(addMenuIds[m.id] ?? false) && (
                          <div className="ml-6">
                            <label className="block text-xs text-slate-500 mb-1">{m.name} price ($)</label>
                            <input
                              type="number"
                              min="0"
                              step="0.01"
                              value={addMenuPrices[m.id] ?? ""}
                              onChange={(e) =>
                                setAddMenuPrices((prev) => ({ ...prev, [m.id]: e.target.value }))
                              }
                              placeholder="0.00"
                              className="w-full px-3 py-2 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                            />
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>

                <div className="space-y-3">
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-1.5">
                      Categories by menu
                    </label>
                    <p className="text-[10px] text-slate-400 mb-1.5">
                      Pick a category (and subcategory if needed) for each menu you selected above. POS only shows always-on categories; each timed menu only shows its own scheduled categories.
                    </p>
                  </div>
                  {addMenuTargetsOrdered.length === 0 ? (
                    <p className="text-xs text-slate-400 italic">Select at least one menu above to choose categories.</p>
                  ) : (
                    addMenuTargetsOrdered.map((targetKey) => {
                      const targetLabel =
                        targetKey === ADD_ITEM_POS_TARGET
                          ? "POS"
                          : menuEntityMap.get(targetKey) ?? "Menu";
                      const catOptions = categoriesForMenuTarget(targetKey, categories, menuEntities);
                      const selCat = addPlacementCategory[targetKey] ?? "";
                      const subs = selCat
                        ? allSubcategories.filter((s) => s.categoryId === selCat)
                        : [];
                      return (
                        <div
                          key={targetKey}
                          className="rounded-xl border border-slate-100 bg-slate-50/60 p-3 space-y-2.5"
                        >
                          <p className="text-xs font-semibold text-slate-700">{targetLabel}</p>
                          <div>
                            <label className="block text-[11px] font-medium text-slate-500 mb-1">
                              Category
                            </label>
                            <select
                              value={selCat}
                              disabled={catOptions.length === 0}
                              onChange={(e) => {
                                const catId = e.target.value;
                                setAddPlacementCategory((prev) => ({ ...prev, [targetKey]: catId }));
                                setAddPlacementSubcategory((prev) => ({ ...prev, [targetKey]: "" }));
                                const cat = categories.find((c) => c.id === catId);
                                if (cat && cat.scheduleIds.length > 0 && targetKey !== ADD_ITEM_POS_TARGET) {
                                  setAddMenuIds((prev) => ({ ...prev, [targetKey]: true }));
                                }
                                if (cat && cat.scheduleIds.length === 0 && targetKey === ADD_ITEM_POS_TARGET) {
                                  setAddPosSelected(true);
                                }
                              }}
                              className="w-full px-3 py-2 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white disabled:bg-slate-100 disabled:text-slate-400"
                            >
                              <option value="">
                                {catOptions.length === 0 ? "No categories for this menu" : "Select category"}
                              </option>
                              {catOptions.map((cat) => (
                                <option key={cat.id} value={cat.id}>
                                  {cat.name}
                                  {cat.scheduleIds.length > 0 ? " (scheduled)" : ""}
                                </option>
                              ))}
                            </select>
                          </div>
                          {selCat && subs.length > 0 && (
                            <div>
                              <label className="block text-[11px] font-medium text-slate-500 mb-1">
                                Subcategory
                              </label>
                              <select
                                value={addPlacementSubcategory[targetKey] ?? ""}
                                onChange={(e) =>
                                  setAddPlacementSubcategory((prev) => ({
                                    ...prev,
                                    [targetKey]: e.target.value,
                                  }))
                                }
                                className="w-full px-3 py-2 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white"
                              >
                                <option value="">(None)</option>
                                {subs.map((s) => (
                                  <option key={s.id} value={s.id}>
                                    {s.name}
                                  </option>
                                ))}
                              </select>
                            </div>
                          )}
                        </div>
                      );
                    })
                  )}
                </div>

                {addCategoryHasSchedule && addScheduleBannerNames.length > 0 && (
                  <div className="flex items-center gap-2 px-3 py-2.5 rounded-xl bg-blue-50 border border-blue-100">
                    <Clock size={14} className="text-blue-500 shrink-0" />
                    <p className="text-xs text-blue-600">
                      At least one placement uses a scheduled category ({addScheduleBannerNames}). Items follow those schedules automatically. Use the menu prices you entered above for each timed menu.
                    </p>
                  </div>
                )}

                {stockCountingEnabled && (
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-1.5">
                      Stock
                    </label>
                    <input
                      type="number"
                      min="0"
                      step="1"
                      value={addStock}
                      onChange={(e) => setAddStock(e.target.value)}
                      placeholder="0"
                      className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                    />
                  </div>
                )}

                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-2">
                    Order Types
                  </label>
                  <label className="flex items-center gap-2 mb-3 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={addUseCategoryTypes}
                      onChange={(e) => setAddUseCategoryTypes(e.target.checked)}
                      className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                    />
                    <span className="text-sm text-slate-600">
                      Use category availability
                    </span>
                  </label>
                  {!addUseCategoryTypes && (
                    <div className="flex flex-col gap-2 pl-1">
                      {ALL_ORDER_TYPES.map((t) => (
                        <label
                          key={t}
                          className="flex items-center gap-2 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={addOrderTypes[t] ?? false}
                            onChange={(e) =>
                              setAddOrderTypes((prev) => ({
                                ...prev,
                                [t]: e.target.checked,
                              }))
                            }
                            className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="text-sm text-slate-700">
                            {ORDER_TYPE_LABELS[t]}
                          </span>
                        </label>
                      ))}
                    </div>
                  )}
                </div>

                {/* ── Assign label (collapsible) ── */}
                <div className="rounded-xl border border-slate-200/80 overflow-hidden">
                  <button
                    type="button"
                    onClick={() => setAddLabelExpanded((v) => !v)}
                    className="flex w-full items-center justify-between gap-2 px-3 py-2.5 text-left bg-slate-50/90 hover:bg-slate-100/90 transition-colors"
                  >
                    <span className="flex items-center gap-2 min-w-0 text-sm font-medium text-slate-700">
                      <Printer size={15} className="text-slate-500 shrink-0" />
                      <span className="truncate">Assign label</span>
                      {addPrinterLabel.trim().length > 0 && (
                        <span className="text-xs font-semibold text-blue-600 tabular-nums shrink-0 truncate max-w-[120px]">
                          ({addPrinterLabel.trim()})
                        </span>
                      )}
                    </span>
                    <ChevronDown
                      size={18}
                      className={`text-slate-400 shrink-0 transition-transform duration-200 ${
                        addLabelExpanded ? "rotate-180" : ""
                      }`}
                    />
                  </button>
                  <div
                    className={`grid transition-[grid-template-rows] duration-200 ease-out ${
                      addLabelExpanded ? "grid-rows-[1fr]" : "grid-rows-[0fr]"
                    }`}
                  >
                    <div className="min-h-0 overflow-hidden">
                      <div className="px-3 py-3 space-y-1.5 border-t border-slate-100">
                        <p className="text-[10px] text-slate-400">
                          Labels match kitchen printers on the POS. (None) = do not send to kitchen printers.
                        </p>
                        <select
                          value={addPrinterLabel}
                          onChange={(e) => setAddPrinterLabel(e.target.value)}
                          className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white"
                        >
                          <option value="">(None)</option>
                          {addAssignableLabelOptions.map((lbl) => (
                              <option key={lbl} value={lbl}>
                                {lbl}
                              </option>
                            ))}
                        </select>
                      </div>
                    </div>
                  </div>
                </div>

                {/* ── Add KDS (collapsible) ── */}
                <div className="rounded-xl border border-slate-200/80 overflow-hidden">
                  <button
                    type="button"
                    onClick={() => setAddKdsExpanded((v) => !v)}
                    className="flex w-full items-center justify-between gap-2 px-3 py-2.5 text-left bg-slate-50/90 hover:bg-slate-100/90 transition-colors"
                  >
                    <span className="flex items-center gap-2 min-w-0 text-sm font-medium text-slate-700">
                      <Monitor size={15} className="text-slate-500 shrink-0" />
                      <span className="truncate">Add KDS</span>
                      {addKdsDeviceId.trim().length > 0 && (
                        <span className="text-xs font-semibold text-blue-600 tabular-nums shrink-0 truncate max-w-[140px]">
                          (
                          {kdsPickerDevices.find((d) => d.id === addKdsDeviceId)?.name ??
                            addKdsDeviceId}
                          )
                        </span>
                      )}
                    </span>
                    <ChevronDown
                      size={18}
                      className={`text-slate-400 shrink-0 transition-transform duration-200 ${
                        addKdsExpanded ? "rotate-180" : ""
                      }`}
                    />
                  </button>
                  <div
                    className={`grid transition-[grid-template-rows] duration-200 ease-out ${
                      addKdsExpanded ? "grid-rows-[1fr]" : "grid-rows-[0fr]"
                    }`}
                  >
                    <div className="min-h-0 overflow-hidden">
                      <div className="px-3 py-3 space-y-1.5 border-t border-slate-100">
                        <p className="text-[10px] text-slate-400">
                          Active KDS devices only. Adds this item to the device&apos;s explicit item list
                          (same as Settings → KDS → Assign items). (None) = no link from this screen.
                        </p>
                        <select
                          value={addKdsDeviceId}
                          onChange={(e) => setAddKdsDeviceId(e.target.value)}
                          className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white"
                        >
                          <option value="">(None)</option>
                          {activeKdsSelectOptions.map((d) => (
                            <option key={d.id} value={d.id}>
                              {d.name}
                            </option>
                          ))}
                        </select>
                      </div>
                    </div>
                  </div>
                </div>

                {/* ── Assign Modifiers (collapsible) ── */}
                {modifierGroups.length > 0 && (() => {
                  const nestedIds = new Set<string>();
                  for (const g of modifierGroups) {
                    for (const o of g.options) {
                      for (const tid of o.triggersModifierGroupIds) nestedIds.add(tid);
                    }
                  }
                  const topLevelGroups = modifierGroups.filter((g) => !nestedIds.has(g.id));
                  const nestedGroups = modifierGroups.filter((g) => nestedIds.has(g.id));
                  const selectedModCount = Object.values(addModifiers).filter(Boolean).length;
                  return (
                  <div className="rounded-xl border border-slate-200/80 overflow-hidden">
                    <button
                      type="button"
                      onClick={() => setAddModifiersExpanded((v) => !v)}
                      className="flex w-full items-center justify-between gap-2 px-3 py-2.5 text-left bg-slate-50/90 hover:bg-slate-100/90 transition-colors"
                    >
                      <span className="flex items-center gap-2 min-w-0 text-sm font-medium text-slate-700">
                        <SlidersHorizontal size={15} className="text-slate-500 shrink-0" />
                        <span className="truncate">Assign Modifiers</span>
                        {selectedModCount > 0 && (
                          <span className="text-xs font-semibold text-blue-600 tabular-nums shrink-0">
                            ({selectedModCount})
                          </span>
                        )}
                      </span>
                      <ChevronDown
                        size={18}
                        className={`text-slate-400 shrink-0 transition-transform duration-200 ${
                          addModifiersExpanded ? "rotate-180" : ""
                        }`}
                      />
                    </button>
                    <div
                      className={`grid transition-[grid-template-rows] duration-200 ease-out ${
                        addModifiersExpanded ? "grid-rows-[1fr]" : "grid-rows-[0fr]"
                      }`}
                    >
                      <div className="min-h-0 overflow-hidden">
                        <div className="flex flex-col gap-2 px-3 py-3 max-h-40 overflow-y-auto border-t border-slate-100">
                          {topLevelGroups.map((g) => (
                            <label
                              key={g.id}
                              className="flex items-center gap-2 cursor-pointer"
                            >
                              <input
                                type="checkbox"
                                checked={addModifiers[g.id] ?? false}
                                onChange={(e) =>
                                  setAddModifiers((prev) => ({
                                    ...prev,
                                    [g.id]: e.target.checked,
                                  }))
                                }
                                className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                              />
                              <span className="text-sm text-slate-700">{g.name}</span>
                              <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${
                                g.groupType === "REMOVE"
                                  ? "bg-red-50 text-red-500"
                                  : "bg-slate-100 text-slate-400"
                              }`}>
                                {g.groupType === "REMOVE" ? "Remove" : "Add-on"}
                              </span>
                            </label>
                          ))}
                          {nestedGroups.length > 0 && (
                            <div className="mt-1 pt-1 border-t border-slate-100">
                              <p className="text-[10px] text-slate-400 mb-1">Triggered by modifier options:</p>
                              {nestedGroups.map((g) => (
                                <label
                                  key={g.id}
                                  className="flex items-center gap-2 cursor-pointer opacity-60"
                                >
                                  <input
                                    type="checkbox"
                                    checked={addModifiers[g.id] ?? false}
                                    onChange={(e) =>
                                      setAddModifiers((prev) => ({
                                        ...prev,
                                        [g.id]: e.target.checked,
                                      }))
                                    }
                                    className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                                  />
                                  <span className="text-sm text-slate-700">{g.name}</span>
                                  <span className="text-[10px] px-1.5 py-0.5 rounded font-medium bg-blue-50 text-blue-500">
                                    Nested
                                  </span>
                                </label>
                              ))}
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  </div>
                  );
                })()}

                {/* ── Assign Taxes (collapsible) ── */}
                {taxes.length > 0 && (() => {
                  const enabledTaxes = taxes.filter((t) => t.enabled);
                  const selectedTaxCount = Object.entries(addTaxes).filter(([id, v]) => v && enabledTaxes.some((t) => t.id === id)).length;
                  return (
                  <div className="rounded-xl border border-slate-200/80 overflow-hidden">
                    <button
                      type="button"
                      onClick={() => setAddTaxesExpanded((v) => !v)}
                      className="flex w-full items-center justify-between gap-2 px-3 py-2.5 text-left bg-slate-50/90 hover:bg-slate-100/90 transition-colors"
                    >
                      <span className="flex items-center gap-2 min-w-0 text-sm font-medium text-slate-700">
                        <Receipt size={15} className="text-slate-500 shrink-0" />
                        <span className="truncate">Assign Taxes</span>
                        {selectedTaxCount > 0 && (
                          <span className="text-xs font-semibold text-blue-600 tabular-nums shrink-0">
                            ({selectedTaxCount})
                          </span>
                        )}
                      </span>
                      <ChevronDown
                        size={18}
                        className={`text-slate-400 shrink-0 transition-transform duration-200 ${
                          addTaxesExpanded ? "rotate-180" : ""
                        }`}
                      />
                    </button>
                    <div
                      className={`grid transition-[grid-template-rows] duration-200 ease-out ${
                        addTaxesExpanded ? "grid-rows-[1fr]" : "grid-rows-[0fr]"
                      }`}
                    >
                      <div className="min-h-0 overflow-hidden">
                        <div className="flex flex-col gap-2 px-3 py-3 max-h-40 overflow-y-auto border-t border-slate-100">
                          {enabledTaxes.map((t) => (
                            <label
                              key={t.id}
                              className="flex items-center gap-2 cursor-pointer"
                            >
                              <input
                                type="checkbox"
                                checked={addTaxes[t.id] ?? false}
                                onChange={(e) =>
                                  setAddTaxes((prev) => ({
                                    ...prev,
                                    [t.id]: e.target.checked,
                                  }))
                                }
                                className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                              />
                              <span className="text-sm text-slate-700">{t.name}</span>
                              <span className="text-[10px] px-1.5 py-0.5 rounded font-medium bg-amber-50 text-amber-600">
                                {t.amount}%
                              </span>
                            </label>
                          ))}
                        </div>
                      </div>
                    </div>
                  </div>
                  );
                })()}
              </div>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setAddOpen(false)}
                  disabled={addSaving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleAddItem}
                  disabled={
                    addSaving ||
                    !addName.trim() ||
                    (!addPosSelected && !Object.values(addMenuIds).some(Boolean)) ||
                    !addAllPlacementsFilled ||
                    (!addPosPrice && Object.values(addMenuPrices).every((v) => !v)) ||
                    (stockCountingEnabled && !addStock)
                  }
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {addSaving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Adding…
                    </>
                  ) : (
                    "Add Item"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Edit item modal ── */}
      {editTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !saving && setEditTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-lg mx-4 overflow-hidden max-h-[90vh] overflow-y-auto">
            <div className="px-6 py-5 space-y-5">
              <div>
                <h3 className="text-lg font-semibold text-slate-800">
                  Edit Item
                </h3>
                <p className="text-sm text-slate-500 mt-0.5">
                  {editTarget.name}
                </p>
              </div>

              {user && (
                <ItemImageSection
                  imageUrl={editImageUrl}
                  onImageUrlChange={setEditImageUrl}
                  onPersistImageUrl={persistEditItemImageUrl}
                  itemId={editTarget.id}
                  itemName={editTarget.name}
                  getIdToken={getMenuItemIdToken}
                  disabled={saving}
                />
              )}

              <div className="space-y-4">
                {editCategoryHasSchedule ? (
                  <>
                    <div className="flex items-center gap-2 px-3 py-2.5 rounded-xl bg-blue-50 border border-blue-100">
                      <Clock size={14} className="text-blue-500 shrink-0" />
                      <p className="text-xs text-blue-600">
                        This category is scheduled ({editSelectedCategory!.scheduleIds.map((id) => scheduleMap.get(id) ?? id).join(", ")}). Items follow the category&apos;s schedule automatically.
                      </p>
                    </div>
                    {editScheduledMenus.length > 0 && (
                      <div className="flex flex-col gap-3">
                        {editScheduledMenus.map((m) => (
                          <div key={m.id}>
                            <label className="block text-sm font-medium text-slate-700 mb-1.5">
                              {m.name} Price ($)
                            </label>
                            <input
                              type="number"
                              min="0"
                              step="0.01"
                              value={editMenuPrices[m.id] ?? ""}
                              onChange={(e) =>
                                setEditMenuPrices((prev) => ({ ...prev, [m.id]: e.target.value }))
                              }
                              className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                            />
                          </div>
                        ))}
                      </div>
                    )}
                  </>
                ) : menuEntities.length > 0 ? (
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-2">
                      Menus
                    </label>
                    <p className="text-[10px] text-slate-400 mb-2">
                      Select which menu this item belongs to, then set the price for each menu.
                    </p>
                    <div className="flex flex-col gap-3 pl-1 max-h-48 overflow-y-auto">
                      {menuEntities.filter((m) => m.isActive).map((m) => (
                        <div key={m.id} className="space-y-1.5">
                          <label className="flex items-center gap-2 cursor-pointer">
                            <input
                              type="checkbox"
                              checked={editMenuIds[m.id] ?? false}
                              onChange={(e) => {
                                setEditMenuIds((prev) => ({ ...prev, [m.id]: e.target.checked }));
                                if (!e.target.checked) {
                                  setEditMenuPrices((prev) => {
                                    const next = { ...prev };
                                    delete next[m.id];
                                    return next;
                                  });
                                }
                              }}
                              className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                            />
                            <span className="text-sm font-medium text-slate-700">{m.name}</span>
                          </label>
                          {(editMenuIds[m.id] ?? false) && (
                            <div className="ml-6">
                              <input
                                type="number"
                                min="0"
                                step="0.01"
                                value={editMenuPrices[m.id] ?? ""}
                                onChange={(e) =>
                                  setEditMenuPrices((prev) => ({ ...prev, [m.id]: e.target.value }))
                                }
                                placeholder={`${m.name} price`}
                                className="w-full px-3 py-2 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                              />
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  </div>
                ) : null}

                {!editCategoryHasSchedule && (
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-1.5">
                      POS Price ($)
                    </label>
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      value={editPosPrice}
                      onChange={(e) => setEditPosPrice(e.target.value)}
                      className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                    />
                  </div>
                )}

                <div className="flex items-center justify-between py-2 px-3 rounded-xl bg-slate-50 border border-slate-100">
                  <div>
                    <p className="text-sm font-medium text-slate-700">Available Online</p>
                    <p className="text-xs text-slate-400">Enable for upcoming online ordering</p>
                  </div>
                  <button
                    type="button"
                    onClick={() => setEditChannelOnline(!editChannelOnline)}
                    className={`relative w-10 h-5 rounded-full transition-colors ${editChannelOnline ? "bg-blue-500" : "bg-slate-200"}`}
                  >
                    <span className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow-sm transition-transform ${editChannelOnline ? "right-0.5" : "left-0.5"}`} />
                  </button>
                </div>

                {stockCountingEnabled && (
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Stock
                  </label>
                  <input
                    type="number"
                    min="0"
                    step="1"
                    value={editStock}
                    onChange={(e) => setEditStock(e.target.value)}
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>
                )}

                {editTarget && allSubcategories.filter((s) => s.categoryId === editTarget.categoryId).length > 0 && (
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-1.5">
                      Subcategory
                    </label>
                    <select
                      value={editSubcategoryId}
                      onChange={(e) => setEditSubcategoryId(e.target.value)}
                      className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white"
                    >
                      <option value="">(None)</option>
                      {allSubcategories.filter((s) => s.categoryId === editTarget.categoryId).map((s) => (
                        <option key={s.id} value={s.id}>{s.name}</option>
                      ))}
                    </select>
                  </div>
                )}

                {/* ── Order Types ── */}
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-2">
                    Order Types
                  </label>

                  <label className="flex items-center gap-2 mb-3 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={editUseCategoryTypes}
                      onChange={(e) =>
                        setEditUseCategoryTypes(e.target.checked)
                      }
                      className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                    />
                    <span className="text-sm text-slate-600">
                      Use category availability
                    </span>
                  </label>

                  {!editUseCategoryTypes && (
                    <div className="flex flex-col gap-2 pl-1">
                      {ALL_ORDER_TYPES.map((t) => (
                        <label
                          key={t}
                          className="flex items-center gap-2 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={editOrderTypes[t] ?? false}
                            onChange={(e) =>
                              setEditOrderTypes((prev) => ({
                                ...prev,
                                [t]: e.target.checked,
                              }))
                            }
                            className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                          />
                          <span className="text-sm text-slate-700">
                            {ORDER_TYPE_LABELS[t]}
                          </span>
                        </label>
                      ))}
                    </div>
                  )}
                </div>

                {/* ── Assign label (collapsible) ── */}
                <div className="rounded-xl border border-slate-200/80 overflow-hidden">
                  <button
                    type="button"
                    onClick={() => setEditLabelExpanded((v) => !v)}
                    className="flex w-full items-center justify-between gap-2 px-3 py-2.5 text-left bg-slate-50/90 hover:bg-slate-100/90 transition-colors"
                  >
                    <span className="flex items-center gap-2 min-w-0 text-sm font-medium text-slate-700">
                      <Printer size={15} className="text-slate-500 shrink-0" />
                      <span className="truncate">Assign label</span>
                      {editPrinterLabel.trim().length > 0 && (
                        <span className="text-xs font-semibold text-blue-600 tabular-nums shrink-0 truncate max-w-[120px]">
                          ({editPrinterLabel.trim()})
                        </span>
                      )}
                    </span>
                    <ChevronDown
                      size={18}
                      className={`text-slate-400 shrink-0 transition-transform duration-200 ${
                        editLabelExpanded ? "rotate-180" : ""
                      }`}
                    />
                  </button>
                  <div
                    className={`grid transition-[grid-template-rows] duration-200 ease-out ${
                      editLabelExpanded ? "grid-rows-[1fr]" : "grid-rows-[0fr]"
                    }`}
                  >
                    <div className="min-h-0 overflow-hidden">
                      <div className="px-3 py-3 space-y-1.5 border-t border-slate-100">
                        <p className="text-[10px] text-slate-400">
                          Labels match kitchen printers on the POS. (None) clears routing.
                        </p>
                        <select
                          value={editPrinterLabel}
                          onChange={(e) => setEditPrinterLabel(e.target.value)}
                          className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white"
                        >
                          <option value="">(None)</option>
                          {editAssignableLabelOptions.map((lbl) => (
                              <option key={lbl} value={lbl}>
                                {lbl}
                              </option>
                            ))}
                        </select>
                      </div>
                    </div>
                  </div>
                </div>

                {/* ── Add KDS (collapsible) ── */}
                <div className="rounded-xl border border-slate-200/80 overflow-hidden">
                  <button
                    type="button"
                    onClick={() => setEditKdsExpanded((v) => !v)}
                    className="flex w-full items-center justify-between gap-2 px-3 py-2.5 text-left bg-slate-50/90 hover:bg-slate-100/90 transition-colors"
                  >
                    <span className="flex items-center gap-2 min-w-0 text-sm font-medium text-slate-700">
                      <Monitor size={15} className="text-slate-500 shrink-0" />
                      <span className="truncate">Add KDS</span>
                      {editKdsDeviceId.trim().length > 0 && (
                        <span className="text-xs font-semibold text-blue-600 tabular-nums shrink-0 truncate max-w-[140px]">
                          (
                          {kdsPickerDevices.find((d) => d.id === editKdsDeviceId)?.name ??
                            editKdsDeviceId}
                          )
                        </span>
                      )}
                    </span>
                    <ChevronDown
                      size={18}
                      className={`text-slate-400 shrink-0 transition-transform duration-200 ${
                        editKdsExpanded ? "rotate-180" : ""
                      }`}
                    />
                  </button>
                  <div
                    className={`grid transition-[grid-template-rows] duration-200 ease-out ${
                      editKdsExpanded ? "grid-rows-[1fr]" : "grid-rows-[0fr]"
                    }`}
                  >
                    <div className="min-h-0 overflow-hidden">
                      <div className="px-3 py-3 space-y-1.5 border-t border-slate-100">
                        <p className="text-[10px] text-slate-400">
                          Active KDS devices only. The selection reflects how the item already reaches a
                          KDS (explicit item list or assigned categories). Saving updates the explicit item
                          list; (None) clears explicit ids on every device (category assignments unchanged).
                        </p>
                        <select
                          value={editKdsDeviceId}
                          onChange={(e) => {
                            editKdsTouchedRef.current = true;
                            setEditKdsDeviceId(e.target.value);
                          }}
                          className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white"
                        >
                          <option value="">(None)</option>
                          {editKdsSelectOptions.map((d) => (
                            <option key={d.id} value={d.id}>
                              {d.name}
                              {!d.isActive ? " (inactive)" : ""}
                            </option>
                          ))}
                        </select>
                      </div>
                    </div>
                  </div>
                </div>

                {/* ── Assign Modifiers (collapsible) ── */}
                {modifierGroups.length > 0 && (() => {
                  const nestedIds = new Set<string>();
                  for (const g of modifierGroups) {
                    for (const o of g.options) {
                      for (const tid of o.triggersModifierGroupIds) nestedIds.add(tid);
                    }
                  }
                  const topLevelGroups = modifierGroups.filter((g) => !nestedIds.has(g.id));
                  const nestedGroups = modifierGroups.filter((g) => nestedIds.has(g.id));
                  const selectedEditModCount = Object.values(editModifiers).filter(Boolean).length;
                  return (
                  <div className="rounded-xl border border-slate-200/80 overflow-hidden">
                    <button
                      type="button"
                      onClick={() => setEditModifiersExpanded((v) => !v)}
                      className="flex w-full items-center justify-between gap-2 px-3 py-2.5 text-left bg-slate-50/90 hover:bg-slate-100/90 transition-colors"
                    >
                      <span className="flex items-center gap-2 min-w-0 text-sm font-medium text-slate-700">
                        <SlidersHorizontal size={15} className="text-slate-500 shrink-0" />
                        <span className="truncate">Assign Modifiers</span>
                        {selectedEditModCount > 0 && (
                          <span className="text-xs font-semibold text-blue-600 tabular-nums shrink-0">
                            ({selectedEditModCount})
                          </span>
                        )}
                      </span>
                      <ChevronDown
                        size={18}
                        className={`text-slate-400 shrink-0 transition-transform duration-200 ${
                          editModifiersExpanded ? "rotate-180" : ""
                        }`}
                      />
                    </button>
                    <div
                      className={`grid transition-[grid-template-rows] duration-200 ease-out ${
                        editModifiersExpanded ? "grid-rows-[1fr]" : "grid-rows-[0fr]"
                      }`}
                    >
                      <div className="min-h-0 overflow-hidden">
                        <div className="flex flex-col gap-2 px-3 py-3 max-h-40 overflow-y-auto border-t border-slate-100">
                          {topLevelGroups.map((g) => (
                            <label
                              key={g.id}
                              className="flex items-center gap-2 cursor-pointer"
                            >
                              <input
                                type="checkbox"
                                checked={editModifiers[g.id] ?? false}
                                onChange={(e) =>
                                  setEditModifiers((prev) => ({
                                    ...prev,
                                    [g.id]: e.target.checked,
                                  }))
                                }
                                className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                              />
                              <span className="text-sm text-slate-700">{g.name}</span>
                              <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${
                                g.groupType === "REMOVE"
                                  ? "bg-red-50 text-red-500"
                                  : "bg-slate-100 text-slate-400"
                              }`}>
                                {g.groupType === "REMOVE" ? "Remove" : "Add-on"}
                              </span>
                            </label>
                          ))}
                          {nestedGroups.length > 0 && (
                            <div className="mt-1 pt-1 border-t border-slate-100">
                              <p className="text-[10px] text-slate-400 mb-1">Triggered by modifier options:</p>
                              {nestedGroups.map((g) => (
                                <label
                                  key={g.id}
                                  className="flex items-center gap-2 cursor-pointer opacity-60"
                                >
                                  <input
                                    type="checkbox"
                                    checked={editModifiers[g.id] ?? false}
                                    onChange={(e) =>
                                      setEditModifiers((prev) => ({
                                        ...prev,
                                        [g.id]: e.target.checked,
                                      }))
                                    }
                                    className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                                  />
                                  <span className="text-sm text-slate-700">{g.name}</span>
                                  <span className="text-[10px] px-1.5 py-0.5 rounded font-medium bg-blue-50 text-blue-500">
                                    Nested
                                  </span>
                                </label>
                              ))}
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  </div>
                  );
                })()}

                {/* ── Assign Taxes (collapsible) ── */}
                {taxes.length > 0 && (() => {
                  const enabledEditTaxes = taxes.filter((t) => t.enabled);
                  const selectedEditTaxCount = Object.entries(editTaxes).filter(
                    ([id, v]) => v && enabledEditTaxes.some((t) => t.id === id)
                  ).length;
                  return (
                  <div className="rounded-xl border border-slate-200/80 overflow-hidden">
                    <button
                      type="button"
                      onClick={() => setEditTaxesExpanded((v) => !v)}
                      className="flex w-full items-center justify-between gap-2 px-3 py-2.5 text-left bg-slate-50/90 hover:bg-slate-100/90 transition-colors"
                    >
                      <span className="flex items-center gap-2 min-w-0 text-sm font-medium text-slate-700">
                        <Receipt size={15} className="text-slate-500 shrink-0" />
                        <span className="truncate">Assign Taxes</span>
                        {selectedEditTaxCount > 0 && (
                          <span className="text-xs font-semibold text-blue-600 tabular-nums shrink-0">
                            ({selectedEditTaxCount})
                          </span>
                        )}
                      </span>
                      <ChevronDown
                        size={18}
                        className={`text-slate-400 shrink-0 transition-transform duration-200 ${
                          editTaxesExpanded ? "rotate-180" : ""
                        }`}
                      />
                    </button>
                    <div
                      className={`grid transition-[grid-template-rows] duration-200 ease-out ${
                        editTaxesExpanded ? "grid-rows-[1fr]" : "grid-rows-[0fr]"
                      }`}
                    >
                      <div className="min-h-0 overflow-hidden">
                        <div className="flex flex-col gap-2 px-3 py-3 max-h-40 overflow-y-auto border-t border-slate-100">
                          {enabledEditTaxes.map((t) => (
                            <label
                              key={t.id}
                              className="flex items-center gap-2 cursor-pointer"
                            >
                              <input
                                type="checkbox"
                                checked={editTaxes[t.id] ?? false}
                                onChange={(e) =>
                                  setEditTaxes((prev) => ({
                                    ...prev,
                                    [t.id]: e.target.checked,
                                  }))
                                }
                                className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                              />
                              <span className="text-sm text-slate-700">{t.name}</span>
                              <span className="text-[10px] px-1.5 py-0.5 rounded font-medium bg-amber-50 text-amber-600">
                                {t.amount}%
                              </span>
                            </label>
                          ))}
                        </div>
                      </div>
                    </div>
                  </div>
                  );
                })()}
              </div>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setEditTarget(null)}
                  disabled={saving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSave}
                  disabled={saving}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {saving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Saving…
                    </>
                  ) : (
                    "Save"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
      {/* ── Add / Edit Subcategory Modal ── */}
      {subModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !subSaving && setSubModalOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden max-h-[90vh] overflow-y-auto">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  {subEditing ? "Edit Subcategory" : "Add Subcategory"}
                </h3>
                <button
                  onClick={() => !subSaving && setSubModalOpen(false)}
                  disabled={subSaving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Subcategory Name
                </label>
                <input
                  type="text"
                  value={subName}
                  onChange={(e) => setSubName(e.target.value)}
                  placeholder="e.g. Soda, Burgers, Appetizers"
                  autoFocus
                  className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Category
                </label>
                <select
                  value={subCategoryId}
                  onChange={(e) => {
                    setSubCategoryId(e.target.value);
                    const sel: Record<string, boolean> = {};
                    for (const item of items) {
                      if (item.categoryId === e.target.value) {
                        sel[item.id] = subEditing ? item.subcategoryId === subEditing.id : false;
                      }
                    }
                    setSubItemSelections(sel);
                  }}
                  className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white"
                >
                  <option value="">Select a category</option>
                  {categories.map((cat) => (
                    <option key={cat.id} value={cat.id}>{cat.name}</option>
                  ))}
                </select>
              </div>

              {subCategoryId && (
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-2">
                    Assign Items
                  </label>
                  <p className="text-[10px] text-slate-400 mb-2">
                    Select items to include in this subcategory.
                  </p>
                  {(() => {
                    const catItems = items.filter((i) => i.categoryId === subCategoryId);
                    if (catItems.length === 0) {
                      return (
                        <p className="text-xs text-slate-400 italic py-2">
                          No items in this category yet.
                        </p>
                      );
                    }
                    return (
                      <div className="flex flex-col gap-1.5 max-h-48 overflow-y-auto border border-slate-100 rounded-xl p-2">
                        {catItems.map((item) => (
                          <label key={item.id} className="flex items-center gap-2 px-2 py-1.5 rounded-lg hover:bg-slate-50 cursor-pointer">
                            <input
                              type="checkbox"
                              checked={subItemSelections[item.id] ?? false}
                              onChange={(e) =>
                                setSubItemSelections((prev) => ({ ...prev, [item.id]: e.target.checked }))
                              }
                              className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                            />
                            <span className="text-sm text-slate-700 truncate">{item.name}</span>
                            <span className="text-xs text-slate-400 ml-auto tabular-nums">${item.price.toFixed(2)}</span>
                          </label>
                        ))}
                      </div>
                    );
                  })()}
                </div>
              )}
            </div>

            <div className="px-6 py-4 bg-slate-50 border-t border-slate-100 flex gap-3">
              <button
                onClick={() => setSubModalOpen(false)}
                disabled={subSaving}
                className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                onClick={handleSaveSubcategory}
                disabled={subSaving || !subName.trim() || !subCategoryId}
                className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
              >
                {subSaving ? (
                  <>
                    <span className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                    Saving…
                  </>
                ) : subEditing ? (
                  "Save Changes"
                ) : (
                  "Add Subcategory"
                )}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Delete Subcategory Confirmation ── */}
      {deleteSubTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !deletingSub && setDeleteSubTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-full bg-red-100 flex items-center justify-center shrink-0">
                  <AlertTriangle size={20} className="text-red-500" />
                </div>
                <div>
                  <h3 className="text-lg font-semibold text-slate-800">
                    Delete Subcategory
                  </h3>
                  <p className="text-sm text-slate-500 mt-0.5">
                    Are you sure you want to delete <strong>{deleteSubTarget.name}</strong>? Items in this subcategory will be unassigned.
                  </p>
                </div>
              </div>
            </div>
            <div className="px-6 py-4 bg-slate-50 border-t border-slate-100 flex gap-3">
              <button
                onClick={() => setDeleteSubTarget(null)}
                disabled={deletingSub}
                className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                onClick={handleDeleteSubcategory}
                disabled={deletingSub}
                className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
              >
                {deletingSub ? (
                  <>
                    <span className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                    Deleting…
                  </>
                ) : (
                  "Delete"
                )}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
