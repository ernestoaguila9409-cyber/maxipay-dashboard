"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@/context/AuthContext";
import { useMerchantId } from "@/hooks/useMerchantId";
import MerchantAccountSwitcher from "@/components/MerchantAccountSwitcher";
import { merchantDoc } from "@/lib/merchantFirestore";
import { onSnapshot } from "firebase/firestore";
import { Bell, Search } from "lucide-react";

interface HeaderProps {
  title: string;
  searchValue?: string;
  onSearchChange?: (value: string) => void;
}

export default function Header({ title, searchValue, onSearchChange }: HeaderProps) {
  const { user, claims } = useAuth();
  const merchantId = useMerchantId();
  const [businessName, setBusinessName] = useState("");
  const showSearch = onSearchChange != null;

  useEffect(() => {
    if (!user || !merchantId) {
      setBusinessName("");
      return;
    }
    const ref = merchantDoc(merchantId, "Settings", "businessInfo");
    const unsub = onSnapshot(
      ref,
      (snap) => {
        if (!snap.exists()) {
          setBusinessName("");
          return;
        }
        const raw = snap.get("businessName");
        setBusinessName(typeof raw === "string" ? raw.trim() : "");
      },
      () => setBusinessName("")
    );
    return () => unsub();
  }, [user, merchantId]);

  const roleLabel =
    claims.role === "super_admin" ? "Admin" : claims.role === "merchant_staff" ? "Staff" : "Owner";

  return (
    <header className="h-16 bg-white border-b border-slate-200 flex items-center justify-between px-6 sticky top-0 z-20">
      <h1 className="text-xl font-semibold text-slate-800">{title}</h1>

      <div className="flex items-center gap-3 sm:gap-4 min-w-0">
        {showSearch && (
          <div className="relative hidden md:block">
            <Search
              size={18}
              className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
            />
            <input
              type="text"
              placeholder="Search items..."
              value={searchValue ?? ""}
              onChange={(e) => onSearchChange(e.target.value)}
              className="pl-10 pr-4 py-2 rounded-xl bg-slate-50 border border-slate-200 text-sm text-slate-700 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20 w-64 transition-all"
            />
          </div>
        )}

        {claims.role === "merchant_owner" || claims.role === "merchant_staff" ? (
          <MerchantAccountSwitcher activeBusinessName={businessName} />
        ) : businessName ? (
          <span
            className="text-sm font-semibold text-slate-800 truncate max-w-[7rem] sm:max-w-[12rem] md:max-w-[14rem] shrink text-right"
            title={businessName}
          >
            {businessName}
          </span>
        ) : null}

        <button
          type="button"
          className="relative p-2 rounded-xl hover:bg-slate-100 transition-colors text-slate-500 shrink-0"
          aria-label="Notifications"
        >
          <Bell size={20} />
          <span className="absolute top-1.5 right-1.5 w-2 h-2 bg-red-500 rounded-full" />
        </button>

        <div className="flex items-center gap-3 pl-3 sm:pl-4 border-l border-slate-200 min-w-0 shrink-0">
          <div className="w-8 h-8 rounded-full bg-blue-600 flex items-center justify-center shrink-0">
            <span className="text-white text-sm font-medium">
              {user?.email?.charAt(0).toUpperCase() || "U"}
            </span>
          </div>
          <div className="hidden sm:block min-w-0">
            <p className="text-sm font-medium text-slate-700 leading-tight truncate">
              {user?.email?.split("@")[0] || "User"}
            </p>
            <p className="text-xs text-slate-400">{roleLabel}</p>
          </div>
        </div>
      </div>
    </header>
  );
}
