"use client";

import { useEffect, useMemo, useState } from "react";
import {
  collection,
  doc,
  onSnapshot,
} from "firebase/firestore";
import {
  ChefHat,
  ExternalLink,
  Image as ImageIcon,
  Layers,
  Settings as SettingsIcon,
  ShoppingBag,
  Sparkles,
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
import HeroCarouselManager from "@/components/online-ordering-admin/HeroCarouselManager";
import FeaturedItemsManager from "@/components/online-ordering-admin/FeaturedItemsManager";
import StorefrontSettingsManager from "@/components/online-ordering-admin/StorefrontSettingsManager";
import { StorefrontPreview } from "@/components/storefront/StorefrontPreview";

/** [OnlineMenuItem] enriched with the raw Firestore doc so curation rules still work client-side. */
interface AdminMenuItem extends OnlineMenuItem {
  rawDoc: Record<string, unknown>;
}

type TabId = "hero" | "categories" | "menu" | "featured" | "settings";

interface TabSpec {
  id: TabId;
  label: string;
  icon: React.ComponentType<{ size?: number; className?: string }>;
}

const TABS: TabSpec[] = [
  { id: "hero", label: "Hero carousel", icon: ImageIcon },
  { id: "categories", label: "Categories", icon: Layers },
  { id: "menu", label: "Menu items", icon: UtensilsCrossed },
  { id: "featured", label: "Featured", icon: Sparkles },
  { id: "settings", label: "Settings", icon: SettingsIcon },
];

export default function OnlineOrderingDashboardPage() {
  const { user } = useAuth();
  const [activeTab, setActiveTab] = useState<TabId>("hero");

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
        <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_380px] gap-6">
          {/* LEFT — admin panes */}
          <div className="space-y-5 min-w-0">
            <header className="rounded-2xl bg-white border border-slate-200 p-5 shadow-sm flex flex-col sm:flex-row sm:items-center gap-3 justify-between">
              <div className="flex items-center gap-3 min-w-0">
                <span className="grid place-items-center w-10 h-10 rounded-xl bg-violet-50 text-violet-700 shrink-0">
                  <ShoppingBag size={20} />
                </span>
                <div className="min-w-0">
                  <h1 className="text-lg font-semibold text-slate-900 truncate">
                    Online ordering control center
                  </h1>
                  <p className="text-xs text-slate-500 truncate">
                    Storefront, hero carousel, featured items — all in one place.
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2 shrink-0">
                <Link
                  href="/dashboard/settings/online-ordering"
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-slate-700 text-xs font-medium hover:bg-slate-50"
                >
                  <ChefHat size={14} /> Slug &amp; payments
                </Link>
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

            {/* Tabs */}
            <div className="rounded-2xl bg-white border border-slate-200 shadow-sm">
              <div className="flex flex-wrap gap-1 p-1.5 border-b border-slate-100 bg-slate-50/60 rounded-t-2xl">
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
              <div className="p-5">
                {activeTab === "hero" && (
                  <HeroCarouselManager categories={visibleCategories} items={visibleItems} />
                )}
                {activeTab === "categories" && (
                  <CategoriesShortcut categoriesCount={categories.length} />
                )}
                {activeTab === "menu" && (
                  <MenuShortcut itemsCount={items.length} curationOn={settings.onlineMenuCurationEnabled} />
                )}
                {activeTab === "featured" && (
                  <FeaturedItemsManager
                    items={visibleItems}
                    categories={visibleCategories}
                    featuredItemIds={settings.featuredItemIds}
                  />
                )}
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

          {/* RIGHT — live preview */}
          <aside className="hidden xl:block">
            <div className="sticky top-[88px]">
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

/* ─────────────────────────────────────────────
   Lightweight pointer tabs to existing pages
   (Categories + Menu Items already have full editors elsewhere; we don't duplicate them.)
   ───────────────────────────────────────────── */

function CategoriesShortcut({ categoriesCount }: { categoriesCount: number }) {
  return (
    <ShortcutPanel
      icon={<Layers size={20} />}
      title="Categories"
      subtitle={`${categoriesCount} categor${categoriesCount === 1 ? "y" : "ies"} configured`}
      description="Categories are shared between the in-store POS, KDS, and the online menu. Manage them in the main Menu page so the rename / sort order / icon stays in sync everywhere."
      links={[
        { href: "/dashboard/menu", label: "Open menu editor" },
        { href: "/dashboard/menu/online", label: "Pick which categories appear online" },
      ]}
    />
  );
}

function MenuShortcut({
  itemsCount,
  curationOn,
}: {
  itemsCount: number;
  curationOn: boolean;
}) {
  return (
    <ShortcutPanel
      icon={<UtensilsCrossed size={20} />}
      title="Menu items"
      subtitle={`${itemsCount} item${itemsCount === 1 ? "" : "s"} in your catalog`}
      description={
        curationOn
          ? "Curated online menu is on — the storefront only shows items / categories you picked. Edit prices, descriptions and photos in the Menu editor; pick what's online in the Online menu page."
          : "Items appear online when their per-item online channel flag is on. Toggle that flag in the Menu editor — or switch to the curated online menu to manage online visibility separately from the POS."
      }
      links={[
        { href: "/dashboard/menu", label: "Edit items, prices & photos" },
        { href: "/dashboard/menu/online", label: "Online menu visibility" },
      ]}
    />
  );
}

function ShortcutPanel({
  icon,
  title,
  subtitle,
  description,
  links,
}: {
  icon: React.ReactNode;
  title: string;
  subtitle: string;
  description: string;
  links: { href: string; label: string }[];
}) {
  return (
    <div>
      <div className="flex items-start gap-3">
        <span className="grid place-items-center w-10 h-10 rounded-xl bg-blue-50 text-blue-700 shrink-0">
          {icon}
        </span>
        <div>
          <h2 className="text-lg font-semibold text-slate-900">{title}</h2>
          <p className="text-xs text-slate-500 mt-0.5">{subtitle}</p>
        </div>
      </div>
      <p className="text-sm text-slate-600 mt-4 leading-relaxed">{description}</p>
      <div className="mt-4 flex flex-wrap gap-2">
        {links.map((l) => (
          <Link
            key={l.href}
            href={l.href}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-slate-700 text-xs font-medium hover:bg-slate-50"
          >
            <ExternalLink size={14} /> {l.label}
          </Link>
        ))}
      </div>
    </div>
  );
}

