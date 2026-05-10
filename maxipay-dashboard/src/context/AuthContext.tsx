"use client";

import { createContext, useContext, useEffect, useState, useCallback, ReactNode } from "react";
import { onAuthStateChanged, User } from "firebase/auth";
import { auth } from "@/firebase/firebaseConfig";

export interface AuthClaims {
  role?: string;
  merchantId?: string;
  /** All merchant Firestore ids owned by this user (JWT; may be absent on older tokens). */
  merchantIds?: string[];
}

function parseMerchantIds(raw: unknown): string[] | undefined {
  if (!Array.isArray(raw)) return undefined;
  const out = raw.map((x) => String(x)).filter(Boolean);
  return out.length ? out : undefined;
}

function claimsFromToken(tokenResult: { claims: Record<string, unknown> }): AuthClaims {
  const role = (tokenResult.claims.role as string) ?? undefined;
  const merchantId = (tokenResult.claims.merchantId as string) ?? undefined;
  const merchantIds = parseMerchantIds(tokenResult.claims.merchantIds);
  return { role, merchantId, merchantIds };
}

interface AuthContextType {
  user: User | null;
  claims: AuthClaims;
  loading: boolean;
  /** Reload custom claims into React state (e.g. after switching merchant). */
  refreshClaims: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType>({
  user: null,
  claims: {},
  loading: true,
  refreshClaims: async () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [claims, setClaims] = useState<AuthClaims>({});
  const [loading, setLoading] = useState(true);

  const refreshClaims = useCallback(async () => {
    const u = auth.currentUser;
    if (!u) {
      setClaims({});
      return;
    }
    const tokenResult = await u.getIdTokenResult(true);
    const next = claimsFromToken(tokenResult);
    console.log(
      "[Auth] Claims refreshed →",
      `merchantId: ${next.merchantId ?? "none"}, merchantIds: ${next.merchantIds?.length ?? 0}`
    );
    setClaims(next);
  }, []);

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, async (firebaseUser) => {
      if (firebaseUser) {
        const tokenResult = await firebaseUser.getIdTokenResult();
        const next = claimsFromToken(tokenResult);
        console.log(
          "[Auth] State changed →",
          `UID: ${firebaseUser.uid}, email: ${firebaseUser.email}, role: ${next.role ?? "none"}, merchantId: ${next.merchantId ?? "none"}`
        );
        setClaims(next);
      } else {
        console.log("[Auth] State changed → signed out");
        setClaims({});
      }
      setUser(firebaseUser);
      setLoading(false);
    });
    return unsubscribe;
  }, []);

  return (
    <AuthContext.Provider value={{ user, claims, loading, refreshClaims }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
