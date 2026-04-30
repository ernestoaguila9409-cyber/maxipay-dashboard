"use client";

import { useEffect, useState } from "react";
import { doc, onSnapshot } from "firebase/firestore";
import {
  ArrowLeft,
  ExternalLink,
  Image as ImageIcon,
  Settings as SettingsIcon,
  ShoppingBag,
  UtensilsCrossed,
} from "lucide-react";
import Link from "next/link";
import Header from "@/components/Header";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import {
  BUSINESS_INFO_DOC,
  DEFAULT_ONLINE_ORDERING_SETTINGS,
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  parseOnlineOrderingSettings,
  slugify,
  type OnlineOrderingSettings,
} from "@/lib/onlineOrderingShared";
import StorefrontPictureManager from "@/components/online-ordering-admin/StorefrontPictureManager";
import StorefrontSettingsManager from "@/components/online-ordering-admin/StorefrontSettingsManager";
import OnlineKitchenRoutingManager from "@/components/online-ordering-admin/OnlineKitchenRoutingManager";

type TabId = "picture" | "settings" | "kitchen";

interface TabSpec {
  id: TabId;
  label: string;
  icon: React.ComponentType<{ size?: number; className?: string }>;
}

const TABS: TabSpec[] = [
  { id: "picture", label: "Store Front picture", icon: ImageIcon },
  { id: "settings", label: "Settings", icon: SettingsIcon },
  { id: "kitchen", label: "Kitchen routing", icon: UtensilsCrossed },
];

export default function OnlineOrderingDashboardPage() {
  const { user } = useAuth();
  const [activeTab, setActiveTab] = useState<TabId>("picture");

  const [settings, setSettings] = useState<OnlineOrderingSettings>(DEFAULT_ONLINE_ORDERING_SETTINGS);
  const [businessName, setBusinessName] = useState("");
  const [origin, setOrigin] = useState("");

  useEffect(() => {
    setOrigin(typeof window !== "undefined" ? window.location.origin : "");
  }, []);

  useEffect(() => {
    if (!user) return;
    const unsubs = [
      onSnapshot(doc(db, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC), (snap) => {
        setSettings(
          parseOnlineOrderingSettings(snap.data() as Record<string, unknown> | undefined)
        );
      }),
      onSnapshot(doc(db, SETTINGS_COLLECTION, BUSINESS_INFO_DOC), (snap) => {
        setBusinessName((snap.get("businessName") as string) ?? "");
      }),
    ];
    return () => unsubs.forEach((u) => u());
  }, [user]);

  const slug = settings.onlineOrderingSlug || slugify(businessName);
  const orderUrl = `${origin || ""}/order${slug ? `/${slug}` : ""}`;

  if (!user) return null;

  return (
    <>
      <Header title="Online ordering" />
      <div className="px-6 py-5 max-w-5xl">
        <Link
          href="/dashboard/settings/online-ordering"
          className="inline-flex items-center gap-1.5 text-sm text-slate-600 hover:text-slate-900 mb-3"
        >
          <ArrowLeft size={16} />
          Back to online ordering settings
        </Link>
        <div className="flex flex-col gap-5 min-w-0">
          <header className="rounded-2xl bg-white border border-slate-200 p-5 shadow-sm flex flex-col sm:flex-row sm:items-center gap-3 justify-between shrink-0">
            <div className="flex items-center gap-3 min-w-0">
              <span className="grid place-items-center w-10 h-10 rounded-xl bg-violet-50 text-violet-700 shrink-0">
                <ShoppingBag size={20} />
              </span>
              <div className="min-w-0">
                <h1 className="text-lg font-semibold text-slate-900 truncate">
                  Online ordering control center
                </h1>
                <p className="text-xs text-slate-500 truncate">
                  Storefront banner image and ordering settings.
                </p>
              </div>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <a
                href={orderUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-medium hover:bg-blue-700"
              >
                <ExternalLink size={14} /> View storefront
              </a>
            </div>
          </header>

          <div className="rounded-2xl bg-white border border-slate-200 shadow-sm flex flex-col min-h-0">
            <div className="flex flex-wrap gap-1 p-1.5 border-b border-slate-100 bg-slate-50/60 rounded-t-2xl shrink-0">
              {TABS.map((t) => {
                const isActive = activeTab === t.id;
                return (
                  <button
                    key={t.id}
                    type="button"
                    onClick={() => setActiveTab(t.id)}
                    className={`inline-flex items-center gap-1.5 text-xs font-medium px-3 py-2 rounded-lg transition-colors ${
                      isActive
                        ? "bg-white text-slate-900 shadow-sm"
                        : "text-slate-500 hover:text-slate-800"
                    }`}
                  >
                    <t.icon size={14} className="opacity-80" />
                    {t.label}
                  </button>
                );
              })}
            </div>
            <div className="p-5 flex-1 min-h-0 overflow-y-auto">
              {activeTab === "picture" && (
                <StorefrontPictureManager businessName={businessName} />
              )}
              {activeTab === "settings" && <StorefrontSettingsManager settings={settings} />}
              {activeTab === "kitchen" && <OnlineKitchenRoutingManager settings={settings} />}
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
