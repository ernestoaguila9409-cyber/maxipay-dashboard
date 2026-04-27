"use client";

import { useEffect, useState } from "react";
import Header from "@/components/Header";
import { db } from "@/firebase/firebaseConfig";
import {
  DEFAULT_ONLINE_ORDERING_SETTINGS,
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  parseOnlineOrderingSettings,
  slugify,
  type OnlineOrderingSettings,
} from "@/lib/onlineOrderingShared";
import { doc, onSnapshot, setDoc, serverTimestamp } from "firebase/firestore";
import {
  ShoppingBag,
  ExternalLink,
  Store,
  Loader2,
  Link2,
  Copy,
  Check,
  CreditCard,
  Eye,
  EyeOff,
} from "lucide-react";

type SaveState = "idle" | "saving" | "saved" | "error";

export default function OnlineOrderingSettingsPage() {
  const [settings, setSettings] = useState<OnlineOrderingSettings>(DEFAULT_ONLINE_ORDERING_SETTINGS);
  const [businessName, setBusinessName] = useState("");
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [origin, setOrigin] = useState("");
  const [slugInput, setSlugInput] = useState("");
  const [slugSaving, setSlugSaving] = useState(false);
  const [slugCopied, setSlugCopied] = useState(false);
  const [hppTpnDraft, setHppTpnDraft] = useState("");
  const [hppAuthDraft, setHppAuthDraft] = useState("");
  const [hppCredsError, setHppCredsError] = useState<string | null>(null);
  const [showHppAuth, setShowHppAuth] = useState(false);
  const [hppCredsSaveState, setHppCredsSaveState] = useState<SaveState>("idle");

  useEffect(() => {
    setOrigin(typeof window !== "undefined" ? window.location.origin : "");
  }, []);

  useEffect(() => {
    const ooRef = doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC);
    const bizRef = doc(db, SETTINGS_COLLECTION, "businessInfo");
    const unsubs = [
      onSnapshot(ooRef, (snap) => {
        const parsed = parseOnlineOrderingSettings(snap.data() as Record<string, unknown> | undefined);
        setSettings(parsed);
        setSlugInput(parsed.onlineOrderingSlug);
      }),
      onSnapshot(bizRef, (snap) => {
        const n = snap.get("businessName");
        setBusinessName(typeof n === "string" ? n : "");
      }),
    ];
    return () => unsubs.forEach((u) => u());
  }, []);

  useEffect(() => {
    setHppTpnDraft(settings.iposHppTpn);
    setHppAuthDraft(settings.iposHppAuthToken);
  }, [settings.iposHppTpn, settings.iposHppAuthToken]);

  type FlagPatch = Partial<
    Pick<OnlineOrderingSettings, "enabled" | "allowPayInStore" | "allowPayOnlineHpp">
  >;

  const persist = async (flags: FlagPatch, credentials?: { tpn: string; authToken: string }) => {
    setSaveState("saving");
    try {
      const payload: Record<string, unknown> = {
        ...flags,
        updatedAt: serverTimestamp(),
      };
      if (credentials) {
        payload.iposHppTpn = credentials.tpn;
        payload.iposHppAuthToken = credentials.authToken;
      }
      await setDoc(
        doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC),
        payload,
        { merge: true }
      );
      setSaveState("saved");
      setTimeout(() => setSaveState("idle"), 2000);
    } catch (e) {
      console.error(e);
      setSaveState("error");
    }
  };

  const saveHppCredentialsOnly = async () => {
    setHppCredsError(null);
    const tpn = hppTpnDraft.trim();
    const authToken = hppAuthDraft.trim();
    if (!tpn || !authToken) {
      setHppCredsError("TPN and Auth token are required.");
      return;
    }
    setHppCredsSaveState("saving");
    try {
      await setDoc(
        doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC),
        {
          iposHppTpn: tpn,
          iposHppAuthToken: authToken,
          updatedAt: serverTimestamp(),
        },
        { merge: true }
      );
      setHppCredsSaveState("saved");
      setTimeout(() => setHppCredsSaveState("idle"), 2000);
    } catch (e) {
      console.error(e);
      setHppCredsSaveState("error");
    }
  };

  const saveSlug = async (raw: string) => {
    const cleaned = slugify(raw) || slugify(businessName);
    if (!cleaned) return;
    setSlugSaving(true);
    try {
      await setDoc(
        doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC),
        { onlineOrderingSlug: cleaned, updatedAt: serverTimestamp() },
        { merge: true }
      );
    } catch (e) {
      console.error(e);
    } finally {
      setSlugSaving(false);
    }
  };

  const effectiveSlug = settings.onlineOrderingSlug || slugify(businessName);
  const orderUrl = origin
    ? `${origin}/order${effectiveSlug ? `/${effectiveSlug}` : ""}`
    : `/order${effectiveSlug ? `/${effectiveSlug}` : ""}`;

  const copyUrl = async () => {
    try {
      await navigator.clipboard.writeText(orderUrl);
      setSlugCopied(true);
      setTimeout(() => setSlugCopied(false), 2000);
    } catch { /* clipboard may be blocked */ }
  };

  return (
    <>
      <Header title="Online ordering" />
      <div className="p-6 max-w-3xl space-y-6">
        <div className="bg-white rounded-2xl border border-slate-100 shadow-sm p-6 space-y-4">
          <div className="flex items-start gap-3">
            <div className="p-2 rounded-xl bg-violet-50 text-violet-700">
              <ShoppingBag size={22} />
            </div>
            <div>
              <h2 className="font-semibold text-slate-900">Customer ordering page</h2>
              <p className="text-sm text-slate-500 mt-1">
                Guests see the same <strong className="text-slate-700">business name</strong> as{" "}
                <span className="text-slate-700">Settings → Business Information</span>
                {businessName ? (
                  <>
                    {" "}
                    (currently <span className="font-medium text-slate-800">&ldquo;{businessName}&rdquo;</span>
                    ).
                  </>
                ) : (
                  " (live from Firestore — updates when you change it)."
                )}
              </p>
            </div>
          </div>

          <div className="rounded-xl bg-slate-50 border border-slate-100 p-4 space-y-3">
            <div className="flex flex-col sm:flex-row sm:items-center gap-3 justify-between">
              <div className="min-w-0">
                <p className="text-xs font-medium text-slate-500 uppercase tracking-wide">Public link</p>
                <p className="text-sm font-mono text-slate-800 break-all">{orderUrl}</p>
              </div>
              <div className="flex gap-2 shrink-0">
                <button
                  type="button"
                  onClick={copyUrl}
                  className="inline-flex items-center justify-center gap-2 px-3 py-2 rounded-lg border border-slate-200 bg-white text-sm font-medium text-slate-700 hover:bg-slate-50"
                >
                  {slugCopied ? <Check size={16} className="text-emerald-600" /> : <Copy size={16} />}
                  {slugCopied ? "Copied" : "Copy"}
                </button>
                <a
                  href={orderUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center justify-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium no-underline"
                >
                  Open page
                  <ExternalLink size={16} />
                </a>
              </div>
            </div>

            <div className="pt-2 border-t border-slate-200">
              <label className="block">
                <div className="flex items-center gap-2 mb-1.5">
                  <Link2 size={14} className="text-slate-400" />
                  <span className="text-xs font-medium text-slate-600">URL slug</span>
                </div>
                <div className="flex gap-2">
                  <div className="flex-1 flex items-center gap-0 rounded-lg border border-slate-200 bg-white overflow-hidden">
                    <span className="text-xs text-slate-400 pl-3 shrink-0 select-none">/order/</span>
                    <input
                      type="text"
                      value={slugInput}
                      onChange={(e) => setSlugInput(e.target.value)}
                      onBlur={() => void saveSlug(slugInput)}
                      placeholder={slugify(businessName) || "your-business"}
                      className="flex-1 py-2 pr-3 text-sm text-slate-800 outline-none bg-transparent placeholder:text-slate-300"
                    />
                  </div>
                  <button
                    type="button"
                    disabled={slugSaving}
                    onClick={() => void saveSlug(slugInput)}
                    className="px-3 py-2 rounded-lg border border-slate-200 bg-white text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                  >
                    {slugSaving ? "Saving…" : "Save"}
                  </button>
                </div>
                <p className="text-[11px] text-slate-400 mt-1.5">
                  Auto-generated from your business name. Edit to customize — only lowercase letters, numbers, and hyphens.
                </p>
              </label>
            </div>
          </div>
        </div>

        <div className="bg-white rounded-2xl border border-slate-100 shadow-sm p-6 space-y-5">
          <h3 className="font-semibold text-slate-900">Options</h3>

          <label className="flex items-center justify-between gap-4 cursor-pointer">
            <div>
              <p className="font-medium text-slate-800">Enable online ordering</p>
              <p className="text-sm text-slate-500">When off, the public page shows ordering is unavailable.</p>
            </div>
            <input
              type="checkbox"
              className="w-5 h-5 accent-blue-600"
              checked={settings.enabled}
              onChange={(e) => void persist({ enabled: e.target.checked })}
            />
          </label>

          <label className="flex items-center justify-between gap-4 cursor-pointer">
            <div className="flex gap-2">
              <Store size={18} className="text-slate-400 shrink-0 mt-0.5" />
              <div>
                <p className="font-medium text-slate-800">Pay when you pick up (cash or card later)</p>
                <p className="text-sm text-slate-500">
                  Order is saved unpaid. At pickup, staff runs the sale on the Dejavoo (SPIn) — chip/tap/swipe
                  or manual entry on the Z* if your terminal profile allows it.
                </p>
              </div>
            </div>
            <input
              type="checkbox"
              className="w-5 h-5 accent-blue-600"
              checked={settings.allowPayInStore}
              onChange={(e) => void persist({ allowPayInStore: e.target.checked })}
            />
          </label>

          <div className="space-y-3">
            <label className="flex items-center justify-between gap-4 cursor-pointer">
              <div className="flex gap-2">
                <CreditCard size={18} className="text-slate-400 shrink-0 mt-0.5" />
                <div>
                  <p className="font-medium text-slate-800">Pay online with card (iPOSpays)</p>
                  <p className="text-sm text-slate-500">
                    Customer pays securely on a hosted payment page before pickup. Card data never touches
                    your site — iPOSpays handles the payment and redirects the customer back.
                  </p>
                </div>
              </div>
              <input
                type="checkbox"
                className="w-5 h-5 accent-blue-600 shrink-0"
                checked={settings.allowPayOnlineHpp}
                onChange={(e) => {
                  const on = e.target.checked;
                  setHppCredsError(null);
                  if (on) {
                    const tpn = hppTpnDraft.trim();
                    const authToken = hppAuthDraft.trim();
                    if (!tpn || !authToken) {
                      setHppCredsError(
                        "Enter your iPOSpays TPN and Auth token below, then enable Pay online with card."
                      );
                      return;
                    }
                    void persist({ allowPayOnlineHpp: true }, { tpn, authToken });
                  } else {
                    void persist({ allowPayOnlineHpp: false });
                  }
                }}
              />
            </label>

            {hppCredsError && (
              <p className="text-sm text-amber-700 bg-amber-50 border border-amber-100 rounded-lg px-3 py-2">
                {hppCredsError}
              </p>
            )}

            {settings.allowPayOnlineHpp &&
              (!settings.iposHppTpn.trim() || !settings.iposHppAuthToken.trim()) && (
                <p className="text-sm text-red-600 bg-red-50 border border-red-100 rounded-lg px-3 py-2">
                  Pay online is on but credentials are missing in Firestore. Enter TPN and Auth token and
                  save.
                </p>
              )}

            <div className="ml-0 sm:ml-8 pl-0 sm:pl-4 sm:border-l-2 sm:border-slate-100 space-y-3">
              <p className="text-xs font-medium text-slate-500 uppercase tracking-wide">
                iPOSpays credentials (required for Pay online with card)
              </p>
              <label className="block space-y-1">
                <span className="text-xs text-slate-600">TPN (terminal processing number)</span>
                <input
                  type="text"
                  autoComplete="off"
                  value={hppTpnDraft}
                  onChange={(e) => {
                    setHppTpnDraft(e.target.value);
                    setHppCredsError(null);
                  }}
                  className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400"
                  placeholder="From your iPOSpays / CloudPOS portal"
                />
              </label>
              <label className="block space-y-1">
                <span className="text-xs text-slate-600">Auth token</span>
                <div className="flex gap-2">
                  <input
                    type={showHppAuth ? "text" : "password"}
                    autoComplete="new-password"
                    value={hppAuthDraft}
                    onChange={(e) => {
                      setHppAuthDraft(e.target.value);
                      setHppCredsError(null);
                    }}
                    className="flex-1 min-w-0 rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400"
                    placeholder="Merchant auth token from iPOSpays"
                  />
                  <button
                    type="button"
                    onClick={() => setShowHppAuth((v) => !v)}
                    className="shrink-0 px-3 rounded-lg border border-slate-200 bg-white text-slate-600 hover:bg-slate-50"
                    aria-label={showHppAuth ? "Hide auth token" : "Show auth token"}
                  >
                    {showHppAuth ? <EyeOff size={18} /> : <Eye size={18} />}
                  </button>
                </div>
              </label>
              <div className="flex flex-wrap items-center gap-3 pt-1">
                <button
                  type="button"
                  onClick={() => void saveHppCredentialsOnly()}
                  disabled={hppCredsSaveState === "saving"}
                  className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-slate-800 text-white text-sm font-medium hover:bg-slate-900 disabled:opacity-50"
                >
                  {hppCredsSaveState === "saving" && <Loader2 className="animate-spin" size={16} />}
                  Save credentials
                </button>
                {hppCredsSaveState === "saved" && (
                  <span className="text-sm text-emerald-600">Credentials saved.</span>
                )}
                {hppCredsSaveState === "error" && (
                  <span className="text-sm text-red-600">Could not save credentials.</span>
                )}
              </div>
              <p className="text-[11px] text-slate-400">
                Stored in your Firestore <code className="text-[10px]">Settings/onlineOrdering</code>. The
                public ordering page never receives these values. You can update the token anytime without
                changing server environment variables.
              </p>
            </div>
          </div>

          {saveState === "saving" && (
            <p className="text-sm text-slate-500 flex items-center gap-2">
              <Loader2 className="animate-spin" size={16} /> Saving…
            </p>
          )}
          {saveState === "saved" && <p className="text-sm text-emerald-600">Saved.</p>}
          {saveState === "error" && <p className="text-sm text-red-600">Save failed. Try again.</p>}
        </div>

        <div className="bg-slate-50 rounded-2xl border border-slate-200 p-6 text-sm text-slate-700 space-y-2">
          <p className="font-semibold text-slate-800">Deployment tip</p>
          <p>
            Set <code className="text-xs bg-white px-1 rounded border border-slate-200">NEXT_PUBLIC_APP_URL</code>{" "}
            to your live dashboard URL so links and redirects stay correct in production.
          </p>
        </div>
      </div>
    </>
  );
}
