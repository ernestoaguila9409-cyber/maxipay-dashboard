const admin = require("firebase-admin");
const logger = require("firebase-functions/logger");

const SANDBOX_AUTH_URL = "https://sandbox-login.uber.com/oauth/v2/authorize";
const PROD_AUTH_URL = "https://auth.uber.com/oauth/v2/authorize";
const SANDBOX_TOKEN_URL = "https://sandbox-login.uber.com/oauth/v2/token";
const PROD_TOKEN_URL = "https://auth.uber.com/oauth/v2/token";

const PROVISIONING_SCOPES = "eats.pos_provisioning";
const FIRESTORE_DOC = "Settings/uberOAuth";
const REFRESH_BUFFER_MS = 60 * 60 * 1000;

function isSandbox() {
  return process.env.UBER_ENV !== "production";
}

function getAuthUrl() {
  return isSandbox() ? SANDBOX_AUTH_URL : PROD_AUTH_URL;
}

function getTokenUrl() {
  return isSandbox() ? SANDBOX_TOKEN_URL : PROD_TOKEN_URL;
}

function getCredentials() {
  const clientId = process.env.UBER_CLIENT_ID;
  const clientSecret = process.env.UBER_CLIENT_SECRET;
  if (!clientId || !clientSecret) {
    throw new Error(
      "UBER_CLIENT_ID and UBER_CLIENT_SECRET must be set. " +
      "Run: firebase functions:secrets:set UBER_CLIENT_ID && " +
      "firebase functions:secrets:set UBER_CLIENT_SECRET",
    );
  }
  return { clientId, clientSecret };
}

/**
 * Build the Uber OAuth authorization URL that the merchant must visit
 * to grant the eats.pos_provisioning scope.
 *
 * @param {string} redirectUri  The HTTPS callback URL (your Cloud Function)
 * @param {string} [state]      Opaque value returned in the callback for CSRF protection
 */
function buildAuthorizationUrl(redirectUri, state) {
  const { clientId } = getCredentials();
  const params = new URLSearchParams({
    response_type: "code",
    client_id: clientId,
    redirect_uri: redirectUri,
    scope: PROVISIONING_SCOPES,
  });
  if (state) params.set("state", state);
  return `${getAuthUrl()}?${params.toString()}`;
}

/**
 * Exchange an authorization code for access + refresh tokens and persist
 * them in Firestore so they survive across Cloud Function cold starts.
 */
async function exchangeCodeForTokens(code, redirectUri) {
  const { clientId, clientSecret } = getCredentials();

  const res = await fetch(getTokenUrl(), {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: clientId,
      client_secret: clientSecret,
      code,
      redirect_uri: redirectUri,
    }),
  });

  if (!res.ok) {
    const text = await res.text();
    logger.error("[uber-auth-code] Token exchange failed", {
      status: res.status, body: text.substring(0, 500),
    });
    throw new Error(`Uber token exchange failed: ${res.status} — ${text.substring(0, 200)}`);
  }

  const data = await res.json();
  logger.info("[uber-auth-code] Token exchange success", {
    scope: data.scope, expiresIn: data.expires_in,
  });

  await persistTokens(data);
  return data;
}

/**
 * Refresh the access token using the stored refresh_token.
 */
async function refreshAccessToken(refreshToken) {
  const { clientId, clientSecret } = getCredentials();

  const res = await fetch(getTokenUrl(), {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "refresh_token",
      client_id: clientId,
      client_secret: clientSecret,
      refresh_token: refreshToken,
    }),
  });

  if (!res.ok) {
    const text = await res.text();
    logger.error("[uber-auth-code] Token refresh failed", {
      status: res.status, body: text.substring(0, 500),
    });
    throw new Error(`Uber token refresh failed: ${res.status} — ${text.substring(0, 200)}`);
  }

  const data = await res.json();
  logger.info("[uber-auth-code] Token refresh success", {
    scope: data.scope, expiresIn: data.expires_in,
  });

  await persistTokens(data);
  return data;
}

async function persistTokens(tokenData) {
  const db = admin.firestore();
  const doc = {
    accessToken: tokenData.access_token,
    refreshToken: tokenData.refresh_token,
    scope: tokenData.scope || PROVISIONING_SCOPES,
    expiresAt: Date.now() + (tokenData.expires_in || 2592000) * 1000,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  };
  await db.doc(FIRESTORE_DOC).set(doc, { merge: true });
  _memCache = { token: doc.accessToken, refreshToken: doc.refreshToken, expiresAt: doc.expiresAt };
}

let _memCache = { token: null, refreshToken: null, expiresAt: 0 };

/**
 * Returns a valid access token with the eats.pos_provisioning scope.
 * Uses an in-memory cache, falls back to Firestore, and auto-refreshes
 * when the token is near expiry.
 *
 * Throws if the merchant has not completed the authorization_code flow yet.
 */
async function getProvisioningToken() {
  if (_memCache.token && Date.now() < _memCache.expiresAt - REFRESH_BUFFER_MS) {
    return _memCache.token;
  }

  const db = admin.firestore();
  const snap = await db.doc(FIRESTORE_DOC).get();
  if (!snap.exists || !snap.data().refreshToken) {
    throw new Error(
      "Uber OAuth tokens not found. The store owner must complete the " +
      "authorization flow first (visit the uberOAuthStart URL).",
    );
  }

  const stored = snap.data();

  if (stored.accessToken && Date.now() < (stored.expiresAt || 0) - REFRESH_BUFFER_MS) {
    _memCache = {
      token: stored.accessToken,
      refreshToken: stored.refreshToken,
      expiresAt: stored.expiresAt,
    };
    return stored.accessToken;
  }

  const refreshed = await refreshAccessToken(stored.refreshToken);
  return refreshed.access_token;
}

function clearProvisioningCache() {
  _memCache = { token: null, refreshToken: null, expiresAt: 0 };
}

module.exports = {
  buildAuthorizationUrl,
  exchangeCodeForTokens,
  refreshAccessToken,
  getProvisioningToken,
  clearProvisioningCache,
  PROVISIONING_SCOPES,
};
