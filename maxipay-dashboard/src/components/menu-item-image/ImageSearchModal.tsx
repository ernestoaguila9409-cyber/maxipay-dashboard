"use client";

import { useEffect, useState } from "react";
import { Loader2, Search, X } from "lucide-react";
import { useImageSearch } from "@/hooks/useImageSearch";

export interface ImageSearchModalProps {
  open: boolean;
  onClose: () => void;
  itemName: string;
  itemId: string;
  getIdToken: () => Promise<string>;
  /** Called after image is in Firebase Storage with a Firebase download URL. */
  onCommitted: (firebaseDownloadUrl: string) => void | Promise<void>;
}

export function ImageSearchModal({
  open,
  onClose,
  itemName,
  itemId,
  getIdToken,
  onCommitted,
}: ImageSearchModalProps) {
  const { images, query, setQuery, loading, error, reset, searchFromItemName, searchWithQuery } =
    useImageSearch();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [committing, setCommitting] = useState(false);
  const [commitError, setCommitError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) {
      reset();
      setSelectedId(null);
      setCommitError(null);
      return;
    }
    void searchFromItemName(itemName, getIdToken);
  }, [open, itemName, getIdToken, searchFromItemName, reset]);

  if (!open) return null;

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const q = query.trim();
    if (!q || loading) return;
    setSelectedId(null);
    void searchWithQuery(q, getIdToken);
  };

  const handleSelect = async (sourceUrl: string, id: number) => {
    setSelectedId(id);
    setCommitError(null);
    setCommitting(true);
    try {
      const token = await getIdToken();
      const res = await fetch("/api/menu/item-image-commit-pexels", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ itemId, sourceUrl }),
      });
      const data = (await res.json()) as { error?: string; imageUrl?: string };
      if (!res.ok || !data.imageUrl) {
        throw new Error(data.error || "Could not save image");
      }
      await onCommitted(data.imageUrl);
      onClose();
    } catch (err) {
      setCommitError(err instanceof Error ? err.message : String(err));
    } finally {
      setCommitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <button
        type="button"
        className="absolute inset-0 bg-black/50 backdrop-blur-sm"
        aria-label="Close"
        disabled={committing}
        onClick={() => !committing && onClose()}
      />
      <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-2xl max-h-[90vh] flex flex-col overflow-hidden border border-slate-200">
        <div className="flex items-center justify-between gap-3 px-5 py-4 border-b border-slate-100 shrink-0">
          <h3 className="text-lg font-semibold text-slate-900">Select image</h3>
          <button
            type="button"
            onClick={() => !committing && onClose()}
            className="p-2 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600"
            disabled={committing}
          >
            <X size={18} />
          </button>
        </div>

        <form onSubmit={handleSearchSubmit} className="px-5 py-3 border-b border-slate-100 shrink-0">
          <label className="block text-xs font-medium text-slate-500 mb-1.5">Search</label>
          <div className="flex gap-2">
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Describe the food photo…"
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

        <div className="flex-1 min-h-0 overflow-y-auto px-5 py-4">
          {error && (
            <p className="text-sm text-red-600 mb-3" role="alert">
              {error}
            </p>
          )}
          {commitError && (
            <p className="text-sm text-red-600 mb-3" role="alert">
              {commitError}
            </p>
          )}

          {loading && images.length === 0 && (
            <div className="flex flex-col items-center justify-center py-16 text-slate-500 gap-2">
              <Loader2 size={28} className="animate-spin text-blue-600" />
              <span className="text-sm">Finding images…</span>
            </div>
          )}

          {!loading && images.length === 0 && !error && (
            <p className="text-sm text-slate-500 text-center py-12">No images found.</p>
          )}

          {images.length > 0 && (
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2">
              {images.map((img) => {
                const selected = selectedId === img.id;
                return (
                  <button
                    key={img.id}
                    type="button"
                    disabled={committing}
                    onClick={() => void handleSelect(img.sourceUrl, img.id)}
                    className={`relative aspect-square rounded-xl overflow-hidden border-2 transition-all focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${
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
        </div>
      </div>
    </div>
  );
}
