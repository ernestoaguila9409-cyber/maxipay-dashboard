"use client";

import { useRef, useState } from "react";
import { ImageIcon, Loader2, Trash2, Upload } from "lucide-react";
import { resizeImageToBlob } from "@/lib/imageUpload";
import { uploadModifierOptionImageToFirebase } from "@/lib/uploadImageToFirebase";
import { AutoImageSearchButton } from "@/components/menu-item-image/AutoImageSearchButton";
import { ImageSearchModal } from "@/components/menu-item-image/ImageSearchModal";

export interface ModifierOptionImageSectionProps {
  imageUrl: string;
  onImageUrlChange: (url: string) => void;
  /** Persist Firebase URL on the modifier option (embedded array in ModifierGroups). */
  onPersistImageUrl: (url: string) => Promise<void>;
  modifierGroupId: string;
  modifierOptionId: string;
  optionName: string;
  getIdToken: () => Promise<string>;
  disabled?: boolean;
}

export function ModifierOptionImageSection({
  imageUrl,
  onImageUrlChange,
  onPersistImageUrl,
  modifierGroupId,
  modifierOptionId,
  optionName,
  getIdToken,
  disabled,
}: ModifierOptionImageSectionProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file || !file.type.startsWith("image/")) {
      setLocalError("Choose an image file.");
      return;
    }
    setLocalError(null);
    setUploading(true);
    try {
      const blob = await resizeImageToBlob(file, {
        maxEdge: 1600,
        quality: 0.88,
        mimeType: "image/jpeg",
      });
      const { downloadUrl } = await uploadModifierOptionImageToFirebase(
        blob,
        modifierGroupId,
        modifierOptionId,
        { contentType: "image/jpeg", extension: "jpg" },
      );
      onImageUrlChange(downloadUrl);
      await onPersistImageUrl(downloadUrl);
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  };

  const clearImage = async () => {
    setLocalError(null);
    try {
      onImageUrlChange("");
      await onPersistImageUrl("");
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : "Could not remove image");
    }
  };

  return (
    <div className="rounded-xl border border-slate-200 bg-slate-50/50 p-4 space-y-3">
      <h4 className="text-sm font-semibold text-slate-800">Option image</h4>

      <div className="flex gap-4">
        <div className="shrink-0 w-24 h-24 rounded-xl border border-slate-200 bg-white overflow-hidden grid place-items-center">
          {imageUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={imageUrl} alt="" className="w-full h-full object-cover" />
          ) : (
            <ImageIcon className="text-slate-300" size={32} />
          )}
        </div>
        <div className="flex-1 min-w-0 flex flex-col gap-2">
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={disabled || uploading}
              className="inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-xl bg-blue-600 text-white text-xs font-medium hover:bg-blue-700 disabled:opacity-50"
            >
              {uploading ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Upload size={14} />
              )}
              Upload image
            </button>
            <AutoImageSearchButton
              onClick={() => setPickerOpen(true)}
              disabled={disabled || uploading || !optionName.trim()}
            />
            {imageUrl ? (
              <button
                type="button"
                onClick={() => void clearImage()}
                disabled={disabled || uploading}
                className="inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-xl border border-slate-200 bg-white text-slate-700 text-xs font-medium hover:bg-red-50 hover:border-red-200 hover:text-red-700 disabled:opacity-50"
              >
                <Trash2 size={14} />
                Remove
              </button>
            ) : null}
          </div>
          <input
            ref={fileInputRef}
            type="file"
            accept="image/jpeg,image/png,image/webp"
            className="hidden"
            onChange={handleFile}
          />
          <p className="text-[11px] text-slate-500 leading-snug">
            Same flow as menu items: Firebase upload, or Auto find (AI query + Pexels) then save a
            copy to your bucket.
          </p>
          {localError && <p className="text-xs text-red-600">{localError}</p>}
        </div>
      </div>

      <ImageSearchModal
        mode="modifierOption"
        open={pickerOpen}
        onClose={() => setPickerOpen(false)}
        optionName={optionName}
        modifierGroupId={modifierGroupId}
        modifierOptionId={modifierOptionId}
        getIdToken={getIdToken}
        onCommitted={async (url) => {
          onImageUrlChange(url);
          await onPersistImageUrl(url);
        }}
      />
    </div>
  );
}
