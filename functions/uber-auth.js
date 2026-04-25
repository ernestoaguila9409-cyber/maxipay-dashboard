const logger = require("firebase-functions/logger");

const SANDBOX_TOKEN_URL = "https://sandbox-login.uber.com/oauth/v2/token";
const PROD_TOKEN_URL = "https://auth.uber.com/oauth/v2/token";
const ALL_SCOPES = "eats.order eats.store eats.report";

function isSandbox() {
  return process.env.UBER_ENV !== "production";
}

function getTokenUrl() {
  return isSandbox() ? SANDBOX_TOKEN_URL : PROD_TOKEN_URL;
}

// Refresh 1 hour before expiry to avoid edge-case failures
const REFRESH_BUFFER_MS = 60 * 60 * 1000;

let _cached = { token: null, expiresAt: 0 };

/**
 * Returns an OAuth2 Bearer token using the client-credentials grant.
 * Tokens are cached in memory and reused across invocations within the
 * same Cloud Functions instance (warm start).
 */
async function getAccessToken() {
  if (_cached.token && Date.now() < _cached.expiresAt - REFRESH_BUFFER_MS) {
    return _cached.token;
  }

  const clientId = process.env.UBER_CLIENT_ID;
  const clientSecret = process.env.UBER_CLIENT_SECRET;

  if (!clientId || !clientSecret) {
    throw new Error(
      "UBER_CLIENT_ID and UBER_CLIENT_SECRET must be set. " +
      "Run: firebase functions:secrets:set UBER_CLIENT_ID && " +
      "firebase functions:secrets:set UBER_CLIENT_SECRET",
    );
  }

  const tokenUrl = getTokenUrl();
  logger.info("[uber-auth] Requesting new access token", {
    sandbox: isSandbox(), tokenUrl,
  });

  const res = await fetch(tokenUrl, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "client_credentials",
      client_id: clientId,
      client_secret: clientSecret,
      scope: ALL_SCOPES,
    }),
  });

  if (!res.ok) {
    const text = await res.text();
    logger.error("[uber-auth] Token request failed", {
      status: res.status,
      body: text.substring(0, 500),
    });
    throw new Error(`Uber OAuth token request failed: ${res.status} — ${text.substring(0, 200)}`);
  }

  const data = await res.json();

  _cached = {
    token: data.access_token,
    expiresAt: Date.now() + (data.expires_in || 2592000) * 1000,
  };

  logger.info("[uber-auth] Token acquired", {
    expiresIn: data.expires_in,
    scope: data.scope,
  });

  return _cached.token;
}

/** Invalidate the cached token (e.g. after a 401 response). */
function clearTokenCache() {
  _cached = { token: null, expiresAt: 0 };
}

module.exports = { getAccessToken, clearTokenCache, isSandbox };
