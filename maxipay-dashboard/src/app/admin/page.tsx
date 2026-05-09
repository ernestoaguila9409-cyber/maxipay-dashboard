"use client";

import { useEffect, useState } from "react";
import { collection, onSnapshot } from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { Store, Users, CheckCircle, Clock } from "lucide-react";
import Link from "next/link";

interface Stats {
  total: number;
  active: number;
  pending: number;
}

export default function AdminHomePage() {
  const [stats, setStats] = useState<Stats>({ total: 0, active: 0, pending: 0 });
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const unsub = onSnapshot(collection(db, "Merchants"), (snap) => {
      let active = 0;
      let pending = 0;
      snap.docs.forEach((doc) => {
        const status = doc.data().status;
        if (status === "active") active++;
        else if (status === "pending") pending++;
      });
      setStats({ total: snap.size, active, pending });
      setLoading(false);
    });
    return () => unsub();
  }, []);

  return (
    <div className="p-8">
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-slate-900">Admin Overview</h1>
        <p className="text-slate-500 mt-1">Manage merchant accounts and platform settings.</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
        <StatCard
          icon={Store}
          label="Total Merchants"
          value={loading ? "..." : stats.total}
          color="blue"
        />
        <StatCard
          icon={CheckCircle}
          label="Active"
          value={loading ? "..." : stats.active}
          color="green"
        />
        <StatCard
          icon={Clock}
          label="Pending"
          value={loading ? "..." : stats.pending}
          color="amber"
        />
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 p-6">
        <h2 className="text-lg font-semibold text-slate-800 mb-4">Quick Actions</h2>
        <div className="flex gap-4">
          <Link
            href="/admin/merchants/new"
            className="inline-flex items-center gap-2 px-5 py-2.5 bg-blue-600 text-white text-sm font-medium rounded-xl hover:bg-blue-700 transition-colors"
          >
            <Store size={18} />
            Create Merchant
          </Link>
          <Link
            href="/admin/merchants"
            className="inline-flex items-center gap-2 px-5 py-2.5 bg-white text-slate-700 text-sm font-medium rounded-xl border border-slate-200 hover:bg-slate-50 transition-colors"
          >
            <Users size={18} />
            View All Merchants
          </Link>
        </div>
      </div>
    </div>
  );
}

function StatCard({
  icon: Icon,
  label,
  value,
  color,
}: {
  icon: React.ComponentType<{ size?: number; className?: string }>;
  label: string;
  value: number | string;
  color: "blue" | "green" | "amber";
}) {
  const colorMap = {
    blue: "bg-blue-50 text-blue-600",
    green: "bg-green-50 text-green-600",
    amber: "bg-amber-50 text-amber-600",
  };

  return (
    <div className="bg-white rounded-2xl border border-slate-200 p-6">
      <div className="flex items-center gap-3 mb-3">
        <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${colorMap[color]}`}>
          <Icon size={20} />
        </div>
        <span className="text-sm font-medium text-slate-500">{label}</span>
      </div>
      <p className="text-3xl font-bold text-slate-900">{value}</p>
    </div>
  );
}
