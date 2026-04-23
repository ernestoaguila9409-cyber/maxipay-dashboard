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
import { ShoppingBag, ExternalLink, CreditCard, Store, Loader2 } from "lucide-react";

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
          ...next,
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
  const webhookUrl = origin ? `${origin}/api/online-ordering/stripe-webhook` : "";

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
                Guests use the same <strong className="text-slate-700">business name</strong> as{" "}
                <span className="text-slate-700">Settings → Business Information</span>
                {businessName ? (
                  <>
                    {" "}
                    (currently <span className="font-medium text-slate-800">&ldquo;{businessName}&rdquo;</span>
                    ).
                  </>
                ) : (
                  " (live from Firestore — updates automatically when you change it)."
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
              <p className="text-sm text-slate-500">When off, the public page shows &ldquo;not available&rdquo;.</p>
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
                <p className="font-medium text-slate-800">Pay at the store (Dejavoo / SPIn)</p>
                <p className="text-sm text-slate-500">
                  Customer pays when they arrive. Staff rings the order on the Z8 (or other SPIn terminal) as
                  today.
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
              <CreditCard size={18} className="text-slate-400 shrink-0 mt-0.5" />
              <div>
                <p className="font-medium text-slate-800">Pay in browser (Stripe)</p>
                <p className="text-sm text-slate-500">
                  Card checkout on the web. Requires server env{" "}
                  <code className="text-xs bg-slate-100 px-1 rounded">STRIPE_SECRET_KEY</code> and webhook
                  secret. This does not run through the Dejavoo; it complements pay-at-store.
                </p>
              </div>
            </div>
            <input
              type="checkbox"
              className="w-5 h-5 accent-blue-600"
              checked={settings.allowPayOnlineStripe}
              onChange={(e) => void persist({ ...settings, allowPayOnlineStripe: e.target.checked })}
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

        <div className="bg-amber-50 rounded-2xl border border-amber-100 p-6 space-y-2 text-sm text-amber-950">
          <p className="font-semibold">Stripe webhook (for &ldquo;Pay now&rdquo;)</p>
          <p>
            In the Stripe Dashboard, add an endpoint that points to:
            {webhookUrl ? (
              <span className="block font-mono text-xs mt-2 break-all bg-white/60 p-2 rounded border border-amber-200">
                {webhookUrl}
              </span>
            ) : (
              <span className="block mt-1">your-site-origin + /api/online-ordering/stripe-webhook</span>
            )}
          </p>
          <p>
            Listen for <code className="bg-white/60 px-1 rounded">checkout.session.completed</code>. Set{" "}
            <code className="bg-white/60 px-1 rounded">STRIPE_WEBHOOK_SECRET</code> on the server.
          </p>
          <p>
            For correct redirects after payment, set{" "}
            <code className="bg-white/60 px-1 rounded">NEXT_PUBLIC_APP_URL</code> to your live site URL (e.g.{" "}
            https://pos.yourdomain.com).
          </p>
        </div>
      </div>
    </>
  );
}
