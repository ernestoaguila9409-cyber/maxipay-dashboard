"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ChevronLeft, ChevronRight, ImageIcon } from "lucide-react";
import type { HeroSlide } from "@/lib/storefrontShared";

/**
 * Customer-facing hero carousel. Renders up to 5 slides with image + title + subtitle + CTA.
 * Auto-advances every [autoplayMs] ms; pauses while the pointer is hovering. Click on a slide
 * (or its CTA) calls [onSlideClick] with the action so the host page can scroll/navigate.
 *
 * Used by both the public ordering page and the dashboard live preview, hence "compact" mode
 * (smaller heights for the preview pane).
 */
export interface HeroCarouselProps {
  slides: HeroSlide[];
  onSlideClick?: (slide: HeroSlide) => void;
  /** ms between auto-advance ticks. 0 disables autoplay. Default 6000. */
  autoplayMs?: number;
  /** Compact = smaller fixed heights for in-dashboard preview. */
  compact?: boolean;
  /**
   * When [compact] is true, use a taller strip (still smaller than the public storefront).
   * Used by the dashboard live preview so the hero is not postage-stamp sized.
   */
  compactTall?: boolean;
  /** Extra classes on the outer wrapper. */
  className?: string;
}

export function HeroCarousel({
  slides,
  onSlideClick,
  autoplayMs = 6000,
  compact = false,
  compactTall = false,
  className = "",
}: HeroCarouselProps) {
  const [active, setActive] = useState(0);
  const [paused, setPaused] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);

  const visible = useMemo(() => slides.slice(0, 5), [slides]);
  const count = visible.length;

  useEffect(() => {
    if (active >= count) setActive(0);
  }, [active, count]);

  const go = useCallback((dir: 1 | -1) => {
    setActive((i) => (count === 0 ? 0 : (i + dir + count) % count));
  }, [count]);

  useEffect(() => {
    if (autoplayMs <= 0 || count <= 1 || paused) return;
    const id = setInterval(() => go(1), autoplayMs);
    return () => clearInterval(id);
  }, [autoplayMs, count, go, paused]);

  if (count === 0) {
    const emptyH =
      compact && compactTall
        ? "min-h-[200px] h-[clamp(200px,28vh,360px)]"
        : compact
          ? "h-36"
          : "h-48 sm:h-72";
    return (
      <div
        className={`flex items-center justify-center rounded-2xl border border-dashed border-neutral-200 bg-neutral-50 text-neutral-400 ${emptyH} ${className}`}
      >
        <div className="flex flex-col items-center gap-2 text-xs">
          <ImageIcon size={compact ? 22 : 28} />
          No hero slides yet
        </div>
      </div>
    );
  }

  const heightClass =
    compact && compactTall
      ? "min-h-[200px] h-[clamp(200px,28vh,360px)]"
      : compact
        ? "h-36 sm:h-44"
        : "h-48 sm:h-64 md:h-80";

  return (
    <div
      ref={wrapperRef}
      className={`relative overflow-hidden rounded-2xl bg-neutral-900 ${heightClass} ${className}`}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onTouchStart={() => setPaused(true)}
      onTouchEnd={() => setPaused(false)}
    >
      {visible.map((s, i) => {
        const isActive = i === active;
        return (
          <button
            key={s.id}
            type="button"
            onClick={() => onSlideClick?.(s)}
            className={`absolute inset-0 w-full text-left transition-opacity duration-500 ${
              isActive ? "opacity-100 z-10" : "opacity-0 pointer-events-none z-0"
            }`}
            aria-hidden={!isActive}
            tabIndex={isActive ? 0 : -1}
          >
            {s.imageUrl ? (
              // Use plain <img/> to avoid Next/Image domain config gotchas with arbitrary Storage URLs.
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={s.imageUrl}
                alt={s.title || "Promotion"}
                className="absolute inset-0 w-full h-full object-cover"
                loading={i === 0 ? "eager" : "lazy"}
              />
            ) : (
              <div className="absolute inset-0 bg-gradient-to-br from-neutral-700 to-neutral-900" />
            )}
            <div className="absolute inset-0 bg-gradient-to-t from-black/65 via-black/15 to-transparent" />
            <div
              className={`relative h-full flex flex-col justify-end ${
                compact ? "px-4 pb-4" : "px-6 pb-6 sm:px-10 sm:pb-10"
              } text-white`}
            >
              {s.title && (
                <h2
                  className={`font-bold leading-tight drop-shadow ${
                    compact ? "text-lg" : "text-2xl sm:text-3xl md:text-4xl"
                  }`}
                >
                  {s.title}
                </h2>
              )}
              {s.subtitle && (
                <p
                  className={`mt-1 text-white/85 max-w-xl ${
                    compact ? "text-xs" : "text-sm sm:text-base"
                  }`}
                >
                  {s.subtitle}
                </p>
              )}
              {s.ctaLabel && s.actionType !== "NONE" && (
                <span
                  className={`mt-3 inline-flex items-center self-start rounded-full bg-white text-neutral-900 font-semibold shadow ${
                    compact ? "px-3 py-1 text-xs" : "px-5 py-2.5 text-sm"
                  }`}
                >
                  {s.ctaLabel}
                </span>
              )}
            </div>
          </button>
        );
      })}

      {count > 1 && (
        <>
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              go(-1);
            }}
            aria-label="Previous slide"
            className={`absolute top-1/2 -translate-y-1/2 left-2 sm:left-3 z-20 rounded-full bg-white/85 hover:bg-white text-neutral-800 shadow grid place-items-center ${
              compact ? "w-7 h-7" : "w-9 h-9"
            }`}
          >
            <ChevronLeft size={compact ? 16 : 20} />
          </button>
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              go(1);
            }}
            aria-label="Next slide"
            className={`absolute top-1/2 -translate-y-1/2 right-2 sm:right-3 z-20 rounded-full bg-white/85 hover:bg-white text-neutral-800 shadow grid place-items-center ${
              compact ? "w-7 h-7" : "w-9 h-9"
            }`}
          >
            <ChevronRight size={compact ? 16 : 20} />
          </button>
          <div className="absolute bottom-2 sm:bottom-3 left-0 right-0 z-20 flex items-center justify-center gap-1.5">
            {visible.map((s, i) => (
              <button
                key={s.id}
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  setActive(i);
                }}
                aria-label={`Go to slide ${i + 1}`}
                className={`h-1.5 rounded-full transition-all ${
                  i === active ? "w-5 bg-white" : "w-1.5 bg-white/55"
                }`}
              />
            ))}
          </div>
        </>
      )}
    </div>
  );
}
