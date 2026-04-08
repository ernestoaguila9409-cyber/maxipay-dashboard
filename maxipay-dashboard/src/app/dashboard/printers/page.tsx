"use client";

import { memo, useCallback, useEffect, useRef, useState } from "react";
import {
  addDoc,
  collection,
  doc,
  serverTimestamp,
  updateDoc,
} from "firebase/firestore";
import { db } from "@/firebase/firebaseConfig";
import Header from "@/components/Header";
import { useAuth } from "@/context/AuthContext";
import {
  usePrintersStatus,
  type PrinterSort,
  type PrinterViewRow,
  type PrinterFilter,
} from "@/hooks/usePrintersStatus";
import {
  PRINTER_STATUS_TICK_MS,
  type PrinterStatus,
} from "@/lib/printerStatusUtils";
import { Plus, X, FlaskConical, Pencil, Trash2, ClipboardList } from "lucide-react";
import { KitchenTicketStyleModal } from "@/components/KitchenTicketStyleModal";
import { deleteDoc } from "firebase/firestore";

/* ─── helpers ─── */

const IP_RE = /^(\d{1,3}\.){3}\d{1,3}$/;

function isValidIp(ip: string): boolean {
  if (!IP_RE.test(ip)) return false;
  return ip.split(".").every((o) => {
    const n = Number(o);
    return n >= 0 && n <= 255;
  });
}

function statusColor(s: PrinterStatus) {
  if (s === "ONLINE") return { bg: "bg-emerald-50", text: "text-emerald-800", dot: "bg-emerald-500" };
  if (s === "OFFLINE") return { bg: "bg-red-50", text: "text-red-800", dot: "bg-red-500" };
  return { bg: "bg-slate-100", text: "text-slate-600", dot: "bg-slate-400" };
}

function statusLabel(s: PrinterStatus) {
  if (s === "ONLINE") return "Online";
  if (s === "OFFLINE") return "Offline";
  return "Unknown";
}

/* ─── Add / Edit Modal ─── */

interface PrinterFormData {
  name: string;
  ipAddress: string;
  port: string;
  labels: string[];
}

function PrinterModal({
  open,
  editId,
  initial,
  onClose,
  onSave,
}: {
  open: boolean;
  editId: string | null;
  initial: PrinterFormData;
  onClose: () => void;
  onSave: (data: PrinterFormData) => Promise<void>;
}) {
  const [form, setForm] = useState<PrinterFormData>(initial);
  const [labelInput, setLabelInput] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const nameRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) {
      setForm(initial);
      setLabelInput("");
      setErrors({});
      setSaving(false);
      setTimeout(() => nameRef.current?.focus(), 50);
    }
  }, [open, initial]);

  const addLabel = () => {
    const v = labelInput.trim().toLowerCase();
    if (v && !form.labels.includes(v)) {
      setForm((f) => ({ ...f, labels: [...f.labels, v] }));
      setErrors((e) => ({ ...e, labels: "" }));
    }
    setLabelInput("");
  };

  const removeLabel = (label: string) => {
    setForm((f) => ({ ...f, labels: f.labels.filter((l) => l !== label) }));
  };

  const validate = (): boolean => {
    const e: Record<string, string> = {};
    if (!form.name.trim()) e.name = "Printer name is required";
    if (!form.ipAddress.trim()) e.ipAddress = "IP address is required";
    else if (!isValidIp(form.ipAddress.trim())) e.ipAddress = "Invalid IP address format";
    if (form.port.trim() && (isNaN(Number(form.port)) || Number(form.port) < 1 || Number(form.port) > 65535))
      e.port = "Port must be 1–65535";
    if (form.labels.length === 0) e.labels = "At least one label is required";
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const handleSubmit = async () => {
    if (!validate()) return;
    setSaving(true);
    try {
      await onSave(form);
      onClose();
    } catch (err) {
      console.error("[PrinterModal] save failed:", err);
      setErrors({ _form: "Failed to save. Please try again." });
    } finally {
      setSaving(false);
    }
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />
      <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-md mx-4 overflow-hidden">
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100">
          <h2 className="text-lg font-bold text-slate-900">
            {editId ? "Edit Printer" : "Add Printer"}
          </h2>
          <button onClick={onClose} className="p-1 rounded-lg hover:bg-slate-100 text-slate-400 hover:text-slate-600 transition-colors">
            <X size={20} />
          </button>
        </div>

        <div className="px-6 py-5 space-y-4">
          {errors._form && (
            <p className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded-lg">{errors._form}</p>
          )}

          {/* Name */}
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">
              Printer Name <span className="text-red-500">*</span>
            </label>
            <input
              ref={nameRef}
              type="text"
              value={form.name}
              onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
              placeholder="e.g. Kitchen Printer"
              className={`w-full px-3 py-2 rounded-lg border text-sm transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500/30 ${
                errors.name ? "border-red-300 bg-red-50" : "border-slate-200"
              }`}
            />
            {errors.name && <p className="text-xs text-red-600 mt-1">{errors.name}</p>}
          </div>

          {/* IP Address */}
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">
              IP Address <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={form.ipAddress}
              onChange={(e) => setForm((f) => ({ ...f, ipAddress: e.target.value }))}
              placeholder="e.g. 192.168.1.50"
              className={`w-full px-3 py-2 rounded-lg border text-sm font-mono transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500/30 ${
                errors.ipAddress ? "border-red-300 bg-red-50" : "border-slate-200"
              }`}
            />
            {errors.ipAddress && <p className="text-xs text-red-600 mt-1">{errors.ipAddress}</p>}
          </div>

          {/* Port */}
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">
              Port <span className="text-slate-400 font-normal">(default 9100)</span>
            </label>
            <input
              type="text"
              value={form.port}
              onChange={(e) => setForm((f) => ({ ...f, port: e.target.value }))}
              placeholder="9100"
              className={`w-full px-3 py-2 rounded-lg border text-sm font-mono transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500/30 ${
                errors.port ? "border-red-300 bg-red-50" : "border-slate-200"
              }`}
            />
            {errors.port && <p className="text-xs text-red-600 mt-1">{errors.port}</p>}
          </div>

          {/* Labels */}
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">
              Labels <span className="text-red-500">*</span>
            </label>
            <div className="flex gap-2">
              <input
                type="text"
                value={labelInput}
                onChange={(e) => setLabelInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addLabel(); } }}
                placeholder="e.g. kitchen, bar, receipt"
                className="flex-1 px-3 py-2 rounded-lg border border-slate-200 text-sm transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500/30"
              />
              <button
                type="button"
                onClick={addLabel}
                className="px-3 py-2 rounded-lg bg-slate-100 text-sm font-medium text-slate-700 hover:bg-slate-200 transition-colors"
              >
                Add
              </button>
            </div>
            {form.labels.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mt-2">
                {form.labels.map((label) => (
                  <span
                    key={label}
                    className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-blue-50 text-blue-700 text-xs font-medium"
                  >
                    {label}
                    <button
                      type="button"
                      onClick={() => removeLabel(label)}
                      className="hover:text-blue-900 transition-colors"
                    >
                      <X size={12} />
                    </button>
                  </span>
                ))}
              </div>
            )}
            {errors.labels && <p className="text-xs text-red-600 mt-1">{errors.labels}</p>}
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 px-6 py-4 border-t border-slate-100 bg-slate-50/60">
          <button
            type="button"
            onClick={onClose}
            disabled={saving}
            className="px-4 py-2 rounded-lg text-sm font-medium text-slate-600 hover:bg-slate-100 transition-colors"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={saving}
            className="px-5 py-2 rounded-lg text-sm font-semibold text-white bg-blue-600 hover:bg-blue-700 transition-colors disabled:opacity-60"
          >
            {saving ? "Saving…" : editId ? "Save Changes" : "Add Printer"}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ─── Delete confirmation ─── */

function DeleteConfirmModal({
  printerName,
  onConfirm,
  onClose,
}: {
  printerName: string;
  onConfirm: () => Promise<void>;
  onClose: () => void;
}) {
  const [deleting, setDeleting] = useState(false);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />
      <div className="relative bg-white rounded-2xl shadow-xl w-full max-w-sm mx-4 overflow-hidden">
        <div className="px-6 py-5 space-y-3">
          <h3 className="text-lg font-bold text-slate-900">Delete Printer</h3>
          <p className="text-sm text-slate-600">
            Are you sure you want to delete <strong>{printerName}</strong>? This action cannot be undone.
          </p>
        </div>
        <div className="flex items-center justify-end gap-2 px-6 py-4 border-t border-slate-100 bg-slate-50/60">
          <button
            type="button"
            onClick={onClose}
            disabled={deleting}
            className="px-4 py-2 rounded-lg text-sm font-medium text-slate-600 hover:bg-slate-100 transition-colors"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={deleting}
            onClick={async () => {
              setDeleting(true);
              try { await onConfirm(); } finally { setDeleting(false); }
            }}
            className="px-5 py-2 rounded-lg text-sm font-semibold text-white bg-red-600 hover:bg-red-700 transition-colors disabled:opacity-60"
          >
            {deleting ? "Deleting…" : "Delete"}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ─── Status Badge ─── */

function StatusBadge({ status }: { status: PrinterStatus }) {
  const c = statusColor(status);
  return (
    <span className={`inline-flex items-center gap-2 text-xs font-semibold px-2.5 py-1 rounded-full ${c.bg} ${c.text}`}>
      <span className={`w-2 h-2 rounded-full shrink-0 ${c.dot}`} aria-hidden />
      {statusLabel(status)}
    </span>
  );
}

/* ─── Table row ─── */

const PrinterTableRow = memo(function PrinterTableRow({
  row,
  onTest,
  onEdit,
  onDelete,
  onKitchenStyle,
  testing,
}: {
  row: PrinterViewRow;
  onTest: (id: string) => void;
  onEdit: (row: PrinterViewRow) => void;
  onDelete: (row: PrinterViewRow) => void;
  onKitchenStyle: (row: PrinterViewRow) => void;
  testing: boolean;
}) {
  return (
    <tr className="border-b border-slate-100 hover:bg-slate-50/80 transition-colors group">
      <td className="px-4 py-3.5 text-sm font-medium text-slate-900">{row.name}</td>
      <td className="px-4 py-3.5 text-sm font-mono text-slate-600 tabular-nums">{row.ipAddress}</td>
      <td className="px-4 py-3.5 text-sm font-mono text-slate-600 tabular-nums">{row.port}</td>
      <td className="px-4 py-3.5">
        <div className="flex flex-wrap gap-1">
          {row.labels.length > 0 ? row.labels.map((l) => (
            <span key={l} className="px-2 py-0.5 rounded-full bg-blue-50 text-blue-700 text-xs font-medium">{l}</span>
          )) : <span className="text-slate-400 text-xs">—</span>}
        </div>
      </td>
      <td className="px-4 py-3.5 text-sm text-slate-500">{row.lastSeenAgo}</td>
      <td className="px-4 py-3.5">
        <StatusBadge status={row.status} />
      </td>
      <td className="px-4 py-3.5">
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={() => onTest(row.id)}
            disabled={testing}
            title="Send test command to POS"
            className="p-1.5 rounded-lg text-slate-400 hover:text-blue-600 hover:bg-blue-50 transition-colors disabled:opacity-50"
          >
            <FlaskConical size={15} />
          </button>
          <button
            type="button"
            onClick={() => onKitchenStyle(row)}
            title="Kitchen ticket style"
            className="p-1.5 rounded-lg text-slate-400 hover:text-violet-600 hover:bg-violet-50 transition-colors opacity-0 group-hover:opacity-100"
          >
            <ClipboardList size={15} />
          </button>
          <button
            type="button"
            onClick={() => onEdit(row)}
            title="Edit printer"
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-700 hover:bg-slate-100 transition-colors opacity-0 group-hover:opacity-100"
          >
            <Pencil size={15} />
          </button>
          <button
            type="button"
            onClick={() => onDelete(row)}
            title="Delete printer"
            className="p-1.5 rounded-lg text-slate-400 hover:text-red-600 hover:bg-red-50 transition-colors opacity-0 group-hover:opacity-100"
          >
            <Trash2 size={15} />
          </button>
        </div>
      </td>
    </tr>
  );
});

/* ─── Mobile card ─── */

function PrinterCard({
  row,
  onTest,
  onEdit,
  onDelete,
  onKitchenStyle,
  testing,
}: {
  row: PrinterViewRow;
  onTest: (id: string) => void;
  onEdit: (row: PrinterViewRow) => void;
  onDelete: (row: PrinterViewRow) => void;
  onKitchenStyle: (row: PrinterViewRow) => void;
  testing: boolean;
}) {
  return (
    <div className="rounded-xl border border-slate-100 bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between gap-2">
        <h3 className="text-sm font-semibold text-slate-900">{row.name}</h3>
        <StatusBadge status={row.status} />
      </div>
      <dl className="mt-3 space-y-2 text-sm">
        <div className="flex justify-between gap-2">
          <dt className="text-slate-500">IP</dt>
          <dd className="font-mono text-slate-800 tabular-nums">{row.ipAddress}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-slate-500">Port</dt>
          <dd className="font-mono text-slate-800 tabular-nums">{row.port}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-slate-500">Labels</dt>
          <dd className="flex flex-wrap gap-1 justify-end">
            {row.labels.length > 0 ? row.labels.map((l) => (
              <span key={l} className="px-2 py-0.5 rounded-full bg-blue-50 text-blue-700 text-xs font-medium">{l}</span>
            )) : <span className="text-slate-400 text-xs">—</span>}
          </dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-slate-500">Last seen</dt>
          <dd className="text-slate-700">{row.lastSeenAgo}</dd>
        </div>
      </dl>
      <div className="flex items-center gap-2 mt-3 pt-3 border-t border-slate-100">
        <button
          type="button"
          onClick={() => onTest(row.id)}
          disabled={testing}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-blue-700 bg-blue-50 hover:bg-blue-100 transition-colors disabled:opacity-50"
        >
          <FlaskConical size={13} />
          Test
        </button>
        <button
          type="button"
          onClick={() => onKitchenStyle(row)}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-violet-700 bg-violet-50 hover:bg-violet-100 transition-colors"
        >
          <ClipboardList size={13} />
          Kitchen
        </button>
        <button
          type="button"
          onClick={() => onEdit(row)}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-slate-600 bg-slate-50 hover:bg-slate-100 transition-colors"
        >
          <Pencil size={13} />
          Edit
        </button>
        <button
          type="button"
          onClick={() => onDelete(row)}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-red-600 bg-red-50 hover:bg-red-100 transition-colors"
        >
          <Trash2 size={13} />
          Delete
        </button>
      </div>
    </div>
  );
}

/* ─── Page ─── */

const EMPTY_FORM: PrinterFormData = { name: "", ipAddress: "", port: "", labels: [] };

export default function PrintersPage() {
  const { user } = useAuth();
  const { printers, loading, error, filter, setFilter, sort, setSort } =
    usePrintersStatus(!!user);

  const [modalOpen, setModalOpen] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [modalInitial, setModalInitial] = useState<PrinterFormData>(EMPTY_FORM);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<PrinterViewRow | null>(null);
  const [kitchenStyleRow, setKitchenStyleRow] = useState<PrinterViewRow | null>(null);

  const openAdd = useCallback(() => {
    setEditId(null);
    setModalInitial(EMPTY_FORM);
    setModalOpen(true);
  }, []);

  const openEdit = useCallback((row: PrinterViewRow) => {
    setEditId(row.id);
    setModalInitial({
      name: row.name === "Unnamed printer" ? "" : row.name,
      ipAddress: row.ipAddress === "—" ? "" : row.ipAddress,
      port: row.port === 9100 ? "" : String(row.port),
      labels: [...row.labels],
    });
    setModalOpen(true);
  }, []);

  const handleSave = useCallback(
    async (data: PrinterFormData) => {
      const payload = {
        name: data.name.trim(),
        ipAddress: data.ipAddress.trim(),
        port: data.port.trim() ? Number(data.port) : 9100,
        labels: data.labels,
        updatedAt: serverTimestamp(),
      };

      if (editId) {
        await updateDoc(doc(db, "Printers", editId), payload);
      } else {
        await addDoc(collection(db, "Printers"), {
          ...payload,
          status: "UNKNOWN",
          lastSeen: null,
          createdAt: serverTimestamp(),
        });
      }
    },
    [editId]
  );

  const handleTest = useCallback(async (printerId: string) => {
    setTestingId(printerId);
    try {
      await addDoc(collection(db, "Printers", printerId, "commands"), {
        type: "testConnection",
        requestedAt: serverTimestamp(),
      });
    } catch (err) {
      console.error("[Printers] test command failed:", err);
    } finally {
      setTimeout(() => setTestingId(null), 1500);
    }
  }, []);

  const handleDelete = useCallback(async () => {
    if (!deleteTarget) return;
    await deleteDoc(doc(db, "Printers", deleteTarget.id));
    setDeleteTarget(null);
  }, [deleteTarget]);

  if (!user) {
    return (
      <>
        <Header title="Printers" />
        <div className="p-6 text-slate-500">Sign in to view printers.</div>
      </>
    );
  }

  return (
    <>
      <Header title="Printers" />
      <div className="p-4 sm:p-6 max-w-6xl mx-auto space-y-6">
        <div className="flex items-start justify-between gap-4">
          <p className="text-sm text-slate-600 max-w-2xl">
            Status is updated by POS devices. This page does{" "}
            <strong className="font-medium text-slate-800">not</strong> connect to printer IPs.
            Refreshes every {PRINTER_STATUS_TICK_MS / 1000}s.
          </p>
          <button
            type="button"
            onClick={openAdd}
            className="shrink-0 flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold text-white bg-blue-600 hover:bg-blue-700 transition-colors shadow-sm"
          >
            <Plus size={16} />
            Add Printer
          </button>
        </div>

        {error && (
          <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">
            {error}
          </div>
        )}

        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs font-medium text-slate-500 uppercase tracking-wide">Filter</span>
            {(
              [
                ["all", "All"],
                ["online", "Online"],
                ["offline", "Offline"],
              ] as const
            ).map(([value, label]) => (
              <button
                key={value}
                type="button"
                onClick={() => setFilter(value as PrinterFilter)}
                className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                  filter === value
                    ? "bg-blue-600 text-white"
                    : "bg-slate-100 text-slate-600 hover:bg-slate-200"
                }`}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs font-medium text-slate-500 uppercase tracking-wide">Sort</span>
            <select
              value={sort}
              onChange={(e) => setSort(e.target.value as PrinterSort)}
              className="text-sm border border-slate-200 rounded-lg px-3 py-2 bg-white text-slate-700 shadow-sm focus:outline-none focus:ring-2 focus:ring-blue-500/30"
              aria-label="Sort printers"
            >
              <option value="name">Name</option>
              <option value="status">Status (online first)</option>
            </select>
          </div>
        </div>

        {loading ? (
          <div className="flex items-center justify-center gap-3 py-16 text-slate-400">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-blue-600 rounded-full animate-spin" />
            Loading printers…
          </div>
        ) : printers.length === 0 ? (
          <div className="rounded-2xl border border-slate-100 bg-white p-10 text-center text-slate-500">
            {error
              ? "Could not load printers."
              : filter === "all"
                ? "No printers yet. Click \"Add Printer\" to create one."
                : `No ${filter} printers.`}
          </div>
        ) : (
          <>
            {/* Desktop table */}
            <div className="hidden md:block bg-white rounded-2xl border border-slate-100 shadow-sm overflow-hidden">
              <div className="overflow-x-auto">
                <table className="w-full text-left">
                  <thead>
                    <tr className="border-b border-slate-100 bg-slate-50/80">
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">Name</th>
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">IP Address</th>
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">Port</th>
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">Labels</th>
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">Last Seen</th>
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">Status</th>
                      <th className="px-4 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wider">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {printers.map((row) => (
                      <PrinterTableRow
                        key={row.id}
                        row={row}
                        onTest={handleTest}
                        onEdit={openEdit}
                        onDelete={setDeleteTarget}
                        onKitchenStyle={setKitchenStyleRow}
                        testing={testingId === row.id}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Mobile cards */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 md:hidden">
              {printers.map((row) => (
                <PrinterCard
                  key={row.id}
                  row={row}
                  onTest={handleTest}
                  onEdit={openEdit}
                  onDelete={setDeleteTarget}
                  onKitchenStyle={setKitchenStyleRow}
                  testing={testingId === row.id}
                />
              ))}
            </div>

            <p className="text-xs text-slate-400 text-center pt-2">
              Status updated by POS device
            </p>
          </>
        )}
      </div>

      <PrinterModal
        open={modalOpen}
        editId={editId}
        initial={modalInitial}
        onClose={() => setModalOpen(false)}
        onSave={handleSave}
      />

      {deleteTarget && (
        <DeleteConfirmModal
          printerName={deleteTarget.name}
          onConfirm={handleDelete}
          onClose={() => setDeleteTarget(null)}
        />
      )}

      <KitchenTicketStyleModal
        row={kitchenStyleRow}
        open={kitchenStyleRow != null}
        onClose={() => setKitchenStyleRow(null)}
      />
    </>
  );
}
