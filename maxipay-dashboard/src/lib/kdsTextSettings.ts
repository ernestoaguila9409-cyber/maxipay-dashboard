/**
 * Mirrors Android `KdsTextSettings` / `parseKdsTextSettings` for
 * `kds_devices/{deviceId}/settings/ui`.
 */

export const KDS_TEXT_MIN_SP = 10;
export const KDS_TEXT_MAX_SP = 44;
export const KDS_TEXT_MAX_SP_MODIFIERS = 30;
export const KDS_TEXT_STEP_SP = 1;

/** Preset ARGB colors — same order as `KdsScreen.kt` KdsModifierColorSwatches. */
export const KDS_MODIFIER_COLOR_SWATCHES: number[] = [
  0xff212121, 0xff424242, 0xff555555, 0xff1565c0, 0xff1976d2, 0xff2e7d32, 0xff00897b,
  0xff6a4fb3, 0xffe65100, 0xfff9a825, 0xffc62828, 0xffad1457, 0xffffffff,
];

export type KdsTextUiSettings = {
  headerSp: number;
  tableInfoSp: number;
  customerNameSp: number;
  timerSp: number;
  itemNameSp: number;
  modifiersSp: number;
  buttonsSp: number;
  modifierAddColorArgb: number;
  modifierRemoveColorArgb: number;
};

const DEFAULT: KdsTextUiSettings = {
  headerSp: 24,
  tableInfoSp: 15,
  customerNameSp: 15,
  timerSp: 18,
  itemNameSp: 24,
  modifiersSp: 18,
  buttonsSp: 20,
  modifierAddColorArgb: 0xff555555,
  modifierRemoveColorArgb: 0xffc62828,
};

function clampSp(v: number, max: number = KDS_TEXT_MAX_SP): number {
  const n = Number.isFinite(v) ? v : DEFAULT.headerSp;
  return Math.min(max, Math.max(KDS_TEXT_MIN_SP, Math.round(n * 10) / 10));
}

export function coerceKdsTextUi(s: KdsTextUiSettings): KdsTextUiSettings {
  return {
    headerSp: clampSp(s.headerSp),
    tableInfoSp: clampSp(s.tableInfoSp),
    customerNameSp: clampSp(s.customerNameSp),
    timerSp: clampSp(s.timerSp),
    itemNameSp: clampSp(s.itemNameSp),
    modifiersSp: clampSp(s.modifiersSp, KDS_TEXT_MAX_SP_MODIFIERS),
    buttonsSp: clampSp(s.buttonsSp),
    modifierAddColorArgb: (s.modifierAddColorArgb >>> 0) & 0xffffffff,
    modifierRemoveColorArgb: (s.modifierRemoveColorArgb >>> 0) & 0xffffffff,
  };
}

export function parseKdsTextUi(data: Record<string, unknown> | undefined | null): KdsTextUiSettings {
  if (!data) return coerceKdsTextUi({ ...DEFAULT });

  const f = (key: string, def: number, max: number = KDS_TEXT_MAX_SP): number => {
    const v = data[key];
    if (typeof v !== "number" || !Number.isFinite(v)) return def;
    return clampSp(v, max);
  };

  const d = DEFAULT;
  const tableInfoSp = f("tableInfoSp", d.tableInfoSp);
  let customerNameSp = tableInfoSp;
  const rawCn = data.customerNameSp;
  if (typeof rawCn === "number" && Number.isFinite(rawCn)) {
    customerNameSp = clampSp(rawCn);
  }

  const colorLong = (key: string, def: number): number => {
    const v = data[key];
    if (typeof v !== "number" || !Number.isFinite(v)) return def;
    return (Math.trunc(v) >>> 0) & 0xffffffff;
  };

  return coerceKdsTextUi({
    headerSp: f("headerSp", d.headerSp),
    tableInfoSp,
    customerNameSp,
    timerSp: f("timerSp", d.timerSp),
    itemNameSp: f("itemNameSp", d.itemNameSp),
    modifiersSp: f("modifiersSp", d.modifiersSp, KDS_TEXT_MAX_SP_MODIFIERS),
    buttonsSp: f("buttonsSp", d.buttonsSp),
    modifierAddColorArgb: colorLong("modifierAddColorArgb", d.modifierAddColorArgb),
    modifierRemoveColorArgb: colorLong("modifierRemoveColorArgb", d.modifierRemoveColorArgb),
  });
}

export function kdsTextUiToFirestore(s: KdsTextUiSettings): Record<string, number> {
  const c = coerceKdsTextUi(s);
  return {
    headerSp: c.headerSp,
    tableInfoSp: c.tableInfoSp,
    customerNameSp: c.customerNameSp,
    timerSp: c.timerSp,
    itemNameSp: c.itemNameSp,
    modifiersSp: c.modifiersSp,
    buttonsSp: c.buttonsSp,
    modifierAddColorArgb: c.modifierAddColorArgb,
    modifierRemoveColorArgb: c.modifierRemoveColorArgb,
  };
}

export type KdsTextFieldKey =
  | "headerSp"
  | "tableInfoSp"
  | "customerNameSp"
  | "timerSp"
  | "itemNameSp"
  | "modifiersSp"
  | "buttonsSp";

export function adjustKdsTextField(
  s: KdsTextUiSettings,
  key: KdsTextFieldKey,
  deltaSteps: number
): KdsTextUiSettings {
  const d = KDS_TEXT_STEP_SP * deltaSteps;
  const next: KdsTextUiSettings = {
    ...s,
    [key]: s[key] + d,
  };
  return coerceKdsTextUi(next);
}

export function argbToCss(argb: number): string {
  const u = (argb >>> 0) & 0xffffffff;
  const a = (u >>> 24) & 0xff;
  const r = (u >>> 16) & 0xff;
  const g = (u >>> 8) & 0xff;
  const b = u & 0xff;
  if (a === 255) return `rgb(${r},${g},${b})`;
  return `rgba(${r},${g},${b},${a / 255})`;
}
