"use client";

import { useCallback, useState } from "react";

export interface PexelsPickerImage {
  id: number;
  previewUrl: string;
  sourceUrl: string;
  photographer: string;
}

export function useImageSearch() {
  const [images, setImages] = useState<PexelsPickerImage[]>([]);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reset = useCallback(() => {
    setImages([]);
    setQuery("");
    setError(null);
    setLoading(false);
  }, []);

  const runSearch = useCallback(
    async (
      getToken: () => Promise<string>,
      payload: { itemName?: string; query?: string }
    ) => {
      setLoading(true);
      setError(null);
      try {
        const token = await getToken();
        const res = await fetch("/api/menu/item-image-search", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify(payload),
        });
        const data = (await res.json()) as {
          error?: string;
          query?: string;
          images?: PexelsPickerImage[];
        };
        if (!res.ok) {
          throw new Error(data.error || "Search failed");
        }
        setQuery(data.query ?? "");
        setImages(Array.isArray(data.images) ? data.images : []);
      } catch (e) {
        setImages([]);
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setLoading(false);
      }
    },
    []
  );

  /** OpenAI-assisted query from menu item name, then Pexels. */
  const searchFromItemName = useCallback(
    async (itemName: string, getToken: () => Promise<string>) => {
      await runSearch(getToken, { itemName });
    },
    [runSearch]
  );

  /** Pexels only with the given query string. */
  const searchWithQuery = useCallback(
    async (q: string, getToken: () => Promise<string>) => {
      await runSearch(getToken, { query: q });
    },
    [runSearch]
  );

  return {
    images,
    query,
    setQuery,
    loading,
    error,
    reset,
    searchFromItemName,
    searchWithQuery,
  };
}
