"use client";

export interface KdsPreviewDisplaySettings {
  orderTypeColorsEnabled: boolean;
  gridColumns?: 2 | 3;
  showTimers: boolean;
  /** Minutes elapsed before ticket body turns yellow (default 5). */
  ticketYellowAfterMinutes?: number;
  /** Minutes elapsed before ticket body turns red (default 10; never before yellow). */
  ticketRedAfterMinutes?: number;
}

const DEFAULT_YELLOW_AFTER = 5;
const DEFAULT_RED_AFTER = 10;
const MAX_URGENCY_MINUTES = 24 * 60;

function normalizedTicketUrgencyMinutes(
  yellow: number,
  red: number
): { yellow: number; red: number } {
  let y = Math.max(0, Math.min(MAX_URGENCY_MINUTES, Math.round(yellow)));
  let r = Math.max(0, Math.min(MAX_URGENCY_MINUTES, Math.round(red)));
  if (r < y) r = y;
  return { yellow: y, red: r };
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

function getUrgencyBg(
  minutes: number,
  yellowAfter: number,
  redAfter: number
): string {
  if (minutes < 0) return "#FFFFFF";
  if (minutes >= redAfter) return "#FFCDD2";
  if (minutes >= yellowAfter) return "#FFF9C4";
  return "#FFFFFF";
}

/**
 * Pick elapsed minutes so the three preview cards always illustrate white → yellow → red
 * for the current yellow/red thresholds (unlike fixed mock times, which can all land in red).
 */
function demoElapsedMinutesForCard(
  cardIndex: number,
  yellowAfter: number,
  redAfter: number
): number {
  const y = yellowAfter;
  const r = redAfter;
  if (cardIndex === 0) {
    return y > 0 ? y - 1 : -1;
  }
  if (cardIndex === 1) {
    if (r > y) {
      return y + Math.max(0, Math.floor((r - y) / 2));
    }
    return y;
  }
  return r + Math.max(2, Math.floor((r - y) / 2) + 1);
}

function formatElapsedLabel(totalMinutes: number): string {
  const m = Math.max(0, totalMinutes);
  const h = Math.floor(m / 60);
  const min = m % 60;
  if (h <= 0) return `${min}:00`;
  return `${h}:${min.toString().padStart(2, "0")}`;
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
};

const MOCK_PREVIEW_ORDERS: MockOrder[] = [
  {
    id: "pv1",
    orderType: "DINE_IN",
    orderNumber: 142,
    status: "OPEN",
    headerTime: "11:26 AM",
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
    items: [{ qty: 1, name: "Chicken wrap", mods: ["Add avocado"] }],
  },
  {
    id: "pv3",
    orderType: "BAR",
    orderNumber: 0,
    tableName: "Bar 7",
    status: "OPEN",
    headerTime: "11:14 AM",
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
  const { yellow: yellowAfter, red: redAfter } = normalizedTicketUrgencyMinutes(
    displaySettings.ticketYellowAfterMinutes ?? DEFAULT_YELLOW_AFTER,
    displaySettings.ticketRedAfterMinutes ?? DEFAULT_RED_AFTER
  );

  return (
    <div className="flex h-full min-h-0 w-full flex-col">
      {/* Tablet bezel + screen */}
      <div className="flex min-h-0 w-full flex-1 flex-col rounded-[1.5rem] border-[8px] border-[#2a2f36] bg-[#1e2228] shadow-[0_28px_55px_-15px_rgba(0,0,0,0.45),inset_0_1px_0_rgba(255,255,255,0.05)]">
        <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-[0.9rem] bg-[#cfd2d6] p-1.5">
          <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-lg bg-[#f4f4f5] shadow-[inset_0_2px_10px_rgba(0,0,0,0.07)]">
            <div className="flex min-h-0 flex-1 flex-col overflow-y-auto overflow-x-hidden p-3 sm:p-4">
              <h4
                className="mb-2.5 shrink-0 px-0.5 text-[clamp(1rem,2.6vw,1.35rem)] font-bold leading-tight text-[#1C1B1F]"
                style={{ fontFamily: "system-ui, sans-serif" }}
              >
                Kitchen display
              </h4>
              <div className="flex min-h-[220px] flex-1 gap-2.5 sm:min-h-[280px] sm:gap-3">
                {MOCK_PREVIEW_ORDERS.map((order, cardIndex) => {
                  const headerHex = headerColorHex(
                    order.orderType,
                    displaySettings.orderTypeColorsEnabled,
                    moduleColorKeys
                  );
                  const elapsedMin = demoElapsedMinutesForCard(
                    cardIndex,
                    yellowAfter,
                    redAfter
                  );
                  let urgencyBg = getUrgencyBg(
                    elapsedMin,
                    yellowAfter,
                    redAfter
                  );
                  if (redAfter === yellowAfter && cardIndex === 1) {
                    urgencyBg = "#FFF9C4";
                  }
                  if (redAfter === yellowAfter && cardIndex === 2) {
                    urgencyBg = "#FFCDD2";
                  }
                  const elapsedMs = Math.max(0, elapsedMin) * 60_000;
                  const elapsedLabel = formatElapsedLabel(elapsedMin);
                  const headerTime = order.headerTime;

                  return (
                    <div
                      key={order.id}
                      className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden rounded-2xl bg-white shadow-md"
                    >
                      <div
                        className="flex h-[56px] shrink-0 items-center px-3 sm:h-[60px]"
                        style={{
                          backgroundColor: headerHex,
                          borderRadius: "20px 20px 0 0",
                        }}
                      >
                        <div className="grid w-full grid-cols-3 items-center gap-0.5 text-[clamp(12px,2.1vw,16px)] font-bold text-white">
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
                          <div className="flex shrink-0 items-center justify-between px-3 py-2 sm:px-4 sm:py-2.5">
                            <span className="text-[12px] font-semibold text-[#64748B] sm:text-[14px]">
                              Elapsed
                            </span>
                            <span
                              className="text-lg font-bold tabular-nums sm:text-xl"
                              style={{
                                color: elapsedWarnColor(elapsedMs),
                              }}
                            >
                              {elapsedLabel}
                            </span>
                          </div>
                        )}
                        <div className="min-h-0 flex-1 space-y-2 overflow-y-auto px-3 pb-2 pt-2 sm:space-y-2.5 sm:px-4 sm:pb-3 sm:pt-2.5">
                          {order.items.map((it, idx) => (
                            <div key={idx}>
                              <p className="text-[14px] font-bold leading-snug text-[#212121] sm:text-[16px]">
                                {it.qty}× {it.name}
                              </p>
                              {it.mods?.map((mod, mi) => (
                                <p
                                  key={mi}
                                  className="mt-1 pl-2 text-[12px] text-[#555555] sm:mt-1.5 sm:pl-2.5 sm:text-[14px]"
                                >
                                  • {mod}
                                </p>
                              ))}
                            </div>
                          ))}
                        </div>
                        <div className="mt-auto shrink-0 px-3 pb-3 pt-1 sm:px-4 sm:pb-3.5 sm:pt-1.5">
                          {order.status === "OPEN" && (
                            <button
                              type="button"
                              className="w-full rounded-xl bg-[#1565C0] py-2.5 text-center text-[15px] font-bold text-white sm:rounded-[14px] sm:py-3.5 sm:text-[18px]"
                              disabled
                            >
                              START
                            </button>
                          )}
                          {order.status === "PREPARING" && (
                            <button
                              type="button"
                              className="w-full rounded-xl bg-[#2E7D32] py-2.5 text-center text-[15px] font-bold text-white sm:rounded-[14px] sm:py-3.5 sm:text-[18px]"
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
