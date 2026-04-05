"use client";

import { useCallback, useEffect, useState } from "react";
import Header from "@/components/Header";
import StatCard from "@/components/StatCard";
import {
  DollarSign,
  Receipt,
  TrendingUp,
  Wallet,
  Calendar,
  Users,
  CreditCard,
  Clock,
  ShoppingBag,
  Percent,
  Ban,
  RotateCcw,
} from "lucide-react";
import {
  type DailySalesSummary,
  type SalesByOrderType,
  type HourlySale,
  type CardBrandSale,
  type EmployeeMetrics,
  periodRange,
  getDailySalesSummary,
  getSalesByOrderType,
  getHourlySales,
  getCardBrandSales,
  getEmployeeMetrics,
} from "@/lib/reportEngine";

type Period = "today" | "week" | "month";

function centsToDisplay(cents: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
  }).format(cents / 100);
}

function formatHour(hour: number): string {
  if (hour === 0) return "12:00 AM";
  if (hour < 12) return `${hour}:00 AM`;
  if (hour === 12) return "12:00 PM";
  return `${hour - 12}:00 PM`;
}

function orderTypeLabel(raw: string): string {
  switch (raw) {
    case "DINE_IN":
      return "Dine-In";
    case "TO_GO":
      return "To-Go";
    case "BAR":
    case "BAR_TAB":
      return "Bar";
    default:
      return raw.replace("_", " ");
  }
}

function SectionCard({
  title,
  icon: Icon,
  children,
}: {
  title: string;
  icon: React.ComponentType<{ size?: number; className?: string }>;
  children: React.ReactNode;
}) {
  return (
    <div className="bg-white rounded-2xl shadow-sm border border-slate-100 overflow-hidden">
      <div className="px-6 py-4 border-b border-slate-100 flex items-center gap-2">
        <Icon size={18} className="text-slate-500" />
        <h3 className="text-base font-semibold text-slate-800">{title}</h3>
      </div>
      <div className="px-6 py-5">{children}</div>
    </div>
  );
}

function RowPair({
  label,
  value,
  bold,
  valueColor,
  indent,
}: {
  label: string;
  value: string;
  bold?: boolean;
  valueColor?: string;
  indent?: boolean;
}) {
  return (
    <div
      className={`flex justify-between py-1.5 ${indent ? "pl-4" : ""} ${
        bold ? "font-semibold" : ""
      }`}
    >
      <span className="text-slate-600 text-sm">{label}</span>
      <span
        className="text-sm font-medium tabular-nums"
        style={{ color: valueColor ?? "#1e293b" }}
      >
        {value}
      </span>
    </div>
  );
}

function Divider() {
  return <hr className="my-2 border-slate-100" />;
}

export default function ReportsPage() {
  const [period, setPeriod] = useState<Period>("today");
  const [loading, setLoading] = useState(true);
  const [summary, setSummary] = useState<DailySalesSummary | null>(null);
  const [orderTypeSales, setOrderTypeSales] =
    useState<SalesByOrderType | null>(null);
  const [hourlySales, setHourlySales] = useState<HourlySale[]>([]);
  const [cardBrands, setCardBrands] = useState<CardBrandSale[]>([]);
  const [employees, setEmployees] = useState<EmployeeMetrics[]>([]);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const { start, end } = periodRange(period);
      const [s, ot, hr, cb, emp] = await Promise.all([
        getDailySalesSummary(start, end),
        getSalesByOrderType(start, end),
        getHourlySales(start, end),
        getCardBrandSales(start, end),
        getEmployeeMetrics(start, end),
      ]);
      setSummary(s);
      setOrderTypeSales(ot);
      setHourlySales(hr);
      setCardBrands(cb);
      setEmployees(emp);
    } catch (err) {
      console.error("Error loading reports:", err);
    } finally {
      setLoading(false);
    }
  }, [period]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const periodLabels = {
    today: "Today",
    week: "Last 7 Days",
    month: "This Month",
  };

  const s = summary;

  return (
    <>
      <Header title="Reports" />
      <div className="p-6 space-y-6">
        {/* Period selector */}
        <div className="flex items-center gap-2">
          <Calendar size={18} className="text-slate-400" />
          <div className="flex bg-white rounded-xl border border-slate-200 p-1">
            {(["today", "week", "month"] as const).map((p) => (
              <button
                key={p}
                onClick={() => setPeriod(p)}
                className={`px-4 py-1.5 rounded-lg text-sm font-medium transition-all ${
                  period === p
                    ? "bg-blue-600 text-white"
                    : "text-slate-600 hover:bg-slate-50"
                }`}
              >
                {periodLabels[p]}
              </button>
            ))}
          </div>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
          </div>
        ) : (
          <>
            {/* Top-level KPI cards */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
              <StatCard
                title="Gross Sales"
                value={centsToDisplay(s?.grossSalesCents ?? 0)}
                icon={DollarSign}
                color="blue"
              />
              <StatCard
                title="Total Orders"
                value={String(s?.totalTransactions ?? 0)}
                icon={Receipt}
                color="green"
              />
              <StatCard
                title="Tips Collected"
                value={centsToDisplay(s?.tipsCollectedCents ?? 0)}
                icon={Wallet}
                color="purple"
              />
              <StatCard
                title="Average Ticket"
                value={centsToDisplay(s?.averageTicketCents ?? 0)}
                icon={TrendingUp}
                color="orange"
              />
            </div>

            {/* Detail sections */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              {/* Sales Overview */}
              <SectionCard title="Sales Overview" icon={DollarSign}>
                <RowPair
                  label="Gross Sales"
                  value={centsToDisplay(s?.grossSalesCents ?? 0)}
                  bold
                />
                {(s?.discountsCents ?? 0) > 0 && (
                  <RowPair
                    label="Discounts"
                    value={`-${centsToDisplay(s!.discountsCents)}`}
                    valueColor="#E65100"
                  />
                )}
                <RowPair
                  label="Tax Collected"
                  value={centsToDisplay(s?.taxCollectedCents ?? 0)}
                />
                <RowPair
                  label="Tips Collected"
                  value={centsToDisplay(s?.tipsCollectedCents ?? 0)}
                />
                <RowPair
                  label="Net Sales"
                  value={centsToDisplay(s?.netSalesCents ?? 0)}
                  bold
                  valueColor="#2E7D32"
                />
                <Divider />
                <RowPair
                  label="Transactions"
                  value={String(s?.totalTransactions ?? 0)}
                />
                <RowPair
                  label="Average Ticket"
                  value={centsToDisplay(s?.averageTicketCents ?? 0)}
                />
                <RowPair
                  label="Items Sold"
                  value={String(s?.itemsSold ?? 0)}
                />
                <RowPair
                  label="Refunds"
                  value={centsToDisplay(s?.refundsCents ?? 0)}
                  valueColor="#C62828"
                />
                <RowPair
                  label="Voided Items"
                  value={String(s?.voidedItems ?? 0)}
                />
              </SectionCard>

              {/* Payment Methods */}
              <SectionCard title="Payment Methods" icon={CreditCard}>
                <RowPair
                  label="Cash"
                  value={centsToDisplay(s?.cashPaymentsCents ?? 0)}
                />
                <RowPair
                  label="Credit"
                  value={centsToDisplay(s?.creditPaymentsCents ?? 0)}
                />
                <RowPair
                  label="Debit"
                  value={centsToDisplay(s?.debitPaymentsCents ?? 0)}
                />
                <Divider />
                <RowPair
                  label="Total"
                  value={centsToDisplay(
                    (s?.cashPaymentsCents ?? 0) +
                      (s?.creditPaymentsCents ?? 0) +
                      (s?.debitPaymentsCents ?? 0)
                  )}
                  bold
                  valueColor="#2E7D32"
                />
              </SectionCard>

              {/* Sales by Order Type */}
              {orderTypeSales && (
                <SectionCard title="Sales by Order Type" icon={ShoppingBag}>
                  {(() => {
                    const ot = orderTypeSales;
                    const total =
                      ot.dineInCents + ot.toGoCents + ot.barCents;
                    const pct = (part: number) =>
                      total > 0 ? Math.round((part * 100) / total) : 0;
                    return (
                      <>
                        <RowPair
                          label="Dine-In"
                          value={`${centsToDisplay(ot.dineInCents)}  (${pct(
                            ot.dineInCents
                          )}%)`}
                          bold
                        />
                        <RowPair
                          label="To-Go"
                          value={`${centsToDisplay(ot.toGoCents)}  (${pct(
                            ot.toGoCents
                          )}%)`}
                          bold
                        />
                        <RowPair
                          label="Bar"
                          value={`${centsToDisplay(ot.barCents)}  (${pct(
                            ot.barCents
                          )}%)`}
                          bold
                        />
                        <Divider />
                        <RowPair
                          label="Total"
                          value={centsToDisplay(total)}
                          bold
                          valueColor="#2E7D32"
                        />
                      </>
                    );
                  })()}
                </SectionCard>
              )}

              {/* Card Brands */}
              {cardBrands.length > 0 && (
                <SectionCard title="Sales by Card Brand" icon={CreditCard}>
                  {cardBrands.map((cb) => (
                    <RowPair
                      key={cb.brand}
                      label={`${cb.brand} (${cb.txCount} txn)`}
                      value={centsToDisplay(cb.totalCents)}
                      bold
                    />
                  ))}
                  <Divider />
                  <RowPair
                    label="Total"
                    value={centsToDisplay(
                      cardBrands.reduce((a, c) => a + c.totalCents, 0)
                    )}
                    bold
                    valueColor="#2E7D32"
                  />
                </SectionCard>
              )}

              {/* Hourly Sales */}
              {hourlySales.length > 0 && (
                <SectionCard title="Hourly Sales" icon={Clock}>
                  {hourlySales.map((h) => (
                    <RowPair
                      key={h.hour}
                      label={formatHour(h.hour)}
                      value={`${centsToDisplay(h.totalCents)}  (${
                        h.orderCount
                      } orders)`}
                      bold
                    />
                  ))}
                  <Divider />
                  <RowPair
                    label="Total"
                    value={centsToDisplay(
                      hourlySales.reduce((a, c) => a + c.totalCents, 0)
                    )}
                    bold
                    valueColor="#2E7D32"
                  />
                </SectionCard>
              )}

              {/* Employee Report */}
              {employees.length > 0 && (
                <SectionCard title="Employee Report" icon={Users}>
                  {employees.map((emp) => (
                    <div
                      key={emp.name}
                      className="mb-4 last:mb-0 pb-4 last:pb-0 border-b last:border-b-0 border-slate-100"
                    >
                      <p className="text-sm font-semibold text-slate-800 mb-1">
                        {emp.name}
                      </p>
                      <RowPair
                        label="Sales"
                        value={centsToDisplay(emp.salesCents)}
                        indent
                        bold
                      />
                      <RowPair
                        label="Orders"
                        value={String(emp.orderCount)}
                        indent
                      />
                      {emp.tipsCents > 0 && (
                        <RowPair
                          label={`Tips (${emp.tipsCount})`}
                          value={centsToDisplay(emp.tipsCents)}
                          indent
                        />
                      )}
                      {emp.refundsCents > 0 && (
                        <RowPair
                          label={`Refunds (${emp.refundsCount})`}
                          value={centsToDisplay(emp.refundsCents)}
                          indent
                          valueColor="#C62828"
                        />
                      )}
                      {emp.voidsCount > 0 && (
                        <RowPair
                          label="Voids"
                          value={String(emp.voidsCount)}
                          indent
                          valueColor="#C62828"
                        />
                      )}
                    </div>
                  ))}
                </SectionCard>
              )}
            </div>
          </>
        )}
      </div>
    </>
  );
}
