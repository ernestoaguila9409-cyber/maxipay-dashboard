import { redirect } from "next/navigation";

/** Redirects old /settings/devices path to the unified Payments & Devices page. */
export default function DevicesRedirectPage() {
  redirect("/dashboard/settings/payments-devices");
}
