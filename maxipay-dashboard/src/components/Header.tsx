"use client";

import { useAuth } from "@/context/AuthContext";
import { Bell, Search } from "lucide-react";

interface HeaderProps {
  title: string;
  searchValue?: string;
  onSearchChange?: (value: string) => void;
}

export default function Header({ title, searchValue, onSearchChange }: HeaderProps) {
  const { user } = useAuth();
  const showSearch = onSearchChange != null;

  return (
    <header className="h-16 bg-white border-b border-slate-200 flex items-center justify-between px-6 sticky top-0 z-20">
      <h1 className="text-xl font-semibold text-slate-800">{title}</h1>

      <div className="flex items-center gap-4">
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

        <button className="relative p-2 rounded-xl hover:bg-slate-100 transition-colors text-slate-500">
          <Bell size={20} />
          <span className="absolute top-1.5 right-1.5 w-2 h-2 bg-red-500 rounded-full" />
        </button>

        <div className="flex items-center gap-3 pl-4 border-l border-slate-200">
          <div className="w-8 h-8 rounded-full bg-blue-600 flex items-center justify-center">
            <span className="text-white text-sm font-medium">
              {user?.email?.charAt(0).toUpperCase() || "U"}
            </span>
          </div>
          <div className="hidden sm:block">
            <p className="text-sm font-medium text-slate-700 leading-tight">
              {user?.email?.split("@")[0] || "User"}
            </p>
            <p className="text-xs text-slate-400">Owner</p>
          </div>
        </div>
      </div>
    </header>
  );
}
