import Header from "@/components/Header";
import SalesActivityBatchesSection from "@/components/SalesActivityBatchesSection";

export default function BatchesPage() {
  return (
    <>
      <Header title="Batches" />
      <div className="px-4 sm:px-6 max-w-[1600px] mx-auto pb-2 pt-1">
        <SalesActivityBatchesSection />
      </div>
    </>
  );
}
