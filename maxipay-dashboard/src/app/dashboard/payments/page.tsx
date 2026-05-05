"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import {
  collection,
  addDoc,
  updateDoc,
  deleteDoc,
  doc,
  onSnapshot,
  Timestamp,
  getDoc,
  getDocs,
  writeBatch,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import {
  Plus,
  Pencil,
  Trash2,
  X,
  AlertTriangle,
  ToggleLeft,
  ToggleRight,
  CreditCard,
  Eye,
  EyeOff,
  Download,
} from "lucide-react";
import {
  PAYMENT_PROVIDERS,
  PAYMENT_PROVIDER_IDS,
  type PaymentProviderId,
  type PaymentProviderCatalogEntry,
  type PaymentTerminalDoc,
} from "@/lib/paymentProviders";

interface TerminalRow extends PaymentTerminalDoc {
  id: string;
}

const PAYMENTS_COLLECTION = "payment_terminals";
const LEGACY_COLLECTION = "Terminals";

/** If the POS stops heartbeating, treat as offline after this many ms without posLastSeen. */
const POS_HEARTBEAT_STALE_MS = 120_000;

function timestampToMillis(ts: unknown): number | null {
  if (ts == null) return null;
  if (ts instanceof Timestamp) return ts.toMillis();
  if (
    typeof ts === "object" &&
    ts !== null &&
    typeof (ts as Timestamp).toMillis === "function"
  ) {
    return (ts as Timestamp).toMillis();
  }
  return null;
}

function formatAgo(ms: number): string {
  const sec = Math.max(0, Math.floor((Date.now() - ms) / 1000));
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 48) return `${hr}h ago`;
  return `${Math.floor(hr / 24)}d ago`;
}

function posReachabilityDisplay(
  posLastSeen: unknown,
  posConnectionStatus?: string
): { title: string; line: string; dotClass: string } {
  const ms = timestampToMillis(posLastSeen);
  if (ms == null) {
    return {
      title: "No signal",
      line: "POS not reporting yet",
      dotClass: "bg-slate-300",
    };
  }
  const age = Date.now() - ms;
  const fresh = age < POS_HEARTBEAT_STALE_MS;
  if (fresh && posConnectionStatus !== "OFFLINE") {
    return {
      title: "Online",
      line: `POS ${formatAgo(ms)}`,
      dotClass: "bg-emerald-500",
    };
  }
  if (posConnectionStatus === "OFFLINE") {
    return {
      title: "Offline",
      line: `Last POS OK ${formatAgo(ms)}`,
      dotClass: "bg-rose-500",
    };
  }
  return {
    title: "Stale",
    line: `Last POS OK ${formatAgo(ms)}`,
    dotClass: "bg-amber-500",
  };
}

export default function PaymentsPage() {
  const { user } = useAuth();

  const [terminals, setTerminals] = useState<TerminalRow[]>([]);
  const [loading, setLoading] = useState(true);

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<TerminalRow | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const [name, setName] = useState("");
  const [providerId, setProviderId] = useState<PaymentProviderId>("SPIN_Z");
  const [deviceModel, setDeviceModel] = useState<string>("");
  const [config, setConfig] = useState<Record<string, string>>({});
  const [showSecrets, setShowSecrets] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<TerminalRow | null>(null);
  const [deleting, setDeleting] = useState(false);

  const [migrationBusy, setMigrationBusy] = useState(false);
  const [migrationMsg, setMigrationMsg] = useState<string | null>(null);

  /** Fingerprint of linked legacy rows' desired active state — avoids redundant getDoc/update loops. */
  const lastLegacySyncKey = useRef<string>("");

  const provider: PaymentProviderCatalogEntry = PAYMENT_PROVIDERS[providerId];

  useEffect(() => {
    if (!user) return;
    const unsub = onSnapshot(collection(db, PAYMENTS_COLLECTION), (snap) => {
      const list: TerminalRow[] = [];
      snap.forEach((d) => {
        const data = d.data() as Partial<PaymentTerminalDoc>;
        list.push({
          id: d.id,
          name: data.name ?? "Unnamed terminal",
          provider: (data.provider ?? "SPIN_Z") as PaymentProviderId,
          deviceModel: data.deviceModel ?? "",
          active: data.active ?? true,
          baseUrl: data.baseUrl ?? "",
          endpoints: data.endpoints ?? {},
          capabilities:
            data.capabilities ?? PAYMENT_PROVIDERS[(data.provider ?? "SPIN_Z") as PaymentProviderId]?.capabilities ?? PAYMENT_PROVIDERS.SPIN_Z.capabilities,
          config: (data.config ?? {}) as Record<string, string>,
          legacyTerminalId: data.legacyTerminalId,
          posConnectionStatus: data.posConnectionStatus,
          posLastSeen: data.posLastSeen,
        });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setTerminals(list);
      setLoading(false);
    });
    return () => unsub();
  }, [user]);

  /** One-way: align legacy `Terminals.active` with dashboard `payment_terminals.active` (POS may still read legacy). */
  useEffect(() => {
    if (!user || loading || terminals.length === 0) return;
    const key = terminals
      .filter((t) => t.legacyTerminalId?.trim())
      .map((t) => `${String(t.legacyTerminalId).trim()}:${t.active ? 1 : 0}`)
      .sort()
      .join("|");
    if (key === lastLegacySyncKey.current) return;
    lastLegacySyncKey.current = key;

    const run = async () => {
      for (const t of terminals) {
        const lid = t.legacyTerminalId?.trim();
        if (!lid) continue;
        try {
          const legRef = doc(db, LEGACY_COLLECTION, lid);
          const legSnap = await getDoc(legRef);
          if (!legSnap.exists()) continue;
          const raw = legSnap.data()?.active;
          const legActive =
            typeof raw === "boolean"
              ? raw
              : raw === null || raw === undefined
                ? true
                : Boolean(raw);
          if (legActive !== t.active) {
            await updateDoc(legRef, { active: t.active });
          }
        } catch (e) {
          console.error("Sync legacy Terminals.active:", e);
        }
      }
    };
    void run();
  }, [user, loading, terminals]);

  const openAdd = () => {
    setEditing(null);
    setName("");
    setProviderId("SPIN_Z");
    setDeviceModel("");
    setConfig({});
    setShowSecrets(false);
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (t: TerminalRow) => {
    setEditing(t);
    setName(t.name);
    setProviderId(t.provider);
    setDeviceModel(t.deviceModel?.trim() ?? "");
    setConfig({ ...t.config });
    setShowSecrets(false);
    setFormError(null);
    setModalOpen(true);
  };

  const onProviderChange = (next: PaymentProviderId) => {
    setProviderId(next);
    const nextKeys = new Set(
      PAYMENT_PROVIDERS[next].credentialSchema.map((f) => f.key)
    );
    setConfig((prev) => {
      const preserved: Record<string, string> = {};
      for (const k of Object.keys(prev)) {
        if (nextKeys.has(k)) preserved[k] = prev[k];
      }
      return preserved;
    });
  };

  const handleSave = async () => {
    setFormError(null);
    const trimmedName = name.trim();
    if (!trimmedName) {
      setFormError("Terminal name is required.");
      return;
    }
    for (const field of provider.credentialSchema) {
      if (field.required && !(config[field.key] ?? "").trim()) {
        setFormError(`${field.label} is required.`);
        return;
      }
    }

    setSaving(true);
    try {
      const payload: Omit<PaymentTerminalDoc, "createdAt"> & {
        createdAt?: unknown;
        updatedAt: unknown;
      } = {
        name: trimmedName,
        provider: providerId,
        deviceModel: deviceModel.trim(),
        active: editing?.active ?? true,
        baseUrl: provider.baseUrl,
        endpoints: provider.endpoints,
        capabilities: provider.capabilities,
        config: Object.fromEntries(
          Object.entries(config).map(([k, v]) => [k, String(v).trim()])
        ),
        updatedAt: Timestamp.now(),
      };

      if (editing) {
        await updateDoc(doc(db, PAYMENTS_COLLECTION, editing.id), payload);
      } else {
        await addDoc(collection(db, PAYMENTS_COLLECTION), {
          ...payload,
          createdAt: Timestamp.now(),
        });
      }
      setModalOpen(false);
    } catch (err) {
      console.error("Failed to save terminal:", err);
      setFormError((err as Error)?.message ?? "Failed to save terminal.");
    } finally {
      setSaving(false);
    }
  };

  const handleToggle = async (t: TerminalRow) => {
    try {
      const next = !t.active;
      await updateDoc(doc(db, PAYMENTS_COLLECTION, t.id), {
        active: next,
        updatedAt: Timestamp.now(),
      });
      // Android still falls back to legacy `Terminals` when `payment_terminals` is empty or
      // not yet synced — mirror `active` so disabling in the dashboard blocks the POS either way.
      const legacyId = t.legacyTerminalId?.trim();
      if (legacyId) {
        try {
          await updateDoc(doc(db, LEGACY_COLLECTION, legacyId), {
            active: next,
          });
        } catch (legacyErr) {
          console.error("Failed to sync legacy Terminals.active:", legacyErr);
        }
      }
    } catch (err) {
      console.error("Failed to toggle terminal:", err);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteDoc(doc(db, PAYMENTS_COLLECTION, deleteTarget.id));
      setDeleteTarget(null);
    } catch (err) {
      console.error("Failed to delete terminal:", err);
    } finally {
      setDeleting(false);
    }
  };

  const runLegacyMigration = async () => {
    setMigrationBusy(true);
    setMigrationMsg(null);
    try {
      const [legacySnap, newSnap] = await Promise.all([
        getDocs(collection(db, LEGACY_COLLECTION)),
        getDocs(collection(db, PAYMENTS_COLLECTION)),
      ]);
      const alreadyMigrated = new Set<string>();
      newSnap.forEach((d) => {
        const data = d.data() as Partial<PaymentTerminalDoc>;
        if (data.legacyTerminalId) alreadyMigrated.add(data.legacyTerminalId);
      });

      if (legacySnap.empty) {
        setMigrationMsg("No legacy `Terminals` documents found.");
        return;
      }

      const batch = writeBatch(db);
      let queued = 0;
      legacySnap.forEach((legacyDoc) => {
        if (alreadyMigrated.has(legacyDoc.id)) return;
        const src = legacyDoc.data() as Record<string, unknown>;
        const newRef = doc(collection(db, PAYMENTS_COLLECTION));
        const payload: PaymentTerminalDoc = {
          name:
            (src.name as string) ||
            (src.terminalName as string) ||
            `Terminal ${legacyDoc.id.slice(0, 6)}`,
          provider: "SPIN_Z",
          deviceModel: ((src.deviceModel as string) || "").trim(),
          active: (src.active as boolean) ?? true,
          baseUrl: PAYMENT_PROVIDERS.SPIN_Z.baseUrl,
          endpoints: PAYMENT_PROVIDERS.SPIN_Z.endpoints,
          capabilities: PAYMENT_PROVIDERS.SPIN_Z.capabilities,
          config: {
            tpn: (src.tpn as string) || "",
            registerId: (src.registerId as string) || "",
            authKey: (src.authKey as string) || "",
          },
          legacyTerminalId: legacyDoc.id,
        };
        batch.set(newRef, {
          ...payload,
          createdAt: Timestamp.now(),
          updatedAt: Timestamp.now(),
        });
        queued++;
      });

      if (queued === 0) {
        setMigrationMsg(
          `Nothing to migrate — ${legacySnap.size} legacy terminal(s) already imported.`
        );
        return;
      }

      await batch.commit();
      setMigrationMsg(
        `Imported ${queued} terminal(s) from the legacy \`Terminals\` collection.`
      );
    } catch (err) {
      console.error("Legacy migration failed:", err);
      setMigrationMsg(
        `Migration failed: ${(err as Error)?.message ?? "unknown error"}`
      );
    } finally {
      setMigrationBusy(false);
    }
  };

  const hasSecretsVisible = useMemo(
    () => provider.credentialSchema.some((f) => f.secret),
    [provider]
  );

  return (
    <>
      <Header title="Payments" />
      <div className="p-6 space-y-6">
        <div className="flex items-center justify-between gap-4 flex-wrap">
          <div>
            {migrationMsg && (
              <p className="text-xs text-slate-500">{migrationMsg}</p>
            )}
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={runLegacyMigration}
              disabled={migrationBusy}
              className="flex items-center gap-2 px-3 py-2 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
              title="Copy legacy Terminals docs into payment_terminals"
            >
              <Download size={16} />
              {migrationBusy ? "Importing…" : "Import legacy"}
            </button>
            <button
              onClick={openAdd}
              className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
            >
              <Plus size={16} />
              Add Terminal
            </button>
          </div>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          </div>
        ) : terminals.length === 0 ? (
          <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-12 text-center">
            <CreditCard
              size={36}
              className="mx-auto text-slate-300"
            />
            <p className="text-slate-400 text-lg mt-2">No terminals configured</p>
            <p className="text-slate-400 text-sm mt-1">
              Add a payment terminal so the POS can process cards.
            </p>
          </div>
        ) : (
          <div className="bg-white rounded-2xl shadow-sm border border-slate-100 overflow-hidden">
            <table className="w-full">
              <thead>
                <tr className="border-b border-slate-100">
                  <th className="text-left text-xs font-medium text-slate-400 uppercase tracking-wider px-6 py-4">
                    Name
                  </th>
                  <th className="text-left text-xs font-medium text-slate-400 uppercase tracking-wider px-6 py-4 w-40">
                    Provider
                  </th>
                  <th className="text-left text-xs font-medium text-slate-400 uppercase tracking-wider px-6 py-4 w-32">
                    Device
                  </th>
                  <th className="text-left text-xs font-medium text-slate-400 uppercase tracking-wider px-6 py-4">
                    Identifier
                  </th>
                  <th className="text-left text-xs font-medium text-slate-400 uppercase tracking-wider px-6 py-4 w-44">
                    POS
                  </th>
                  <th className="text-center text-xs font-medium text-slate-400 uppercase tracking-wider px-6 py-4 w-24">
                    Enabled
                  </th>
                  <th className="text-right text-xs font-medium text-slate-400 uppercase tracking-wider px-6 py-4 w-28">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody>
                {terminals.map((t) => {
                  const providerEntry = PAYMENT_PROVIDERS[t.provider];
                  const identifier =
                    t.config?.tpn || t.config?.deviceId || "—";
                  const pos = posReachabilityDisplay(
                    t.posLastSeen,
                    t.posConnectionStatus
                  );
                  return (
                    <tr
                      key={t.id}
                      className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors group/row"
                    >
                      <td className="px-6 py-4">
                        <span
                          className={`text-sm font-medium ${
                            t.active ? "text-slate-800" : "text-slate-400"
                          }`}
                        >
                          {t.name}
                        </span>
                      </td>
                      <td className="px-6 py-4">
                        <span className="text-xs px-2 py-1 rounded-full font-medium bg-blue-50 text-blue-600">
                          {providerEntry?.displayName ?? t.provider}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-sm text-slate-600">
                        {t.deviceModel || "—"}
                      </td>
                      <td className="px-6 py-4 text-sm text-slate-600 font-mono">
                        {identifier}
                      </td>
                      <td className="px-6 py-4">
                        <div
                          className="flex items-start gap-2"
                          title="SPIn reachability as seen from a signed-in POS (heartbeat)."
                        >
                          <span
                            className={`mt-1.5 w-2 h-2 rounded-full shrink-0 ${pos.dotClass}`}
                          />
                          <div className="min-w-0">
                            <div className="text-sm font-medium text-slate-800">
                              {pos.title}
                            </div>
                            <div className="text-xs text-slate-500 truncate max-w-[11rem]">
                              {pos.line}
                            </div>
                          </div>
                        </div>
                      </td>
                      <td className="px-6 py-4 text-center">
                        <button
                          onClick={() => handleToggle(t)}
                          className="inline-flex items-center"
                          title={t.active ? "Disable" : "Enable"}
                        >
                          {t.active ? (
                            <ToggleRight size={28} className="text-emerald-500" />
                          ) : (
                            <ToggleLeft size={28} className="text-slate-300" />
                          )}
                        </button>
                      </td>
                      <td className="px-6 py-4 text-right">
                        <div className="flex items-center justify-end gap-1 opacity-0 group-hover/row:opacity-100 transition-opacity">
                          <button
                            onClick={() => openEdit(t)}
                            className="p-1.5 rounded-lg text-slate-400 hover:bg-blue-50 hover:text-blue-600 transition-colors"
                            title="Edit terminal"
                          >
                            <Pencil size={15} />
                          </button>
                          <button
                            onClick={() => setDeleteTarget(t)}
                            className="p-1.5 rounded-lg text-slate-400 hover:bg-red-50 hover:text-red-500 transition-colors"
                            title="Delete terminal"
                          >
                            <Trash2 size={15} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {modalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !saving && setModalOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-lg mx-4 overflow-hidden max-h-[90vh] overflow-y-auto">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  {editing ? "Edit Terminal" : "Add Terminal"}
                </h3>
                <button
                  onClick={() => setModalOpen(false)}
                  disabled={saving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Terminal Name
                  </label>
                  <input
                    type="text"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder="e.g. Bar 1, Dining register"
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Provider
                  </label>
                  <select
                    value={providerId}
                    onChange={(e) =>
                      onProviderChange(e.target.value as PaymentProviderId)
                    }
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white"
                  >
                    {PAYMENT_PROVIDER_IDS.map((pid) => (
                      <option key={pid} value={pid}>
                        {PAYMENT_PROVIDERS[pid].displayName}
                      </option>
                    ))}
                  </select>
                  <p className="mt-1.5 text-xs text-slate-500">
                    {provider.description}
                  </p>
                </div>

                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Device model
                  </label>
                  <input
                    type="text"
                    value={deviceModel}
                    onChange={(e) => setDeviceModel(e.target.value)}
                    placeholder="e.g. Dejavoo Z8, P17, QD4"
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                  <p className="mt-1.5 text-xs text-slate-500">
                    Enter any model name; it is stored as-is for your records and the POS.
                  </p>
                </div>

                <div className="pt-2 border-t border-slate-100">
                  <div className="flex items-center justify-between mb-2">
                    <h4 className="text-sm font-semibold text-slate-700">
                      {provider.displayName} credentials
                    </h4>
                    {hasSecretsVisible && (
                      <button
                        type="button"
                        onClick={() => setShowSecrets((v) => !v)}
                        className="text-xs flex items-center gap-1 text-slate-500 hover:text-slate-700"
                      >
                        {showSecrets ? (
                          <>
                            <EyeOff size={14} /> Hide secrets
                          </>
                        ) : (
                          <>
                            <Eye size={14} /> Show secrets
                          </>
                        )}
                      </button>
                    )}
                  </div>

                  <div className="space-y-3">
                    {provider.credentialSchema.map((field) => {
                      const value = config[field.key] ?? "";
                      const isSecret = field.secret && !showSecrets;
                      return (
                        <div key={field.key}>
                          <label className="block text-sm font-medium text-slate-700 mb-1.5">
                            {field.label}
                            {field.required && (
                              <span className="text-red-500 ml-0.5">*</span>
                            )}
                          </label>
                          <input
                            type={isSecret ? "password" : "text"}
                            value={value}
                            placeholder={field.placeholder}
                            onChange={(e) =>
                              setConfig((prev) => ({
                                ...prev,
                                [field.key]: e.target.value,
                              }))
                            }
                            className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 font-mono"
                          />
                          {field.helperText && (
                            <p className="mt-1 text-xs text-slate-500">
                              {field.helperText}
                            </p>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>

                <div className="pt-2 border-t border-slate-100">
                  <p className="text-xs text-slate-500">
                    Base URL:{" "}
                    <span className="font-mono text-slate-700">
                      {provider.baseUrl}
                    </span>
                  </p>
                  <p className="text-xs text-slate-500 mt-0.5">
                    Endpoints and capabilities for this provider will be saved
                    with the terminal automatically.
                  </p>
                </div>

                {formError && (
                  <div className="rounded-xl bg-red-50 border border-red-100 px-3 py-2 text-sm text-red-600 flex items-start gap-2">
                    <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" />
                    <span>{formError}</span>
                  </div>
                )}
              </div>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setModalOpen(false)}
                  disabled={saving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSave}
                  disabled={saving}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {saving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Saving…
                    </>
                  ) : editing ? (
                    "Save Changes"
                  ) : (
                    "Add Terminal"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {deleteTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !deleting && setDeleteTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex flex-col items-center gap-3">
                <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center">
                  <AlertTriangle size={24} className="text-red-500" />
                </div>
                <h3 className="text-lg font-semibold text-slate-800">
                  Delete Terminal
                </h3>
              </div>
              <p className="text-sm text-slate-500 text-center">
                Are you sure you want to delete{" "}
                <strong className="text-slate-700">{deleteTarget.name}</strong>?
                The POS using this terminal will stop processing cards until it
                selects a different one.
              </p>
              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setDeleteTarget(null)}
                  disabled={deleting}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleDelete}
                  disabled={deleting}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {deleting ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Deleting…
                    </>
                  ) : (
                    "Delete"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
