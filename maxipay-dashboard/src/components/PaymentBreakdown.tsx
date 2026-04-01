"use client";

export interface PaymentBreakdownTotals {
  card: number;
  cash: number;
  other: number;
}

interface PaymentBreakdownProps {
  totals: PaymentBreakdownTotals;
  loading?: boolean;
  emptyMessage?: string;
}

function formatMoney(n: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(n);
}

export default function PaymentBreakdown({
  totals,
  loading,
  emptyMessage = "Payment totals will appear when card or cash sales are recorded for today.",
}: PaymentBreakdownProps) {
  const { card, cash, other } = totals;
  const sum = card + cash + other;

  if (loading) {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6">
        <div className="flex items-center gap-3 text-slate-400 text-sm">
          <div className="w-4 h-4 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          Loading payment methods…
        </div>
      </div>
    );
  }

  const rows: { key: keyof PaymentBreakdownTotals; label: string; color: string }[] = [
    { key: "card", label: "Card", color: "bg-blue-500" },
    { key: "cash", label: "Cash", color: "bg-emerald-500" },
    { key: "other", label: "Other", color: "bg-slate-400" },
  ];

  if (sum <= 0) {
    return (
      <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6">
        <h3 className="text-sm font-semibold text-slate-800 mb-1">Payment methods</h3>
        <p className="text-sm text-slate-400">{emptyMessage}</p>
      </div>
    );
  }

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6">
      <h3 className="text-sm font-semibold text-slate-800 mb-4">Payment methods</h3>
      <div
        className="flex h-3 rounded-full overflow-hidden bg-slate-100 mb-6"
        role="img"
        aria-label="Payment mix"
      >
        {rows.map(({ key, color }) => {
          const v = totals[key];
          const pct = sum > 0 ? Math.max(0, (v / sum) * 100) : 0;
          if (pct <= 0) return null;
          return (
            <div
              key={key}
              className={`${color} h-full transition-all`}
              style={{ width: `${pct}%` }}
            />
          );
        })}
      </div>
      <ul className="space-y-3">
        {rows.map(({ key, label, color }) => (
          <li key={key} className="flex items-center justify-between gap-4 text-sm">
            <span className="flex items-center gap-2 text-slate-600">
              <span className={`w-2.5 h-2.5 rounded-full ${color}`} />
              {label}
            </span>
            <span className="font-medium text-slate-900 tabular-nums">
              {formatMoney(totals[key])}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
