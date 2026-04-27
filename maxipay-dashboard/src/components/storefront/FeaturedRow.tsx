"use client";

import { Plus } from "lucide-react";

export interface FeaturedRowItem {
  id: string;
  name: string;
  unitPriceCents: number;
  imageUrl: string;
}

export interface FeaturedRowProps {
  items: FeaturedRowItem[];
  title?: string;
  onAdd?: (id: string) => void;
  compact?: boolean;
}

function fmt(cents: number): string {
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency: "USD",
  }).format(cents / 100);
}

/**
 * Horizontally-scrollable "Featured / Popular" carousel. Hides itself when [items] is empty
 * so the storefront stays clean for new restaurants that haven't featured anything yet.
 */
export function FeaturedRow({
  items,
  title = "Popular",
  onAdd,
  compact = false,
}: FeaturedRowProps) {
  if (items.length === 0) return null;
  const cardW = compact ? "w-44" : "w-56";
  const imgH = compact ? "h-24" : "h-32";

  return (
    <section className={compact ? "" : ""}>
      <h2
        className={`font-bold text-neutral-900 ${
          compact ? "text-sm mb-2" : "text-lg mb-3"
        }`}
      >
        {title}
      </h2>
      <div className="-mx-1 flex gap-3 overflow-x-auto scrollbar-hide pb-1 px-1">
        {items.map((it) => (
          <div
            key={it.id}
            className={`shrink-0 ${cardW} bg-white rounded-2xl border border-neutral-100 overflow-hidden hover:shadow-[0_4px_24px_rgba(0,0,0,0.08)] transition-shadow`}
          >
            <div className={`relative w-full ${imgH} bg-neutral-100`}>
              {it.imageUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={it.imageUrl}
                  alt={it.name}
                  className="w-full h-full object-cover"
                  loading="lazy"
                />
              ) : null}
            </div>
            <div className={`px-3 ${compact ? "py-2" : "py-3"} flex flex-col gap-1.5`}>
              <p
                className={`font-semibold text-neutral-900 line-clamp-2 ${
                  compact ? "text-xs" : "text-sm"
                }`}
                title={it.name}
              >
                {it.name}
              </p>
              <div className="flex items-center justify-between">
                <span
                  className={`font-medium text-neutral-700 tabular-nums ${
                    compact ? "text-xs" : "text-sm"
                  }`}
                >
                  {fmt(it.unitPriceCents)}
                </span>
                {onAdd && (
                  <button
                    type="button"
                    onClick={() => onAdd(it.id)}
                    aria-label={`Add ${it.name}`}
                    className={`grid place-items-center rounded-full bg-black text-white shadow ${
                      compact ? "w-7 h-7" : "w-8 h-8"
                    } hover:bg-neutral-800 active:scale-95 transition-transform`}
                  >
                    <Plus size={compact ? 14 : 16} />
                  </button>
                )}
              </div>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
