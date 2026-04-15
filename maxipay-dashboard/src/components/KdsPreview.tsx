"use client";

export interface KdsPreviewDisplaySettings {
  orderTypeColorsEnabled: boolean;
  gridColumns?: 2 | 3;
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
  /** Fixed header clock (preview only; does not tick). */
  headerTime: string;
  /** Fixed elapsed label (preview only; does not tick). */
  elapsedLabel: string;
  /** For urgency background / warn color only. */
  elapsedMs: number;
};

const MOCK_PREVIEW_ORDERS: MockOrder[] = [
  {
    id: "pv1",
    orderType: "DINE_IN",
    orderNumber: 142,
    status: "OPEN",
    headerTime: "11:26 AM",
    elapsedLabel: "2:00",
    elapsedMs: 2 * 60 * 1000,
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
    headerTime: "11:21 AM",
    elapsedLabel: "7:00",
    elapsedMs: 7 * 60 * 1000,
    items: [{ qty: 1, name: "Chicken wrap", mods: ["Add avocado"] }],
  },
  {
    id: "pv3",
    orderType: "BAR",
    orderNumber: 0,
    tableName: "Bar 7",
    status: "OPEN",
    headerTime: "11:14 AM",
    elapsedLabel: "14:00",
    elapsedMs: 14 * 60 * 1000,
    items: [
      { qty: 2, name: "Margarita" },
      { qty: 1, name: "IPA Draft" },
    ],
  },
];

function orderHeaderNumber(o: MockOrder): string {
  if (o.orderNumber > 0) return `#${o.orderNumber}`;
  return o.tableName ?? "—";
}

export function KdsPreview({
  displaySettings,
  moduleColorKeys = {},
}: {
  displaySettings: KdsPreviewDisplaySettings;
  moduleColorKeys?: Record<string, string>;
}) {
  return (
    <div className="flex h-full w-full flex-col">
      {/* Tablet bezel + screen */}
      <div className="flex min-h-0 w-full flex-1 flex-col rounded-[1.5rem] border-[8px] border-[#2a2f36] bg-[#1e2228] shadow-[0_28px_55px_-15px_rgba(0,0,0,0.45),inset_0_1px_0_rgba(255,255,255,0.05)]">
        <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-[0.9rem] bg-[#cfd2d6] p-1.5">
          <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-lg bg-[#f4f4f5] shadow-[inset_0_2px_10px_rgba(0,0,0,0.07)]">
            <div className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden p-3 sm:p-4">
              <h4
                className="mb-2.5 px-0.5 text-[clamp(1rem,2.6vw,1.35rem)] font-bold leading-tight text-[#1C1B1F]"
                style={{ fontFamily: "system-ui, sans-serif" }}
              >
                Kitchen display
              </h4>
              <div className="grid w-full grid-cols-3 gap-2.5 sm:gap-3">
                {MOCK_PREVIEW_ORDERS.map((order) => {
                  const headerHex = headerColorHex(
                    order.orderType,
                    displaySettings.orderTypeColorsEnabled,
                    moduleColorKeys
                  );
                  const elapsedMs = order.elapsedMs;
                  const elapsedMin = Math.floor(elapsedMs / 60_000);
                  const urgencyBg = getUrgencyBg(elapsedMin);
                  const headerTime = order.headerTime;

                  return (
                    <div
                      key={order.id}
                      className="flex min-h-[160px] flex-col overflow-hidden rounded-2xl bg-white shadow-md sm:min-h-[180px]"
                    >
                      <div
                        className="flex h-[52px] shrink-0 items-center px-3"
                        style={{
                          backgroundColor: headerHex,
                          borderRadius: "20px 20px 0 0",
                        }}
                      >
                        <div className="grid w-full grid-cols-3 items-center gap-0.5 text-[clamp(11px,2vw,15px)] font-bold text-white">
                          <span className="truncate">
                            {formatOrderTypeLabel(order.orderType)}
                          </span>
                          <span className="truncate text-center">
                            {orderHeaderNumber(order)}
                          </span>
                          <span className="truncate text-right">
                            {headerTime}
                          </span>
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
                          <div className="flex items-center justify-between px-2.5 py-1.5 sm:px-3 sm:py-2">
                            <span className="text-[11px] font-semibold text-[#64748B] sm:text-[13px]">
                              Elapsed
                            </span>
                            <span
                              className="text-base font-bold tabular-nums sm:text-lg"
                              style={{
                                color: elapsedWarnColor(elapsedMs),
                              }}
                            >
                              {order.elapsedLabel}
                            </span>
                          </div>
                        )}
                        <div className="min-h-0 flex-1 space-y-1.5 overflow-y-auto px-3 pb-2 pt-1.5 sm:space-y-2 sm:px-4 sm:pt-2">
                          {order.items.map((it, idx) => (
                            <div key={idx}>
                              <p className="text-[13px] font-bold leading-snug text-[#212121] sm:text-[15px]">
                                {it.qty}× {it.name}
                              </p>
                              {it.mods?.map((mod, mi) => (
                                <p
                                  key={mi}
                                  className="mt-0.5 pl-1.5 text-[11px] text-[#555555] sm:mt-1 sm:pl-2 sm:text-[13px]"
                                >
                                  • {mod}
                                </p>
                              ))}
                            </div>
                          ))}
                        </div>
                        <div className="shrink-0 px-3 pb-2.5 pt-1 sm:px-4 sm:pb-3">
                          {order.status === "OPEN" && (
                            <button
                              type="button"
                              className="w-full rounded-xl bg-[#1565C0] py-2 text-center text-[14px] font-bold text-white sm:rounded-[14px] sm:py-3 sm:text-[17px]"
                              disabled
                            >
                              START
                            </button>
                          )}
                          {order.status === "PREPARING" && (
                            <button
                              type="button"
                              className="w-full rounded-xl bg-[#2E7D32] py-2 text-center text-[14px] font-bold text-white sm:rounded-[14px] sm:py-3 sm:text-[17px]"
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
        </div>
      </div>
    </div>
  );
}
