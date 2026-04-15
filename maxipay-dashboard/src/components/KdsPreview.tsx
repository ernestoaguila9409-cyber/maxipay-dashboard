"use client";

import { useMemo } from "react";

export interface KdsPreviewDisplaySettings {
  orderTypeColorsEnabled: boolean;
  gridColumns: 2 | 3;
  showTimers: boolean;
}

/** Mirrors Android [KdsColorPalette]. */
const PALETTE: Record<string, string> = {
  green: "#2E7D32",
  orange: "#E65100",
  teal: "#00897B",
  purple: "#6A4FB3",
  blue: "#1976D2",
  red: "#C62828",
  amber: "#F9A825",
  indigo: "#3949AB",
  pink: "#AD1457",
  cyan: "#0097A7",
};

function defaultColorKey(dashboardKey: string): string {
  if (dashboardKey === "dine_in") return "green";
  if (dashboardKey === "to_go") return "orange";
  if (dashboardKey === "bar") return "teal";
  return "purple";
}

function orderTypeToDashboardKey(orderType: string): string {
  const u = orderType.trim().toUpperCase();
  if (u === "DINE_IN") return "dine_in";
  if (u === "TO_GO" || u === "TAKEOUT" || u === "TAKE_OUT") return "to_go";
  if (u === "BAR" || u === "BAR_TAB") return "bar";
  return "to_go";
}

export function parseDashboardModuleColorKeys(
  raw: unknown
): Record<string, string> {
  const list = Array.isArray(raw) ? raw : [];
  const out: Record<string, string> = {};
  for (const item of list) {
    if (!item || typeof item !== "object") continue;
    const m = item as Record<string, unknown>;
    const key = String(m.key ?? "").trim().toLowerCase();
    if (!key) continue;
    let ck = String(m.colorKey ?? m.color_key ?? "").trim().toLowerCase();
    if (!ck) ck = defaultColorKey(key);
    out[key] = ck;
  }
  return out;
}

function headerColorHex(
  orderType: string,
  colorsEnabled: boolean,
  moduleColorKeys: Record<string, string>
): string {
  if (!colorsEnabled) return "#64748B";
  const dk = orderTypeToDashboardKey(orderType);
  const ck =
    moduleColorKeys[dk]?.trim().toLowerCase() || defaultColorKey(dk);
  return PALETTE[ck] ?? PALETTE[defaultColorKey(dk)] ?? "#6A4FB3";
}

function formatOrderTypeLabel(type: string): string {
  const t = type.trim().toUpperCase();
  if (t === "DINE_IN") return "Dine in";
  if (t === "TO_GO" || t === "TAKEOUT" || t === "TAKE_OUT") return "TO-GO";
  if (t === "BAR" || t === "BAR_TAB") return "Bar";
  return type;
}

function formatElapsed(elapsedMs: number): string {
  const totalSec = Math.max(0, Math.floor(elapsedMs / 1000));
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (h > 0)
    return `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  return `${m}:${String(s).padStart(2, "0")}`;
}

function formatKitchenTime(d: Date): string {
  return d.toLocaleTimeString(undefined, {
    hour: "numeric",
    minute: "2-digit",
  });
}

function getUrgencyBg(minutes: number): string {
  if (minutes >= 10) return "#FFCDD2";
  if (minutes >= 5) return "#FFF9C4";
  return "#FFFFFF";
}

function elapsedWarnColor(elapsedMs: number): string {
  if (elapsedMs >= 30 * 60 * 1000) return "#C62828";
  if (elapsedMs >= 15 * 60 * 1000) return "#E65100";
  return "#424242";
}

type MockOrder = {
  id: string;
  orderType: string;
  orderNumber: number;
  tableName?: string;
  status: "OPEN" | "PREPARING";
  items: { qty: number; name: string; mods?: string[] }[];
  /** Elapsed time simulated at first paint (grows with [nowMs]). */
  placedAtMs: number;
};

function buildMockOrders(anchorMs: number): MockOrder[] {
  return [
    {
      id: "pv1",
      orderType: "DINE_IN",
      orderNumber: 142,
      status: "OPEN",
      placedAtMs: anchorMs - 2 * 60 * 1000,
      items: [
        { qty: 2, name: "Burger", mods: ["No onion", "Extra pickle"] },
        { qty: 1, name: "Fries" },
      ],
    },
    {
      id: "pv2",
      orderType: "TO_GO",
      orderNumber: 288,
      status: "PREPARING",
      placedAtMs: anchorMs - 7 * 60 * 1000,
      items: [{ qty: 1, name: "Chicken wrap", mods: ["Add avocado"] }],
    },
    {
      id: "pv3",
      orderType: "BAR",
      orderNumber: 0,
      tableName: "Bar 7",
      status: "OPEN",
      placedAtMs: anchorMs - 14 * 60 * 1000,
      items: [
        { qty: 2, name: "Margarita" },
        { qty: 1, name: "IPA Draft" },
      ],
    },
  ];
}

function orderHeaderNumber(o: MockOrder): string {
  if (o.orderNumber > 0) return `#${o.orderNumber}`;
  return o.tableName ?? "—";
}

export function KdsPreview({
  displaySettings,
  nowMs,
  moduleColorKeys = {},
}: {
  displaySettings: KdsPreviewDisplaySettings;
  nowMs: number;
  moduleColorKeys?: Record<string, string>;
}) {
  const mockOrders = useMemo(() => buildMockOrders(Date.now()), []);

  const gridClass =
    displaySettings.gridColumns === 3
      ? "grid grid-cols-3 gap-2"
      : "grid grid-cols-2 gap-3";

  return (
    <div
      className="mx-auto w-full max-w-[400px] rounded-2xl border-4 border-slate-300 bg-slate-200/90 shadow-inner"
      style={{ maxHeight: "min(720px, 72vh)" }}
    >
      <div className="max-h-[inherit] overflow-y-auto overflow-x-hidden rounded-xl bg-[#FAFAFA] p-3">
        <h4
          className="mb-3 px-1 text-[22px] font-bold leading-tight text-[#1C1B1F]"
          style={{ fontFamily: "system-ui, sans-serif" }}
        >
          Kitchen display
        </h4>
        <div className={gridClass}>
          {mockOrders.map((order) => {
            const headerHex = headerColorHex(
              order.orderType,
              displaySettings.orderTypeColorsEnabled,
              moduleColorKeys
            );
            const elapsedMs = Math.max(0, nowMs - order.placedAtMs);
            const elapsedMin = Math.floor(elapsedMs / 60_000);
            const urgencyBg = getUrgencyBg(elapsedMin);
            const headerTime = formatKitchenTime(new Date(order.placedAtMs));

            return (
              <div
                key={order.id}
                className="flex min-h-[200px] flex-col overflow-hidden rounded-[20px] bg-white shadow-md"
              >
                <div
                  className="flex h-[52px] shrink-0 items-center px-3"
                  style={{
                    backgroundColor: headerHex,
                    borderRadius: "20px 20px 0 0",
                  }}
                >
                  <div className="grid w-full grid-cols-3 items-center gap-1 text-[15px] font-bold text-white">
                    <span className="truncate">
                      {formatOrderTypeLabel(order.orderType)}
                    </span>
                    <span className="truncate text-center">
                      {orderHeaderNumber(order)}
                    </span>
                    <span className="truncate text-right">{headerTime}</span>
                  </div>
                </div>
                <div
                  className="flex min-h-0 flex-1 flex-col"
                  style={{
                    backgroundColor: urgencyBg,
                    borderRadius: "0 0 20px 20px",
                  }}
                >
                  {displaySettings.showTimers && (
                    <div className="flex items-center justify-between px-3 py-2">
                      <span className="text-[13px] font-semibold text-[#64748B]">
                        Elapsed
                      </span>
                      <span
                        className="text-lg font-bold tabular-nums"
                        style={{ color: elapsedWarnColor(elapsedMs) }}
                      >
                        {formatElapsed(elapsedMs)}
                      </span>
                    </div>
                  )}
                  <div className="min-h-0 flex-1 space-y-2 overflow-y-auto px-4 pb-2 pt-2">
                    {order.items.map((it, idx) => (
                      <div key={idx}>
                        <p className="text-[15px] font-bold leading-snug text-[#212121]">
                          {it.qty}× {it.name}
                        </p>
                        {it.mods?.map((mod, mi) => (
                          <p
                            key={mi}
                            className="mt-1 pl-2 text-[13px] text-[#555555]"
                          >
                            • {mod}
                          </p>
                        ))}
                      </div>
                    ))}
                  </div>
                  <div className="shrink-0 px-4 pb-3 pt-1">
                    {order.status === "OPEN" && (
                      <button
                        type="button"
                        className="w-full rounded-[14px] bg-[#1565C0] py-3 text-center text-[17px] font-bold text-white"
                        disabled
                      >
                        START
                      </button>
                    )}
                    {order.status === "PREPARING" && (
                      <button
                        type="button"
                        className="w-full rounded-[14px] bg-[#2E7D32] py-3 text-center text-[17px] font-bold text-white"
                        disabled
                      >
                        READY
                      </button>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
