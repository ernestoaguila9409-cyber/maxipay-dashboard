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
import { ShoppingBag, ExternalLink, Store, Loader2, Smartphone, Link2, Copy, Check } from "lucide-react";

type SaveState = "idle" | "saving" | "saved" | "error";

export default function OnlineOrderingSettingsPage() {
  const [settings, setSettings] = useState<OnlineOrderingSettings>(DEFAULT_ONLINE_ORDERING_SETTINGS);
  const [businessName, setBusinessName] = useState("");
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [origin, setOrigin] = useState("");
  const [slugInput, setSlugInput] = useState("");
  const [slugSaving, setSlugSaving] = useState(false);
  const [slugCopied, setSlugCopied] = useState(false);

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

  const persist = async (next: OnlineOrderingSettings) => {
    setSaveState("saving");
    try {
      await setDoc(
        doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC),
        {
          enabled: next.enabled,
          allowPayInStore: next.allowPayInStore,
          allowRequestTerminalFromWeb: next.allowRequestTerminalFromWeb,
          updatedAt: serverTimestamp(),
        },
        { merge: true }
      );
      setSaveState("saved");
      setTimeout(() => setSaveState("idle"), 2000);
    } catch (e) {
      console.error(e);
      setSaveState("error");
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
              onChange={(e) => void persist({ ...settings, enabled: e.target.checked })}
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
              onChange={(e) => void persist({ ...settings, allowPayInStore: e.target.checked })}
            />
          </label>

          <label className="flex items-center justify-between gap-4 cursor-pointer">
            <div className="flex gap-2">
              <Smartphone size={18} className="text-slate-400 shrink-0 mt-0.5" />
              <div>
                <p className="font-medium text-slate-800">Notify POS to take card on Dejavoo</p>
                <p className="text-sm text-slate-500">
                  No card data on the web. The POS gets a notification to open checkout; the sale still runs
                  on the Dejavoo through SPIn (insert/tap/swipe or{" "}
                  <strong className="font-medium text-slate-700">manual entry on the Z*</strong> when your
                  device and processor allow it).
                </p>
              </div>
            </div>
            <input
              type="checkbox"
              className="w-5 h-5 accent-blue-600"
              checked={settings.allowRequestTerminalFromWeb}
              onChange={(e) =>
                void persist({ ...settings, allowRequestTerminalFromWeb: e.target.checked })
              }
            />
          </label>

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
