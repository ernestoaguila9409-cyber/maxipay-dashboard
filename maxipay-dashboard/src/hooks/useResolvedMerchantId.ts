"use client";

import { useEffect, useState } from "react";
import { collection, getDocs, limit, query } from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import { useMerchantId } from "@/hooks/useMerchantId";

/**
 * `merchantId` from JWT, or for `super_admin` when Firestore has exactly one `Merchants`
 * document (single-tenant), that document id — matches Business Information bootstrap.
 */
export function useResolvedMerchantId(): string {
  const { user, claims } = useAuth();
  const fromClaims = useMerchantId();
  const [scoped, setScoped] = useState("");

  useEffect(() => {
    if (fromClaims) setScoped(fromClaims);
  }, [fromClaims]);

  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    (async () => {
      if (fromClaims) return;
      if (claims.role !== "super_admin") return;
      try {
        const mq = query(collection(db, "Merchants"), limit(2));
        const mset = await getDocs(mq);
        if (cancelled) return;
        if (mset.size === 1) {
          setScoped(mset.docs[0].id);
        }
      } catch {
        /* ignore */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [user, fromClaims, claims.role]);

  return scoped || fromClaims;
}
