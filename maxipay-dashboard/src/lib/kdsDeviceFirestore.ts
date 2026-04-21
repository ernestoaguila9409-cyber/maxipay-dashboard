/**
 * Shared parsing for `kds_devices` (dashboard + menu item picker).
 */

export const KDS_DEVICES_COLLECTION = "kds_devices";

export function parseAssignedItemIds(data: Record<string, unknown>): string[] {
  const raw = data.assignedItemIds;
  if (!Array.isArray(raw)) return [];
  return raw
    .map((x) => String(x ?? "").trim())
    .filter((x) => x.length > 0);
}

export function parseAssignedCategoryIds(data: Record<string, unknown>): string[] {
  const raw = data.assignedCategoryIds;
  if (!Array.isArray(raw)) return [];
  return raw
    .map((x) => String(x ?? "").trim())
    .filter((x) => x.length > 0);
}

/**
 * Old KDS builds used ANDROID_ID as the Firestore document id (often 16 hex chars) and
 * `set(merge)` heartbeats, so deleting the dashboard-registered row left a second doc that
 * looked like the same tablet. Hide those from the list so they don't "come back."
 */
export function shouldHideLegacyKdsAutoDevice(
  id: string,
  data: Record<string, unknown>
): boolean {
  if (data.registeredFromWeb === true) return false;
  const pc = data.pairingCode;
  if (typeof pc === "string" && /^\d{6}$/.test(pc)) return false;
  if (data.isPaired === true) return false;
  return /^[a-f0-9]{16}$/i.test(id);
}

/** Row shape for KDS pickers (menu item, etc.). */
export interface KdsDevicePickerRow {
  id: string;
  name: string;
  isActive: boolean;
  assignedCategoryIds: string[];
  assignedItemIds: string[];
}

export function parseKdsDevicePickerRow(
  id: string,
  data: Record<string, unknown>
): KdsDevicePickerRow {
  return {
    id,
    name: String(data.name ?? "").trim() || "KDS device",
    isActive: data.isActive !== false,
    assignedCategoryIds: parseAssignedCategoryIds(data),
    assignedItemIds: parseAssignedItemIds(data),
  };
}
