/**
 * Client-side upload of a menu item image to Firebase Storage.
 * Returns a Firebase download URL suitable for the MenuItems `imageUrl` field (not external URLs).
 */

import { ref, uploadBytes, getDownloadURL } from "firebase/storage";
import { storage } from "@/firebase/firebaseConfig";

const MENU_ITEMS_FOLDER = "menuItems";

export interface UploadMenuItemImageResult {
  storagePath: string;
  downloadUrl: string;
}

/**
 * Uploads [blob] to `menuItems/{itemId}_{timestamp}.jpg` (or .png) and returns the download URL.
 */
export async function uploadMenuItemImageToFirebase(
  blob: Blob,
  itemId: string,
  opts?: { contentType?: string; extension?: "jpg" | "png" }
): Promise<UploadMenuItemImageResult> {
  const ext = opts?.extension ?? (opts?.contentType?.includes("png") ? "png" : "jpg");
  const contentType =
    opts?.contentType ?? (ext === "png" ? "image/png" : "image/jpeg");
  const storagePath = `${MENU_ITEMS_FOLDER}/${itemId}_${Date.now()}.${ext}`;
  const storageRef = ref(storage, storagePath);
  await uploadBytes(storageRef, blob, { contentType });
  const downloadUrl = await getDownloadURL(storageRef);
  return { storagePath, downloadUrl };
}
