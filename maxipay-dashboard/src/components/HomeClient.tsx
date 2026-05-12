"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { onAuthStateChanged } from "firebase/auth";
import { auth } from "@/firebase/firebaseConfig";

export default function HomeClient() {
  const router = useRouter();

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, async (user) => {
      if (user) {
        const tokenResult = await user.getIdTokenResult();
        const role = tokenResult.claims.role as string | undefined;
        if (role === "merchant_owner" || role === "super_admin" || role === "merchant_staff") {
          router.replace("/dashboard");
        } else {
          router.replace("/login?error=no_merchant");
        }
      } else {
        router.replace("/login");
      }
    });
    return unsubscribe;
  }, [router]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-100">
      <div className="w-8 h-8 border-3 border-blue-600 border-t-transparent rounded-full animate-spin" />
    </div>
  );
}
