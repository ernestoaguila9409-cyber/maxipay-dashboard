"use client";

import { useCallback, useRef, useState } from "react";
import {
  X,
  Upload,
  FileSpreadsheet,
  CheckCircle2,
  AlertTriangle,
  Loader2,
  Info,
} from "lucide-react";
import {
  parseCloverExcel,
  importMenuToFirestore,
  type ParsedMenu,
  type ImportProgress,
  type ImportResult,
  type ImportLogEntry,
} from "@/lib/menuImporter";

interface Props {
  open: boolean;
  onClose: () => void;
  onImportComplete: () => void;
}

type Stage = "pick" | "preview" | "importing" | "done" | "error";

export default function MenuUploadModal({
  open,
  onClose,
  onImportComplete,
}: Props) {
  const fileRef = useRef<HTMLInputElement>(null);

  const [stage, setStage] = useState<Stage>("pick");
  const [fileName, setFileName] = useState("");
  const [parsed, setParsed] = useState<ParsedMenu | null>(null);
  const [progress, setProgress] = useState<ImportProgress | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState("");

  const reset = useCallback(() => {
    setStage("pick");
    setFileName("");
    setParsed(null);
    setProgress(null);
    setResult(null);
    setError("");
    if (fileRef.current) fileRef.current.value = "";
  }, []);

  const handleClose = () => {
    reset();
    onClose();
  };

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setFileName(file.name);

    try {
      const buffer = await file.arrayBuffer();
      const data = parseCloverExcel(buffer);
      setParsed(data);
      setStage("preview");
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to parse Excel file"
      );
      setStage("error");
    }
  };

  const handleImport = async () => {
    if (!parsed) return;

    setStage("importing");
    try {
      const res = await importMenuToFirestore(parsed, setProgress);
      setResult(res);
      setStage("done");
      onImportComplete();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Import failed");
      setStage("error");
    }
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div
        className="absolute inset-0 bg-black/40 backdrop-blur-sm"
        onClick={handleClose}
      />

      <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-lg mx-4 overflow-hidden max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100">
          <h2 className="text-lg font-semibold text-slate-800">
            Upload Menu
          </h2>
          <button
            onClick={handleClose}
            className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 transition-colors"
          >
            <X size={18} />
          </button>
        </div>

        <div className="px-6 py-5 space-y-5">
          {stage === "pick" && (
            <>
              <p className="text-sm text-slate-500">
                Select an Excel file (.xlsx) with sheets:{" "}
                <strong>Items</strong>, <strong>Categories</strong>,{" "}
                <strong>Modifier Groups</strong>, <strong>Modifier Options</strong>,{" "}
                and <strong>Taxes</strong>.
              </p>

              <div className="bg-slate-50 rounded-xl p-4 text-xs text-slate-500 space-y-2">
                <p className="font-medium text-slate-600">Expected sheet columns (Clover format):</p>
                <p><strong>Items:</strong> Item ID, Name, Price, Category ID, Modifier Group IDs, Tax IDs, Order Types</p>
                <p><strong>Categories:</strong> Category ID, Category Name</p>
                <p><strong>Modifier Groups:</strong> Modifier Group ID, Modifier Group Name</p>
                <p><strong>Modifier Options:</strong> Option ID, Modifier Group ID, Option Name, Price</p>
                <p><strong>Taxes:</strong> Tax ID, Tax Name, Rate</p>
              </div>

              <label className="flex flex-col items-center justify-center gap-3 p-8 border-2 border-dashed border-slate-200 rounded-xl cursor-pointer hover:border-blue-400 hover:bg-blue-50/30 transition-colors">
                <Upload size={32} className="text-slate-400" />
                <span className="text-sm font-medium text-slate-600">
                  Click to select .xlsx file
                </span>
                <input
                  ref={fileRef}
                  type="file"
                  accept=".xlsx,.xls"
                  className="hidden"
                  onChange={handleFile}
                />
              </label>
            </>
          )}

          {stage === "preview" && parsed && (
            <>
              <div className="flex items-center gap-3 p-3 bg-slate-50 rounded-xl">
                <FileSpreadsheet size={20} className="text-emerald-600" />
                <span className="text-sm font-medium text-slate-700 truncate">
                  {fileName}
                </span>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <SummaryCard label="Items" count={parsed.items.length} />
                <SummaryCard label="Categories" count={parsed.categories.length} />
                <SummaryCard label="Modifier Groups" count={parsed.modifierGroups.length} />
                <SummaryCard label="Modifier Options" count={parsed.modifierGroups.reduce((s, g) => s + g.options.length, 0)} />
                <SummaryCard label="Taxes" count={parsed.taxRates.length} />
              </div>

              <div className="p-3 bg-amber-50 border border-amber-200 rounded-xl">
                <div className="flex items-start gap-2">
                  <AlertTriangle
                    size={16}
                    className="text-amber-600 mt-0.5 flex-shrink-0"
                  />
                  <p className="text-xs text-amber-800">
                    This will <strong>replace</strong> all existing categories,
                    menu items, modifier groups, and taxes. Items will be linked
                    to modifiers and taxes by ID.
                  </p>
                </div>
              </div>

              <div className="flex gap-3">
                <button
                  onClick={reset}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors"
                >
                  Cancel
                </button>
                <button
                  onClick={handleImport}
                  className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
                >
                  Import to POS
                </button>
              </div>
            </>
          )}

          {stage === "importing" && (
            <div className="flex flex-col items-center gap-4 py-6">
              <Loader2 size={36} className="text-blue-600 animate-spin" />
              <p className="text-sm font-medium text-slate-700">
                {progress?.stage ?? "Starting import…"}
              </p>
              {progress && (
                <div className="w-full bg-slate-100 rounded-full h-2">
                  <div
                    className="bg-blue-600 h-2 rounded-full transition-all duration-300"
                    style={{
                      width: `${(progress.current / progress.total) * 100}%`,
                    }}
                  />
                </div>
              )}
              <p className="text-xs text-slate-400">
                Do not close this window
              </p>
            </div>
          )}

          {stage === "done" && result && (
            <div className="flex flex-col gap-4 py-4">
              <div className="flex flex-col items-center gap-3">
                <CheckCircle2 size={44} className="text-emerald-500" />
                <p className="text-base font-semibold text-slate-800">
                  Import complete
                </p>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <SummaryCard label="Items" count={result.items} success />
                <SummaryCard label="Categories" count={result.categories} success />
                <SummaryCard label="Modifier Groups" count={result.modifierGroups} success />
                <SummaryCard label="Modifier Options" count={result.modifierOptions} success />
                <SummaryCard label="Taxes" count={result.taxRates} success />
              </div>

              {result.logs.length > 0 && (
                <div className="bg-slate-50 rounded-xl border border-slate-200 overflow-hidden">
                  <div className="px-4 py-2.5 border-b border-slate-200 flex items-center gap-2">
                    <Info size={14} className="text-slate-400" />
                    <span className="text-xs font-medium text-slate-600">Import Log</span>
                    <span className="text-xs text-slate-400">
                      ({result.logs.filter((l) => l.level === "warn").length} warnings,{" "}
                      {result.logs.filter((l) => l.level === "error").length} errors)
                    </span>
                  </div>
                  <div className="max-h-48 overflow-y-auto p-3 space-y-1">
                    {result.logs
                      .filter((l) => l.level !== "info")
                      .map((entry, i) => (
                        <LogLine key={i} entry={entry} />
                      ))}
                    {result.logs.every((l) => l.level === "info") && (
                      <p className="text-xs text-emerald-600">All items imported successfully with no warnings.</p>
                    )}
                  </div>
                </div>
              )}

              <p className="text-xs text-slate-400 text-center">
                Your POS app will pick up the changes automatically
              </p>

              <button
                onClick={handleClose}
                className="w-full px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
              >
                Done
              </button>
            </div>
          )}

          {stage === "error" && (
            <div className="flex flex-col items-center gap-4 py-4">
              <AlertTriangle size={44} className="text-red-500" />
              <p className="text-base font-semibold text-slate-800">
                Import failed
              </p>
              <p className="text-sm text-red-600 text-center">{error}</p>
              <button
                onClick={reset}
                className="w-full px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors"
              >
                Try Again
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function SummaryCard({
  label,
  count,
  success,
}: {
  label: string;
  count: number;
  success?: boolean;
}) {
  return (
    <div
      className={`p-3 rounded-xl border text-center ${
        success
          ? "bg-emerald-50 border-emerald-200"
          : "bg-slate-50 border-slate-200"
      }`}
    >
      <p className="text-2xl font-bold text-slate-800">{count}</p>
      <p className="text-xs text-slate-500">{label}</p>
    </div>
  );
}

function LogLine({ entry }: { entry: ImportLogEntry }) {
  const color =
    entry.level === "error"
      ? "text-red-600"
      : entry.level === "warn"
      ? "text-amber-600"
      : "text-slate-500";
  const icon =
    entry.level === "error" ? "✕" : entry.level === "warn" ? "⚠" : "•";
  return (
    <p className={`text-xs ${color} flex items-start gap-1.5`}>
      <span className="flex-shrink-0 w-3 text-center">{icon}</span>
      <span>{entry.message}</span>
    </p>
  );
}
