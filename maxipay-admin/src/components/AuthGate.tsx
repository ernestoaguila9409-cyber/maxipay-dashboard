"use client";

import { useEffect, useState } from "react";
import { useRouter, usePathname } from "next/navigation";
import { useAuth, AuthProvider } from "@/context/AuthContext";
import AdminSidebar from "@/components/AdminSidebar";

function AuthGateInner({ children }: { children: React.ReactNode }) {
  const { user, claims, loading } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  const isLoginPage = pathname === "/login";

  useEffect(() => {
    if (loading) return;

    if (!user && !isLoginPage) {
      router.replace("/login");
      return;
    }

    if (user && !isLoginPage && claims.role !== "super_admin") {
      router.replace("/login");
    }
  }, [user, claims, loading, router, isLoginPage]);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-100">
        <div className="flex flex-col items-center gap-4">
          <div className="w-10 h-10 border-3 border-slate-700 border-t-transparent rounded-full animate-spin" />
          <p className="text-slate-500 text-sm">Loading...</p>
        </div>
      </div>
    );
  }

  if (isLoginPage) {
    return <>{children}</>;
  }

  if (!user || claims.role !== "super_admin") return null;

  return (
    <div className="min-h-screen bg-slate-50">
      <AdminSidebar
        collapsed={sidebarCollapsed}
        onToggle={() => setSidebarCollapsed(!sidebarCollapsed)}
      />
      <main
        className={`transition-all duration-300 ${
          sidebarCollapsed ? "ml-[72px]" : "ml-[260px]"
        }`}
      >
        {children}
      </main>
    </div>
  );
}

export default function AuthGate({ children }: { children: React.ReactNode }) {
  return (
    <AuthProvider>
      <AuthGateInner>{children}</AuthGateInner>
    </AuthProvider>
  );
}
