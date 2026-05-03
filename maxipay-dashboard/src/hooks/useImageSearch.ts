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
      payload: {
        itemName?: string;
        query?: string;
        searchKind?: "menu" | "storefront" | "businessLogo";
      }
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

  /** OpenAI-assisted query from menu item name (or storefront seed), then Pexels. */
  const searchFromItemName = useCallback(
    async (
      itemName: string,
      getToken: () => Promise<string>,
      searchKind: "menu" | "storefront" | "businessLogo" = "menu"
    ) => {
      await runSearch(getToken, { itemName, searchKind });
    },
    [runSearch]
  );

  /** Pexels with the given query string (optional searchKind for storefront landscape bias). */
  const searchWithQuery = useCallback(
    async (
      q: string,
      getToken: () => Promise<string>,
      searchKind: "menu" | "storefront" | "businessLogo" = "menu"
    ) => {
      await runSearch(getToken, { query: q, searchKind });
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
