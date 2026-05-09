"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  LayoutDashboard,
  Store,
  ChevronLeft,
  LogOut,
  ShieldCheck,
} from "lucide-react";
import { signOut } from "firebase/auth";
import { auth } from "@/firebase/firebaseConfig";

interface NavItem {
  label: string;
  href: string;
  icon: React.ComponentType<{ size?: number; className?: string }>;
}

const navItems: NavItem[] = [
  { label: "Overview", href: "/", icon: LayoutDashboard },
  { label: "Merchants", href: "/merchants", icon: Store },
];

interface AdminSidebarProps {
  collapsed: boolean;
  onToggle: () => void;
}

export default function AdminSidebar({ collapsed, onToggle }: AdminSidebarProps) {
  const pathname = usePathname();
  const router = useRouter();

  const handleLogout = async () => {
    await signOut(auth);
    router.push("/login");
  };

  return (
    <aside
      className={`fixed top-0 left-0 h-screen bg-white border-r border-slate-200 flex flex-col transition-all duration-300 z-30 ${
        collapsed ? "w-[72px]" : "w-[260px]"
      }`}
    >
      <div className="flex items-center gap-3 px-5 h-16 border-b border-slate-100">
        <div className="flex-shrink-0 w-9 h-9 rounded-xl bg-slate-800 flex items-center justify-center">
          <ShieldCheck size={18} className="text-white" />
        </div>
        {!collapsed && (
          <span className="text-lg font-bold text-slate-800">MaxiPay Admin</span>
        )}
        <button
          onClick={onToggle}
          className={`ml-auto p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 transition-all ${
            collapsed ? "rotate-180" : ""
          }`}
        >
          <ChevronLeft size={18} />
        </button>
      </div>

      <nav className="flex-1 py-4 px-3 space-y-1 overflow-y-auto">
        {navItems.map((item) => {
          const isActive =
            item.href === "/"
              ? pathname === "/"
              : pathname.startsWith(item.href);
          return (
            <Link
              key={item.href}
              href={item.href}
              className={`flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-all ${
                isActive
                  ? "bg-slate-100 text-slate-900"
                  : "text-slate-600 hover:bg-slate-50 hover:text-slate-900"
              } ${collapsed ? "justify-center" : ""}`}
              title={collapsed ? item.label : undefined}
            >
              <item.icon size={20} className="flex-shrink-0" />
              {!collapsed && <span>{item.label}</span>}
            </Link>
          );
        })}
      </nav>

      <div className="p-3 border-t border-slate-100">
        <button
          onClick={handleLogout}
          className={`flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium text-slate-600 hover:bg-red-50 hover:text-red-600 transition-all w-full ${
            collapsed ? "justify-center" : ""
          }`}
          title={collapsed ? "Logout" : undefined}
        >
          <LogOut size={20} className="flex-shrink-0" />
          {!collapsed && <span>Logout</span>}
        </button>
      </div>
    </aside>
  );
}
