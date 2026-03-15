"use client";

import { useCallback, useRef, useState } from "react";
import {
  X,
  Upload,
  FileSpreadsheet,
  CheckCircle2,
  AlertTriangle,
  Loader2,
} from "lucide-react";
import {
  parseCloverExcel,
  importMenuToFirestore,
  type ParsedMenu,
  type ImportProgress,
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
  const [result, setResult] = useState<Record<string, number> | null>(null);
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
      setError(
        err instanceof Error ? err.message : "Import failed"
      );
      setStage("error");
    }
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/40 backdrop-blur-sm"
        onClick={handleClose}
      />

      {/* Modal */}
      <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-lg mx-4 overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100">
          <h2 className="text-lg font-semibold text-slate-800">
            Upload Clover Menu
          </h2>
          <button
            onClick={handleClose}
            className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 transition-colors"
          >
            <X size={18} />
          </button>
        </div>

        {/* Body */}
        <div className="px-6 py-5 space-y-5">
          {/* ── File picker ── */}
          {stage === "pick" && (
            <>
              <p className="text-sm text-slate-500">
                Select a Clover inventory export file (.xlsx) containing{" "}
                <strong>Items</strong>, <strong>Categories</strong>,{" "}
                <strong>Modifier Groups</strong>, and{" "}
                <strong>Tax Rates</strong> sheets.
              </p>

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

          {/* ── Preview ── */}
          {stage === "preview" && parsed && (
            <>
              <div className="flex items-center gap-3 p-3 bg-slate-50 rounded-xl">
                <FileSpreadsheet size={20} className="text-emerald-600" />
                <span className="text-sm font-medium text-slate-700 truncate">
                  {fileName}
                </span>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <SummaryCard
                  label="Categories"
                  count={parsed.categories.length}
                />
                <SummaryCard label="Items" count={parsed.items.length} />
                <SummaryCard
                  label="Modifier Groups"
                  count={parsed.modifierGroups.length}
                />
                <SummaryCard
                  label="Tax Rates"
                  count={parsed.taxRates.length}
                />
              </div>

              <div className="p-3 bg-amber-50 border border-amber-200 rounded-xl">
                <div className="flex items-start gap-2">
                  <AlertTriangle
                    size={16}
                    className="text-amber-600 mt-0.5 flex-shrink-0"
                  />
                  <p className="text-xs text-amber-800">
                    This will <strong>replace</strong> all existing categories,
                    menu items, modifier groups, and taxes. The POS app will
                    update automatically via real-time listeners.
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

          {/* ── Importing ── */}
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

          {/* ── Done ── */}
          {stage === "done" && result && (
            <div className="flex flex-col items-center gap-4 py-4">
              <CheckCircle2 size={44} className="text-emerald-500" />
              <p className="text-base font-semibold text-slate-800">
                Import complete
              </p>

              <div className="grid grid-cols-2 gap-3 w-full">
                <SummaryCard
                  label="Categories"
                  count={result.categories}
                  success
                />
                <SummaryCard label="Items" count={result.items} success />
                <SummaryCard
                  label="Modifier Groups"
                  count={result.modifierGroups}
                  success
                />
                <SummaryCard
                  label="Tax Rates"
                  count={result.taxRates}
                  success
                />
              </div>

              <p className="text-xs text-slate-400">
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

          {/* ── Error ── */}
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
