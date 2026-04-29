"use client";

import type { ChangeEvent } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  collection,
  deleteDoc,
  doc,
  onSnapshot,
  serverTimestamp,
  writeBatch,
} from "firebase/firestore";
import {
  deleteObject,
  getDownloadURL,
  ref as storageRef,
  uploadBytes,
} from "firebase/storage";
import { AlertTriangle, ImagePlus, Loader2, Trash2 } from "lucide-react";
import { AutoImageSearchButton } from "@/components/menu-item-image/AutoImageSearchButton";
import { ImageSearchModal } from "@/components/menu-item-image/ImageSearchModal";
import { useAuth } from "@/context/AuthContext";
import { db, storage } from "@/firebase/firebaseConfig";
import { resizeImageToBlob } from "@/lib/imageUpload";
import {
  DEFAULT_HERO_CTA,
  HERO_SLIDES_COLLECTION,
  HERO_STORAGE_PREFIX,
  parseHeroSlide,
  type HeroSlide,
} from "@/lib/storefrontShared";

function newSlideId(): string {
  return `slide_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

/**
 * Single storefront banner image. Persists as the first `OnlineHeroSlides` doc (order 0).
 * Replacing the image removes any extra slides so the public page shows one picture only.
 */
export default function StorefrontPictureManager({ businessName }: { businessName: string }) {
  const { user } = useAuth();
  const [slides, setSlides] = useState<HeroSlide[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [imageSearchOpen, setImageSearchOpen] = useState(false);
  const [heroSearchSlideId, setHeroSearchSlideId] = useState("");
  const fileInputRef = useRef<HTMLInputElement>(null);

  const getIdToken = useCallback(() => {
    if (!user) throw new Error("Not signed in");
    return user.getIdToken();
  }, [user]);

  useEffect(() => {
    const unsub = onSnapshot(
      collection(db, HERO_SLIDES_COLLECTION),
      (snap) => {
        const list = snap.docs.map((d) =>
          parseHeroSlide(d.id, d.data() as Record<string, unknown>)
        );
        list.sort((a, b) => a.order - b.order || a.id.localeCompare(b.id));
        setSlides(list);
        setLoading(false);
      },
      (e) => {
        console.error("[storefront-picture]", e);
        setError(e.message);
        setLoading(false);
      }
    );
    return () => unsub();
  }, []);

  const primary = useMemo(() => (slides.length === 0 ? null : slides[0]), [slides]);
  const extraCount = slides.length > 1 ? slides.length - 1 : 0;

  const deleteSlideStorage = useCallback(async (s: HeroSlide) => {
    if (!s.storagePath) return;
    try {
      await deleteObject(storageRef(storage, s.storagePath));
    } catch {
      /* may already be gone */
    }
  }, []);

  const syncHeroPrimaryFromRemote = useCallback(
    async (targetId: string, imageUrl: string, storagePath: string) => {
      const batch = writeBatch(db);
      batch.set(
        doc(db, HERO_SLIDES_COLLECTION, targetId),
        {
          imageUrl,
          storagePath,
          title: "",
          subtitle: "",
          ctaLabel: DEFAULT_HERO_CTA,
          actionType: "NONE",
          actionValue: "",
          order: 0,
          updatedAt: serverTimestamp(),
          ...(slides.some((s) => s.id === targetId) ? {} : { createdAt: serverTimestamp() }),
        },
        { merge: true }
      );

      for (const s of slides) {
        if (s.id !== targetId) {
          batch.delete(doc(db, HERO_SLIDES_COLLECTION, s.id));
        }
      }
      await batch.commit();

      for (const s of slides) {
        if (s.id !== targetId && s.storagePath && s.storagePath !== storagePath) {
          await deleteSlideStorage(s);
        }
      }
    },
    [deleteSlideStorage, slides]
  );

  const removePicture = useCallback(async () => {
    if (!primary) return;
    if (!confirm("Remove the storefront picture? It will be deleted from storage.")) return;
    setBusy(true);
    setError(null);
    try {
      await deleteSlideStorage(primary);
      await deleteDoc(doc(db, HERO_SLIDES_COLLECTION, primary.id));
      for (const s of slides.slice(1)) {
        await deleteSlideStorage(s);
        await deleteDoc(doc(db, HERO_SLIDES_COLLECTION, s.id));
      }
    } catch (e) {
      console.error("[storefront-picture] remove", e);
      setError("Could not remove the image. Try again.");
    } finally {
      setBusy(false);
    }
  }, [deleteSlideStorage, primary, slides]);

  const handleFile = useCallback(
    async (file: File) => {
      setError(null);
      const willRemoveExtras = slides.length > 1;
      if (
        willRemoveExtras &&
        !confirm(
          `You have ${slides.length} banner images. Only one storefront picture is kept. Remove the other ${extraCount}?`
        )
      ) {
        return;
      }

      setUploading(true);
      try {
        const blob = await resizeImageToBlob(file, { maxEdge: 2000, mimeType: "image/jpeg" });
        const targetId = primary?.id ?? newSlideId();
        const path = `${HERO_STORAGE_PREFIX}/${targetId}.jpg`;
        const sref = storageRef(storage, path);
        await uploadBytes(sref, blob, { contentType: "image/jpeg" });
        const url = await getDownloadURL(sref);
        await syncHeroPrimaryFromRemote(targetId, url, path);
      } catch (e) {
        const msg = e instanceof Error ? e.message : "Upload failed";
        setError(msg);
      } finally {
        setUploading(false);
      }
    },
    [extraCount, primary, slides, syncHeroPrimaryFromRemote]
  );

  const onFilePicked = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      e.target.value = "";
      if (file) void handleFile(file);
    },
    [handleFile]
  );

  const openImageSearch = useCallback(() => {
    setHeroSearchSlideId(primary?.id ?? newSlideId());
    setImageSearchOpen(true);
  }, [primary]);

  const keepFirstOnly = useCallback(async () => {
    if (slides.length <= 1) return;
    if (!confirm(`Remove ${slides.length - 1} extra slide(s) and keep only the first image?`))
      return;
    setBusy(true);
    setError(null);
    try {
      const keep = slides[0];
      const batch = writeBatch(db);
      for (const s of slides.slice(1)) {
        batch.delete(doc(db, HERO_SLIDES_COLLECTION, s.id));
      }
      batch.set(
        doc(db, HERO_SLIDES_COLLECTION, keep.id),
        { order: 0, updatedAt: serverTimestamp() },
        { merge: true }
      );
      await batch.commit();
      for (const s of slides.slice(1)) {
        await deleteSlideStorage(s);
      }
    } catch (e) {
      console.error("[storefront-picture] trim", e);
      setError("Could not remove extra slides.");
    } finally {
      setBusy(false);
    }
  }, [deleteSlideStorage, slides]);

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-lg font-semibold text-slate-900">Store Front picture</h2>
        <p className="text-sm text-slate-500 mt-0.5 max-w-xl">
          One banner image at the top of your public ordering page (above categories and menu).
          Auto find uses the same AI + Pexels flow as menu item images, tuned for wide banner
          photos.
        </p>
      </div>

      {error && (
        <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
          {error}
        </p>
      )}

      {extraCount > 0 && (
        <div className="flex flex-wrap items-start gap-3 rounded-xl border border-amber-200 bg-amber-50/80 px-3 py-2.5 text-sm text-amber-950">
          <AlertTriangle className="shrink-0 mt-0.5" size={18} />
          <div className="min-w-0 flex-1">
            <p className="font-medium">Multiple images detected</p>
            <p className="text-xs text-amber-900/90 mt-0.5">
              The storefront would rotate {slides.length} slides. Use one picture only.
            </p>
            <button
              type="button"
              disabled={busy}
              onClick={() => void keepFirstOnly()}
              className="mt-2 text-xs font-semibold text-amber-900 underline hover:no-underline disabled:opacity-50"
            >
              Keep only the first image
            </button>
          </div>
        </div>
      )}

      {loading ? (
        <div className="flex items-center gap-2 text-slate-500 py-6">
          <Loader2 className="animate-spin" size={18} /> Loading…
        </div>
      ) : (
        <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm max-w-lg">
          <div
            className={`relative w-full aspect-[16/9] rounded-xl overflow-hidden border border-slate-200 ${
              primary?.imageUrl ? "bg-neutral-900" : "bg-slate-50"
            }`}
          >
            {primary?.imageUrl ? (
              // eslint-disable-next-line @next/next/no-img-element -- Firebase Storage URL
              <img
                src={primary.imageUrl}
                alt=""
                className="w-full h-full object-cover"
              />
            ) : (
              <div className="w-full h-full grid place-items-center text-slate-400">
                <div className="flex flex-col items-center gap-1.5">
                  <ImagePlus size={32} />
                  <span className="text-sm">No storefront picture yet</span>
                </div>
              </div>
            )}
            {uploading && (
              <div className="absolute inset-0 bg-black/40 grid place-items-center text-white text-sm gap-1">
                <Loader2 size={22} className="animate-spin" />
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

          <div className="mt-4 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={uploading || busy}
              className="inline-flex items-center justify-center gap-1.5 rounded-lg bg-blue-600 text-white text-sm font-medium px-4 py-2 hover:bg-blue-700 disabled:bg-blue-300"
            >
              {primary?.imageUrl ? "Replace picture" : "Upload picture"}
            </button>
            <AutoImageSearchButton
              onClick={() => openImageSearch()}
              disabled={uploading || busy || !user}
            />
            {primary?.imageUrl ? (
              <button
                type="button"
                onClick={() => void removePicture()}
                disabled={uploading || busy}
                className="inline-flex items-center gap-1.5 rounded-lg border border-red-200 text-red-700 text-sm font-medium px-4 py-2 hover:bg-red-50 disabled:opacity-50"
              >
                <Trash2 size={16} />
                Remove
              </button>
            ) : null}
          </div>
        </div>
      )}

      <ImageSearchModal
        mode="storefront"
        open={imageSearchOpen}
        onClose={() => setImageSearchOpen(false)}
        businessName={businessName.trim() || "Restaurant"}
        heroSlideId={heroSearchSlideId}
        getIdToken={getIdToken}
        onCommitted={async (url, storagePath) => {
          if (!storagePath || !heroSearchSlideId) {
            setError("Could not save image (missing storage path). Try again.");
            return false;
          }
          const willRemoveExtras = slides.length > 1;
          if (
            willRemoveExtras &&
            !confirm(
              `You have ${slides.length} banner images. Only one storefront picture is kept. Remove the other ${extraCount}?`
            )
          ) {
            return false;
          }
          setError(null);
          try {
            await syncHeroPrimaryFromRemote(heroSearchSlideId, url, storagePath);
            return true;
          } catch (e) {
            console.error("[storefront-picture] pexels commit", e);
            setError("Could not save the storefront image. Try again.");
            return false;
          }
        }}
      />
    </div>
  );
}
