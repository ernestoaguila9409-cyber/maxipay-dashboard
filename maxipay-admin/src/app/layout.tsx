import type { Metadata } from "next";
import AuthGate from "@/components/AuthGate";
import "./globals.css";

export const metadata: Metadata = {
  title: "MaxiPay Admin",
  description: "MaxiPay Admin Portal",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>
        <AuthGate>{children}</AuthGate>
      </body>
    </html>
  );
}
