/** POS Android tablets / phones report foreground heartbeats here for the Devices settings page. */
export const POS_DEVICES_COLLECTION = "PosDevices";

/** Single-use codes created from the dashboard; redeemed on the POS to mark [PosDevices] enrolled. */
export const DEVICE_ACTIVATIONS_COLLECTION = "DeviceActivations";

/** How long a generated activation code stays valid (matches dashboard + POS messaging). */
export const DEVICE_ACTIVATION_TTL_MS = 15 * 60 * 1000;

/** Consider a device online if [lastSeen] is newer than this (heartbeat every ~45s on Android). */
export const POS_DEVICE_ONLINE_THRESHOLD_MS = 120_000;

/** Cash log entries created from the merchant dashboard (not a POS tablet). */
export const WEB_DASHBOARD_DEVICE_ID = "web_dashboard";

export interface PosDeviceOption {
  id: string;
  label: string;
  deactivated: boolean;
}

export function parsePosDeviceOption(
  id: string,
  data: Record<string, unknown>
): PosDeviceOption {
  const deactivated = data.deactivated === true;
  const model = String(data.deviceModel ?? "").trim() || "Unknown device";
  const platform = String(data.platform ?? "").trim();
  const label = platform ? `${model} (${platform})` : model;
  return { id, label, deactivated };
}
