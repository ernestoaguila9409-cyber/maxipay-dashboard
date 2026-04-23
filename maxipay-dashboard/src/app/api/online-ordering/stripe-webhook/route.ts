import admin from "firebase-admin";
import { NextResponse } from "next/server";
import Stripe from "stripe";
import { getFirebaseAdminApp } from "@/lib/firebaseAdmin";

export const runtime = "nodejs";

/**
 * Marks online orders paid after Stripe Checkout completes.
 * Configure `STRIPE_WEBHOOK_SECRET` and point your Stripe webhook to this URL.
 */
export async function POST(request: Request) {
  const secret = process.env.STRIPE_WEBHOOK_SECRET?.trim();
  if (!secret) {
    console.error("[stripe-webhook] STRIPE_WEBHOOK_SECRET is not set.");
    return NextResponse.json({ error: "Webhook not configured." }, { status: 503 });
  }
  const key = process.env.STRIPE_SECRET_KEY?.trim();
  if (!key) {
    return NextResponse.json({ error: "Stripe not configured." }, { status: 503 });
  }

  const rawBody = Buffer.from(await request.arrayBuffer());
  const sig = request.headers.get("stripe-signature");
  if (!sig) {
    return NextResponse.json({ error: "Missing stripe-signature." }, { status: 400 });
  }

  const stripe = new Stripe(key);
  let event: Stripe.Event;
  try {
    event = stripe.webhooks.constructEvent(rawBody, sig, secret);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.warn("[stripe-webhook] signature verify failed:", msg);
    return NextResponse.json({ error: "Invalid signature." }, { status: 400 });
  }

  if (event.type !== "checkout.session.completed") {
    return NextResponse.json({ received: true });
  }

  const session = event.data.object as Stripe.Checkout.Session;
  const orderId =
    (session.metadata?.orderId as string | undefined)?.trim() ||
    (session.client_reference_id as string | undefined)?.trim();
  if (!orderId) {
    console.warn("[stripe-webhook] checkout.session.completed without orderId metadata.");
    return NextResponse.json({ received: true });
  }

  const amountTotal = session.amount_total;
  if (amountTotal == null) {
    console.warn("[stripe-webhook] session missing amount_total", session.id);
    return NextResponse.json({ received: true });
  }

  try {
    getFirebaseAdminApp();
    const db = admin.firestore();
    const orderRef = db.collection("Orders").doc(orderId);
    const snap = await orderRef.get();
    if (!snap.exists) {
      console.warn("[stripe-webhook] order not found:", orderId);
      return NextResponse.json({ received: true });
    }
    const data = snap.data()!;
    if (data.orderSource !== "online_ordering") {
      console.warn("[stripe-webhook] order is not an online order:", orderId);
      return NextResponse.json({ received: true });
    }
    const totalInCents = (data.totalInCents as number | undefined) ?? 0;
    if (totalInCents !== amountTotal) {
      console.warn(
        "[stripe-webhook] amount mismatch order=%s firestore=%s stripe=%s",
        orderId,
        totalInCents,
        amountTotal
      );
    }

    const paidUpdate: Record<string, unknown> = {
      totalPaidInCents: amountTotal,
      remainingInCents: Math.max(0, totalInCents - amountTotal),
      paymentMethod: "STRIPE_ONLINE",
      stripeCheckoutSessionId: session.id,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    };
    if (session.payment_intent) {
      paidUpdate.stripePaymentIntentId = session.payment_intent.toString();
    }
    await orderRef.update(paidUpdate);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error("[stripe-webhook] firestore update failed:", msg);
    return NextResponse.json({ error: "Persist failed." }, { status: 500 });
  }

  return NextResponse.json({ received: true });
}
