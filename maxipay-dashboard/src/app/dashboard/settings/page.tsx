"use client";

import Link from "next/link";
import Header from "@/components/Header";
import { useAuth } from "@/context/AuthContext";
import { Store, Bell, Shield, Palette, ChevronRight, ShoppingBag, type LucideIcon } from "lucide-react";

export default function SettingsPage() {
  const { user } = useAuth();

  const sections: {
    icon: LucideIcon;
    title: string;
    description: string;
    href?: string;
  }[] = [
    {
      icon: Store,
      title: "Business Information",
      description: "Manage your restaurant name, address, and contact info",
      href: "/dashboard/settings/business",
    },
    {
      icon: ShoppingBag,
      title: "Online ordering",
      description: "Public order page — pay at pickup or notify POS for Dejavoo (SPIn)",
      href: "/dashboard/settings/online-ordering",
    },
    {
      icon: Bell,
      title: "Notifications",
      description: "Configure alerts for orders, low stock, and reports",
    },
    {
      icon: Shield,
      title: "Security",
      description: "Password, two-factor authentication, and login history",
    },
    {
      icon: Palette,
      title: "Appearance",
      description: "Theme preferences and dashboard customization",
    },
  ];

  return (
    <>
      <Header title="Settings" />
      <div className="p-6 space-y-6">
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6">
          <h3 className="text-sm font-semibold text-slate-500 uppercase tracking-wider mb-4">
            Account
          </h3>
          <div className="flex items-center gap-4">
            <div className="w-14 h-14 rounded-full bg-blue-600 flex items-center justify-center">
              <span className="text-white text-xl font-semibold">
                {user?.email?.charAt(0).toUpperCase() || "U"}
              </span>
            </div>
            <div>
              <p className="font-semibold text-slate-800">
                {user?.email?.split("@")[0] || "User"}
              </p>
              <p className="text-sm text-slate-500">{user?.email || ""}</p>
            </div>
          </div>
        </div>

        <div className="space-y-3">
          {sections.map((section) => {
            const cardInner = (
              <>
                <div className="p-3 rounded-xl bg-slate-50">
                  <section.icon size={22} className="text-slate-600" />
                </div>
                <div className="flex-1 min-w-0 text-left">
                  <h3 className="font-semibold text-slate-800">
                    {section.title}
                  </h3>
                  <p className="text-sm text-slate-500">{section.description}</p>
                </div>
              </>
            );

            if (section.href) {
              return (
                <Link
                  key={section.title}
                  href={section.href}
                  className="w-full bg-white rounded-2xl shadow-sm border border-slate-100 p-5 flex items-center gap-4 text-left transition-all duration-200 hover:shadow-md hover:border-blue-200 hover:bg-slate-50/60 active:scale-[0.995] cursor-pointer group no-underline text-inherit"
                >
                  {cardInner}
                  <ChevronRight
                    size={20}
                    className="text-slate-400 shrink-0 transition-transform group-hover:translate-x-0.5 group-hover:text-blue-500"
                    aria-hidden
                  />
                </Link>
              );
            }

            return (
              <div
                key={section.title}
                className="w-full bg-white rounded-2xl shadow-sm border border-slate-100 p-5 flex items-center gap-4 transition-shadow hover:shadow-md"
              >
                {cardInner}
              </div>
            );
          })}
        </div>

        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6 text-center">
          <p className="text-slate-400 text-sm">
            Full settings management coming soon
          </p>
        </div>
      </div>
    </>
  );
}
