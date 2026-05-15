"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  addDoc,
  deleteDoc,
  deleteField,
  getDoc,
  onSnapshot,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
} from "firebase/firestore";
import { merchantCol, merchantDoc } from "@/lib/merchantFirestore";
import { useAuth } from "@/context/AuthContext";
import { useMerchantId } from "@/hooks/useMerchantId";
import Header from "@/components/Header";
import {
  Plus,
  X,
  AlertTriangle,
  CreditCard,
  Eye,
  EyeOff,
  Copy,
  Loader2,
  Smartphone,
  Monitor,
  Circle,
  Lock,
} from "lucide-react";
import {
  PAYMENT_PROVIDERS,
  PAYMENT_PROVIDER_IDS,
  type PaymentProviderId,
  type PaymentProviderCatalogEntry,
  type PaymentTerminalDoc,
  isSpinProvider,
} from "@/lib/paymentProviders";
import {
  POS_DEVICES_COLLECTION,
  POS_DEVICE_ONLINE_THRESHOLD_MS,
  DEVICE_ACTIVATIONS_COLLECTION,
  DEVICE_ACTIVATION_TTL_MS,
} from "@/lib/posDevicesFirestore";

// ── Types ──────────────────────────────────────────────────────────

type AppType = "pos" | "mobile";

interface TerminalRow extends PaymentTerminalDoc {
  id: string;
}

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

// ── Constants ──────────────────────────────────────────────────────

const PAYMENTS_COLLECTION = "payment_terminals";
const LEGACY_COLLECTION = "Terminals";
const POS_HEARTBEAT_STALE_MS = 120_000;

const SPIN_PROVIDER_IDS = PAYMENT_PROVIDER_IDS.filter((id) => isSpinProvider(id));

// ── Helpers ────────────────────────────────────────────────────────

function timestampToMillis(ts: unknown): number | null {
  if (ts == null) return null;
  if (ts instanceof Timestamp) return ts.toMillis();
  if (typeof ts === "object" && ts !== null && typeof (ts as Timestamp).toMillis === "function") {
    return (ts as Timestamp).toMillis();
  }
  return null;
}

function pinpadStatusLabel(
  posLastSeen: unknown,
  posConnectionStatus?: string,
): { text: string; dotClass: string } {
  const ms = timestampToMillis(posLastSeen);
  if (ms == null) return { text: "No signal", dotClass: "bg-slate-300" };
  const age = Date.now() - ms;
  if (age < POS_HEARTBEAT_STALE_MS && posConnectionStatus !== "OFFLINE")
    return { text: "Online", dotClass: "bg-emerald-500" };
  if (posConnectionStatus === "OFFLINE")
    return { text: "Offline", dotClass: "bg-rose-500" };
  return { text: "Stale", dotClass: "bg-amber-500" };
}

function parseFirestoreDate(value: unknown): Date | null {
  if (value && typeof (value as { toDate?: () => Date }).toDate === "function")
    return (value as { toDate: () => Date }).toDate();
  return null;
}

type LiveStatus = "online" | "offline" | "locked";
function getLiveStatus(lastSeen: Date | null, _nowMs: number, deactivated: boolean): LiveStatus {
  if (deactivated) return "locked";
  if (!lastSeen) return "offline";
  return Date.now() - lastSeen.getTime() < POS_DEVICE_ONLINE_THRESHOLD_MS ? "online" : "offline";
}

function formatLastSeenAgo(lastSeen: Date | null, nowMs: number): string {
  if (!lastSeen) return "Never";
  const sec = Math.floor((nowMs - lastSeen.getTime()) / 1000);
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

async function createDeviceActivationCode(merchantId: string): Promise<{ code: string; expiresAtMs: number }> {
  const ttl = DEVICE_ACTIVATION_TTL_MS;
  for (let attempt = 0; attempt < 12; attempt++) {
    const code = String(Math.floor(Math.random() * 1_000_000)).padStart(6, "0");
    const ref = merchantDoc(merchantId, DEVICE_ACTIVATIONS_COLLECTION, code);
    const existing = await getDoc(ref);
    if (existing.exists()) continue;
    const expiresAtMs = Date.now() + ttl;
    await setDoc(ref, { code, createdAt: serverTimestamp(), expiresAt: Timestamp.fromMillis(expiresAtMs), consumed: false });
    return { code, expiresAtMs };
  }
  throw new Error("Could not generate a unique code. Try again.");
}

async function deactivatePosDevice(merchantId: string, deviceId: string): Promise<void> {
  const ref = merchantDoc(merchantId, POS_DEVICES_COLLECTION, deviceId);
  await updateDoc(ref, { deactivated: true, deactivatedAt: serverTimestamp(), enrolledFromDashboard: false, activatedAt: deleteField() });
}

function formatCountdown(msRemaining: number): string {
  if (msRemaining <= 0) return "Expired";
  const sec = Math.ceil(msRemaining / 1000);
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  if (m <= 0) return `${s}s`;
  return `${m}m ${s.toString().padStart(2, "0")}s`;
}

// ── Page Component ─────────────────────────────────────────────────

export default function PaymentsAndDevicesPage() {
  const { user } = useAuth();
  const merchantId = useMerchantId();

  // ── Payment terminals state ──
  const [terminals, setTerminals] = useState<TerminalRow[]>([]);
  const [terminalsLoading, setTerminalsLoading] = useState(true);
  const [snapshotError, setSnapshotError] = useState<string | null>(null);

  // ── POS devices state ──
  const [devices, setDevices] = useState<PosDeviceRow[]>([]);
  const [devicesLoading, setDevicesLoading] = useState(true);

  // ── Shared UI state ──
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [error, setError] = useState<string | null>(null);

  // ── Add Device modal (step wizard) ──
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [addStep, setAddStep] = useState<"type" | "pinpad" | "credentials" | "code">("type");
  const [appType, setAppType] = useState<AppType | null>(null);
  const [providerId, setProviderId] = useState<PaymentProviderId>("SPIN_P");
  const [deviceModel, setDeviceModel] = useState("");
  const [terminalName, setTerminalName] = useState("");
  const [config, setConfig] = useState<Record<string, string>>({});
  const [showSecrets, setShowSecrets] = useState(false);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // ── Activation code state ──
  const [activationCode, setActivationCode] = useState<string | null>(null);
  const [activationExpiresAtMs, setActivationExpiresAtMs] = useState<number | null>(null);
  const [creatingCode, setCreatingCode] = useState(false);
  const [copyDone, setCopyDone] = useState(false);

  // ── Deactivate state ──
  const [confirmDeactivate, setConfirmDeactivate] = useState<PosDeviceRow | null>(null);
  const [deactivatingId, setDeactivatingId] = useState<string | null>(null);

  // ── Legacy sync ref ──
  const lastLegacySyncKey = useRef<string>("");

  const provider: PaymentProviderCatalogEntry = PAYMENT_PROVIDERS[providerId];
  const modalRemainingMs = activationExpiresAtMs != null ? activationExpiresAtMs - nowMs : 0;

  // ── Tick ──
  useEffect(() => {
    const t = setInterval(() => setNowMs(Date.now()), 5_000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    if (!addModalOpen || activationExpiresAtMs == null) return;
    const t = setInterval(() => setNowMs(Date.now()), 1_000);
    return () => clearInterval(t);
  }, [addModalOpen, activationExpiresAtMs]);

  // ── Firestore listeners: payment_terminals ──
  useEffect(() => {
    if (!user || !merchantId) { setTerminalsLoading(false); setTerminals([]); return; }
    setTerminalsLoading(true);
    const unsub = onSnapshot(
      merchantCol(merchantId, PAYMENTS_COLLECTION),
      (snap) => {
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
            capabilities: data.capabilities ?? PAYMENT_PROVIDERS[(data.provider ?? "SPIN_Z") as PaymentProviderId]?.capabilities ?? PAYMENT_PROVIDERS.SPIN_Z.capabilities,
            config: (data.config ?? {}) as Record<string, string>,
            legacyTerminalId: data.legacyTerminalId,
            posConnectionStatus: data.posConnectionStatus,
            posLastSeen: data.posLastSeen,
          });
        });
        list.sort((a, b) => a.name.localeCompare(b.name));
        setTerminals(list);
        setSnapshotError(null);
        setTerminalsLoading(false);
      },
      (err) => {
        console.error("[payment-terminals]", err);
        setSnapshotError((err as { message?: string })?.message ?? "Could not load payment terminals.");
        setTerminals([]);
        setTerminalsLoading(false);
      },
    );
    return () => unsub();
  }, [user, merchantId]);

  // ── Firestore listeners: PosDevices ──
  useEffect(() => {
    if (!user || !merchantId) { setDevicesLoading(false); return; }
    setDevicesLoading(true);
    const unsub = onSnapshot(
      merchantCol(merchantId, POS_DEVICES_COLLECTION),
      (snap) => {
        const rows = snap.docs
          .map((d) => parseDevice(d.id, d.data() as Record<string, unknown>))
          .filter((row) => !row.deactivated)
          .sort((a, b) => (b.lastSeen?.getTime() ?? 0) - (a.lastSeen?.getTime() ?? 0));
        setDevices(rows);
        setDevicesLoading(false);
      },
      (e) => { console.error(e); setDevicesLoading(false); },
    );
    return () => unsub();
  }, [user, merchantId]);

  // ── Legacy sync: keep Terminals.active in sync ──
  useEffect(() => {
    if (!user || terminalsLoading || terminals.length === 0) return;
    const key = terminals.filter((t) => t.legacyTerminalId?.trim()).map((t) => `${String(t.legacyTerminalId).trim()}:${t.active ? 1 : 0}`).sort().join("|");
    if (key === lastLegacySyncKey.current) return;
    lastLegacySyncKey.current = key;
    const run = async () => {
      for (const t of terminals) {
        const lid = t.legacyTerminalId?.trim();
        if (!lid) continue;
        try {
          const legRef = merchantDoc(merchantId, LEGACY_COLLECTION, lid);
          const legSnap = await getDoc(legRef);
          if (!legSnap.exists()) continue;
          const raw = legSnap.data()?.active;
          const legActive = typeof raw === "boolean" ? raw : raw === null || raw === undefined ? true : Boolean(raw);
          if (legActive !== t.active) await updateDoc(legRef, { active: t.active });
        } catch (e) { console.error("Sync legacy Terminals.active:", e); }
      }
    };
    void run();
  }, [user, terminalsLoading, terminals, merchantId]);

  // ── Listen for activation code consumed ──
  useEffect(() => {
    if (!addModalOpen || !activationCode) return;
    const ref = merchantDoc(merchantId, DEVICE_ACTIVATIONS_COLLECTION, activationCode);
    const unsub = onSnapshot(ref, (snap) => {
      if (!snap.exists()) return;
      if (snap.get("consumed") === true) resetAddModal();
    }, (e) => console.error("activation code listen:", e));
    return () => unsub();
  }, [addModalOpen, activationCode, merchantId]);

  // ── Resolve the first active terminal for the PIN pad column ──
  const activeTerminal = useMemo(() => terminals.find((t) => t.active) ?? null, [terminals]);

  // ── Add Device wizard helpers ──

  function resetAddModal() {
    setAddModalOpen(false);
    setAddStep("type");
    setAppType(null);
    setProviderId("SPIN_P");
    setDeviceModel("");
    setTerminalName("");
    setConfig({});
    setShowSecrets(false);
    setFormError(null);
    setSaving(false);
    setActivationCode(null);
    setActivationExpiresAtMs(null);
    setCopyDone(false);
    setCreatingCode(false);
  }

  function openAddModal() {
    resetAddModal();
    setAddModalOpen(true);
  }

  function handleAppTypeSelect(type: AppType) {
    setAppType(type);
    if (type === "pos") {
      setAddStep("pinpad");
    } else {
      generateActivationCode();
    }
  }

  function handlePinpadNext() {
    setAddStep("credentials");
  }

  function onProviderChange(next: PaymentProviderId) {
    setProviderId(next);
    const nextKeys = new Set(PAYMENT_PROVIDERS[next].credentialSchema.map((f) => f.key));
    setConfig((prev) => {
      const preserved: Record<string, string> = {};
      for (const k of Object.keys(prev)) {
        if (nextKeys.has(k)) preserved[k] = prev[k];
      }
      return preserved;
    });
  }

  async function handleSaveTerminalAndCode() {
    setFormError(null);
    const trimmedName = terminalName.trim();
    if (!trimmedName) { setFormError("Terminal name is required."); return; }
    for (const field of provider.credentialSchema) {
      if (field.required && !(config[field.key] ?? "").trim()) {
        setFormError(`${field.label} is required.`);
        return;
      }
    }
    setSaving(true);
    try {
      if (!merchantId) { setFormError("Missing merchant scope."); return; }
      await addDoc(merchantCol(merchantId, PAYMENTS_COLLECTION), {
        name: trimmedName,
        provider: providerId,
        deviceModel: deviceModel.trim(),
        active: true,
        baseUrl: provider.baseUrl,
        endpoints: provider.endpoints,
        capabilities: provider.capabilities,
        config: Object.fromEntries(Object.entries(config).map(([k, v]) => [k, String(v).trim()])),
        createdAt: Timestamp.now(),
        updatedAt: Timestamp.now(),
      });
      await generateActivationCode();
    } catch (err) {
      console.error("Failed to save terminal:", err);
      setFormError((err as Error)?.message ?? "Failed to save terminal.");
      setSaving(false);
    }
  }

  async function generateActivationCode() {
    setCreatingCode(true);
    setFormError(null);
    try {
      const { code, expiresAtMs } = await createDeviceActivationCode(merchantId);
      setActivationCode(code);
      setActivationExpiresAtMs(expiresAtMs);
      setAddStep("code");
    } catch (e) {
      console.error(e);
      setFormError(e instanceof Error ? e.message : "Could not create activation code");
    } finally {
      setCreatingCode(false);
      setSaving(false);
    }
  }

  const copyCode = useCallback(async () => {
    if (!activationCode) return;
    try {
      await navigator.clipboard.writeText(activationCode);
      setCopyDone(true);
      setTimeout(() => setCopyDone(false), 2000);
    } catch { setError("Could not copy to clipboard"); }
  }, [activationCode]);

  // ── Device deactivation ──

  const runDeactivate = useCallback(async (row: PosDeviceRow) => {
    const mid = merchantId.trim();
    if (!mid) { setError("Merchant is not loaded yet."); return; }
    setDeactivatingId(row.id);
    setError(null);
    try {
      await deactivatePosDevice(mid, row.id);
      setConfirmDeactivate(null);
    } catch (e) {
      console.error(e);
      setError(e instanceof Error ? e.message : "Could not deactivate device");
    } finally { setDeactivatingId(null); }
  }, [merchantId]);

  const hasSecretsVisible = useMemo(() => provider.credentialSchema.some((f) => f.secret), [provider]);
  const loading = terminalsLoading || devicesLoading;

  const onlineDevices = devices.filter((d) => getLiveStatus(d.lastSeen, nowMs, d.deactivated) === "online").length;

  // ── Render ──

  return (
    <>
      <Header title="Payments & Devices" />
      <div className="p-6 space-y-6">
        {snapshotError && (
          <div className="rounded-xl border border-red-200 bg-red-50 text-red-800 text-sm px-4 py-3">{snapshotError}</div>
        )}
        {error && (
          <div className="rounded-xl border border-red-200 bg-red-50 text-red-800 text-sm px-4 py-3">{error}</div>
        )}
        {!loading && user && !merchantId && (
          <div className="rounded-xl border border-amber-200 bg-amber-50 text-amber-950 text-sm px-4 py-3">
            <p className="font-medium">No merchant selected for this login.</p>
            <p className="mt-1 text-amber-900/90">
              Sign in with the <strong>store owner</strong> account, or use a platform admin when this Firebase project has <strong>exactly one</strong> merchant.
            </p>
          </div>
        )}

        {/* ─── Action bar ─── */}
        <div className="flex items-center justify-end">
          <button onClick={openAddModal} className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors">
            <Plus size={16} />
            Add Device
          </button>
        </div>

        {/* ─── Unified Devices table ─── */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 overflow-hidden">
          <div className="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
            <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wider flex items-center gap-2">
              <Smartphone size={16} /> Devices
            </h2>
            <span className="text-xs text-slate-400">
              <span className="font-semibold text-emerald-600">{onlineDevices}</span> online · <span className="font-semibold text-slate-700">{devices.length}</span> total
            </span>
          </div>

          {loading ? (
            <div className="flex items-center justify-center py-12">
              <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
            </div>
          ) : devices.length === 0 ? (
            <div className="p-10 text-center">
              <Smartphone size={36} className="mx-auto text-slate-300" />
              <p className="text-slate-400 text-lg mt-2">No devices have reported in yet</p>
              <p className="text-slate-400 text-sm mt-1">Use <strong>Add Device</strong> to link a terminal with a one-time activation code.</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-slate-100 bg-slate-50/80">
                    <th className="px-5 py-3 font-semibold text-slate-600">Status</th>
                    <th className="px-5 py-3 font-semibold text-slate-600">Device</th>
                    <th className="px-5 py-3 font-semibold text-slate-600">PIN pad</th>
                    <th className="px-5 py-3 font-semibold text-slate-600">OS</th>
                    <th className="px-5 py-3 font-semibold text-slate-600">App</th>
                    <th className="px-5 py-3 font-semibold text-slate-600">Last seen</th>
                    <th className="px-5 py-3 font-semibold text-slate-600 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {devices.map((d) => {
                    const live = getLiveStatus(d.lastSeen, nowMs, d.deactivated);
                    const ago = formatLastSeenAgo(d.lastSeen, nowMs);

                    const isP8 = d.deviceModel.toLowerCase().includes("p8");
                    const pinpad = isP8 ? null : activeTerminal;

                    return (
                      <tr key={d.id} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                        <td className="px-5 py-3.5">
                          <span className={live === "locked" ? "inline-flex items-center gap-1.5 text-amber-800 font-medium" : live === "online" ? "inline-flex items-center gap-1.5 text-emerald-700 font-medium" : "inline-flex items-center gap-1.5 text-slate-500"}>
                            {live === "locked" ? (
                              <><Lock size={12} className="text-amber-700" /> Locked</>
                            ) : (
                              <><Circle size={10} className={live === "online" ? "fill-emerald-500 text-emerald-500" : "fill-slate-300 text-slate-300"} /> {live === "online" ? "Online" : "Offline"}</>
                            )}
                          </span>
                        </td>
                        <td className="px-5 py-3.5">
                          <div className="font-medium text-slate-800">{d.deviceModel}</div>
                          <div className="text-xs text-slate-400 font-mono truncate max-w-[220px]" title={d.id}>
                            {d.id.length > 18 ? `${d.id.slice(0, 8)}…${d.id.slice(-6)}` : d.id}
                          </div>
                        </td>
                        <td className="px-5 py-3.5">
                          {isP8 ? (
                            <span className="text-xs text-slate-400 italic">DvPayLite (built-in)</span>
                          ) : pinpad ? (
                            <div className="flex items-center gap-1.5">
                              <span className={`w-2 h-2 rounded-full shrink-0 ${pinpadStatusLabel(pinpad.posLastSeen, pinpad.posConnectionStatus).dotClass}`} />
                              <div className="min-w-0">
                                <div className="text-sm font-medium text-slate-800">{pinpad.name}</div>
                                <div className="text-xs text-slate-500">{pinpad.deviceModel || pinpad.provider}</div>
                              </div>
                            </div>
                          ) : (
                            <span className="text-xs text-slate-400">No PIN pad configured</span>
                          )}
                        </td>
                        <td className="px-5 py-3.5 text-slate-600">{d.osVersion}</td>
                        <td className="px-5 py-3.5 text-slate-600">{d.appVersion}</td>
                        <td className="px-5 py-3.5 text-slate-600">{ago}</td>
                        <td className="px-5 py-3.5 text-right">
                          <button
                            type="button"
                            disabled={deactivatingId === d.id}
                            onClick={() => setConfirmDeactivate(d)}
                            className="rounded-lg border border-red-200 bg-white px-3 py-1.5 text-xs font-semibold text-red-700 hover:bg-red-50 disabled:opacity-45 disabled:pointer-events-none"
                          >
                            {deactivatingId === d.id ? "Working…" : "Deactivate"}
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

      {/* ══════════════════════════════════════════════════════════════
         ADD DEVICE WIZARD MODAL
         ══════════════════════════════════════════════════════════════ */}
      {addModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" onClick={() => !saving && !creatingCode && resetAddModal()} />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-lg mx-4 overflow-hidden max-h-[90vh] overflow-y-auto">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  {addStep === "type" && "Add Device"}
                  {addStep === "pinpad" && "Select PIN pad"}
                  {addStep === "credentials" && "Payment terminal details"}
                  {addStep === "code" && "Activation Code"}
                </h3>
                <button onClick={resetAddModal} disabled={saving || creatingCode} className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors">
                  <X size={18} />
                </button>
              </div>

              {/* ─── Step 1: Choose app type ─── */}
              {addStep === "type" && (
                <div className="space-y-3">
                  <p className="text-sm text-slate-500">What type of device are you adding?</p>
                  <button onClick={() => handleAppTypeSelect("pos")} className="w-full flex items-center gap-4 p-4 rounded-xl border-2 border-slate-200 hover:border-blue-400 hover:bg-blue-50/30 transition-all text-left">
                    <div className="p-3 rounded-xl bg-slate-50"><Monitor size={24} className="text-slate-600" /></div>
                    <div>
                      <div className="font-semibold text-slate-800">POS App</div>
                      <div className="text-sm text-slate-500">Android POS on a tablet (e.g. Landi C20 Pro) with an external PIN pad (e.g. Dejavoo P17)</div>
                    </div>
                  </button>
                  <button onClick={() => handleAppTypeSelect("mobile")} className="w-full flex items-center gap-4 p-4 rounded-xl border-2 border-slate-200 hover:border-blue-400 hover:bg-blue-50/30 transition-all text-left">
                    <div className="p-3 rounded-xl bg-slate-50"><Smartphone size={24} className="text-slate-600" /></div>
                    <div>
                      <div className="font-semibold text-slate-800">Mobile App</div>
                      <div className="text-sm text-slate-500">MaxiMobile on a Dejavoo P8 — payments handled via DvPayLite deeplink, no external PIN pad needed</div>
                    </div>
                  </button>
                </div>
              )}

              {/* ─── Step 2a: POS App → Choose PIN pad ─── */}
              {addStep === "pinpad" && (
                <div className="space-y-4">
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-1.5">Terminal Name</label>
                    <input type="text" value={terminalName} onChange={(e) => setTerminalName(e.target.value)} placeholder="e.g. Bar 1, Front register" className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20" />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-1.5">Payment Provider</label>
                    <select value={providerId} onChange={(e) => onProviderChange(e.target.value as PaymentProviderId)} className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 bg-white">
                      {SPIN_PROVIDER_IDS.map((pid) => (
                        <option key={pid} value={pid}>{PAYMENT_PROVIDERS[pid].displayName}</option>
                      ))}
                    </select>
                    <p className="mt-1.5 text-xs text-slate-500">{provider.description}</p>
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-slate-700 mb-1.5">PIN pad model</label>
                    <input type="text" value={deviceModel} onChange={(e) => setDeviceModel(e.target.value)} placeholder="e.g. Dejavoo P17, Z8" className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20" />
                  </div>
                  <div className="flex gap-3 pt-1">
                    <button onClick={() => setAddStep("type")} className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors">Back</button>
                    <button onClick={handlePinpadNext} className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors">Next</button>
                  </div>
                </div>
              )}

              {/* ─── Step 2b: POS App → Enter SPIn credentials ─── */}
              {addStep === "credentials" && (
                <div className="space-y-4">
                  <div className="pt-2 border-t border-slate-100">
                    <div className="flex items-center justify-between mb-2">
                      <h4 className="text-sm font-semibold text-slate-700">{provider.displayName} credentials</h4>
                      {hasSecretsVisible && (
                        <button type="button" onClick={() => setShowSecrets((v) => !v)} className="text-xs flex items-center gap-1 text-slate-500 hover:text-slate-700">
                          {showSecrets ? (<><EyeOff size={14} /> Hide secrets</>) : (<><Eye size={14} /> Show secrets</>)}
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
                              {field.label}{field.required && <span className="text-red-500 ml-0.5">*</span>}
                            </label>
                            <input type={isSecret ? "password" : "text"} value={value} placeholder={field.placeholder} onChange={(e) => setConfig((prev) => ({ ...prev, [field.key]: e.target.value }))} className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 font-mono" />
                            {field.helperText && <p className="mt-1 text-xs text-slate-500">{field.helperText}</p>}
                          </div>
                        );
                      })}
                    </div>
                  </div>
                  <div className="pt-2 border-t border-slate-100">
                    <p className="text-xs text-slate-500">Base URL: <span className="font-mono text-slate-700">{provider.baseUrl}</span></p>
                    <p className="text-xs text-slate-500 mt-0.5">Endpoints and capabilities for this provider will be saved with the terminal automatically.</p>
                  </div>
                  {formError && (
                    <div className="rounded-xl bg-red-50 border border-red-100 px-3 py-2 text-sm text-red-600 flex items-start gap-2">
                      <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" /><span>{formError}</span>
                    </div>
                  )}
                  <div className="flex gap-3 pt-1">
                    <button onClick={() => setAddStep("pinpad")} disabled={saving} className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50">Back</button>
                    <button onClick={handleSaveTerminalAndCode} disabled={saving || creatingCode} className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2">
                      {saving || creatingCode ? (<><div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" /> Saving…</>) : "Save & Get Code"}
                    </button>
                  </div>
                </div>
              )}

              {/* ─── Activation Code step ─── */}
              {addStep === "code" && activationCode && (
                <div className="space-y-4">
                  <p className="text-sm text-slate-500">
                    On the {appType === "mobile" ? "Dejavoo P8" : "POS tablet"}: <span className="font-medium text-slate-700">Configuration</span> → <span className="font-medium text-slate-700">Link device (activation code)</span>, then enter this code. Single use; expires in {Math.round(DEVICE_ACTIVATION_TTL_MS / 60_000)} minutes.
                  </p>
                  <div className="flex items-center justify-center">
                    <span className="text-4xl font-mono font-bold tracking-[0.25em] text-slate-900 bg-slate-50 px-6 py-4 rounded-xl border border-slate-200">{activationCode}</span>
                  </div>
                  <p className="text-center text-sm text-slate-500">
                    {modalRemainingMs > 0 ? <>Expires in {formatCountdown(modalRemainingMs)}</> : <span className="text-amber-700 font-medium">This code has expired — generate a new one.</span>}
                  </p>
                  {formError && (
                    <div className="rounded-xl bg-red-50 border border-red-100 px-3 py-2 text-sm text-red-600 flex items-start gap-2">
                      <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" /><span>{formError}</span>
                    </div>
                  )}
                  <div className="flex gap-3">
                    <button type="button" onClick={copyCode} className="flex-1 inline-flex items-center justify-center gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50">
                      <Copy size={16} /> {copyDone ? "Copied!" : "Copy code"}
                    </button>
                    <button type="button" onClick={resetAddModal} className="flex-1 rounded-xl bg-blue-600 text-white px-4 py-2.5 text-sm font-semibold hover:bg-blue-700">Done</button>
                  </div>
                </div>
              )}

              {addStep === "code" && !activationCode && creatingCode && (
                <div className="flex flex-col items-center gap-3 py-8">
                  <Loader2 size={32} className="animate-spin text-blue-600" />
                  <p className="text-sm text-slate-500">Generating activation code…</p>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ── Deactivate device confirm ── */}
      {confirmDeactivate && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40" role="dialog" aria-modal="true">
          <div className="bg-white rounded-2xl shadow-xl border border-slate-200 max-w-md w-full p-6">
            <h3 className="text-lg font-semibold text-slate-800">Deactivate this device?</h3>
            <p className="text-sm text-slate-600 mt-2">
              <span className="font-medium text-slate-800">{confirmDeactivate.deviceModel}</span> will be sent to the activation screen and must enter a new code before the POS can be used again.
            </p>
            <div className="mt-6 flex gap-2 justify-end">
              <button type="button" onClick={() => setConfirmDeactivate(null)} className="rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50">Cancel</button>
              <button type="button" disabled={deactivatingId != null} onClick={() => runDeactivate(confirmDeactivate)} className="rounded-xl bg-red-600 text-white px-4 py-2.5 text-sm font-semibold hover:bg-red-700 disabled:opacity-50">Deactivate</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
