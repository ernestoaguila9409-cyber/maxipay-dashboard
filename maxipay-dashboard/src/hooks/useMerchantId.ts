"use client";

import { useEffect, useState } from "react";
import { collection, getDocs, limit, query, where } from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";

function merchantIdsFromClaims(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((x) => String(x)).filter(Boolean);
}

/**
 * Merchant Firestore document id for scoping dashboard data.
 * From JWT `merchantId`, or for `super_admin` when Firestore has exactly one `Merchants`
 * document, that document id (single-tenant bootstrap). Empty when logged out, when claims
 * have no merchant and bootstrap does not apply, or super_admin with 0 or 2+ merchants.
 */
export function useMerchantId(): string {
  const { user, claims } = useAuth();
  const fromClaims = (claims.merchantId as string | undefined)?.trim() ?? "";
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
    if (claims.role !== "super_admin" && claims.role !== "merchant_owner") return;
    let cancelled = false;
    (async () => {
      try {
        if (claims.role === "super_admin") {
          const mq = query(collection(db, "Merchants"), limit(2));
          const mset = await getDocs(mq);
          if (cancelled) return;
          if (mset.size === 1) {
            setResolved(mset.docs[0].id);
          }
          return;
        }

        const ownedQuery = query(
          collection(db, "Merchants"),
          where("ownerAuthUid", "==", user.uid),
          limit(2)
        );
        const owned = await getDocs(ownedQuery);
        if (cancelled) return;
        if (owned.size === 1) {
          setResolved(owned.docs[0].id);
          return;
        }

        const ids = merchantIdsFromClaims(claims.merchantIds);
        if (ids.length > 0) {
          setResolved(ids[0]);
        }
      } catch {
        /* ignore */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [user, fromClaims, claims.role, claims.merchantIds, user?.uid]);

  return fromClaims || resolved;
}
