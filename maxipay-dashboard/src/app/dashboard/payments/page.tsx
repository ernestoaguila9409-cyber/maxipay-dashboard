import { redirect } from "next/navigation";

/** Redirects old /payments path to the unified Payments & Devices page. */
export default function PaymentsRedirectPage() {
  redirect("/dashboard/settings/payments-devices");
}
