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

export type PaymentProviderId = "SPIN" | "SPIN_Z" | "SPIN_P";

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

const SPIN_CREDENTIAL_SCHEMA: PaymentCredentialField[] = [
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
];

const SPIN_P_CREDENTIAL_SCHEMA: PaymentCredentialField[] = [
  ...SPIN_CREDENTIAL_SCHEMA,
  {
    key: "iposTransactAuthToken",
    label: "iPOS Transact Auth Token",
    placeholder: "e.g. abc123...",
    required: false,
    secret: true,
    helperText:
      "Merchant auth token from iPOSpays portal. Required for direct refunds (no card present) from the dashboard.",
  },
];

const SPIN_Z_CAPABILITIES: PaymentCapabilities = {
  supportsPreAuth: true,
  supportsCapture: true,
  supportsTipAdjust: true,
  supportsSale: true,
  supportsVoid: true,
  supportsRefund: false,
  supportsSettle: true,
  supportsStatusCheck: true,
};

const SPIN_P_CAPABILITIES: PaymentCapabilities = {
  supportsPreAuth: true,
  supportsCapture: true,
  supportsTipAdjust: true,
  supportsSale: true,
  supportsVoid: true,
  supportsRefund: true,
  supportsSettle: true,
  supportsStatusCheck: true,
};

const SPIN_ENDPOINTS_Z: PaymentEndpointMap = {
  auth: "/Payment/Auth",
  capture: "/Payment/Capture",
  tipAdjust: "/Payment/TipAdjust",
  sale: "/Payment/Sale",
  void: "/Payment/Void",
  refund: "/Payment/Return",
  settle: "/Payment/Settle",
  status: "/Payment/Status",
};

const SPIN_ENDPOINTS_P: PaymentEndpointMap = {
  auth: "/Payment/Auth",
  capture: "/Payment/Capture",
  tipAdjust: "/Payment/TipAdjust",
  sale: "/Payment/Sale",
  void: "/Payment/Void",
  refund: "/Payment/Return",
  settle: "/Payment/Settle",
  status: "/Payment/Status",
};

export const PAYMENT_PROVIDERS: Record<PaymentProviderId, PaymentProviderCatalogEntry> = {
  /* Legacy alias — existing Firestore docs store provider:"SPIN". Kept so the
     table + edit form can still render them; the "add" dropdown only shows Z / P. */
  SPIN: {
    id: "SPIN",
    displayName: "SPIn (SPInPos Gateway)",
    description:
      "Legacy SPIn entry. Existing terminals use this; new ones should choose Z or P.",
    deviceModels: ["Z8", "P17", "P20", "Dejavoo QD3", "Dejavoo QD4", "Other"],
    baseUrl: "https://spinpos.net/v2",
    endpoints: SPIN_ENDPOINTS_Z,
    capabilities: SPIN_Z_CAPABILITIES,
    credentialSchema: SPIN_CREDENTIAL_SCHEMA,
  },
  SPIN_Z: {
    id: "SPIN_Z",
    displayName: "SPIn (SPInPos Gateway) Z",
    description:
      "Semi-integrated SPInPos gateway for Z-series terminals. Uses Payment/Status for reachability probes.",
    deviceModels: ["Z8", "Dejavoo QD3", "Dejavoo QD4", "Other"],
    baseUrl: "https://spinpos.net/v2",
    endpoints: SPIN_ENDPOINTS_Z,
    capabilities: SPIN_Z_CAPABILITIES,
    credentialSchema: SPIN_CREDENTIAL_SCHEMA,
  },
  SPIN_P: {
    id: "SPIN_P",
    displayName: "SPIn (SPInPos Gateway) P",
    description:
      "Semi-integrated SPInPos gateway for P-series terminals. Uses lightweight Common/TerminalStatus for reachability (no device-side UI flash).",
    deviceModels: ["P17", "P20", "Other"],
    baseUrl: "https://spinpos.net/v2",
    endpoints: SPIN_ENDPOINTS_P,
    capabilities: SPIN_P_CAPABILITIES,
    credentialSchema: SPIN_P_CREDENTIAL_SCHEMA,
  },
};

/** Only Z and P appear in the "add terminal" dropdown; legacy SPIN is kept
 *  for rendering/editing existing docs but is not offered for new terminals. */
export const PAYMENT_PROVIDER_IDS: PaymentProviderId[] = ["SPIN_Z", "SPIN_P"];

/** Returns true for any SPIn-family provider id (legacy SPIN, SPIN_Z, SPIN_P). */
export function isSpinProvider(provider: string): boolean {
  return provider === "SPIN" || provider === "SPIN_Z" || provider === "SPIN_P";
}

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
