import * as admin from "firebase-admin";
import { createPrivateKey } from "crypto";

/** Returns true if Node can use this PEM (same check firebase-admin uses internally). */
function isValidPkcs8Pem(pem: string): boolean {
  try {
    createPrivateKey({ key: pem, format: "pem" });
    return true;
  } catch {
    return false;
  }
}

/**
 * Normalizes PEM so Node's crypto accepts it (Firebase JSON, env paste, Windows, etc.).
 */
function normalizePrivateKey(privateKey: string | undefined): string {
  if (privateKey == null || typeof privateKey !== "string") return "";
  let key = privateKey.trim();
  // UTF-8 BOM
  if (key.charCodeAt(0) === 0xfeff) key = key.slice(1).trim();

  // Literal backslash + "n" pairs (broken copy, double-encoding, some .env styles)
  key = key.replace(/\\n/g, "\n");
  key = key.replace(/\r\n/g, "\n");

  // Still one long line: split markers (rare but fixes some exports)
  if (!key.includes("\n") && key.includes("BEGIN PRIVATE KEY")) {
    key = key
      .replace(
        "-----BEGIN PRIVATE KEY-----",
        "-----BEGIN PRIVATE KEY-----\n"
      )
      .replace(
        "-----END PRIVATE KEY-----",
        "\n-----END PRIVATE KEY-----\n"
      );
  }

  key = key.trim();
  if (!key.endsWith("\n")) key += "\n";
  return key;
}

/**
 * Rebuilds PKCS#8 PEM when the base64 body lost its line breaks (common after
 * pasting JSON into Vercel / dashboard env UIs).
 */
function repairPkcs8PrivateKeyPem(pem: string): string {
  const begin = "-----BEGIN PRIVATE KEY-----";
  const end = "-----END PRIVATE KEY-----";
  const bi = pem.indexOf(begin);
  const ei = pem.indexOf(end);
  if (bi === -1 || ei === -1 || ei <= bi) return pem;
  const inner = pem.slice(bi + begin.length, ei).replace(/\s/g, "");
  if (!inner.length) return pem;
  const chunks = inner.match(/.{1,64}/g) ?? [];
  return `${begin}\n${chunks.join("\n")}\n${end}\n`;
}

/**
 * Normalize → validate; if invalid, repair base64 wrapping and validate again.
 */
function finalizePrivateKey(rawPem: string): string {
  let key = normalizePrivateKey(rawPem);
  if (isValidPkcs8Pem(key)) return key;

  const repaired = repairPkcs8PrivateKeyPem(key);
  if (isValidPkcs8Pem(repaired)) return repaired;

  // Try repair on raw (some keys skip normalize well)
  const repaired2 = repairPkcs8PrivateKeyPem(normalizePrivateKey(rawPem));
  if (isValidPkcs8Pem(repaired2)) return repaired2;

  throw new Error(
    "private_key is not valid PKCS#8 PEM after normalization/repair. " +
      "Re-download the JSON from Firebase or paste the full JSON into FIREBASE_SERVICE_ACCOUNT_JSON without editing the private_key field."
  );
}

function getPrivateKeyRaw(sa: admin.ServiceAccount): string {
  const o = sa as Record<string, unknown>;
  const pk = o.private_key ?? o.privateKey;
  return typeof pk === "string" ? pk : "";
}

function tryParseJson(text: string): admin.ServiceAccount | null {
  try {
    return JSON.parse(text) as admin.ServiceAccount;
  } catch {
    return null;
  }
}

function fixPrivateKey(key: string): string {
  if (!key) return key;

  // Convert escaped newlines
  key = key.replace(/\\n/g, "\n").trim();

  // Extract body
  const match = key.match(/-----BEGIN PRIVATE KEY-----([\s\S]*)-----END PRIVATE KEY-----/);
  if (!match) return key;

  let body = match[1].replace(/\s+/g, "");

  // Rewrap to 64 chars per line
  const lines = body.match(/.{1,64}/g) || [];

  return [
    "-----BEGIN PRIVATE KEY-----",
    ...lines,
    "-----END PRIVATE KEY-----"
  ].join("\n");
}

function loadRawServiceAccountFromEnv(): admin.ServiceAccount | null {
  const envJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if (typeof envJson !== "string" || !envJson.trim()) return null;
  const serviceAccount = tryParseJson(envJson.trim());
  if (!serviceAccount) return null;
  console.log("SERVICE ACCOUNT LOADED FROM ENV");
  const sa = serviceAccount as Record<string, unknown>;
  if (typeof sa.private_key === "string") {
    sa.private_key = fixPrivateKey(sa.private_key);
  }
  return serviceAccount;
}

function assertServiceAccountUsable(sa: admin.ServiceAccount): void {
  const pk = getPrivateKeyRaw(sa);
  if (
    typeof pk === "string" &&
    (pk.includes("REPLACE_") || pk.includes("REPLACE_WITH"))
  ) {
    throw new Error(
      "template_or_placeholder_key"
    );
  }
  if (!String(pk).includes("BEGIN PRIVATE KEY")) {
    throw new Error(
      "missing_begin_private_key"
    );
  }
}

/**
 * Single object shape google-auth / firebase-admin expect (snake_case fields).
 */
function buildNormalizedCredentials(): admin.ServiceAccount {
  const raw = loadRawServiceAccountFromEnv();
  if (!raw) {
    throw new Error(
      "No valid Firebase service account could be loaded. " +
        "Set FIREBASE_SERVICE_ACCOUNT_JSON on your host to the full JSON from Firebase Console → Project settings → Service accounts → Generate new private key."
    );
  }
  const tag = "FIREBASE_SERVICE_ACCOUNT_JSON";
  try {
    assertServiceAccountUsable(raw);
    const fixedKey = finalizePrivateKey(getPrivateKeyRaw(raw));
    const o = raw as Record<string, unknown>;

    return {
      type: (o.type as string) || "service_account",
      project_id: o.project_id as string,
      private_key: fixedKey,
      client_email: o.client_email as string,
      client_id: o.client_id as string,
      auth_uri: o.auth_uri as string,
      token_uri: o.token_uri as string,
      auth_provider_x509_cert_url: o.auth_provider_x509_cert_url as string,
      client_x509_cert_url: o.client_x509_cert_url as string,
      ...(o.universe_domain
        ? { universe_domain: o.universe_domain as string }
        : {}),
    } as admin.ServiceAccount;
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (msg === "template_or_placeholder_key") {
      throw new Error(
        `${tag}: still template/placeholder — replace with real Firebase JSON`
      );
    }
    if (msg === "missing_begin_private_key") {
      throw new Error(`${tag}: private_key missing PKCS#8 header`);
    }
    throw new Error(`${tag}: ${msg}`);
  }
}

let cachedCredentials: admin.ServiceAccount | null = null;

/** Normalized service account for firebase-admin and @google-cloud/vision (lazy). */
export function getServiceAccountCredentials(): admin.ServiceAccount {
  if (!cachedCredentials) {
    cachedCredentials = buildNormalizedCredentials();
  }
  return cachedCredentials;
}

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
  try {
    app = admin.initializeApp({
      credential: admin.credential.cert(getServiceAccountCredentials()),
      storageBucket: bucket,
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (msg.includes("PEM") || msg.includes("private key")) {
      throw new Error(
        `Firebase Admin could not read the service account private key (${msg}). ` +
          "Use the exact JSON from Firebase (Generate new private key). " +
          "Set FIREBASE_SERVICE_ACCOUNT_JSON on production to the full JSON string."
      );
    }
    throw e;
  }
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
