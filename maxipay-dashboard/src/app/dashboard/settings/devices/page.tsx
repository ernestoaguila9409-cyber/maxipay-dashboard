"use client";

import { useCallback, useEffect, useState } from "react";
import {
  collection,
  deleteField,
  doc,
  getDoc,
  onSnapshot,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import {
  POS_DEVICES_COLLECTION,
  POS_DEVICE_ONLINE_THRESHOLD_MS,
  DEVICE_ACTIVATIONS_COLLECTION,
  DEVICE_ACTIVATION_TTL_MS,
} from "@/lib/posDevicesFirestore";
import { Smartphone, Circle, Plus, Copy, X, Loader2, Lock } from "lucide-react";

type LiveStatus = "online" | "offline" | "locked";

interface PosDeviceRow {
  id: string;
  platform: string;
  deviceModel: string;
  osVersion: string;
  appVersion: string;
  lastSeen: Date | null;
  enrolled: boolean;
  deactivated: boolean;
}

function parseFirestoreDate(value: unknown): Date | null {
  if (value && typeof (value as { toDate?: () => Date }).toDate === "function") {
    return (value as { toDate: () => Date }).toDate();
  }
  return null;
}

function getLiveStatus(
  lastSeen: Date | null,
  nowMs: number,
  deactivated: boolean
): LiveStatus {
  if (deactivated) return "locked";
  if (!lastSeen) return "offline";
  const age = nowMs - lastSeen.getTime();
  if (age < POS_DEVICE_ONLINE_THRESHOLD_MS) return "online";
  return "offline";
}

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

function parseDevice(id: string, data: Record<string, unknown>): PosDeviceRow {
  const activatedAt = parseFirestoreDate(data.activatedAt);
  const enrolledFlag = data.enrolledFromDashboard === true;
  const deactivated = data.deactivated === true;
  return {
    id,
    platform: String(data.platform ?? "").trim() || "—",
    deviceModel: String(data.deviceModel ?? "").trim() || "Unknown device",
    osVersion: String(data.osVersion ?? "").trim() || "—",
    appVersion: String(data.appVersion ?? "").trim() || "—",
    lastSeen: parseFirestoreDate(data.lastSeen),
    enrolled: !deactivated && (enrolledFlag || activatedAt != null),
    deactivated,
  };
}

async function createDeviceActivationCode(): Promise<{ code: string; expiresAtMs: number }> {
  const ttl = DEVICE_ACTIVATION_TTL_MS;
  for (let attempt = 0; attempt < 12; attempt++) {
    const code = String(Math.floor(Math.random() * 1_000_000)).padStart(6, "0");
    const ref = doc(db, DEVICE_ACTIVATIONS_COLLECTION, code);
    const existing = await getDoc(ref);
    if (existing.exists()) continue;
    const expiresAtMs = Date.now() + ttl;
    await setDoc(ref, {
      code,
      createdAt: serverTimestamp(),
      expiresAt: Timestamp.fromMillis(expiresAtMs),
      consumed: false,
    });
    return { code, expiresAtMs };
  }
  throw new Error("Could not generate a unique code. Try again.");
}

async function deactivatePosDevice(deviceId: string): Promise<void> {
  const ref = doc(db, POS_DEVICES_COLLECTION, deviceId);
  await updateDoc(ref, {
    deactivated: true,
    deactivatedAt: serverTimestamp(),
    enrolledFromDashboard: false,
    activatedAt: deleteField(),
  });
}

function formatCountdown(msRemaining: number): string {
  if (msRemaining <= 0) return "Expired";
  const sec = Math.ceil(msRemaining / 1000);
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  if (m <= 0) return `${s}s`;
  return `${m}m ${s.toString().padStart(2, "0")}s`;
}

export default function PosDevicesSettingsPage() {
  const { user, loading: authLoading } = useAuth();
  const [devices, setDevices] = useState<PosDeviceRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());

  const [modalOpen, setModalOpen] = useState(false);
  const [modalCode, setModalCode] = useState<string | null>(null);
  const [modalExpiresAtMs, setModalExpiresAtMs] = useState<number | null>(null);
  const [creatingCode, setCreatingCode] = useState(false);
  const [copyDone, setCopyDone] = useState(false);

  const [confirmDeactivate, setConfirmDeactivate] = useState<PosDeviceRow | null>(null);
  const [deactivatingId, setDeactivatingId] = useState<string | null>(null);

  useEffect(() => {
    const t = setInterval(() => setNowMs(Date.now()), 5_000);
    return () => clearInterval(t);
  }, []);

  const modalRemainingMs =
    modalExpiresAtMs != null ? modalExpiresAtMs - nowMs : 0;

  useEffect(() => {
    if (!modalOpen || modalExpiresAtMs == null) return;
    const t = setInterval(() => setNowMs(Date.now()), 1_000);
    return () => clearInterval(t);
  }, [modalOpen, modalExpiresAtMs]);

  useEffect(() => {
    if (authLoading || !user) {
      setLoading(authLoading);
      return;
    }

    const unsub = onSnapshot(
      collection(db, POS_DEVICES_COLLECTION),
      (snap) => {
        const rows = snap.docs
          .map((d) => parseDevice(d.id, d.data() as Record<string, unknown>))
          .filter((row) => !row.deactivated)
          .sort((a, b) => {
            const ta = a.lastSeen?.getTime() ?? 0;
            const tb = b.lastSeen?.getTime() ?? 0;
            return tb - ta;
          });
        setDevices(rows);
        setError(null);
        setLoading(false);
      },
      (e) => {
        console.error(e);
        setError(e.message || "Could not load devices");
        setLoading(false);
      }
    );

    return () => unsub();
  }, [user, authLoading]);

  const handleAddDevice = useCallback(async () => {
    setCreatingCode(true);
    setError(null);
    setCopyDone(false);
    try {
      const { code, expiresAtMs } = await createDeviceActivationCode();
      setModalCode(code);
      setModalExpiresAtMs(expiresAtMs);
      setModalOpen(true);
    } catch (e) {
      console.error(e);
      setError(e instanceof Error ? e.message : "Could not create activation code");
    } finally {
      setCreatingCode(false);
    }
  }, []);

  const copyCode = useCallback(async () => {
    if (!modalCode) return;
    try {
      await navigator.clipboard.writeText(modalCode);
      setCopyDone(true);
      setTimeout(() => setCopyDone(false), 2000);
    } catch {
      setError("Could not copy to clipboard");
    }
  }, [modalCode]);

  const closeModal = useCallback(() => {
    setModalOpen(false);
    setModalCode(null);
    setModalExpiresAtMs(null);
    setCopyDone(false);
  }, []);

  const onlineCount = devices.filter(
    (d) => getLiveStatus(d.lastSeen, nowMs, d.deactivated) === "online"
  ).length;

  const runDeactivate = useCallback(async (row: PosDeviceRow) => {
    setDeactivatingId(row.id);
    setError(null);
    try {
      await deactivatePosDevice(row.id);
      setConfirmDeactivate(null);
    } catch (e) {
      console.error(e);
      setError(e instanceof Error ? e.message : "Could not deactivate device");
    } finally {
      setDeactivatingId(null);
    }
  }, []);

  return (
    <>
      <Header title="Devices" />
      <div className="p-6 space-y-6 max-w-5xl">
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6">
          <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4">
            <div className="flex items-start gap-4">
              <div className="p-3 rounded-xl bg-slate-50">
                <Smartphone size={24} className="text-slate-600" />
              </div>
              <div>
                <h2 className="text-lg font-semibold text-slate-800">POS devices</h2>
                <p className="text-sm text-slate-500 mt-1">
                  Tablets and phones running the POS app send a heartbeat while the app is open in the
                  foreground. A device shows <span className="font-medium text-slate-700">Online</span>{" "}
                  when the last heartbeat was within{" "}
                  {Math.round(POS_DEVICE_ONLINE_THRESHOLD_MS / 60_000)} minutes.
                </p>
                <p className="text-sm text-slate-600 mt-3">
                  <span className="font-semibold text-emerald-600">{onlineCount}</span> online
                  <span className="text-slate-400 mx-2">·</span>
                  <span className="font-semibold text-slate-700">{devices.length}</span> total seen
                </p>
              </div>
            </div>
            <button
              type="button"
              onClick={handleAddDevice}
              disabled={creatingCode || authLoading || !user}
              className="inline-flex items-center justify-center gap-2 rounded-xl bg-blue-600 text-white px-4 py-2.5 text-sm font-semibold shadow-sm hover:bg-blue-700 disabled:opacity-50 disabled:pointer-events-none shrink-0"
            >
              {creatingCode ? (
                <Loader2 size={18} className="animate-spin" />
              ) : (
                <Plus size={18} />
              )}
              Add device
            </button>
          </div>
        </div>

        {error && (
          <div className="rounded-xl border border-red-200 bg-red-50 text-red-800 text-sm px-4 py-3">
            {error}
          </div>
        )}

        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 overflow-hidden">
          {loading ? (
            <div className="p-10 text-center text-slate-500 text-sm">Loading devices…</div>
          ) : devices.length === 0 ? (
            <div className="p-10 text-center text-slate-500 text-sm">
              No devices have reported in yet. Open the POS app on a terminal (and sign in) with this
              project — devices appear here automatically. Use <strong>Add device</strong> to link a
              terminal with a one-time code (Configuration → Link device on the POS).
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-slate-100 bg-slate-50/80">
                    <th className="px-4 py-3 font-semibold text-slate-600">Status</th>
                    <th className="px-4 py-3 font-semibold text-slate-600">Device</th>
                    <th className="px-4 py-3 font-semibold text-slate-600">Enrolled</th>
                    <th className="px-4 py-3 font-semibold text-slate-600">OS</th>
                    <th className="px-4 py-3 font-semibold text-slate-600">App</th>
                    <th className="px-4 py-3 font-semibold text-slate-600">Last seen</th>
                    <th className="px-4 py-3 font-semibold text-slate-600 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {devices.map((d) => {
                    const live = getLiveStatus(d.lastSeen, nowMs, d.deactivated);
                    const ago = formatLastSeenAgo(d.lastSeen, nowMs);
                    return (
                      <tr key={d.id} className="border-b border-slate-50 hover:bg-slate-50/50">
                        <td className="px-4 py-3.5">
                          <span
                            className={
                              live === "locked"
                                ? "inline-flex items-center gap-1.5 text-amber-800 font-medium"
                                : live === "online"
                                  ? "inline-flex items-center gap-1.5 text-emerald-700 font-medium"
                                  : "inline-flex items-center gap-1.5 text-slate-500"
                            }
                          >
                            {live === "locked" ? (
                              <>
                                <Lock size={12} className="text-amber-700" />
                                Locked
                              </>
                            ) : (
                              <>
                                <Circle
                                  size={10}
                                  className={
                                    live === "online"
                                      ? "fill-emerald-500 text-emerald-500"
                                      : "fill-slate-300 text-slate-300"
                                  }
                                />
                                {live === "online" ? "Online" : "Offline"}
                              </>
                            )}
                          </span>
                        </td>
                        <td className="px-4 py-3.5">
                          <div className="font-medium text-slate-800">{d.deviceModel}</div>
                          <div className="text-xs text-slate-400 font-mono truncate max-w-[220px]" title={d.id}>
                            {d.id.length > 18
                              ? `${d.id.slice(0, 8)}…${d.id.slice(-6)}`
                              : d.id}
                          </div>
                        </td>
                        <td className="px-4 py-3.5">
                          {d.enrolled ? (
                            <span className="text-emerald-700 font-medium">Yes</span>
                          ) : (
                            <span className="text-slate-400">—</span>
                          )}
                        </td>
                        <td className="px-4 py-3.5 text-slate-600">{d.osVersion}</td>
                        <td className="px-4 py-3.5 text-slate-600">{d.appVersion}</td>
                        <td className="px-4 py-3.5 text-slate-600">{ago}</td>
                        <td className="px-4 py-3.5 text-right">
                          <button
                            type="button"
                            disabled={deactivatingId === d.id}
                            onClick={() => setConfirmDeactivate(d)}
                            className="rounded-lg border border-red-200 bg-white px-3 py-1.5 text-xs font-semibold text-red-700 hover:bg-red-50 disabled:opacity-45 disabled:pointer-events-none"
                          >
                            {deactivatingId === d.id ? "Working…" : "Deactivation"}
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {confirmDeactivate && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40"
          role="dialog"
          aria-modal="true"
          aria-labelledby="deactivate-title"
        >
          <div className="bg-white rounded-2xl shadow-xl border border-slate-200 max-w-md w-full p-6">
            <h3 id="deactivate-title" className="text-lg font-semibold text-slate-800">
              Deactivate this device?
            </h3>
            <p className="text-sm text-slate-600 mt-2">
              <span className="font-medium text-slate-800">{confirmDeactivate.deviceModel}</span> will
              be sent to the activation screen and must enter a new code from{" "}
              <strong>Add device</strong> before the POS can be used again.
            </p>
            <div className="mt-6 flex gap-2 justify-end">
              <button
                type="button"
                onClick={() => setConfirmDeactivate(null)}
                className="rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={deactivatingId != null}
                onClick={() => runDeactivate(confirmDeactivate)}
                className="rounded-xl bg-red-600 text-white px-4 py-2.5 text-sm font-semibold hover:bg-red-700 disabled:opacity-50"
              >
                Deactivate
              </button>
            </div>
          </div>
        </div>
      )}

      {modalOpen && modalCode && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40"
          role="dialog"
          aria-modal="true"
          aria-labelledby="activation-code-title"
        >
          <div className="bg-white rounded-2xl shadow-xl border border-slate-200 max-w-md w-full p-6 relative">
            <button
              type="button"
              onClick={closeModal}
              className="absolute top-4 right-4 p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600"
              aria-label="Close"
            >
              <X size={20} />
            </button>
            <h3 id="activation-code-title" className="text-lg font-semibold text-slate-800 pr-8">
              Activation code
            </h3>
            <p className="text-sm text-slate-500 mt-2">
              On the POS: <span className="font-medium text-slate-700">Configuration</span> →{" "}
              <span className="font-medium text-slate-700">Link device (activation code)</span>, then
              enter this code. Single use; expires in {Math.round(DEVICE_ACTIVATION_TTL_MS / 60_000)}{" "}
              minutes.
            </p>
            <div className="mt-6 flex items-center justify-center gap-3 flex-wrap">
              <span
                className="text-4xl font-mono font-bold tracking-[0.25em] text-slate-900 bg-slate-50 px-6 py-4 rounded-xl border border-slate-200"
                data-testid="activation-code-display"
              >
                {modalCode}
              </span>
            </div>
            <p className="text-center text-sm text-slate-500 mt-3">
              {modalRemainingMs > 0 ? (
                <>Expires in {formatCountdown(modalRemainingMs)}</>
              ) : (
                <span className="text-amber-700 font-medium">This code has expired — generate a new one.</span>
              )}
            </p>
            <div className="mt-6 flex gap-2">
              <button
                type="button"
                onClick={copyCode}
                className="flex-1 inline-flex items-center justify-center gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                <Copy size={16} />
                {copyDone ? "Copied!" : "Copy code"}
              </button>
              <button
                type="button"
                onClick={closeModal}
                className="flex-1 rounded-xl bg-blue-600 text-white px-4 py-2.5 text-sm font-semibold hover:bg-blue-700"
              >
                Done
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
