"use client";

import { useCallback, useEffect, useState } from "react";
import { Building2, ImageIcon, Loader2, Search, X } from "lucide-react";
import { useImageSearch } from "@/hooks/useImageSearch";

export type ImageSearchModalProps =
  | {
      mode?: "menu";
      open: boolean;
      onClose: () => void;
      itemName: string;
      itemId: string;
      getIdToken: () => Promise<string>;
      onCommitted: (
        firebaseDownloadUrl: string,
        storagePath?: string
      ) => void | boolean | Promise<void | boolean>;
    }
  | {
      mode: "storefront";
      open: boolean;
      onClose: () => void;
      businessName: string;
      heroSlideId: string;
      getIdToken: () => Promise<string>;
      onCommitted: (
        firebaseDownloadUrl: string,
        storagePath?: string
      ) => void | boolean | Promise<void | boolean>;
    }
  | {
      mode: "businessLogo";
      open: boolean;
      onClose: () => void;
      businessName: string;
      getIdToken: () => Promise<string>;
      onCommitted: (
        firebaseDownloadUrl: string,
        storagePath?: string
      ) => void | boolean | Promise<void | boolean>;
    };

interface BrandfetchHit {
  brandId: string;
  name: string;
  domain: string;
  icon: string;
  logoUrl: string;
}

type LogoTab = "brand" | "stock";

export function ImageSearchModal(props: ImageSearchModalProps) {
  const isStorefront = props.mode === "storefront";
  const isBusinessLogo = props.mode === "businessLogo";
  const seedForSearch = isBusinessLogo || isStorefront ? props.businessName : props.itemName;
  const searchKind = isBusinessLogo ? "businessLogo" : isStorefront ? "storefront" : "menu";

  const { images, query, setQuery, loading, error, reset, searchFromItemName, searchWithQuery } =
    useImageSearch();

  const [selectedId, setSelectedId] = useState<number | string | null>(null);
  const [committing, setCommitting] = useState(false);
  const [commitError, setCommitError] = useState<string | null>(null);

  const [logoTab, setLogoTab] = useState<LogoTab>("brand");
  const [bfQuery, setBfQuery] = useState("");
  const [bfResults, setBfResults] = useState<BrandfetchHit[]>([]);
  const [bfLoading, setBfLoading] = useState(false);
  const [bfError, setBfError] = useState<string | null>(null);

  const searchBrandfetch = useCallback(
    async (q: string, getToken: () => Promise<string>) => {
      if (!q.trim()) return;
      setBfLoading(true);
      setBfError(null);
      try {
        const token = await getToken();
        const res = await fetch("/api/menu/brandfetch-search", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify({ query: q.trim() }),
        });
        const data = (await res.json()) as {
          error?: string;
          results?: BrandfetchHit[];
        };
        if (!res.ok) throw new Error(data.error || "Search failed");
        setBfResults(Array.isArray(data.results) ? data.results : []);
      } catch (e) {
        setBfResults([]);
        setBfError(e instanceof Error ? e.message : String(e));
      } finally {
        setBfLoading(false);
      }
    },
    [],
  );

  useEffect(() => {
    if (!props.open) {
      reset();
      setSelectedId(null);
      setCommitError(null);
      setBfQuery("");
      setBfResults([]);
      setBfError(null);
      setLogoTab("brand");
      return;
    }
    const seed = seedForSearch.trim() || (isStorefront || isBusinessLogo ? "restaurant" : "");
    if (isBusinessLogo) {
      setBfQuery(seed);
      void searchBrandfetch(seed, props.getIdToken);
    } else {
      void searchFromItemName(seed, props.getIdToken, searchKind);
    }
  }, [
    props.open,
    seedForSearch,
    isStorefront,
    isBusinessLogo,
    searchKind,
    props.getIdToken,
    reset,
    searchFromItemName,
    searchBrandfetch,
  ]);

  if (!props.open) return null;

  const handlePexelsSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const q = query.trim();
    if (!q || loading) return;
    setSelectedId(null);
    void searchWithQuery(q, props.getIdToken, searchKind);
  };

  const handleBfSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const q = bfQuery.trim();
    if (!q || bfLoading) return;
    setSelectedId(null);
    void searchBrandfetch(q, props.getIdToken);
  };

  const handleSelect = async (sourceUrl: string, id: number | string) => {
    setSelectedId(id);
    setCommitError(null);
    setCommitting(true);
    try {
      const token = await props.getIdToken();
      const body = isBusinessLogo
        ? { businessLogo: true, sourceUrl }
        : isStorefront
          ? { heroSlideId: props.heroSlideId, sourceUrl }
          : { itemId: props.itemId, sourceUrl };
      const res = await fetch("/api/menu/item-image-commit-pexels", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(body),
      });
      const data = (await res.json()) as {
        error?: string;
        imageUrl?: string;
        storagePath?: string;
      };
      if (!res.ok || !data.imageUrl) {
        throw new Error(data.error || "Could not save image");
      }
      const committed = await props.onCommitted(data.imageUrl, data.storagePath);
      if (committed !== false) {
        props.onClose();
      }
    } catch (err) {
      setCommitError(err instanceof Error ? err.message : String(err));
    } finally {
      setCommitting(false);
    }
  };

  const switchToStockTab = () => {
    setLogoTab("stock");
    setSelectedId(null);
    setCommitError(null);
    if (images.length === 0 && !loading) {
      const seed = seedForSearch.trim() || "restaurant";
      void searchFromItemName(seed, props.getIdToken, searchKind);
    }
  };

  const title = isBusinessLogo
    ? "Find logo image"
    : isStorefront
      ? "Find storefront picture"
      : "Select image";
  const cellAspect = isStorefront ? "aspect-[16/9]" : "aspect-square";
  const gridClass = isStorefront
    ? "grid grid-cols-1 sm:grid-cols-2 gap-2"
    : "grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2";

  const showBrandTab = isBusinessLogo && logoTab === "brand";
  const activeLoading = showBrandTab ? bfLoading : loading;
  const activeError = showBrandTab ? bfError : error;

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <button
        type="button"
        className="absolute inset-0 bg-black/50 backdrop-blur-sm"
        aria-label="Close"
        disabled={committing}
        onClick={() => !committing && props.onClose()}
      />
      <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-2xl max-h-[90vh] flex flex-col overflow-hidden border border-slate-200">
        <div className="flex items-center justify-between gap-3 px-5 py-4 border-b border-slate-100 shrink-0">
          <h3 className="text-lg font-semibold text-slate-900">{title}</h3>
          <button
            type="button"
            onClick={() => !committing && props.onClose()}
            className="p-2 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600"
            disabled={committing}
          >
            <X size={18} />
          </button>
        </div>

        {isBusinessLogo && (
          <div className="flex border-b border-slate-100 shrink-0">
            <button
              type="button"
              onClick={() => { setLogoTab("brand"); setSelectedId(null); setCommitError(null); }}
              className={`flex-1 flex items-center justify-center gap-2 px-4 py-2.5 text-sm font-medium transition-colors ${
                logoTab === "brand"
                  ? "text-blue-600 border-b-2 border-blue-600"
                  : "text-slate-500 hover:text-slate-700"
              }`}
              disabled={committing}
            >
              <Building2 size={15} />
              Brand Logos
            </button>
            <button
              type="button"
              onClick={switchToStockTab}
              className={`flex-1 flex items-center justify-center gap-2 px-4 py-2.5 text-sm font-medium transition-colors ${
                logoTab === "stock"
                  ? "text-blue-600 border-b-2 border-blue-600"
                  : "text-slate-500 hover:text-slate-700"
              }`}
              disabled={committing}
            >
              <ImageIcon size={15} />
              Stock Images
            </button>
          </div>
        )}

        {showBrandTab ? (
          <form onSubmit={handleBfSearchSubmit} className="px-5 py-3 border-b border-slate-100 shrink-0">
            <label className="block text-xs font-medium text-slate-500 mb-1.5">
              Search brand by name
            </label>
            <div className="flex gap-2">
              <input
                type="text"
                value={bfQuery}
                onChange={(e) => setBfQuery(e.target.value)}
                placeholder="Type a business name…"
                className="flex-1 min-w-0 px-3 py-2 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                disabled={bfLoading || committing}
              />
              <button
                type="submit"
                disabled={bfLoading || committing || !bfQuery.trim()}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 disabled:opacity-50 shrink-0"
              >
                {bfLoading ? <Loader2 size={16} className="animate-spin" /> : <Search size={16} />}
                Search
              </button>
            </div>
          </form>
        ) : (
          <form onSubmit={handlePexelsSearchSubmit} className="px-5 py-3 border-b border-slate-100 shrink-0">
            <label className="block text-xs font-medium text-slate-500 mb-1.5">Search</label>
            <div className="flex gap-2">
              <input
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={
                  isBusinessLogo
                    ? "Describe the logo or brand look…"
                    : isStorefront
                      ? "Describe the banner image…"
                      : "Describe the food photo…"
                }
                className="flex-1 min-w-0 px-3 py-2 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                disabled={loading || committing}
              />
              <button
                type="submit"
                disabled={loading || committing || !query.trim()}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 disabled:opacity-50 shrink-0"
              >
                {loading ? <Loader2 size={16} className="animate-spin" /> : <Search size={16} />}
                Search
              </button>
            </div>
          </form>
        )}

        <div className="flex-1 min-h-0 overflow-y-auto px-5 py-4">
          {activeError && (
            <p className="text-sm text-red-600 mb-3" role="alert">
              {activeError}
            </p>
          )}
          {commitError && (
            <p className="text-sm text-red-600 mb-3" role="alert">
              {commitError}
            </p>
          )}

          {activeLoading && (showBrandTab ? bfResults.length === 0 : images.length === 0) && (
            <div className="flex flex-col items-center justify-center py-16 text-slate-500 gap-2">
              <Loader2 size={28} className="animate-spin text-blue-600" />
              <span className="text-sm">
                {showBrandTab ? "Searching brands…" : "Finding images…"}
              </span>
            </div>
          )}

          {showBrandTab ? (
            <>
              {!bfLoading && bfResults.length === 0 && !bfError && (
                <p className="text-sm text-slate-500 text-center py-12">
                  No brands found. Try a different name.
                </p>
              )}
              {bfResults.length > 0 && (
                <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
                  {bfResults.map((brand) => {
                    const selected = selectedId === brand.brandId;
                    return (
                      <button
                        key={brand.brandId || brand.domain}
                        type="button"
                        disabled={committing}
                        onClick={() => void handleSelect(brand.logoUrl, brand.brandId || brand.domain)}
                        className={`relative flex flex-col items-center gap-2 p-3 rounded-xl border-2 transition-all focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${
                          selected
                            ? "border-blue-600 ring-2 ring-blue-500/30 bg-blue-50"
                            : "border-slate-100 hover:border-slate-300 bg-white"
                        } disabled:opacity-50`}
                      >
                        <div className="w-full aspect-square rounded-lg bg-slate-50 flex items-center justify-center overflow-hidden">
                          {/* eslint-disable-next-line @next/next/no-img-element */}
                          <img
                            src={brand.icon || brand.logoUrl}
                            alt={brand.name}
                            className="max-w-full max-h-full object-contain p-2"
                            onError={(e) => {
                              (e.target as HTMLImageElement).src = brand.logoUrl;
                            }}
                          />
                        </div>
                        <span className="text-xs font-medium text-slate-700 truncate w-full text-center">
                          {brand.name}
                        </span>
                        <span className="text-[10px] text-slate-400 truncate w-full text-center">
                          {brand.domain}
                        </span>
                        {committing && selected && (
                          <div className="absolute inset-0 bg-white/70 rounded-xl flex items-center justify-center">
                            <Loader2 size={24} className="animate-spin text-blue-600" />
                          </div>
                        )}
                      </button>
                    );
                  })}
                </div>
              )}
            </>
          ) : (
            <>
              {!loading && images.length === 0 && !error && (
                <p className="text-sm text-slate-500 text-center py-12">No images found.</p>
              )}
              {images.length > 0 && (
                <div className={gridClass}>
                  {images.map((img) => {
                    const selected = selectedId === img.id;
                    return (
                      <button
                        key={img.id}
                        type="button"
                        disabled={committing}
                        onClick={() => void handleSelect(img.sourceUrl, img.id)}
                        className={`relative ${cellAspect} rounded-xl overflow-hidden border-2 transition-all focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${
                          selected
                            ? "border-blue-600 ring-2 ring-blue-500/30"
                            : "border-transparent hover:border-slate-300"
                        } disabled:opacity-50`}
                      >
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img
                          src={img.previewUrl}
                          alt=""
                          className="absolute inset-0 w-full h-full object-cover"
                        />
                        {img.photographer && (
                          <span className="absolute bottom-0 left-0 right-0 bg-black/50 text-[10px] text-white px-1 py-0.5 truncate">
                            {img.photographer}
                          </span>
                        )}
                        {committing && selected && (
                          <div className="absolute inset-0 bg-black/40 flex items-center justify-center">
                            <Loader2 size={24} className="animate-spin text-white" />
                          </div>
                        )}
                      </button>
                    );
                  })}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
