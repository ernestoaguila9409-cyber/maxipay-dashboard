import { redirect } from "next/navigation";

/** Redirects old /payment-terminals path to the unified Payments & Devices page. */
export default function PaymentTerminalsRedirectPage() {
  redirect("/dashboard/settings/payments-devices");
}
