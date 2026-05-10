"use client";

import { createContext, useContext, useEffect, useState, ReactNode } from "react";
import { onAuthStateChanged, User } from "firebase/auth";
import { auth } from "@/firebase/firebaseConfig";

export interface AuthClaims {
  role?: string;
  merchantId?: string;
}

interface AuthContextType {
  user: User | null;
  claims: AuthClaims;
  loading: boolean;
}

const AuthContext = createContext<AuthContextType>({
  user: null,
  claims: {},
  loading: true,
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [claims, setClaims] = useState<AuthClaims>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, async (firebaseUser) => {
      if (firebaseUser) {
        const tokenResult = await firebaseUser.getIdTokenResult();
        const role = (tokenResult.claims.role as string) ?? undefined;
        const merchantId = (tokenResult.claims.merchantId as string) ?? undefined;
        console.log("[Auth] State changed →", `UID: ${firebaseUser.uid}, email: ${firebaseUser.email}, role: ${role ?? "none"}, merchantId: ${merchantId ?? "none"}`);
        setClaims({ role, merchantId });
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
    <AuthContext.Provider value={{ user, claims, loading }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
