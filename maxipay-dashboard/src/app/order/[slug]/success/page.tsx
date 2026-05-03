"use client";

import { useEffect, useRef, useState, Suspense } from "react";
import { useSearchParams, useParams } from "next/navigation";

function CheckCircle() {
  return (
    <svg width={56} height={56} viewBox="0 0 56 56" fill="none">
      <circle cx={28} cy={28} r={28} fill="#16a34a" />
      <path d="M18 28l7 7 13-13" stroke="white" strokeWidth={3} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function XCircle() {
  return (
    <svg width={56} height={56} viewBox="0 0 56 56" fill="none">
      <circle cx={28} cy={28} r={28} fill="#dc2626" />
      <path d="M20 20l16 16M36 20l-16 16" stroke="white" strokeWidth={3} strokeLinecap="round" />
    </svg>
  );
}

function Spinner() {
  return (
    <svg width={56} height={56} viewBox="0 0 56 56" fill="none" className="animate-spin">
      <circle cx={28} cy={28} r={24} stroke="#e5e5e5" strokeWidth={4} />
      <path d="M28 4a24 24 0 0 1 24 24" stroke="#000" strokeWidth={4} strokeLinecap="round" />
    </svg>
  );
}

function ReviewSpinner() {
  return (
    <svg width={56} height={56} viewBox="0 0 56 56" fill="none" className="animate-spin">
      <circle cx={28} cy={28} r={24} stroke="#fde68a" strokeWidth={4} />
      <path d="M28 4a24 24 0 0 1 24 24" stroke="#d97706" strokeWidth={4} strokeLinecap="round" />
    </svg>
  );
}

/**
 * iPOSpays appends its own query params (e.g. `?responseCode=200&…`) to the
 * return URL.  Because the URL already has `?`, the appended `?` becomes a
 * literal character inside the *last* parameter's value.  Strip it so IDs and
 * numbers are clean.
 */
function cleanParam(raw: string | null): string | null {
  if (!raw) return null;
  const idx = raw.indexOf("?");
  return idx >= 0 ? raw.substring(0, idx) : raw;
}

const IPOS_KEYS = [
  "responseCode", "responseMessage", "transactionType", "amount",
  "cardType", "cardLast4Digit", "batchNumber", "transactionNumber",
  "transactionId", "responseApprovalCode", "totalAmount", "tips",
  "cardToken", "rrn", "transactionReferenceId",
];

/**
 * Extract iPOSpays redirect params.
 */
function extractIposRedirectParams(sp: URLSearchParams): Record<string, string> | null {
  if (sp.get("responseCode")) {
    const result: Record<string, string> = {};
    for (const k of IPOS_KEYS) {
      const v = sp.get(k);
      if (v != null) result[k] = v;
    }
    return result;
  }

  const rawOrderId = sp.get("orderId") || "";
  const qIdx = rawOrderId.indexOf("?");
  if (qIdx >= 0) {
    const embedded = new URLSearchParams(rawOrderId.substring(qIdx + 1));
    if (embedded.get("responseCode")) {
      const result: Record<string, string> = {};
      for (const k of IPOS_KEYS) {
        const v = embedded.get(k);
        if (v != null) result[k] = v;
      }
      return result;
    }
  }

  return null;
}

type StaffOutcome = "checking" | "accepted" | "declined";

type KdsPhase = "none" | "preparing" | "ready";

function staffOutcomeFromApi(data: {
  voided: boolean;
  status: string;
  awaitingStaffConfirmOrder: boolean;
}): StaffOutcome {
  if (data.voided || data.status === "VOIDED") return "declined";
  if (!data.awaitingStaffConfirmOrder) return "accepted";
  return "checking";
}

function SuccessInner() {
  const { slug } = useParams<{ slug: string }>();
  const sp = useSearchParams();
  const orderNumber = cleanParam(sp.get("orderNumber"));
  const orderId = cleanParam(sp.get("orderId"));
  const paymentParam = (sp.get("payment") || "").trim().toUpperCase();
  const isPayAtStore = paymentParam === "PAY_AT_STORE";
  const sessionId = sp.get("session_id");
  const iposRedirectParams = extractIposRedirectParams(sp);
  const [businessName, setBusinessName] = useState<string>("");
  const [logoUrl, setLogoUrl] = useState<string>("");
  const [paymentStatus, setPaymentStatus] = useState<"confirming" | "confirmed" | "pending" | "error">(() =>
    orderId && !isPayAtStore ? "confirming" : "confirmed",
  );
  const confirmedRef = useRef(false);

  const [staffOutcome, setStaffOutcome] = useState<StaffOutcome | null>(
    isPayAtStore && orderId && orderNumber ? "checking" : null,
  );
  const [kdsPhase, setKdsPhase] = useState<KdsPhase>("none");

  useEffect(() => {
    void (async () => {
      try {
        const res = await fetch("/api/online-ordering/config", { cache: "no-store" });
        const data = await res.json();
        if (data.businessName) setBusinessName(data.businessName as string);
        const rawLogo = typeof data.logoUrl === "string" ? data.logoUrl.trim() : "";
        if (rawLogo.startsWith("http://") || rawLogo.startsWith("https://")) {
          setLogoUrl(rawLogo);
        }
      } catch {
        /* ignore */
      }
    })();
  }, []);

  useEffect(() => {
    if (!orderId || !orderNumber) return;

    let cancelled = false;
    const poll = async () => {
      try {
        const qs = new URLSearchParams({ orderId, orderNumber: String(orderNumber) });
        const res = await fetch(`/api/online-ordering/order-status?${qs.toString()}`, { cache: "no-store" });
        const data = await res.json();
        if (cancelled || !data.ok) return;
        const phase = (data.kdsPhase as KdsPhase) || "none";
        setKdsPhase(phase);
        if (isPayAtStore) {
          const next = staffOutcomeFromApi({
            voided: !!data.voided,
            status: String(data.status ?? ""),
            awaitingStaffConfirmOrder: !!data.awaitingStaffConfirmOrder,
          });
          setStaffOutcome(next);
        }
      } catch {
        /* keep last state */
      }
    };

    void poll();
    const t = window.setInterval(() => void poll(), 2500);
    return () => {
      cancelled = true;
      window.clearInterval(t);
    };
  }, [orderId, orderNumber, isPayAtStore]);

  useEffect(() => {
    if (!orderId || confirmedRef.current || isPayAtStore) return;
    confirmedRef.current = true;

    let attempts = 0;
    /** ~36s: query API + webhook can lag after iPOS redirect to this page */
    const maxAttempts = 12;

    const tryConfirm = async () => {
      try {
        const res = await fetch("/api/online-ordering/confirm-hpp-payment", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            orderId,
            ...(iposRedirectParams ? { iposRedirect: iposRedirectParams } : {}),
          }),
        });
        const data = await res.json();
        if (data.status === "PAID") {
          setPaymentStatus("confirmed");
          return;
        }
        attempts++;
        if (attempts < maxAttempts) {
          setTimeout(() => void tryConfirm(), 3000);
        } else {
          setPaymentStatus("pending");
        }
      } catch {
        attempts++;
        if (attempts < maxAttempts) {
          setTimeout(() => void tryConfirm(), 3000);
        } else {
          setPaymentStatus("error");
        }
      }
    };

    void tryConfirm();
  }, [orderId, iposRedirectParams, isPayAtStore]);

  const legacyNumberOnly = !orderId && !!orderNumber;

  const kitchenTrackingEligible =
    (isPayAtStore && staffOutcome === "accepted") ||
    (!isPayAtStore && paymentStatus === "confirmed");

  const heroIcon = (() => {
    if (isPayAtStore && staffOutcome) {
      if (staffOutcome === "checking") return <ReviewSpinner />;
      if (staffOutcome === "declined") return <XCircle />;
      if (staffOutcome === "accepted" && kdsPhase === "preparing") return <ReviewSpinner />;
      if (staffOutcome === "accepted") return <CheckCircle />;
    }
    if (paymentStatus === "confirming") return <Spinner />;
    if (!isPayAtStore && paymentStatus === "confirmed" && kdsPhase === "preparing") {
      return <ReviewSpinner />;
    }
    return <CheckCircle />;
  })();

  const title = (() => {
    if (isPayAtStore && staffOutcome) {
      if (staffOutcome === "checking") return "Checking order";
      if (staffOutcome === "declined") return "Order not accepted";
      if (kitchenTrackingEligible && kdsPhase === "preparing") return "Order is being prepared";
      if (kitchenTrackingEligible && kdsPhase === "ready") return "Your order is ready for pickup";
      if (staffOutcome === "accepted") return "Order accepted";
    }
    if (legacyNumberOnly) return "Order received";
    if (paymentStatus === "confirming") return "Confirming payment…";
    if (kitchenTrackingEligible && kdsPhase === "preparing") return "Order is being prepared";
    if (kitchenTrackingEligible && kdsPhase === "ready") return "Your order is ready for pickup";
    if (paymentStatus === "confirmed") return "Order approved";
    if (paymentStatus === "pending") return "Order received";
    if (paymentStatus === "error") return "We couldn't verify payment";
    return "Order approved";
  })();

  const description = (() => {
    if (isPayAtStore && staffOutcome) {
      if (staffOutcome === "checking") {
        return (
          <p className="text-sm text-amber-800 max-w-xs mx-auto leading-relaxed">
            Your order is in review. The store will confirm or decline it shortly &mdash; this page updates automatically.
          </p>
        );
      }
      if (staffOutcome === "declined") {
        return (
          <p className="text-sm text-red-600 max-w-xs mx-auto leading-relaxed">
            The store declined this order. If you have questions, please contact them directly.
          </p>
        );
      }
      if (kitchenTrackingEligible && kdsPhase === "preparing") {
        return (
          <p className="text-sm text-neutral-500 max-w-xs mx-auto leading-relaxed">
            The kitchen started your order. This page updates automatically when it&apos;s ready for pickup.
          </p>
        );
      }
      if (kitchenTrackingEligible && kdsPhase === "ready") {
        return (
          <p className="text-sm text-neutral-500 max-w-xs mx-auto leading-relaxed">
            Your order is ready. Please show your order number when you pick it up.
          </p>
        );
      }
      if (staffOutcome === "accepted") {
        return (
          <p className="text-sm text-neutral-500 max-w-xs mx-auto leading-relaxed">
            The store accepted your order. Show your order number when you pick up. We&apos;ll have it ready for you.
          </p>
        );
      }
    }
    if (legacyNumberOnly) {
      return (
        <p className="text-sm text-neutral-500 max-w-xs mx-auto leading-relaxed">
          Thank you. Show your order number when you pick up. If you pay at the store, staff will confirm your order there.
        </p>
      );
    }
    if (paymentStatus === "confirming") {
      return (
        <p className="text-sm text-neutral-500 max-w-xs mx-auto leading-relaxed">
          Please wait while we verify your payment&hellip;
        </p>
      );
    }
    if (paymentStatus === "confirmed") {
      if (kitchenTrackingEligible && kdsPhase === "preparing") {
        return (
          <p className="text-sm text-neutral-500 max-w-xs mx-auto leading-relaxed">
            The kitchen started your order. This page updates automatically when it&apos;s ready for pickup.
          </p>
        );
      }
      if (kitchenTrackingEligible && kdsPhase === "ready") {
        return (
          <p className="text-sm text-neutral-500 max-w-xs mx-auto leading-relaxed">
            Your order is ready. Please show your order number when you pick it up.
          </p>
        );
      }
      return (
        <p className="text-sm text-neutral-500 max-w-xs mx-auto leading-relaxed">
          Payment successful. Your order is approved &mdash; show your order number when you pick up. We&apos;ll have it
          ready for you.
        </p>
      );
    }
    if (paymentStatus === "pending") {
      return (
        <p className="text-sm text-amber-600 max-w-xs mx-auto leading-relaxed">
          Your order is placed. Payment confirmation is still processing &mdash; it should update shortly.
        </p>
      );
    }
    if (paymentStatus === "error") {
      return (
        <p className="text-sm text-red-600 max-w-xs mx-auto leading-relaxed">
          We could not verify your payment right now. Please contact the store if you have any questions.
        </p>
      );
    }
    return null;
  })();

  return (
    <div className="min-h-screen bg-[#fafafa] flex flex-col items-center justify-center p-6 text-center">
      <div className="bg-white rounded-3xl shadow-sm border border-neutral-100 max-w-md w-full px-8 py-12 space-y-5">
        <div className="flex justify-center">{heroIcon}</div>

        {logoUrl && (
          <div className="flex justify-center pt-1">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={logoUrl}
              alt={businessName ? `${businessName} logo` : "Business logo"}
              className="max-h-16 max-w-[200px] w-auto object-contain"
              referrerPolicy="no-referrer"
            />
          </div>
        )}

        {businessName && (
          <p className="text-xs font-semibold text-neutral-400 uppercase tracking-wider">
            {businessName}
          </p>
        )}

        <h1 className="text-2xl font-bold text-neutral-900">{title}</h1>

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

        {description}

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
