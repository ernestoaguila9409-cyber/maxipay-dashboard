/**
 * Payment-provider catalog.
 *
 * Each provider here declares:
 *  - how the dashboard should render its configuration form (credential fields
 *    + device-model options)
 *  - the default `baseUrl`, `endpoints`, and `capabilities` written to every
 *    new `payment_terminals/{id}` doc for that provider
 *
 * Adding a new provider = add a new entry to `PAYMENT_PROVIDERS`. The Android
 * app reads the values written to `payment_terminals/{id}`, so no hardcoded
 * URLs need to change once the catalog grows.
 */

export type PaymentProviderId = "SPIN";

export interface PaymentCredentialField {
  key: string;
  label: string;
  placeholder?: string;
  required: boolean;
  secret: boolean;
  helperText?: string;
}

export interface PaymentEndpointMap {
  auth?: string;
  capture?: string;
  tipAdjust?: string;
  sale?: string;
  void?: string;
  refund?: string;
  settle?: string;
  status?: string;
}

export interface PaymentCapabilities {
  supportsPreAuth: boolean;
  supportsCapture: boolean;
  supportsTipAdjust: boolean;
  supportsSale: boolean;
  supportsVoid: boolean;
  supportsRefund: boolean;
  supportsSettle: boolean;
  supportsStatusCheck: boolean;
}

export interface PaymentProviderCatalogEntry {
  id: PaymentProviderId;
  displayName: string;
  description: string;
  deviceModels: string[];
  baseUrl: string;
  endpoints: PaymentEndpointMap;
  capabilities: PaymentCapabilities;
  credentialSchema: PaymentCredentialField[];
}

export const PAYMENT_PROVIDERS: Record<PaymentProviderId, PaymentProviderCatalogEntry> = {
  SPIN: {
    id: "SPIN",
    displayName: "SPIn (SPInPos Gateway)",
    description:
      "Semi-integrated SPInPos gateway. Sends Auth / Capture / TipAdjust / Sale / Void / Return / Settle / Status HTTPS requests keyed by TPN + Register ID + Auth Key.",
    deviceModels: ["Z8", "P17", "P20", "Dejavoo QD3", "Dejavoo QD4", "Other"],
    baseUrl: "https://spinpos.net/v2",
    endpoints: {
      auth: "/Payment/Auth",
      capture: "/Payment/Capture",
      tipAdjust: "/Payment/TipAdjust",
      sale: "/Payment/Sale",
      void: "/Payment/Void",
      refund: "/Payment/Return",
      settle: "/Payment/Settle",
      status: "/Payment/Status",
    },
    capabilities: {
      supportsPreAuth: true,
      supportsCapture: true,
      supportsTipAdjust: true,
      supportsSale: true,
      supportsVoid: true,
      supportsRefund: true,
      supportsSettle: true,
      supportsStatusCheck: true,
    },
    credentialSchema: [
      {
        key: "tpn",
        label: "TPN",
        placeholder: "e.g. 11881706541A",
        required: true,
        secret: false,
        helperText: "Terminal Profile Number assigned by SPIn.",
      },
      {
        key: "registerId",
        label: "Register ID",
        placeholder: "e.g. 134909005",
        required: true,
        secret: false,
        helperText: "Register this TPN is assigned to.",
      },
      {
        key: "authKey",
        label: "Auth Key",
        placeholder: "e.g. Qt9N7CxhDs",
        required: true,
        secret: true,
        helperText: "Secret auth key issued with the TPN. Treat as a password.",
      },
    ],
  },
};

export const PAYMENT_PROVIDER_IDS: PaymentProviderId[] = Object.keys(
  PAYMENT_PROVIDERS
) as PaymentProviderId[];

/**
 * Firestore document shape for `payment_terminals/{id}`. The Android app reads
 * this directly — keep field names stable.
 */
export interface PaymentTerminalDoc {
  name: string;
  provider: PaymentProviderId;
  deviceModel: string;
  active: boolean;
  baseUrl: string;
  endpoints: PaymentEndpointMap;
  capabilities: PaymentCapabilities;
  config: Record<string, string>;
  createdAt?: unknown;
  updatedAt?: unknown;
  legacyTerminalId?: string;
  /** Written by POS heartbeat: ONLINE / OFFLINE */
  posConnectionStatus?: string;
  /** When the POS last reported a successful SPIn status probe */
  posLastSeen?: unknown;
}
