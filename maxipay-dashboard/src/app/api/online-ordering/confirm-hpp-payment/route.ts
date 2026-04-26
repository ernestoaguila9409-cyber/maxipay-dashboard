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

    const body = (await request.json()) as { orderId?: string };
    const orderId = body.orderId;
    if (!orderId || typeof orderId !== "string") {
      return NextResponse.json({ error: "orderId is required." }, { status: 400 });
    }

    const creds = await loadIposHppCredentials(db);
    const { tpn, queryApiKey: apiKey } = creds;
    if (!tpn || !apiKey) {
      return NextResponse.json({ error: "Payment service not configured." }, { status: 500 });
    }

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

    const url = `${queryBaseUrl(creds.hppBaseUrl)}?tpn=${encodeURIComponent(tpn)}&transactionReferenceId=${encodeURIComponent(txRefId)}`;

    const resp = await fetch(url, {
      method: "GET",
      headers: { Authorization: apiKey },
    });

    const respBody = await resp.json();
    const hpResp = respBody?.iposHPResponse || respBody;
    const responseCode = Number(hpResp.responseCode);
    const isSuccess = responseCode === 200;

    // Webhook may have already closed the order while queryPaymentStatus still
    // returns pending / non-200 for a short window — treat paid order as success.
    const orderAfterQuery = await db.collection("Orders").doc(orderId).get();
    const fresh = orderAfterQuery.data() || order;
    if (fresh.status === "CLOSED" || fresh.status === "PAID") {
      return NextResponse.json({ status: "PAID", message: "Already confirmed." });
    }

    if (isSuccess && fresh.status !== "CLOSED" && fresh.status !== "PAID") {
      const saleTransactionId = await createEcommerceTransaction(db, orderId, fresh, hpResp as Record<string, unknown>);

      await db.collection("Orders").doc(orderId).update({
        hppStatus: "PAID",
        hppResponseCode: responseCode,
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
      status: isSuccess ? "PAID" : "PENDING",
      responseCode,
      responseMessage: hpResp.responseMessage || "",
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error("[confirm-hpp-payment]", msg);
    return NextResponse.json({ error: "Failed to confirm payment." }, { status: 500 });
  }
}
