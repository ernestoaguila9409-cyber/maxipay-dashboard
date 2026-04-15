"use client";

import { useEffect, useState, useCallback } from "react";
import {
  collection,
  addDoc,
  updateDoc,
  deleteDoc,
  doc,
  onSnapshot,
  serverTimestamp,
  setDoc,
  deleteField,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import {
  Plus,
  Pencil,
  Trash2,
  X,
  Monitor,
  LayoutGrid,
  Palette,
  Timer,
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
  isActive: boolean;
  createdAt: Date | null;
  /** Heartbeat from the KDS app (Firestore Timestamp). */
  lastSeen: Date | null;
}

type LiveStatus = "online" | "stale" | "offline";

const ONLINE_MAX_MS = 10_000;
const OFFLINE_MIN_MS = 30_000;

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
  if (age > OFFLINE_MIN_MS) return "offline";
  return "stale";
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
}

const defaultDisplaySettings: KdsDisplaySettings = {
  orderTypeColorsEnabled: true,
  gridColumns: 2,
  showTimers: true,
};

function parseDevice(id: string, data: Record<string, unknown>): KdsDevice {
  const createdAt = parseFirestoreDate(data.createdAt);
  const lastSeen =
    parseFirestoreDate(data.lastSeen) ?? createdAt;
  const stationId = String(data.stationId ?? "");
  const legacy =
    typeof data.station === "string" && data.station.trim() !== ""
      ? data.station.trim()
      : undefined;
  return {
    id,
    name: String(data.name ?? ""),
    stationId,
    legacyStationName: legacy,
    isActive: data.isActive !== false,
    createdAt,
    lastSeen,
  };
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

  const [createStationOpen, setCreateStationOpen] = useState(false);
  const [newStationName, setNewStationName] = useState("");
  const [newStationColor, setNewStationColor] = useState("#94a3b8");
  const [savingStation, setSavingStation] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<KdsDevice | null>(null);
  const [deleting, setDeleting] = useState(false);

  const [savingDisplay, setSavingDisplay] = useState(false);

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
          list.push(parseDevice(d.id, d.data()));
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
      doc(db, SETTINGS_COLLECTION, KDS_SETTINGS_DOC),
      (snap) => {
        if (!snap.exists()) {
          setDisplaySettings(defaultDisplaySettings);
          setDisplayLoading(false);
          return;
        }
        const data = snap.data();
        setDisplaySettings({
          orderTypeColorsEnabled:
            data.orderTypeColorsEnabled !== false,
          gridColumns: data.gridColumns === 3 ? 3 : 2,
          showTimers: data.showTimers !== false,
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
    setStationId(stations[0]?.id ?? "");
    setModalOpen(true);
  };

  const openEdit = (d: KdsDevice) => {
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
    const trimmed = deviceName.trim();
    if (!trimmed) return;
    if (stations.length === 0 || !stationId) return;
    setSaving(true);
    try {
      if (editing) {
        await updateDoc(doc(db, KDS_DEVICES_COLLECTION, editing.id), {
          name: trimmed,
          stationId,
          station: deleteField(),
          updatedAt: serverTimestamp(),
        });
      } else {
        await addDoc(collection(db, KDS_DEVICES_COLLECTION), {
          name: trimmed,
          stationId,
          isActive: true,
          createdAt: serverTimestamp(),
          lastSeen: serverTimestamp(),
        });
      }
      setModalOpen(false);
    } catch (err) {
      console.error("[KDS] save device failed:", err);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteDoc(doc(db, KDS_DEVICES_COLLECTION, deleteTarget.id));
    } catch (err) {
      console.error("[KDS] delete device failed:", err);
    } finally {
      setDeleting(false);
      setDeleteTarget(null);
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
    const next = { ...displaySettings, ...partial };
    setDisplaySettings(next);
    void persistDisplaySettings(next);
  };

  const statusBadge = (live: LiveStatus) => {
    switch (live) {
      case "online":
        return {
          label: "Online",
          emoji: String.fromCodePoint(0x1f7e2),
          className:
            "bg-emerald-50 text-emerald-800 border-emerald-200 ring-1 ring-emerald-100",
        };
      case "stale":
        return {
          label: "Idle",
          emoji: String.fromCodePoint(0x1f7e1),
          className:
            "bg-amber-50 text-amber-900 border-amber-200 ring-1 ring-amber-100",
        };
      default:
        return {
          label: "Offline",
          emoji: String.fromCodePoint(0x1f534),
          className: "bg-red-50 text-red-800 border-red-200 ring-1 ring-red-100",
        };
    }
  };

  return (
    <>
      <Header title="KDS" />
      <div className="p-6 space-y-6 max-w-4xl">
        <div className="bg-blue-50 border border-blue-100 rounded-2xl p-4 flex gap-3">
          <Monitor className="text-blue-600 shrink-0 mt-0.5" size={22} />
          <div className="text-sm text-slate-700">
            <p className="font-medium text-slate-800">Kitchen Display System</p>
            <p className="text-slate-600 mt-1">
              Register screens by station. Devices show <strong>Online</strong> when
              the app sends a recent <code className="text-xs bg-white/80 px-1 rounded border border-blue-100">lastSeen</code> heartbeat (under 10s). After 30s
              without a heartbeat they appear <strong>Offline</strong>. The KDS app
              should update its document in{" "}
              <code className="text-xs bg-white/80 px-1 rounded border border-blue-100">
                {KDS_DEVICES_COLLECTION}
              </code>
              .
            </p>
          </div>
        </div>

        {/* Section 1 — Devices */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6">
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
                        </p>
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
                          onClick={() => setDeleteTarget(d)}
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

        {/* Section 3 — Display settings */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6">
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

              <div className="p-4 rounded-xl border border-slate-100 bg-slate-50/50">
                <p className="font-medium text-slate-800 mb-3">Grid columns</p>
                <div className="flex gap-3">
                  {([2, 3] as const).map((n) => (
                    <button
                      key={n}
                      type="button"
                      onClick={() => updateDisplay({ gridColumns: n })}
                      disabled={savingDisplay}
                      className={`flex-1 py-2.5 rounded-xl text-sm font-medium border transition-all ${
                        displaySettings.gridColumns === n
                          ? "bg-blue-600 text-white border-blue-600"
                          : "bg-white text-slate-700 border-slate-200 hover:border-slate-300"
                      }`}
                    >
                      {n} columns
                    </button>
                  ))}
                </div>
              </div>

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
            </div>
          )}
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
                onClick={() => setModalOpen(false)}
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
                  onChange={(e) => setDeviceName(e.target.value)}
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
                    onChange={(e) => setStationId(e.target.value)}
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
            </div>
            <div className="flex justify-end gap-2 px-5 py-4 bg-slate-50 border-t border-slate-100">
              <button
                type="button"
                onClick={() => setModalOpen(false)}
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
              cannot be undone.
            </p>
            <div className="flex justify-end gap-2 mt-6">
              <button
                type="button"
                onClick={() => setDeleteTarget(null)}
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
