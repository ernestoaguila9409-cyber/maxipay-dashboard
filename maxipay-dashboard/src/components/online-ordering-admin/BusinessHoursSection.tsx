"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { CalendarClock } from "lucide-react";
import type { OnlineOrderingDayHours, OnlineOrderingSettings } from "@/lib/onlineOrderingShared";
import { normalizeTimeHm, zonedWeekdayAndMinutes } from "@/lib/onlineOrderingShared";

/** Monday-first display order; values are `Date#getDay()` indices (0 = Sunday). */
const DAY_ORDER = [1, 2, 3, 4, 5, 6, 0] as const;

const LONG_DAY_NAMES = [
  "Sunday",
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
] as const;

const PRESET_TIMEZONES: { value: string; label: string }[] = [
  { value: "America/New_York", label: "US — Eastern" },
  { value: "America/Chicago", label: "US — Central" },
  { value: "America/Denver", label: "US — Mountain" },
  { value: "America/Phoenix", label: "US — Arizona" },
  { value: "America/Los_Angeles", label: "US — Pacific" },
  { value: "America/Anchorage", label: "US — Alaska" },
  { value: "Pacific/Honolulu", label: "US — Hawaii" },
  { value: "America/Toronto", label: "Canada — Eastern" },
  { value: "America/Vancouver", label: "Canada — Pacific" },
  { value: "Europe/London", label: "UK" },
  { value: "Europe/Paris", label: "Central Europe" },
  { value: "UTC", label: "UTC" },
  { value: "Asia/Dubai", label: "Gulf" },
  { value: "Asia/Tokyo", label: "Japan" },
  { value: "Australia/Sydney", label: "Australia — Sydney" },
];

const OTHER_VALUE = "__other__";

function isValidIanaTimeZone(tz: string): boolean {
  const t = tz.trim();
  if (!t) return false;
  return zonedWeekdayAndMinutes(new Date(), t) !== null;
}

interface BusinessHoursSectionProps {
  settings: OnlineOrderingSettings;
  disabled: boolean;
  onPersist: (patch: Partial<OnlineOrderingSettings>) => void;
}

export default function BusinessHoursSection({
  settings,
  disabled,
  onPersist,
}: BusinessHoursSectionProps) {
  const tz = settings.businessHoursTimezone.trim();
  const presetValues = useMemo(() => new Set(PRESET_TIMEZONES.map((p) => p.value)), []);
  const [customTzDraft, setCustomTzDraft] = useState("");
  const selectValue = presetValues.has(tz) ? tz : OTHER_VALUE;

  useEffect(() => {
    if (!presetValues.has(settings.businessHoursTimezone.trim())) {
      setCustomTzDraft(settings.businessHoursTimezone.trim());
    }
  }, [settings.businessHoursTimezone, presetValues]);

  const applyWeekly = useCallback(
    (next: OnlineOrderingDayHours[]) => {
      onPersist({ businessHoursWeekly: next });
    },
    [onPersist]
  );

  const setDay = useCallback(
    (dayIndex: number, patch: Partial<OnlineOrderingDayHours>) => {
      const next = settings.businessHoursWeekly.map((row, i) =>
        i === dayIndex ? { ...row, ...patch } : row
      );
      applyWeekly(next);
    },
    [settings.businessHoursWeekly, applyWeekly]
  );

  const onTimeBlur = useCallback(
    (dayIndex: number, field: "openTime" | "closeTime", raw: string) => {
      const norm = normalizeTimeHm(raw);
      if (!norm) return;
      setDay(dayIndex, { [field]: norm });
    },
    [setDay]
  );

  const onTimezoneSelect = useCallback(
    (value: string) => {
      if (value === OTHER_VALUE) {
        setCustomTzDraft(
          settings.businessHoursTimezone.trim() || "America/New_York"
        );
        return;
      }
      onPersist({ businessHoursTimezone: value });
      setCustomTzDraft("");
    },
    [onPersist, settings.businessHoursTimezone]
  );

  const commitCustomTimezone = useCallback(() => {
    const t = customTzDraft.trim();
    if (!t) return;
    if (!isValidIanaTimeZone(t)) return;
    onPersist({ businessHoursTimezone: t });
  }, [customTzDraft, onPersist]);

  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-5">
      <div className="flex items-center gap-2 mb-3">
        <CalendarClock size={16} className="text-slate-400" />
        <h3 className="text-sm font-semibold text-slate-800">Business hours</h3>
      </div>
      <p className="text-xs text-slate-500 mb-4">
        Set a weekly schedule in your chosen time zone. When{" "}
        <span className="font-medium text-slate-700">Enforce business hours</span> is on and{" "}
        <span className="font-medium text-slate-700">Auto (follow toggle)</span> is selected above, the
        public ordering site shows <span className="font-medium text-slate-700">Closed</span> outside
        these windows and checkout is blocked. Use <span className="font-medium text-slate-700">Force open</span>{" "}
        to bypass the schedule temporarily, or <span className="font-medium text-slate-700">Force closed</span>{" "}
        for a one-off closure.
      </p>

      <label className="flex items-start gap-2 mb-4 cursor-pointer">
        <input
          type="checkbox"
          checked={settings.businessHoursEnforced}
          disabled={disabled}
          onChange={(e) => onPersist({ businessHoursEnforced: e.target.checked })}
          className="mt-0.5 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
        />
        <span className="text-sm text-slate-700">
          Enforce business hours (only applies when override is Auto and online ordering is enabled)
        </span>
      </label>

      <div className="space-y-3 mb-5">
        <p className="text-xs font-medium text-slate-600">Time zone</p>
        <select
          value={selectValue}
          disabled={disabled}
          onChange={(e) => onTimezoneSelect(e.target.value)}
          className="w-full max-w-md rounded-lg border border-slate-200 px-3 py-2 text-sm bg-white outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20"
        >
          {PRESET_TIMEZONES.map((p) => (
            <option key={p.value} value={p.value}>
              {p.label} ({p.value})
            </option>
          ))}
          <option value={OTHER_VALUE}>Other (IANA)…</option>
        </select>
        {selectValue === OTHER_VALUE && (
          <div className="flex flex-col sm:flex-row gap-2 max-w-md">
            <input
              type="text"
              placeholder="e.g. Europe/Berlin"
              value={customTzDraft}
              disabled={disabled}
              onChange={(e) => setCustomTzDraft(e.target.value)}
              onBlur={() => commitCustomTimezone()}
              className="flex-1 rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20"
            />
            <button
              type="button"
              disabled={disabled}
              onClick={() => commitCustomTimezone()}
              className="shrink-0 px-3 py-2 rounded-lg border border-slate-200 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
            >
              Apply zone
            </button>
          </div>
        )}
      </div>

      <div className="overflow-x-auto rounded-xl border border-slate-100">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-slate-50 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">
              <th className="px-3 py-2">Day</th>
              <th className="px-3 py-2 w-24">Open</th>
              <th className="px-3 py-2">From</th>
              <th className="px-3 py-2">Until</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {DAY_ORDER.map((dayIndex) => {
              const row = settings.businessHoursWeekly[dayIndex]!;
              const label = LONG_DAY_NAMES[dayIndex];
              return (
                <tr key={dayIndex} className="bg-white">
                  <td className="px-3 py-2 font-medium text-slate-800 whitespace-nowrap">{label}</td>
                  <td className="px-3 py-2">
                    <input
                      type="checkbox"
                      checked={row.openForDay}
                      disabled={disabled}
                      onChange={(e) => setDay(dayIndex, { openForDay: e.target.checked })}
                      className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                      aria-label={`${label} accepting orders`}
                    />
                  </td>
                  <td className="px-3 py-2">
                    <input
                      key={`${dayIndex}-open-${row.openTime}-${row.openForDay}`}
                      type="time"
                      step={60}
                      defaultValue={row.openTime}
                      disabled={disabled || !row.openForDay}
                      onBlur={(e) => onTimeBlur(dayIndex, "openTime", e.target.value)}
                      className="rounded-lg border border-slate-200 px-2 py-1.5 text-sm tabular-nums outline-none focus:border-blue-400 disabled:opacity-50"
                    />
                  </td>
                  <td className="px-3 py-2">
                    <input
                      key={`${dayIndex}-close-${row.closeTime}-${row.openForDay}`}
                      type="time"
                      step={60}
                      defaultValue={row.closeTime}
                      disabled={disabled || !row.openForDay}
                      onBlur={(e) => onTimeBlur(dayIndex, "closeTime", e.target.value)}
                      className="rounded-lg border border-slate-200 px-2 py-1.5 text-sm tabular-nums outline-none focus:border-blue-400 disabled:opacity-50"
                    />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-slate-400 mt-3">
        If &ldquo;Until&rdquo; is earlier than &ldquo;From&rdquo;, hours span midnight (e.g. 22:00–02:00).
      </p>
    </section>
  );
}
