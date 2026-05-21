const admin = require("firebase-admin");
const logger = require("firebase-functions/logger");
const { FIRESTORE_DOC, getAuthenticatedOAuthClient, parseApiJsonBody } = require("./qbo-auth");

const MINOR_VERSION = 73;

function centsToDollars(cents) {
  return Math.round(Number(cents) || 0) / 100;
}

async function qboApiCall(oauthClient, realmId, { method, path, body }) {
  const baseUrl = oauthClient.getQBOEnvironmentURI();
  const url = path.startsWith("http")
    ? path
    : `${baseUrl}/v3/company/${realmId}${path}${path.includes("?") ? "&" : "?"}minorversion=${MINOR_VERSION}`;

  const response = await oauthClient.makeApiCall({
    url,
    method: method || "GET",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  return parseApiJsonBody(response);
}

async function runQuery(oauthClient, realmId, query) {
  const encoded = encodeURIComponent(query);
  return qboApiCall(oauthClient, realmId, {
    method: "GET",
    path: `/query?query=${encoded}`,
  });
}

function firstQueryEntity(body, entityName) {
  const rows = body?.QueryResponse?.[entityName];
  if (Array.isArray(rows)) return rows[0] || null;
  return rows && typeof rows === "object" ? rows : null;
}

async function findActiveServiceItem(oauthClient, realmId) {
  const serviceQuery =
    "select Id, Name from Item where Active = true and Type = 'Service' maxresults 1";
  const serviceBody = await runQuery(oauthClient, realmId, serviceQuery);
  return firstQueryEntity(serviceBody, "Item");
}

async function findIncomeAccountRef(oauthClient, realmId) {
  const queries = [
    "select Id, Name from Account where Active = true and Classification = 'Revenue' maxresults 1",
    "select Id, Name from Account where Active = true and AccountType = 'Income' maxresults 1",
    "select Id, Name from Account where Name = 'Sales' maxresults 1",
  ];

  for (const accountQuery of queries) {
    const accountBody = await runQuery(oauthClient, realmId, accountQuery);
    const account = firstQueryEntity(accountBody, "Account");
    if (account?.Id) {
      return { value: String(account.Id), name: account.Name || "Sales" };
    }
  }

  const fallbackBody = await runQuery(
    oauthClient,
    realmId,
    "select Id, Name, AccountType, Classification from Account where Active = true maxresults 100",
  );
  const accounts = fallbackBody?.QueryResponse?.Account;
  const list = Array.isArray(accounts) ? accounts : accounts ? [accounts] : [];
  const revenue = list.find(
    (a) =>
      a.Classification === "Revenue" ||
      a.AccountType === "Income" ||
      (a.Name && /sales|income|service|revenue/i.test(String(a.Name))),
  );
  if (revenue?.Id) {
    return { value: String(revenue.Id), name: revenue.Name || "Sales" };
  }

  throw new Error(
    "No Income/Revenue account found in QuickBooks sandbox. In QBO go to Settings → Chart of accounts and add an Income account named Sales.",
  );
}

async function createDefaultServiceItem(oauthClient, realmId) {
  const incomeAccountRef = await findIncomeAccountRef(oauthClient, realmId);
  const body = await qboApiCall(oauthClient, realmId, {
    method: "POST",
    path: "/item",
    body: {
      Item: {
        Name: "MaxiPay POS Sales",
        Type: "Service",
        IncomeAccountRef: incomeAccountRef,
      },
    },
  });
  const item = body?.Item;
  if (!item?.Id) {
    throw new Error("Failed to create default Service item in QuickBooks");
  }
  logger.info("[qbo-api] Created default Service item", {
    id: item.Id,
    name: item.Name,
  });
  return { value: String(item.Id), name: item.Name || "MaxiPay POS Sales" };
}

/**
 * Resolves a QBO Item to use on SalesReceipt lines (cached in Settings/qboOAuth).
 */
async function getDefaultSalesItemRef() {
  const db = admin.firestore();
  const settingsRef = db.doc(FIRESTORE_DOC);
  const snap = await settingsRef.get();
  const stored = snap.data() || {};

  if (stored.defaultSalesItemId) {
    return {
      value: String(stored.defaultSalesItemId),
      name: stored.defaultSalesItemName || "POS Sales",
    };
  }

  const { oauthClient, realmId } = await getAuthenticatedOAuthClient();

  let item = await findActiveServiceItem(oauthClient, realmId);
  if (!item?.Id) {
    logger.info("[qbo-api] No Service item in QBO; creating MaxiPay POS Sales");
    const created = await createDefaultServiceItem(oauthClient, realmId);
    await settingsRef.set(
      {
        defaultSalesItemId: created.value,
        defaultSalesItemName: created.name,
      },
      { merge: true },
    );
    return created;
  }

  const itemRef = { value: String(item.Id), name: item.Name || "POS Sales" };
  await settingsRef.set(
    {
      defaultSalesItemId: itemRef.value,
      defaultSalesItemName: itemRef.name,
    },
    { merge: true },
  );

  logger.info("[qbo-api] Cached default sales item", itemRef);
  return itemRef;
}

/**
 * @param {object} salesReceiptPayload QuickBooks SalesReceipt entity
 * @returns {{ id: string, docNumber: string | null, payload: object }}
 */
async function createSalesReceipt(salesReceiptPayload) {
  const { oauthClient, realmId } = await getAuthenticatedOAuthClient();
  const body = await qboApiCall(oauthClient, realmId, {
    method: "POST",
    path: "/salesreceipt",
    body: { SalesReceipt: salesReceiptPayload },
  });

  const receipt = body?.SalesReceipt;
  if (!receipt?.Id) {
    logger.error("[qbo-api] Unexpected SalesReceipt response", {
      body: JSON.stringify(body).substring(0, 500),
    });
    throw new Error("QuickBooks did not return a SalesReceipt Id");
  }

  return {
    id: String(receipt.Id),
    docNumber: receipt.DocNumber ? String(receipt.DocNumber) : null,
    payload: receipt,
  };
}

module.exports = {
  MINOR_VERSION,
  centsToDollars,
  qboApiCall,
  getDefaultSalesItemRef,
  createSalesReceipt,
};
