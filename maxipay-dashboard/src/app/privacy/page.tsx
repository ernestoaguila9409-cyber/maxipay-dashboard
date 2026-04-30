import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Privacy Policy — MaxiPay",
  description: "MaxiPay POS privacy policy",
};

export default function PrivacyPolicy() {
  const lastUpdated = "April 29, 2026";
  const contactEmail = "support@maxipaypos.com";
  const websiteUrl = "https://www.maxipaypos.com";

  return (
    <div className="min-h-screen bg-white">
      <div className="mx-auto max-w-3xl px-6 py-16">
        <h1 className="text-3xl font-bold text-gray-900">Privacy Policy</h1>
        <p className="mt-2 text-sm text-gray-500">
          Last updated: {lastUpdated}
        </p>

        <div className="mt-10 space-y-8 text-gray-700 leading-relaxed">
          <section>
            <h2 className="text-xl font-semibold text-gray-900">
              1. Introduction
            </h2>
            <p className="mt-2">
              MaxiPay (&quot;we&quot;, &quot;us&quot;, or &quot;our&quot;)
              operates a point-of-sale (POS) system, management dashboard, and
              related services (collectively, the &quot;Service&quot;). This
              Privacy Policy explains how we collect, use, disclose, and
              safeguard your information when you use our Service, including
              integrations with third-party platforms such as Uber Eats.
            </p>
          </section>

          <section>
            <h2 className="text-xl font-semibold text-gray-900">
              2. Information We Collect
            </h2>
            <h3 className="mt-4 font-medium text-gray-800">
              2.1 Information You Provide
            </h3>
            <ul className="mt-2 list-disc space-y-1 pl-6">
              <li>
                Account and business information (business name, address, email,
                phone number)
              </li>
              <li>Employee names and roles</li>
              <li>Menu items, pricing, and product descriptions</li>
              <li>Customer names and contact details for order fulfillment</li>
              <li>Payment and transaction records</li>
            </ul>

            <h3 className="mt-4 font-medium text-gray-800">
              2.2 Information from Third-Party Platforms
            </h3>
            <p className="mt-2">
              When you connect third-party delivery platforms (e.g., Uber Eats),
              we receive order data including customer names, delivery
              addresses, order items, and payment totals. This data is used
              solely to fulfill and manage orders within the POS system.
            </p>

            <h3 className="mt-4 font-medium text-gray-800">
              2.3 Automatically Collected Information
            </h3>
            <ul className="mt-2 list-disc space-y-1 pl-6">
              <li>Device identifiers and operating system version</li>
              <li>Usage logs, timestamps, and interaction data</li>
              <li>IP addresses and approximate location</li>
            </ul>
          </section>

          <section>
            <h2 className="text-xl font-semibold text-gray-900">
              3. How We Use Your Information
            </h2>
            <ul className="mt-2 list-disc space-y-1 pl-6">
              <li>Process and manage orders, payments, and refunds</li>
              <li>
                Synchronize menus and order statuses with connected delivery
                platforms
              </li>
              <li>Generate sales, tax, and inventory reports</li>
              <li>Send transactional communications (receipts, order updates)</li>
              <li>Improve, maintain, and troubleshoot the Service</li>
              <li>Comply with legal obligations</li>
            </ul>
          </section>

          <section>
            <h2 className="text-xl font-semibold text-gray-900">
              4. Sharing of Information
            </h2>
            <p className="mt-2">
              We do not sell your personal information. We may share data with:
            </p>
            <ul className="mt-2 list-disc space-y-1 pl-6">
              <li>
                <strong>Third-party delivery platforms</strong> (e.g., Uber
                Eats) — order status updates, menu data, and store information
                necessary for the integration to function
              </li>
              <li>
                <strong>Payment processors</strong> — transaction data required
                to process payments
              </li>
              <li>
                <strong>Service providers</strong> — cloud hosting (Google
                Firebase), email delivery (SendGrid), and analytics providers
                that help us operate the Service
              </li>
              <li>
                <strong>Legal requirements</strong> — when required by law,
                regulation, or legal process
              </li>
            </ul>
          </section>

          <section>
            <h2 className="text-xl font-semibold text-gray-900">
              5. Data Security
            </h2>
            <p className="mt-2">
              We use commercially reasonable technical and organizational
              measures to protect your data, including encryption in transit
              (TLS/SSL), secure cloud infrastructure, and access controls.
              However, no method of transmission over the Internet is 100%
              secure.
            </p>
          </section>

          <section>
            <h2 className="text-xl font-semibold text-gray-900">
              6. Data Retention
            </h2>
            <p className="mt-2">
              We retain your information for as long as your account is active
              or as needed to provide the Service, comply with legal
              obligations, resolve disputes, and enforce agreements. You may
              request deletion of your data by contacting us.
            </p>
          </section>

          <section>
            <h2 className="text-xl font-semibold text-gray-900">
              7. Third-Party Integrations
            </h2>
            <p className="mt-2">
              Our Service integrates with third-party platforms such as Uber
              Eats. When you enable an integration, data is exchanged between
              MaxiPay and the third party in accordance with both this Privacy
              Policy and the third party&apos;s own privacy policy. We
              encourage you to review the privacy policies of any third-party
              services you connect.
            </p>
          </section>

          <section>
            <h2 className="text-xl font-semibold text-gray-900">
              8. Your Rights
            </h2>
            <p className="mt-2">
              Depending on your jurisdiction, you may have the right to:
            </p>
            <ul className="mt-2 list-disc space-y-1 pl-6">
              <li>Access the personal data we hold about you</li>
              <li>Request correction of inaccurate data</li>
              <li>Request deletion of your data</li>
              <li>Object to or restrict certain processing</li>
              <li>Data portability</li>
            </ul>
            <p className="mt-2">
              To exercise any of these rights, contact us at{" "}
              <a
                href={`mailto:${contactEmail}`}
                className="text-blue-600 underline"
              >
                {contactEmail}
              </a>
              .
            </p>
          </section>

          <section>
            <h2 className="text-xl font-semibold text-gray-900">
              9. Children&apos;s Privacy
            </h2>
            <p className="mt-2">
              Our Service is not directed to individuals under the age of 13. We
              do not knowingly collect personal information from children.
            </p>
          </section>

          <section>
            <h2 className="text-xl font-semibold text-gray-900">
              10. Changes to This Policy
            </h2>
            <p className="mt-2">
              We may update this Privacy Policy from time to time. Changes will
              be posted on this page with an updated &quot;Last updated&quot;
              date. Continued use of the Service after changes constitutes
              acceptance of the revised policy.
            </p>
          </section>

          <section>
            <h2 className="text-xl font-semibold text-gray-900">
              11. Contact Us
            </h2>
            <p className="mt-2">
              If you have questions about this Privacy Policy, please contact
              us:
            </p>
            <ul className="mt-2 list-disc space-y-1 pl-6">
              <li>
                Email:{" "}
                <a
                  href={`mailto:${contactEmail}`}
                  className="text-blue-600 underline"
                >
                  {contactEmail}
                </a>
              </li>
              <li>
                Website:{" "}
                <a href={websiteUrl} className="text-blue-600 underline">
                  {websiteUrl}
                </a>
              </li>
            </ul>
          </section>
        </div>

        <div className="mt-16 border-t border-gray-200 pt-6 text-center text-xs text-gray-400">
          &copy; {new Date().getFullYear()} MaxiPay. All rights reserved.
        </div>
      </div>
    </div>
  );
}
