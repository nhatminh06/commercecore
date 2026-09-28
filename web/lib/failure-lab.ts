import type { OrderInspection, PaymentStatus } from "./order-inspection";

export type FailureScenarioId = "NORMAL_AUTHORIZATION" | "EXPLICIT_DECLINE" | "TIMEOUT_AFTER_COMMIT";
export type ProviderOutcome = "SUCCESS" | "DECLINED" | "TIMEOUT_AFTER_PROCESSING";

export type FailureScenario = {
  id: FailureScenarioId;
  title: string;
  providerOutcome: ProviderOutcome;
  injectedBehavior: string;
  semanticOutcome: string;
  demonstrates: string[];
  expectedPaymentStatus: PaymentStatus;
};

export const failureScenarios: FailureScenario[] = [
  {
    id: "NORMAL_AUTHORIZATION",
    title: "Normal authorization",
    providerOutcome: "SUCCESS",
    injectedBehavior: "Authorize and return the successful response.",
    semanticOutcome: "CommerceCore records an authoritative AUTHORIZED payment outcome.",
    demonstrates: ["healthy baseline", "stable provider request", "outbox workflow"],
    expectedPaymentStatus: "AUTHORIZED",
  },
  {
    id: "EXPLICIT_DECLINE",
    title: "Explicit payment failure",
    providerOutcome: "DECLINED",
    injectedBehavior: "Decline authorization and return the definitive response.",
    semanticOutcome: "CommerceCore records FAILED because the provider definitively declined.",
    demonstrates: ["definitive failure", "compensating workflow", "inventory restoration"],
    expectedPaymentStatus: "FAILED",
  },
  {
    id: "TIMEOUT_AFTER_COMMIT",
    title: "Timeout after provider commit",
    providerOutcome: "TIMEOUT_AFTER_PROCESSING",
    injectedBehavior: "Commit AUTHORIZED provider truth, then delay beyond the caller deadline.",
    semanticOutcome: "CommerceCore records UNKNOWN because the response was not observed.",
    demonstrates: ["payment ambiguity", "UNKNOWN", "safe reconciliation"],
    expectedPaymentStatus: "UNKNOWN",
  },
];

export type ExperimentResult = {
  scenario: FailureScenario;
  orderId: string;
  paymentId: string;
  providerRequestId: string;
  operationPaymentStatus: PaymentStatus;
  inspection: OrderInspection;
  publication: { claimed: number; published: number } | null;
};

export function canReconcile(result: ExperimentResult | null) {
  return result?.inspection.payment?.status === "UNKNOWN";
}

export function observedExpectedOutcome(result: ExperimentResult) {
  return result.inspection.payment?.status === result.scenario.expectedPaymentStatus;
}

export function orderLink(orderId: string) {
  return `/orders/${encodeURIComponent(orderId)}`;
}

export function eventLink(orderId: string) {
  return `/events?aggregateId=${encodeURIComponent(orderId)}`;
}
