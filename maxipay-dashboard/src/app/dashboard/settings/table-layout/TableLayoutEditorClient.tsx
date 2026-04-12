"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { collection, onSnapshot } from "firebase/firestore";
import {
  Copy,
  LayoutTemplate,
  Plus,
  Save,
  Star,
  Trash2,
  Upload,
} from "lucide-react";
import Header from "@/components/Header";
import { db } from "@/firebase/firebaseConfig";
import { useAuth } from "@/context/AuthContext";
import {
  batchUpdateLayoutTables,
  createTableLayout,
  DEFAULT_CANVAS,
  DEFAULT_TABLE_SIZE,
  deleteFirestoreSectionDoc,
  deleteLayoutTable,
  deleteTableLayout,
  emptyTable,
  importLegacyTablesLayout,
  MAX_RESERVATION_GRACE_AFTER_SLOT_MINUTES,
  SECTIONS_COLLECTION,
  setDefaultTableLayout,
  subscribeLayoutTables,
  subscribeTableLayouts,
  updateTableLayoutMeta,
  upsertFirestoreSection,
  upsertLayoutTable,
  type TableLayoutDocument,
  type TableLayoutTableDocument,
  type TableShape,
} from "@/lib/tableLayoutFirestore";

type TableRow = { id: string; data: TableLayoutTableDocument };

const SHAPES: { value: TableShape; label: string }[] = [
  { value: "SQUARE", label: "Square" },
  { value: "RECTANGLE", label: "Rectangle" },
  { value: "ROUND", label: "Round" },
  { value: "BOOTH", label: "Booth" },
];

function shapeStyle(shape: TableShape): React.CSSProperties {
  const base: React.CSSProperties = {
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    fontSize: "11px",
    fontWeight: 600,
    color: "#1e293b",
    border: "2px solid #334155",
    background: "rgba(255,255,255,0.92)",
    boxShadow: "0 2px 8px rgba(15,23,42,0.08)",
    userSelect: "none",
    cursor: "grab",
  };
  if (shape === "ROUND") return { ...base, borderRadius: "9999px" };
  if (shape === "BOOTH")
    return {
      ...base,
      borderRadius: "6px",
      borderBottomWidth: "10px",
      borderBottomColor: "#94a3b8",
    };
  if (shape === "RECTANGLE") return { ...base, borderRadius: "6px" };
  return { ...base, borderRadius: "4px" };
}

type SectionRow = { id: string; name: string };

export default function TableLayoutEditorClient() {
  const { user } = useAuth();
  const sectionListId = useId();
  const [layouts, setLayouts] = useState<Array<{ id: string; data: TableLayoutDocument }>>([]);
  const [layoutId, setLayoutId] = useState<string | null>(null);
  const [tables, setTables] = useState<TableRow[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  /** Firestore `Sections` docs (same as Android floor chips). */
  const [sectionRows, setSectionRows] = useState<SectionRow[]>([]);
  /** `null` = show all sections on canvas; otherwise filter case-insensitively (matches Android). */
  const [canvasSectionFilter, setCanvasSectionFilter] = useState<string | null>(null);
  const [newSectionName, setNewSectionName] = useState("");
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [importing, setImporting] = useState(false);
  const canvasRef = useRef<HTMLDivElement>(null);
  const tablesRef = useRef<TableRow[]>([]);
  const dragRef = useRef<{
    id: string;
    startX: number;
    startY: number;
    origX: number;
    origY: number;
  } | null>(null);

  useEffect(() => {
    tablesRef.current = tables;
  }, [tables]);

  const mergedSectionNames = useMemo(() => {
    const s = new Set<string>();
    for (const r of sectionRows) {
      if (r.name) s.add(r.name);
    }
    for (const t of tables) {
      const v = t.data.section?.trim();
      if (v) s.add(v);
    }
    return Array.from(s).sort((a, b) => a.localeCompare(b));
  }, [sectionRows, tables]);

  const tablesForCanvas = useMemo(() => {
    return tables.filter((t) => {
      if (!t.data.isActive) return false;
      if (canvasSectionFilter == null) return true;
      const sec = (t.data.section || "").trim();
      return sec.toLowerCase() === canvasSectionFilter.toLowerCase();
    });
  }, [tables, canvasSectionFilter]);

  useEffect(() => {
    if (!selectedId) return;
    const still = tablesForCanvas.some((t) => t.id === selectedId);
    if (!still) setSelectedId(null);
  }, [tablesForCanvas, selectedId]);

  const layout = layouts.find((l) => l.id === layoutId)?.data;
  const selected = tables.find((t) => t.id === selectedId);

  useEffect(() => {
    if (!user) return;
    const unsub = subscribeTableLayouts(
      db,
      (list) => {
        setLayouts(list);
        setLayoutId((prev) => {
          if (prev && list.some((l) => l.id === prev)) return prev;
          const def = list.find((l) => l.data.isDefault);
          return def?.id ?? list[0]?.id ?? null;
        });
      },
      (e) => console.error(e)
    );
    return () => unsub();
  }, [user]);

  useEffect(() => {
    if (!user || !layoutId) {
      setTables([]);
      return;
    }
    const unsub = subscribeLayoutTables(
      db,
      layoutId,
      (list) => setTables(list),
      (e) => console.error(e)
    );
    return () => unsub();
  }, [user, layoutId]);

  useEffect(() => {
    if (!user) return;
    const unsub = onSnapshot(collection(db, SECTIONS_COLLECTION), (snap) => {
      const rows: SectionRow[] = [];
      snap.forEach((d) => {
        const n = String(d.get("name") ?? d.id).trim();
        if (!n || n.toLowerCase() === "bar") return;
        rows.push({ id: d.id, name: n });
      });
      rows.sort((a, b) => a.name.localeCompare(b.name));
      setSectionRows(rows);
    });
    return () => unsub();
  }, [user]);

  const canvasW = layout?.canvasWidth ?? DEFAULT_CANVAS.width;
  const canvasH = layout?.canvasHeight ?? DEFAULT_CANVAS.height;

  const updateTableLocal = useCallback((id: string, patch: Partial<TableLayoutTableDocument>) => {
    setTables((prev) =>
      prev.map((t) => (t.id === id ? { ...t, data: { ...t.data, ...patch } } : t))
    );
  }, []);

  const persistTable = useCallback(
    async (id: string, data: TableLayoutTableDocument) => {
      if (!layoutId) return;
      setSaveState("saving");
      try {
        await upsertLayoutTable(db, layoutId, id, data);
        setSaveState("saved");
        setTimeout(() => setSaveState("idle"), 1500);
      } catch {
        setSaveState("error");
      }
    },
    [layoutId]
  );

  const handlePointerDown = useCallback(
    (e: React.PointerEvent, id: string) => {
      if (!canvasRef.current || !layout) return;
      e.preventDefault();
      e.stopPropagation();
      setSelectedId(id);
      const row = tables.find((t) => t.id === id);
      if (!row) return;
      (e.target as HTMLElement).setPointerCapture(e.pointerId);
      dragRef.current = {
        id,
        startX: e.clientX,
        startY: e.clientY,
        origX: row.data.x,
        origY: row.data.y,
      };
    },
    [tables, layout]
  );

  useEffect(() => {
    const onMove = (e: PointerEvent) => {
      const d = dragRef.current;
      if (!d || !canvasRef.current || !layout) return;
      const rect = canvasRef.current.getBoundingClientRect();
      const dx = ((e.clientX - d.startX) / rect.width) * canvasW;
      const dy = ((e.clientY - d.startY) / rect.height) * canvasH;
      updateTableLocal(d.id, {
        x: Math.round(
          Math.max(0, Math.min(canvasW - DEFAULT_TABLE_SIZE.width, d.origX + dx))
        ),
        y: Math.round(
          Math.max(0, Math.min(canvasH - DEFAULT_TABLE_SIZE.height, d.origY + dy))
        ),
      });
    };
    const onUp = async () => {
      const d = dragRef.current;
      if (!d || !layoutId) return;
      dragRef.current = null;
      const row = tablesRef.current.find((t) => t.id === d.id);
      if (row) {
        setSaveState("saving");
        try {
          await batchUpdateLayoutTables(db, layoutId, [
            { id: d.id, data: { x: row.data.x, y: row.data.y } },
          ]);
          setSaveState("saved");
          setTimeout(() => setSaveState("idle"), 1200);
        } catch {
          setSaveState("error");
        }
      }
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onUp);
    return () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onUp);
    };
  }, [canvasW, canvasH, layout, layoutId, updateTableLocal]);

  const addLayout = async () => {
    const name = window.prompt("Layout name", "Main Dining");
    if (!name?.trim()) return;
    setSaveState("saving");
    try {
      const id = await createTableLayout(db, { name: name.trim() });
      setLayoutId(id);
      setSaveState("saved");
      setTimeout(() => setSaveState("idle"), 1200);
    } catch {
      setSaveState("error");
    }
  };

  const renameLayout = async () => {
    if (!layoutId || !layout) return;
    const name = window.prompt("Rename layout", layout.name);
    if (!name?.trim()) return;
    setSaveState("saving");
    try {
      await updateTableLayoutMeta(db, layoutId, { name: name.trim() });
      setSaveState("saved");
      setTimeout(() => setSaveState("idle"), 1200);
    } catch {
      setSaveState("error");
    }
  };

  const removeLayout = async () => {
    if (!layoutId || !layout) return;
    if (!window.confirm(`Delete layout "${layout.name}" and all its tables?`)) return;
    setSaveState("saving");
    try {
      await deleteTableLayout(db, layoutId);
      setSelectedId(null);
      setSaveState("idle");
    } catch {
      setSaveState("error");
    }
  };

  const makeDefault = async () => {
    if (!layoutId) return;
    setSaveState("saving");
    try {
      await setDefaultTableLayout(db, layoutId);
      setSaveState("saved");
      setTimeout(() => setSaveState("idle"), 1200);
    } catch {
      setSaveState("error");
    }
  };

  const importLegacy = async () => {
    if (!window.confirm("Create a new layout from legacy Tables collection?")) return;
    setImporting(true);
    try {
      const id = await importLegacyTablesLayout(db, "Imported from POS");
      setLayoutId(id);
    } catch (e) {
      console.error(e);
      alert("Import failed. Check console.");
    } finally {
      setImporting(false);
    }
  };

  const addSectionFromWeb = async () => {
    const name = newSectionName.trim();
    if (!name) {
      window.alert("Enter a section name in the box, then click Add (or press Enter).");
      return;
    }
    const dup = sectionRows.find((r) => r.name.toLowerCase() === name.toLowerCase());
    if (dup) {
      window.alert(`Section "${dup.name}" already exists.`);
      return;
    }
    setSaveState("saving");
    try {
      await upsertFirestoreSection(db, name);
      setNewSectionName("");
      setSaveState("saved");
      setTimeout(() => setSaveState("idle"), 1200);
    } catch (e) {
      console.error(e);
      window.alert(e instanceof Error ? e.message : "Could not add section. Check Firestore rules and your connection.");
      setSaveState("error");
      setTimeout(() => setSaveState("idle"), 2000);
    }
  };

  const removeSectionFromWeb = async (row: SectionRow) => {
    const used = tables.filter(
      (t) => (t.data.section || "").trim().toLowerCase() === row.name.toLowerCase()
    ).length;
    const msg =
      used > 0
        ? `Delete section "${row.name}"? ${used} table(s) still reference it; assign them elsewhere first, or they will disappear when filtering by section on POS.`
        : `Delete section "${row.name}"?`;
    if (!window.confirm(msg)) return;
    setSaveState("saving");
    try {
      await deleteFirestoreSectionDoc(db, row.id);
      if (canvasSectionFilter?.toLowerCase() === row.name.toLowerCase()) {
        setCanvasSectionFilter(null);
      }
      setSaveState("idle");
    } catch (e) {
      console.error(e);
      setSaveState("error");
    }
  };

  const addTable = async () => {
    if (!layoutId || !layout) return;
    const next = emptyTable(layout.canvasWidth, layout.canvasHeight, tables.length);
    if (canvasSectionFilter) next.section = canvasSectionFilter;
    setSaveState("saving");
    try {
      await upsertLayoutTable(db, layoutId, null, next);
      setSaveState("saved");
      setTimeout(() => setSaveState("idle"), 1200);
    } catch {
      setSaveState("error");
    }
  };

  const duplicateTable = async () => {
    if (!selected || !layoutId) return;
    const d = {
      ...selected.data,
      name: `${selected.data.name} (copy)`,
      x: selected.data.x + 40,
      y: selected.data.y + 40,
      sortOrder: tables.length,
    };
    setSaveState("saving");
    try {
      await upsertLayoutTable(db, layoutId, null, d);
      setSaveState("saved");
      setTimeout(() => setSaveState("idle"), 1200);
    } catch {
      setSaveState("error");
    }
  };

  const removeTable = async () => {
    if (!selectedId || !layoutId) return;
    if (!window.confirm("Remove this table from the layout?")) return;
    setSaveState("saving");
    try {
      await deleteLayoutTable(db, layoutId, selectedId);
      setSelectedId(null);
      setSaveState("idle");
    } catch {
      setSaveState("error");
    }
  };

  const saveInspector = async () => {
    if (!selected || !layoutId) return;
    setSaveState("saving");
    try {
      await upsertLayoutTable(db, layoutId, selected.id, selected.data);
      setSaveState("saved");
      setTimeout(() => setSaveState("idle"), 1200);
    } catch {
      setSaveState("error");
    }
  };

  const saveCanvasSize = useCallback(async () => {
    if (!layoutId) return;
    const l = layouts.find((x) => x.id === layoutId);
    if (!l) return;
    setSaveState("saving");
    try {
      await updateTableLayoutMeta(db, layoutId, {
        canvasWidth: l.data.canvasWidth,
        canvasHeight: l.data.canvasHeight,
        reservationGraceAfterSlotMinutes: l.data.reservationGraceAfterSlotMinutes ?? 0,
        reservationHoldStartsMinutesBeforeSlot: l.data.reservationHoldStartsMinutesBeforeSlot ?? 0,
      });
      setSaveState("saved");
      setTimeout(() => setSaveState("idle"), 1200);
    } catch {
      setSaveState("error");
    }
  }, [layoutId, layouts]);

  const renderInspector = () => {
    if (!selected) return null;
    const d = selected.data;
    return (
      <div className="space-y-4">
        <div>
          <label className="mb-1 block text-xs font-medium text-slate-500">Table name</label>
          <input
            className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm"
            value={d.name}
            onChange={(e) => updateTableLocal(selected.id, { name: e.target.value })}
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-slate-500">Code / number</label>
          <input
            className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm"
            value={d.code}
            onChange={(e) => updateTableLocal(selected.id, { code: e.target.value })}
            placeholder="e.g. T12"
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-slate-500">Capacity</label>
          <input
            type="number"
            min={0}
            className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm"
            value={d.capacity}
            onChange={(e) =>
              updateTableLocal(selected.id, { capacity: Number(e.target.value) || 0 })
            }
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-slate-500">Shape</label>
          <select
            className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm"
            value={d.shape}
            onChange={(e) =>
              updateTableLocal(selected.id, { shape: e.target.value as TableShape })
            }
          >
            {SHAPES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1 block text-xs font-medium text-slate-500">X</label>
            <input
              type="number"
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              value={d.x}
              onChange={(e) => updateTableLocal(selected.id, { x: Number(e.target.value) || 0 })}
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-slate-500">Y</label>
            <input
              type="number"
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              value={d.y}
              onChange={(e) => updateTableLocal(selected.id, { y: Number(e.target.value) || 0 })}
            />
          </div>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1 block text-xs font-medium text-slate-500">Width</label>
            <input
              type="number"
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              value={d.width}
              onChange={(e) =>
                updateTableLocal(selected.id, { width: Math.max(20, Number(e.target.value) || 20) })
              }
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-slate-500">Height</label>
            <input
              type="number"
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              value={d.height}
              onChange={(e) =>
                updateTableLocal(selected.id, {
                  height: Math.max(20, Number(e.target.value) || 20),
                })
              }
            />
          </div>
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-slate-500">Rotation (°)</label>
          <input
            type="number"
            className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            value={d.rotation}
            onChange={(e) =>
              updateTableLocal(selected.id, { rotation: Number(e.target.value) || 0 })
            }
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-slate-500">Section / zone</label>
          <input
            className="mb-2 w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            list={sectionListId}
            value={d.section}
            onChange={(e) => updateTableLocal(selected.id, { section: e.target.value })}
            placeholder="e.g. Inside, Rooftop"
          />
          <datalist id={sectionListId}>
            {mergedSectionNames.map((s) => (
              <option key={s} value={s} />
            ))}
          </datalist>
          <p className="text-xs text-slate-500">
            Must match a section from the list (left panel) for Dine In / Android chips to line up.
          </p>
        </div>
        <label className="flex items-center gap-2 text-sm text-slate-700">
          <input
            type="checkbox"
            checked={d.isActive}
            onChange={(e) => updateTableLocal(selected.id, { isActive: e.target.checked })}
          />
          Active (visible on POS)
        </label>
        <button
          type="button"
          onClick={saveInspector}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-blue-600 py-2.5 text-sm font-medium text-white hover:bg-blue-700"
        >
          <Save size={16} />
          Apply table changes
        </button>
      </div>
    );
  };

  if (!user) {
    return (
      <>
        <Header title="Table Layout" />
        <div className="p-8 text-center text-slate-500">Sign in to edit table layouts.</div>
      </>
    );
  }

  return (
    <>
      <Header title="Table Layout" />

      <div className="flex min-h-[calc(100vh-4rem)] flex-col gap-4 p-4 lg:flex-row lg:p-6">
        {/* Layout list */}
        <aside className="flex w-full flex-shrink-0 flex-col gap-3 rounded-2xl border border-slate-100 bg-white p-4 shadow-sm lg:w-56">
          <div className="flex items-center gap-2 text-slate-800">
            <LayoutTemplate size={18} className="text-blue-600" />
            <span className="text-sm font-semibold">Floor plans</span>
          </div>
          <div className="max-h-48 space-y-1 overflow-y-auto lg:max-h-none">
            {layouts.length === 0 && (
              <p className="text-xs text-slate-500">No layouts yet. Create one to start.</p>
            )}
            {layouts.map((l) => (
              <button
                key={l.id}
                type="button"
                onClick={() => {
                  setLayoutId(l.id);
                  setSelectedId(null);
                }}
                className={`flex w-full items-center justify-between rounded-xl px-3 py-2 text-left text-sm transition-colors ${
                  layoutId === l.id
                    ? "bg-blue-50 font-medium text-blue-800"
                    : "text-slate-700 hover:bg-slate-50"
                }`}
              >
                <span className="truncate">{l.data.name}</span>
                {l.data.isDefault && (
                  <Star size={14} className="flex-shrink-0 fill-amber-400 text-amber-500" />
                )}
              </button>
            ))}
          </div>
          <button
            type="button"
            onClick={addLayout}
            className="flex items-center justify-center gap-2 rounded-xl border border-dashed border-slate-300 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50"
          >
            <Plus size={16} /> New layout
          </button>
          <button
            type="button"
            onClick={importLegacy}
            disabled={importing}
            className="flex items-center justify-center gap-2 rounded-xl border border-slate-200 py-2 text-xs font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-50"
          >
            <Upload size={14} /> Import legacy Tables
          </button>

          <div className="mt-4 border-t border-slate-100 pt-4">
            <p className="text-sm font-semibold text-slate-800">Sections (Dine In)</p>
            <p className="mb-2 mt-1 text-xs text-slate-500">
              Synced with Android POS Setup and Dine In chips. Create zones here, then set each
              table&apos;s section in the properties panel.
            </p>
            <form
              className="flex gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                void addSectionFromWeb();
              }}
            >
              <input
                name="sectionName"
                autoComplete="off"
                className="relative z-0 min-w-0 flex-1 rounded-lg border border-slate-200 px-2 py-1.5 text-sm"
                placeholder="e.g. Patio, VIP"
                value={newSectionName}
                onChange={(e) => setNewSectionName(e.target.value)}
              />
              <button
                type="submit"
                disabled={saveState === "saving"}
                className="relative z-10 flex-shrink-0 cursor-pointer rounded-lg bg-slate-800 px-3 py-1.5 text-xs font-medium text-white hover:bg-slate-900 disabled:cursor-wait disabled:opacity-70"
              >
                {saveState === "saving" ? "…" : "Add"}
              </button>
            </form>
            <ul className="mt-2 max-h-36 space-y-1 overflow-y-auto text-sm">
              {sectionRows.length === 0 && (
                <li className="text-xs text-slate-400">No sections yet — add one or import tables.</li>
              )}
              {sectionRows.map((row) => (
                <li
                  key={row.id}
                  className="flex items-center justify-between gap-2 rounded-lg bg-slate-50 px-2 py-1.5"
                >
                  <span className="min-w-0 truncate text-slate-800">{row.name}</span>
                  <button
                    type="button"
                    onClick={() => void removeSectionFromWeb(row)}
                    className="flex-shrink-0 text-xs font-medium text-red-600 hover:underline"
                  >
                    Remove
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </aside>

        {/* Canvas + toolbar */}
        <main className="min-w-0 flex-1 space-y-3">
          {layout && (
            <div className="flex flex-wrap items-center gap-2 rounded-2xl border border-slate-100 bg-white px-4 py-3 shadow-sm">
              <span className="text-sm font-medium text-slate-800">{layout.name}</span>
              {layout.isDefault ? (
                <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                  Default for POS
                </span>
              ) : (
                <button
                  type="button"
                  onClick={makeDefault}
                  className="text-xs font-medium text-blue-600 hover:underline"
                >
                  Set as default
                </button>
              )}
              <span className="text-slate-300">|</span>
              <button
                type="button"
                onClick={renameLayout}
                className="text-xs font-medium text-slate-600 hover:text-slate-900"
              >
                Rename
              </button>
              <button
                type="button"
                onClick={removeLayout}
                className="inline-flex items-center gap-1 text-xs font-medium text-red-600 hover:text-red-700"
              >
                <Trash2 size={12} /> Delete
              </button>
              <span className="ml-auto flex items-center gap-2 text-xs text-slate-500">
                {saveState === "saving" && "Saving…"}
                {saveState === "saved" && "Saved"}
                {saveState === "error" && "Error saving"}
                {saveState === "idle" && "Drag tables on the canvas; positions save on release."}
              </span>
            </div>
          )}

          <div className="grid grid-cols-1 gap-4 xl:grid-cols-[1fr_280px]">
            <div className="rounded-2xl border border-slate-100 bg-white p-4 shadow-sm">
              {!layoutId ? (
                <div className="flex h-[420px] items-center justify-center text-slate-500">
                  Create a layout to open the editor.
                </div>
              ) : (
                <>
                  {mergedSectionNames.length > 0 && (
                    <div className="mb-3 flex flex-wrap items-center gap-2">
                      <span className="text-xs font-medium text-slate-600">Canvas:</span>
                      <button
                        type="button"
                        onClick={() => setCanvasSectionFilter(null)}
                        className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                          canvasSectionFilter == null
                            ? "bg-slate-900 text-white"
                            : "bg-slate-100 text-slate-700 hover:bg-slate-200"
                        }`}
                      >
                        All sections
                      </button>
                      {mergedSectionNames.map((name) => (
                        <button
                          key={name}
                          type="button"
                          onClick={() => setCanvasSectionFilter(name)}
                          className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                            canvasSectionFilter != null &&
                            canvasSectionFilter.toLowerCase() === name.toLowerCase()
                              ? "bg-blue-600 text-white"
                              : "bg-slate-100 text-slate-700 hover:bg-slate-200"
                          }`}
                        >
                          {name}
                        </button>
                      ))}
                    </div>
                  )}
                  <div className="mb-3 flex flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={addTable}
                      className="inline-flex items-center gap-2 rounded-xl bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800"
                    >
                      <Plus size={16} /> Add table
                    </button>
                    <button
                      type="button"
                      onClick={duplicateTable}
                      disabled={!selected}
                      className="inline-flex items-center gap-2 rounded-xl border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-40"
                    >
                      <Copy size={16} /> Duplicate
                    </button>
                    <button
                      type="button"
                      onClick={removeTable}
                      disabled={!selected}
                      className="inline-flex items-center gap-2 rounded-xl border border-red-200 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-50 disabled:opacity-40"
                    >
                      <Trash2 size={16} /> Delete table
                    </button>
                  </div>
                  <div className="mb-3 flex flex-wrap items-end gap-3 text-sm">
                    <div>
                      <label className="mb-1 block text-xs text-slate-500">Canvas width</label>
                      <input
                        type="number"
                        className="w-24 rounded-lg border border-slate-200 px-2 py-1.5"
                        value={layout?.canvasWidth ?? DEFAULT_CANVAS.width}
                        onChange={(e) => {
                          const v = Number(e.target.value);
                          if (!layoutId || !Number.isFinite(v)) return;
                          setLayouts((prev) =>
                            prev.map((l) =>
                              l.id === layoutId
                                ? { ...l, data: { ...l.data, canvasWidth: Math.max(400, v) } }
                                : l
                            )
                          );
                        }}
                        onBlur={saveCanvasSize}
                      />
                    </div>
                    <div>
                      <label className="mb-1 block text-xs text-slate-500">Canvas height</label>
                      <input
                        type="number"
                        className="w-24 rounded-lg border border-slate-200 px-2 py-1.5"
                        value={layout?.canvasHeight ?? DEFAULT_CANVAS.height}
                        onChange={(e) => {
                          const v = Number(e.target.value);
                          if (!layoutId || !Number.isFinite(v)) return;
                          setLayouts((prev) =>
                            prev.map((l) =>
                              l.id === layoutId
                                ? { ...l, data: { ...l.data, canvasHeight: Math.max(300, v) } }
                                : l
                            )
                          );
                        }}
                        onBlur={saveCanvasSize}
                      />
                    </div>
                    <div className="min-w-[200px] max-w-xs">
                      <label className="mb-1 block text-xs text-slate-500">
                        Extra reserved time after slot (minutes)
                      </label>
                      <input
                        type="number"
                        min={0}
                        max={MAX_RESERVATION_GRACE_AFTER_SLOT_MINUTES}
                        className="w-28 rounded-lg border border-slate-200 px-2 py-1.5"
                        value={layout?.reservationGraceAfterSlotMinutes ?? 0}
                        onChange={(e) => {
                          const v = Math.round(Number(e.target.value));
                          if (!layoutId || !Number.isFinite(v)) return;
                          const clamped = Math.max(
                            0,
                            Math.min(v, MAX_RESERVATION_GRACE_AFTER_SLOT_MINUTES)
                          );
                          setLayouts((prev) =>
                            prev.map((l) =>
                              l.id === layoutId
                                ? {
                                    ...l,
                                    data: { ...l.data, reservationGraceAfterSlotMinutes: clamped },
                                  }
                                : l
                            )
                          );
                        }}
                        onBlur={saveCanvasSize}
                      />
                      <p className="mt-1 text-[11px] leading-snug text-slate-500">
                        After the reservation time, the table stays RESERVED for this many extra
                        minutes before the POS frees it (0 = free as soon as the slot time passes).
                        Max {MAX_RESERVATION_GRACE_AFTER_SLOT_MINUTES} min (1 week).
                      </p>
                    </div>
                    <div className="min-w-[200px] max-w-xs">
                      <label className="mb-1 block text-xs text-slate-500">
                        Show reserved on Dine-In (minutes before slot)
                      </label>
                      <input
                        type="number"
                        min={0}
                        max={MAX_RESERVATION_GRACE_AFTER_SLOT_MINUTES}
                        className="w-28 rounded-lg border border-slate-200 px-2 py-1.5"
                        value={layout?.reservationHoldStartsMinutesBeforeSlot ?? 0}
                        onChange={(e) => {
                          const v = Math.round(Number(e.target.value));
                          if (!layoutId || !Number.isFinite(v)) return;
                          const clamped = Math.max(
                            0,
                            Math.min(v, MAX_RESERVATION_GRACE_AFTER_SLOT_MINUTES)
                          );
                          setLayouts((prev) =>
                            prev.map((l) =>
                              l.id === layoutId
                                ? {
                                    ...l,
                                    data: {
                                      ...l.data,
                                      reservationHoldStartsMinutesBeforeSlot: clamped,
                                    },
                                  }
                                : l
                            )
                          );
                        }}
                        onBlur={saveCanvasSize}
                      />
                      <p className="mt-1 text-[11px] leading-snug text-slate-500">
                        Dine-In floor only: tables look RESERVED starting this many minutes before
                        the booking time (e.g. 15 = 6:45 PM for a 7:00 PM reservation). Does not
                        change table status in Firestore early. Max {MAX_RESERVATION_GRACE_AFTER_SLOT_MINUTES}{" "}
                        min.
                      </p>
                    </div>
                  </div>
                  <div
                    ref={canvasRef}
                    className="relative mx-auto w-full max-w-5xl overflow-hidden rounded-xl border border-slate-200 bg-slate-50"
                    style={{
                      aspectRatio: `${canvasW} / ${canvasH}`,
                      backgroundImage:
                        "linear-gradient(rgba(148,163,184,0.12) 1px, transparent 1px), linear-gradient(90deg, rgba(148,163,184,0.12) 1px, transparent 1px)",
                      backgroundSize: "24px 24px",
                    }}
                    onClick={() => setSelectedId(null)}
                  >
                    {tablesForCanvas.map((t) => {
                        const { data } = t;
                        const leftPct = (data.x / canvasW) * 100;
                        const topPct = (data.y / canvasH) * 100;
                        const wPct = (data.width / canvasW) * 100;
                        const hPct = (data.height / canvasH) * 100;
                        return (
                          <div
                            key={t.id}
                            role="button"
                            tabIndex={0}
                            onPointerDown={(e) => handlePointerDown(e, t.id)}
                            onClick={(e) => e.stopPropagation()}
                            className={`absolute flex flex-col items-center justify-center p-1 transition-shadow ${
                              selectedId === t.id ? "ring-2 ring-blue-500 ring-offset-2" : ""
                            }`}
                            style={{
                              left: `${leftPct}%`,
                              top: `${topPct}%`,
                              width: `${wPct}%`,
                              height: `${hPct}%`,
                              ...shapeStyle(data.shape),
                              transform: `rotate(${data.rotation}deg)`,
                              zIndex: selectedId === t.id ? 5 : 1,
                            }}
                          >
                            <span className="truncate px-0.5 text-center leading-tight">
                              {data.name}
                            </span>
                            <span className="text-[9px] font-normal text-slate-500">
                              {data.capacity} seats
                            </span>
                          </div>
                        );
                      })}
                  </div>
                  <p className="mt-2 text-xs text-slate-500">
                    Coordinates are in layout units ({canvasW}×{canvasH}). Android scales this canvas
                    to the device screen.
                  </p>
                </>
              )}
            </div>

            <aside className="rounded-2xl border border-slate-100 bg-white p-4 shadow-sm">
              <h3 className="mb-3 text-sm font-semibold text-slate-800">Table properties</h3>
              {!selected ? (
                <p className="text-sm text-slate-500">Select a table on the canvas.</p>
              ) : (
                renderInspector()
              )}
            </aside>
          </div>
        </main>
      </div>
    </>
  );
}
