"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  X,
  Loader2,
  Image as ImageIcon,
  Upload,
  CheckCircle2,
  AlertTriangle,
  ChevronDown,
  Sparkles,
} from "lucide-react";
import { useAuth } from "@/context/AuthContext";
import { useMerchantId } from "@/hooks/useMerchantId";
import {
  importScannedModifierGroupsToFirestore,
  normalizedModifierGroupsToScanRows,
  type ImportProgress,
  type ScannedModifierGroupRow,
} from "@/lib/menuImporter";
import type { NormalizedModifierGroup } from "@/lib/menuScanNormalize";

interface Props {
  open: boolean;
  onClose: () => void;
  onImportComplete: () => void;
}

type Stage = "pick" | "scanning" | "preview" | "importing" | "done" | "error";

export default function ModifierScanModal({
  open,
  onClose,
  onImportComplete,
}: Props) {
  const { user } = useAuth();
  const merchantId = useMerchantId();
  const fileRef = useRef<HTMLInputElement>(null);

  const [stage, setStage] = useState<Stage>("pick");
  const [fileName, setFileName] = useState("");
  const [rawText, setRawText] = useState("");
  const [scanRows, setScanRows] = useState<ScannedModifierGroupRow[] | null>(null);
  const [normalizedGroups, setNormalizedGroups] = useState<
    NormalizedModifierGroup[] | null
  >(null);
  const [error, setError] = useState("");
  const [dragOver, setDragOver] = useState(false);
  const [reprocessing, setReprocessing] = useState(false);
  const [importProgress, setImportProgress] = useState<ImportProgress | null>(
    null
  );
  const [resultSummary, setResultSummary] = useState<{
    modifierGroups: number;
    modifierOptions: number;
  } | null>(null);
  const [expandedGroupIds, setExpandedGroupIds] = useState<Set<string>>(
    () => new Set()
  );

  const reset = useCallback(() => {
    setStage("pick");
    setFileName("");
    setRawText("");
    setScanRows(null);
    setNormalizedGroups(null);
    setError("");
    setDragOver(false);
    setReprocessing(false);
    setImportProgress(null);
    setResultSummary(null);
    setExpandedGroupIds(new Set());
    if (fileRef.current) fileRef.current.value = "";
  }, []);

  useEffect(() => {
    if (!open) reset();
  }, [open, reset]);

  const callModifiersScanApi = useCallback(
    async (body: FormData | string) => {
      if (!user) throw new Error("You must be signed in.");
      const token = await user.getIdToken();
      const isJson = typeof body === "string";
      const res = await fetch("/api/modifiers/scan", {
        method: "POST",
        headers: isJson
          ? {
              Authorization: `Bearer ${token}`,
              "Content-Type": "application/json",
            }
          : { Authorization: `Bearer ${token}` },
        body: isJson ? body : body,
      });
      const data = (await res.json()) as {
        success?: boolean;
        error?: string;
        groups?: NormalizedModifierGroup[];
        rawText?: string;
      };
      if (!res.ok || !data.success) {
        throw new Error(data.error || "Scan failed");
      }
      if (!data.groups || !Array.isArray(data.groups)) {
        throw new Error("Invalid scan response");
      }
      return data;
    },
    [user]
  );

  const applyScanResult = useCallback(
    (groups: NormalizedModifierGroup[], text: string) => {
      setRawText(text ?? "");
      setNormalizedGroups(groups);
      const rows = normalizedModifierGroupsToScanRows(groups);
      setScanRows(rows);
      if (rows.length === 0) {
        setError(
          "No modifier groups were detected. Try a clearer image or use Reprocess after editing the raw text."
        );
        setStage("error");
      } else {
        setStage("preview");
      }
    },
    []
  );

  const runScan = async (file: File) => {
    setFileName(file.name);
    setError("");
    setStage("scanning");
    try {
      const fd = new FormData();
      fd.append("file", file);
      const data = await callModifiersScanApi(fd);
      applyScanResult(data.groups ?? [], data.rawText ?? "");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Scan failed");
      setStage("error");
    }
  };

  const handleReprocess = async () => {
    if (!rawText.trim()) return;
    setReprocessing(true);
    setError("");
    try {
      const data = await callModifiersScanApi(
        JSON.stringify({ text: rawText, reprocess: true })
      );
      applyScanResult(data.groups ?? [], data.rawText ?? "");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Reprocess failed");
      setStage("error");
    } finally {
      setReprocessing(false);
    }
  };

  const handleImport = async () => {
    if (!merchantId || !scanRows || scanRows.length === 0) return;
    setStage("importing");
    setImportProgress({ stage: "Creating modifier groups…", current: 0, total: 2 });
    try {
      const res = await importScannedModifierGroupsToFirestore(
        merchantId,
        scanRows,
        setImportProgress
      );
      setResultSummary(res);
      setStage("done");
      onImportComplete();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Import failed");
      setStage("error");
    } finally {
      setImportProgress(null);
    }
  };

  const onFileInput = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!/^image\/(jpeg|jpg|png)$/i.test(file.type)) {
      setError("Please use a JPG or PNG image.");
      setStage("error");
      return;
    }
    void runScan(file);
  };

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files?.[0];
    if (!file) return;
    if (!/^image\/(jpeg|jpg|png)$/i.test(file.type)) {
      setError("Please use a JPG or PNG image.");
      setStage("error");
      return;
    }
    void runScan(file);
  };

  const toggleExpand = (id: string) => {
    setExpandedGroupIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  if (!open) return null;

  const busy = stage === "scanning" || stage === "importing" || reprocessing;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div
        className="absolute inset-0 bg-black/40 backdrop-blur-sm"
        onClick={() => !busy && onClose()}
      />
      <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-lg max-h-[90vh] overflow-hidden flex flex-col">
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100 shrink-0">
          <div className="flex items-center gap-2">
            <div className="w-9 h-9 rounded-xl bg-indigo-50 flex items-center justify-center">
              <ImageIcon size={18} className="text-indigo-600" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-slate-900">
                Scan modifiers from picture
              </h2>
              <p className="text-xs text-slate-500">
                Same technology as menu photo import — adds modifier groups only
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => !busy && onClose()}
            disabled={busy}
            className="p-1.5 rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors disabled:opacity-40"
          >
            <X size={20} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          {(stage === "pick" || stage === "error") && (
            <>
              <div
                onDragOver={(e) => {
                  e.preventDefault();
                  setDragOver(true);
                }}
                onDragLeave={() => setDragOver(false)}
                onDrop={onDrop}
                onClick={() => !busy && fileRef.current?.click()}
                className={`border-2 border-dashed rounded-xl p-8 text-center cursor-pointer transition-colors ${
                  dragOver
                    ? "border-indigo-400 bg-indigo-50/50"
                    : "border-slate-200 hover:border-slate-300 hover:bg-slate-50/50"
                }`}
              >
                <Upload className="mx-auto mb-2 text-slate-400" size={28} />
                <p className="text-sm font-medium text-slate-700">
                  Drop a photo here or click to upload
                </p>
                <p className="text-xs text-slate-400 mt-1">JPG or PNG</p>
              </div>
              <input
                ref={fileRef}
                type="file"
                accept="image/jpeg,image/jpg,image/png"
                className="hidden"
                onChange={onFileInput}
              />
              {error && stage === "error" && (
                <div className="flex gap-2 p-3 rounded-xl bg-red-50 border border-red-100 text-sm text-red-800">
                  <AlertTriangle className="shrink-0" size={18} />
                  <span>{error}</span>
                </div>
              )}
            </>
          )}

          {stage === "scanning" && (
            <div className="flex flex-col items-center justify-center py-12 gap-3">
              <Loader2 className="animate-spin text-indigo-600" size={36} />
              <p className="text-sm text-slate-600">Reading image and extracting modifiers…</p>
              {fileName && (
                <p className="text-xs text-slate-400 truncate max-w-full">{fileName}</p>
              )}
            </div>
          )}

          {stage === "preview" && scanRows && (
            <>
              <div className="flex items-center justify-between gap-2">
                <p className="text-sm text-slate-600">
                  <strong>{scanRows.length}</strong> group
                  {scanRows.length !== 1 ? "s" : ""} — review, then import.
                </p>
                <button
                  type="button"
                  onClick={() => void handleReprocess()}
                  disabled={reprocessing || !rawText.trim()}
                  className="text-xs font-medium text-indigo-600 hover:text-indigo-800 disabled:opacity-40 flex items-center gap-1"
                >
                  {reprocessing ? (
                    <Loader2 className="animate-spin" size={14} />
                  ) : (
                    <Sparkles size={14} />
                  )}
                  Reprocess
                </button>
              </div>
              <div className="space-y-2 max-h-64 overflow-y-auto pr-1">
                {scanRows.map((g) => {
                  const expanded = expandedGroupIds.has(g.id);
                  const optCount = g.options.length;
                  return (
                    <div
                      key={g.id}
                      className="rounded-xl border border-slate-200 overflow-hidden bg-white"
                    >
                      <button
                        type="button"
                        onClick={() => toggleExpand(g.id)}
                        className="flex w-full items-center justify-between gap-2 px-3 py-2.5 text-left hover:bg-slate-50"
                      >
                        <span className="text-sm font-medium text-slate-800 truncate">
                          {g.name}
                        </span>
                        <span className="flex items-center gap-2 shrink-0 text-xs text-slate-500">
                          {g.required ? (
                            <span className="text-amber-600 font-medium">Required</span>
                          ) : (
                            <span>Optional</span>
                          )}
                          · {optCount} opt
                          <ChevronDown
                            size={16}
                            className={`text-slate-400 transition-transform ${
                              expanded ? "rotate-180" : ""
                            }`}
                          />
                        </span>
                      </button>
                      {expanded && (
                        <ul className="px-3 pb-3 pt-0 space-y-1 border-t border-slate-100 bg-slate-50/50">
                          {g.options.map((o) => (
                            <li
                              key={o.id}
                              className="text-xs text-slate-700 flex justify-between gap-2"
                            >
                              <span className="truncate">{o.name}</span>
                              <span className="text-slate-500 tabular-nums shrink-0">
                                {o.price > 0 ? `$${o.price.toFixed(2)}` : "—"}
                              </span>
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  );
                })}
              </div>
              {normalizedGroups && (
                <details className="text-xs">
                  <summary className="cursor-pointer text-slate-400 hover:text-slate-600">
                    Raw OCR text (for reprocess)
                  </summary>
                  <textarea
                    className="mt-2 w-full min-h-[80px] p-2 rounded-lg border border-slate-200 text-slate-600 font-mono text-[11px]"
                    value={rawText}
                    onChange={(e) => setRawText(e.target.value)}
                  />
                </details>
              )}
            </>
          )}

          {stage === "importing" && (
            <div className="flex flex-col items-center py-10 gap-3">
              <Loader2 className="animate-spin text-indigo-600" size={32} />
              <p className="text-sm text-slate-600">
                {importProgress?.stage ?? "Importing…"}
              </p>
            </div>
          )}

          {stage === "done" && resultSummary && (
            <div className="flex flex-col items-center py-8 gap-3 text-center">
              <CheckCircle2 className="text-green-500" size={44} />
              <p className="text-sm font-medium text-slate-800">Import complete</p>
              <p className="text-sm text-slate-500">
                Added <strong>{resultSummary.modifierGroups}</strong> group
                {resultSummary.modifierGroups !== 1 ? "s" : ""} and{" "}
                <strong>{resultSummary.modifierOptions}</strong> option
                {resultSummary.modifierOptions !== 1 ? "s" : ""}.
              </p>
            </div>
          )}
        </div>

        <div className="flex gap-3 px-5 py-4 border-t border-slate-100 shrink-0">
          {stage === "preview" && (
            <>
              <button
                type="button"
                onClick={() => {
                  reset();
                }}
                className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50"
              >
                Start over
              </button>
              <button
                type="button"
                onClick={() => void handleImport()}
                disabled={!scanRows?.length}
                className="flex-1 px-4 py-2.5 rounded-xl bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700 disabled:opacity-40"
              >
                Import groups
              </button>
            </>
          )}
          {stage === "done" && (
            <button
              type="button"
              onClick={onClose}
              className="flex-1 px-4 py-2.5 rounded-xl bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700"
            >
              Done
            </button>
          )}
          {(stage === "error" || stage === "pick") && (
            <button
              type="button"
              onClick={onClose}
              className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50"
            >
              Close
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
