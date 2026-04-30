"use client";

import { useCallback, useEffect, useState } from "react";
import {
  collection,
  doc,
  onSnapshot,
  serverTimestamp,
  setDoc,
} from "firebase/firestore";
import { Loader2, Printer, Monitor } from "lucide-react";
import { db } from "@/firebase/firebaseConfig";
import {
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  type OnlineOrderingSettings,
} from "@/lib/onlineOrderingShared";
import {
  mapPrinterDocument,
  resolveStatus,
  type PrinterDocFields,
  type PrinterStatus,
} from "@/lib/printerStatusUtils";
import {
  KDS_DEVICES_COLLECTION,
  parseKdsDevicePickerRow,
  shouldHideLegacyKdsAutoDevice,
  type KdsDevicePickerRow,
} from "@/lib/kdsDeviceFirestore";

interface Props {
  settings: OnlineOrderingSettings;
}

function statusDot(s: PrinterStatus) {
  const color =
    s === "ONLINE"
      ? "bg-emerald-400"
      : s === "OFFLINE"
        ? "bg-red-400"
        : "bg-slate-300";
  return <span className={`inline-block w-2 h-2 rounded-full ${color}`} />;
}

export default function OnlineKitchenRoutingManager({ settings }: Props) {
  const [printers, setPrinters] = useState<PrinterDocFields[]>([]);
  const [kdsDevices, setKdsDevices] = useState<KdsDevicePickerRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let loadCount = 0;
    const tryDone = () => {
      loadCount++;
      if (loadCount >= 2) setLoading(false);
    };

    const unsubPrinters = onSnapshot(
      collection(db, "Printers"),
      (snap) => {
        const nowMs = Date.now();
        const rows: PrinterDocFields[] = [];
        snap.forEach((d) => {
          const mapped = mapPrinterDocument(
            d.id,
            d.data() as Record<string, unknown>,
          );
          const resolved = resolveStatus(mapped.rawStatus, mapped.lastSeenMs, nowMs);
          rows.push({ ...mapped, rawStatus: resolved });
        });
        rows.sort((a, b) =>
          a.name.localeCompare(b.name, undefined, { sensitivity: "base" }),
        );
        setPrinters(rows);
        tryDone();
      },
      () => tryDone(),
    );

    const unsubKds = onSnapshot(
      collection(db, KDS_DEVICES_COLLECTION),
      (snap) => {
        const rows: KdsDevicePickerRow[] = [];
        snap.forEach((d) => {
          const data = d.data() as Record<string, unknown>;
          if (shouldHideLegacyKdsAutoDevice(d.id, data)) return;
          const parsed = parseKdsDevicePickerRow(d.id, data);
          if (parsed.isActive) rows.push(parsed);
        });
        rows.sort((a, b) =>
          a.name.localeCompare(b.name, undefined, { sensitivity: "base" }),
        );
        setKdsDevices(rows);
        tryDone();
      },
      () => tryDone(),
    );

    return () => {
      unsubPrinters();
      unsubKds();
    };
  }, []);

  const persist = useCallback(
    async (patch: Partial<OnlineOrderingSettings>) => {
      setError(null);
      setSaving(true);
      try {
        await setDoc(
          doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC),
          { ...patch, updatedAt: serverTimestamp() },
          { merge: true },
        );
      } catch (e) {
        setError(e instanceof Error ? e.message : "Save failed");
      } finally {
        setSaving(false);
      }
    },
    [],
  );

  const togglePrinter = useCallback(
    (id: string) => {
      const cur = settings.onlineRoutingPrinterIds;
      const next = cur.includes(id)
        ? cur.filter((x) => x !== id)
        : [...cur, id];
      void persist({ onlineRoutingPrinterIds: next });
    },
    [settings.onlineRoutingPrinterIds, persist],
  );

  const toggleKds = useCallback(
    (id: string) => {
      const cur = settings.onlineRoutingKdsDeviceIds;
      const next = cur.includes(id)
        ? cur.filter((x) => x !== id)
        : [...cur, id];
      void persist({ onlineRoutingKdsDeviceIds: next });
    },
    [settings.onlineRoutingKdsDeviceIds, persist],
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16 text-slate-400">
        <Loader2 size={20} className="animate-spin" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-slate-900">
          Online order kitchen routing
        </h2>
        <p className="text-sm text-slate-500 mt-0.5">
          Select which printers and KDS stations should{" "}
          <strong>also</strong> receive online orders. Items still follow their
          normal label routing — these are additive.
        </p>
      </div>

      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
          {error}
        </p>
      )}

      {/* Printers */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5">
        <div className="flex items-center gap-2 mb-3">
          <Printer size={16} className="text-slate-400" />
          <h3 className="text-sm font-semibold text-slate-800">Printers</h3>
          {saving && (
            <Loader2 size={14} className="animate-spin text-slate-400" />
          )}
        </div>
        {printers.length === 0 ? (
          <p className="text-xs text-slate-400">
            No printers registered yet.
          </p>
        ) : (
          <ul className="space-y-1.5">
            {printers.map((p) => {
              const checked = settings.onlineRoutingPrinterIds.includes(p.id);
              return (
                <li key={p.id}>
                  <label className="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-slate-50 cursor-pointer transition-colors">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => togglePrinter(p.id)}
                      className="accent-blue-600 w-4 h-4"
                    />
                    {statusDot(p.rawStatus)}
                    <span className="text-sm text-slate-800 flex-1 min-w-0 truncate">
                      {p.name}
                    </span>
                    <span className="text-xs text-slate-400 tabular-nums shrink-0">
                      {p.ipAddress}
                    </span>
                  </label>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      {/* KDS devices */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5">
        <div className="flex items-center gap-2 mb-3">
          <Monitor size={16} className="text-slate-400" />
          <h3 className="text-sm font-semibold text-slate-800">
            KDS stations
          </h3>
          {saving && (
            <Loader2 size={14} className="animate-spin text-slate-400" />
          )}
        </div>
        {kdsDevices.length === 0 ? (
          <p className="text-xs text-slate-400">
            No active KDS devices found.
          </p>
        ) : (
          <ul className="space-y-1.5">
            {kdsDevices.map((d) => {
              const checked =
                settings.onlineRoutingKdsDeviceIds.includes(d.id);
              return (
                <li key={d.id}>
                  <label className="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-slate-50 cursor-pointer transition-colors">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleKds(d.id)}
                      className="accent-blue-600 w-4 h-4"
                    />
                    <span className="text-sm text-slate-800 flex-1 min-w-0 truncate">
                      {d.name}
                    </span>
                  </label>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
