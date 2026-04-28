"use client";

import { useEffect, useMemo, useState } from "react";
import {
  collection,
  doc,
  onSnapshot,
} from "firebase/firestore";
import { ArrowLeft, ExternalLink, Image as ImageIcon, Settings as SettingsIcon, ShoppingBag } from "lucide-react";
import Link from "next/link";
import Header from "@/components/Header";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import {
  BUSINESS_INFO_DOC,
  DEFAULT_ONLINE_ORDERING_SETTINGS,
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  formatPrepTimeRange,
  isStoreCurrentlyOpen,
  parseOnlineOrderingSettings,
  slugify,
  type OnlineOrderingSettings,
} from "@/lib/onlineOrderingShared";
import {
  HERO_SLIDES_COLLECTION,
  parseHeroSlide,
  type HeroSlide,
  type PublicStorefront,
} from "@/lib/storefrontShared";
import {
  isMenuItemVisibleOnOnlineChannel,
  menuItemPlacementCategoryIds,
} from "@/lib/onlineMenuCuration";
import type { OnlineMenuCategory, OnlineMenuItem } from "@/lib/onlineOrderingServer";
import StorefrontPictureManager from "@/components/online-ordering-admin/StorefrontPictureManager";
import StorefrontSettingsManager from "@/components/online-ordering-admin/StorefrontSettingsManager";
import { StorefrontPreview } from "@/components/storefront/StorefrontPreview";

/** [OnlineMenuItem] enriched with the raw Firestore doc so curation rules still work client-side. */
interface AdminMenuItem extends OnlineMenuItem {
  rawDoc: Record<string, unknown>;
}

type TabId = "picture" | "settings";

interface TabSpec {
  id: TabId;
  label: string;
  icon: React.ComponentType<{ size?: number; className?: string }>;
}

const TABS: TabSpec[] = [
  { id: "picture", label: "Store Front picture", icon: ImageIcon },
  { id: "settings", label: "Settings", icon: SettingsIcon },
];

export default function OnlineOrderingDashboardPage() {
  const { user } = useAuth();
  const [activeTab, setActiveTab] = useState<TabId>("picture");

  const [settings, setSettings] = useState<OnlineOrderingSettings>(DEFAULT_ONLINE_ORDERING_SETTINGS);
  const [businessName, setBusinessName] = useState("");
  const [logoUrl, setLogoUrl] = useState("");
  const [categories, setCategories] = useState<OnlineMenuCategory[]>([]);
  const [items, setItems] = useState<AdminMenuItem[]>([]);
  const [heroSlides, setHeroSlides] = useState<HeroSlide[]>([]);
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
        setLogoUrl((snap.get("logoUrl") as string) ?? "");
      }),
      onSnapshot(collection(db, "Categories"), (snap) => {
        const rows: OnlineMenuCategory[] = [];
        snap.forEach((d) => {
          const data = d.data();
          rows.push({
            id: d.id,
            name: (data.name as string) || "Category",
            sortOrder: typeof data.sortOrder === "number" ? data.sortOrder : 0,
          });
        });
        rows.sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));
        setCategories(rows);
      }),
      onSnapshot(collection(db, "MenuItems"), (snap) => {
        const rows: AdminMenuItem[] = [];
        snap.forEach((d) => {
          const data = d.data() as Record<string, unknown>;
          const rawPricing = data.pricing as Record<string, unknown> | undefined;
          const onlinePrice =
            rawPricing && typeof rawPricing.online === "number" ? rawPricing.online : null;
          const legacy = typeof data.price === "number" ? data.price : 0;
          const unitPriceCents = Math.round(((onlinePrice ?? legacy) as number) * 100);
          const placement = menuItemPlacementCategoryIds(data);
          rows.push({
            id: d.id,
            name: (data.name as string) || "Item",
            description: typeof data.description === "string" ? data.description : "",
            categoryId: placement[0] ?? "",
            categoryIds: placement,
            unitPriceCents,
            stock: typeof data.stock === "number" ? data.stock : 0,
            imageUrl: typeof data.imageUrl === "string" ? data.imageUrl : "",
            isFeatured: false,
            rawDoc: data,
          });
        });
        rows.sort((a, b) => a.name.localeCompare(b.name));
        setItems(rows);
      }),
      onSnapshot(collection(db, HERO_SLIDES_COLLECTION), (snap) => {
        const rows = snap.docs.map((d) => parseHeroSlide(d.id, d.data() as Record<string, unknown>));
        rows.sort((a, b) => a.order - b.order);
        setHeroSlides(rows);
      }),
    ];
    return () => unsubs.forEach((u) => u());
  }, [user]);

  /** Items filtered by current online curation rules (so the preview matches the customer page). */
  const visibleItems = useMemo(() => {
    return items
      .filter((it) => isMenuItemVisibleOnOnlineChannel(it.id, it.rawDoc, settings))
      .map((it) => ({ ...it, isFeatured: settings.featuredItemIds.includes(it.id) }));
  }, [items, settings]);

  const visibleCategories = useMemo(() => {
    const used = new Set<string>();
    for (const it of visibleItems) {
      for (const cid of it.categoryIds.length > 0 ? it.categoryIds : [it.categoryId]) {
        if (cid) used.add(cid);
      }
    }
    return categories.filter((c) => used.has(c.id));
  }, [categories, visibleItems]);

  const slug = settings.onlineOrderingSlug || slugify(businessName);
  const previewStorefront: PublicStorefront = {
    enabled: settings.enabled,
    isOpen: isStoreCurrentlyOpen(settings),
    businessName: businessName || "Restaurant",
    logoUrl,
    slug,
    prepTimeMinutes: settings.prepTimeMinutes,
    prepTimeLabel: formatPrepTimeRange(settings.prepTimeMinutes),
    allowPayInStore: settings.allowPayInStore,
    allowPayOnlineHpp: settings.allowPayOnlineHpp,
    heroSlides,
    featuredItemIds: settings.featuredItemIds,
  };

  const orderUrl = `${origin || ""}/order${slug ? `/${slug}` : ""}`;

  if (!user) return null;

  return (
    <>
      <Header title="Online ordering" />
      <div className="px-6 py-5">
        {/* Breadcrumb / back affordance — entry point lives at Settings → Online ordering */}
        <Link
          href="/dashboard/settings/online-ordering"
          className="inline-flex items-center gap-1.5 text-sm text-slate-600 hover:text-slate-900 mb-3"
        >
          <ArrowLeft size={16} />
          Back to online ordering settings
        </Link>
        <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_minmax(420px,min(46vw,720px))] gap-6 xl:items-stretch xl:min-h-[50vh]">
          {/* LEFT — admin panes */}
          <div className="flex flex-col gap-5 min-w-0 xl:min-h-[50vh] xl:h-full">
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

            {/* Tabs — stretch on xl so the pane uses at least half the viewport height */}
            <div className="rounded-2xl bg-white border border-slate-200 shadow-sm xl:flex-1 xl:flex xl:flex-col xl:min-h-0">
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
              <div className="p-5 xl:flex-1 xl:min-h-0 xl:overflow-y-auto">
                {activeTab === "picture" && <StorefrontPictureManager />}
                {activeTab === "settings" && (
                  <StorefrontSettingsManager
                    settings={settings}
                    businessName={businessName}
                    logoUrl={logoUrl}
                    logoStoragePath={user ? `businesses/${user.uid}/logo.png` : null}
                    uid={user?.uid ?? null}
                  />
                )}
              </div>
            </div>
          </div>

          {/* RIGHT — live preview (wide column, min half viewport tall) */}
          <aside className="hidden xl:block xl:min-h-[50vh] self-stretch">
            <div className="sticky top-[88px] h-full min-h-[50vh]">
              <StorefrontPreview
                storefront={previewStorefront}
                menu={{ categories: visibleCategories, items: visibleItems }}
              />
            </div>
          </aside>
        </div>

        {/* Mobile preview (below tabs on smaller screens) */}
        <div className="xl:hidden mt-6">
          <StorefrontPreview
            storefront={previewStorefront}
            menu={{ categories: visibleCategories, items: visibleItems }}
          />
        </div>
      </div>
    </>
  );
}

