"use client";

import { useAuth } from "@/context/AuthContext";

/**
 * Returns the current merchant's Firestore ID from auth claims.
 * Every Firestore query in the dashboard should scope by this value.
 * Returns empty string when claims haven't loaded yet — callers
 * should skip queries until the value is truthy.
 */
export function useMerchantId(): string {
  const { claims } = useAuth();
  return claims.merchantId ?? "";
}
