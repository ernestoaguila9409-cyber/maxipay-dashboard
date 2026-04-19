"use client";

import { Minus, Plus } from "lucide-react";
import { useMemo } from "react";

/** Dashboard UI cap for reservation timing steppers (minutes). */
export const RESERVATION_TIMING_STEPPER_MAX = 120;

const COL_BEFORE = "#FFF3CD";
const COL_RESERVATION = "#FF4D4F";
const COL_AFTER = "#E0E0E0";
const TEXT_MUTED = "#666666";
const TEXT_DARK = "#222222";
const BTN_BG = "#F5F5F5";

type StepperButtonProps = {
  direction: "dec" | "inc";
  disabled: boolean;
  onClick: () => void;
  label: string;
};

function StepperButton({ direction, disabled, onClick, label }: StepperButtonProps) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="inline-flex h-12 min-h-[48px] w-12 min-w-[48px] shrink-0 items-center justify-center rounded-xl transition-opacity disabled:cursor-not-allowed disabled:opacity-35"
      style={{ backgroundColor: BTN_BG, color: TEXT_DARK }}
    >
      {direction === "dec" ? <Minus size={20} strokeWidth={2.25} /> : <Plus size={20} strokeWidth={2.25} />}
    </button>
  );
}

type ReservationTimingBarProps = {
  beforeMinutes: number;
  afterMinutes: number;
};

function ReservationTimingBar({ beforeMinutes, afterMinutes }: ReservationTimingBarProps) {
  const { beforePct, midPct, afterPct } = useMemo(() => {
    const b = Math.max(0, beforeMinutes);
    const a = Math.max(0, afterMinutes);
    const midUnit = 1;
    const total = b + a + midUnit;
    if (total <= 0) {
      return { beforePct: 0, midPct: 100, afterPct: 0 };
    }
    return {
      beforePct: (b / total) * 100,
      midPct: (midUnit / total) * 100,
      afterPct: (a / total) * 100,
    };
  }, [beforeMinutes, afterMinutes]);

  return (
    <div
      className="flex h-[8px] w-full overflow-hidden rounded-full"
      style={{ boxShadow: "inset 0 0 0 1px rgba(0,0,0,0.06)" }}
    >
      <div
        className="h-full transition-[width] duration-300 ease-out"
        style={{ width: `${beforePct}%`, backgroundColor: COL_BEFORE }}
      />
      <div
        className="h-full transition-[width] duration-300 ease-out"
        style={{ width: `${midPct}%`, backgroundColor: COL_RESERVATION }}
      />
      <div
        className="h-full transition-[width] duration-300 ease-out"
        style={{ width: `${afterPct}%`, backgroundColor: COL_AFTER }}
      />
    </div>
  );
}

export type ReservationTimingCardProps = {
  beforeMinutes: number;
  afterMinutes: number;
  disabled?: boolean;
  onBeforeChange: (next: number) => void;
  onAfterChange: (next: number) => void;
  maxMinutes?: number;
};

export function ReservationTimingCard({
  beforeMinutes,
  afterMinutes,
  disabled = false,
  onBeforeChange,
  onAfterChange,
  maxMinutes = RESERVATION_TIMING_STEPPER_MAX,
}: ReservationTimingCardProps) {
  const clamp = (v: number) => Math.max(0, Math.min(Math.round(v), maxMinutes));

  const bumpBefore = (delta: number) => {
    if (disabled) return;
    onBeforeChange(clamp(beforeMinutes + delta));
  };

  const bumpAfter = (delta: number) => {
    if (disabled) return;
    onAfterChange(clamp(afterMinutes + delta));
  };

  const canDecBefore = !disabled && beforeMinutes > 0;
  const canIncBefore = !disabled && beforeMinutes < maxMinutes;
  const canDecAfter = !disabled && afterMinutes > 0;
  const canIncAfter = !disabled && afterMinutes < maxMinutes;

  return (
    <div className="mx-auto w-full max-w-xl rounded-2xl border border-slate-200/90 bg-white p-4 shadow-sm">
      <h3 className="mb-3 text-[15px] font-semibold text-slate-900">Reservation Timing</h3>

      <div className="mb-2 flex items-center justify-between gap-2 text-center text-xs font-medium" style={{ color: TEXT_MUTED }}>
        <span className="min-w-0 flex-1 truncate">
          {beforeMinutes} min before
        </span>
        <span className="shrink-0 px-1 text-slate-400">|</span>
        <span className="min-w-0 flex-1 truncate">Reservation</span>
        <span className="shrink-0 px-1 text-slate-400">|</span>
        <span className="min-w-0 flex-1 truncate">
          {afterMinutes} min after
        </span>
      </div>

      <div className="mb-4">
        <ReservationTimingBar beforeMinutes={beforeMinutes} afterMinutes={afterMinutes} />
      </div>

      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-2">
          <StepperButton
            direction="dec"
            disabled={!canDecBefore}
            onClick={() => bumpBefore(-1)}
            label="Decrease minutes before reservation"
          />
          <span className="min-w-[4.5rem] text-center text-sm font-medium tabular-nums" style={{ color: TEXT_DARK }}>
            {beforeMinutes} min
          </span>
          <StepperButton
            direction="inc"
            disabled={!canIncBefore}
            onClick={() => bumpBefore(1)}
            label="Increase minutes before reservation"
          />
        </div>

        <div className="flex items-center gap-2">
          <StepperButton
            direction="dec"
            disabled={!canDecAfter}
            onClick={() => bumpAfter(-1)}
            label="Decrease minutes after reservation"
          />
          <span className="min-w-[4.5rem] text-center text-sm font-medium tabular-nums" style={{ color: TEXT_DARK }}>
            {afterMinutes} min
          </span>
          <StepperButton
            direction="inc"
            disabled={!canIncAfter}
            onClick={() => bumpAfter(1)}
            label="Increase minutes after reservation"
          />
        </div>
      </div>
    </div>
  );
}
