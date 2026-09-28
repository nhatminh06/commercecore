import { request } from "./api";
import type { OrderInspection } from "./order-inspection";

export function getOrderInspection(orderId: string) {
  return request<OrderInspection>(`/orders/${encodeURIComponent(orderId)}/inspection`);
}
