/**
 * Client-side image helpers used by the online-ordering admin (hero slide + storefront logo
 * uploads). Resizes oversized images before upload to keep Firebase Storage costs sane and
 * ensures the customer page never has to download a 12MB photo.
 *
 * Uses an HTMLCanvasElement → Blob; runs in the browser only. For server uploads, use
 * Firebase Admin Storage directly.
 */

export interface ResizeOptions {
  /** Cap longest edge in CSS pixels. Default 1600. */
  maxEdge?: number;
  /** JPEG/WebP quality 0–1. Default 0.85. */
  quality?: number;
  /** Output mime type. Default "image/jpeg" — small + universal. */
  mimeType?: "image/jpeg" | "image/png" | "image/webp";
}

/**
 * Reads a file and re-renders it on a canvas, returning a Blob smaller than [maxEdge] on its
 * longest side. Throws on unreadable inputs (e.g. PDFs). Aspect ratio is preserved.
 */
export function resizeImageToBlob(
  file: File,
  opts: ResizeOptions = {}
): Promise<Blob> {
  const { maxEdge = 1600, quality = 0.85, mimeType = "image/jpeg" } = opts;
  return new Promise((resolve, reject) => {
    const img = new window.Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      let w = img.naturalWidth;
      let h = img.naturalHeight;
      const longest = Math.max(w, h);
      if (longest > maxEdge) {
        const scale = maxEdge / longest;
        w = Math.round(w * scale);
        h = Math.round(h * scale);
      }
      const canvas = document.createElement("canvas");
      canvas.width = w;
      canvas.height = h;
      const ctx = canvas.getContext("2d");
      if (!ctx) {
        reject(new Error("Canvas context unavailable"));
        return;
      }
      // Light fill keeps transparent PNGs from going black on JPEG output.
      if (mimeType === "image/jpeg") {
        ctx.fillStyle = "#ffffff";
        ctx.fillRect(0, 0, w, h);
      }
      ctx.drawImage(img, 0, 0, w, h);
      canvas.toBlob(
        (blob) => (blob ? resolve(blob) : reject(new Error("Image encode failed"))),
        mimeType,
        quality
      );
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("Could not read image file"));
    };
    img.src = url;
  });
}
