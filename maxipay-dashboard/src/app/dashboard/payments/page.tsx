import { redirect } from "next/navigation";

/** Avoids some hosts/WAFs that block paths containing the word "payments". */
export default function PaymentsRedirectPage() {
  redirect("/dashboard/payment-terminals");
}
