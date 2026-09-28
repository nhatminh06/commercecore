"use client";

import { Minus, Plus, RefreshCw, ShoppingCart, Trash2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";

import { ErrorState } from "@/components/error-state";
import { LoadingState } from "@/components/loading-state";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ApiError } from "@/lib/api";
import {
  checkout,
  createCart,
  getCart,
  getInventory,
  getProducts,
  removeCartItem,
  setCartItem,
} from "@/lib/commerce-api";
import {
  clearStoredCartId,
  formatMoney,
  generateCheckoutKey,
  isValidQuantity,
  multiplyMoney,
  readStoredCartId,
  storeCartId,
} from "@/lib/store-utils";
import type { Cart as CartType, Inventory, Product } from "@/lib/types";

type ProductWithInventory = Product & { inventory: Inventory | null };

function messageFor(error: unknown) {
  return error instanceof Error ? error.message : "An unexpected error occurred.";
}

function QuantityControl({
  value,
  onChange,
  disabled,
  label,
}: {
  value: number;
  onChange: (value: number) => void;
  disabled?: boolean;
  label: string;
}) {
  return (
    <div className="flex items-center rounded-md border border-border" aria-label={label}>
      <Button
        variant="ghost"
        size="icon"
        onClick={() => onChange(value - 1)}
        disabled={disabled || value <= 1}
        aria-label={`Decrease ${label}`}
      >
        <Minus className="size-3.5" />
      </Button>
      <span className="w-8 text-center font-mono text-sm" aria-live="polite">{value}</span>
      <Button
        variant="ghost"
        size="icon"
        onClick={() => onChange(value + 1)}
        disabled={disabled}
        aria-label={`Increase ${label}`}
      >
        <Plus className="size-3.5" />
      </Button>
    </div>
  );
}

export function StoreSimulator() {
  const router = useRouter();
  const [products, setProducts] = useState<ProductWithInventory[] | null>(null);
  const [productsError, setProductsError] = useState<string | null>(null);
  const [cart, setCart] = useState<CartType | null>(null);
  const [cartLoading, setCartLoading] = useState(true);
  const [cartError, setCartError] = useState<string | null>(null);
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [busySku, setBusySku] = useState<string | null>(null);
  const [creatingCart, setCreatingCart] = useState(false);
  const [checkoutPending, setCheckoutPending] = useState(false);
  const [checkoutError, setCheckoutError] = useState<{ message: string; ambiguous: boolean } | null>(null);
  const [idempotencyKey, setIdempotencyKey] = useState("");

  const loadProducts = useCallback(async () => {
    setProductsError(null);
    setProducts(null);
    try {
      const catalog = await getProducts();
      const inventory = await Promise.all(
        catalog.map(async (product) => {
          try {
            return await getInventory(product.sku);
          } catch {
            return null;
          }
        }),
      );
      setProducts(catalog.map((product, index) => ({ ...product, inventory: inventory[index] })));
      setQuantities(Object.fromEntries(catalog.map((product) => [product.sku, 1])));
    } catch (error) {
      setProductsError(messageFor(error));
    }
  }, []);

  useEffect(() => {
    queueMicrotask(() => {
      void loadProducts();

      const cartId = readStoredCartId();
      if (!cartId) {
        setCartLoading(false);
        return;
      }
      getCart(cartId)
        .then((savedCart) => {
          setCart(savedCart);
          setIdempotencyKey(generateCheckoutKey());
        })
        .catch((error: unknown) => {
          if (error instanceof ApiError && error.code === "cart_not_found") {
            clearStoredCartId();
            setCartError("The saved cart no longer exists. Create a new cart to continue.");
          } else {
            setCartError(messageFor(error));
          }
        })
        .finally(() => setCartLoading(false));
    });
  }, [loadProducts]);

  const productBySku = useMemo(
    () => new Map(products?.map((product) => [product.sku, product]) ?? []),
    [products],
  );

  const displayTotal = useMemo(
    () => cart?.items.reduce((sum, item) => {
      const price = productBySku.get(item.sku)?.price;
      return price === undefined ? sum : sum + multiplyMoney(price, item.quantity);
    }, 0n) ?? 0n,
    [cart, productBySku],
  );

  async function handleCreateCart() {
    setCreatingCart(true);
    setCartError(null);
    try {
      const created = await createCart();
      const nextCart = await getCart(created.id);
      storeCartId(created.id);
      setCart(nextCart);
      setIdempotencyKey(generateCheckoutKey());
      setCheckoutError(null);
    } catch (error) {
      setCartError(messageFor(error));
    } finally {
      setCreatingCart(false);
    }
  }

  function handleNewCart() {
    clearStoredCartId();
    setCart(null);
    setCartError(null);
    setCheckoutError(null);
    setIdempotencyKey(generateCheckoutKey());
  }

  async function mutateItem(sku: string, quantity: number | null) {
    if (!cart || (quantity !== null && !isValidQuantity(quantity))) return;
    setBusySku(sku);
    setCartError(null);
    try {
      const nextCart = quantity === null
        ? await removeCartItem(cart.id, sku)
        : await setCartItem(cart.id, sku, quantity);
      setCart(nextCart);
    } catch (error) {
      setCartError(messageFor(error));
    } finally {
      setBusySku(null);
    }
  }

  async function handleCheckout() {
    if (!cart || cart.items.length === 0 || !idempotencyKey.trim()) return;
    setCheckoutPending(true);
    setCheckoutError(null);
    try {
      const order = await checkout(cart.id, idempotencyKey);
      router.push(`/orders/${order.orderId}`);
    } catch (error) {
      if (error instanceof ApiError) {
        setCheckoutError({ message: error.message, ambiguous: false });
      } else {
        setCheckoutError({
          message: "CommerceCore could not be reached or the response could not be read. The checkout outcome may be unknown. Retry deliberately with the same idempotency key.",
          ambiguous: true,
        });
      }
    } finally {
      setCheckoutPending(false);
    }
  }

  return (
    <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(320px,1fr)]">
      <section aria-labelledby="products-heading">
        <div className="mb-3 flex items-center justify-between">
          <h2 id="products-heading" className="text-[10px] font-semibold uppercase tracking-[0.16em] text-muted-foreground">Catalog</h2>
          {products && <span className="text-xs text-muted-foreground">{products.length} available</span>}
        </div>

        {productsError && (
          <div className="space-y-3">
            <ErrorState message={`Products unavailable: ${productsError}`} />
            <Button variant="outline" size="sm" onClick={() => void loadProducts()}><RefreshCw className="size-3.5" />Retry</Button>
          </div>
        )}
        {!products && !productsError && <LoadingState label="Loading products" />}
        {products?.length === 0 && <Card><CardContent className="p-5 text-sm text-muted-foreground">No products available.</CardContent></Card>}

        <div className="grid gap-3 xl:grid-cols-2">
          {products?.map((product) => {
            const quantity = quantities[product.sku] ?? 1;
            const busy = busySku === product.sku;
            return (
              <Card key={product.sku}>
                <CardHeader className="pb-3">
                  <CardTitle className="text-sm">{product.name}</CardTitle>
                  <p className="font-mono text-xs text-muted-foreground">{product.sku}</p>
                </CardHeader>
                <CardContent className="space-y-3">
                  <div className="flex items-end justify-between gap-4">
                    <p className="font-mono text-lg font-semibold">{formatMoney(product.price)}</p>
                    <p className="text-xs text-muted-foreground">
                      {product.inventory ? `Available: ${product.inventory.availableQuantity}` : "Inventory unavailable"}
                    </p>
                  </div>
                  <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-3">
                    <QuantityControl
                      label={`quantity for ${product.name}`}
                      value={quantity}
                      onChange={(value) => setQuantities((current) => ({ ...current, [product.sku]: value }))}
                      disabled={busy}
                    />
                    <Button
                      onClick={() => cart && void mutateItem(product.sku, quantity)}
                      disabled={!cart || busy}
                    >
                      <ShoppingCart className="size-4" />
                      {busy ? "Updating…" : "Set in cart"}
                    </Button>
                  </div>
                  {!cart && <p className="text-xs text-muted-foreground">Create a cart before adding products.</p>}
                </CardContent>
              </Card>
            );
          })}
        </div>
      </section>

      <aside className="lg:sticky lg:top-[72px]" aria-labelledby="cart-heading">
        <Card>
          <CardHeader className="border-b border-border pb-3">
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="mb-1 font-mono text-[10px] uppercase tracking-[0.16em] text-muted-foreground">Current cart</p>
                <CardTitle id="cart-heading">Simulator cart</CardTitle>
              </div>
              {cart && <Button variant="ghost" size="sm" onClick={handleNewCart}>New cart</Button>}
            </div>
            {cart && <p className="break-all font-mono text-[11px] text-muted-foreground">{cart.id}</p>}
          </CardHeader>
          <CardContent className="space-y-4 pt-4">
            {cartLoading && <LoadingState label="Loading saved cart" />}
            {cartError && <ErrorState message={cartError} />}
            {!cartLoading && !cart && (
              <div className="space-y-4 py-3 text-center">
                <p className="text-sm text-muted-foreground">No active server-side cart.</p>
                <Button onClick={() => void handleCreateCart()} disabled={creatingCart}>
                  {creatingCart ? "Creating…" : "Create cart"}
                </Button>
              </div>
            )}

            {cart && cart.items.length === 0 && (
              <div className="border border-dashed border-border p-4 text-[13px] text-muted-foreground">
                Your simulator cart is empty. Add a product to generate commerce activity.
              </div>
            )}

            {cart?.items.map((item) => {
              const product = productBySku.get(item.sku);
              const busy = busySku === item.sku;
              return (
                <div key={item.sku} className="space-y-2.5 border-b border-border pb-3 last:border-0">
                  <div className="flex justify-between gap-3">
                    <div>
                      <p className="text-sm font-medium">{product?.name ?? item.sku}</p>
                      <p className="font-mono text-[11px] text-muted-foreground">{item.sku}</p>
                    </div>
                    {product && <p className="font-mono text-sm">{formatMoney(multiplyMoney(product.price, item.quantity))}</p>}
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <QuantityControl
                      label={`cart quantity for ${product?.name ?? item.sku}`}
                      value={item.quantity}
                      onChange={(value) => void mutateItem(item.sku, value)}
                      disabled={busy}
                    />
                    <Button variant="ghost" size="sm" onClick={() => void mutateItem(item.sku, null)} disabled={busy}>
                      <Trash2 className="size-3.5" />Remove
                    </Button>
                  </div>
                </div>
              );
            })}

            {cart && cart.items.length > 0 && (
              <div className="flex items-baseline justify-between border-t border-border pt-3">
                <div>
                  <p className="text-sm font-medium">Estimated total</p>
                  <p className="text-[11px] text-muted-foreground">Current catalog prices; checkout is authoritative.</p>
                </div>
                <p className="font-mono text-lg font-semibold">{formatMoney(displayTotal)}</p>
              </div>
            )}

            {cart && (
              <div className="space-y-3 border-t border-border pt-4">
                <div className="flex items-center justify-between gap-3">
                  <label htmlFor="idempotency-key" className="text-xs font-medium">Checkout idempotency key</label>
                  <Button variant="ghost" size="sm" onClick={() => setIdempotencyKey(generateCheckoutKey())} disabled={checkoutPending}>
                    <RefreshCw className="size-3.5" />Regenerate
                  </Button>
                </div>
                <Input
                  id="idempotency-key"
                  className="font-mono text-xs"
                  value={idempotencyKey}
                  maxLength={255}
                  onChange={(event) => setIdempotencyKey(event.target.value)}
                  aria-describedby="idempotency-help"
                />
                <p id="idempotency-help" className="text-xs leading-5 text-muted-foreground">
                  Retrying this cart with the same key resolves to the same logical checkout. CommerceCore persistence—not button disabling—provides that guarantee.
                </p>
                {checkoutError && (
                  <div className={checkoutError.ambiguous ? "rounded-md border border-amber-900 bg-amber-950/20 p-3 text-sm text-amber-200" : ""}>
                    {checkoutError.ambiguous ? checkoutError.message : <ErrorState message={`Checkout rejected: ${checkoutError.message}`} />}
                  </div>
                )}
                <Button
                  className="w-full"
                  onClick={() => void handleCheckout()}
                  disabled={checkoutPending || cart.items.length === 0 || !idempotencyKey.trim()}
                >
                  {checkoutPending ? "Checkout in progress…" : "Checkout"}
                </Button>
              </div>
            )}
          </CardContent>
        </Card>
      </aside>
    </div>
  );
}
