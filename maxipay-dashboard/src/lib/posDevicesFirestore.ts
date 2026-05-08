/** POS Android tablets / phones report foreground heartbeats here for the Devices settings page. */
export const POS_DEVICES_COLLECTION = "PosDevices";

/** Consider a device online if [lastSeen] is newer than this (heartbeat every ~45s on Android). */
export const POS_DEVICE_ONLINE_THRESHOLD_MS = 120_000;
