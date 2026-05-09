"use client";

import { createContext, useContext, useEffect, useState, ReactNode } from "react";
import { onAuthStateChanged, User } from "firebase/auth";
import { auth } from "@/firebase/firebaseConfig";

export interface CustomClaims {
  role?: "super_admin" | "merchant_owner";
  merchantId?: string;
}

interface AuthContextType {
  user: User | null;
  claims: CustomClaims;
  loading: boolean;
}

const AuthContext = createContext<AuthContextType>({
  user: null,
  claims: {},
  loading: true,
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [claims, setClaims] = useState<CustomClaims>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, async (firebaseUser) => {
      setUser(firebaseUser);
      if (firebaseUser) {
        const tokenResult = await firebaseUser.getIdTokenResult();
        setClaims({
          role: tokenResult.claims.role as CustomClaims["role"],
          merchantId: tokenResult.claims.merchantId as string | undefined,
        });
      } else {
        setClaims({});
      }
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
