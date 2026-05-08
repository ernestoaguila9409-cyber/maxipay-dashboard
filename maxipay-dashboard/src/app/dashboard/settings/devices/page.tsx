"use client";

import { useEffect, useState } from "react";
import { collection, onSnapshot } from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import {
  POS_DEVICES_COLLECTION,
  POS_DEVICE_ONLINE_THRESHOLD_MS,
} from "@/lib/posDevicesFirestore";
import { Smartphone, Circle } from "lucide-react";

type LiveStatus = "online" | "offline";

interface PosDeviceRow {
  id: string;
  platform: string;
  deviceModel: string;
  osVersion: string;
  appVersion: string;
  lastSeen: Date | null;
}

function parseFirestoreDate(value: unknown): Date | null {
  if (value && typeof (value as { toDate?: () => Date }).toDate === "function") {
    return (value as { toDate: () => Date }).toDate();
  }
  return null;
}

function getLiveStatus(lastSeen: Date | null, nowMs: number): LiveStatus {
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
  return {
    id,
    platform: String(data.platform ?? "").trim() || "—",
    deviceModel: String(data.deviceModel ?? "").trim() || "Unknown device",
    osVersion: String(data.osVersion ?? "").trim() || "—",
    appVersion: String(data.appVersion ?? "").trim() || "—",
    lastSeen: parseFirestoreDate(data.lastSeen),
  };
}

export default function PosDevicesSettingsPage() {
  const { user, loading: authLoading } = useAuth();
  const [devices, setDevices] = useState<PosDeviceRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());

  useEffect(() => {
    const t = setInterval(() => setNowMs(Date.now()), 5_000);
    return () => clearInterval(t);
  }, []);

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

  const onlineCount = devices.filter((d) => getLiveStatus(d.lastSeen, nowMs) === "online").length;

  return (
    <>
      <Header title="Devices" />
      <div className="p-6 space-y-6 max-w-5xl">
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6">
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
              project — devices appear here automatically.
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-slate-100 bg-slate-50/80">
                    <th className="px-4 py-3 font-semibold text-slate-600">Status</th>
                    <th className="px-4 py-3 font-semibold text-slate-600">Device</th>
                    <th className="px-4 py-3 font-semibold text-slate-600">OS</th>
                    <th className="px-4 py-3 font-semibold text-slate-600">App</th>
                    <th className="px-4 py-3 font-semibold text-slate-600">Last seen</th>
                  </tr>
                </thead>
                <tbody>
                  {devices.map((d) => {
                    const live = getLiveStatus(d.lastSeen, nowMs);
                    const ago = formatLastSeenAgo(d.lastSeen, nowMs);
                    return (
                      <tr key={d.id} className="border-b border-slate-50 hover:bg-slate-50/50">
                        <td className="px-4 py-3.5">
                          <span
                            className={
                              live === "online"
                                ? "inline-flex items-center gap-1.5 text-emerald-700 font-medium"
                                : "inline-flex items-center gap-1.5 text-slate-500"
                            }
                          >
                            <Circle
                              size={10}
                              className={
                                live === "online" ? "fill-emerald-500 text-emerald-500" : "fill-slate-300 text-slate-300"
                              }
                            />
                            {live === "online" ? "Online" : "Offline"}
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
                        <td className="px-4 py-3.5 text-slate-600">{d.osVersion}</td>
                        <td className="px-4 py-3.5 text-slate-600">{d.appVersion}</td>
                        <td className="px-4 py-3.5 text-slate-600">{ago}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </>
  );
}
