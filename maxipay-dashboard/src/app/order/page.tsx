"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

/**
 * Legacy `/order` path — redirects to `/order/{slug}` once the merchant's
 * slug is known. Keeps old links and bookmarks working.
 */
export default function OrderRedirectPage() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        const res = await fetch("/api/online-ordering/config", { cache: "no-store" });
        const data = await res.json();
        if (!res.ok) {
          setError(data.error || "Could not load store configuration.");
          return;
        }
        const slug: string | undefined = data.slug;
        if (slug) {
          router.replace(`/order/${slug}`);
        } else {
          setError("This store has not configured online ordering yet.");
        }
      } catch {
        setError("Could not reach the server. Please try again.");
      }
    })();
  }, [router]);

  if (error) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center p-6">
        <p className="text-slate-600">{error}</p>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50 flex items-center justify-center p-6">
      <p className="text-slate-500">Loading…</p>
    </div>
  );
}
