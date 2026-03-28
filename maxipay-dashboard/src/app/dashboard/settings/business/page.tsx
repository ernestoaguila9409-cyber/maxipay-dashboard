"use client";

import { useEffect } from "react";
import Header from "@/components/Header";

export default function BusinessInformationPage() {
  useEffect(() => {
    console.log("Business page loaded");
  }, []);

  return (
    <>
      <Header title="Business Information" />
      <div className="p-6">
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6">
          <h2 className="text-lg font-semibold text-slate-800">Business Information</h2>
          <p className="mt-2 text-sm text-slate-500">
            Business details and settings will be added here.
          </p>
        </div>
      </div>
    </>
  );
}
