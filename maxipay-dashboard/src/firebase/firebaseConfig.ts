import { initializeApp, getApps, getApp } from "firebase/app";
import { getAuth, type Auth } from "firebase/auth";
import { getFirestore, type Firestore } from "firebase/firestore";

const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
  storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
};

let auth = {} as Auth;
let db = {} as Firestore;

if (typeof window !== "undefined") {
  const missingKeys = Object.entries(firebaseConfig)
    .filter(([, v]) => !v)
    .map(([k]) => k);

  if (missingKeys.length > 0) {
    console.error(
      `[Firebase] Missing config keys: ${missingKeys.join(", ")}. ` +
        "Ensure NEXT_PUBLIC_FIREBASE_* env vars are set at build time."
    );
  }

  console.log(
    "[Firebase] Client init →",
    "projectId:", firebaseConfig.projectId ?? "MISSING",
    "| authDomain:", firebaseConfig.authDomain ?? "MISSING",
    "| apiKey:", firebaseConfig.apiKey ? "SET" : "MISSING"
  );

  const app = !getApps().length ? initializeApp(firebaseConfig) : getApp();
  auth = getAuth(app);
  db = getFirestore(app);

  console.log("[Firebase] Initialized successfully");
}

export { auth, db };
