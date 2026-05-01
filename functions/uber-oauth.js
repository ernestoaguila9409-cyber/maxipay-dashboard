const { onRequest } = require("firebase-functions/v2/https");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const { randomUUID } = require("crypto");
const authCode = require("./uber-auth-code");

/**
 * Derives the callback URL from the request so we don't hard-code the
 * Cloud Function host. Works for both emulator and deployed functions.
 */
function callbackUrlFromReq(req) {
  const proto = req.headers["x-forwarded-proto"] || req.protocol || "https";
  const host = req.headers["x-forwarded-host"] || req.headers.host;
  const base = `${proto}://${host}`;
  return `${base}/uberOAuthCallback`;
}

// ---------------------------------------------------------------------------
// 1. Start the OAuth flow — redirects the merchant to Uber's consent page
// ---------------------------------------------------------------------------

exports.uberOAuthStart = onRequest(
  { maxInstances: 5, cors: false },
  (req, res) => {
    try {
      const state = randomUUID();
      const redirectUri = callbackUrlFromReq(req);
      const url = authCode.buildAuthorizationUrl(redirectUri, state);

      logger.info("[uber-oauth] Redirecting to Uber authorization", {
        redirectUri, state,
      });

      res.redirect(302, url);
    } catch (err) {
      logger.error("[uber-oauth] Failed to build auth URL", { err: err.message });
      res.status(500).json({ error: err.message });
    }
  },
);

// ---------------------------------------------------------------------------
// 2. OAuth callback — Uber redirects here with ?code=...&state=...
// ---------------------------------------------------------------------------

exports.uberOAuthCallback = onRequest(
  { maxInstances: 5, cors: false },
  async (req, res) => {
    const code = req.query.code;
    const error = req.query.error;

    if (error) {
      logger.error("[uber-oauth] Authorization denied by merchant", {
        error, description: req.query.error_description,
      });
      res.status(400).send(
        `<h2>Authorization denied</h2><p>${req.query.error_description || error}</p>`,
      );
      return;
    }

    if (!code) {
      res.status(400).send("<h2>Missing authorization code</h2>");
      return;
    }

    try {
      const proto = req.headers["x-forwarded-proto"] || req.protocol || "https";
      const host = req.headers["x-forwarded-host"] || req.headers.host;
      const redirectUri = `${proto}://${host}/uberOAuthCallback`;

      const tokens = await authCode.exchangeCodeForTokens(code, redirectUri);

      logger.info("[uber-oauth] OAuth flow completed", {
        scope: tokens.scope,
      });

      res.status(200).send(
        "<h2>Uber Eats authorization successful!</h2>" +
        "<p>The POS provisioning scope has been granted. You can close this window.</p>" +
        `<p><small>Scope: ${tokens.scope || "eats.pos_provisioning"}</small></p>`,
      );
    } catch (err) {
      logger.error("[uber-oauth] Token exchange failed", { err: err.message });
      res.status(500).send(
        `<h2>Token exchange failed</h2><p>${err.message}</p>`,
      );
    }
  },
);

// ---------------------------------------------------------------------------
// 3. Callable: check whether the OAuth flow has been completed
// ---------------------------------------------------------------------------

exports.uberOAuthStatus = onCall(async () => {
  try {
    const token = await authCode.getProvisioningToken();
    return { connected: true, hasToken: !!token };
  } catch {
    return { connected: false, hasToken: false };
  }
});
