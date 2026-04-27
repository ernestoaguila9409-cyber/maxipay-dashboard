import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp } from "@/lib/firebaseAdmin";
import { loadIposHppCredentials } from "@/lib/onlineOrderingServer";

export const runtime = "nodejs";

const SANDBOX_QUERY_URL = "https://api.ipospays.tech/v1/queryPaymentStatus";
const PROD_QUERY_URL = "https://api.ipospays.com/v1/queryPaymentStatus";

function queryBaseUrl(hppBaseUrl: string): string {
  const base = hppBaseUrl.trim() || (process.env.IPOS_HPP_BASE_URL || "").trim();
  if (base.includes("ipospays.com")) return PROD_QUERY_URL;
  if (base) return SANDBOX_QUERY_URL;
  return PROD_QUERY_URL;
}

/**
 * Creates a Transaction document for an HPP ecommerce payment so the Android
 * POS can reference it for void / refund / receipt flows.
 */
async function createEcommerceTransaction(
  db: admin.firestore.Firestore,
  orderId: string,
  order: admin.firestore.DocumentData,
  hpResp: Record<string, unknown>,
): Promise<string> {
  const totalInCents = (order.totalInCents as number) || 0;
  const txRef = db.collection("Transactions").doc();

  const payments = [
    {
      paymentType: "CREDIT",
      cardBrand: (hpResp.cardType as string) || "",
      last4: String(hpResp.cardLast4Digit || ""),
      authCode: (hpResp.responseApprovalCode as string) || "",
      entryType: "ECOMMERCE",
      amountInCents: totalInCents,
      referenceId: (hpResp.transactionId as string) || "",
    },
  ];

  await txRef.set({
    orderId,
    orderNumber: order.orderNumber || 0,
    type: "SALE",
    totalPaidInCents: totalInCents,
    totalPaid: totalInCents / 100,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    voided: false,
    settled: false,
    ecommerce: true,
    payments,
  });

  return txRef.id;
}

export async function POST(request: Request) {
  try {
    getFirebaseAdminApp();
    const db = admin.firestore();

    const body = (await request.json()) as {
      orderId?: string;
      iposRedirect?: Record<string, string>;
    };
    const orderId = body.orderId;
    if (!orderId || typeof orderId !== "string") {
      return NextResponse.json({ error: "orderId is required." }, { status: 400 });
    }
    const iposRedirect = body.iposRedirect ?? null;

    const creds = await loadIposHppCredentials(db);
    const { tpn, queryApiKey: apiKey } = creds;

    const orderDoc = await db.collection("Orders").doc(orderId).get();
    if (!orderDoc.exists) {
      return NextResponse.json({ error: "Order not found." }, { status: 404 });
    }

    const order = orderDoc.data()!;

    if (order.status === "CLOSED" || order.status === "PAID") {
      return NextResponse.json({ status: "PAID", message: "Already confirmed." });
    }

    const txRefId = order.hppTransactionRefId;
    if (!txRefId) {
      return NextResponse.json({ error: "No HPP payment link for this order." }, { status: 400 });
    }

    // --- 1. Try the queryPaymentStatus API (if credentials available) ---
    let apiSuccess = false;
    let hpResp: Record<string, unknown> = {};

    if (tpn && apiKey) {
      try {
        const url = `${queryBaseUrl(creds.hppBaseUrl)}?tpn=${encodeURIComponent(tpn)}&transactionReferenceId=${encodeURIComponent(txRefId)}`;
        const resp = await fetch(url, {
          method: "GET",
          headers: { Authorization: apiKey },
          signal: AbortSignal.timeout(8000),
        });
        const respBody = await resp.json();
        hpResp = (respBody?.iposHPResponse || respBody) as Record<string, unknown>;
        apiSuccess = Number(hpResp.responseCode) === 200;
      } catch (e) {
        console.warn("[confirm-hpp-payment] queryPaymentStatus failed, will try redirect params", e);
      }
    }

    // Webhook may have closed the order while we queried.
    const orderAfterQuery = await db.collection("Orders").doc(orderId).get();
    const fresh = orderAfterQuery.data() || order;
    if (fresh.status === "CLOSED" || fresh.status === "PAID") {
      return NextResponse.json({ status: "PAID", message: "Already confirmed." });
    }

    // --- 2. Fallback: use iPOSpays redirect params from the client ---
    // iPOSpays redirects the customer with responseCode=200 in the URL when
    // payment succeeds, but their queryPaymentStatus API may lag by seconds.
    // We trust the redirect params when either:
    //   (a) transactionReferenceId in the redirect matches the stored one, OR
    //   (b) no transactionReferenceId in the redirect but the order already has
    //       an hppPaymentUrl (i.e. the HPP link was legitimately created).
    if (!apiSuccess && iposRedirect) {
      const redirectCode = Number(iposRedirect.responseCode);
      const redirectTxRef = (iposRedirect.transactionReferenceId || "").trim();
      const storedTxRef = String(txRefId || "").trim();
      const txRefMatches = redirectTxRef && storedTxRef && redirectTxRef === storedTxRef;
      const hasHppLink = !!fresh.hppPaymentUrl;
      if (redirectCode === 200 && (txRefMatches || (!redirectTxRef && hasHppLink))) {
        apiSuccess = true;
        hpResp = { ...iposRedirect };
      }
    }

    if (apiSuccess && fresh.status !== "CLOSED" && fresh.status !== "PAID") {
      const saleTransactionId = await createEcommerceTransaction(db, orderId, fresh, hpResp);

      await db.collection("Orders").doc(orderId).update({
        hppStatus: "PAID",
        hppResponseCode: Number(hpResp.responseCode) || 200,
        hppResponseMessage: hpResp.responseMessage || "",
        hppCardType: hpResp.cardType || "",
        hppCardLast4: hpResp.cardLast4Digit || "",
        hppTransactionId: hpResp.transactionId || "",
        hppTransactionNumber: hpResp.transactionNumber || "",
        hppBatchNumber: hpResp.batchNumber || "",
        hppApprovalCode: hpResp.responseApprovalCode || "",
        hppTotalAmount: hpResp.totalAmount || 0,
        hppRespondedAt: admin.firestore.FieldValue.serverTimestamp(),
        totalPaidInCents: (fresh.totalInCents as number) || 0,
        remainingInCents: 0,
        status: "CLOSED",
        saleTransactionId,
        paidAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      });

      return NextResponse.json({ status: "PAID", message: "Payment confirmed." });
    }

    return NextResponse.json({
      status: apiSuccess ? "PAID" : "PENDING",
      responseCode: Number(hpResp.responseCode) || 0,
      responseMessage: (hpResp.responseMessage as string) || "",
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error("[confirm-hpp-payment]", msg);
    return NextResponse.json({ error: "Failed to confirm payment." }, { status: 500 });
  }
}
