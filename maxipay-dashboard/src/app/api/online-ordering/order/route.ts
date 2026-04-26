import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp } from "@/lib/firebaseAdmin";
import {
  createOnlineOrderTransaction,
  loadPublicOnlineOrderingConfig,
  OnlineOrderValidationError,
  type CartLineInput,
  type OnlinePaymentChoice,
} from "@/lib/onlineOrderingServer";
import { ONLINE_TERMINAL_PAYMENT_REQUESTS } from "@/lib/onlineOrderingShared";

export const runtime = "nodejs";

const IPOS_HPP_SANDBOX = "https://payment.ipospays.tech/api/v1/external-payment-transaction";
const IPOS_HPP_PROD = "https://payment.ipospays.com/api/v1/external-payment-transaction";

function iposHppUrl(): string {
  return process.env.IPOS_HPP_BASE_URL || IPOS_HPP_PROD;
}

interface OrderBody {
  lines?: CartLineInput[];
  customerName?: string;
  customerPhone?: string;
  customerEmail?: string;
  paymentChoice?: string;
}

async function createHppPaymentUrl(
  db: admin.firestore.Firestore,
  orderId: string,
  orderNumber: number,
  totalInCents: number,
  customerName: string,
  customerEmail: string,
  customerPhone: string,
  slug: string,
): Promise<string> {
  const tpn = (process.env.IPOS_HPP_TPN || "").trim();
  const authToken = (process.env.IPOS_HPP_AUTH_TOKEN || "").trim();
  if (!tpn || !authToken) throw new Error("iPOSpays HPP is not configured (missing TPN or auth token).");

  const appUrl = (process.env.NEXT_PUBLIC_APP_URL || "").trim();
  const base = appUrl || "https://www.maxipaypos.com";
  const returnUrl = `${base}/order/${slug}/success?orderNumber=${orderNumber}&orderId=${orderId}`;
  const failureUrl = `${base}/order/${slug}?orderId=${orderId}&paymentFailed=1`;
  const cancelUrl = `${base}/order/${slug}?orderId=${orderId}&paymentCancelled=1`;

  const txRefId = `OO${orderId.substring(0, 16)}`;

  let webhookUrl = "";
  const projectId = process.env.GCLOUD_PROJECT || process.env.GCP_PROJECT || process.env.FIREBASE_PROJECT_ID || "";
  if (projectId) {
    webhookUrl = `https://us-central1-${projectId}.cloudfunctions.net/iposPaymentWebhook`;
  }

  let bizName = "MaxiPay";
  let logoUrl = "";
  const bizSnap = await db.collection("Settings").doc("businessInfo").get();
  if (bizSnap.exists) {
    const biz = bizSnap.data() || {};
    if (biz.businessName) bizName = String(biz.businessName);
    if (biz.logoUrl && String(biz.logoUrl).startsWith("http")) logoUrl = String(biz.logoUrl);
  }

  const payload = {
    merchantAuthentication: {
      merchantId: tpn,
      transactionReferenceId: txRefId,
    },
    transactionRequest: {
      transactionType: 1,
      amount: String(totalInCents),
      calculateFee: false,
      tipsInputPrompt: false,
      calculateTax: false,
      txReferenceTag1: {
        tagLabel: "Order",
        tagValue: String(orderNumber),
        isTagMandate: false,
      },
      expiry: 1,
    },
    notificationOption: {
      notificationBySMS: false,
      mobileNumber: "",
      notifyByPOST: !!webhookUrl,
      authHeader: webhookUrl ? authToken : "",
      postAPI: webhookUrl,
      notifyByRedirect: true,
      returnUrl,
      failureUrl,
      cancelUrl,
    },
    preferences: {
      integrationType: 1,
      avsVerification: false,
      eReceipt: !!(customerEmail || customerPhone),
      eReceiptInputPrompt: false,
      customerName: customerName || "Customer",
      customerEmail: customerEmail || "",
      customerMobile: customerPhone || "",
      requestCardToken: false,
      shortenURL: true,
      sendPaymentLink: false,
      integrationVersion: "v2",
    },
    personalization: {
      merchantName: bizName,
      ...(logoUrl ? { logoUrl } : {}),
      themeColor: "#000000",
      description: `Order #${orderNumber}`,
      payNowButtonText: "Pay Now",
      buttonColor: "#000000",
      cancelButtonText: "Cancel",
    },
  };

  const resp = await fetch(iposHppUrl(), {
    method: "POST",
    headers: { "Content-Type": "application/json", token: authToken },
    body: JSON.stringify(payload),
  });

  const body = await resp.json();

  if (!body.information) {
    const errMsg = Array.isArray(body.errors)
      ? body.errors.map((e: { field?: string; message?: string }) => `${e.field}: ${e.message}`).join("; ")
      : "Payment page generation failed.";
    throw new Error(errMsg);
  }

  await db.collection("Orders").doc(orderId).update({
    hppTransactionRefId: txRefId,
    hppPaymentUrl: body.information,
    hppCreatedAt: admin.firestore.FieldValue.serverTimestamp(),
    hppStatus: "PENDING",
  });

  return body.information as string;
}

export async function POST(request: Request) {
  try {
    getFirebaseAdminApp();
    const db = admin.firestore();
    const cfg = await loadPublicOnlineOrderingConfig(db);
    if (!cfg.enabled) {
      return NextResponse.json({ error: "Online ordering is disabled." }, { status: 403 });
    }

    let body: OrderBody;
    try {
      body = (await request.json()) as OrderBody;
    } catch {
      return NextResponse.json({ error: "Invalid JSON body." }, { status: 400 });
    }

    const lines = Array.isArray(body.lines) ? body.lines : [];
    const customerName = typeof body.customerName === "string" ? body.customerName : "";
    const customerPhone = typeof body.customerPhone === "string" ? body.customerPhone : "";
    const customerEmail = typeof body.customerEmail === "string" ? body.customerEmail : "";
    const rawChoice = body.paymentChoice;

    let paymentChoice: OnlinePaymentChoice;
    if (rawChoice === "PAY_AT_STORE") {
      if (!cfg.allowPayInStore) {
        return NextResponse.json({ error: "Pay at store is not enabled." }, { status: 400 });
      }
      paymentChoice = "PAY_AT_STORE";
    } else if (rawChoice === "REQUEST_TERMINAL_FROM_WEB") {
      if (!cfg.allowRequestTerminalFromWeb) {
        return NextResponse.json({ error: "Pay on POS terminal is not enabled." }, { status: 400 });
      }
      paymentChoice = "REQUEST_TERMINAL_FROM_WEB";
    } else if (rawChoice === "PAY_ONLINE_HPP") {
      if (!cfg.allowPayOnlineHpp) {
        return NextResponse.json({ error: "Online card payment is not enabled." }, { status: 400 });
      }
      paymentChoice = "PAY_ONLINE_HPP";
    } else {
      return NextResponse.json(
        { error: "Invalid paymentChoice." },
        { status: 400 }
      );
    }

    const created = await createOnlineOrderTransaction(db, {
      lines,
      customerName,
      customerPhone,
      customerEmail,
      paymentChoice,
    });

    if (paymentChoice === "REQUEST_TERMINAL_FROM_WEB") {
      await db.collection(ONLINE_TERMINAL_PAYMENT_REQUESTS).doc(created.orderId).set({
        orderId: created.orderId,
        orderNumber: created.orderNumber,
        totalInCents: created.totalInCents,
        status: "pending",
        requestedAt: admin.firestore.FieldValue.serverTimestamp(),
        source: "online_ordering",
      });
    }

    if (paymentChoice === "PAY_ONLINE_HPP") {
      try {
        const paymentUrl = await createHppPaymentUrl(
          db,
          created.orderId,
          created.orderNumber,
          created.totalInCents,
          customerName,
          customerEmail,
          customerPhone,
          cfg.slug,
        );
        return NextResponse.json({
          ok: true,
          orderId: created.orderId,
          orderNumber: created.orderNumber,
          totalInCents: created.totalInCents,
          paymentChoice,
          paymentUrl,
          message: "Redirecting to payment page…",
        });
      } catch (hppErr) {
        const msg = hppErr instanceof Error ? hppErr.message : String(hppErr);
        console.error("[online-ordering/order] HPP error:", msg);
        return NextResponse.json({
          ok: true,
          orderId: created.orderId,
          orderNumber: created.orderNumber,
          totalInCents: created.totalInCents,
          paymentChoice,
          paymentUrl: null,
          hppError: msg,
          message: "Order placed but payment link generation failed. Please contact the store.",
        });
      }
    }

    return NextResponse.json({
      ok: true,
      orderId: created.orderId,
      orderNumber: created.orderNumber,
      totalInCents: created.totalInCents,
      paymentChoice,
      message:
        paymentChoice === "PAY_AT_STORE"
          ? "Order placed. Pay when you pick up."
          : "Order placed. The POS was notified — staff will run payment when you arrive.",
    });
  } catch (e) {
    if (e instanceof OnlineOrderValidationError) {
      return NextResponse.json({ error: e.message }, { status: 400 });
    }
    const msg = e instanceof Error ? e.message : String(e);
    console.error("[online-ordering/order]", msg);
    return NextResponse.json({ error: "Could not create order.", detail: msg }, { status: 500 });
  }
}
