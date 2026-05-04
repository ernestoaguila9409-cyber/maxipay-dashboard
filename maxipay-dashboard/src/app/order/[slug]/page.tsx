"use client";

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { expandModifierGroupIdsFromPicks } from "@/lib/onlineOrderingShared";
import type { HeroSlide, PublicStorefront } from "@/lib/storefrontShared";
import { HeroCarousel } from "@/components/storefront/HeroCarousel";

/* ═══════════════════════════════════════════
   Color tokens
   ═══════════════════════════════════════════ */

const O = {
  bg: "bg-[#FEF7ED]",
  primary: "bg-[#EA580C]",
  primaryHover: "hover:bg-[#C2410C]",
  primaryText: "text-[#EA580C]",
  primaryRing: "ring-[#EA580C]",
  primaryBorder: "border-[#EA580C]",
  primaryBg10: "bg-orange-50",
} as const;

/* ═══════════════════════════════════════════
   Types
   ═══════════════════════════════════════════ */

type PublicConfig = {
  enabled: boolean;
  businessName: string;
  logoUrl: string;
  slug: string;
  isOpen: boolean;
  prepTimeLabel: string;
  allowPayInStore: boolean;
  allowPayOnlineHpp: boolean;
};

type ModifierOption = {
  id: string;
  name: string;
  price: number;
  triggersModifierGroupIds: string[];
  imageUrl?: string;
};

type ModifierGroup = {
  id: string;
  name: string;
  required: boolean;
  minSelection: number;
  maxSelection: number;
  groupType: string;
  options: ModifierOption[];
};

type MenuItem = {
  id: string;
  name: string;
  description: string;
  categoryId: string;
  categoryIds: string[];
  unitPriceCents: number;
  stock: number;
  imageUrl: string;
  isFeatured: boolean;
  modifierGroupIds: string[];
};

type MenuCategory = { id: string; name: string; sortOrder: number };

type ModifierSelection = { groupId: string; optionId: string };

type CartLine = {
  lineId: string;
  itemId: string;
  quantity: number;
  item: MenuItem;
  selections: ModifierSelection[];
};

/* ═══════════════════════════════════════════
   Helpers
   ═══════════════════════════════════════════ */

function fmt(cents: number): string {
  return new Intl.NumberFormat(undefined, { style: "currency", currency: "USD" }).format(cents / 100);
}

function unitPriceCentsForLine(item: MenuItem, selections: ModifierSelection[], groupMap: Map<string, ModifierGroup>): number {
  let extra = 0;
  for (const s of selections) {
    const g = groupMap.get(s.groupId);
    const opt = g?.options.find((o) => o.id === s.optionId);
    if (!g || !opt) continue;
    if (g.groupType === "REMOVE") continue;
    extra += Math.round(opt.price * 100);
  }
  return item.unitPriceCents + extra;
}

function modifierSummaryLines(selections: ModifierSelection[], groupMap: Map<string, ModifierGroup>): string[] {
  const out: string[] = [];
  for (const s of selections) {
    const g = groupMap.get(s.groupId);
    const opt = g?.options.find((o) => o.id === s.optionId);
    if (opt?.name) out.push(opt.name);
  }
  return out;
}

/** Mirrors Android [ModifierRemoveDisplay.cartLine]. */
function formatRemoveModifierLabel(name: string): string {
  const t = name.trim();
  if (!t) return t;
  const u = t.toUpperCase();
  if (u.startsWith("NO ") || u === "NO") return t;
  return `No ${t}`;
}

type ModifierCartRow = { label: string; remove: boolean; extraCents: number; imageUrl?: string };

/** Per-modifier rows for cart UI; [extraCents] is ADD-on price in cents (0 for REMOVE / free). */
function modifierCartRows(selections: ModifierSelection[], groupMap: Map<string, ModifierGroup>): ModifierCartRow[] {
  const out: ModifierCartRow[] = [];
  for (const s of selections) {
    const g = groupMap.get(s.groupId);
    const opt = g?.options.find((o) => o.id === s.optionId);
    if (!opt?.name) continue;
    const remove = g?.groupType === "REMOVE";
    const extraCents = remove ? 0 : Math.round(opt.price * 100);
    const img = opt.imageUrl?.trim();
    out.push({ label: opt.name, remove, extraCents, ...(img ? { imageUrl: img } : {}) });
  }
  return out;
}

/** Base item price + priced modifiers + line total (matches Android cart breakdown). */
function CartLinePriceBlock({
  line,
  groupMap,
  compact = false,
}: {
  line: CartLine;
  groupMap: Map<string, ModifierGroup>;
  compact?: boolean;
}) {
  const base = line.item.unitPriceCents;
  const unit = unitPriceCentsForLine(line.item, line.selections, groupMap);
  const lineTotal = unit * line.quantity;
  const modRows = modifierCartRows(line.selections, groupMap);
  const hasSelections = modRows.length > 0;
  const metaCls = compact ? "text-[11px]" : "text-xs";
  const totalCls = compact ? "text-xs" : "text-sm";
  const totalMt = compact ? "mt-1" : "mt-1.5";

  return (
    <>
      {hasSelections ? (
        <>
          <p className={`${metaCls} text-neutral-600 mt-0.5 tabular-nums`}>Base · {fmt(base)}</p>
          <p className={`${metaCls} text-neutral-500 tabular-nums`}>Qty {line.quantity}</p>
          {modRows.map((m, i) => (
            <div
              key={i}
              className={`flex justify-between gap-2 items-center pl-0.5 ${metaCls} mt-0.5 ${m.remove ? "text-red-700" : "text-neutral-600"}`}
            >
              <span className="min-w-0 flex items-center gap-2">
                {m.imageUrl ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={m.imageUrl}
                    alt=""
                    className={`shrink-0 rounded-md object-cover border border-neutral-200 bg-neutral-50 ${compact ? "w-4 h-4" : "w-5 h-5"}`}
                  />
                ) : null}
                <span>• {m.remove ? formatRemoveModifierLabel(m.label) : m.label}</span>
              </span>
              {!m.remove && m.extraCents > 0 ? (
                <span className={`shrink-0 tabular-nums font-medium ${O.primaryText}`}>+{fmt(m.extraCents)}</span>
              ) : null}
            </div>
          ))}
        </>
      ) : (
        <p className={`${metaCls} text-neutral-500 mt-0.5 tabular-nums`}>
          Qty {line.quantity} · {fmt(base)} ea
        </p>
      )}
      <p className={`${totalCls} font-bold text-emerald-900 ${totalMt} tabular-nums`}>Line total: {fmt(lineTotal)}</p>
    </>
  );
}

/* ═══════════════════════════════════════════
   SVG Icons
   ═══════════════════════════════════════════ */

function IconPlus({ size = 18 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg>);
}
function IconMinus({ size = 18 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round"><path d="M5 12h14" /></svg>);
}
function IconBag({ size = 20 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round"><path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z" /><path d="M3 6h18" /><path d="M16 10a4 4 0 01-8 0" /></svg>);
}
function IconX({ size = 20 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round"><path d="M18 6L6 18M6 6l12 12" /></svg>);
}
function IconTrash({ size = 16 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"><path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2" /></svg>);
}
function IconStore({ size = 20 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z" /><polyline points="9 22 9 12 15 12 15 22" /></svg>);
}
function IconClock({ size = 14 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round"><circle cx="12" cy="12" r="10" /><path d="M12 6v6l4 2" /></svg>);
}
function IconArrowRight({ size = 18 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14M12 5l7 7-7 7" /></svg>);
}
function IconChevronLeft({ size = 22 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round"><path d="M15 18l-6-6 6-6" /></svg>);
}
function IconChevronRight({ size = 22 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round"><path d="M9 18l6-6-6-6" /></svg>);
}
function IconTruck({ size = 22 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round"><rect x="1" y="3" width="15" height="13" /><polygon points="16 8 20 8 23 11 23 16 16 16 16 8" /><circle cx="5.5" cy="18.5" r="2.5" /><circle cx="18.5" cy="18.5" r="2.5" /></svg>);
}
function IconLeaf({ size = 22 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round"><path d="M17 8C8 10 5.9 16.17 3.82 21.34l1.89.66L12 14" /><path d="M20.59 5.41a2.09 2.09 0 00-2.95 0L12 11.07l5.66 5.66 5.65-5.66a2.09 2.09 0 000-2.95z" /></svg>);
}
function IconShield({ size = 22 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" /></svg>);
}
function IconStar({ size = 22 }: { size?: number }) {
  return (<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" /></svg>);
}

/* ═══════════════════════════════════════════
   CategoryTabs (orange active)
   ═══════════════════════════════════════════ */

function CategoryTabs({ categories, active, onSelect }: { categories: MenuCategory[]; active: string; onSelect: (id: string) => void }) {
  return (
    <div className="flex gap-2 overflow-x-auto scrollbar-hide px-1 py-1">
      {[{ id: "ALL", name: "All" } as const, ...categories].map((c) => {
        const isActive = active === c.id;
        return (
          <button
            key={c.id}
            type="button"
            onClick={() => onSelect(c.id)}
            className={`shrink-0 px-5 py-2 text-sm font-semibold rounded-full transition-all whitespace-nowrap ${
              isActive
                ? `${O.primary} text-white shadow-sm`
                : "bg-white text-neutral-600 hover:bg-neutral-50 border border-neutral-200"
            }`}
          >
            {c.name}
          </button>
        );
      })}
    </div>
  );
}

/* ═══════════════════════════════════════════
   PopularCard (large vertical card for Popular section)
   ═══════════════════════════════════════════ */

/**
 * Horizontal Popular strip with visible scrollbar (Android-style scroll) and optional arrows.
 */
function PopularRow({ items, onItemAction }: { items: MenuItem[]; onItemAction: (it: MenuItem) => void }) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const scrollBy = (delta: number) => scrollRef.current?.scrollBy({ left: delta, behavior: "smooth" });

  return (
    <div className="relative">
      <button
        type="button"
        aria-label="Scroll popular items left"
        onClick={() => scrollBy(-220)}
        className="hidden sm:flex absolute left-0 top-1/2 z-10 -translate-y-1/2 w-9 h-24 items-center justify-center rounded-r-xl bg-white/95 border border-neutral-200 shadow-md text-neutral-700 hover:bg-orange-50 hover:text-[#EA580C] transition-colors"
      >
        <IconChevronLeft size={22} />
      </button>
      <button
        type="button"
        aria-label="Scroll popular items right"
        onClick={() => scrollBy(220)}
        className="hidden sm:flex absolute right-0 top-1/2 z-10 -translate-y-1/2 w-9 h-24 items-center justify-center rounded-l-xl bg-white/95 border border-neutral-200 shadow-md text-neutral-700 hover:bg-orange-50 hover:text-[#EA580C] transition-colors"
      >
        <IconChevronRight size={22} />
      </button>
      <div
        ref={scrollRef}
        className="popular-strip-scroll flex gap-3 overflow-x-auto pb-2 pt-0.5 px-1 sm:px-10 -mx-1"
      >
        {items.map((it) => (
          <PopularCard key={it.id} item={it} onAction={() => onItemAction(it)} />
        ))}
      </div>
    </div>
  );
}

function PopularCard({ item, onAction }: { item: MenuItem; onAction: () => void }) {
  return (
    <div className="shrink-0 w-[180px] bg-white rounded-2xl overflow-hidden shadow-sm border border-neutral-100 hover:shadow-md transition-shadow">
      <div className="relative w-full h-[140px] bg-neutral-100">
        {item.imageUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={item.imageUrl} alt={item.name} className="w-full h-full object-cover" loading="lazy" />
        ) : (
          <div className="w-full h-full flex items-center justify-center text-neutral-300"><IconStore size={32} /></div>
        )}
      </div>
      <div className="px-3 py-3">
        <p className="text-sm font-semibold text-neutral-900 line-clamp-1">{item.name}</p>
        <div className="flex items-center justify-between mt-2">
          <span className={`text-sm font-bold ${O.primaryText}`}>{fmt(item.unitPriceCents)}</span>
          <button
            type="button"
            onClick={(e) => { e.stopPropagation(); onAction(); }}
            className={`w-8 h-8 rounded-full ${O.primary} ${O.primaryHover} text-white grid place-items-center shadow-sm active:scale-95 transition-transform`}
          >
            <IconPlus size={16} />
          </button>
        </div>
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   MenuItemCard (compact horizontal — text left, image right)
   ═══════════════════════════════════════════ */

function MenuItemCard({
  item, qtySimple, hasModifiers, onAddSimple, onOpenCustomize, onDecSimple,
}: {
  item: MenuItem; qtySimple: number; hasModifiers: boolean;
  onAddSimple: () => void; onOpenCustomize: () => void; onDecSimple: () => void;
}) {
  const hasImage = item.imageUrl.trim().length > 0;

  return (
    <div className="flex items-center gap-4 bg-white rounded-2xl border border-neutral-100 p-3 hover:shadow-md transition-shadow">
      <div className="flex-1 min-w-0 flex flex-col justify-between py-1">
        <div>
          <h3 className="font-semibold text-sm text-neutral-900 leading-snug">{item.name}</h3>
          {item.description && (
            <p className="text-xs text-neutral-500 mt-0.5 line-clamp-2">{item.description}</p>
          )}
        </div>
        <div className="flex items-center gap-2 mt-2">
          <span className={`text-sm font-bold ${O.primaryText}`}>{fmt(item.unitPriceCents)}</span>
          {hasModifiers ? (
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); onOpenCustomize(); }}
              className={`w-7 h-7 rounded-full ${O.primary} ${O.primaryHover} text-white grid place-items-center shadow-sm active:scale-95 transition-transform`}
            >
              <IconPlus size={14} />
            </button>
          ) : qtySimple === 0 ? (
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); onAddSimple(); }}
              className={`w-7 h-7 rounded-full ${O.primary} ${O.primaryHover} text-white grid place-items-center shadow-sm active:scale-95 transition-transform`}
            >
              <IconPlus size={14} />
            </button>
          ) : (
            <div className={`flex items-center gap-0 ${O.primary} rounded-full shadow-sm overflow-hidden`}>
              <button type="button" onClick={(e) => { e.stopPropagation(); onDecSimple(); }} className="flex items-center justify-center w-7 h-7 text-white hover:bg-[#C2410C] transition-colors">
                {qtySimple === 1 ? <IconTrash size={11} /> : <IconMinus size={13} />}
              </button>
              <span className="text-white text-xs font-bold w-5 text-center tabular-nums">{qtySimple}</span>
              <button type="button" onClick={(e) => { e.stopPropagation(); onAddSimple(); }} className="flex items-center justify-center w-7 h-7 text-white hover:bg-[#C2410C] transition-colors">
                <IconPlus size={13} />
              </button>
            </div>
          )}
        </div>
      </div>

      {hasImage && (
        <div className="shrink-0">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={item.imageUrl} alt={item.name} className="w-[100px] h-[100px] rounded-xl object-cover" loading="lazy" />
        </div>
      )}
    </div>
  );
}

/* ═══════════════════════════════════════════
   CartSidebar (desktop — thumbnails, totals, orange checkout)
   ═══════════════════════════════════════════ */

function CartSidebar({
  lines, subtotal, groupMap, onAdd, onDec, onCheckout,
}: {
  lines: CartLine[]; subtotal: number; groupMap: Map<string, ModifierGroup>;
  onAdd: (lineId: string) => void; onDec: (lineId: string) => void; onCheckout: () => void;
}) {
  const itemCount = lines.reduce((s, l) => s + l.quantity, 0);

  return (
    <div className="space-y-4">
      <div className="bg-white rounded-2xl shadow-sm border border-neutral-100 overflow-hidden flex flex-col max-h-[calc(100vh-140px)]">
        <div className="px-5 pt-5 pb-3 border-b border-neutral-100">
          <div className="flex items-center gap-2.5">
            <div className={`w-9 h-9 rounded-xl ${O.primary} flex items-center justify-center text-white`}>
              <IconBag size={17} />
            </div>
            <div>
              <h2 className="font-bold text-base text-neutral-900">Your Cart</h2>
              {itemCount > 0 && <p className="text-xs text-neutral-500">{itemCount} item{itemCount !== 1 ? "s" : ""}</p>}
            </div>
          </div>
        </div>

        {lines.length === 0 ? (
          <div className="flex-1 flex flex-col items-center justify-center px-5 py-10 text-center">
            <div className={`w-14 h-14 rounded-full ${O.primaryBg10} flex items-center justify-center mb-3`}>
              <IconBag size={24} />
            </div>
            <p className="text-sm font-medium text-neutral-500">Your cart is empty</p>
            <p className="text-xs text-neutral-400 mt-1">Add items to get started</p>
          </div>
        ) : (
          <>
            <div className="flex-1 overflow-y-auto px-5 py-3 space-y-4">
              {lines.map((l) => (
                  <div key={l.lineId} className="flex items-start gap-3">
                    {l.item.imageUrl ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img src={l.item.imageUrl} alt={l.item.name} className="w-14 h-14 rounded-xl object-cover shrink-0" />
                    ) : (
                      <div className="w-14 h-14 rounded-xl bg-neutral-100 shrink-0 flex items-center justify-center text-neutral-300"><IconStore size={20} /></div>
                    )}
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-semibold text-neutral-900 truncate">{l.item.name}</p>
                      <CartLinePriceBlock line={l} groupMap={groupMap} />
                      <div className={`flex items-center gap-0 mt-2 ${O.primaryText}`}>
                        <button type="button" onClick={() => onDec(l.lineId)} className="w-6 h-6 rounded-full border border-[#EA580C] flex items-center justify-center hover:bg-orange-50 transition-colors">
                          {l.quantity === 1 ? <IconTrash size={10} /> : <IconMinus size={12} />}
                        </button>
                        <span className="text-sm font-bold w-7 text-center tabular-nums text-neutral-900">{l.quantity}</span>
                        <button type="button" onClick={() => onAdd(l.lineId)} className="w-6 h-6 rounded-full border border-[#EA580C] flex items-center justify-center hover:bg-orange-50 transition-colors">
                          <IconPlus size={12} />
                        </button>
                      </div>
                    </div>
                  </div>
              ))}
            </div>

            <div className="border-t border-neutral-100 px-5 py-4 space-y-2">
              <div className="flex justify-between text-sm">
                <span className="text-neutral-500">Subtotal</span>
                <span className="font-semibold text-neutral-900 tabular-nums">{fmt(subtotal)}</span>
              </div>
              <div className="flex justify-between text-sm font-bold text-neutral-900 pt-1 border-t border-neutral-100">
                <span>Total</span>
                <span className="tabular-nums">{fmt(subtotal)}</span>
              </div>
              <button
                type="button"
                onClick={onCheckout}
                className={`w-full h-12 mt-2 rounded-xl ${O.primary} ${O.primaryHover} text-white font-semibold text-[15px] flex items-center justify-center gap-2 active:scale-[0.98] transition-all`}
              >
                Checkout <IconArrowRight size={16} />
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   MobileCartBar (sticky bottom on mobile)
   ═══════════════════════════════════════════ */

function MobileCartBar({ count, subtotal, onOpen }: { count: number; subtotal: number; onOpen: () => void }) {
  if (count === 0) return null;
  return (
    <div className="fixed bottom-0 inset-x-0 z-30 p-3 lg:hidden">
      <button
        type="button"
        onClick={onOpen}
        className={`w-full h-14 rounded-2xl ${O.primary} ${O.primaryHover} text-white flex items-center justify-between px-5 shadow-[0_4px_30px_rgba(234,88,12,0.3)] active:scale-[0.98] transition-transform`}
      >
        <span className="flex items-center gap-2">
          <span className="bg-white/20 text-white text-xs font-bold w-6 h-6 rounded-full flex items-center justify-center tabular-nums">{count}</span>
          <span className="font-semibold text-[15px]">View cart</span>
        </span>
        <span className="font-semibold text-[15px] tabular-nums">{fmt(subtotal)}</span>
      </button>
    </div>
  );
}

/* ═══════════════════════════════════════════
   MobileCartSheet
   ═══════════════════════════════════════════ */

function MobileCartSheet({
  open, lines, subtotal, groupMap, onClose, onAdd, onDec, onCheckout,
}: {
  open: boolean; lines: CartLine[]; subtotal: number; groupMap: Map<string, ModifierGroup>;
  onClose: () => void; onAdd: (lineId: string) => void; onDec: (lineId: string) => void; onCheckout: () => void;
}) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-40 lg:hidden">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="absolute bottom-0 inset-x-0 bg-white rounded-t-3xl max-h-[85vh] flex flex-col animate-slide-up">
        <div className="flex justify-center pt-3 pb-1"><div className="w-10 h-1 rounded-full bg-neutral-300" /></div>
        <div className="flex items-center justify-between px-5 pb-3 border-b border-neutral-100">
          <h2 className="font-bold text-lg">Your cart</h2>
          <button type="button" onClick={onClose} className="p-1.5 rounded-full hover:bg-neutral-100 transition-colors"><IconX size={20} /></button>
        </div>
        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          {lines.map((l) => (
              <div key={l.lineId} className="flex items-start gap-3">
                {l.item.imageUrl ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={l.item.imageUrl} alt={l.item.name} className="w-12 h-12 rounded-lg object-cover shrink-0" />
                ) : (
                  <div className="w-12 h-12 rounded-lg bg-neutral-100 shrink-0 flex items-center justify-center text-neutral-300"><IconStore size={18} /></div>
                )}
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-semibold text-neutral-900 truncate">{l.item.name}</p>
                  <CartLinePriceBlock line={l} groupMap={groupMap} compact />
                  <div className={`flex items-center gap-0 mt-2 ${O.primaryText}`}>
                    <button type="button" onClick={() => onDec(l.lineId)} className="w-7 h-7 rounded-full border border-[#EA580C] flex items-center justify-center hover:bg-orange-50 transition-colors">
                      {l.quantity === 1 ? <IconTrash size={11} /> : <IconMinus size={13} />}
                    </button>
                    <span className="text-sm font-bold w-6 text-center tabular-nums text-neutral-900">{l.quantity}</span>
                    <button type="button" onClick={() => onAdd(l.lineId)} className="w-7 h-7 rounded-full border border-[#EA580C] flex items-center justify-center hover:bg-orange-50 transition-colors">
                      <IconPlus size={13} />
                    </button>
                  </div>
                </div>
              </div>
          ))}
        </div>
        <div className="border-t border-neutral-100 px-5 py-4 space-y-3 pb-6">
          <div className="flex justify-between text-sm font-bold text-neutral-900">
            <span>Total</span><span className="tabular-nums">{fmt(subtotal)}</span>
          </div>
          <button
            type="button"
            onClick={() => { onClose(); onCheckout(); }}
            className={`w-full h-14 rounded-2xl ${O.primary} ${O.primaryHover} text-white font-semibold text-base flex items-center justify-center gap-2 active:scale-[0.98] transition-all`}
          >
            Checkout <IconArrowRight size={16} />
          </button>
        </div>
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   CheckoutModal (orange accents)
   ═══════════════════════════════════════════ */

function CheckoutModal({
  open, cfg, lines, subtotal, groupMap, onClose, onSubmit, submitting, submitError,
}: {
  open: boolean; cfg: PublicConfig; lines: CartLine[]; subtotal: number;
  groupMap: Map<string, ModifierGroup>; onClose: () => void;
  onSubmit: (data: { customerName: string; customerPhone: string; customerEmail: string; paymentChoice: "PAY_AT_STORE" | "PAY_ONLINE_HPP" }) => void;
  submitting: boolean; submitError: string | null;
}) {
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [email, setEmail] = useState("");
  const [pay, setPay] = useState<"PAY_AT_STORE" | "PAY_ONLINE_HPP">(cfg.allowPayOnlineHpp ? "PAY_ONLINE_HPP" : "PAY_AT_STORE");

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={!submitting ? onClose : undefined} />
      <div className="relative bg-white rounded-t-3xl sm:rounded-2xl w-full sm:max-w-lg max-h-[90vh] overflow-y-auto shadow-2xl animate-slide-up sm:animate-fade-in">
        <div className="sticky top-0 bg-white z-10 flex items-center justify-between px-6 pt-5 pb-4 border-b border-neutral-100">
          <h2 className="font-bold text-xl text-neutral-900">Checkout</h2>
          {!submitting && (<button type="button" onClick={onClose} className="p-2 -mr-2 rounded-full hover:bg-neutral-100 transition-colors"><IconX size={20} /></button>)}
        </div>
        <div className="px-6 py-5 space-y-5">
          <div>
            <h3 className="text-xs font-semibold text-neutral-400 uppercase tracking-wider mb-3">Order summary</h3>
            <div className="space-y-2">
              {lines.map((l) => (
                  <div key={l.lineId} className="text-sm border-b border-neutral-100 pb-2 last:border-0">
                    <div className="text-neutral-700 min-w-0">
                      <span className="font-semibold">{l.item.name}</span>
                      <CartLinePriceBlock line={l} groupMap={groupMap} compact />
                    </div>
                  </div>
              ))}
              <div className="flex justify-between text-sm font-bold pt-2 border-t border-neutral-100">
                <span>Total</span><span className="tabular-nums">{fmt(subtotal)}</span>
              </div>
            </div>
          </div>
          <div>
            <h3 className="text-xs font-semibold text-neutral-400 uppercase tracking-wider mb-3">Your info</h3>
            <div className="space-y-3">
              <input placeholder="Name *" value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" className="w-full h-12 px-4 rounded-xl border border-neutral-200 text-sm outline-none focus:border-[#EA580C] focus:ring-1 focus:ring-[#EA580C]/20 transition-colors" />
              <input placeholder="Phone" value={phone} onChange={(e) => setPhone(e.target.value)} autoComplete="tel" type="tel" className="w-full h-12 px-4 rounded-xl border border-neutral-200 text-sm outline-none focus:border-[#EA580C] focus:ring-1 focus:ring-[#EA580C]/20 transition-colors" />
              <input placeholder="Email (optional)" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" type="email" className="w-full h-12 px-4 rounded-xl border border-neutral-200 text-sm outline-none focus:border-[#EA580C] focus:ring-1 focus:ring-[#EA580C]/20 transition-colors" />
            </div>
          </div>
          <div>
            <h3 className="text-xs font-semibold text-neutral-400 uppercase tracking-wider mb-3">Payment</h3>
            <div className="space-y-2">
              {cfg.allowPayInStore && (
                <label className={`flex items-start gap-3 p-3.5 rounded-xl border cursor-pointer transition-colors ${pay === "PAY_AT_STORE" ? "border-[#EA580C] bg-orange-50" : "border-neutral-200 hover:border-neutral-300"}`}>
                  <input type="radio" name="payment" className="mt-0.5 accent-[#EA580C]" checked={pay === "PAY_AT_STORE"} onChange={() => setPay("PAY_AT_STORE")} />
                  <div><p className="text-sm font-semibold text-neutral-900">Pay at the store</p><p className="text-xs text-neutral-500 mt-0.5">Cash or card when you pick up</p></div>
                </label>
              )}
              {cfg.allowPayOnlineHpp && (
                <label className={`flex items-start gap-3 p-3.5 rounded-xl border cursor-pointer transition-colors ${pay === "PAY_ONLINE_HPP" ? "border-[#EA580C] bg-orange-50" : "border-neutral-200 hover:border-neutral-300"}`}>
                  <input type="radio" name="payment" className="mt-0.5 accent-[#EA580C]" checked={pay === "PAY_ONLINE_HPP"} onChange={() => setPay("PAY_ONLINE_HPP")} />
                  <div><p className="text-sm font-semibold text-neutral-900">Pay now with card</p><p className="text-xs text-neutral-500 mt-0.5">Secure online payment</p></div>
                </label>
              )}
            </div>
          </div>
          {submitError && (<div className="rounded-xl bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">{submitError}</div>)}
        </div>
        <div className="sticky bottom-0 bg-white border-t border-neutral-100 px-6 py-4 pb-6">
          <button
            type="button"
            disabled={submitting}
            onClick={() => onSubmit({ customerName: name, customerPhone: phone, customerEmail: email, paymentChoice: pay })}
            className={`w-full h-14 rounded-2xl ${O.primary} ${O.primaryHover} text-white font-semibold text-base disabled:bg-neutral-300 disabled:text-neutral-500 active:scale-[0.98] transition-all`}
          >
            {submitting ? "Placing order\u2026" : `Place order \u00B7 ${fmt(subtotal)}`}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   CustomizeSheet (orange accents)
   ═══════════════════════════════════════════ */

function draftPicksByGroup(draft: Record<string, string[]>): Record<string, readonly string[]> {
  const out: Record<string, readonly string[]> = {};
  for (const [k, v] of Object.entries(draft)) {
    if (v?.length) out[k] = v;
  }
  return out;
}

function CustomizeSheet({
  open, item, groupMap, onClose, onConfirm,
}: {
  open: boolean;
  item: MenuItem | null;
  groupMap: Map<string, ModifierGroup>;
  onClose: () => void;
  onConfirm: (selections: ModifierSelection[]) => void;
}) {
  const [draft, setDraft] = useState<Record<string, string[]>>({});
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!open || !item) return;
    const init: Record<string, string[]> = {};
    for (const id of item.modifierGroupIds) {
      if (groupMap.has(id)) init[id] = [];
    }
    setDraft(init);
    setErr(null);
  }, [open, item?.id, groupMap]);

  const picksByGroup = useMemo(() => draftPicksByGroup(draft), [draft]);

  const visibleGroupIds = useMemo(() => {
    if (!item) return [];
    return expandModifierGroupIdsFromPicks(
      item.modifierGroupIds,
      picksByGroup,
      (id) => groupMap.has(id),
      (gid, oid) =>
        groupMap.get(gid)?.options.find((o) => o.id === oid)?.triggersModifierGroupIds ?? [],
    );
  }, [item, picksByGroup, groupMap]);

  const visibleKey = visibleGroupIds.join("\0");
  const visibleGroupIdsRef = useRef(visibleGroupIds);
  visibleGroupIdsRef.current = visibleGroupIds;

  useEffect(() => {
    if (!open || !item) return;
    const ids = visibleGroupIdsRef.current;
    setDraft((prev) => {
      const next: Record<string, string[]> = {};
      for (const id of ids) {
        next[id] = [...(prev[id] ?? [])];
      }
      const prevKeys = Object.keys(prev).sort().join("|");
      const nextKeys = [...ids].sort().join("|");
      if (prevKeys === nextKeys) {
        let same = true;
        for (const id of ids) {
          const a = prev[id] ?? [];
          const b = next[id];
          if (a.length !== b.length || a.some((x, i) => x !== b[i])) {
            same = false;
            break;
          }
        }
        if (same) return prev;
      }
      return next;
    });
  }, [open, item?.id, visibleKey]);

  const visibleGroups = useMemo(
    () => visibleGroupIds.map((id) => groupMap.get(id)).filter((g): g is ModifierGroup => g != null),
    [visibleGroupIds, groupMap],
  );

  const draftSelections = useMemo((): ModifierSelection[] => {
    const out: ModifierSelection[] = [];
    for (const id of visibleGroupIds) {
      for (const oid of draft[id] ?? []) out.push({ groupId: id, optionId: oid });
    }
    return out;
  }, [draft, visibleGroupIds]);

  const previewUnitCents = item != null ? unitPriceCentsForLine(item, draftSelections, groupMap) : 0;

  const toggle = (g: ModifierGroup, optionId: string) => {
    setDraft((prev) => {
      const cur = prev[g.id] ?? [];
      if (g.maxSelection <= 1) return { ...prev, [g.id]: cur[0] === optionId ? [] : [optionId] };
      const set = new Set(cur);
      if (set.has(optionId)) set.delete(optionId); else if (set.size < g.maxSelection) set.add(optionId);
      return { ...prev, [g.id]: [...set] };
    });
    setErr(null);
  };

  if (!open || !item) return null;

  if (visibleGroups.length === 0) {
    return (
      <div className="fixed inset-0 z-[60] flex items-end sm:items-center justify-center">
        <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
        <div className="relative bg-white rounded-t-3xl sm:rounded-2xl w-full sm:max-w-md p-6 shadow-2xl animate-slide-up sm:animate-fade-in">
          <h2 className="font-bold text-lg text-neutral-900">{item.name}</h2>
          <p className="text-sm text-neutral-600 mt-2">Modifier options are not available right now.</p>
          <button type="button" onClick={onClose} className={`mt-5 w-full h-11 rounded-xl ${O.primary} text-white font-semibold text-sm`}>Close</button>
        </div>
      </div>
    );
  }

  const handleConfirm = () => {
    for (const g of visibleGroups) {
      const n = (draft[g.id] ?? []).length;
      if (n < g.minSelection || n > g.maxSelection) { setErr(`Choose ${g.minSelection}\u2013${g.maxSelection} option(s) for \u201c${g.name}\u201d.`); return; }
    }
    onConfirm(draftSelections);
    onClose();
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-end sm:items-center justify-center">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-white rounded-t-3xl sm:rounded-2xl w-full sm:max-w-lg max-h-[90vh] flex flex-col shadow-2xl animate-slide-up sm:animate-fade-in">
        <div className="sticky top-0 bg-white z-10 flex items-center justify-between px-5 pt-4 pb-3 border-b border-neutral-100">
          <div className="min-w-0 pr-2">
            <h2 className="font-bold text-lg text-neutral-900 truncate">{item.name}</h2>
            <p className={`text-sm font-bold ${O.primaryText} mt-0.5`}>{fmt(previewUnitCents)} each</p>
          </div>
          <button type="button" onClick={onClose} className="p-2 rounded-full hover:bg-neutral-100 shrink-0"><IconX size={20} /></button>
        </div>
        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-6">
          {visibleGroups.map((g) => (
            <div key={g.id}>
              <p className="text-sm font-semibold text-neutral-900">{g.name}{g.minSelection > 0 && <span className="text-red-600 font-normal"> *</span>}</p>
              <p className="text-xs text-neutral-500 mt-0.5 mb-2">{g.maxSelection <= 1 ? "Choose one" : `Choose ${g.minSelection}\u2013${g.maxSelection}`}</p>
              <div className="flex flex-col gap-2">
                {g.options.map((opt) => {
                  const picked = (draft[g.id] ?? []).includes(opt.id);
                  const priceLabel = g.groupType === "REMOVE" ? "" : opt.price > 0 ? ` +${fmt(Math.round(opt.price * 100))}` : "";
                  const optImg = opt.imageUrl?.trim();
                  return (
                    <button key={opt.id} type="button" onClick={() => toggle(g, opt.id)}
                      className={`flex items-center justify-between gap-3 text-left rounded-xl border px-3 py-2.5 text-sm transition-colors ${
                        picked ? "border-[#EA580C] bg-orange-50" : "border-neutral-200 hover:border-neutral-300"
                      }`}
                    >
                      <span className="flex items-center gap-3 min-w-0 flex-1">
                        <span className="shrink-0 w-11 h-11 rounded-lg border border-neutral-200 bg-neutral-100 overflow-hidden flex items-center justify-center">
                          {optImg ? (
                            // eslint-disable-next-line @next/next/no-img-element
                            <img src={optImg} alt="" className="w-full h-full object-cover" />
                          ) : (
                            <span className="text-neutral-300"><IconStore size={18} /></span>
                          )}
                        </span>
                        <span className="font-medium text-neutral-900 truncate">{opt.name}</span>
                      </span>
                      {priceLabel ? <span className="text-neutral-600 tabular-nums text-xs shrink-0">{priceLabel}</span> : null}
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
          {err && <div className="rounded-xl bg-red-50 border border-red-200 px-3 py-2 text-sm text-red-700">{err}</div>}
        </div>
        <div className="sticky bottom-0 bg-white border-t border-neutral-100 px-5 py-4">
          <button type="button" onClick={handleConfirm} className={`w-full h-12 rounded-2xl ${O.primary} ${O.primaryHover} text-white font-semibold text-[15px] active:scale-[0.98] transition-all`}>
            Add to cart &middot; {fmt(previewUnitCents)}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   TrustBar
   ═══════════════════════════════════════════ */

function TrustBar({ prepTimeLabel }: { prepTimeLabel: string }) {
  const items = [
    { icon: <IconTruck size={22} />, title: "Fast Delivery", desc: prepTimeLabel || "20\u201330 min" },
    { icon: <IconLeaf size={22} />, title: "Fresh & Tasty", desc: "Made with love" },
    { icon: <IconShield size={22} />, title: "Secure Payment", desc: "100% safe" },
    { icon: <IconStar size={22} />, title: "Best Quality", desc: "Premium ingredients" },
  ];
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 max-w-4xl mx-auto">
      {items.map((t) => (
        <div key={t.title} className="flex items-center gap-3 bg-white rounded-xl px-4 py-3 shadow-sm border border-neutral-100">
          <div className={`shrink-0 ${O.primaryText}`}>{t.icon}</div>
          <div>
            <p className="text-sm font-semibold text-neutral-900">{t.title}</p>
            <p className="text-xs text-neutral-500">{t.desc}</p>
          </div>
        </div>
      ))}
    </div>
  );
}

/* ═══════════════════════════════════════════
   Main Page
   ═══════════════════════════════════════════ */

function PublicOrderPageInner() {
  const { slug } = useParams<{ slug: string }>();
  const router = useRouter();
  const searchParams = useSearchParams();

  const [cfg, setCfg] = useState<PublicConfig | null>(null);
  const [storefront, setStorefront] = useState<PublicStorefront | null>(null);
  const [menu, setMenu] = useState<{ categories: MenuCategory[]; items: MenuItem[]; modifierGroups: ModifierGroup[]; bestSellerItemIds?: string[] } | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [menuLoading, setMenuLoading] = useState(false);

  type CartRowState = { lineId: string; itemId: string; quantity: number; selections: ModifierSelection[] };
  const [cartRows, setCartRows] = useState<CartRowState[]>([]);
  const [customizeItemId, setCustomizeItemId] = useState<string | null>(null);
  const [activeCategoryId, setActiveCategoryId] = useState<string>("ALL");
  const [checkoutOpen, setCheckoutOpen] = useState(false);
  const [mobileCartOpen, setMobileCartOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const categoryRefs = useRef<Record<string, HTMLElement | null>>({});
  const setCategoryRef = useCallback((id: string, el: HTMLElement | null) => { categoryRefs.current[id] = el; }, []);

  const paymentFailed = searchParams.get("paymentFailed") === "1";
  const paymentCancelled = searchParams.get("paymentCancelled") === "1";

  /* ── Data loading ── */

  const loadConfig = useCallback(async () => {
    const res = await fetch("/api/online-ordering/storefront", { cache: "no-store" });
    const data = (await res.json()) as PublicStorefront & { error?: string };
    if (!res.ok) { setLoadError(data.error || "Could not load store."); setCfg(null); return; }
    if (data.slug && data.slug !== slug) { router.replace(`/order/${data.slug}`); return; }
    setLoadError(null);
    setStorefront(data);
    setCfg({ enabled: data.enabled, businessName: data.businessName, logoUrl: data.logoUrl, slug: data.slug, isOpen: data.isOpen, prepTimeLabel: data.prepTimeLabel, allowPayInStore: data.allowPayInStore, allowPayOnlineHpp: data.allowPayOnlineHpp });
  }, [slug, router]);

  const loadMenu = useCallback(async () => {
    setMenuLoading(true);
    try {
      const res = await fetch("/api/online-ordering/menu", { cache: "no-store" });
      const data = (await res.json()) as { categories?: MenuCategory[]; items?: MenuItem[]; modifierGroups?: ModifierGroup[]; bestSellerItemIds?: string[]; error?: string };
      if (!res.ok) { setMenu(null); setLoadError(data.error || "Menu unavailable."); return; }
      const rawItems = Array.isArray(data.items) ? data.items : [];
      const items: MenuItem[] = rawItems.map((it) => ({ ...it, modifierGroupIds: Array.isArray(it.modifierGroupIds) ? it.modifierGroupIds.filter((x): x is string => typeof x === "string" && x.length > 0) : [] }));
      setMenu({ categories: Array.isArray(data.categories) ? data.categories : [], items, modifierGroups: Array.isArray(data.modifierGroups) ? data.modifierGroups : [], bestSellerItemIds: Array.isArray(data.bestSellerItemIds) ? data.bestSellerItemIds : [] });
      setLoadError(null);
    } finally { setMenuLoading(false); }
  }, []);

  useEffect(() => { void loadConfig(); const id = setInterval(() => void loadConfig(), 45_000); return () => clearInterval(id); }, [loadConfig]);
  useEffect(() => { if (!cfg?.enabled) { setMenu(null); return; } void loadMenu(); }, [cfg?.enabled, loadMenu]);

  /* ── Derived ── */

  const itemsByCategory = useMemo(() => {
    if (!menu) return new Map<string, MenuItem[]>();
    const m = new Map<string, MenuItem[]>();
    for (const it of menu.items) {
      const keys = it.categoryIds.length > 0 ? it.categoryIds : [it.categoryId].filter(Boolean);
      for (const c of (keys.length > 0 ? keys : ["_uncat"])) { const list = m.get(c) ?? []; list.push(it); m.set(c, list); }
    }
    return m;
  }, [menu]);

  const visibleCategories = useMemo(() => menu?.categories ?? [], [menu]);

  const groupMap = useMemo(() => {
    if (!menu) return new Map<string, ModifierGroup>();
    return new Map(menu.modifierGroups.map((g) => [g.id, g]));
  }, [menu]);

  const cartLines: CartLine[] = useMemo(() => {
    if (!menu) return [];
    return cartRows.map((row) => { const item = menu.items.find((i) => i.id === row.itemId); if (!item) return null; return { lineId: row.lineId, itemId: row.itemId, quantity: row.quantity, item, selections: row.selections }; }).filter((x): x is CartLine => x != null);
  }, [cartRows, menu]);

  const subtotalCents = useMemo(() => cartLines.reduce((s, l) => s + unitPriceCentsForLine(l.item, l.selections, groupMap) * l.quantity, 0), [cartLines, groupMap]);
  const cartCount = useMemo(() => cartLines.reduce((s, l) => s + l.quantity, 0), [cartLines]);

  const simpleQtyByItemId = useMemo(() => {
    const m = new Map<string, number>();
    for (const row of cartRows) { if (row.selections.length === 0) m.set(row.itemId, (m.get(row.itemId) ?? 0) + row.quantity); }
    return m;
  }, [cartRows]);

  const customizeItem = useMemo(() => { if (!menu || !customizeItemId) return null; return menu.items.find((i) => i.id === customizeItemId) ?? null; }, [menu, customizeItemId]);

  const popularItems: MenuItem[] = useMemo(() => {
    if (!menu) return [];
    const itemMap = new Map(menu.items.map((it) => [it.id, it]));
    if (menu.bestSellerItemIds && menu.bestSellerItemIds.length > 0) {
      return menu.bestSellerItemIds.slice(0, 5).map((id) => itemMap.get(id)).filter((it): it is MenuItem => Boolean(it));
    }
    if (storefront?.featuredItemIds && storefront.featuredItemIds.length > 0) {
      return storefront.featuredItemIds.slice(0, 5).map((id) => itemMap.get(id)).filter((it): it is MenuItem => Boolean(it));
    }
    return menu.items.filter((it) => it.imageUrl).slice(0, 5);
  }, [menu, storefront]);

  const scrollToCategory = useCallback((categoryId: string) => { setActiveCategoryId(categoryId); const el = categoryRefs.current[categoryId]; if (el) el.scrollIntoView({ behavior: "smooth", block: "start" }); }, []);

  const handleHeroClick = useCallback((s: HeroSlide) => {
    if (s.actionType === "CATEGORY" && s.actionValue) scrollToCategory(s.actionValue);
    else if (s.actionType === "ITEM" && s.actionValue && menu) { const ids = menu.items.find((it) => it.id === s.actionValue)?.categoryIds ?? []; if (ids[0]) scrollToCategory(ids[0]); }
    else if (s.actionType === "URL" && s.actionValue) window.open(s.actionValue, "_blank", "noopener,noreferrer");
  }, [menu, scrollToCategory]);

  /* ── Cart actions ── */

  const newLineId = () => typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `ln_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;

  const addSimpleToCart = useCallback((itemId: string) => {
    setCartRows((prev) => {
      const idx = prev.findIndex((r) => r.itemId === itemId && r.selections.length === 0);
      if (idx >= 0) { const next = [...prev]; next[idx] = { ...next[idx], quantity: next[idx].quantity + 1 }; return next; }
      return [...prev, { lineId: newLineId(), itemId, quantity: 1, selections: [] }];
    });
  }, []);

  const addCustomizedLine = useCallback((itemId: string, selections: ModifierSelection[]) => {
    setCartRows((prev) => [...prev, { lineId: newLineId(), itemId, quantity: 1, selections }]);
  }, []);

  const incLine = useCallback((lineId: string) => { setCartRows((prev) => prev.map((r) => (r.lineId === lineId ? { ...r, quantity: r.quantity + 1 } : r))); }, []);
  const decLine = useCallback((lineId: string) => { setCartRows((prev) => prev.flatMap((r) => { if (r.lineId !== lineId) return [r]; if (r.quantity <= 1) return []; return [{ ...r, quantity: r.quantity - 1 }]; })); }, []);

  /* ── Order submit ── */

  const submitOrder = async (data: { customerName: string; customerPhone: string; customerEmail: string; paymentChoice: "PAY_AT_STORE" | "PAY_ONLINE_HPP" }) => {
    setSubmitError(null);
    if (!cfg?.enabled) return;
    const lines = cartLines.map((l) => ({ itemId: l.itemId, quantity: l.quantity, modifierSelections: l.selections.length > 0 ? l.selections : undefined }));
    if (lines.length === 0) { setSubmitError("Your cart is empty."); return; }
    if (!data.customerName.trim()) { setSubmitError("Please enter your name."); return; }
    setSubmitting(true);
    try {
      const res = await fetch("/api/online-ordering/order", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ lines, ...data }) });
      const result = await res.json();
      if (!res.ok) { setSubmitError(result.error || result.detail || "Order failed."); return; }
      if (data.paymentChoice === "PAY_ONLINE_HPP" && result.paymentUrl) { window.location.href = result.paymentUrl; return; }
      if (data.paymentChoice === "PAY_ONLINE_HPP" && result.hppError) { setSubmitError(result.hppError); return; }
      setCheckoutOpen(false);
      setCartRows([]);
      const pay = encodeURIComponent(String(result.paymentChoice ?? ""));
      const oid = encodeURIComponent(String(result.orderId ?? ""));
      router.push(`/order/${slug}/success?orderNumber=${result.orderNumber}&orderId=${oid}&payment=${pay}`);
    } finally { setSubmitting(false); }
  };

  /* ── Loading / error / disabled states ── */

  if (loadError && !cfg) {
    return (<div className={`min-h-screen ${O.bg} flex items-center justify-center p-6`}><div className="text-center"><div className="w-16 h-16 rounded-full bg-white flex items-center justify-center mx-auto mb-4 shadow-sm"><IconStore size={28} /></div><p className="text-neutral-600 text-sm">{loadError}</p></div></div>);
  }
  if (!cfg) {
    return (<div className={`min-h-screen ${O.bg} flex items-center justify-center p-6`}><div className="flex flex-col items-center gap-3"><div className="w-8 h-8 border-2 border-neutral-200 border-t-[#EA580C] rounded-full animate-spin" /><p className="text-neutral-500 text-sm">Loading\u2026</p></div></div>);
  }
  if (!cfg.enabled) {
    return (<div className={`min-h-screen ${O.bg} flex flex-col items-center justify-center p-6 gap-4`}><div className="w-16 h-16 rounded-full bg-white flex items-center justify-center shadow-sm"><IconStore size={28} /></div><h1 className="text-2xl font-bold text-neutral-900">{cfg.businessName}</h1><p className="text-neutral-500 text-center max-w-sm text-sm">Online ordering is not available right now.</p></div>);
  }

  /* ── Render helper for menu item card ── */

  const renderMenuCard = (it: MenuItem) => (
    <MenuItemCard
      key={it.id}
      item={it}
      qtySimple={simpleQtyByItemId.get(it.id) ?? 0}
      hasModifiers={it.modifierGroupIds.length > 0}
      onAddSimple={() => addSimpleToCart(it.id)}
      onOpenCustomize={() => setCustomizeItemId(it.id)}
      onDecSimple={() => { const line = cartRows.find((r) => r.itemId === it.id && r.selections.length === 0); if (line) decLine(line.lineId); }}
    />
  );

  /* ── Main layout ── */

  return (
    <div className={`min-h-screen ${O.bg} text-neutral-900`}>
      {(paymentFailed || paymentCancelled) && (
        <div className={`px-4 py-3 text-center text-sm font-medium ${paymentFailed ? "bg-red-50 text-red-700" : "bg-amber-50 text-amber-700"}`}>
          {paymentFailed ? "Payment was declined or failed. Please try again." : "Payment was cancelled. Your order is still saved."}
        </div>
      )}
      {!cfg.isOpen && (
        <div className="px-4 py-3 text-center text-sm font-medium bg-rose-50 text-rose-700">We&apos;re currently closed. You can still browse the menu.</div>
      )}

      {/* ── Header ── */}
      <header className="sticky top-0 z-20 bg-white border-b border-neutral-100 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 sm:px-6">
          <div className="flex items-center justify-between h-16 gap-4">
            <div className="flex items-center gap-3 min-w-0">
              {cfg.logoUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={cfg.logoUrl} alt="" className="w-9 h-9 rounded-lg object-cover shrink-0" />
              ) : (
                <div className={`w-9 h-9 rounded-lg ${O.primary} flex items-center justify-center text-white shrink-0`}><IconStore size={18} /></div>
              )}
              <div className="min-w-0">
                <h1 className="font-bold text-lg text-neutral-900 truncate leading-tight">{cfg.businessName || "Restaurant"}</h1>
                <div className="flex items-center gap-2 text-xs mt-0.5">
                  <span className={`inline-flex items-center gap-1 font-medium ${cfg.isOpen ? "text-emerald-600" : "text-rose-600"}`}>
                    <span className={`w-1.5 h-1.5 rounded-full ${cfg.isOpen ? "bg-emerald-500" : "bg-rose-500"}`} />{cfg.isOpen ? "Open" : "Closed"}
                  </span>
                  {cfg.prepTimeLabel && <span className="text-neutral-400 flex items-center gap-1"><IconClock size={11} /> {cfg.prepTimeLabel}</span>}
                </div>
              </div>
            </div>

            {/* Desktop category pills */}
            {menu && visibleCategories.length > 0 && (
              <div className="hidden md:flex flex-1 justify-center px-4">
                <CategoryTabs categories={visibleCategories} active={activeCategoryId} onSelect={(id) => { setActiveCategoryId(id); if (id !== "ALL") scrollToCategory(id); }} />
              </div>
            )}

            {/* Cart badge */}
            <button
              type="button"
              onClick={() => cartCount > 0 ? setMobileCartOpen(true) : undefined}
              className="lg:hidden flex items-center gap-2 h-10 px-4 rounded-full bg-white border border-neutral-200 text-neutral-900 text-sm font-semibold shrink-0"
              style={{ visibility: cartCount > 0 ? "visible" : "hidden" }}
            >
              Cart
              <span className={`w-5 h-5 rounded-full ${O.primary} text-white text-[11px] font-bold grid place-items-center`}>{cartCount}</span>
            </button>
            <button
              type="button"
              onClick={() => { setSubmitError(null); setCheckoutOpen(true); }}
              className={`hidden lg:flex items-center gap-2 h-10 px-5 rounded-full bg-white border border-neutral-200 text-neutral-900 text-sm font-semibold shrink-0 ${cartCount === 0 ? "invisible" : ""}`}
            >
              Cart
              <span className={`w-5 h-5 rounded-full ${O.primary} text-white text-[11px] font-bold grid place-items-center`}>{cartCount}</span>
            </button>
          </div>
        </div>

        {/* Mobile category pills */}
        {menu && visibleCategories.length > 0 && (
          <div className="md:hidden max-w-7xl mx-auto px-4 pb-2">
            <CategoryTabs categories={visibleCategories} active={activeCategoryId} onSelect={(id) => { setActiveCategoryId(id); if (id !== "ALL") scrollToCategory(id); }} />
          </div>
        )}
      </header>

      {/* ── Hero ── */}
      {storefront && storefront.heroSlides.length > 0 && (
        <div className="max-w-7xl mx-auto px-4 sm:px-6 pt-5">
          <HeroCarousel slides={storefront.heroSlides} onSlideClick={handleHeroClick} />
        </div>
      )}

      {/* ── Body: 2-column ── */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 py-6">
        <div className="flex gap-6">
          {/* LEFT: menu */}
          <main className="flex-1 min-w-0">
            {menuLoading && (
              <div className="flex items-center justify-center py-20"><div className="w-8 h-8 border-2 border-neutral-200 border-t-[#EA580C] rounded-full animate-spin" /></div>
            )}
            {loadError && <p className="text-sm text-red-600 mb-4">{loadError}</p>}

            {/* Popular section */}
            {menu && !menuLoading && popularItems.length > 0 && activeCategoryId === "ALL" && (
              <div className="mb-8">
                <div className="flex items-center justify-between mb-3">
                  <h2 className="text-lg font-bold text-neutral-900">Popular</h2>
                </div>
                <PopularRow
                  items={popularItems}
                  onItemAction={(it) => {
                    if (it.modifierGroupIds.length > 0) setCustomizeItemId(it.id);
                    else addSimpleToCart(it.id);
                  }}
                />
              </div>
            )}

            {/* Category sections */}
            {menu && !menuLoading && (
              activeCategoryId === "ALL" ? (
                <div className="space-y-8">
                  {visibleCategories.map((cat) => {
                    const catItems = itemsByCategory.get(cat.id) ?? [];
                    if (catItems.length === 0) return null;
                    return (
                      <section key={cat.id} ref={(el) => setCategoryRef(cat.id, el)} className="scroll-mt-32">
                        <h2 className="text-lg font-bold text-neutral-900 mb-3">{cat.name}</h2>
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                          {catItems.map(renderMenuCard)}
                        </div>
                      </section>
                    );
                  })}
                  {(itemsByCategory.get("_uncat") ?? []).length > 0 && (
                    <section>
                      <h2 className="text-lg font-bold text-neutral-900 mb-3">Other</h2>
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">{(itemsByCategory.get("_uncat") ?? []).map(renderMenuCard)}</div>
                    </section>
                  )}
                </div>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                  {(itemsByCategory.get(activeCategoryId) ?? []).map(renderMenuCard)}
                </div>
              )
            )}

            {menu && !menuLoading && menu.items.length === 0 && (
              <div className="text-center py-20"><p className="text-neutral-500 text-sm">No items on the menu yet.</p></div>
            )}
          </main>

          {/* RIGHT: cart sidebar (desktop) */}
          <aside className="hidden lg:block w-[340px] shrink-0">
            <div className="sticky top-[120px]">
              <CartSidebar lines={cartLines} subtotal={subtotalCents} groupMap={groupMap} onAdd={incLine} onDec={decLine} onCheckout={() => { setSubmitError(null); setCheckoutOpen(true); }} />
            </div>
          </aside>
        </div>
      </div>

      {/* ── Trust bar ── */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 pb-6">
        <TrustBar prepTimeLabel={cfg.prepTimeLabel} />
      </div>

      {/* ── Footer ── */}
      <footer className="border-t border-neutral-200/60 py-6">
        <p className="text-center text-xs font-semibold tracking-widest text-neutral-400 uppercase">
          Powered by Volt Merchant Solutions
        </p>
      </footer>

      {/* Mobile bottom bar + sheet */}
      <MobileCartBar count={cartCount} subtotal={subtotalCents} onOpen={() => setMobileCartOpen(true)} />
      <MobileCartSheet open={mobileCartOpen} lines={cartLines} subtotal={subtotalCents} groupMap={groupMap} onClose={() => setMobileCartOpen(false)} onAdd={incLine} onDec={decLine} onCheckout={() => { setSubmitError(null); setCheckoutOpen(true); }} />

      {/* Checkout modal */}
      <CheckoutModal open={checkoutOpen} cfg={cfg} lines={cartLines} subtotal={subtotalCents} groupMap={groupMap} onClose={() => setCheckoutOpen(false)} onSubmit={(d) => void submitOrder(d)} submitting={submitting} submitError={submitError} />

      <CustomizeSheet
        open={customizeItemId != null && customizeItem != null && customizeItem.modifierGroupIds.length > 0}
        item={customizeItem}
        groupMap={groupMap}
        onClose={() => setCustomizeItemId(null)}
        onConfirm={(selections) => { if (customizeItemId) addCustomizedLine(customizeItemId, selections); setCustomizeItemId(null); }}
      />

      {cartCount > 0 && <div className="h-24 lg:hidden" />}

      <style jsx global>{`
        .scrollbar-hide::-webkit-scrollbar { display: none; }
        .scrollbar-hide { -ms-overflow-style: none; scrollbar-width: none; }
        .popular-strip-scroll {
          scrollbar-width: thin;
          scrollbar-color: #EA580C #FDE8D4;
        }
        .popular-strip-scroll::-webkit-scrollbar { height: 10px; }
        .popular-strip-scroll::-webkit-scrollbar-track { background: #FDE8D4; border-radius: 6px; }
        .popular-strip-scroll::-webkit-scrollbar-thumb { background: #EA580C; border-radius: 6px; }
        .popular-strip-scroll::-webkit-scrollbar-thumb:hover { background: #C2410C; }
        @keyframes slide-up { from { transform: translateY(100%); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
        @keyframes fade-in { from { transform: scale(0.97); opacity: 0; } to { transform: scale(1); opacity: 1; } }
        .animate-slide-up { animation: slide-up 0.3s ease-out; }
        .animate-fade-in { animation: fade-in 0.2s ease-out; }
      `}</style>
    </div>
  );
}

export default function PublicOrderPage() {
  return (
    <Suspense fallback={<div className={`min-h-screen ${O.bg} flex items-center justify-center`}><div className="w-8 h-8 border-2 border-neutral-200 border-t-[#EA580C] rounded-full animate-spin" /></div>}>
      <PublicOrderPageInner />
    </Suspense>
  );
}
