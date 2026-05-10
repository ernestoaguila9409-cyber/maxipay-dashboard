"use client";

import { useEffect, useState } from "react";
import { collection, getDocs, limit, query } from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";

/**
 * Merchant Firestore document id for scoping dashboard data.
 * From JWT `merchantId`, or for `super_admin` when Firestore has exactly one `Merchants`
 * document, that document id (single-tenant bootstrap). Empty when logged out, when claims
 * have no merchant and bootstrap does not apply, or super_admin with 0 or 2+ merchants.
 */
export function useMerchantId(): string {
  const { user, claims } = useAuth();
  const fromClaims = (claims.merchantId as string | undefined) ?? "";
  const [resolved, setResolved] = useState("");

  useEffect(() => {
    if (!user) {
      setResolved("");
      return;
    }
    if (fromClaims) {
      setResolved(fromClaims);
      return;
    }
    setResolved("");
  }, [user, fromClaims]);

  useEffect(() => {
    if (!user || fromClaims) return;
    if (claims.role !== "super_admin") return;
    let cancelled = false;
    (async () => {
      try {
        const mq = query(collection(db, "Merchants"), limit(2));
        const mset = await getDocs(mq);
        if (cancelled) return;
        if (mset.size === 1) {
          setResolved(mset.docs[0].id);
        }
      } catch {
        /* ignore */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [user, fromClaims, claims.role]);

  return resolved;
}
