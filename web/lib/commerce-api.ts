import { request } from "@/lib/api";
import type { Cart, Inventory, Order, Product } from "@/lib/types";

export function getProducts() {
  return request<Product[]>("/products");
}

export function getInventory(sku: string) {
  return request<Inventory>(`/products/${encodeURIComponent(sku)}/inventory`);
}

export function createCart() {
  return request<{ id: string }>("/carts", { method: "POST" });
}

export function getCart(cartId: string) {
  return request<Cart>(`/carts/${encodeURIComponent(cartId)}`);
}

export async function setCartItem(cartId: string, sku: string, quantity: number) {
  await request<void>(`/carts/${encodeURIComponent(cartId)}/items/${encodeURIComponent(sku)}`, {
    method: "PUT",
    body: JSON.stringify({ quantity }),
  });
  return getCart(cartId);
}

export async function removeCartItem(cartId: string, sku: string) {
  await request<void>(`/carts/${encodeURIComponent(cartId)}/items/${encodeURIComponent(sku)}`, {
    method: "DELETE",
  });
  return getCart(cartId);
}

export function checkout(cartId: string, idempotencyKey: string) {
  return request<Order>(`/carts/${encodeURIComponent(cartId)}/checkout`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
  });
}

export function getOrder(orderId: string) {
  return request<Order>(`/orders/${encodeURIComponent(orderId)}`);
}
