"use client";

import { useEffect, useState } from "react";
import { query, where, limit, onSnapshot } from "firebase/firestore";
import { merchantCol } from "@/lib/merchantFirestore";
import { useMerchantId } from "@/hooks/useMerchantId";
import type { PaymentCapabilities, PaymentProviderId } from "@/lib/paymentProviders";

const PAYMENTS_COLLECTION = "payment_terminals";

const DEFAULT_CAPABILITIES: PaymentCapabilities = {
  supportsPreAuth: false,
  supportsCapture: false,
  supportsTipAdjust: false,
  supportsSale: false,
  supportsVoid: true,
  supportsRefund: false,
  supportsSettle: false,
  supportsStatusCheck: false,
};

export interface ActiveTerminalInfo {
  capabilities: PaymentCapabilities;
  provider: PaymentProviderId | "";
  loading: boolean;
}

export function useActiveTerminalCapabilities(): ActiveTerminalInfo {
  const merchantId = useMerchantId();
  const [info, setInfo] = useState<ActiveTerminalInfo>({
    capabilities: DEFAULT_CAPABILITIES,
    provider: "",
    loading: true,
  });

  useEffect(() => {
    if (!merchantId) return;
    const q = query(
      merchantCol(merchantId, PAYMENTS_COLLECTION),
      where("active", "==", true),
      limit(1),
    );
    const unsub = onSnapshot(q, (snap) => {
      if (snap.empty) {
        setInfo({ capabilities: DEFAULT_CAPABILITIES, provider: "", loading: false });
        return;
      }
      const data = snap.docs[0].data();
      const caps = data.capabilities as PaymentCapabilities | undefined;
      const provider = (data.provider ?? "") as PaymentProviderId | "";
      setInfo({
        capabilities: caps ?? DEFAULT_CAPABILITIES,
        provider,
        loading: false,
      });
    });
    return () => unsub();
  }, [merchantId]);

  return info;
}
