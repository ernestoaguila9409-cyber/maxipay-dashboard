"use client";

import { useEffect, useState } from "react";
import Header from "@/components/Header";
import { db } from "@/firebase/firebaseConfig";
import {
  DEFAULT_ONLINE_ORDERING_SETTINGS,
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  parseOnlineOrderingSettings,
  type OnlineOrderingSettings,
} from "@/lib/onlineOrderingShared";
import { doc, onSnapshot, setDoc, serverTimestamp } from "firebase/firestore";
import { ShoppingBag, ExternalLink, Store, Loader2, Smartphone } from "lucide-react";

type SaveState = "idle" | "saving" | "saved" | "error";

export default function OnlineOrderingSettingsPage() {
  const [settings, setSettings] = useState<OnlineOrderingSettings>(DEFAULT_ONLINE_ORDERING_SETTINGS);
  const [businessName, setBusinessName] = useState("");
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [origin, setOrigin] = useState("");

  useEffect(() => {
    setOrigin(typeof window !== "undefined" ? window.location.origin : "");
  }, []);

  useEffect(() => {
    const ooRef = doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC);
    const bizRef = doc(db, SETTINGS_COLLECTION, "businessInfo");
    const unsubs = [
      onSnapshot(ooRef, (snap) => {
        setSettings(parseOnlineOrderingSettings(snap.data() as Record<string, unknown> | undefined));
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

  const orderUrl = origin ? `${origin}/order` : "/order";

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

          <div className="rounded-xl bg-slate-50 border border-slate-100 p-4 flex flex-col sm:flex-row sm:items-center gap-3 justify-between">
            <div className="min-w-0">
              <p className="text-xs font-medium text-slate-500 uppercase tracking-wide">Public link</p>
              <p className="text-sm font-mono text-slate-800 break-all">{orderUrl}</p>
            </div>
            <a
              href={orderUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center justify-center gap-2 shrink-0 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium no-underline"
            >
              Open page
              <ExternalLink size={16} />
            </a>
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
                  Order is saved unpaid. Staff collects payment at pickup — card runs on the Dejavoo (SPIn) as
                  usual.
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
                  Customer chooses card at the restaurant. The website does not collect card numbers. The POS
                  tablet gets a notification to open checkout so payment runs on your Z8 through SPIn.
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
