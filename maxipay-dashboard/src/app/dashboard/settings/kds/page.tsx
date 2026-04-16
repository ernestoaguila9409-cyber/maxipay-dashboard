"use client";

import { useEffect, useState, useCallback } from "react";
import {
  collection,
  updateDoc,
  deleteDoc,
  doc,
  onSnapshot,
  serverTimestamp,
  setDoc,
  deleteField,
} from "firebase/firestore";
import { FirebaseError } from "firebase/app";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import {
  KdsPreview,
  parseDashboardModuleColorKeys,
} from "@/components/KdsPreview";
import {
  Plus,
  Pencil,
  Trash2,
  X,
  Monitor,
  LayoutGrid,
  Palette,
  Timer,
  AlarmClock,
  Copy,
  Check,
} from "lucide-react";

const KDS_DEVICES_COLLECTION = "kds_devices";
const KDS_SETTINGS_DOC = "kds";
const SETTINGS_COLLECTION = "Settings";
const STATIONS_COLLECTION = "stations";

/** Kitchen station (Firestore `stations` collection). Document id is canonical [id]. */
interface KitchenStation {
  id: string;
  name: string;
  color: string;
  createdAt: Date | null;
}

interface KdsDevice {
  id: string;
  name: string;
  stationId: string;
  /** Legacy field when devices only stored a display name in `station`. */
  legacyStationName?: string;
  /** Six-digit code for tablet pairing; removed from doc after pair. */
  pairingCode: string | null;
  isPaired: boolean;
  deviceType: string;
  /** Set by KDS app (Build.MANUFACTURER + MODEL) after pair / heartbeat. */
  deviceModel: string;
  isActive: boolean;
  createdAt: Date | null;
  /** Heartbeat from the KDS app (Firestore Timestamp). */
  lastSeen: Date | null;
}

type LiveStatus = "online" | "offline";

const ONLINE_MAX_MS = 10_000;

function parseFirestoreDate(value: unknown): Date | null {
  if (value && typeof (value as { toDate?: () => Date }).toDate === "function") {
    return (value as { toDate: () => Date }).toDate();
  }
  return null;
}

function getLiveStatus(lastSeen: Date | null, nowMs: number): LiveStatus {
  if (!lastSeen) return "offline";
  const age = nowMs - lastSeen.getTime();
  if (age < ONLINE_MAX_MS) return "online";
  return "offline";
}

/** Relative label for last heartbeat (updates with `nowMs`). */
function formatLastSeenAgo(lastSeen: Date | null, nowMs: number): string {
  if (!lastSeen) return "Never";
  const sec = Math.floor((nowMs - lastSeen.getTime()) / 1000);
  if (sec < 0) return "Just now";
  if (sec < 5) return "Just now";
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return min === 1 ? "1 min ago" : `${min} min ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return hr === 1 ? "1 hr ago" : `${hr} hr ago`;
  const days = Math.floor(hr / 24);
  return days === 1 ? "1 day ago" : `${days} days ago`;
}

interface KdsDisplaySettings {
  orderTypeColorsEnabled: boolean;
  gridColumns: 2 | 3;
  showTimers: boolean;
  ticketYellowAfterMinutes: number;
  ticketRedAfterMinutes: number;
}

const MAX_TICKET_URGENCY_MINUTES = 24 * 60;
const DEFAULT_TICKET_YELLOW_AFTER_MINUTES = 5;
const DEFAULT_TICKET_RED_AFTER_MINUTES = 10;

function parseFirestoreTicketMinutes(value: unknown, fallback: number): number {
  if (typeof value === "number" && Number.isFinite(value)) {
    return Math.round(value);
  }
  if (typeof value === "string" && value.trim() !== "") {
    const n = parseInt(value, 10);
    if (Number.isFinite(n)) return n;
  }
  return fallback;
}

function normalizeTicketUrgencyForState(
  yellow: number,
  red: number
): Pick<KdsDisplaySettings, "ticketYellowAfterMinutes" | "ticketRedAfterMinutes"> {
  let y = Math.max(0, Math.min(MAX_TICKET_URGENCY_MINUTES, Math.round(yellow)));
  let r = Math.max(0, Math.min(MAX_TICKET_URGENCY_MINUTES, Math.round(red)));
  if (r < y) r = y;
  return { ticketYellowAfterMinutes: y, ticketRedAfterMinutes: r };
}

const defaultDisplaySettings: KdsDisplaySettings = {
  orderTypeColorsEnabled: true,
  gridColumns: 3,
  showTimers: true,
  ticketYellowAfterMinutes: DEFAULT_TICKET_YELLOW_AFTER_MINUTES,
  ticketRedAfterMinutes: DEFAULT_TICKET_RED_AFTER_MINUTES,
};

function parseDevice(id: string, data: Record<string, unknown>): KdsDevice {
  const createdAt = parseFirestoreDate(data.createdAt);
  const lastSeen = parseFirestoreDate(data.lastSeen);
  const stationId = String(data.stationId ?? "");
  const legacy =
    typeof data.station === "string" && data.station.trim() !== ""
      ? data.station.trim()
      : undefined;
  const rawCode = data.pairingCode;
  const pairingCode =
    typeof rawCode === "string" && /^\d{6}$/.test(rawCode) ? rawCode : null;
  const isPaired =
    pairingCode != null ? data.isPaired === true : data.isPaired !== false;
  return {
    id,
    name: String(data.name ?? ""),
    stationId,
    legacyStationName: legacy,
    pairingCode,
    isPaired,
    deviceType: String(data.deviceType ?? "").trim(),
    deviceModel: String(data.deviceModel ?? "").trim(),
    isActive: data.isActive !== false,
    createdAt,
    lastSeen,
  };
}

/**
 * Old KDS builds used ANDROID_ID as the Firestore document id (often 16 hex chars) and
 * `set(merge)` heartbeats, so deleting the dashboard-registered row left a second doc that
 * looked like the same tablet. Hide those from the list so they don't "come back."
 */
function shouldHideLegacyKdsAutoDevice(
  id: string,
  data: Record<string, unknown>
): boolean {
  if (data.registeredFromWeb === true) return false;
  const pc = data.pairingCode;
  if (typeof pc === "string" && /^\d{6}$/.test(pc)) return false;
  if (data.isPaired === true) return false;
  return /^[a-f0-9]{16}$/i.test(id);
}

/** 6-digit code; generated locally (no Firestore query — avoids index/rules issues on read). */
function generatePairingCode(): string {
  return String(Math.floor(Math.random() * 1_000_000)).padStart(6, "0");
}

function parseStation(docId: string, data: Record<string, unknown>): KitchenStation {
  return {
    id: String(data.id ?? docId),
    name: String(data.name ?? "").trim() || "Unnamed station",
    color: String(data.color ?? "").trim() || "#94a3b8",
    createdAt: parseFirestoreDate(data.createdAt),
  };
}

function stationDisplayName(
  device: KdsDevice,
  stations: KitchenStation[]
): string {
  if (device.stationId) {
    const s = stations.find((x) => x.id === device.stationId);
    if (s) return s.name;
    return "Unknown station";
  }
  return device.legacyStationName ?? "—";
}

export default function KdsSettingsPage() {
  const { user } = useAuth();
  const [stations, setStations] = useState<KitchenStation[]>([]);
  const [stationsLoading, setStationsLoading] = useState(true);
  const [devices, setDevices] = useState<KdsDevice[]>([]);
  const [loading, setLoading] = useState(true);
  const [displaySettings, setDisplaySettings] = useState<KdsDisplaySettings>(
    defaultDisplaySettings
  );
  const [displayLoading, setDisplayLoading] = useState(true);

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<KdsDevice | null>(null);
  const [deviceName, setDeviceName] = useState("");
  const [stationId, setStationId] = useState("");
  const [saving, setSaving] = useState(false);
  const [deviceSaveError, setDeviceSaveError] = useState<string | null>(null);

  const [createStationOpen, setCreateStationOpen] = useState(false);
  const [newStationName, setNewStationName] = useState("");
  const [newStationColor, setNewStationColor] = useState("#94a3b8");
  const [savingStation, setSavingStation] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<KdsDevice | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const [savingDisplay, setSavingDisplay] = useState(false);

  const [pairingCreated, setPairingCreated] = useState<{
    code: string;
    deviceName: string;
  } | null>(null);
  const [pairingCopied, setPairingCopied] = useState(false);

  const [dashboardColorKeys, setDashboardColorKeys] = useState<
    Record<string, string>
  >({});

  /** Re-render status / “Xs ago” labels every second without extra Firestore reads. */
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => {
    if (!user) return;
    const unsub = onSnapshot(
      collection(db, STATIONS_COLLECTION),
      (snap) => {
        const list: KitchenStation[] = [];
        snap.forEach((d) => {
          list.push(parseStation(d.id, d.data() as Record<string, unknown>));
        });
        list.sort((a, b) =>
          a.name.localeCompare(b.name, undefined, { sensitivity: "base" })
        );
        setStations(list);
        setStationsLoading(false);
      },
      (err) => {
        console.error("[KDS] stations listener:", err);
        setStationsLoading(false);
      }
    );
    return () => unsub();
  }, [user]);

  useEffect(() => {
    if (!user) return;
    const unsub = onSnapshot(
      collection(db, KDS_DEVICES_COLLECTION),
      (snap) => {
        const list: KdsDevice[] = [];
        snap.forEach((d) => {
          const raw = d.data();
          if (shouldHideLegacyKdsAutoDevice(d.id, raw)) return;
          list.push(parseDevice(d.id, raw));
        });
        list.sort((a, b) => {
          const ta = a.createdAt?.getTime() ?? 0;
          const tb = b.createdAt?.getTime() ?? 0;
          return ta - tb;
        });
        setDevices(list);
        setLoading(false);
      },
      (err) => {
        console.error("[KDS] devices listener:", err);
        setLoading(false);
      }
    );
    return () => unsub();
  }, [user]);

  useEffect(() => {
    if (!user) return;
    const unsub = onSnapshot(
      doc(db, SETTINGS_COLLECTION, "dashboard"),
      (snap) => {
        const raw = snap.data()?.modules;
        setDashboardColorKeys(parseDashboardModuleColorKeys(raw));
      },
      () => setDashboardColorKeys({})
    );
    return () => unsub();
  }, [user]);

  useEffect(() => {
    if (!user) return;
    const unsub = onSnapshot(
      doc(db, SETTINGS_COLLECTION, KDS_SETTINGS_DOC),
      (snap) => {
        if (!snap.exists()) {
          setDisplaySettings(defaultDisplaySettings);
          setDisplayLoading(false);
          return;
        }
        const data = snap.data();
        const rawY = parseFirestoreTicketMinutes(
          data?.ticketYellowAfterMinutes,
          DEFAULT_TICKET_YELLOW_AFTER_MINUTES
        );
        const rawR = parseFirestoreTicketMinutes(
          data?.ticketRedAfterMinutes,
          DEFAULT_TICKET_RED_AFTER_MINUTES
        );
        const urgency = normalizeTicketUrgencyForState(rawY, rawR);
        setDisplaySettings({
          orderTypeColorsEnabled:
            data.orderTypeColorsEnabled !== false,
          gridColumns: 3,
          showTimers: data.showTimers !== false,
          ...urgency,
        });
        setDisplayLoading(false);
      },
      (err) => {
        console.error("[KDS] settings listener:", err);
        setDisplayLoading(false);
      }
    );
    return () => unsub();
  }, [user]);

  const openAdd = () => {
    setEditing(null);
    setDeviceName("");
    setDeviceSaveError(null);
    setStationId(stations[0]?.id ?? "");
    setModalOpen(true);
  };

  const openEdit = (d: KdsDevice) => {
    setDeviceSaveError(null);
    setEditing(d);
    setDeviceName(d.name);
    setStationId(
      d.stationId ||
        (d.legacyStationName
          ? stations.find(
              (s) =>
                s.name.toLowerCase() === d.legacyStationName!.toLowerCase()
            )?.id ?? ""
          : "")
    );
    setModalOpen(true);
  };

  const openCreateStation = () => {
    setNewStationName("");
    setNewStationColor("#94a3b8");
    setCreateStationOpen(true);
  };

  const handleSaveNewStation = async () => {
    const trimmed = newStationName.trim();
    if (!trimmed) return;
    setSavingStation(true);
    try {
      const colRef = collection(db, STATIONS_COLLECTION);
      const docRef = doc(colRef);
      let hex = newStationColor.trim().replace(/^#/, "");
      if (!/^[0-9A-Fa-f]{6}$/.test(hex)) hex = "94a3b8";
      await setDoc(docRef, {
        id: docRef.id,
        name: trimmed,
        color: `#${hex}`,
        createdAt: serverTimestamp(),
      });
      setStationId(docRef.id);
      setCreateStationOpen(false);
    } catch (err) {
      console.error("[KDS] create station failed:", err);
    } finally {
      setSavingStation(false);
    }
  };

  const handleSaveDevice = async () => {
    setDeviceSaveError(null);
    const trimmed = deviceName.trim();
    if (!trimmed) {
      setDeviceSaveError("Enter a device name.");
      return;
    }
    if (stations.length === 0) {
      setDeviceSaveError("Add at least one station first.");
      return;
    }
    if (!stationId) {
      setDeviceSaveError("Select a station from the list.");
      return;
    }
    setSaving(true);
    try {
      if (editing) {
        await updateDoc(doc(db, KDS_DEVICES_COLLECTION, editing.id), {
          name: trimmed,
          stationId,
          station: deleteField(),
          registeredFromWeb: true,
          updatedAt: serverTimestamp(),
        });
        setModalOpen(false);
      } else {
        const pairingCode = generatePairingCode();
        const colRef = collection(db, KDS_DEVICES_COLLECTION);
        const docRef = doc(colRef);
        await setDoc(docRef, {
          id: docRef.id,
          name: trimmed,
          stationId,
          pairingCode,
          isPaired: false,
          deviceType: "",
          isActive: true,
          registeredFromWeb: true,
          createdAt: serverTimestamp(),
        });
        setModalOpen(false);
        setPairingCreated({ code: pairingCode, deviceName: trimmed });
        setPairingCopied(false);
      }
    } catch (err) {
      console.error("[KDS] save device failed:", err);
      const msg =
        err instanceof FirebaseError
          ? `${err.code}: ${err.message}`
          : err instanceof Error
            ? err.message
            : "Could not save. Check your connection and Firestore rules.";
      setDeviceSaveError(msg);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    setDeleteError(null);
    try {
      await deleteDoc(doc(db, KDS_DEVICES_COLLECTION, deleteTarget.id));
      setDeleteTarget(null);
    } catch (err) {
      console.error("[KDS] delete device failed:", err);
      setDeleteError(
        "Could not delete this device. Check your connection and Firestore rules."
      );
    } finally {
      setDeleting(false);
    }
  };

  const persistDisplaySettings = useCallback(
    async (next: KdsDisplaySettings) => {
      if (!user) return;
      setSavingDisplay(true);
      try {
        await setDoc(
          doc(db, SETTINGS_COLLECTION, KDS_SETTINGS_DOC),
          {
            orderTypeColorsEnabled: next.orderTypeColorsEnabled,
            gridColumns: next.gridColumns,
            showTimers: next.showTimers,
            ticketYellowAfterMinutes: next.ticketYellowAfterMinutes,
            ticketRedAfterMinutes: next.ticketRedAfterMinutes,
            updatedAt: serverTimestamp(),
          },
          { merge: true }
        );
      } catch (err) {
        console.error("[KDS] display settings save failed:", err);
      } finally {
        setSavingDisplay(false);
      }
    },
    [user]
  );

  const updateDisplay = (partial: Partial<KdsDisplaySettings>) => {
    const merged = { ...displaySettings, ...partial };
    const urgency = normalizeTicketUrgencyForState(
      merged.ticketYellowAfterMinutes,
      merged.ticketRedAfterMinutes
    );
    const next = { ...merged, ...urgency };
    setDisplaySettings(next);
    void persistDisplaySettings(next);
  };

  const statusBadge = (live: LiveStatus) => {
    if (live === "online") {
      return {
        label: "Online",
        emoji: String.fromCodePoint(0x1f7e2),
        className:
          "bg-emerald-50 text-emerald-800 border-emerald-200 ring-1 ring-emerald-100",
      };
    }
    return {
      label: "Offline",
      emoji: String.fromCodePoint(0x1f534),
      className: "bg-red-50 text-red-800 border-red-200 ring-1 ring-red-100",
    };
  };

  return (
    <>
      <Header title="KDS" />
      {/* Left-aligned (no mx-auto) so content sits near the sidebar; max-w keeps 65/35 proportions unchanged */}
      <div className="p-4 sm:p-6">
        <div className="flex w-full max-w-[1800px] min-h-0 flex-col gap-6 lg:min-h-[calc(100vh-5.5rem)] lg:flex-row lg:items-stretch lg:gap-8">
        {/* LEFT — control panel: devices + display settings */}
        <div className="flex w-full min-w-0 flex-col gap-6 lg:w-[50%] lg:shrink-0">
        {/* KDS devices */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-5 sm:p-6">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-lg font-semibold text-slate-800">
                KDS devices
              </h2>
              <p className="text-sm text-slate-500 mt-0.5">
                Live status from <span className="font-medium">lastSeen</span>{" "}
                (real-time)
              </p>
            </div>
            <button
              type="button"
              onClick={openAdd}
              className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
            >
              <Plus size={16} />
              Add device
            </button>
          </div>

          {loading ? (
            <div className="flex justify-center py-12">
              <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
            </div>
          ) : devices.length === 0 ? (
            <div className="text-center py-12 text-slate-400 border border-dashed border-slate-200 rounded-xl">
              <Monitor size={40} className="mx-auto mb-3 text-slate-300" />
              <p className="font-medium text-slate-600">No devices yet</p>
              <p className="text-sm mt-1">Add a kitchen screen to get started</p>
            </div>
          ) : (
            <ul className="space-y-2">
              {devices.map((d) => {
                const live = getLiveStatus(d.lastSeen, nowMs);
                const badge = statusBadge(live);
                const ago = formatLastSeenAgo(d.lastSeen, nowMs);
                return (
                  <li
                    key={d.id}
                    className="flex flex-col sm:flex-row sm:items-center gap-3 p-4 rounded-xl border border-slate-100 bg-slate-50/50 hover:bg-slate-50 transition-colors"
                  >
                    <div className="flex items-start gap-3 flex-1 min-w-0">
                      <div className="p-2 rounded-lg bg-white border border-slate-100 shadow-sm shrink-0">
                        <Monitor size={20} className="text-slate-600" />
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className="font-semibold text-slate-800 truncate">
                          {d.name || "Unnamed device"}
                        </p>
                        <p className="text-sm text-slate-500">
                          Station:{" "}
                          <span className="text-slate-700">
                            {stationDisplayName(d, stations)}
                          </span>
                          {" · "}
                          <span className="text-slate-600">
                            Model:{" "}
                            <span className="text-slate-700 font-medium">
                              {d.deviceModel || d.deviceType || "—"}
                            </span>
                          </span>
                        </p>
                        {!d.isPaired && d.pairingCode && (
                          <p className="text-xs text-amber-900 bg-amber-50 border border-amber-100 rounded-lg px-2 py-1 mt-2 inline-block">
                            Waiting for tablet · code{" "}
                            <span className="font-mono font-bold tracking-widest">
                              {d.pairingCode}
                            </span>
                          </p>
                        )}
                        <p className="text-xs text-slate-500 mt-1">
                          Last seen:{" "}
                          <span className="font-medium text-slate-700">{ago}</span>
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-3 sm:shrink-0 pl-11 sm:pl-0">
                      <span
                        className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-semibold border ${badge.className}`}
                      >
                        <span aria-hidden>{badge.emoji}</span>
                        {badge.label}
                      </span>
                      <div className="flex items-center gap-1">
                        <button
                          type="button"
                          onClick={() => openEdit(d)}
                          className="p-2 rounded-lg text-slate-500 hover:bg-white hover:text-blue-600 border border-transparent hover:border-slate-200 transition-all"
                          aria-label="Edit device"
                        >
                          <Pencil size={18} />
                        </button>
                        <button
                          type="button"
                          onClick={() => {
                            setDeleteError(null);
                            setDeleteTarget(d);
                          }}
                          className="p-2 rounded-lg text-slate-500 hover:bg-white hover:text-red-600 border border-transparent hover:border-slate-200 transition-all"
                          aria-label="Delete device"
                        >
                          <Trash2 size={18} />
                        </button>
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>

        {/* Display settings */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-5 sm:p-6">
          <div className="flex items-center gap-2 mb-1">
            <LayoutGrid size={20} className="text-slate-600" />
            <h2 className="text-lg font-semibold text-slate-800">
              Display settings
            </h2>
          </div>
          <p className="text-sm text-slate-500 mb-6">
            Optional preferences for KDS layout (Android app can read{" "}
            <code className="text-xs bg-slate-100 px-1 rounded">
              Settings/{KDS_SETTINGS_DOC}
            </code>
            ).
          </p>

          {displayLoading ? (
            <div className="flex justify-center py-8">
              <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
            </div>
          ) : (
            <div className="space-y-6">
              <label className="flex items-center justify-between gap-4 p-4 rounded-xl border border-slate-100 bg-slate-50/50 cursor-pointer">
                <div className="flex items-center gap-3">
                  <Palette size={20} className="text-slate-500" />
                  <div>
                    <p className="font-medium text-slate-800">
                      Order type colors
                    </p>
                    <p className="text-sm text-slate-500">
                      Use colored headers by order type (Dine-in, To-go, Bar)
                    </p>
                  </div>
                </div>
                <input
                  type="checkbox"
                  className="w-5 h-5 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                  checked={displaySettings.orderTypeColorsEnabled}
                  onChange={(e) =>
                    updateDisplay({
                      orderTypeColorsEnabled: e.target.checked,
                    })
                  }
                  disabled={savingDisplay}
                />
              </label>

              <label className="flex items-center justify-between gap-4 p-4 rounded-xl border border-slate-100 bg-slate-50/50 cursor-pointer">
                <div className="flex items-center gap-3">
                  <Timer size={20} className="text-slate-500" />
                  <div>
                    <p className="font-medium text-slate-800">Show timers</p>
                    <p className="text-sm text-slate-500">
                      Show elapsed time on tickets
                    </p>
                  </div>
                </div>
                <input
                  type="checkbox"
                  className="w-5 h-5 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                  checked={displaySettings.showTimers}
                  onChange={(e) =>
                    updateDisplay({ showTimers: e.target.checked })
                  }
                  disabled={savingDisplay}
                />
              </label>

              <div className="rounded-xl border border-slate-100 bg-slate-50/50 p-4">
                <div className="flex items-start gap-3">
                  <AlarmClock
                    size={20}
                    className="text-slate-500 shrink-0 mt-0.5"
                    aria-hidden
                  />
                  <div className="min-w-0 flex-1 space-y-3">
                    <div>
                      <p className="font-medium text-slate-800">
                        Ticket urgency (elapsed time)
                      </p>
                      <p className="text-sm text-slate-500 mt-0.5">
                        Incoming tickets start with a white body, then turn
                        yellow, then red. Red is never earlier than yellow.
                      </p>
                    </div>
                    <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
                      <div className="flex-1 min-w-0">
                        <label
                          htmlFor="kds-ticket-yellow-after"
                          className="block text-xs font-medium text-slate-600 mb-1"
                        >
                          Yellow after (minutes)
                        </label>
                        <input
                          id="kds-ticket-yellow-after"
                          type="number"
                          inputMode="numeric"
                          min={0}
                          max={MAX_TICKET_URGENCY_MINUTES}
                          className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:opacity-50"
                          value={displaySettings.ticketYellowAfterMinutes}
                          onChange={(e) => {
                            const t = e.target.value;
                            if (t === "") {
                              updateDisplay({
                                ticketYellowAfterMinutes: 0,
                              });
                              return;
                            }
                            const n = parseInt(t, 10);
                            if (!Number.isFinite(n)) return;
                            updateDisplay({ ticketYellowAfterMinutes: n });
                          }}
                          disabled={savingDisplay}
                        />
                      </div>
                      <div className="flex-1 min-w-0">
                        <label
                          htmlFor="kds-ticket-red-after"
                          className="block text-xs font-medium text-slate-600 mb-1"
                        >
                          Red after (minutes)
                        </label>
                        <input
                          id="kds-ticket-red-after"
                          type="number"
                          inputMode="numeric"
                          min={0}
                          max={MAX_TICKET_URGENCY_MINUTES}
                          className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:opacity-50"
                          value={displaySettings.ticketRedAfterMinutes}
                          onChange={(e) => {
                            const t = e.target.value;
                            if (t === "") {
                              updateDisplay({ ticketRedAfterMinutes: 0 });
                              return;
                            }
                            const n = parseInt(t, 10);
                            if (!Number.isFinite(n)) return;
                            updateDisplay({ ticketRedAfterMinutes: n });
                          }}
                          disabled={savingDisplay}
                        />
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
        </div>

        {/* RIGHT — live KDS preview, tablet-style */}
        <aside className="flex w-full min-w-0 flex-col lg:sticky lg:top-20 lg:w-[50%] lg:shrink-0">
          <div className="flex min-h-0 flex-1 flex-col rounded-2xl border border-slate-200/90 bg-gradient-to-b from-slate-50 via-white to-slate-50/80 p-4 shadow-sm sm:p-5">
            <div className="shrink-0 pb-3">
              <h2 className="text-lg font-semibold text-slate-800">
                Live preview
              </h2>
              <p className="mt-1 text-sm leading-relaxed text-slate-500">
                Sample tickets mirror your{" "}
                <span className="font-medium text-slate-600">Display settings</span>{" "}
                in real time.
              </p>
            </div>
            <div className="mt-1 flex min-h-0 flex-1 flex-col">
              <KdsPreview
                displaySettings={displaySettings}
                moduleColorKeys={dashboardColorKeys}
              />
            </div>
          </div>
        </aside>
        </div>
      </div>

      {/* Add / Edit modal */}
      {modalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40">
          <div
            className="bg-white rounded-2xl shadow-xl border border-slate-200 w-full max-w-md overflow-hidden"
            role="dialog"
            aria-modal="true"
            aria-labelledby="kds-device-modal-title"
          >
            <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100">
              <h3
                id="kds-device-modal-title"
                className="font-semibold text-slate-800"
              >
                {editing ? "Edit device" : "Add device"}
              </h3>
              <button
                type="button"
                onClick={() => {
                  setDeviceSaveError(null);
                  setModalOpen(false);
                }}
                className="p-1.5 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                aria-label="Close"
              >
                <X size={20} />
              </button>
            </div>
            <div className="p-5 space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Device name
                </label>
                <input
                  type="text"
                  value={deviceName}
                  onChange={(e) => {
                    setDeviceSaveError(null);
                    setDeviceName(e.target.value);
                  }}
                  placeholder="e.g. Kitchen Screen 1"
                  className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-slate-800 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Station
                </label>
                {stationsLoading ? (
                  <div className="flex justify-center py-6">
                    <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
                  </div>
                ) : stations.length === 0 ? (
                  <p className="text-sm text-amber-800 bg-amber-50 border border-amber-100 rounded-xl px-3 py-2.5">
                    No stations yet. Add one below to link this device.
                  </p>
                ) : (
                  <select
                    value={stationId}
                    onChange={(e) => {
                      setDeviceSaveError(null);
                      setStationId(e.target.value);
                    }}
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-slate-800 bg-white focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                  >
                    <option value="">Select station…</option>
                    {stations.map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.name}
                      </option>
                    ))}
                  </select>
                )}
                <button
                  type="button"
                  onClick={openCreateStation}
                  className="mt-2 w-full flex items-center justify-center gap-2 px-3 py-2 rounded-xl border border-dashed border-slate-300 text-sm font-medium text-blue-700 hover:bg-blue-50/80 transition-colors"
                >
                  <Plus size={16} />
                  Add new station
                </button>
              </div>
              {editing &&
                !editing.isPaired &&
                editing.pairingCode && (
                  <div className="rounded-xl border border-amber-100 bg-amber-50 px-3 py-2.5 text-sm text-amber-950">
                    <span className="font-medium">Pairing code: </span>
                    <span className="font-mono font-bold tracking-widest">
                      {editing.pairingCode}
                    </span>
                    <p className="text-xs text-amber-900/80 mt-1">
                      Enter on the tablet to complete setup.
                    </p>
                  </div>
                )}
            </div>
            {deviceSaveError && (
              <div className="px-5 pb-2">
                <p className="text-sm text-red-700 bg-red-50 border border-red-100 rounded-xl px-3 py-2">
                  {deviceSaveError}
                </p>
              </div>
            )}
            <div className="flex justify-end gap-2 px-5 py-4 bg-slate-50 border-t border-slate-100">
              <button
                type="button"
                onClick={() => {
                  setDeviceSaveError(null);
                  setModalOpen(false);
                }}
                className="px-4 py-2.5 rounded-xl text-sm font-medium text-slate-600 hover:bg-slate-200/80 transition-colors"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleSaveDevice}
                disabled={
                  saving ||
                  !deviceName.trim() ||
                  stationsLoading ||
                  stations.length === 0 ||
                  !stationId
                }
                className="px-4 py-2.5 rounded-xl text-sm font-medium bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                {saving ? "Saving…" : "Save"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Pairing code after new device */}
      {pairingCreated && (
        <div className="fixed inset-0 z-[55] flex items-center justify-center p-4 bg-black/40">
          <div
            className="bg-white rounded-2xl shadow-xl border border-slate-200 w-full max-w-md overflow-hidden"
            role="dialog"
            aria-modal="true"
            aria-labelledby="kds-pairing-reveal-title"
          >
            <div className="px-5 py-4 border-b border-slate-100">
              <h3
                id="kds-pairing-reveal-title"
                className="font-semibold text-slate-800"
              >
                Device created
              </h3>
              <p className="text-sm text-slate-600 mt-1">
                Enter this code on <strong>{pairingCreated.deviceName}</strong>{" "}
                (KDS app).
              </p>
            </div>
            <div className="p-5">
              <p className="text-xs font-medium text-slate-500 uppercase tracking-wide mb-2">
                Pairing code
              </p>
              <div className="flex items-center gap-2">
                <div className="flex-1 font-mono text-3xl font-bold tracking-[0.35em] text-center text-slate-900 bg-slate-50 border border-slate-200 rounded-xl py-4">
                  {pairingCreated.code}
                </div>
                <button
                  type="button"
                  onClick={async () => {
                    try {
                      await navigator.clipboard.writeText(pairingCreated.code);
                      setPairingCopied(true);
                      window.setTimeout(() => setPairingCopied(false), 2000);
                    } catch {
                      setPairingCopied(false);
                    }
                  }}
                  className="shrink-0 p-3 rounded-xl border border-slate-200 text-slate-600 hover:bg-slate-50"
                  aria-label="Copy pairing code"
                >
                  {pairingCopied ? (
                    <Check size={22} className="text-emerald-600" />
                  ) : (
                    <Copy size={22} />
                  )}
                </button>
              </div>
            </div>
            <div className="flex justify-end px-5 py-4 bg-slate-50 border-t border-slate-100">
              <button
                type="button"
                onClick={() => setPairingCreated(null)}
                className="px-4 py-2.5 rounded-xl text-sm font-medium bg-blue-600 text-white hover:bg-blue-700 transition-colors"
              >
                Done
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Create station (from device modal) */}
      {createStationOpen && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center p-4 bg-black/40">
          <div
            className="bg-white rounded-2xl shadow-xl border border-slate-200 w-full max-w-md overflow-hidden"
            role="dialog"
            aria-modal="true"
            aria-labelledby="kds-new-station-title"
          >
            <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100">
              <h3
                id="kds-new-station-title"
                className="font-semibold text-slate-800"
              >
                New station
              </h3>
              <button
                type="button"
                onClick={() => setCreateStationOpen(false)}
                className="p-1.5 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                aria-label="Close"
              >
                <X size={20} />
              </button>
            </div>
            <div className="p-5 space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Station name
                </label>
                <input
                  type="text"
                  value={newStationName}
                  onChange={(e) => setNewStationName(e.target.value)}
                  placeholder="e.g. Grill, Expo"
                  className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-slate-800 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Color <span className="font-normal text-slate-500">(optional)</span>
                </label>
                <div className="flex items-center gap-3">
                  <input
                    type="color"
                    value={
                      newStationColor.startsWith("#")
                        ? newStationColor
                        : `#${newStationColor}`
                    }
                    onChange={(e) => setNewStationColor(e.target.value)}
                    className="h-10 w-14 rounded-lg border border-slate-200 cursor-pointer bg-white shrink-0"
                    aria-label="Station color"
                  />
                  <input
                    type="text"
                    value={newStationColor}
                    onChange={(e) => setNewStationColor(e.target.value)}
                    placeholder="#94a3b8"
                    className="flex-1 min-w-0 px-3 py-2.5 rounded-xl border border-slate-200 text-slate-800 font-mono text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                  />
                </div>
              </div>
            </div>
            <div className="flex justify-end gap-2 px-5 py-4 bg-slate-50 border-t border-slate-100">
              <button
                type="button"
                onClick={() => setCreateStationOpen(false)}
                className="px-4 py-2.5 rounded-xl text-sm font-medium text-slate-600 hover:bg-slate-200/80 transition-colors"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleSaveNewStation}
                disabled={savingStation || !newStationName.trim()}
                className="px-4 py-2.5 rounded-xl text-sm font-medium bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                {savingStation ? "Saving…" : "Save station"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Delete confirm */}
      {deleteTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40">
          <div className="bg-white rounded-2xl shadow-xl border border-slate-200 w-full max-w-sm p-6">
            <h3 className="font-semibold text-slate-800">Remove device?</h3>
            <p className="text-sm text-slate-600 mt-2">
              Delete <strong>{deleteTarget.name}</strong> from KDS devices? This
              cannot be undone. The tablet will stop heartbeating and must pair
              again to reconnect.
            </p>
            {deleteError && (
              <p className="text-sm text-red-700 bg-red-50 border border-red-100 rounded-lg px-3 py-2 mt-3">
                {deleteError}
              </p>
            )}
            <div className="flex justify-end gap-2 mt-6">
              <button
                type="button"
                onClick={() => {
                  setDeleteTarget(null);
                  setDeleteError(null);
                }}
                className="px-4 py-2.5 rounded-xl text-sm font-medium text-slate-600 hover:bg-slate-100 transition-colors"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleDelete}
                disabled={deleting}
                className="px-4 py-2.5 rounded-xl text-sm font-medium bg-red-600 text-white hover:bg-red-700 disabled:opacity-50 transition-colors"
              >
                {deleting ? "Removing…" : "Delete"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
