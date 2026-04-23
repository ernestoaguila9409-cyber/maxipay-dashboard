"use client";

import { useEffect, useState, Suspense } from "react";
import { useSearchParams } from "next/navigation";

function SuccessInner() {
  const sp = useSearchParams();
  const orderNumber = sp.get("orderNumber");
  const sessionId = sp.get("session_id");
  const [businessName, setBusinessName] = useState<string>("");

  useEffect(() => {
    void (async () => {
      try {
        const res = await fetch("/api/online-ordering/config", { cache: "no-store" });
        const data = await res.json();
        if (data.businessName) setBusinessName(data.businessName as string);
      } catch {
        /* ignore */
      }
    })();
  }, []);

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col items-center justify-center p-6 text-center gap-4">
      {businessName ? (
        <h1 className="text-2xl font-semibold text-slate-900">{businessName}</h1>
      ) : null}
      <p className="text-lg text-slate-800 font-medium">Thank you for your order.</p>
      {orderNumber ? (
        <p className="text-slate-600">
          Order number: <span className="font-mono font-semibold">#{orderNumber}</span>
        </p>
      ) : null}
      {sessionId ? (
        <p className="text-xs text-slate-400 break-all max-w-md">Reference: {sessionId}</p>
      ) : null}
      <p className="text-sm text-slate-600 max-w-md">
        You will receive a confirmation from your bank. Show your order number at pickup.
      </p>
      <a href="/order" className="text-blue-600 hover:underline text-sm">
        Place another order
      </a>
    </div>
  );
}

export default function OrderSuccessPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen bg-slate-50 flex items-center justify-center">
          <p className="text-slate-500">Loading…</p>
        </div>
      }
    >
      <SuccessInner />
    </Suspense>
  );
}
