"use client";

import { getApp } from "firebase/app";
import { collection, getDocs, query, where, doc, getDoc } from "firebase/firestore";
import { getFunctions, httpsCallable } from "firebase/functions";
import { useEffect, useRef, useState } from "react";
import { ChevronDown, Store } from "lucide-react";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import { useMerchantId } from "@/hooks/useMerchantId";

const nameSpanClass =
  "text-sm font-semibold text-slate-800 truncate max-w-[7rem] sm:max-w-[12rem] md:max-w-[14rem] shrink text-right";

export default function MerchantAccountSwitcher({
  activeBusinessName,
}: {
  activeBusinessName: string;
}) {
  const { user, claims, refreshClaims } = useAuth();
  const activeMerchantId = useMerchantId();
  const [stores, setStores] = useState<{ id: string; name: string }[]>([]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!user) {
      setStores([]);
      return;
    }

    if (claims.role === "merchant_owner") {
      let cancelled = false;
      (async () => {
        try {
          const q = query(collection(db, "Merchants"), where("ownerAuthUid", "==", user.uid));
          const snap = await getDocs(q);
          if (cancelled) return;
          setStores(
            snap.docs.map((d) => ({
              id: d.id,
              name:
                String(d.data().businessName || d.data().merchantNumber || d.id).trim() || d.id,
            }))
          );
        } catch {
          if (!cancelled) setStores([]);
        }
      })();
      return () => {
        cancelled = true;
      };
    }

    if (claims.role === "merchant_staff") {
      const ids =
        Array.isArray(claims.merchantIds) && claims.merchantIds.length > 0
          ? claims.merchantIds.map((x) => String(x)).filter(Boolean)
          : [];
      if (ids.length === 0) {
        setStores([]);
        return;
      }
      let cancelled = false;
      (async () => {
        try {
          const rows = await Promise.all(ids.map((id) => getDoc(doc(db, "Merchants", id))));
          if (cancelled) return;
          setStores(
            rows
              .filter((s) => s.exists())
              .map((s) => ({
                id: s.id,
                name:
                  String(s.data().businessName || s.data().merchantNumber || s.id).trim() || s.id,
              }))
          );
        } catch {
          if (!cancelled) setStores([]);
        }
      })();
      return () => {
        cancelled = true;
      };
    }

    setStores([]);
  }, [user, claims.role, claims.merchantIds, user?.uid]);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [open]);

  const label =
    (activeBusinessName && activeBusinessName.trim()) ||
    stores.find((s) => s.id === activeMerchantId)?.name ||
    "";

  if (stores.length <= 1) {
    if (!label) return null;
    return (
      <span className={nameSpanClass} title={label}>
        {label}
      </span>
    );
  }

  const pick = async (id: string) => {
    if (id === activeMerchantId) {
      setOpen(false);
      return;
    }
    setErr(null);
    setBusy(true);
    try {
      const functions = getFunctions(getApp());
      const call = httpsCallable(functions, "selectActiveMerchant");
      const res = await call({ merchantId: id });
      const data = res.data as { ok?: boolean; error?: string };
      if (data && data.ok === false && data.error) {
        setErr(data.error);
        return;
      }
      await refreshClaims();
      setOpen(false);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      setErr(msg.length > 120 ? `${msg.slice(0, 120)}…` : msg);
    } finally {
      setBusy(false);
    }
  };

  const display = label || "Select business";

  return (
    <div className="relative shrink-0 min-w-0" ref={rootRef}>
      <button
        type="button"
        disabled={busy}
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1.5 max-w-[10rem] sm:max-w-[14rem] rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-sm font-semibold text-slate-800 hover:bg-slate-100 disabled:opacity-60"
        title="Switch business"
        aria-expanded={open}
        aria-haspopup="listbox"
      >
        <Store size={16} className="shrink-0 text-slate-500" aria-hidden />
        <span className="truncate">{display}</span>
        <ChevronDown size={16} className="shrink-0 text-slate-500" aria-hidden />
      </button>
      {err ? (
        <p className="absolute right-0 top-full z-40 mt-1 max-w-xs text-xs text-red-600">{err}</p>
      ) : null}
      {open ? (
        <ul
          className="absolute right-0 top-full z-30 mt-1 max-h-64 min-w-[12rem] overflow-auto rounded-lg border border-slate-200 bg-white py-1 shadow-lg"
          role="listbox"
          aria-label="Businesses"
        >
          {stores.map((s) => (
            <li key={s.id}>
              <button
                type="button"
                role="option"
                aria-selected={s.id === activeMerchantId}
                className={
                  "w-full px-3 py-2 text-left text-sm hover:bg-slate-50 " +
                  (s.id === activeMerchantId ? "bg-blue-50 font-medium text-blue-900" : "text-slate-700")
                }
                onClick={() => void pick(s.id)}
              >
                {s.name}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
