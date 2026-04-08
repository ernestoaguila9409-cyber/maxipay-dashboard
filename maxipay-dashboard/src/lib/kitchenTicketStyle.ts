/**
 * Per-printer kitchen chit typography (Firestore `kitchenTicketStyle` on `Printers` docs).
 * Font sizes match POS ESC/POS: 0=Normal, 1=Large (tall), 2=X-Large (2x2).
 */

export type KitchenFontSize = 0 | 1 | 2;

export const KITCHEN_FONT_SIZE_LABELS: Record<KitchenFontSize, string> = {
  0: "Normal",
  1: "Large",
  2: "X-Large",
};

export interface KitchenTicketStyleState {
  /** When true, print `Table: …` only if order type is Dine In. */
  showTableLineOnlyForDineIn: boolean;
  /** When true, print routing tag line (e.g. `[drinks]`) under each item. */
  showRoutingTag: boolean;
  titleFontSize: KitchenFontSize;
  titleBold: boolean;
  metaFontSize: KitchenFontSize;
  metaBold: boolean;
  dividerFontSize: KitchenFontSize;
  dividerBold: boolean;
  itemFontSize: KitchenFontSize;
  itemBold: boolean;
  modifierFontSize: KitchenFontSize;
  modifierBold: boolean;
  stationTagFontSize: KitchenFontSize;
  stationTagBold: boolean;
  notesHeadingFontSize: KitchenFontSize;
  notesHeadingBold: boolean;
  notesBodyFontSize: KitchenFontSize;
  notesBodyBold: boolean;
}

export const DEFAULT_KITCHEN_TICKET_STYLE: KitchenTicketStyleState = {
  showTableLineOnlyForDineIn: true,
  showRoutingTag: false,
  titleFontSize: 0,
  titleBold: false,
  metaFontSize: 0,
  metaBold: false,
  dividerFontSize: 0,
  dividerBold: false,
  itemFontSize: 0,
  itemBold: false,
  modifierFontSize: 0,
  modifierBold: false,
  stationTagFontSize: 0,
  stationTagBold: false,
  notesHeadingFontSize: 0,
  notesHeadingBold: false,
  notesBodyFontSize: 0,
  notesBodyBold: false,
};

function clampFontSize(n: number): KitchenFontSize {
  if (n === 1) return 1;
  if (n >= 2) return 2;
  return 0;
}

function num(raw: unknown): number | null {
  if (typeof raw === "number" && Number.isFinite(raw)) return raw;
  if (typeof raw === "string" && raw.trim() !== "" && !Number.isNaN(Number(raw))) return Number(raw);
  if (raw && typeof raw === "object" && "toNumber" in raw && typeof (raw as { toNumber: () => number }).toNumber === "function") {
    try {
      return (raw as { toNumber: () => number }).toNumber();
    } catch {
      return null;
    }
  }
  return null;
}

function bool(raw: unknown, fallback: boolean): boolean {
  if (typeof raw === "boolean") return raw;
  return fallback;
}

/** Merge Firestore map with defaults; returns null if `raw` is missing or not an object. */
export function parseKitchenTicketStyle(raw: unknown): KitchenTicketStyleState | null {
  if (raw == null || typeof raw !== "object") return null;
  const o = raw as Record<string, unknown>;
  const d = DEFAULT_KITCHEN_TICKET_STYLE;
  return {
    showTableLineOnlyForDineIn: bool(o.showTableLineOnlyForDineIn, d.showTableLineOnlyForDineIn),
    showRoutingTag: bool(o.showRoutingTag, d.showRoutingTag),
    titleFontSize: clampFontSize(num(o.titleFontSize) ?? d.titleFontSize),
    titleBold: bool(o.titleBold, d.titleBold),
    metaFontSize: clampFontSize(num(o.metaFontSize) ?? d.metaFontSize),
    metaBold: bool(o.metaBold, d.metaBold),
    dividerFontSize: clampFontSize(num(o.dividerFontSize) ?? d.dividerFontSize),
    dividerBold: bool(o.dividerBold, d.dividerBold),
    itemFontSize: clampFontSize(num(o.itemFontSize) ?? d.itemFontSize),
    itemBold: bool(o.itemBold, d.itemBold),
    modifierFontSize: clampFontSize(num(o.modifierFontSize) ?? d.modifierFontSize),
    modifierBold: bool(o.modifierBold, d.modifierBold),
    stationTagFontSize: clampFontSize(num(o.stationTagFontSize) ?? d.stationTagFontSize),
    stationTagBold: bool(o.stationTagBold, d.stationTagBold),
    notesHeadingFontSize: clampFontSize(num(o.notesHeadingFontSize) ?? d.notesHeadingFontSize),
    notesHeadingBold: bool(o.notesHeadingBold, d.notesHeadingBold),
    notesBodyFontSize: clampFontSize(num(o.notesBodyFontSize) ?? d.notesBodyFontSize),
    notesBodyBold: bool(o.notesBodyBold, d.notesBodyBold),
  };
}

export function kitchenTicketStyleForFirestore(s: KitchenTicketStyleState): Record<string, number | boolean> {
  return {
    showTableLineOnlyForDineIn: s.showTableLineOnlyForDineIn,
    showRoutingTag: s.showRoutingTag,
    titleFontSize: s.titleFontSize,
    titleBold: s.titleBold,
    metaFontSize: s.metaFontSize,
    metaBold: s.metaBold,
    dividerFontSize: s.dividerFontSize,
    dividerBold: s.dividerBold,
    itemFontSize: s.itemFontSize,
    itemBold: s.itemBold,
    modifierFontSize: s.modifierFontSize,
    modifierBold: s.modifierBold,
    stationTagFontSize: s.stationTagFontSize,
    stationTagBold: s.stationTagBold,
    notesHeadingFontSize: s.notesHeadingFontSize,
    notesHeadingBold: s.notesHeadingBold,
    notesBodyFontSize: s.notesBodyFontSize,
    notesBodyBold: s.notesBodyBold,
  };
}

/** Character width per line on 58mm kitchen paper (matches POS). */
export function kitchenPreviewLineWidth(fontSize: KitchenFontSize): number {
  return fontSize === 2 ? 16 : 32;
}

export function repeatDash(width: number): string {
  if (width <= 0) return "";
  return "-".repeat(width);
}
