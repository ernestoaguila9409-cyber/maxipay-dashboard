"use client";

import { ShoppingBag } from "lucide-react";
import type { HeroSlide, PublicStorefront } from "@/lib/storefrontShared";
import type { OnlineMenuCategory, OnlineMenuItem } from "@/lib/onlineOrderingServer";
import { HeroCarousel } from "./HeroCarousel";
import { StoreHeader } from "./StoreHeader";
import { FeaturedRow, type FeaturedRowItem } from "./FeaturedRow";

export interface StorefrontPreviewProps {
  /** Storefront snapshot (hero slides, logo, name, prep time, open). */
  storefront: PublicStorefront;
  /** Latest online menu (categories + items) for the featured row + categories preview. */
  menu: { categories: OnlineMenuCategory[]; items: OnlineMenuItem[] } | null;
}

/**
 * Live preview pane shown beside the admin dashboard's edit columns. Reflects every change
 * (hero slides, featured items, store name, logo, open/closed) the moment Firestore updates,
 * because both this preview and the customer page read from the same `Settings/onlineOrdering`
 * + `OnlineHeroSlides` documents via Firestore listeners.
 *
 * Rendered inside a fixed-width card that imitates a phone-frame so the owner can sanity-check
 * the layout on small screens without leaving the dashboard.
 */
export function StorefrontPreview({ storefront, menu }: StorefrontPreviewProps) {
  const featuredItems: FeaturedRowItem[] = (() => {
    if (!menu) return [];
    if (storefront.featuredItemIds.length > 0) {
      const map = new Map(menu.items.map((it) => [it.id, it] as const));
      return storefront.featuredItemIds
        .map((id) => map.get(id))
        .filter((it): it is OnlineMenuItem => Boolean(it))
        .map((it) => ({
          id: it.id,
          name: it.name,
          unitPriceCents: it.unitPriceCents,
          imageUrl: it.imageUrl,
        }));
    }
    // Auto-popular fallback: first 6 visible items with images.
    return menu.items
      .filter((it) => it.imageUrl)
      .slice(0, 6)
      .map((it) => ({
        id: it.id,
        name: it.name,
        unitPriceCents: it.unitPriceCents,
        imageUrl: it.imageUrl,
      }));
  })();

  const visibleCategories = menu?.categories ?? [];

  return (
    <div className="bg-neutral-100 rounded-3xl border border-neutral-200 shadow-inner p-3 flex flex-col gap-3 max-h-[calc(100vh-120px)] overflow-y-auto">
      <div className="flex items-center justify-between text-[11px] font-medium text-neutral-500 px-1">
        <span className="uppercase tracking-wider">Live preview</span>
        <span className="text-neutral-400">/order/{storefront.slug || "…"}</span>
      </div>

      <div className="bg-white rounded-2xl overflow-hidden border border-neutral-200">
        <div className="px-4 pt-4 pb-2">
          <StoreHeader
            businessName={storefront.businessName}
            logoUrl={storefront.logoUrl}
            isOpen={storefront.isOpen}
            prepTimeLabel={storefront.prepTimeLabel}
            compact
          />
        </div>

        <div className="px-4 pb-4">
          <HeroCarousel slides={storefront.heroSlides} compact autoplayMs={5000} />
        </div>

        {visibleCategories.length > 0 && (
          <div className="px-4 pb-3 border-t border-neutral-100 pt-3">
            <div className="flex gap-1.5 overflow-x-auto scrollbar-hide">
              <span className="shrink-0 px-3 py-1 rounded-full bg-black text-white text-[11px] font-medium">
                All
              </span>
              {visibleCategories.slice(0, 8).map((c) => (
                <span
                  key={c.id}
                  className="shrink-0 px-3 py-1 rounded-full bg-neutral-100 text-neutral-700 text-[11px] font-medium"
                >
                  {c.name}
                </span>
              ))}
            </div>
          </div>
        )}

        {featuredItems.length > 0 && (
          <div className="px-4 pb-4">
            <FeaturedRow items={featuredItems} title="Popular" compact />
          </div>
        )}

        {!menu && (
          <div className="px-4 pb-4">
            <p className="text-xs text-neutral-400">Loading menu preview…</p>
          </div>
        )}

        <div className="border-t border-neutral-100 px-4 py-3 flex items-center gap-2">
          <span className="grid place-items-center w-8 h-8 rounded-full bg-black text-white">
            <ShoppingBag size={14} />
          </span>
          <span className="text-xs font-medium text-neutral-700">Cart</span>
          <span className="ml-auto text-[11px] text-neutral-400">Sticky on the right →</span>
        </div>
      </div>

      {!storefront.enabled && (
        <p className="text-[11px] text-amber-700 bg-amber-50 border border-amber-200 rounded-xl px-3 py-2">
          Online ordering is disabled. Customers see an "unavailable" message until you turn it on
          from the <span className="font-medium">Settings</span> tab.
        </p>
      )}
    </div>
  );
}
