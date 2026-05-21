const admin = require("firebase-admin");
const logger = require("firebase-functions/logger");
const OAuthClient = require("intuit-oauth");

const FIRESTORE_DOC = "Settings/qboOAuth";
const REDIRECT_URI =
  "https://us-central1-restaurantapp-180da.cloudfunctions.net/qboCallback";
const REFRESH_BUFFER_MS = 5 * 60 * 1000;

function qboEnvironment() {
  return process.env.QBO_ENV === "production" ? "production" : "sandbox";
}

function createOAuthClient() {
  const clientId = process.env.QBO_CLIENT_ID;
  const clientSecret = process.env.QBO_CLIENT_SECRET;
  if (!clientId || !clientSecret) {
    throw new Error("QBO_CLIENT_ID and QBO_CLIENT_SECRET must be set");
  }
  return new OAuthClient({
    clientId,
    clientSecret,
    environment: qboEnvironment(),
    redirectUri: REDIRECT_URI,
  });
}

async function persistTokens(token, realmId) {
  const expiresIn = Number(token.expires_in) || 3600;
  const resolvedRealmId = String(realmId || token.realmId || "");
  await admin.firestore().doc(FIRESTORE_DOC).set(
    {
      realmId: resolvedRealmId,
      accessToken: token.access_token,
      refreshToken: token.refresh_token,
      scope: token.scope || OAuthClient.scopes.Accounting,
      expiresAt: Date.now() + expiresIn * 1000,
      environment: qboEnvironment(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    },
    { merge: true },
  );
}

/**
 * Returns an OAuthClient with a valid access token and the connected realmId.
 */
async function getAuthenticatedOAuthClient() {
  const snap = await admin.firestore().doc(FIRESTORE_DOC).get();
  if (!snap.exists || !snap.data().refreshToken) {
    throw new Error(
      "QuickBooks is not connected. Open startQBOAuth in the browser first.",
    );
  }

  const stored = snap.data();
  const realmId = String(stored.realmId || "");
  const oauthClient = createOAuthClient();

  const needsRefresh =
    !stored.accessToken ||
    !stored.expiresAt ||
    Date.now() >= stored.expiresAt - REFRESH_BUFFER_MS;

  if (!needsRefresh) {
    const remainingSec = Math.max(
      60,
      Math.floor((stored.expiresAt - Date.now()) / 1000),
    );
    oauthClient.setToken({
      access_token: stored.accessToken,
      refresh_token: stored.refreshToken,
      token_type: "bearer",
      expires_in: remainingSec,
      createdAt: Date.now(),
      realmId,
    });
    return { oauthClient, realmId };
  }

  oauthClient.setToken({
    access_token: stored.accessToken || "",
    refresh_token: stored.refreshToken,
    token_type: "bearer",
    expires_in: 0,
    createdAt: 0,
    realmId,
  });

  const authResponse = await oauthClient.refresh();
  const token = authResponse.getToken();
  await persistTokens(token, realmId);
  logger.info("[qbo-auth] Access token refreshed", { realmId });
  return { oauthClient, realmId };
}

function pickNonEmptyString(value) {
  if (value == null) return null;
  const s = String(value).trim();
  return s.length > 0 ? s : null;
}

function parseApiJsonBody(response) {
  let body = response.json ?? response.data ?? response.body;
  if (typeof body === "string" && body.trim()) {
    try {
      body = JSON.parse(body);
    } catch {
      /* keep string */
    }
  }
  return body;
}

function extractCompanyRecord(body) {
  if (!body || typeof body !== "object") return null;
  let company = body.CompanyInfo ?? body.companyInfo;
  if (!company && body.QueryResponse) {
    company = body.QueryResponse.CompanyInfo ?? body.QueryResponse.companyInfo;
  }
  if (Array.isArray(company)) return company[0] || null;
  return company && typeof company === "object" ? company : null;
}

function companyDisplayName(company) {
  if (!company || typeof company !== "object") return null;
  return (
    pickNonEmptyString(company.CompanyName) ||
    pickNonEmptyString(company.LegalName) ||
    pickNonEmptyString(company.DisplayName) ||
    pickNonEmptyString(company.Name) ||
    pickNonEmptyString(company.CompanyAddr?.Line1) ||
    null
  );
}

/**
 * Fetches CompanyInfo from QuickBooks (sandbox or production).
 */
async function getCompanyInfo() {
  const { oauthClient, realmId } = await getAuthenticatedOAuthClient();
  const baseUrl = oauthClient.getQBOEnvironmentURI();
  const url =
    `${baseUrl}/v3/company/${realmId}/companyinfo/${realmId}?minorversion=73`;

  const response = await oauthClient.makeApiCall({
    url,
    method: "GET",
    headers: { Accept: "application/json" },
  });

  const body = parseApiJsonBody(response);
  const company = extractCompanyRecord(body);
  const companyName = companyDisplayName(company);

  return {
    realmId,
    companyName,
    environment: qboEnvironment(),
    companyInfo: company,
  };
}

module.exports = {
  FIRESTORE_DOC,
  REDIRECT_URI,
  createOAuthClient,
  persistTokens,
  getAuthenticatedOAuthClient,
  getCompanyInfo,
  parseApiJsonBody,
};
