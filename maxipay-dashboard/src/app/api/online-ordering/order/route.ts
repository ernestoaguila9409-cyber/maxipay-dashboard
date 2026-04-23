import admin from "firebase-admin";
import { NextResponse } from "next/server";
import Stripe from "stripe";
import { getFirebaseAdminApp } from "@/lib/firebaseAdmin";
import {
  createOnlineOrderTransaction,
  loadPublicOnlineOrderingConfig,
  OnlineOrderValidationError,
  type CartLineInput,
  type OnlinePaymentChoice,
} from "@/lib/onlineOrderingServer";

export const runtime = "nodejs";

function siteOrigin(request: Request): string {
  const fromEnv = process.env.NEXT_PUBLIC_APP_URL?.trim();
  if (fromEnv) return fromEnv.replace(/\/$/, "");
  const u = new URL(request.url);
  return `${u.protocol}//${u.host}`;
}

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
    } else if (rawChoice === "PAY_ONLINE_STRIPE") {
      if (!cfg.allowPayOnlineStripe) {
        return NextResponse.json({ error: "Online card payment is not enabled." }, { status: 400 });
      }
      if (!process.env.STRIPE_SECRET_KEY?.trim()) {
        return NextResponse.json(
          { error: "Stripe is not configured on the server (missing STRIPE_SECRET_KEY)." },
          { status: 503 }
        );
      }
      paymentChoice = "PAY_ONLINE_STRIPE";
    } else {
      return NextResponse.json(
        { error: "Invalid paymentChoice. Use PAY_AT_STORE or PAY_ONLINE_STRIPE." },
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

    if (paymentChoice === "PAY_AT_STORE") {
      return NextResponse.json({
        ok: true,
        orderId: created.orderId,
        orderNumber: created.orderNumber,
        totalInCents: created.totalInCents,
        paymentChoice,
        message:
          "Order placed. Pay at the restaurant when you pick up — staff will ring the total on the Dejavoo terminal (SPIn).",
      });
    }

    const stripe = new Stripe(process.env.STRIPE_SECRET_KEY!);

    const origin = siteOrigin(request);
    const session = await stripe.checkout.sessions.create({
      mode: "payment",
      client_reference_id: created.orderId,
      metadata: {
        orderId: created.orderId,
        orderNumber: String(created.orderNumber),
      },
      line_items: [
        {
          price_data: {
            currency: "usd",
            unit_amount: created.totalInCents,
            product_data: {
              name: `${cfg.businessName} — Order #${created.orderNumber}`,
              description: "Online pickup order",
            },
          },
          quantity: 1,
        },
      ],
      success_url: `${origin}/order/success?session_id={CHECKOUT_SESSION_ID}&orderNumber=${created.orderNumber}`,
      cancel_url: `${origin}/order?cancelled=1`,
    });

    await db.collection("Orders").doc(created.orderId).update({
      stripeCheckoutSessionId: session.id,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    return NextResponse.json({
      ok: true,
      orderId: created.orderId,
      orderNumber: created.orderNumber,
      totalInCents: created.totalInCents,
      paymentChoice,
      checkoutUrl: session.url,
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
