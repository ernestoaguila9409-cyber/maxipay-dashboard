"use client";

import { useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";

function SuccessRedirectInner() {
  const router = useRouter();
  const sp = useSearchParams();
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
          const qs = sp.toString();
          router.replace(`/order/${slug}/success${qs ? `?${qs}` : ""}`);
        } else {
          setError("This store has not configured online ordering yet.");
        }
      } catch {
        setError("Could not reach the server. Please try again.");
      }
    })();
  }, [router, sp]);

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

export default function SuccessRedirectPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen bg-slate-50 flex items-center justify-center">
          <p className="text-slate-500">Loading…</p>
        </div>
      }
    >
      <SuccessRedirectInner />
    </Suspense>
  );
}
