import { ApiError, request } from "./api";
import type { ExperimentResult, FailureScenario } from "./failure-lab";
import type { OrderInspection, PaymentStatus, ReconciliationInspection } from "./order-inspection";
import type { Order, Product } from "./types";

async function providerRequest(path: string, options: RequestInit = {}) {
  const response = await fetch(`/api/payment-provider${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...options.headers },
  });
  const contentType = response.headers.get("content-type") ?? "";
  const body: unknown = response.status === 204 ? undefined : contentType.includes("application/json") ? await response.json() : await response.text();
  if (!response.ok) throw new ApiError(`Payment Service development control failed (${response.status})`, response.status, body);
}

export function configureProvider(outcome: FailureScenario["providerOutcome"]) {
  return providerRequest("/dev/provider/next-outcome", { method: "POST", body: JSON.stringify({ outcome }) });
}

export function createExperimentProduct(sku: string) {
  return request<Product>("/products", { method: "POST", body: JSON.stringify({ sku, name: "Failure Lab Fixture", price: 29.9, initialQuantity: 5 }) });
}

export async function prepareOrder(sku: string): Promise<Order> {
  await createExperimentProduct(sku);
  const cart = await request<{ id: string }>("/carts", { method: "POST" });
  await request<void>(`/carts/${cart.id}/items/${encodeURIComponent(sku)}`, { method: "PUT", body: JSON.stringify({ quantity: 1 }) });
  return request<Order>(`/carts/${cart.id}/checkout`, { method: "POST", headers: { "Idempotency-Key": `failure-lab-${crypto.randomUUID()}` } });
}

export function initiatePayment(orderId: string) {
  return request<{ paymentId: string; orderId: string; amount: number; status: PaymentStatus; providerReference: string | null }>(`/orders/${encodeURIComponent(orderId)}/payment`, { method: "POST" });
}

export function inspectOrder(orderId: string) {
  return request<OrderInspection>(`/orders/${encodeURIComponent(orderId)}/inspection`);
}

export function publishOutbox() {
  return request<{ claimed: number; published: number }>("/dev/outbox/publish", { method: "POST" });
}

export function reconcilePayment(paymentId: string) {
  return request<ReconciliationInspection | null>(`/dev/payments/${encodeURIComponent(paymentId)}/reconcile`, { method: "POST" });
}

export async function runFailureExperiment(scenario: FailureScenario): Promise<ExperimentResult> {
  try {
    await configureProvider(scenario.providerOutcome);
  } catch (cause) {
    throw new FailureLabError("configuration", "Payment Service development control is unavailable.", cause);
  }
  const sku = `FAIL-LAB-${crypto.randomUUID().slice(0, 8).toUpperCase()}`;
  let order: Order;
  try {
    order = await prepareOrder(sku);
  } catch (cause) {
    throw new FailureLabError(cause instanceof ApiError ? "business" : "infrastructure", "Experiment fixture preparation failed.", cause);
  }
  let payment;
  try {
    payment = await initiatePayment(order.orderId);
  } catch (cause) {
    throw new FailureLabError(cause instanceof ApiError ? "business" : "infrastructure", "Payment request could not be completed.", cause);
  }
  let publication = null;
  try {
    publication = payment.status === "UNKNOWN" ? null : await publishOutbox();
    const inspection = await inspectOrder(order.orderId);
    return { scenario, orderId: order.orderId, paymentId: payment.paymentId, providerRequestId: payment.paymentId, operationPaymentStatus: payment.status, inspection, publication };
  } catch (cause) {
    throw new FailureLabError("infrastructure", `Payment ${payment.paymentId} was persisted, but its resulting state could not be inspected.`, cause);
  }
}

export class FailureLabError extends Error {
  constructor(readonly kind: "configuration" | "business" | "infrastructure", message: string, readonly cause: unknown) {
    super(message);
    this.name = "FailureLabError";
  }
}
