import admin from "firebase-admin";
import { NextResponse } from "next/server";
import { getFirebaseAdminApp } from "@/lib/firebaseAdmin";

export const runtime = "nodejs";

/**
 * Public read of minimal order fields for the customer success page (polling).
 * Caller must know both [orderId] and [orderNumber]; they must match the same document.
 */
export async function GET(request: Request) {
  try {
    getFirebaseAdminApp();
    const { searchParams } = new URL(request.url);
    const orderId = searchParams.get("orderId")?.trim();
    const orderNumRaw = searchParams.get("orderNumber")?.trim();
    if (!orderId || !orderNumRaw) {
      return NextResponse.json({ error: "orderId and orderNumber are required." }, { status: 400 });
    }
    const orderNumber = Number(orderNumRaw);
    if (!Number.isFinite(orderNumber)) {
      return NextResponse.json({ error: "Invalid orderNumber." }, { status: 400 });
    }

    const db = admin.firestore();
    const snap = await db.collection("Orders").doc(orderId).get();
    if (!snap.exists) {
      return NextResponse.json({ error: "Order not found." }, { status: 404 });
    }
    const d = snap.data() ?? {};
    const docNum = typeof d.orderNumber === "number" ? d.orderNumber : Number(d.orderNumber);
    if (!Number.isFinite(docNum) || docNum !== orderNumber) {
      return NextResponse.json({ error: "Order number does not match this order." }, { status: 404 });
    }

    const status = String(d.status ?? "").toUpperCase();
    const voided = d.voided === true;
    const awaitingStaff = d.awaitingStaffConfirmOrder === true;
    const rawChoice = String(d.onlinePaymentChoice ?? "");
    const onlinePaymentChoice =
      rawChoice === "PAY_ONLINE_HPP"
        ? "PAY_ONLINE_HPP"
        : rawChoice === "PAY_AT_STORE"
          ? "PAY_AT_STORE"
          : "";

    return NextResponse.json({
      ok: true,
      status,
      voided,
      awaitingStaffConfirmOrder: awaitingStaff,
      onlinePaymentChoice,
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error("[online-ordering/order-status]", msg);
    return NextResponse.json({ error: "Server error.", detail: msg }, { status: 500 });
  }
}
