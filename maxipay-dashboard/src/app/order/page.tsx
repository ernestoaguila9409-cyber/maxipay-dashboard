"use client";

import { useCallback, useEffect, useMemo, useState } from "react";

type PublicConfig = {
  enabled: boolean;
  businessName: string;
  allowPayInStore: boolean;
  allowPayOnlineStripe: boolean;
};

type MenuItem = {
  id: string;
  name: string;
  description: string;
  categoryId: string;
  categoryIds: string[];
  unitPriceCents: number;
  stock: number;
};

type MenuCategory = { id: string; name: string; sortOrder: number };

function formatMoney(cents: number): string {
  return new Intl.NumberFormat(undefined, { style: "currency", currency: "USD" }).format(
    cents / 100
  );
}

export default function PublicOrderPage() {
  const [cfg, setCfg] = useState<PublicConfig | null>(null);
  const [menu, setMenu] = useState<{ categories: MenuCategory[]; items: MenuItem[] } | null>(
    null
  );
  const [loadError, setLoadError] = useState<string | null>(null);
  const [menuLoading, setMenuLoading] = useState(false);
  const [cart, setCart] = useState<Record<string, number>>({});
  const [activeCategoryId, setActiveCategoryId] = useState<string | "ALL">("ALL");
  const [checkoutOpen, setCheckoutOpen] = useState(false);
  const [customerName, setCustomerName] = useState("");
  const [customerPhone, setCustomerPhone] = useState("");
  const [customerEmail, setCustomerEmail] = useState("");
  const [paymentChoice, setPaymentChoice] = useState<"PAY_AT_STORE" | "PAY_ONLINE_STRIPE">(
    "PAY_AT_STORE"
  );
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const loadConfig = useCallback(async () => {
    const res = await fetch("/api/online-ordering/config", { cache: "no-store" });
    const data = (await res.json()) as PublicConfig & { error?: string };
    if (!res.ok) {
      setLoadError(data.error || "Could not load store.");
      setCfg(null);
      return;
    }
    setLoadError(null);
    setCfg({
      enabled: data.enabled,
      businessName: data.businessName,
      allowPayInStore: data.allowPayInStore,
      allowPayOnlineStripe: data.allowPayOnlineStripe,
    });
  }, []);

  const loadMenu = useCallback(async () => {
    setMenuLoading(true);
    try {
      const res = await fetch("/api/online-ordering/menu", { cache: "no-store" });
      const data = await res.json();
      if (!res.ok) {
        setMenu(null);
        setLoadError(data.error || "Menu unavailable.");
        return;
      }
      setMenu(data as { categories: MenuCategory[]; items: MenuItem[] });
      setLoadError(null);
    } finally {
      setMenuLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadConfig();
    const id = setInterval(() => void loadConfig(), 45_000);
    return () => clearInterval(id);
  }, [loadConfig]);

  useEffect(() => {
    if (!cfg?.enabled) {
      setMenu(null);
      return;
    }
    void loadMenu();
  }, [cfg?.enabled, loadMenu]);

  useEffect(() => {
    if (!cfg) return;
    if (cfg.allowPayInStore) setPaymentChoice("PAY_AT_STORE");
    else if (cfg.allowPayOnlineStripe) setPaymentChoice("PAY_ONLINE_STRIPE");
  }, [cfg]);

  const itemsByCategory = useMemo(() => {
    if (!menu) return new Map<string, MenuItem[]>();
    const m = new Map<string, MenuItem[]>();
    for (const it of menu.items) {
      const keys = it.categoryIds.length > 0 ? it.categoryIds : [it.categoryId].filter(Boolean);
      const cats = keys.length > 0 ? keys : ["_uncat"];
      for (const c of cats) {
        const list = m.get(c) ?? [];
        list.push(it);
        m.set(c, list);
      }
    }
    return m;
  }, [menu]);

  const visibleItems = useMemo(() => {
    if (!menu) return [];
    if (activeCategoryId === "ALL") return menu.items;
    return itemsByCategory.get(activeCategoryId) ?? [];
  }, [menu, activeCategoryId, itemsByCategory]);

  const cartLines = useMemo(() => {
    if (!menu) return [];
    return Object.entries(cart)
      .filter(([, q]) => q > 0)
      .map(([itemId, quantity]) => {
        const item = menu.items.find((i) => i.id === itemId);
        if (!item) return null;
        return { itemId, quantity, item };
      })
      .filter((x): x is NonNullable<typeof x> => x != null);
  }, [cart, menu]);

  const subtotalCents = useMemo(
    () => cartLines.reduce((s, l) => s + l.item.unitPriceCents * l.quantity, 0),
    [cartLines]
  );

  const addToCart = (id: string) => {
    setCart((c) => ({ ...c, [id]: (c[id] ?? 0) + 1 }));
  };

  const decFromCart = (id: string) => {
    setCart((c) => {
      const n = (c[id] ?? 0) - 1;
      const next = { ...c };
      if (n <= 0) delete next[id];
      else next[id] = n;
      return next;
    });
  };

  const submitOrder = async () => {
    setSubmitError(null);
    if (!cfg?.enabled) return;
    const lines = cartLines.map((l) => ({ itemId: l.itemId, quantity: l.quantity }));
    if (lines.length === 0) {
      setSubmitError("Your cart is empty.");
      return;
    }
    if (!customerName.trim()) {
      setSubmitError("Please enter your name.");
      return;
    }
    setSubmitting(true);
    try {
      const res = await fetch("/api/online-ordering/order", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          lines,
          customerName: customerName.trim(),
          customerPhone: customerPhone.trim(),
          customerEmail: customerEmail.trim(),
          paymentChoice,
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        setSubmitError(data.error || data.detail || "Order failed.");
        return;
      }
      if (paymentChoice === "PAY_ONLINE_STRIPE" && data.checkoutUrl) {
        window.location.href = data.checkoutUrl as string;
        return;
      }
      setCheckoutOpen(false);
      setCart({});
      alert(
        `Order #${data.orderNumber} placed. ${data.message || "See you at the store for payment on the terminal."}`
      );
    } finally {
      setSubmitting(false);
    }
  };

  if (loadError && !cfg) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center p-6">
        <p className="text-slate-600">{loadError}</p>
      </div>
    );
  }

  if (!cfg) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center p-6">
        <p className="text-slate-500">Loading…</p>
      </div>
    );
  }

  if (!cfg.enabled) {
    return (
      <div className="min-h-screen bg-slate-50 flex flex-col items-center justify-center p-6 gap-4">
        <h1 className="text-2xl font-semibold text-slate-800">{cfg.businessName}</h1>
        <p className="text-slate-600 text-center max-w-md">
          Online ordering is not available right now. Please call the restaurant or visit in person.
        </p>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 pb-28">
      <header className="sticky top-0 z-10 bg-white/90 backdrop-blur border-b border-slate-200 px-4 py-3 flex items-center justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold text-slate-900">{cfg.businessName}</h1>
          <p className="text-xs text-slate-500">Pickup order — menu updates with your dashboard</p>
        </div>
        <span className="text-xs text-slate-400 shrink-0">Pickup</span>
      </header>

      <div className="max-w-3xl mx-auto p-4 space-y-4">
        {menuLoading && <p className="text-sm text-slate-500">Loading menu…</p>}
        {loadError && <p className="text-sm text-red-600">{loadError}</p>}

        {menu && menu.categories.length > 0 && (
          <div className="flex gap-2 overflow-x-auto pb-1">
            <button
              type="button"
              onClick={() => setActiveCategoryId("ALL")}
              className={`shrink-0 px-3 py-1.5 rounded-full text-sm border ${
                activeCategoryId === "ALL"
                  ? "bg-blue-600 text-white border-blue-600"
                  : "bg-white border-slate-200"
              }`}
            >
              All
            </button>
            {menu.categories.map((c) => (
              <button
                key={c.id}
                type="button"
                onClick={() => setActiveCategoryId(c.id)}
                className={`shrink-0 px-3 py-1.5 rounded-full text-sm border ${
                  activeCategoryId === c.id
                    ? "bg-blue-600 text-white border-blue-600"
                    : "bg-white border-slate-200"
                }`}
              >
                {c.name}
              </button>
            ))}
          </div>
        )}

        <div className="space-y-2">
          {visibleItems.map((it) => (
            <div
              key={it.id}
              className="bg-white rounded-xl border border-slate-200 p-4 flex justify-between gap-3"
            >
              <div className="min-w-0">
                <p className="font-medium text-slate-900">{it.name}</p>
                {it.description ? (
                  <p className="text-sm text-slate-500 line-clamp-2">{it.description}</p>
                ) : null}
                <p className="text-sm font-semibold text-slate-800 mt-1">
                  {formatMoney(it.unitPriceCents)}
                </p>
              </div>
              <div className="flex flex-col items-end gap-1 shrink-0">
                <button
                  type="button"
                  onClick={() => addToCart(it.id)}
                  className="px-3 py-1.5 rounded-lg bg-blue-600 text-white text-sm font-medium"
                >
                  Add
                </button>
                {(cart[it.id] ?? 0) > 0 && (
                  <div className="flex items-center gap-2 text-sm">
                    <button
                      type="button"
                      className="w-7 h-7 rounded border border-slate-300"
                      onClick={() => decFromCart(it.id)}
                    >
                      −
                    </button>
                    <span>{cart[it.id]}</span>
                    <button
                      type="button"
                      className="w-7 h-7 rounded border border-slate-300"
                      onClick={() => addToCart(it.id)}
                    >
                      +
                    </button>
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="fixed bottom-0 inset-x-0 bg-white border-t border-slate-200 p-4 flex items-center justify-between gap-3 shadow-[0_-4px_20px_rgba(0,0,0,0.06)]">
        <div>
          <p className="text-xs text-slate-500">Subtotal</p>
          <p className="text-lg font-semibold">{formatMoney(subtotalCents)}</p>
        </div>
        <button
          type="button"
          disabled={cartLines.length === 0}
          onClick={() => {
            setSubmitError(null);
            setCheckoutOpen(true);
          }}
          className="px-5 py-2.5 rounded-xl bg-blue-600 text-white font-medium disabled:opacity-40"
        >
          Checkout
        </button>
      </div>

      {checkoutOpen && (
        <div className="fixed inset-0 z-20 bg-black/40 flex items-end sm:items-center justify-center p-4">
          <div className="bg-white rounded-2xl max-w-md w-full p-5 space-y-4 shadow-xl">
            <h2 className="text-lg font-semibold">Checkout</h2>
            <label className="block text-sm">
              <span className="text-slate-600">Name</span>
              <input
                className="mt-1 w-full border border-slate-200 rounded-lg px-3 py-2"
                value={customerName}
                onChange={(e) => setCustomerName(e.target.value)}
                autoComplete="name"
              />
            </label>
            <label className="block text-sm">
              <span className="text-slate-600">Phone</span>
              <input
                className="mt-1 w-full border border-slate-200 rounded-lg px-3 py-2"
                value={customerPhone}
                onChange={(e) => setCustomerPhone(e.target.value)}
                autoComplete="tel"
              />
            </label>
            <label className="block text-sm">
              <span className="text-slate-600">Email (optional)</span>
              <input
                className="mt-1 w-full border border-slate-200 rounded-lg px-3 py-2"
                value={customerEmail}
                onChange={(e) => setCustomerEmail(e.target.value)}
                autoComplete="email"
              />
            </label>

            <div className="space-y-2">
              <p className="text-sm font-medium text-slate-700">Payment</p>
              {cfg.allowPayInStore && (
                <label className="flex gap-2 items-start text-sm cursor-pointer">
                  <input
                    type="radio"
                    name="pay"
                    checked={paymentChoice === "PAY_AT_STORE"}
                    onChange={() => setPaymentChoice("PAY_AT_STORE")}
                  />
                  <span>
                    <span className="font-medium text-slate-900">Pay at the store</span>
                    <span className="block text-slate-500">
                      Staff will take payment on the Dejavoo terminal (SPIn) when you pick up.
                    </span>
                  </span>
                </label>
              )}
              {cfg.allowPayOnlineStripe && (
                <label className="flex gap-2 items-start text-sm cursor-pointer">
                  <input
                    type="radio"
                    name="pay"
                    checked={paymentChoice === "PAY_ONLINE_STRIPE"}
                    onChange={() => setPaymentChoice("PAY_ONLINE_STRIPE")}
                  />
                  <span>
                    <span className="font-medium text-slate-900">Pay now (card)</span>
                    <span className="block text-slate-500">
                      Secure checkout by Stripe. Your order is paid before pickup.
                    </span>
                  </span>
                </label>
              )}
            </div>

            {submitError && <p className="text-sm text-red-600">{submitError}</p>}

            <div className="flex gap-2 justify-end pt-2">
              <button
                type="button"
                className="px-4 py-2 rounded-lg border border-slate-200 text-slate-700"
                onClick={() => setCheckoutOpen(false)}
                disabled={submitting}
              >
                Cancel
              </button>
              <button
                type="button"
                className="px-4 py-2 rounded-lg bg-blue-600 text-white font-medium disabled:opacity-50"
                onClick={() => void submitOrder()}
                disabled={submitting}
              >
                {submitting ? "Please wait…" : "Place order"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
