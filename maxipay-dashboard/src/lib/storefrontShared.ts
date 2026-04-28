/**
 * Online ordering storefront shared types.
 *
 * Hero slides live in their own top-level Firestore collection (`OnlineHeroSlides`),
 * matching the existing pattern of `Categories` / `MenuItems` (vs. nesting under a
 * `stores/{storeId}` document — the rest of the POS / KDS / Android pipeline uses
 * the flat collection shape, so we stay consistent here).
 *
 * Logo + storefront flags live on the same docs already used by the Android receipt
 * settings + online ordering settings:
 *  - `Settings/businessInfo`     → `businessName`, `logoUrl`
 *  - `Settings/onlineOrdering`   → `prepTimeMinutes`, `openStatusOverride`, business hours, `featuredItemIds`
 */

export const HERO_SLIDES_COLLECTION = "OnlineHeroSlides";
export const HERO_SLIDES_MAX = 5;
export const HERO_STORAGE_PREFIX = "online-ordering/hero";

export type HeroActionType = "NONE" | "CATEGORY" | "ITEM" | "URL";

export interface HeroSlide {
  id: string;
  imageUrl: string;
  /** Storage object path so we can delete the file when the slide is removed. */
  storagePath: string;
  title: string;
  subtitle: string;
  ctaLabel: string;
  actionType: HeroActionType;
  /** `categoryId` for CATEGORY, MenuItem id for ITEM, full URL for URL, "" for NONE. */
  actionValue: string;
  /** Lower numbers render first. Reordering rewrites this for every slide. */
  order: number;
}

export const DEFAULT_HERO_CTA = "Order now";

export function parseHeroAction(v: unknown): HeroActionType {
  return v === "CATEGORY" || v === "ITEM" || v === "URL" ? v : "NONE";
}

/**
 * Parses a Firestore document into a [HeroSlide]. Used on both server (Admin SDK)
 * and client (web SDK) — only depends on plain JSON fields.
 */
export function parseHeroSlide(
  id: string,
  data: Record<string, unknown> | undefined
): HeroSlide {
  if (!data) {
    return {
      id,
      imageUrl: "",
      storagePath: "",
      title: "",
      subtitle: "",
      ctaLabel: DEFAULT_HERO_CTA,
      actionType: "NONE",
      actionValue: "",
      order: 0,
    };
  }
  return {
    id,
    imageUrl: typeof data.imageUrl === "string" ? data.imageUrl.trim() : "",
    storagePath: typeof data.storagePath === "string" ? data.storagePath.trim() : "",
    title: typeof data.title === "string" ? data.title : "",
    subtitle: typeof data.subtitle === "string" ? data.subtitle : "",
    ctaLabel: typeof data.ctaLabel === "string" && data.ctaLabel.trim().length > 0
      ? data.ctaLabel.trim()
      : DEFAULT_HERO_CTA,
    actionType: parseHeroAction(data.actionType),
    actionValue: typeof data.actionValue === "string" ? data.actionValue.trim() : "",
    order: typeof data.order === "number" && Number.isFinite(data.order) ? data.order : 0,
  };
}

/**
 * Public storefront config returned by `/api/online-ordering/storefront`.
 * Contains everything the customer page needs to render its top sections in one round-trip.
 */
export interface PublicStorefront {
  enabled: boolean;
  isOpen: boolean;
  businessName: string;
  logoUrl: string;
  slug: string;
  prepTimeMinutes: number;
  prepTimeLabel: string;
  allowPayInStore: boolean;
  allowPayOnlineHpp: boolean;
  heroSlides: HeroSlide[];
  /** MenuItem ids the storefront should highlight in its "Featured" row. */
  featuredItemIds: string[];
}
