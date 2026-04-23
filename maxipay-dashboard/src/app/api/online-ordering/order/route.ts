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

interface OrderBody {
  lines?: CartLineInput[];
  customerName?: string;
  customerPhone?: string;
  customerEmail?: string;
  paymentChoice?: string;
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
    } else {
      return NextResponse.json(
        { error: "Invalid paymentChoice. Use PAY_AT_STORE or REQUEST_TERMINAL_FROM_WEB." },
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

    return NextResponse.json({
      ok: true,
      orderId: created.orderId,
      orderNumber: created.orderNumber,
      totalInCents: created.totalInCents,
      paymentChoice,
      message:
        paymentChoice === "PAY_AT_STORE"
          ? "Order placed. Pay when you pick up — staff will use the Dejavoo terminal (SPIn)."
          : "Order placed. The restaurant POS has been notified to take card payment on the Dejavoo terminal (SPIn) when you arrive.",
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
