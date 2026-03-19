"use client";

import { useEffect, useState, useMemo } from "react";
import {
  collection,
  doc,
  addDoc,
  updateDoc,
  deleteDoc,
  onSnapshot,
  writeBatch,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import Header from "@/components/Header";
import {
  Plus,
  Pencil,
  Trash2,
  X,
  AlertTriangle,
  Clock,
  CalendarDays,
  Eye,
  ChevronLeft,
  Search,
  ToggleLeft,
  ToggleRight,
} from "lucide-react";

// ── Types ──

interface Menu {
  id: string;
  name: string;
  isActive: boolean;
}

interface Schedule {
  id: string;
  name: string;
  days: string[];
  startTime: string;
  endTime: string;
}

interface MenuItem {
  id: string;
  name: string;
  price: number;
  prices: Record<string, number>;
  categoryId: string;
  menuId: string;
  isScheduled: boolean;
  scheduleIds: string[];
}

interface Category {
  id: string;
  name: string;
  scheduleIds: string[];
}

const DAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"] as const;
const DAY_LABELS: Record<string, string> = {
  MON: "Mon",
  TUE: "Tue",
  WED: "Wed",
  THU: "Thu",
  FRI: "Fri",
  SAT: "Sat",
  SUN: "Sun",
};

type Screen = "main" | "scheduleDetail";

export default function MenusPage() {
  const { user } = useAuth();

  // ── Data ──
  const [menus, setMenus] = useState<Menu[]>([]);
  const [schedules, setSchedules] = useState<Schedule[]>([]);
  const [items, setItems] = useState<MenuItem[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);

  // ── Navigation ──
  const [screen, setScreen] = useState<Screen>("main");
  const [activeScheduleId, setActiveScheduleId] = useState<string | null>(null);

  // ── Create / Edit menu modal ──
  const [menuModalOpen, setMenuModalOpen] = useState(false);
  const [menuModalEdit, setMenuModalEdit] = useState<Menu | null>(null);
  const [menuName, setMenuName] = useState("");
  const [menuIsActive, setMenuIsActive] = useState(true);
  const [menuSaving, setMenuSaving] = useState(false);

  // ── Delete menu ──
  const [deleteMenuTarget, setDeleteMenuTarget] = useState<Menu | null>(null);
  const [deletingMenu, setDeletingMenu] = useState(false);

  // ── Create / Edit schedule modal ──
  const [scheduleModalOpen, setScheduleModalOpen] = useState(false);
  const [scheduleEdit, setScheduleEdit] = useState<Schedule | null>(null);
  const [scheduleName, setScheduleName] = useState("");
  const [scheduleDays, setScheduleDays] = useState<Record<string, boolean>>({});
  const [scheduleStart, setScheduleStart] = useState("08:00");
  const [scheduleEnd, setScheduleEnd] = useState("16:00");
  const [scheduleSaving, setScheduleSaving] = useState(false);
  const [scheduleError, setScheduleError] = useState("");

  // ── Delete schedule ──
  const [deleteScheduleTarget, setDeleteScheduleTarget] = useState<Schedule | null>(null);
  const [deletingSchedule, setDeletingSchedule] = useState(false);

  // ── Schedule detail ──
  const [itemSearch, setItemSearch] = useState("");
  const [assignSaving, setAssignSaving] = useState<string | null>(null);

  // ── Preview mode ──
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewDay, setPreviewDay] = useState<string>(
    DAYS[new Date().getDay() === 0 ? 6 : new Date().getDay() - 1]
  );
  const [previewTime, setPreviewTime] = useState(
    `${String(new Date().getHours()).padStart(2, "0")}:${String(new Date().getMinutes()).padStart(2, "0")}`
  );

  // ── Firestore listeners ──
  useEffect(() => {
    if (!user) return;

    let readyCount = 0;
    const checkReady = () => {
      readyCount++;
      if (readyCount >= 4) setLoading(false);
    };

    const unsubMenus = onSnapshot(collection(db, "menus"), (snap) => {
      const list: Menu[] = [];
      snap.forEach((d) => {
        const data = d.data();
        list.push({
          id: d.id,
          name: data.name ?? "",
          isActive: data.isActive ?? true,
        });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setMenus(list);
      checkReady();
    });

    const unsubSchedules = onSnapshot(collection(db, "menuSchedules"), (snap) => {
      const list: Schedule[] = [];
      snap.forEach((d) => {
        const data = d.data();
        list.push({
          id: d.id,
          name: data.name ?? "Untitled Schedule",
          days: Array.isArray(data.days) ? data.days : [],
          startTime: data.startTime ?? "",
          endTime: data.endTime ?? "",
        });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setSchedules(list);
      checkReady();
    });

    const unsubItems = onSnapshot(collection(db, "MenuItems"), (snap) => {
      const list: MenuItem[] = [];
      snap.forEach((d) => {
        const data = d.data();
        const rawPrices = (typeof data.prices === "object" && data.prices !== null)
          ? Object.fromEntries(
              Object.entries(data.prices as Record<string, unknown>).map(
                ([k, v]) => [k, typeof v === "number" ? v : 0]
              )
            )
          : {};
        const legacyPrice: number = data.price ?? 0;
        const prices = Object.keys(rawPrices).length > 0 ? rawPrices : { default: legacyPrice };
        const displayPrice = Object.values(prices)[0] ?? 0;
        list.push({
          id: d.id,
          name: data.name ?? "Unnamed",
          price: displayPrice,
          prices,
          categoryId: data.categoryId ?? "",
          menuId: data.menuId ?? "",
          isScheduled: data.isScheduled ?? false,
          scheduleIds: Array.isArray(data.scheduleIds) ? data.scheduleIds : [],
        });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setItems(list);
      checkReady();
    });

    const unsubCats = onSnapshot(collection(db, "Categories"), (snap) => {
      const list: Category[] = [];
      snap.forEach((d) => {
        const data = d.data();
        if (data.name) list.push({ id: d.id, name: data.name, scheduleIds: Array.isArray(data.scheduleIds) ? data.scheduleIds : [] });
      });
      list.sort((a, b) => a.name.localeCompare(b.name));
      setCategories(list);
      checkReady();
    });

    return () => {
      unsubMenus();
      unsubSchedules();
      unsubItems();
      unsubCats();
    };
  }, [user]);

  // ── Derived ──
  const catMap = useMemo(() => new Map(categories.map((c) => [c.id, c.name])), [categories]);
  const menuMap = useMemo(() => new Map(menus.map((m) => [m.id, m.name])), [menus]);
  const activeSchedule = schedules.find((s) => s.id === activeScheduleId) ?? null;

  const menuItemCounts = useMemo(() => {
    const map = new Map<string, number>();
    for (const item of items) {
      if (item.menuId) {
        map.set(item.menuId, (map.get(item.menuId) ?? 0) + 1);
      }
    }
    return map;
  }, [items]);

  const scheduleItemCounts = useMemo(() => {
    const map = new Map<string, number>();
    for (const item of items) {
      if (item.isScheduled) {
        for (const sid of item.scheduleIds) {
          map.set(sid, (map.get(sid) ?? 0) + 1);
        }
      }
    }
    return map;
  }, [items]);

  // ── Preview logic (additive model) ──
  const previewItems = useMemo(() => {
    const activeSchedIds = new Set<string>();
    for (const sched of schedules) {
      if (
        sched.days.includes(previewDay) &&
        previewTime >= sched.startTime &&
        previewTime <= sched.endTime
      ) {
        activeSchedIds.add(sched.id);
      }
    }

    const seen = new Set<string>();
    const result: MenuItem[] = [];
    for (const item of items) {
      if (seen.has(item.id)) continue;
      if (!item.isScheduled) {
        seen.add(item.id);
        result.push(item);
      } else if (item.scheduleIds.some((sid) => activeSchedIds.has(sid))) {
        seen.add(item.id);
        result.push(item);
      }
    }
    return result;
  }, [schedules, items, previewDay, previewTime]);

  // ── Menu CRUD ──
  const openCreateMenu = () => {
    setMenuModalEdit(null);
    setMenuName("");
    setMenuIsActive(true);
    setMenuModalOpen(true);
  };

  const openEditMenu = (menu: Menu) => {
    setMenuModalEdit(menu);
    setMenuName(menu.name);
    setMenuIsActive(menu.isActive);
    setMenuModalOpen(true);
  };

  const handleSaveMenu = async () => {
    const name = menuName.trim();
    if (!name) return;
    setMenuSaving(true);
    try {
      if (menuModalEdit) {
        await updateDoc(doc(db, "menus", menuModalEdit.id), {
          name,
          isActive: menuIsActive,
        });
      } else {
        await addDoc(collection(db, "menus"), {
          name,
          isActive: menuIsActive,
        });
      }
      setMenuModalOpen(false);
    } catch (err) {
      console.error("Failed to save menu:", err);
    } finally {
      setMenuSaving(false);
    }
  };

  const handleDeleteMenu = async () => {
    if (!deleteMenuTarget) return;
    setDeletingMenu(true);
    try {
      const batch = writeBatch(db);
      batch.delete(doc(db, "menus", deleteMenuTarget.id));
      for (const item of items) {
        if (item.menuId === deleteMenuTarget.id) {
          batch.update(doc(db, "MenuItems", item.id), { menuId: "" });
        }
      }
      await batch.commit();
    } catch (err) {
      console.error("Failed to delete menu:", err);
    } finally {
      setDeletingMenu(false);
      setDeleteMenuTarget(null);
    }
  };

  // ── Schedule CRUD ──
  const openCreateSchedule = () => {
    setScheduleEdit(null);
    setScheduleName("");
    setScheduleDays(Object.fromEntries(DAYS.map((d) => [d, false])));
    setScheduleStart("08:00");
    setScheduleEnd("16:00");
    setScheduleError("");
    setScheduleModalOpen(true);
  };

  const openEditSchedule = (sched: Schedule) => {
    setScheduleEdit(sched);
    setScheduleName(sched.name);
    setScheduleDays(Object.fromEntries(DAYS.map((d) => [d, sched.days.includes(d)])));
    setScheduleStart(sched.startTime);
    setScheduleEnd(sched.endTime);
    setScheduleError("");
    setScheduleModalOpen(true);
  };

  const handleSaveSchedule = async () => {
    const name = scheduleName.trim();
    if (!name) {
      setScheduleError("Enter a schedule name.");
      return;
    }
    const selectedDays = DAYS.filter((d) => scheduleDays[d]);
    if (selectedDays.length === 0) {
      setScheduleError("Select at least one day.");
      return;
    }
    if (scheduleStart >= scheduleEnd) {
      setScheduleError("Start time must be before end time.");
      return;
    }
    setScheduleError("");
    setScheduleSaving(true);
    try {
      const data = {
        name,
        days: selectedDays,
        startTime: scheduleStart,
        endTime: scheduleEnd,
      };
      if (scheduleEdit) {
        await updateDoc(doc(db, "menuSchedules", scheduleEdit.id), data);
      } else {
        await addDoc(collection(db, "menuSchedules"), data);
      }
      setScheduleModalOpen(false);
    } catch (err) {
      console.error("Failed to save schedule:", err);
    } finally {
      setScheduleSaving(false);
    }
  };

  const handleDeleteSchedule = async () => {
    if (!deleteScheduleTarget) return;
    setDeletingSchedule(true);
    try {
      const batch = writeBatch(db);
      batch.delete(doc(db, "menuSchedules", deleteScheduleTarget.id));
      for (const item of items) {
        if (item.scheduleIds.includes(deleteScheduleTarget.id)) {
          const newIds = item.scheduleIds.filter((id) => id !== deleteScheduleTarget.id);
          const update: Record<string, unknown> = { scheduleIds: newIds };
          if (newIds.length === 0) update.isScheduled = false;
          batch.update(doc(db, "MenuItems", item.id), update);
        }
      }
      for (const cat of categories) {
        if (cat.scheduleIds.includes(deleteScheduleTarget.id)) {
          const newIds = cat.scheduleIds.filter((id) => id !== deleteScheduleTarget.id);
          batch.update(doc(db, "Categories", cat.id), { scheduleIds: newIds });
        }
      }
      await batch.commit();
      if (activeScheduleId === deleteScheduleTarget.id) {
        setScreen("main");
        setActiveScheduleId(null);
      }
    } catch (err) {
      console.error("Failed to delete schedule:", err);
    } finally {
      setDeletingSchedule(false);
      setDeleteScheduleTarget(null);
    }
  };

  // ── Toggle item schedule assignment ──
  const toggleItemSchedule = async (item: MenuItem) => {
    if (!activeScheduleId) return;
    setAssignSaving(item.id);
    try {
      const has = item.scheduleIds.includes(activeScheduleId);
      if (has) {
        const newIds = item.scheduleIds.filter((id) => id !== activeScheduleId);
        const update: Record<string, unknown> = { scheduleIds: newIds };
        if (newIds.length === 0) update.isScheduled = false;
        await updateDoc(doc(db, "MenuItems", item.id), update);
      } else {
        await updateDoc(doc(db, "MenuItems", item.id), {
          isScheduled: true,
          scheduleIds: [...item.scheduleIds, activeScheduleId],
        });
      }
    } catch (err) {
      console.error("Failed to toggle item schedule:", err);
    } finally {
      setAssignSaving(null);
    }
  };

  const openScheduleDetail = (sched: Schedule) => {
    setActiveScheduleId(sched.id);
    setScreen("scheduleDetail");
    setItemSearch("");
  };

  // ── Filtered items for schedule detail ──
  const filteredItems = items.filter(
    (i) => !itemSearch || i.name.toLowerCase().includes(itemSearch.toLowerCase())
  );
  const assignedItems = filteredItems.filter(
    (i) => activeScheduleId && i.scheduleIds.includes(activeScheduleId)
  );
  const unassignedItems = filteredItems.filter(
    (i) => activeScheduleId && !i.scheduleIds.includes(activeScheduleId)
  );

  // ── Render ──

  if (loading) {
    return (
      <>
        <Header title="Menus & Schedules" />
        <div className="flex items-center justify-center py-20">
          <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
        </div>
      </>
    );
  }

  return (
    <>
      <Header title="Menus & Schedules" />
      <div className="px-4 pt-3 pb-6 space-y-4">
        {screen === "main" ? (
          <>
            {/* ── Top toolbar ── */}
            <div className="flex items-center justify-end gap-2">
              <button
                onClick={() => setPreviewOpen(true)}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors"
              >
                <Eye size={14} />
                <span className="hidden sm:inline">Preview</span>
              </button>
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              {/* ════════════════════════
                 MENUS PANEL
                 ════════════════════════ */}
              <div>
                <div className="flex items-center justify-between mb-3">
                  <div className="flex items-center gap-2">
                    <CalendarDays size={18} className="text-slate-500" />
                    <h2 className="text-sm font-bold text-slate-700 uppercase tracking-wider">
                      Menus
                    </h2>
                    <span className="text-xs text-slate-400">{menus.length}</span>
                  </div>
                  <button
                    onClick={openCreateMenu}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-medium hover:bg-blue-700 transition-colors"
                  >
                    <Plus size={14} />
                    Create Menu
                  </button>
                </div>

                {menus.length === 0 ? (
                  <div className="bg-white rounded-xl border border-slate-100 p-8 text-center">
                    <p className="text-slate-400 text-sm">No menus yet</p>
                    <p className="text-slate-300 text-xs mt-1">
                      Create menus like Breakfast, Lunch, or Dinner to organize items.
                    </p>
                  </div>
                ) : (
                  <div className="space-y-2">
                    {menus.map((menu) => {
                      const count = menuItemCounts.get(menu.id) ?? 0;
                      return (
                        <div
                          key={menu.id}
                          className="group bg-white rounded-xl border border-slate-200 p-4 hover:shadow-sm hover:border-slate-300 transition-all"
                        >
                          <div className="flex items-center justify-between">
                            <div className="flex items-center gap-3 min-w-0">
                              <h3 className="text-sm font-semibold text-slate-800 truncate">
                                {menu.name}
                              </h3>
                              <span
                                className={`inline-flex items-center gap-1 text-[10px] px-2 py-0.5 rounded-full font-semibold ${
                                  menu.isActive
                                    ? "bg-emerald-50 text-emerald-600"
                                    : "bg-slate-100 text-slate-400"
                                }`}
                              >
                                <span
                                  className={`w-1.5 h-1.5 rounded-full ${
                                    menu.isActive ? "bg-emerald-500" : "bg-slate-300"
                                  }`}
                                />
                                {menu.isActive ? "Active" : "Inactive"}
                              </span>
                              <span className="text-xs text-slate-400">
                                {count} item{count !== 1 ? "s" : ""}
                              </span>
                            </div>
                            <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
                              <button
                                onClick={() => openEditMenu(menu)}
                                className="p-1.5 rounded-lg text-slate-400 hover:text-blue-600 hover:bg-blue-50 transition-colors"
                              >
                                <Pencil size={13} />
                              </button>
                              <button
                                onClick={() => setDeleteMenuTarget(menu)}
                                className="p-1.5 rounded-lg text-slate-400 hover:text-red-500 hover:bg-red-50 transition-colors"
                              >
                                <Trash2 size={13} />
                              </button>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>

              {/* ════════════════════════
                 SCHEDULES PANEL
                 ════════════════════════ */}
              <div>
                <div className="flex items-center justify-between mb-3">
                  <div className="flex items-center gap-2">
                    <Clock size={18} className="text-slate-500" />
                    <h2 className="text-sm font-bold text-slate-700 uppercase tracking-wider">
                      Schedules
                    </h2>
                    <span className="text-xs text-slate-400">{schedules.length}</span>
                  </div>
                  <button
                    onClick={openCreateSchedule}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-medium hover:bg-blue-700 transition-colors"
                  >
                    <Plus size={14} />
                    Create Schedule
                  </button>
                </div>

                {schedules.length === 0 ? (
                  <div className="bg-white rounded-xl border border-slate-100 p-8 text-center">
                    <p className="text-slate-400 text-sm">No schedules yet</p>
                    <p className="text-slate-300 text-xs mt-1">
                      Schedules define time windows when extra items appear.
                    </p>
                  </div>
                ) : (
                  <div className="space-y-2">
                    {schedules.map((sched) => {
                      const count = scheduleItemCounts.get(sched.id) ?? 0;
                      return (
                        <div
                          key={sched.id}
                          className="group bg-white rounded-xl border border-slate-200 p-4 hover:shadow-sm hover:border-slate-300 transition-all cursor-pointer"
                          onClick={() => openScheduleDetail(sched)}
                        >
                          <div className="flex items-start justify-between">
                            <div className="min-w-0">
                              <h3 className="text-sm font-semibold text-slate-800 truncate">
                                {sched.name}
                              </h3>
                              <div className="flex items-center gap-1.5 flex-wrap mt-1.5">
                                {sched.days.map((d) => (
                                  <span
                                    key={d}
                                    className="text-[10px] px-1.5 py-0.5 rounded bg-blue-100 text-blue-600 font-semibold"
                                  >
                                    {DAY_LABELS[d]}
                                  </span>
                                ))}
                              </div>
                              <div className="flex items-center gap-3 mt-1.5 text-xs text-slate-400">
                                <span className="font-medium text-slate-500">
                                  {sched.startTime} – {sched.endTime}
                                </span>
                                <span>·</span>
                                <span className="font-medium text-slate-500">
                                  {count} scheduled item{count !== 1 ? "s" : ""}
                                </span>
                              </div>
                            </div>
                            <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  openEditSchedule(sched);
                                }}
                                className="p-1.5 rounded-lg text-slate-400 hover:text-blue-600 hover:bg-blue-50 transition-colors"
                              >
                                <Pencil size={13} />
                              </button>
                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setDeleteScheduleTarget(sched);
                                }}
                                className="p-1.5 rounded-lg text-slate-400 hover:text-red-500 hover:bg-red-50 transition-colors"
                              >
                                <Trash2 size={13} />
                              </button>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </div>
          </>
        ) : (
          /* ════════════════════════════════════════════
             SCREEN: SCHEDULE DETAIL — Assign Items
             ════════════════════════════════════════════ */
          activeSchedule && (
            <>
              <div className="flex items-center gap-3 mb-1">
                <button
                  onClick={() => {
                    setScreen("main");
                    setActiveScheduleId(null);
                  }}
                  className="flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700 transition-colors"
                >
                  <ChevronLeft size={16} />
                  Back
                </button>
              </div>

              <div className="flex items-center justify-between flex-wrap gap-3">
                <div>
                  <h2 className="text-lg font-bold text-slate-800">
                    {activeSchedule.name}
                  </h2>
                  <div className="flex items-center gap-2 mt-1">
                    <div className="flex items-center gap-1 flex-wrap">
                      {activeSchedule.days.map((d) => (
                        <span
                          key={d}
                          className="text-[10px] px-1.5 py-0.5 rounded bg-blue-100 text-blue-600 font-semibold"
                        >
                          {DAY_LABELS[d]}
                        </span>
                      ))}
                    </div>
                    <span className="text-xs text-slate-400">
                      {activeSchedule.startTime} – {activeSchedule.endTime}
                    </span>
                  </div>
                </div>
                <button
                  onClick={() => openEditSchedule(activeSchedule)}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-xs font-medium text-slate-600 hover:bg-slate-50 transition-colors"
                >
                  <Pencil size={13} />
                  Edit Schedule
                </button>
              </div>

              <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
                <div className="flex items-center justify-between px-4 py-3 border-b border-slate-100">
                  <div className="flex items-center gap-2">
                    <Clock size={16} className="text-slate-400" />
                    <h3 className="text-sm font-semibold text-slate-700">
                      Assign Items to Schedule ({assignedItems.length} assigned)
                    </h3>
                  </div>
                </div>

                <div className="px-4 py-2 border-b border-slate-50">
                  <p className="text-xs text-slate-400 mb-2">
                    Toggle items to make them visible only during this schedule&apos;s time window.
                    Items not toggled remain available all day (base items).
                  </p>
                  <div className="relative">
                    <Search
                      size={14}
                      className="absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400"
                    />
                    <input
                      type="text"
                      placeholder="Search items..."
                      value={itemSearch}
                      onChange={(e) => setItemSearch(e.target.value)}
                      className="w-full pl-8 pr-3 py-1.5 rounded-lg bg-slate-50 border border-slate-200 text-xs text-slate-700 focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-400/20"
                    />
                  </div>
                </div>

                <div className="max-h-[500px] overflow-y-auto divide-y divide-slate-50">
                  {assignedItems.length > 0 && (
                    <>
                      <div className="px-4 py-1.5 bg-blue-50/50 sticky top-0 z-10">
                        <p className="text-[10px] font-bold text-blue-600 uppercase tracking-wider">
                          Scheduled for this time window ({assignedItems.length})
                        </p>
                      </div>
                      {assignedItems.map((item) => (
                        <div
                          key={item.id}
                          className="flex items-center gap-3 px-4 py-2.5 hover:bg-slate-50/50 transition-colors"
                        >
                          <button
                            onClick={() => toggleItemSchedule(item)}
                            disabled={assignSaving === item.id}
                            className="relative w-9 h-5 rounded-full bg-blue-500 transition-colors flex-shrink-0 disabled:opacity-60"
                          >
                            <span className="absolute top-0.5 right-0.5 w-4 h-4 rounded-full bg-white shadow-sm transition-transform" />
                          </button>
                          <div className="flex-1 min-w-0">
                            <p className="text-xs font-semibold text-slate-700 truncate">
                              {item.name}
                            </p>
                            <div className="flex items-center gap-1.5 mt-0.5">
                              <span className="text-[10px] text-slate-400">
                                {catMap.get(item.categoryId) ?? "Uncategorized"}
                              </span>
                              {item.menuId && (
                                <>
                                  <span className="text-[10px] text-slate-300">·</span>
                                  <span className="text-[10px] px-1.5 py-0.5 rounded bg-purple-50 text-purple-500 font-medium">
                                    {menuMap.get(item.menuId) ?? "No Menu"}
                                  </span>
                                </>
                              )}
                            </div>
                          </div>
                          <span className="text-xs font-medium text-slate-500 tabular-nums">
                            ${item.price.toFixed(2)}
                          </span>
                        </div>
                      ))}
                    </>
                  )}
                  {unassignedItems.length > 0 && (
                    <>
                      <div className="px-4 py-1.5 bg-slate-50/80 sticky top-0 z-10">
                        <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">
                          Available All Day — Base Items ({unassignedItems.length})
                        </p>
                      </div>
                      {unassignedItems.map((item) => (
                        <div
                          key={item.id}
                          className="flex items-center gap-3 px-4 py-2.5 hover:bg-slate-50/50 transition-colors"
                        >
                          <button
                            onClick={() => toggleItemSchedule(item)}
                            disabled={assignSaving === item.id}
                            className="relative w-9 h-5 rounded-full bg-slate-200 transition-colors flex-shrink-0 disabled:opacity-60"
                          >
                            <span className="absolute top-0.5 left-0.5 w-4 h-4 rounded-full bg-white shadow-sm transition-transform" />
                          </button>
                          <div className="flex-1 min-w-0">
                            <p className="text-xs font-semibold text-slate-700 truncate">
                              {item.name}
                            </p>
                            <div className="flex items-center gap-1.5 mt-0.5">
                              <span className="text-[10px] text-slate-400">
                                {catMap.get(item.categoryId) ?? "Uncategorized"}
                              </span>
                              {item.menuId && (
                                <>
                                  <span className="text-[10px] text-slate-300">·</span>
                                  <span className="text-[10px] px-1.5 py-0.5 rounded bg-purple-50 text-purple-500 font-medium">
                                    {menuMap.get(item.menuId) ?? "No Menu"}
                                  </span>
                                </>
                              )}
                            </div>
                          </div>
                          <span className="text-xs font-medium text-slate-500 tabular-nums">
                            ${item.price.toFixed(2)}
                          </span>
                        </div>
                      ))}
                    </>
                  )}
                  {filteredItems.length === 0 && (
                    <div className="py-8 text-center">
                      <p className="text-sm text-slate-400">No items found</p>
                    </div>
                  )}
                </div>
              </div>
            </>
          )
        )}
      </div>

      {/* ════════════════════════════════════════════
          MODAL: CREATE / EDIT MENU
          ════════════════════════════════════════════ */}
      {menuModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !menuSaving && setMenuModalOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  {menuModalEdit ? "Edit Menu" : "Create Menu"}
                </h3>
                <button
                  onClick={() => setMenuModalOpen(false)}
                  disabled={menuSaving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Menu Name
                </label>
                <input
                  type="text"
                  value={menuName}
                  onChange={(e) => setMenuName(e.target.value)}
                  placeholder="e.g. Breakfast, Lunch, Dinner"
                  className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  autoFocus
                />
              </div>

              <div className="flex items-center justify-between py-2">
                <div>
                  <p className="text-sm font-medium text-slate-700">Active</p>
                  <p className="text-xs text-slate-400">
                    Inactive menus won&apos;t affect POS visibility
                  </p>
                </div>
                <button
                  onClick={() => setMenuIsActive(!menuIsActive)}
                  className="flex items-center"
                >
                  {menuIsActive ? (
                    <ToggleRight size={32} className="text-blue-600" />
                  ) : (
                    <ToggleLeft size={32} className="text-slate-300" />
                  )}
                </button>
              </div>

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setMenuModalOpen(false)}
                  disabled={menuSaving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSaveMenu}
                  disabled={menuSaving || !menuName.trim()}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {menuSaving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Saving…
                    </>
                  ) : menuModalEdit ? (
                    "Save"
                  ) : (
                    "Create Menu"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ════════════════════════════════════════════
          MODAL: DELETE MENU
          ════════════════════════════════════════════ */}
      {deleteMenuTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !deletingMenu && setDeleteMenuTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex flex-col items-center gap-3">
                <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center">
                  <AlertTriangle size={24} className="text-red-500" />
                </div>
                <h3 className="text-lg font-semibold text-slate-800">Delete Menu</h3>
              </div>
              <p className="text-sm text-slate-500 text-center">
                Delete <strong className="text-slate-700">{deleteMenuTarget.name}</strong>?
                Items belonging to this menu will become unassigned. This cannot be undone.
              </p>
              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setDeleteMenuTarget(null)}
                  disabled={deletingMenu}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleDeleteMenu}
                  disabled={deletingMenu}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {deletingMenu ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Deleting…
                    </>
                  ) : (
                    "Delete"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ════════════════════════════════════════════
          MODAL: CREATE / EDIT SCHEDULE
          ════════════════════════════════════════════ */}
      {scheduleModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !scheduleSaving && setScheduleModalOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-semibold text-slate-800">
                  {scheduleEdit ? "Edit Schedule" : "Create Schedule"}
                </h3>
                <button
                  onClick={() => setScheduleModalOpen(false)}
                  disabled={scheduleSaving}
                  className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
                >
                  <X size={18} />
                </button>
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">
                  Schedule Name
                </label>
                <input
                  type="text"
                  value={scheduleName}
                  onChange={(e) => setScheduleName(e.target.value)}
                  placeholder="e.g. Breakfast Hours, Happy Hour"
                  className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  autoFocus
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-700 mb-2">Days</label>
                <div className="flex flex-wrap gap-2">
                  {DAYS.map((d) => (
                    <button
                      key={d}
                      onClick={() =>
                        setScheduleDays((prev) => ({ ...prev, [d]: !prev[d] }))
                      }
                      className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors ${
                        scheduleDays[d]
                          ? "bg-blue-600 text-white"
                          : "bg-slate-100 text-slate-500 hover:bg-slate-200"
                      }`}
                    >
                      {DAY_LABELS[d]}
                    </button>
                  ))}
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    Start Time
                  </label>
                  <input
                    type="time"
                    value={scheduleStart}
                    onChange={(e) => setScheduleStart(e.target.value)}
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">
                    End Time
                  </label>
                  <input
                    type="time"
                    value={scheduleEnd}
                    onChange={(e) => setScheduleEnd(e.target.value)}
                    className="w-full px-3 py-2.5 rounded-xl border border-slate-200 text-sm text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-400/20"
                  />
                </div>
              </div>

              {scheduleError && (
                <p className="text-sm text-red-600 font-medium">{scheduleError}</p>
              )}

              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setScheduleModalOpen(false)}
                  disabled={scheduleSaving}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSaveSchedule}
                  disabled={
                    scheduleSaving ||
                    !scheduleName.trim() ||
                    DAYS.every((d) => !scheduleDays[d])
                  }
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {scheduleSaving ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Saving…
                    </>
                  ) : scheduleEdit ? (
                    "Save"
                  ) : (
                    "Create Schedule"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ════════════════════════════════════════════
          MODAL: DELETE SCHEDULE
          ════════════════════════════════════════════ */}
      {deleteScheduleTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => !deletingSchedule && setDeleteScheduleTarget(null)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
            <div className="px-6 py-5 space-y-4">
              <div className="flex flex-col items-center gap-3">
                <div className="w-12 h-12 rounded-full bg-red-50 flex items-center justify-center">
                  <AlertTriangle size={24} className="text-red-500" />
                </div>
                <h3 className="text-lg font-semibold text-slate-800">Delete Schedule</h3>
              </div>
              <p className="text-sm text-slate-500 text-center">
                Delete <strong className="text-slate-700">{deleteScheduleTarget.name}</strong>?
                Items assigned to this schedule will revert to base (always available). This cannot
                be undone.
              </p>
              <div className="flex gap-3 pt-1">
                <button
                  onClick={() => setDeleteScheduleTarget(null)}
                  disabled={deletingSchedule}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleDeleteSchedule}
                  disabled={deletingSchedule}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-red-600 text-white text-sm font-medium hover:bg-red-700 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {deletingSchedule ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                      Deleting…
                    </>
                  ) : (
                    "Delete"
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ════════════════════════════════════════════
          MODAL: PREVIEW MODE (Additive)
          ════════════════════════════════════════════ */}
      {previewOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={() => setPreviewOpen(false)}
          />
          <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-lg mx-4 overflow-hidden max-h-[90vh] flex flex-col">
            <div className="px-6 py-4 border-b border-slate-100 flex items-center justify-between shrink-0">
              <div className="flex items-center gap-2">
                <Eye size={18} className="text-blue-600" />
                <h3 className="text-lg font-semibold text-slate-800">Preview Mode</h3>
              </div>
              <button
                onClick={() => setPreviewOpen(false)}
                className="p-1 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors"
              >
                <X size={18} />
              </button>
            </div>

            <div className="px-6 py-4 border-b border-slate-50 shrink-0">
              <p className="text-xs text-slate-500 mb-3">
                Base items are always visible. Scheduled items appear only during their time
                window.
              </p>
              <div className="flex flex-wrap gap-2 mb-3">
                {DAYS.map((d) => (
                  <button
                    key={d}
                    onClick={() => setPreviewDay(d)}
                    className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors ${
                      previewDay === d
                        ? "bg-blue-600 text-white"
                        : "bg-slate-100 text-slate-500 hover:bg-slate-200"
                    }`}
                  >
                    {DAY_LABELS[d]}
                  </button>
                ))}
              </div>
              <div className="flex items-center gap-3">
                <label className="text-xs font-medium text-slate-600">Time:</label>
                <input
                  type="time"
                  value={previewTime}
                  onChange={(e) => setPreviewTime(e.target.value)}
                  className="px-3 py-1.5 rounded-lg border border-slate-200 text-xs text-slate-800 focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-400/20"
                />
                <span className="text-xs text-slate-400">
                  {previewItems.length} item{previewItems.length !== 1 ? "s" : ""} visible
                </span>
              </div>
            </div>

            <div className="flex-1 overflow-y-auto px-6 py-3">
              {previewItems.length === 0 ? (
                <div className="py-10 text-center">
                  <p className="text-sm text-slate-400">No items available at this time</p>
                </div>
              ) : (
                <div className="space-y-1">
                  {previewItems.map((item) => (
                    <div
                      key={item.id}
                      className="flex items-center justify-between py-2 px-2 rounded-lg hover:bg-slate-50 transition-colors"
                    >
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <p className="text-xs font-semibold text-slate-700 truncate">
                            {item.name}
                          </p>
                          {item.isScheduled && (
                            <span className="text-[9px] px-1.5 py-0.5 rounded-full bg-blue-50 text-blue-600 font-semibold shrink-0">
                              Scheduled
                            </span>
                          )}
                        </div>
                        <div className="flex items-center gap-1.5 mt-0.5">
                          <span className="text-[10px] text-slate-400">
                            {catMap.get(item.categoryId) ?? "Uncategorized"}
                          </span>
                          {item.menuId && (
                            <>
                              <span className="text-[10px] text-slate-300">·</span>
                              <span className="text-[10px] text-purple-500 font-medium">
                                {menuMap.get(item.menuId) ?? ""}
                              </span>
                            </>
                          )}
                        </div>
                      </div>
                      <span className="text-xs font-medium text-slate-500 tabular-nums shrink-0 ml-3">
                        ${item.price.toFixed(2)}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
