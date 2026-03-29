import * as admin from "firebase-admin";
import serviceAccount from "../../serviceAccountKey.json";

let app: admin.app.App | null = null;

export function getFirebaseAdminApp(): admin.app.App {
  if (app) return app;
  if (admin.apps.length > 0) {
    app = admin.app();
    return app;
  }
  const bucket =
    process.env.FIREBASE_STORAGE_BUCKET ||
    process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET;
  app = admin.initializeApp({
    credential: admin.credential.cert(
      serviceAccount as admin.ServiceAccount
    ),
    storageBucket: bucket,
  });
  return app;
}

export async function verifyIdToken(
  authHeader: string | null
): Promise<admin.auth.DecodedIdToken> {
  if (!authHeader?.startsWith("Bearer ")) {
    throw new Error("Unauthorized");
  }
  const token = authHeader.slice(7).trim();
  if (!token) throw new Error("Unauthorized");
  getFirebaseAdminApp();
  return admin.auth().verifyIdToken(token);
}
