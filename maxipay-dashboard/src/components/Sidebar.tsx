"use client";

import { useState, useEffect, useCallback } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  LayoutDashboard,
  ClipboardList,
  UtensilsCrossed,
  SlidersHorizontal,
  Tags,
  Receipt,
  Users,
  BarChart3,
  Printer,
  Settings,
  LayoutGrid,
  LogOut,
  ChevronLeft,
  ChevronDown,
  CalendarClock,
  Store,
} from "lucide-react";
import { signOut } from "firebase/auth";
import { auth } from "@/firebase/firebaseConfig";
import { useRouter } from "next/navigation";

interface NavItem {
  label: string;
  href: string;
  icon: React.ComponentType<{ size?: number; className?: string }>;
  children?: { label: string; href: string; icon: React.ComponentType<{ size?: number; className?: string }> }[];
}

const navItems: NavItem[] = [
  { label: "Dashboard", href: "/dashboard", icon: LayoutDashboard },
  { label: "Orders", href: "/dashboard/orders", icon: ClipboardList },
  {
    label: "Menu",
    href: "/dashboard/menu",
    icon: UtensilsCrossed,
    children: [
      { label: "Menus", href: "/dashboard/menus", icon: CalendarClock },
      { label: "Modifiers", href: "/dashboard/modifiers", icon: SlidersHorizontal },
    ],
  },
  { label: "Employees", href: "/dashboard/employees", icon: Users },
  { label: "Printers", href: "/dashboard/printers", icon: Printer },
  { label: "Reports", href: "/dashboard/reports", icon: BarChart3 },
  {
    label: "Settings",
    href: "/dashboard/settings",
    icon: Settings,
    children: [
      { label: "Business Information", href: "/dashboard/settings/business", icon: Store },
      { label: "Taxes", href: "/dashboard/taxes", icon: Receipt },
      { label: "Discounts", href: "/dashboard/discounts", icon: Tags },
      { label: "Customize Dashboard", href: "/dashboard/settings/customize-dashboard", icon: LayoutGrid },
    ],
  },
];

interface SidebarProps {
  collapsed: boolean;
  onToggle: () => void;
}

export default function Sidebar({ collapsed, onToggle }: SidebarProps) {
  const pathname = usePathname();
  const router = useRouter();

  const isSectionActive = useCallback(
    (item: NavItem) => {
      if (pathname === item.href) return true;
      if (item.children?.some((c) => pathname.startsWith(c.href))) return true;
      if (item.href !== "/dashboard" && pathname.startsWith(item.href)) return true;
      return false;
    },
    [pathname]
  );

  const [expanded, setExpanded] = useState<Record<string, boolean>>(() => {
    const initial: Record<string, boolean> = {};
    navItems.forEach((item) => {
      if (item.children && isSectionActive(item)) {
        initial[item.href] = true;
      }
    });
    return initial;
  });

  useEffect(() => {
    navItems.forEach((item) => {
      if (item.children && isSectionActive(item)) {
        setExpanded((prev) => ({ ...prev, [item.href]: true }));
      }
    });
  }, [pathname, isSectionActive]);

  const toggleSection = (href: string) => {
    setExpanded((prev) => ({ ...prev, [href]: !prev[href] }));
  };

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
        <div className="flex-shrink-0 w-9 h-9 rounded-xl bg-blue-600 flex items-center justify-center">
          <span className="text-white font-bold text-sm">M</span>
        </div>
        {!collapsed && (
          <span className="text-lg font-bold text-slate-800">MaxiPay</span>
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
          if (item.children) {
            const sectionActive = isSectionActive(item);
            const isExpanded = expanded[item.href] ?? false;

            return (
              <div key={item.href}>
                <div className="flex items-center">
                  <Link
                    href={item.href}
                    className={`flex-1 flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-all ${
                      sectionActive
                        ? "bg-blue-50 text-blue-600"
                        : "text-slate-600 hover:bg-slate-50 hover:text-slate-900"
                    } ${collapsed ? "justify-center" : ""}`}
                    title={collapsed ? item.label : undefined}
                  >
                    <item.icon size={20} className="flex-shrink-0" />
                    {!collapsed && <span>{item.label}</span>}
                  </Link>
                  {!collapsed && (
                    <button
                      onClick={() => toggleSection(item.href)}
                      className={`p-1.5 rounded-lg transition-all ${
                        sectionActive
                          ? "text-blue-400 hover:bg-blue-100"
                          : "text-slate-400 hover:bg-slate-100"
                      }`}
                    >
                      <ChevronDown
                        size={16}
                        className={`transition-transform duration-200 ${
                          isExpanded ? "rotate-0" : "-rotate-90"
                        }`}
                      />
                    </button>
                  )}
                </div>

                {!collapsed && isExpanded && (
                  <div className="ml-5 pl-3 border-l border-slate-200 mt-1 space-y-0.5">
                    {item.children.map((child) => {
                      const isChildActive = pathname.startsWith(child.href);
                      return (
                        <Link
                          key={child.href}
                          href={child.href}
                          className={`flex items-center gap-3 px-3 py-2 rounded-xl text-sm font-medium transition-all ${
                            isChildActive
                              ? "bg-blue-50 text-blue-600"
                              : "text-slate-500 hover:bg-slate-50 hover:text-slate-800"
                          }`}
                        >
                          <child.icon size={16} className="flex-shrink-0" />
                          <span>{child.label}</span>
                        </Link>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          }

          const isActive =
            pathname === item.href ||
            (item.href !== "/dashboard" && pathname.startsWith(item.href));
          return (
            <Link
              key={item.href}
              href={item.href}
              className={`flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-all ${
                isActive
                  ? "bg-blue-50 text-blue-600"
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
