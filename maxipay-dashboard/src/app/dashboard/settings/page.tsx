"use client";

import Header from "@/components/Header";
import { useAuth } from "@/context/AuthContext";
import { Store, Bell, Shield, Palette } from "lucide-react";

export default function SettingsPage() {
  const { user } = useAuth();

  const sections = [
    {
      icon: Store,
      title: "Business Information",
      description: "Manage your restaurant name, address, and contact info",
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
          {sections.map((section) => (
            <button
              key={section.title}
              className="w-full bg-white rounded-2xl shadow-sm border border-slate-100 p-5 flex items-center gap-4 hover:shadow-md transition-shadow text-left"
            >
              <div className="p-3 rounded-xl bg-slate-50">
                <section.icon size={22} className="text-slate-600" />
              </div>
              <div>
                <h3 className="font-semibold text-slate-800">
                  {section.title}
                </h3>
                <p className="text-sm text-slate-500">{section.description}</p>
              </div>
            </button>
          ))}
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
