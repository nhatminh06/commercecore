const CART_STORAGE_KEY = "commercecore.active-cart-id";

export function generateCheckoutKey() {
  return `demo-checkout-${crypto.randomUUID()}`;
}

export function readStoredCartId() {
  return window.localStorage.getItem(CART_STORAGE_KEY);
}

export function storeCartId(cartId: string) {
  window.localStorage.setItem(CART_STORAGE_KEY, cartId);
}

export function clearStoredCartId() {
  window.localStorage.removeItem(CART_STORAGE_KEY);
}

export function isValidQuantity(quantity: number) {
  return Number.isInteger(quantity) && quantity > 0;
}

function decimalToMinorUnits(value: number | string): bigint {
  const normalized = String(value);
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(normalized);
  if (!match) {
    throw new Error(`Unsupported money value: ${normalized}`);
  }
  return BigInt(match[1]) * 100n + BigInt((match[2] ?? "").padEnd(2, "0"));
}

export function multiplyMoney(value: number | string, quantity: number) {
  return decimalToMinorUnits(value) * BigInt(quantity);
}

export function formatMoney(value: number | string | bigint) {
  const minorUnits = typeof value === "bigint" ? value : decimalToMinorUnits(value);
  const dollars = minorUnits / 100n;
  const cents = (minorUnits % 100n).toString().padStart(2, "0");
  return `$${dollars.toLocaleString("en-US")}.${cents}`;
}
