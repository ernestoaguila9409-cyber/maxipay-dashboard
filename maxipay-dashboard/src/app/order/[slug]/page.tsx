"use client";

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";

/* ═══════════════════════════════════════════
   Types
   ═══════════════════════════════════════════ */

type PublicConfig = {
  enabled: boolean;
  businessName: string;
  slug: string;
  allowPayInStore: boolean;
  allowPayOnlineHpp: boolean;
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
};

type MenuCategory = { id: string; name: string; sortOrder: number };
type CartLine = { itemId: string; quantity: number; item: MenuItem };

function fmt(cents: number): string {
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency: "USD",
  }).format(cents / 100);
}

/* ═══════════════════════════════════════════
   Inline SVG icons (avoids extra deps)
   ═══════════════════════════════════════════ */

function IconPlus({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round">
      <path d="M12 5v14M5 12h14" />
    </svg>
  );
}
function IconMinus({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round">
      <path d="M5 12h14" />
    </svg>
  );
}
function IconBag({ size = 20 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
      <path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z" />
      <path d="M3 6h18" />
      <path d="M16 10a4 4 0 01-8 0" />
    </svg>
  );
}
function IconX({ size = 20 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round">
      <path d="M18 6L6 18M6 6l12 12" />
    </svg>
  );
}
function IconTrash({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2" />
    </svg>
  );
}
function IconStore({ size = 20 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z" />
      <polyline points="9 22 9 12 15 12 15 22" />
    </svg>
  );
}

/* ═══════════════════════════════════════════
   CategoryTabs
   ═══════════════════════════════════════════ */

function CategoryTabs({
  categories,
  active,
  onSelect,
}: {
  categories: MenuCategory[];
  active: string;
  onSelect: (id: string) => void;
}) {
  const scrollRef = useRef<HTMLDivElement>(null);

  return (
    <div
      ref={scrollRef}
      className="flex gap-1 overflow-x-auto scrollbar-hide px-1 py-1"
    >
      {[{ id: "ALL", name: "All" } as const, ...categories].map((c) => {
        const isActive = active === c.id;
        return (
          <button
            key={c.id}
            type="button"
            onClick={() => onSelect(c.id)}
            className={`relative shrink-0 px-4 py-2 text-sm font-medium rounded-full transition-all duration-200 whitespace-nowrap ${
              isActive
                ? "bg-black text-white shadow-sm"
                : "bg-transparent text-neutral-600 hover:bg-neutral-100"
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
   MenuItemCard
   ═══════════════════════════════════════════ */

function MenuItemCard({
  item,
  qty,
  onAdd,
  onDec,
}: {
  item: MenuItem;
  qty: number;
  onAdd: () => void;
  onDec: () => void;
}) {
  const hasImage = item.imageUrl.trim().length > 0;

  return (
    <div className="group flex gap-4 bg-white rounded-2xl border border-neutral-100 p-4 transition-shadow duration-200 hover:shadow-[0_4px_24px_rgba(0,0,0,0.08)] cursor-pointer">
      {/* Text */}
      <div className="flex-1 min-w-0 flex flex-col justify-between">
        <div>
          <h3 className="font-semibold text-[15px] text-neutral-900 leading-snug">
            {item.name}
          </h3>
          {item.description && (
            <p className="text-[13px] text-neutral-500 mt-0.5 line-clamp-2 leading-relaxed">
              {item.description}
            </p>
          )}
        </div>
        <p className="text-[14px] font-medium text-neutral-800 mt-2">
          {fmt(item.unitPriceCents)}
        </p>
      </div>

      {/* Image + action */}
      <div className="relative shrink-0 flex flex-col items-end">
        {hasImage && (
          <img
            src={item.imageUrl}
            alt={item.name}
            className="w-[120px] h-[120px] sm:w-[130px] sm:h-[130px] rounded-xl object-cover"
            loading="lazy"
          />
        )}

        {/* Add / quantity stepper */}
        <div className={`${hasImage ? "absolute -bottom-3 right-1" : "mt-auto"}`}>
          {qty === 0 ? (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onAdd();
              }}
              className="flex items-center gap-1.5 h-9 px-4 rounded-full bg-white text-black text-sm font-semibold shadow-md border border-neutral-200 hover:scale-105 active:scale-95 transition-transform"
            >
              <IconPlus size={15} />
              Add
            </button>
          ) : (
            <div className="flex items-center gap-0 bg-black rounded-full shadow-md overflow-hidden">
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  onDec();
                }}
                className="flex items-center justify-center w-9 h-9 text-white hover:bg-neutral-800 transition-colors"
              >
                {qty === 1 ? <IconTrash size={14} /> : <IconMinus size={15} />}
              </button>
              <span className="text-white text-sm font-semibold w-6 text-center tabular-nums">
                {qty}
              </span>
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  onAdd();
                }}
                className="flex items-center justify-center w-9 h-9 text-white hover:bg-neutral-800 transition-colors"
              >
                <IconPlus size={15} />
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   CartSidebar (desktop)
   ═══════════════════════════════════════════ */

function CartSidebar({
  lines,
  subtotal,
  onAdd,
  onDec,
  onCheckout,
}: {
  lines: CartLine[];
  subtotal: number;
  onAdd: (id: string) => void;
  onDec: (id: string) => void;
  onCheckout: () => void;
}) {
  const itemCount = lines.reduce((s, l) => s + l.quantity, 0);

  return (
    <div className="bg-white rounded-2xl border border-neutral-100 shadow-sm overflow-hidden flex flex-col max-h-[calc(100vh-110px)]">
      {/* Header */}
      <div className="px-5 pt-5 pb-3 border-b border-neutral-100">
        <div className="flex items-center gap-2.5">
          <div className="w-9 h-9 rounded-full bg-black flex items-center justify-center">
            <IconBag size={17} />
            <span className="sr-only">Cart</span>
          </div>
          <div>
            <h2 className="font-bold text-[15px] text-neutral-900">
              Cart{itemCount > 0 && ` (${itemCount})`}
            </h2>
          </div>
        </div>
      </div>

      {/* Items */}
      {lines.length === 0 ? (
        <div className="flex-1 flex flex-col items-center justify-center px-5 py-10 text-center">
          <div className="w-14 h-14 rounded-full bg-neutral-100 flex items-center justify-center mb-3">
            <IconBag size={24} />
          </div>
          <p className="text-sm font-medium text-neutral-500">Your cart is empty</p>
          <p className="text-xs text-neutral-400 mt-1">Add items to get started</p>
        </div>
      ) : (
        <>
          <div className="flex-1 overflow-y-auto px-5 py-3 space-y-3">
            {lines.map((l) => (
              <div key={l.itemId} className="flex items-start gap-3">
                {/* Qty stepper */}
                <div className="flex items-center gap-0 bg-neutral-100 rounded-full shrink-0">
                  <button
                    type="button"
                    onClick={() => onDec(l.itemId)}
                    className="flex items-center justify-center w-7 h-7 text-neutral-700 hover:bg-neutral-200 rounded-full transition-colors"
                  >
                    {l.quantity === 1 ? <IconTrash size={12} /> : <IconMinus size={13} />}
                  </button>
                  <span className="text-xs font-semibold w-5 text-center tabular-nums text-neutral-800">
                    {l.quantity}
                  </span>
                  <button
                    type="button"
                    onClick={() => onAdd(l.itemId)}
                    className="flex items-center justify-center w-7 h-7 text-neutral-700 hover:bg-neutral-200 rounded-full transition-colors"
                  >
                    <IconPlus size={13} />
                  </button>
                </div>
                {/* Name + price */}
                <div className="flex-1 min-w-0">
                  <p className="text-[13px] font-medium text-neutral-800 leading-tight truncate">
                    {l.item.name}
                  </p>
                </div>
                <p className="text-[13px] font-medium text-neutral-700 tabular-nums shrink-0">
                  {fmt(l.item.unitPriceCents * l.quantity)}
                </p>
              </div>
            ))}
          </div>

          {/* Totals + checkout */}
          <div className="border-t border-neutral-100 px-5 py-4 space-y-3">
            <div className="flex justify-between text-sm">
              <span className="text-neutral-500">Subtotal</span>
              <span className="font-semibold text-neutral-900 tabular-nums">{fmt(subtotal)}</span>
            </div>
            <button
              type="button"
              onClick={onCheckout}
              className="w-full h-12 rounded-xl bg-black text-white font-semibold text-[15px] hover:bg-neutral-800 active:scale-[0.98] transition-all"
            >
              Go to checkout &middot; {fmt(subtotal)}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

/* ═══════════════════════════════════════════
   MobileCartBar (sticky bottom on mobile)
   ═══════════════════════════════════════════ */

function MobileCartBar({
  count,
  subtotal,
  onOpen,
}: {
  count: number;
  subtotal: number;
  onOpen: () => void;
}) {
  if (count === 0) return null;
  return (
    <div className="fixed bottom-0 inset-x-0 z-30 p-3 lg:hidden">
      <button
        type="button"
        onClick={onOpen}
        className="w-full h-14 rounded-2xl bg-black text-white flex items-center justify-between px-5 shadow-[0_4px_30px_rgba(0,0,0,0.2)] active:scale-[0.98] transition-transform"
      >
        <span className="flex items-center gap-2">
          <span className="bg-neutral-700 text-white text-xs font-bold w-6 h-6 rounded-full flex items-center justify-center tabular-nums">
            {count}
          </span>
          <span className="font-semibold text-[15px]">View cart</span>
        </span>
        <span className="font-semibold text-[15px] tabular-nums">{fmt(subtotal)}</span>
      </button>
    </div>
  );
}

/* ═══════════════════════════════════════════
   MobileCartSheet (full-screen on mobile)
   ═══════════════════════════════════════════ */

function MobileCartSheet({
  open,
  lines,
  subtotal,
  onClose,
  onAdd,
  onDec,
  onCheckout,
}: {
  open: boolean;
  lines: CartLine[];
  subtotal: number;
  onClose: () => void;
  onAdd: (id: string) => void;
  onDec: (id: string) => void;
  onCheckout: () => void;
}) {
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-40 lg:hidden">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="absolute bottom-0 inset-x-0 bg-white rounded-t-3xl max-h-[85vh] flex flex-col animate-slide-up">
        {/* Handle */}
        <div className="flex justify-center pt-3 pb-1">
          <div className="w-10 h-1 rounded-full bg-neutral-300" />
        </div>
        <div className="flex items-center justify-between px-5 pb-3 border-b border-neutral-100">
          <h2 className="font-bold text-lg">Your cart</h2>
          <button type="button" onClick={onClose} className="p-1.5 rounded-full hover:bg-neutral-100 transition-colors">
            <IconX size={20} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          {lines.map((l) => (
            <div key={l.itemId} className="flex items-center gap-3">
              <div className="flex items-center gap-0 bg-neutral-100 rounded-full shrink-0">
                <button type="button" onClick={() => onDec(l.itemId)} className="flex items-center justify-center w-8 h-8 text-neutral-700 hover:bg-neutral-200 rounded-full transition-colors">
                  {l.quantity === 1 ? <IconTrash size={13} /> : <IconMinus size={14} />}
                </button>
                <span className="text-sm font-semibold w-6 text-center tabular-nums">{l.quantity}</span>
                <button type="button" onClick={() => onAdd(l.itemId)} className="flex items-center justify-center w-8 h-8 text-neutral-700 hover:bg-neutral-200 rounded-full transition-colors">
                  <IconPlus size={14} />
                </button>
              </div>
              <p className="flex-1 text-sm font-medium text-neutral-800 truncate">{l.item.name}</p>
              <p className="text-sm font-medium text-neutral-700 tabular-nums shrink-0">{fmt(l.item.unitPriceCents * l.quantity)}</p>
            </div>
          ))}
        </div>

        <div className="border-t border-neutral-100 px-5 py-4 space-y-3 pb-6">
          <div className="flex justify-between text-sm">
            <span className="text-neutral-500">Subtotal</span>
            <span className="font-semibold tabular-nums">{fmt(subtotal)}</span>
          </div>
          <button
            type="button"
            onClick={() => { onClose(); onCheckout(); }}
            className="w-full h-14 rounded-2xl bg-black text-white font-semibold text-base hover:bg-neutral-800 active:scale-[0.98] transition-all"
          >
            Go to checkout &middot; {fmt(subtotal)}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════
   CheckoutModal
   ═══════════════════════════════════════════ */

function CheckoutModal({
  open,
  cfg,
  lines,
  subtotal,
  onClose,
  onSubmit,
  submitting,
  submitError,
}: {
  open: boolean;
  cfg: PublicConfig;
  lines: CartLine[];
  subtotal: number;
  onClose: () => void;
  onSubmit: (data: {
    customerName: string;
    customerPhone: string;
    customerEmail: string;
    paymentChoice: "PAY_AT_STORE" | "PAY_ONLINE_HPP";
  }) => void;
  submitting: boolean;
  submitError: string | null;
}) {
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [email, setEmail] = useState("");
  const [pay, setPay] = useState<"PAY_AT_STORE" | "PAY_ONLINE_HPP">(
    cfg.allowPayOnlineHpp ? "PAY_ONLINE_HPP" : "PAY_AT_STORE"
  );

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={!submitting ? onClose : undefined} />
      <div className="relative bg-white rounded-t-3xl sm:rounded-2xl w-full sm:max-w-lg max-h-[90vh] overflow-y-auto shadow-2xl animate-slide-up sm:animate-fade-in">
        {/* Header */}
        <div className="sticky top-0 bg-white z-10 flex items-center justify-between px-6 pt-5 pb-4 border-b border-neutral-100">
          <h2 className="font-bold text-xl text-neutral-900">Checkout</h2>
          {!submitting && (
            <button type="button" onClick={onClose} className="p-2 -mr-2 rounded-full hover:bg-neutral-100 transition-colors">
              <IconX size={20} />
            </button>
          )}
        </div>

        <div className="px-6 py-5 space-y-5">
          {/* Order summary */}
          <div>
            <h3 className="text-xs font-semibold text-neutral-400 uppercase tracking-wider mb-3">Order summary</h3>
            <div className="space-y-2">
              {lines.map((l) => (
                <div key={l.itemId} className="flex justify-between text-sm">
                  <span className="text-neutral-700">
                    {l.quantity}&times; {l.item.name}
                  </span>
                  <span className="font-medium text-neutral-800 tabular-nums">{fmt(l.item.unitPriceCents * l.quantity)}</span>
                </div>
              ))}
              <div className="flex justify-between text-sm font-semibold pt-2 border-t border-neutral-100">
                <span>Total</span>
                <span className="tabular-nums">{fmt(subtotal)}</span>
              </div>
            </div>
          </div>

          {/* Contact info */}
          <div>
            <h3 className="text-xs font-semibold text-neutral-400 uppercase tracking-wider mb-3">Your info</h3>
            <div className="space-y-3">
              <input
                placeholder="Name *"
                value={name}
                onChange={(e) => setName(e.target.value)}
                autoComplete="name"
                className="w-full h-12 px-4 rounded-xl border border-neutral-200 text-sm text-neutral-900 placeholder:text-neutral-400 outline-none focus:border-black focus:ring-1 focus:ring-black/10 transition-colors"
              />
              <input
                placeholder="Phone"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                autoComplete="tel"
                type="tel"
                className="w-full h-12 px-4 rounded-xl border border-neutral-200 text-sm text-neutral-900 placeholder:text-neutral-400 outline-none focus:border-black focus:ring-1 focus:ring-black/10 transition-colors"
              />
              <input
                placeholder="Email (optional)"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="email"
                type="email"
                className="w-full h-12 px-4 rounded-xl border border-neutral-200 text-sm text-neutral-900 placeholder:text-neutral-400 outline-none focus:border-black focus:ring-1 focus:ring-black/10 transition-colors"
              />
            </div>
          </div>

          {/* Payment */}
          <div>
            <h3 className="text-xs font-semibold text-neutral-400 uppercase tracking-wider mb-3">Payment</h3>
            <div className="space-y-2">
              {cfg.allowPayInStore && (
                <label
                  className={`flex items-start gap-3 p-3.5 rounded-xl border cursor-pointer transition-colors ${
                    pay === "PAY_AT_STORE" ? "border-black bg-neutral-50" : "border-neutral-200 hover:border-neutral-300"
                  }`}
                >
                  <input
                    type="radio"
                    name="payment"
                    className="mt-0.5 accent-black"
                    checked={pay === "PAY_AT_STORE"}
                    onChange={() => setPay("PAY_AT_STORE")}
                  />
                  <div>
                    <p className="text-sm font-semibold text-neutral-900">Pay at the store</p>
                    <p className="text-xs text-neutral-500 mt-0.5">Cash or card when you pick up</p>
                  </div>
                </label>
              )}
              {cfg.allowPayOnlineHpp && (
                <label
                  className={`flex items-start gap-3 p-3.5 rounded-xl border cursor-pointer transition-colors ${
                    pay === "PAY_ONLINE_HPP" ? "border-black bg-neutral-50" : "border-neutral-200 hover:border-neutral-300"
                  }`}
                >
                  <input
                    type="radio"
                    name="payment"
                    className="mt-0.5 accent-black"
                    checked={pay === "PAY_ONLINE_HPP"}
                    onChange={() => setPay("PAY_ONLINE_HPP")}
                  />
                  <div>
                    <p className="text-sm font-semibold text-neutral-900">Pay now with card</p>
                    <p className="text-xs text-neutral-500 mt-0.5">Secure online payment — pay before pickup</p>
                  </div>
                </label>
              )}
            </div>
          </div>

          {submitError && (
            <div className="rounded-xl bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
              {submitError}
            </div>
          )}
        </div>

        {/* Submit */}
        <div className="sticky bottom-0 bg-white border-t border-neutral-100 px-6 py-4 pb-6">
          <button
            type="button"
            disabled={submitting}
            onClick={() => onSubmit({ customerName: name, customerPhone: phone, customerEmail: email, paymentChoice: pay })}
            className="w-full h-14 rounded-2xl bg-black text-white font-semibold text-base hover:bg-neutral-800 disabled:bg-neutral-300 disabled:text-neutral-500 active:scale-[0.98] transition-all"
          >
            {submitting ? "Placing order…" : `Place order · ${fmt(subtotal)}`}
          </button>
        </div>
      </div>
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
  const [menu, setMenu] = useState<{ categories: MenuCategory[]; items: MenuItem[] } | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [menuLoading, setMenuLoading] = useState(false);

  const [cart, setCart] = useState<Record<string, number>>({});
  const [activeCategoryId, setActiveCategoryId] = useState<string>("ALL");

  const [checkoutOpen, setCheckoutOpen] = useState(false);
  const [mobileCartOpen, setMobileCartOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const paymentFailed = searchParams.get("paymentFailed") === "1";
  const paymentCancelled = searchParams.get("paymentCancelled") === "1";

  /* ── Data loading ── */

  const loadConfig = useCallback(async () => {
    const res = await fetch("/api/online-ordering/config", { cache: "no-store" });
    const data = (await res.json()) as PublicConfig & { error?: string };
    if (!res.ok) { setLoadError(data.error || "Could not load store."); setCfg(null); return; }
    if (data.slug && data.slug !== slug) { router.replace(`/order/${data.slug}`); return; }
    setLoadError(null);
    setCfg(data);
  }, [slug, router]);

  const loadMenu = useCallback(async () => {
    setMenuLoading(true);
    try {
      const res = await fetch("/api/online-ordering/menu", { cache: "no-store" });
      const data = await res.json();
      if (!res.ok) { setMenu(null); setLoadError(data.error || "Menu unavailable."); return; }
      setMenu(data as { categories: MenuCategory[]; items: MenuItem[] });
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
      for (const c of (keys.length > 0 ? keys : ["_uncat"])) {
        const list = m.get(c) ?? [];
        list.push(it);
        m.set(c, list);
      }
    }
    return m;
  }, [menu]);

  const visibleCategories = useMemo(() => {
    if (!menu) return [];
    return menu.categories;
  }, [menu]);

  const cartLines: CartLine[] = useMemo(() => {
    if (!menu) return [];
    return Object.entries(cart)
      .filter(([, q]) => q > 0)
      .map(([itemId, quantity]) => {
        const item = menu.items.find((i) => i.id === itemId);
        return item ? { itemId, quantity, item } : null;
      })
      .filter((x): x is CartLine => x != null);
  }, [cart, menu]);

  const subtotalCents = useMemo(() => cartLines.reduce((s, l) => s + l.item.unitPriceCents * l.quantity, 0), [cartLines]);
  const cartCount = useMemo(() => cartLines.reduce((s, l) => s + l.quantity, 0), [cartLines]);

  /* ── Cart actions ── */

  const addToCart = (id: string) => setCart((c) => ({ ...c, [id]: (c[id] ?? 0) + 1 }));
  const decFromCart = (id: string) => setCart((c) => {
    const n = (c[id] ?? 0) - 1;
    const next = { ...c };
    if (n <= 0) delete next[id];
    else next[id] = n;
    return next;
  });

  /* ── Order submit ── */

  const submitOrder = async (data: {
    customerName: string;
    customerPhone: string;
    customerEmail: string;
    paymentChoice: "PAY_AT_STORE" | "PAY_ONLINE_HPP";
  }) => {
    setSubmitError(null);
    if (!cfg?.enabled) return;
    const lines = cartLines.map((l) => ({ itemId: l.itemId, quantity: l.quantity }));
    if (lines.length === 0) { setSubmitError("Your cart is empty."); return; }
    if (!data.customerName.trim()) { setSubmitError("Please enter your name."); return; }
    setSubmitting(true);
    try {
      const res = await fetch("/api/online-ordering/order", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ lines, ...data }),
      });
      const result = await res.json();
      if (!res.ok) { setSubmitError(result.error || result.detail || "Order failed."); return; }

      if (data.paymentChoice === "PAY_ONLINE_HPP" && result.paymentUrl) {
        window.location.href = result.paymentUrl;
        return;
      }
      if (data.paymentChoice === "PAY_ONLINE_HPP" && result.hppError) {
        setSubmitError(result.hppError);
        return;
      }

      setCheckoutOpen(false);
      setCart({});
      router.push(`/order/${slug}/success?orderNumber=${result.orderNumber}`);
    } finally { setSubmitting(false); }
  };

  /* ── Loading / error / disabled states ── */

  if (loadError && !cfg) {
    return (
      <div className="min-h-screen bg-[#fafafa] flex items-center justify-center p-6">
        <div className="text-center">
          <div className="w-16 h-16 rounded-full bg-neutral-100 flex items-center justify-center mx-auto mb-4">
            <IconStore size={28} />
          </div>
          <p className="text-neutral-600 text-sm">{loadError}</p>
        </div>
      </div>
    );
  }

  if (!cfg) {
    return (
      <div className="min-h-screen bg-[#fafafa] flex items-center justify-center p-6">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-neutral-200 border-t-black rounded-full animate-spin" />
          <p className="text-neutral-500 text-sm">Loading…</p>
        </div>
      </div>
    );
  }

  if (!cfg.enabled) {
    return (
      <div className="min-h-screen bg-[#fafafa] flex flex-col items-center justify-center p-6 gap-4">
        <div className="w-16 h-16 rounded-full bg-neutral-100 flex items-center justify-center">
          <IconStore size={28} />
        </div>
        <h1 className="text-2xl font-bold text-neutral-900">{cfg.businessName}</h1>
        <p className="text-neutral-500 text-center max-w-sm text-sm">
          Online ordering is not available right now. Please call or visit in person.
        </p>
      </div>
    );
  }

  /* ── Main layout ── */

  return (
    <div className="min-h-screen bg-[#fafafa] text-neutral-900">
      {(paymentFailed || paymentCancelled) && (
        <div className={`px-4 py-3 text-center text-sm font-medium ${paymentFailed ? "bg-red-50 text-red-700" : "bg-amber-50 text-amber-700"}`}>
          {paymentFailed ? "Payment was declined or failed. Please try again." : "Payment was cancelled. Your order is still saved — you can try paying again."}
        </div>
      )}
      {/* Header */}
      <header className="sticky top-0 z-20 bg-white border-b border-neutral-100">
        <div className="max-w-7xl mx-auto px-4 sm:px-6">
          <div className="flex items-center justify-between h-16">
            <div>
              <h1 className="text-xl font-bold text-neutral-900 leading-none">{cfg.businessName}</h1>
              <p className="text-xs text-neutral-500 mt-0.5">Pickup</p>
            </div>

            {/* Desktop cart badge */}
            <button
              type="button"
              onClick={() => cartCount > 0 ? setMobileCartOpen(true) : undefined}
              className="lg:hidden flex items-center gap-2 h-10 px-4 rounded-full bg-black text-white text-sm font-semibold"
              style={{ visibility: cartCount > 0 ? "visible" : "hidden" }}
            >
              <IconBag size={16} />
              {cartCount}
            </button>
          </div>
        </div>

        {/* Category tabs */}
        {menu && visibleCategories.length > 0 && (
          <div className="max-w-7xl mx-auto px-4 sm:px-6 pb-2">
            <CategoryTabs
              categories={visibleCategories}
              active={activeCategoryId}
              onSelect={setActiveCategoryId}
            />
          </div>
        )}
      </header>

      {/* Body: 2-column layout */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 py-6">
        <div className="flex gap-6">
          {/* LEFT: menu */}
          <main className="flex-1 min-w-0">
            {menuLoading && (
              <div className="flex items-center justify-center py-20">
                <div className="w-8 h-8 border-2 border-neutral-200 border-t-black rounded-full animate-spin" />
              </div>
            )}
            {loadError && <p className="text-sm text-red-600 mb-4">{loadError}</p>}

            {menu && !menuLoading && (
              activeCategoryId === "ALL" ? (
                <div className="space-y-8">
                  {visibleCategories.map((cat) => {
                    const catItems = itemsByCategory.get(cat.id) ?? [];
                    if (catItems.length === 0) return null;
                    return (
                      <section key={cat.id}>
                        <h2 className="text-lg font-bold text-neutral-900 mb-3">{cat.name}</h2>
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                          {catItems.map((it) => (
                            <MenuItemCard
                              key={it.id}
                              item={it}
                              qty={cart[it.id] ?? 0}
                              onAdd={() => addToCart(it.id)}
                              onDec={() => decFromCart(it.id)}
                            />
                          ))}
                        </div>
                      </section>
                    );
                  })}
                  {/* Uncategorized items */}
                  {(itemsByCategory.get("_uncat") ?? []).length > 0 && (
                    <section>
                      <h2 className="text-lg font-bold text-neutral-900 mb-3">Other</h2>
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                        {(itemsByCategory.get("_uncat") ?? []).map((it) => (
                          <MenuItemCard
                            key={it.id}
                            item={it}
                            qty={cart[it.id] ?? 0}
                            onAdd={() => addToCart(it.id)}
                            onDec={() => decFromCart(it.id)}
                          />
                        ))}
                      </div>
                    </section>
                  )}
                </div>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                  {(itemsByCategory.get(activeCategoryId) ?? []).map((it) => (
                    <MenuItemCard
                      key={it.id}
                      item={it}
                      qty={cart[it.id] ?? 0}
                      onAdd={() => addToCart(it.id)}
                      onDec={() => decFromCart(it.id)}
                    />
                  ))}
                </div>
              )
            )}

            {menu && !menuLoading && menu.items.length === 0 && (
              <div className="text-center py-20">
                <p className="text-neutral-500 text-sm">No items on the menu yet.</p>
              </div>
            )}
          </main>

          {/* RIGHT: cart sidebar (desktop only) */}
          <aside className="hidden lg:block w-[340px] shrink-0">
            <div className="sticky top-[120px]">
              <CartSidebar
                lines={cartLines}
                subtotal={subtotalCents}
                onAdd={addToCart}
                onDec={decFromCart}
                onCheckout={() => { setSubmitError(null); setCheckoutOpen(true); }}
              />
            </div>
          </aside>
        </div>
      </div>

      {/* Mobile bottom bar + sheet */}
      <MobileCartBar count={cartCount} subtotal={subtotalCents} onOpen={() => setMobileCartOpen(true)} />
      <MobileCartSheet
        open={mobileCartOpen}
        lines={cartLines}
        subtotal={subtotalCents}
        onClose={() => setMobileCartOpen(false)}
        onAdd={addToCart}
        onDec={decFromCart}
        onCheckout={() => { setSubmitError(null); setCheckoutOpen(true); }}
      />

      {/* Checkout modal */}
      <CheckoutModal
        open={checkoutOpen}
        cfg={cfg}
        lines={cartLines}
        subtotal={subtotalCents}
        onClose={() => setCheckoutOpen(false)}
        onSubmit={(d) => void submitOrder(d)}
        submitting={submitting}
        submitError={submitError}
      />

      {/* Spacer for mobile bottom bar */}
      {cartCount > 0 && <div className="h-24 lg:hidden" />}

      <style jsx global>{`
        .scrollbar-hide::-webkit-scrollbar { display: none; }
        .scrollbar-hide { -ms-overflow-style: none; scrollbar-width: none; }
        @keyframes slide-up {
          from { transform: translateY(100%); opacity: 0; }
          to { transform: translateY(0); opacity: 1; }
        }
        @keyframes fade-in {
          from { transform: scale(0.97); opacity: 0; }
          to { transform: scale(1); opacity: 1; }
        }
        .animate-slide-up { animation: slide-up 0.3s ease-out; }
        .animate-fade-in { animation: fade-in 0.2s ease-out; }
      `}</style>
    </div>
  );
}

export default function PublicOrderPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen bg-[#fafafa] flex items-center justify-center">
          <div className="w-8 h-8 border-2 border-neutral-200 border-t-black rounded-full animate-spin" />
        </div>
      }
    >
      <PublicOrderPageInner />
    </Suspense>
  );
}
