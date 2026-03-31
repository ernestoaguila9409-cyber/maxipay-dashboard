"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  X,
  Upload,
  FileSpreadsheet,
  CheckCircle2,
  AlertTriangle,
  Loader2,
  Info,
  ImageIcon,
  Trash2,
  Sparkles,
} from "lucide-react";
import { useAuth } from "@/context/AuthContext";
import {
  parseCloverExcel,
  importMenuToFirestore,
  importScannedMenuToFirestore,
  type ParsedMenu,
  type ImportProgress,
  type ImportResult,
  type ImportLogEntry,
  type ScannedMenuCategoryRow,
  type ScannedMenuItemRow,
  type ScannedMenuImportResult,
} from "@/lib/menuImporter";

interface Props {
  open: boolean;
  onClose: () => void;
  onImportComplete: () => void;
  /** Which tab to show when the modal opens (default: excel). */
  initialTab?: "excel" | "picture";
}

type Tab = "excel" | "picture";

type ExcelStage = "pick" | "preview" | "importing" | "done" | "error";
type PictureStage =
  | "pick"
  | "scanning"
  | "preview"
  | "importing"
  | "done"
  | "error";

function assignScanIds(data: {
  categories: Array<{
    name: string;
    items: Array<{
      name: string;
      price: number | null;
      priceUncertain?: boolean;
    }>;
  }>;
}): ScannedMenuCategoryRow[] {
  return data.categories.map((c) => ({
    id: crypto.randomUUID(),
    name: c.name,
    items: c.items.map(
      (it): ScannedMenuItemRow => ({
        id: crypto.randomUUID(),
        name: it.name,
        price: it.price,
        priceUncertain: it.priceUncertain,
      })
    ),
  }));
}

export default function MenuUploadModal({
  open,
  onClose,
  onImportComplete,
  initialTab = "excel",
}: Props) {
  const { user } = useAuth();
  const fileRef = useRef<HTMLInputElement>(null);
  const picInputRef = useRef<HTMLInputElement>(null);

  const [tab, setTab] = useState<Tab>(initialTab);

  const [excelStage, setExcelStage] = useState<ExcelStage>("pick");
  const [fileName, setFileName] = useState("");
  const [parsed, setParsed] = useState<ParsedMenu | null>(null);
  const [progress, setProgress] = useState<ImportProgress | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState("");

  const [pictureStage, setPictureStage] = useState<PictureStage>("pick");
  const [pictureFileName, setPictureFileName] = useState("");
  const [scanCategories, setScanCategories] = useState<
    ScannedMenuCategoryRow[] | null
  >(null);
  const [scanRawText, setScanRawText] = useState("");
  const [picProgress, setPicProgress] = useState<ImportProgress | null>(null);
  const [picResult, setPicResult] = useState<ScannedMenuImportResult | null>(
    null
  );
  const [picError, setPicError] = useState("");
  const [reprocessing, setReprocessing] = useState(false);
  const [dragOver, setDragOver] = useState(false);

  const resetExcel = useCallback(() => {
    setExcelStage("pick");
    setFileName("");
    setParsed(null);
    setProgress(null);
    setResult(null);
    setError("");
    if (fileRef.current) fileRef.current.value = "";
  }, []);

  const resetPicture = useCallback(() => {
    setPictureStage("pick");
    setPictureFileName("");
    setScanCategories(null);
    setScanRawText("");
    setPicProgress(null);
    setPicResult(null);
    setPicError("");
    setReprocessing(false);
    if (picInputRef.current) picInputRef.current.value = "";
  }, []);

  const reset = useCallback(() => {
    resetExcel();
    resetPicture();
    setTab("excel");
  }, [resetExcel, resetPicture]);

  useEffect(() => {
    if (!open) return;
    resetExcel();
    resetPicture();
    setTab(initialTab);
  }, [open, initialTab, resetExcel, resetPicture]);

  const handleClose = () => {
    reset();
    onClose();
  };

  const handleTabChange = (t: Tab) => {
    if (t === tab) return;
    if (t === "excel") resetPicture();
    else resetExcel();
    setTab(t);
  };

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setFileName(file.name);

    try {
      const buffer = await file.arrayBuffer();
      const data = parseCloverExcel(buffer);
      setParsed(data);
      setExcelStage("preview");
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to parse Excel file"
      );
      setExcelStage("error");
    }
  };

  const handleImport = async () => {
    if (!parsed) return;

    setExcelStage("importing");
    try {
      const res = await importMenuToFirestore(parsed, setProgress);
      setResult(res);
      setExcelStage("done");
      onImportComplete();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Import failed");
      setExcelStage("error");
    }
  };

  const callScanApi = async (body: FormData | string) => {
    if (!user) throw new Error("You must be signed in.");
    const token = await user.getIdToken();
    const isJson = typeof body === "string";
    const res = await fetch("/api/menu/scan", {
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
      categories?: Array<{
        name: string;
        items: Array<{
          name: string;
          price: number | null;
          priceUncertain?: boolean;
        }>;
      }>;
      rawText?: string;
    };
    if (!res.ok || !data.success) {
      throw new Error(data.error || "Scan failed");
    }
    if (!data.categories) throw new Error("Invalid scan response");
    return data;
  };

  const runPictureScan = async (file: File) => {
    setPictureFileName(file.name);
    setPicError("");
    setPictureStage("scanning");
    try {
      const fd = new FormData();
      fd.append("file", file);
      const data = await callScanApi(fd);
      setScanRawText(data.rawText ?? "");
      setScanCategories(assignScanIds({ categories: data.categories! }));
      setPictureStage("preview");
    } catch (err) {
      setPicError(err instanceof Error ? err.message : "Scan failed");
      setPictureStage("error");
    }
  };

  const onPictureInput = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    runPictureScan(file);
  };

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files?.[0];
    if (!file) return;
    if (!/^image\/(jpeg|jpg|png)$/i.test(file.type)) {
      setPicError("Please use a JPG or PNG image.");
      setPictureStage("error");
      return;
    }
    runPictureScan(file);
  };

  const handleImproveScan = async () => {
    if (!scanRawText.trim()) return;
    setReprocessing(true);
    setPicError("");
    try {
      const data = await callScanApi(
        JSON.stringify({ text: scanRawText, reprocess: true })
      );
      setScanCategories(assignScanIds({ categories: data.categories! }));
    } catch (err) {
      setPicError(err instanceof Error ? err.message : "Reprocess failed");
    } finally {
      setReprocessing(false);
    }
  };

  const updateItemName = (
    catId: string,
    itemId: string,
    name: string
  ) => {
    setScanCategories((prev) => {
      if (!prev) return prev;
      return prev.map((c) =>
        c.id !== catId
          ? c
          : {
              ...c,
              items: c.items.map((it) =>
                it.id === itemId ? { ...it, name } : it
              ),
            }
      );
    });
  };

  const updateItemPrice = (
    catId: string,
    itemId: string,
    priceStr: string
  ) => {
    const n = parseFloat(priceStr.replace(/[^0-9.]/g, ""));
    const price = Number.isNaN(n) ? null : Math.max(0, n);
    setScanCategories((prev) => {
      if (!prev) return prev;
      return prev.map((c) =>
        c.id !== catId
          ? c
          : {
              ...c,
              items: c.items.map((it) =>
                it.id === itemId
                  ? { ...it, price, priceUncertain: false }
                  : it
              ),
            }
      );
    });
  };

  const deleteItem = (catId: string, itemId: string) => {
    setScanCategories((prev) => {
      if (!prev) return prev;
      return prev
        .map((c) =>
          c.id !== catId
            ? c
            : { ...c, items: c.items.filter((it) => it.id !== itemId) }
        )
        .filter((c) => c.items.length > 0 || c.name.trim() !== "");
    });
  };

  const handleConfirmScanImport = async () => {
    if (!scanCategories?.length) return;
    const cleaned = scanCategories
      .map((c) => ({
        ...c,
        items: c.items.filter((it) => it.name.trim()),
      }))
      .filter((c) => c.name.trim() && c.items.length > 0);
    if (cleaned.length === 0) {
      setPicError("Add at least one category with items.");
      return;
    }

    setPictureStage("importing");
    setPicError("");
    try {
      const res = await importScannedMenuToFirestore(cleaned, setPicProgress);
      setPicResult(res);
      setPictureStage("done");
      onImportComplete();
    } catch (err) {
      setPicError(err instanceof Error ? err.message : "Import failed");
      setPictureStage("error");
    }
  };

  if (!open) return null;

  const widePreview =
    tab === "picture" &&
    (pictureStage === "preview" ||
      pictureStage === "importing" ||
      pictureStage === "error");

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div
        className="absolute inset-0 bg-black/40 backdrop-blur-sm"
        onClick={handleClose}
      />

      <div
        className={`relative bg-white rounded-2xl shadow-xl w-full mx-4 overflow-hidden max-h-[90vh] overflow-y-auto ${
          widePreview ? "max-w-2xl" : "max-w-lg"
        }`}
      >
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100">
          <h2 className="text-lg font-semibold text-slate-800">Upload Menu</h2>
          <button
            onClick={handleClose}
            className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 transition-colors"
          >
            <X size={18} />
          </button>
        </div>

        <div className="px-6 pt-4">
          <div className="flex rounded-xl bg-slate-100 p-1 mb-2">
            <button
              type="button"
              onClick={() => handleTabChange("excel")}
              className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                tab === "excel"
                  ? "bg-white text-slate-800 shadow-sm"
                  : "text-slate-500 hover:text-slate-700"
              }`}
            >
              <FileSpreadsheet size={16} />
              Upload Excel
            </button>
            <button
              type="button"
              onClick={() => handleTabChange("picture")}
              className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                tab === "picture"
                  ? "bg-white text-slate-800 shadow-sm"
                  : "text-slate-500 hover:text-slate-700"
              }`}
            >
              <ImageIcon size={16} />
              Upload Picture
            </button>
          </div>
        </div>

        <div className="px-6 py-5 space-y-5">
          {tab === "excel" && (
            <>
              {excelStage === "pick" && (
                <>
                  <p className="text-sm text-slate-500">
                    Select an Excel file (.xlsx) with sheets:{" "}
                    <strong>Items</strong>, <strong>Categories</strong>,{" "}
                    <strong>Modifier Groups</strong>,{" "}
                    <strong>Modifier Options</strong>, and <strong>Taxes</strong>
                    .
                  </p>

                  <div className="bg-slate-50 rounded-xl p-4 text-xs text-slate-500 space-y-2">
                    <p className="font-medium text-slate-600">
                      Expected sheet columns (Clover format):
                    </p>
                    <p>
                      <strong>Items:</strong> Item ID, Name, Price, Category
                      ID, Modifier Group IDs, Tax IDs, Order Types
                    </p>
                    <p>
                      <strong>Categories:</strong> Category ID, Category Name
                    </p>
                    <p>
                      <strong>Modifier Groups:</strong> Modifier Group ID,
                      Modifier Group Name
                    </p>
                    <p>
                      <strong>Modifier Options:</strong> Option ID, Modifier
                      Group ID, Option Name, Price
                    </p>
                    <p>
                      <strong>Taxes:</strong> Tax ID, Tax Name, Rate
                    </p>
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

              {excelStage === "preview" && parsed && (
                <>
                  <div className="flex items-center gap-3 p-3 bg-slate-50 rounded-xl">
                    <FileSpreadsheet size={20} className="text-emerald-600" />
                    <span className="text-sm font-medium text-slate-700 truncate">
                      {fileName}
                    </span>
                  </div>

                  <div className="grid grid-cols-2 gap-3">
                    <SummaryCard label="Items" count={parsed.items.length} />
                    <SummaryCard
                      label="Categories"
                      count={parsed.categories.length}
                    />
                    <SummaryCard
                      label="Modifier Groups"
                      count={parsed.modifierGroups.length}
                    />
                    <SummaryCard
                      label="Modifier Options"
                      count={parsed.modifierGroups.reduce(
                        (s, g) => s + g.options.length,
                        0
                      )}
                    />
                    <SummaryCard
                      label="Taxes"
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
                        This will <strong>replace</strong> all existing
                        categories, menu items, modifier groups, and taxes. Items
                        will be linked to modifiers and taxes by ID.
                      </p>
                    </div>
                  </div>

                  <div className="flex gap-3">
                    <button
                      onClick={resetExcel}
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

              {excelStage === "importing" && (
                <div className="flex flex-col items-center gap-4 py-6">
                  <Loader2
                    size={36}
                    className="text-blue-600 animate-spin"
                  />
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

              {excelStage === "done" && result && (
                <div className="flex flex-col gap-4 py-4">
                  <div className="flex flex-col items-center gap-3">
                    <CheckCircle2 size={44} className="text-emerald-500" />
                    <p className="text-base font-semibold text-slate-800">
                      Import complete
                    </p>
                  </div>

                  <div className="grid grid-cols-2 gap-3">
                    <SummaryCard
                      label="Items"
                      count={result.items}
                      success
                    />
                    <SummaryCard
                      label="Categories"
                      count={result.categories}
                      success
                    />
                    <SummaryCard
                      label="Modifier Groups"
                      count={result.modifierGroups}
                      success
                    />
                    <SummaryCard
                      label="Modifier Options"
                      count={result.modifierOptions}
                      success
                    />
                    <SummaryCard
                      label="Taxes"
                      count={result.taxRates}
                      success
                    />
                  </div>

                  {result.logs.length > 0 && (
                    <div className="bg-slate-50 rounded-xl border border-slate-200 overflow-hidden">
                      <div className="px-4 py-2.5 border-b border-slate-200 flex items-center gap-2">
                        <Info size={14} className="text-slate-400" />
                        <span className="text-xs font-medium text-slate-600">
                          Import Log
                        </span>
                        <span className="text-xs text-slate-400">
                          ({result.logs.filter((l) => l.level === "warn").length}{" "}
                          warnings,{" "}
                          {result.logs.filter((l) => l.level === "error").length}{" "}
                          errors)
                        </span>
                      </div>
                      <div className="max-h-48 overflow-y-auto p-3 space-y-1">
                        {result.logs
                          .filter((l) => l.level !== "info")
                          .map((entry, i) => (
                            <LogLine key={i} entry={entry} />
                          ))}
                        {result.logs.every((l) => l.level === "info") && (
                          <p className="text-xs text-emerald-600">
                            All items imported successfully with no warnings.
                          </p>
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

              {excelStage === "error" && (
                <div className="flex flex-col items-center gap-4 py-4">
                  <AlertTriangle size={44} className="text-red-500" />
                  <p className="text-base font-semibold text-slate-800">
                    Import failed
                  </p>
                  <p className="text-sm text-red-600 text-center">{error}</p>
                  <button
                    onClick={resetExcel}
                    className="w-full px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors"
                  >
                    Try Again
                  </button>
                </div>
              )}
            </>
          )}

          {tab === "picture" && (
            <>
              {pictureStage === "pick" && (
                <>
                  <p className="text-sm text-slate-500">
                    Upload a picture of your menu and we&apos;ll automatically
                    create your items. Use a clear, well-lit photo (JPG or PNG).
                  </p>

                  <div
                    role="presentation"
                    onDragOver={(e) => {
                      e.preventDefault();
                      setDragOver(true);
                    }}
                    onDragLeave={() => setDragOver(false)}
                    onDrop={onDrop}
                    className={`rounded-xl border-2 border-dashed transition-colors ${
                      dragOver
                        ? "border-blue-400 bg-blue-50/50"
                        : "border-slate-200"
                    }`}
                  >
                    <label className="flex flex-col items-center justify-center gap-3 p-10 cursor-pointer hover:bg-slate-50/80 rounded-xl">
                      <ImageIcon size={36} className="text-slate-400" />
                      <span className="text-sm font-medium text-slate-600 text-center px-2">
                        Drag &amp; drop your menu image here, or click to browse
                      </span>
                      <span className="text-xs text-slate-400">
                        JPG or PNG · max size depends on server limits
                      </span>
                      <input
                        ref={picInputRef}
                        type="file"
                        accept="image/jpeg,image/jpg,image/png"
                        className="hidden"
                        onChange={onPictureInput}
                      />
                    </label>
                  </div>
                </>
              )}

              {pictureStage === "scanning" && (
                <div className="flex flex-col items-center gap-4 py-10">
                  <Loader2
                    size={40}
                    className="text-blue-600 animate-spin"
                  />
                  <p className="text-sm font-medium text-slate-700">
                    Scanning menu…
                  </p>
                  {pictureFileName && (
                    <p className="text-xs text-slate-400 truncate max-w-full">
                      {pictureFileName}
                    </p>
                  )}
                </div>
              )}

              {pictureStage === "preview" && scanCategories && (
                <>
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <p className="text-sm text-slate-600">
                      Review and edit items before importing. Nothing is saved
                      until you confirm.
                    </p>
                    <button
                      type="button"
                      onClick={handleImproveScan}
                      disabled={reprocessing || !scanRawText.trim()}
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 text-xs font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-50"
                    >
                      {reprocessing ? (
                        <Loader2 size={14} className="animate-spin" />
                      ) : (
                        <Sparkles size={14} />
                      )}
                      Improve scan
                    </button>
                  </div>

                  <div className="space-y-6 max-h-[50vh] overflow-y-auto pr-1">
                    {scanCategories.map((cat) => (
                      <div
                        key={cat.id}
                        className="border border-slate-200 rounded-xl overflow-hidden"
                      >
                        <div className="px-4 py-2.5 bg-slate-50 border-b border-slate-200">
                          <p className="text-sm font-semibold text-slate-800">
                            {cat.name}
                          </p>
                        </div>
                        <ul className="divide-y divide-slate-100">
                          {cat.items.map((it) => (
                            <li
                              key={it.id}
                              className="p-3 flex flex-col sm:flex-row gap-2 sm:items-center"
                            >
                              <input
                                type="text"
                                value={it.name}
                                onChange={(e) =>
                                  updateItemName(cat.id, it.id, e.target.value)
                                }
                                className="flex-1 min-w-0 px-3 py-2 rounded-lg border border-slate-200 text-sm"
                              />
                              <div className="flex items-center gap-2 shrink-0">
                                <span className="text-slate-400 text-sm">$</span>
                                <input
                                  type="text"
                                  inputMode="decimal"
                                  placeholder="0.00"
                                  value={
                                    it.price == null ? "" : String(it.price)
                                  }
                                  onChange={(e) =>
                                    updateItemPrice(
                                      cat.id,
                                      it.id,
                                      e.target.value
                                    )
                                  }
                                  className={`w-24 px-2 py-2 rounded-lg border text-sm ${
                                    it.priceUncertain
                                      ? "border-amber-400 bg-amber-50/50"
                                      : "border-slate-200"
                                  }`}
                                />
                                <button
                                  type="button"
                                  onClick={() => deleteItem(cat.id, it.id)}
                                  className="p-2 rounded-lg text-slate-400 hover:text-red-600 hover:bg-red-50"
                                  title="Remove item"
                                >
                                  <Trash2 size={18} />
                                </button>
                              </div>
                            </li>
                          ))}
                        </ul>
                      </div>
                    ))}
                  </div>

                  {picError && (
                    <p className="text-sm text-red-600">{picError}</p>
                  )}

                  <div className="flex flex-col gap-2">
                    <p className="text-xs text-slate-400">
                      Yellow price fields may need verification. New items are
                      added alongside your current menu (nothing is replaced
                      automatically).
                    </p>
                    <div className="flex gap-3">
                      <button
                        type="button"
                        onClick={resetPicture}
                        className="flex-1 px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors"
                      >
                        Cancel
                      </button>
                      <button
                        type="button"
                        onClick={handleConfirmScanImport}
                        className="flex-1 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
                      >
                        Confirm &amp; Import Menu
                      </button>
                    </div>
                  </div>
                </>
              )}

              {pictureStage === "importing" && (
                <div className="flex flex-col items-center gap-4 py-8">
                  <Loader2
                    size={36}
                    className="text-blue-600 animate-spin"
                  />
                  <p className="text-sm font-medium text-slate-700">
                    {picProgress?.stage ?? "Importing…"}
                  </p>
                </div>
              )}

              {pictureStage === "done" && picResult && (
                <div className="flex flex-col gap-4 py-4">
                  <div className="flex flex-col items-center gap-3">
                    <CheckCircle2 size={44} className="text-emerald-500" />
                    <p className="text-base font-semibold text-slate-800">
                      Menu imported
                    </p>
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <SummaryCard
                      label="Categories added"
                      count={picResult.categories}
                      success
                    />
                    <SummaryCard
                      label="Items added"
                      count={picResult.items}
                      success
                    />
                  </div>
                  <p className="text-xs text-slate-400 text-center">
                    Existing menu items were not modified.
                  </p>
                  <button
                    onClick={handleClose}
                    className="w-full px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 transition-colors"
                  >
                    Done
                  </button>
                </div>
              )}

              {pictureStage === "error" && (
                <div className="flex flex-col items-center gap-4 py-4">
                  <AlertTriangle size={44} className="text-red-500" />
                  <p className="text-base font-semibold text-slate-800">
                    Something went wrong
                  </p>
                  <p className="text-sm text-red-600 text-center">
                    {picError || "Please try again."}
                  </p>
                  <button
                    onClick={resetPicture}
                    className="w-full px-4 py-2.5 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors"
                  >
                    Try Again
                  </button>
                </div>
              )}
            </>
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
