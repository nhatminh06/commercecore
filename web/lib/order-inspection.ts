import type { Order } from "@/lib/types";

export type PaymentStatus = "PENDING" | "AUTHORIZED" | "FAILED" | "UNKNOWN";
export type ReservationStatus = "ACTIVE" | "CONFIRMED" | "RELEASED" | "EXPIRED";
export type ReconciliationStatus = "OPEN" | "RESOLVED" | "REQUIRES_REVIEW";
export type ProviderPaymentStatus = "AUTHORIZED" | "DECLINED" | "NOT_FOUND";

export type PaymentInspection = {
  paymentId: string;
  orderId: string;
  amount: number;
  status: PaymentStatus;
  providerReference: string | null;
  createdAt: string;
  updatedAt: string;
};

export type ReservationInspection = {
  id: string;
  sku: string;
  quantity: number;
  status: ReservationStatus;
  expiresAt: string;
};

export type ReconciliationInspection = {
  id: string;
  paymentId: string;
  status: ReconciliationStatus;
  lastProviderStatus: ProviderPaymentStatus | null;
  reason: string | null;
  attemptCount: number;
  lastCheckedAt: string | null;
};

export type OrderInspection = {
  order: Order;
  orderCreatedAt: string;
  payment: PaymentInspection | null;
  reservations: ReservationInspection[];
  reconciliation: ReconciliationInspection | null;
};

export type StatusTone = "neutral" | "success" | "warning" | "danger";

export function toneForStatus(status: string): StatusTone {
  if (["AUTHORIZED", "CONFIRMED", "RESOLVED"].includes(status)) return "success";
  if (["FAILED", "CANCELLED", "RELEASED", "EXPIRED"].includes(status)) return "danger";
  if (["UNKNOWN", "REQUIRES_REVIEW", "OPEN"].includes(status)) return "warning";
  return "neutral";
}

export function explanationForStatus(status: string): string | null {
  switch (status) {
    case "UNKNOWN":
      return "CommerceCore does not have authoritative evidence that the payment was authorized or failed. Reconciliation can query provider truth without re-authorizing.";
    case "REQUIRES_REVIEW":
      return "Provider truth is definitive, but automatic business repair is unsafe or conflicts with recorded CommerceCore state.";
    case "PENDING":
      return "The outcome is not yet reflected as a terminal state.";
    default:
      return null;
  }
}
