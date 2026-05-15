"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { onSnapshot } from "firebase/firestore";
import QRCode from "qrcode";
import { merchantDoc } from "@/lib/merchantFirestore";
import { useAuth } from "@/context/AuthContext";
import { useMerchantId } from "@/hooks/useMerchantId";
import {
  BUSINESS_INFO_DOC,
  ONLINE_ORDERING_SETTINGS_DOC,
  SETTINGS_COLLECTION,
  parseOnlineOrderingSettings,
  slugify,
} from "@/lib/onlineOrderingShared";
import { Copy, Download, Loader2, QrCode, Check, X } from "lucide-react";

function usePublicMenuOrderUrl(): {
  orderUrl: string;
  effectiveSlug: string;
  ready: boolean;
} {
  const { user } = useAuth();
  const merchantId = useMerchantId();
  const [origin, setOrigin] = useState("");
  const [slugFromSettings, setSlugFromSettings] = useState("");
  const [businessName, setBusinessName] = useState("");
  const [snapshotsReady, setSnapshotsReady] = useState(false);

  useEffect(() => {
    setOrigin(typeof window !== "undefined" ? window.location.origin : "");
  }, []);

  useEffect(() => {
    if (!user || !merchantId) {
      setSnapshotsReady(false);
      return;
    }
    setSnapshotsReady(false);
    let cancelled = false;
    let ooDone = false;
    let bizDone = false;
    const mark = () => {
      if (!cancelled && ooDone && bizDone) setSnapshotsReady(true);
    };
    const u1 = onSnapshot(
      merchantDoc(merchantId, SETTINGS_COLLECTION, ONLINE_ORDERING_SETTINGS_DOC),
      (snap) => {
        const p = parseOnlineOrderingSettings(snap.data() as Record<string, unknown> | undefined);
        setSlugFromSettings(p.onlineOrderingSlug);
        if (!ooDone) {
          ooDone = true;
          mark();
        }
      },
      () => {
        if (!ooDone) {
          ooDone = true;
          mark();
        }
      }
    );
    const u2 = onSnapshot(
      merchantDoc(merchantId, SETTINGS_COLLECTION, BUSINESS_INFO_DOC),
      (snap) => {
        const n = snap.get("businessName");
        setBusinessName(typeof n === "string" ? n : "");
        if (!bizDone) {
          bizDone = true;
          mark();
        }
      },
      () => {
        if (!bizDone) {
          bizDone = true;
          mark();
        }
      }
    );
    return () => {
      cancelled = true;
      u1();
      u2();
    };
  }, [user, merchantId]);

  const effectiveSlug = useMemo(
    () => slugFromSettings || slugify(businessName),
    [slugFromSettings, businessName]
  );

  const orderUrl = useMemo(() => {
    if (!origin) return "";
    return `${origin}/order${effectiveSlug ? `/${effectiveSlug}` : ""}`;
  }, [origin, effectiveSlug]);

  const ready = Boolean(user && merchantId && origin && snapshotsReady);

  return { orderUrl, effectiveSlug, ready };
}

function MenuQrBody({
  orderUrl,
  effectiveSlug,
  onClose,
  compact,
}: {
  orderUrl: string;
  effectiveSlug: string;
  onClose?: () => void;
  compact?: boolean;
}) {
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [qrError, setQrError] = useState<string | null>(null);
  const [generating, setGenerating] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!orderUrl) {
      setDataUrl(null);
      setQrError(null);
      return;
    }
    let cancelled = false;
    setGenerating(true);
    setQrError(null);
    QRCode.toDataURL(orderUrl, {
      width: compact ? 220 : 320,
      margin: 2,
      errorCorrectionLevel: "M",
      color: { dark: "#0f172a", light: "#ffffff" },
    })
      .then((url) => {
        if (!cancelled) {
          setDataUrl(url);
          setGenerating(false);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setDataUrl(null);
          setQrError("Could not generate QR code.");
          setGenerating(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [orderUrl, compact]);

  const downloadPng = useCallback(() => {
    if (!dataUrl) return;
    const a = document.createElement("a");
    a.href = dataUrl;
    a.download = `maxipay-menu-qr-${effectiveSlug || "store"}.png`;
    a.click();
  }, [dataUrl, effectiveSlug]);

  const copyLink = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(orderUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard may be blocked */
    }
  }, [orderUrl]);

  return (
    <div className={compact ? "space-y-3" : "space-y-4"}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <p className="text-xs font-medium text-slate-500 uppercase tracking-wide">Public menu link</p>
          <p className="text-sm font-mono text-slate-800 break-all mt-0.5">{orderUrl}</p>
          <p className="text-xs text-slate-500 mt-1.5">
            Scanning opens your storefront so guests can browse the menu (and order, if online ordering is on).
          </p>
        </div>
        {onClose ? (
          <button
            type="button"
            onClick={onClose}
            className="shrink-0 p-2 rounded-lg text-slate-500 hover:bg-slate-100"
            aria-label="Close"
          >
            <X size={18} />
          </button>
        ) : null}
      </div>

      <div className="flex flex-col sm:flex-row gap-4 items-center sm:items-start">
        <div className="shrink-0 w-[200px] h-[200px] sm:w-[240px] sm:h-[240px] rounded-xl border border-slate-200 bg-white flex items-center justify-center p-3">
          {generating ? (
            <Loader2 className="animate-spin text-slate-400" size={28} />
          ) : dataUrl ? (
            <img src={dataUrl} alt="QR code linking to your public menu" className="w-full h-full object-contain" />
          ) : (
            <span className="text-xs text-slate-500 text-center px-2">{qrError ?? "No preview"}</span>
          )}
        </div>
        <div className="flex flex-col gap-2 w-full sm:w-auto sm:pt-1">
          <button
            type="button"
            disabled={!dataUrl}
            onClick={downloadPng}
            className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:bg-blue-300 disabled:cursor-not-allowed"
          >
            <Download size={16} />
            Download QR (PNG)
          </button>
          <button
            type="button"
            onClick={copyLink}
            className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl border border-slate-200 bg-white text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            {copied ? <Check size={16} className="text-emerald-600" /> : <Copy size={16} />}
            {copied ? "Copied link" : "Copy menu link"}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Menu management toolbar: opens a modal with QR + download. */
export function MenuQrMenuToolbarButton() {
  const [open, setOpen] = useState(false);
  const { user } = useAuth();
  const merchantId = useMerchantId();
  const { orderUrl, effectiveSlug, ready } = usePublicMenuOrderUrl();

  const disabled = !user || !merchantId;

  return (
    <>
      <button
        type="button"
        disabled={disabled}
        onClick={() => setOpen(true)}
        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
        title="Generate a QR code for your public menu"
      >
        <QrCode size={14} />
        <span className="hidden lg:inline">Menu QR</span>
      </button>
      {open ? (
        <div
          className="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/40"
          role="dialog"
          aria-modal="true"
          aria-labelledby="menu-qr-title"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) setOpen(false);
          }}
        >
          <div
            className="bg-white rounded-2xl shadow-xl max-w-lg w-full max-h-[90vh] overflow-y-auto p-6 space-y-2"
            onMouseDown={(e) => e.stopPropagation()}
          >
            <h2 id="menu-qr-title" className="text-lg font-semibold text-slate-900 pr-8">
              Menu QR code
            </h2>
            {!ready ? (
              <div className="flex items-center gap-2 text-slate-500 py-8 justify-center">
                <Loader2 className="animate-spin" size={20} />
                Loading…
              </div>
            ) : (
              <MenuQrBody orderUrl={orderUrl} effectiveSlug={effectiveSlug} onClose={() => setOpen(false)} />
            )}
          </div>
        </div>
      ) : null}
    </>
  );
}

/** Online menu page: inline card with QR + download. */
export function MenuQrOnlineMenuCard() {
  const { user } = useAuth();
  const merchantId = useMerchantId();
  const { orderUrl, effectiveSlug, ready } = usePublicMenuOrderUrl();

  if (!user) return null;

  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-start gap-3 mb-4">
        <div className="p-2 rounded-xl bg-slate-100 text-slate-700">
          <QrCode size={22} />
        </div>
        <div>
          <h2 className="font-semibold text-slate-900">Customer menu QR</h2>
          <p className="text-sm text-slate-500 mt-1">
            Print or share a QR code that sends guests straight to your public menu page.
          </p>
        </div>
      </div>
      {!merchantId ? (
        <p className="text-sm text-slate-500">Select a merchant to generate a QR code.</p>
      ) : !ready ? (
        <div className="flex items-center gap-2 text-slate-500 py-4">
          <Loader2 className="animate-spin" size={18} />
          Loading…
        </div>
      ) : (
        <MenuQrBody orderUrl={orderUrl} effectiveSlug={effectiveSlug} compact />
      )}
    </div>
  );
}
