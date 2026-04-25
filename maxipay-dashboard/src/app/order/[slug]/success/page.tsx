"use client";

import { useEffect, useState, Suspense } from "react";
import { useSearchParams, useParams } from "next/navigation";

function CheckCircle() {
  return (
    <svg width={56} height={56} viewBox="0 0 56 56" fill="none">
      <circle cx={28} cy={28} r={28} fill="#16a34a" />
      <path d="M18 28l7 7 13-13" stroke="white" strokeWidth={3} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function SuccessInner() {
  const { slug } = useParams<{ slug: string }>();
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
    <div className="min-h-screen bg-[#fafafa] flex flex-col items-center justify-center p-6 text-center">
      <div className="bg-white rounded-3xl shadow-sm border border-neutral-100 max-w-md w-full px-8 py-12 space-y-5">
        <div className="flex justify-center">
          <CheckCircle />
        </div>

        {businessName && (
          <p className="text-xs font-semibold text-neutral-400 uppercase tracking-wider">
            {businessName}
          </p>
        )}

        <h1 className="text-2xl font-bold text-neutral-900">
          Order confirmed
        </h1>

        {orderNumber && (
          <div className="bg-neutral-50 rounded-xl px-5 py-4 inline-block">
            <p className="text-xs text-neutral-500 mb-1">Order number</p>
            <p className="text-3xl font-bold text-neutral-900 tabular-nums tracking-tight">
              #{orderNumber}
            </p>
          </div>
        )}

        {sessionId && (
          <p className="text-xs text-neutral-400 break-all max-w-xs mx-auto">
            Ref: {sessionId}
          </p>
        )}

        <p className="text-sm text-neutral-500 max-w-xs mx-auto leading-relaxed">
          Show your order number when you pick up. We&apos;ll have it ready for you.
        </p>

        <a
          href={`/order/${slug}`}
          className="inline-flex items-center justify-center h-12 px-8 rounded-xl bg-black text-white font-semibold text-sm hover:bg-neutral-800 active:scale-[0.98] transition-all no-underline"
        >
          Order again
        </a>
      </div>
    </div>
  );
}

export default function OrderSuccessPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen bg-[#fafafa] flex items-center justify-center">
          <div className="w-8 h-8 border-2 border-neutral-200 border-t-black rounded-full animate-spin" />
        </div>
      }
    >
      <SuccessInner />
    </Suspense>
  );
}
