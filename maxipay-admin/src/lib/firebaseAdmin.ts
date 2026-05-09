import * as admin from "firebase-admin";
import { createPrivateKey } from "crypto";

function isValidPkcs8Pem(pem: string): boolean {
  try {
    createPrivateKey({ key: pem, format: "pem" });
    return true;
  } catch {
    return false;
  }
}

function normalizePrivateKey(privateKey: string | undefined): string {
  if (privateKey == null || typeof privateKey !== "string") return "";
  let key = privateKey.trim();
  if (key.charCodeAt(0) === 0xfeff) key = key.slice(1).trim();
  key = key.replace(/\\n/g, "\n");
  key = key.replace(/\r\n/g, "\n");
  if (!key.includes("\n") && key.includes("BEGIN PRIVATE KEY")) {
    key = key
      .replace("-----BEGIN PRIVATE KEY-----", "-----BEGIN PRIVATE KEY-----\n")
      .replace("-----END PRIVATE KEY-----", "\n-----END PRIVATE KEY-----\n");
  }
  key = key.trim();
  if (!key.endsWith("\n")) key += "\n";
  return key;
}

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

function finalizePrivateKey(rawPem: string): string {
  let key = normalizePrivateKey(rawPem);
  if (isValidPkcs8Pem(key)) return key;
  const repaired = repairPkcs8PrivateKeyPem(key);
  if (isValidPkcs8Pem(repaired)) return repaired;
  const repaired2 = repairPkcs8PrivateKeyPem(normalizePrivateKey(rawPem));
  if (isValidPkcs8Pem(repaired2)) return repaired2;
  throw new Error(
    "private_key is not valid PKCS#8 PEM after normalization/repair. " +
      "Re-download the JSON from Firebase or paste the full JSON into FIREBASE_SERVICE_ACCOUNT_JSON."
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
  key = key.replace(/\\n/g, "\n").trim();
  const match = key.match(/-----BEGIN PRIVATE KEY-----([\s\S]*)-----END PRIVATE KEY-----/);
  if (!match) return key;
  const body = match[1].replace(/\s+/g, "");
  const lines = body.match(/.{1,64}/g) || [];
  return ["-----BEGIN PRIVATE KEY-----", ...lines, "-----END PRIVATE KEY-----"].join("\n");
}

function buildNormalizedCredentials(): admin.ServiceAccount {
  const envJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if (typeof envJson !== "string" || !envJson.trim()) {
    throw new Error(
      "No Firebase service account found. Set FIREBASE_SERVICE_ACCOUNT_JSON."
    );
  }
  const raw = tryParseJson(envJson.trim());
  if (!raw) throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is not valid JSON.");

  const sa = raw as Record<string, unknown>;
  if (typeof sa.private_key === "string") {
    sa.private_key = fixPrivateKey(sa.private_key);
  }

  const pk = getPrivateKeyRaw(raw);
  if (!String(pk).includes("BEGIN PRIVATE KEY")) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON: private_key missing PKCS#8 header");
  }

  const fixedKey = finalizePrivateKey(pk);
  return {
    type: (sa.type as string) || "service_account",
    project_id: sa.project_id as string,
    private_key: fixedKey,
    client_email: sa.client_email as string,
    client_id: sa.client_id as string,
    auth_uri: sa.auth_uri as string,
    token_uri: sa.token_uri as string,
    auth_provider_x509_cert_url: sa.auth_provider_x509_cert_url as string,
    client_x509_cert_url: sa.client_x509_cert_url as string,
  } as admin.ServiceAccount;
}

let cachedCredentials: admin.ServiceAccount | null = null;

function getServiceAccountCredentials(): admin.ServiceAccount {
  if (!cachedCredentials) cachedCredentials = buildNormalizedCredentials();
  return cachedCredentials;
}

let app: admin.app.App | null = null;

export function getFirebaseAdminApp(): admin.app.App {
  if (app) return app;
  if (admin.apps.length > 0) {
    app = admin.app();
    return app;
  }
  app = admin.initializeApp({
    credential: admin.credential.cert(getServiceAccountCredentials()),
  });
  return app;
}

export async function verifyIdToken(
  authHeader: string | null
): Promise<admin.auth.DecodedIdToken> {
  if (!authHeader?.startsWith("Bearer ")) throw new Error("Unauthorized");
  const token = authHeader.slice(7).trim();
  if (!token) throw new Error("Unauthorized");
  getFirebaseAdminApp();
  return admin.auth().verifyIdToken(token);
}
