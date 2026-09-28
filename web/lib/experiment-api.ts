import { request } from "./api";

export type ExperimentKind = "inventory" | "checkout" | "provider" | "duplicate";
export type BaseResult = { experimentId: string; complete: boolean; invariantPreserved: boolean; durationMs: number };
export type InventoryResult = BaseResult & { sku: string; workers: number; initialInventory: number; quantityPerWorker: number; successful: number; rejected: number; finalInventory: number };
export type CheckoutResult = BaseResult & { workers: number; cartId: string; idempotencyKey: string; successfulResponses: number; failedResponses: number; distinctResponseOrderIds: string[]; persistedMappingsForCart: number };
export type ProviderResult = BaseResult & { workers: number; providerRequestId: string; successfulResponses: number; failedResponses: number; distinctLogicalResults: string[]; authoritativeProviderStatus: string; providerReference: string | null };
export type DuplicateResult = BaseResult & { attempts: number; eventId: string; newlyPersistedReceipts: number; authoritativeReceiptCount: number };
export type ExperimentResult = InventoryResult | CheckoutResult | ProviderResult | DuplicateResult;

export const limits = { workers: { min: 2, max: 200 }, inventory: { min: 0, max: 10_000 }, quantity: { min: 1, max: 100 } } as const;
export function boundedInteger(value: number, min: number, max: number) { return Number.isInteger(value) && value >= min && value <= max; }
export function resultLabel(result: BaseResult) { return !result.complete ? "INCOMPLETE" : result.invariantPreserved ? "PASS" : "FAIL"; }
export function orderLink(id: string) { return `/orders/${encodeURIComponent(id)}`; }
export function eventLink(id: string) { return `/events/${encodeURIComponent(id)}`; }

export function runInventory(config: { initialInventory: number; workers: number; quantityPerWorker: number }) {
  return request<InventoryResult>("/dev/experiments/inventory-contention", { method: "POST", body: JSON.stringify(config) });
}
export function runCheckout(workers: number) { return request<CheckoutResult>("/dev/experiments/checkout-idempotency", { method: "POST", body: JSON.stringify({ workers }) }); }
export function runProvider(workers: number) { return request<ProviderResult>("/dev/experiments/provider-idempotency", { method: "POST", body: JSON.stringify({ workers }) }); }
export function runDuplicate(attempts: number) { return request<DuplicateResult>("/dev/experiments/duplicate-event", { method: "POST", body: JSON.stringify({ attempts }) }); }
