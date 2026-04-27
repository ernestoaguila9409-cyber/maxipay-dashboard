"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import {
  collection,
  deleteDoc,
  doc,
  onSnapshot,
  serverTimestamp,
  setDoc,
  writeBatch,
} from "firebase/firestore";
import {
  deleteObject,
  getDownloadURL,
  ref as storageRef,
  uploadBytes,
} from "firebase/storage";
import { GripVertical, ImagePlus, Loader2, Plus, Trash2 } from "lucide-react";
import { db, storage } from "@/firebase/firebaseConfig";
import { resizeImageToBlob } from "@/lib/imageUpload";
import {
  DEFAULT_HERO_CTA,
  HERO_SLIDES_COLLECTION,
  HERO_SLIDES_MAX,
  HERO_STORAGE_PREFIX,
  parseHeroSlide,
  type HeroActionType,
  type HeroSlide,
} from "@/lib/storefrontShared";
import type { OnlineMenuCategory, OnlineMenuItem } from "@/lib/onlineOrderingServer";

interface HeroCarouselManagerProps {
  /** Categories admin can target with hero CTAs. */
  categories: OnlineMenuCategory[];
  /** Items admin can target with hero CTAs. */
  items: OnlineMenuItem[];
}

function newSlideId(): string {
  return `slide_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

export default function HeroCarouselManager({ categories, items }: HeroCarouselManagerProps) {
  const [slides, setSlides] = useState<HeroSlide[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [savingId, setSavingId] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  useEffect(() => {
    const unsub = onSnapshot(
      collection(db, HERO_SLIDES_COLLECTION),
      (snap) => {
        const list = snap.docs.map((d) =>
          parseHeroSlide(d.id, d.data() as Record<string, unknown>)
        );
        list.sort((a, b) => a.order - b.order);
        setSlides(list);
        setLoading(false);
      },
      (e) => {
        console.error("[hero-slides]", e);
        setError(e.message);
        setLoading(false);
      }
    );
    return () => unsub();
  }, []);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const onDragEnd = useCallback(
    async (e: DragEndEvent) => {
      const { active, over } = e;
      if (!over || active.id === over.id) return;
      const oldIndex = slides.findIndex((s) => s.id === active.id);
      const newIndex = slides.findIndex((s) => s.id === over.id);
      if (oldIndex < 0 || newIndex < 0) return;
      const next = arrayMove(slides, oldIndex, newIndex);
      setSlides(next);
      try {
        const batch = writeBatch(db);
        next.forEach((s, i) => {
          batch.set(
            doc(db, HERO_SLIDES_COLLECTION, s.id),
            { order: i, updatedAt: serverTimestamp() },
            { merge: true }
          );
        });
        await batch.commit();
      } catch (err) {
        console.error("[hero-slides] reorder", err);
        setError("Could not save the new order. Try again.");
      }
    },
    [slides]
  );

  const updateSlide = useCallback(
    async (id: string, patch: Partial<HeroSlide>) => {
      setSavingId(id);
      try {
        await setDoc(
          doc(db, HERO_SLIDES_COLLECTION, id),
          { ...patch, updatedAt: serverTimestamp() },
          { merge: true }
        );
      } catch (err) {
        console.error("[hero-slides] update", err);
        setError("Could not save changes. Try again.");
      } finally {
        setSavingId(null);
      }
    },
    []
  );

  const removeSlide = useCallback(
    async (s: HeroSlide) => {
      if (!confirm("Delete this slide? This also removes the image from storage.")) return;
      setSavingId(s.id);
      try {
        if (s.storagePath) {
          try {
            await deleteObject(storageRef(storage, s.storagePath));
          } catch {
            // Object may already be gone (e.g. manual cleanup). Continue with Firestore delete.
          }
        }
        await deleteDoc(doc(db, HERO_SLIDES_COLLECTION, s.id));
      } catch (err) {
        console.error("[hero-slides] delete", err);
        setError("Could not delete slide.");
      } finally {
        setSavingId(null);
      }
    },
    []
  );

  const addSlide = useCallback(async () => {
    if (slides.length >= HERO_SLIDES_MAX) {
      setError(`You can only have up to ${HERO_SLIDES_MAX} slides.`);
      return;
    }
    setAdding(true);
    setError(null);
    try {
      const id = newSlideId();
      await setDoc(doc(db, HERO_SLIDES_COLLECTION, id), {
        imageUrl: "",
        storagePath: "",
        title: "",
        subtitle: "",
        ctaLabel: DEFAULT_HERO_CTA,
        actionType: "NONE",
        actionValue: "",
        order: slides.length,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });
    } catch (err) {
      console.error("[hero-slides] create", err);
      setError("Could not create slide.");
    } finally {
      setAdding(false);
    }
  }, [slides.length]);

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-slate-900">Hero carousel</h2>
          <p className="text-sm text-slate-500 mt-0.5">
            Up to {HERO_SLIDES_MAX} promo slides at the top of your storefront. Drag to reorder.
          </p>
        </div>
        <button
          type="button"
          onClick={addSlide}
          disabled={adding || slides.length >= HERO_SLIDES_MAX}
          className="inline-flex items-center gap-2 px-3.5 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:bg-blue-300"
        >
          {adding ? <Loader2 size={16} className="animate-spin" /> : <Plus size={16} />}
          Add slide
        </button>
      </div>

      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
          {error}
        </p>
      )}

      {loading ? (
        <div className="flex items-center gap-2 text-slate-500 py-6">
          <Loader2 className="animate-spin" size={18} /> Loading slides…
        </div>
      ) : slides.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-300 bg-slate-50 p-8 text-center text-slate-500">
          <ImagePlus className="mx-auto text-slate-400 mb-2" size={32} />
          <p className="text-sm">
            No slides yet. Click <span className="font-medium">Add slide</span> to get started.
          </p>
        </div>
      ) : (
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
          <SortableContext items={slides.map((s) => s.id)} strategy={verticalListSortingStrategy}>
            <ul className="space-y-3">
              {slides.map((s, i) => (
                <SortableSlideCard
                  key={s.id}
                  slide={s}
                  index={i}
                  saving={savingId === s.id}
                  categories={categories}
                  items={items}
                  onChange={(patch) => void updateSlide(s.id, patch)}
                  onRemove={() => void removeSlide(s)}
                />
              ))}
            </ul>
          </SortableContext>
        </DndContext>
      )}
    </div>
  );
}

/* ─────────────────────────────────────────────
   Sortable card
   ───────────────────────────────────────────── */

function SortableSlideCard(props: {
  slide: HeroSlide;
  index: number;
  saving: boolean;
  categories: OnlineMenuCategory[];
  items: OnlineMenuItem[];
  onChange: (patch: Partial<HeroSlide>) => void;
  onRemove: () => void;
}) {
  const { slide, index, saving, categories, items, onChange, onRemove } = props;
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: slide.id,
  });
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.65 : 1,
  };

  return (
    <li
      ref={setNodeRef}
      style={style}
      className={`bg-white rounded-2xl border ${
        isDragging ? "border-blue-300 shadow-lg" : "border-slate-200"
      } shadow-sm overflow-hidden`}
    >
      <div className="flex flex-col md:flex-row">
        {/* Drag handle + index */}
        <div className="flex md:flex-col items-center justify-between md:justify-start gap-2 px-3 py-3 bg-slate-50 md:bg-slate-50/60 border-b md:border-b-0 md:border-r border-slate-100 md:w-12">
          <button
            type="button"
            className="text-slate-400 hover:text-slate-700 cursor-grab active:cursor-grabbing"
            aria-label="Drag to reorder"
            {...attributes}
            {...listeners}
          >
            <GripVertical size={18} />
          </button>
          <span className="text-xs font-semibold text-slate-400 tabular-nums">{index + 1}</span>
          {saving && <Loader2 size={14} className="animate-spin text-slate-400" />}
        </div>

        <SlideEditor
          slide={slide}
          categories={categories}
          items={items}
          onChange={onChange}
          onRemove={onRemove}
        />
      </div>
    </li>
  );
}

/* ─────────────────────────────────────────────
   Inline editor: image upload + fields + action picker
   ───────────────────────────────────────────── */

function SlideEditor({
  slide,
  categories,
  items,
  onChange,
  onRemove,
}: {
  slide: HeroSlide;
  categories: OnlineMenuCategory[];
  items: OnlineMenuItem[];
  onChange: (patch: Partial<HeroSlide>) => void;
  onRemove: () => void;
}) {
  const [titleDraft, setTitleDraft] = useState(slide.title);
  const [subtitleDraft, setSubtitleDraft] = useState(slide.subtitle);
  const [ctaDraft, setCtaDraft] = useState(slide.ctaLabel);
  const [actionTypeDraft, setActionTypeDraft] = useState<HeroActionType>(slide.actionType);
  const [actionValueDraft, setActionValueDraft] = useState(slide.actionValue);
  const [urlDraft, setUrlDraft] = useState(slide.actionType === "URL" ? slide.actionValue : "");
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Keep drafts in sync if a Firestore snapshot changes the slide externally.
  useEffect(() => setTitleDraft(slide.title), [slide.title]);
  useEffect(() => setSubtitleDraft(slide.subtitle), [slide.subtitle]);
  useEffect(() => setCtaDraft(slide.ctaLabel), [slide.ctaLabel]);
  useEffect(() => setActionTypeDraft(slide.actionType), [slide.actionType]);
  useEffect(() => setActionValueDraft(slide.actionValue), [slide.actionValue]);
  useEffect(() => {
    if (slide.actionType === "URL") setUrlDraft(slide.actionValue);
  }, [slide.actionType, slide.actionValue]);

  const handleFile = useCallback(
    async (file: File) => {
      setUploadError(null);
      setUploading(true);
      try {
        const blob = await resizeImageToBlob(file, { maxEdge: 1600, mimeType: "image/jpeg" });
        const path = `${HERO_STORAGE_PREFIX}/${slide.id}.jpg`;
        const sref = storageRef(storage, path);
        await uploadBytes(sref, blob, { contentType: "image/jpeg" });
        const url = await getDownloadURL(sref);
        onChange({ imageUrl: url, storagePath: path });
      } catch (e) {
        const msg = e instanceof Error ? e.message : "Upload failed";
        setUploadError(msg);
      } finally {
        setUploading(false);
      }
    },
    [slide.id, onChange]
  );

  const onFilePicked = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      e.target.value = "";
      if (file) void handleFile(file);
    },
    [handleFile]
  );

  const onPickActionType = useCallback(
    (t: HeroActionType) => {
      setActionTypeDraft(t);
      // Reset action value when switching to NONE so we don't leave dangling references.
      if (t === "NONE") {
        setActionValueDraft("");
        onChange({ actionType: "NONE", actionValue: "" });
      } else if (t === "URL") {
        setActionValueDraft(urlDraft);
        onChange({ actionType: "URL", actionValue: urlDraft });
      } else {
        // For CATEGORY / ITEM, keep blank until the user picks something.
        setActionValueDraft("");
        onChange({ actionType: t, actionValue: "" });
      }
    },
    [onChange, urlDraft]
  );

  const targetOptions = useMemo(() => {
    if (actionTypeDraft === "CATEGORY") {
      return categories.map((c) => ({ id: c.id, label: c.name }));
    }
    if (actionTypeDraft === "ITEM") {
      return items
        .map((it) => ({ id: it.id, label: it.name }))
        .sort((a, b) => a.label.localeCompare(b.label));
    }
    return [] as { id: string; label: string }[];
  }, [actionTypeDraft, categories, items]);

  return (
    <div className="flex-1 p-4 grid grid-cols-1 lg:grid-cols-[180px_1fr] gap-4">
      {/* Image */}
      <div>
        <div
          className={`relative w-full aspect-[16/9] lg:aspect-square rounded-xl overflow-hidden border border-slate-200 ${
            slide.imageUrl ? "bg-neutral-900" : "bg-slate-50"
          }`}
        >
          {slide.imageUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={slide.imageUrl} alt="" className="w-full h-full object-cover" />
          ) : (
            <div className="w-full h-full grid place-items-center text-slate-400">
              <div className="flex flex-col items-center gap-1.5">
                <ImagePlus size={26} />
                <span className="text-xs">No image</span>
              </div>
            </div>
          )}
          {uploading && (
            <div className="absolute inset-0 bg-black/40 grid place-items-center text-white text-xs gap-1">
              <Loader2 size={20} className="animate-spin" />
              Uploading…
            </div>
          )}
        </div>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          className="hidden"
          onChange={onFilePicked}
        />
        <button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          disabled={uploading}
          className="mt-2 w-full inline-flex items-center justify-center gap-1.5 rounded-lg border border-slate-200 bg-white text-slate-700 hover:bg-slate-50 text-xs font-medium px-3 py-1.5 disabled:opacity-50"
        >
          {slide.imageUrl ? "Replace image" : "Upload image"}
        </button>
        {uploadError && <p className="text-[11px] text-red-600 mt-1">{uploadError}</p>}
      </div>

      {/* Fields */}
      <div className="space-y-3">
        <FieldGrid>
          <Field label="Title">
            <input
              value={titleDraft}
              onChange={(e) => setTitleDraft(e.target.value)}
              onBlur={() => titleDraft !== slide.title && onChange({ title: titleDraft })}
              maxLength={80}
              placeholder="Limited time offer"
              className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20"
            />
          </Field>
          <Field label="CTA label">
            <input
              value={ctaDraft}
              onChange={(e) => setCtaDraft(e.target.value)}
              onBlur={() =>
                ctaDraft !== slide.ctaLabel &&
                onChange({ ctaLabel: ctaDraft.trim() || DEFAULT_HERO_CTA })
              }
              maxLength={32}
              placeholder={DEFAULT_HERO_CTA}
              className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20"
            />
          </Field>
        </FieldGrid>

        <Field label="Subtitle">
          <input
            value={subtitleDraft}
            onChange={(e) => setSubtitleDraft(e.target.value)}
            onBlur={() => subtitleDraft !== slide.subtitle && onChange({ subtitle: subtitleDraft })}
            maxLength={140}
            placeholder="Save 20% on your first online order"
            className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20"
          />
        </Field>

        <div>
          <label className="block text-[11px] font-semibold text-slate-500 uppercase tracking-wide mb-1.5">
            Click action
          </label>
          <div className="flex gap-1 bg-slate-100 rounded-lg p-1 mb-2">
            {(["NONE", "CATEGORY", "ITEM", "URL"] as const).map((t) => (
              <button
                key={t}
                type="button"
                onClick={() => onPickActionType(t)}
                className={`flex-1 text-xs font-medium px-2 py-1.5 rounded-md transition-colors ${
                  actionTypeDraft === t
                    ? "bg-white text-slate-900 shadow-sm"
                    : "text-slate-500 hover:text-slate-800"
                }`}
              >
                {t === "NONE" ? "None" : t === "CATEGORY" ? "Category" : t === "ITEM" ? "Item" : "URL"}
              </button>
            ))}
          </div>
          {actionTypeDraft === "CATEGORY" || actionTypeDraft === "ITEM" ? (
            <select
              value={actionValueDraft}
              onChange={(e) => {
                setActionValueDraft(e.target.value);
                onChange({ actionType: actionTypeDraft, actionValue: e.target.value });
              }}
              className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm bg-white outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20"
            >
              <option value="">
                {actionTypeDraft === "CATEGORY" ? "Pick a category…" : "Pick an item…"}
              </option>
              {targetOptions.map((o) => (
                <option key={o.id} value={o.id}>
                  {o.label}
                </option>
              ))}
            </select>
          ) : actionTypeDraft === "URL" ? (
            <input
              value={urlDraft}
              onChange={(e) => setUrlDraft(e.target.value)}
              onBlur={() => onChange({ actionType: "URL", actionValue: urlDraft.trim() })}
              placeholder="https://…"
              className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20"
            />
          ) : (
            <p className="text-[11px] text-slate-400">
              The CTA button is hidden when the action is set to None.
            </p>
          )}
        </div>

        <div className="flex justify-end">
          <button
            type="button"
            onClick={onRemove}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-rose-600 hover:bg-rose-50"
          >
            <Trash2 size={14} /> Delete slide
          </button>
        </div>
      </div>
    </div>
  );
}

/* ─────────────────────────────────────────────
   Tiny field helpers (kept local to this component)
   ───────────────────────────────────────────── */

function FieldGrid({ children }: { children: React.ReactNode }) {
  return <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">{children}</div>;
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-[11px] font-semibold text-slate-500 uppercase tracking-wide mb-1.5">
        {label}
      </label>
      {children}
    </div>
  );
}
