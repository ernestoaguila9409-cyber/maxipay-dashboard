import { redirect } from "next/navigation";

/** Landing on /dashboard/reports opens the default report section. */
export default function ReportsIndexPage() {
  redirect("/dashboard/reports/overview");
}
