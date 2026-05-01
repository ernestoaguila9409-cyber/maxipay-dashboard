import Header from "@/components/Header";
import SalesActivityBatchesSection from "@/components/SalesActivityBatchesSection";
import SalesActivityClient from "@/components/SalesActivityClient";

export default function SalesActivityPage() {
  return (
    <>
      <Header title="Sales activity" />
      <div className="px-4 sm:px-6 max-w-[1600px] mx-auto pb-2 pt-1">
        <SalesActivityBatchesSection />
      </div>
      <SalesActivityClient />
    </>
  );
}
