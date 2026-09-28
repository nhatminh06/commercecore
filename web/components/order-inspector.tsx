"use client";

import { Activity, AlertTriangle, ArrowLeft, Boxes, CreditCard, RefreshCw } from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";

import { CopyId } from "@/components/copy-id";
import { ErrorState } from "@/components/error-state";
import { LoadingState } from "@/components/loading-state";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiError } from "@/lib/api";
import { getOrderInspection } from "@/lib/order-api";
import {
  explanationForStatus,
  toneForStatus,
  type OrderInspection,
} from "@/lib/order-inspection";
import { formatMoney } from "@/lib/store-utils";

function StateValue({ label, state }: { label: string; state: string }) {
  const explanation = explanationForStatus(state);
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-1"><StatusBadge tone={toneForStatus(state)}>{state}</StatusBadge></dd>
      {explanation && <p className="mt-2 max-w-md text-xs leading-5 text-muted-foreground">{explanation}</p>}
    </div>
  );
}

function IdValue({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-1 flex min-w-0 items-center gap-1">
        <span className="min-w-0 break-all font-mono text-xs">{value}</span>
        <CopyId value={value} label={label} />
      </dd>
    </div>
  );
}

function formatFetchTime(value: Date) {
  return new Intl.DateTimeFormat(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(value);
}

export function OrderInspector({ orderId }: { orderId: string }) {
  const [inspection, setInspection] = useState<OrderInspection | null>(null);
  const [initialError, setInitialError] = useState<unknown>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [lastFetchedAt, setLastFetchedAt] = useState<Date | null>(null);

  const load = useCallback(async (refresh: boolean) => {
    if (refresh) setRefreshing(true);
    setRefreshError(null);
    try {
      const result = await getOrderInspection(orderId);
      setInspection(result);
      setInitialError(null);
      setLastFetchedAt(new Date());
    } catch (error) {
      if (refresh) {
        setRefreshError(error instanceof Error ? error.message : "Refresh failed.");
      } else {
        setInitialError(error);
      }
    } finally {
      if (refresh) setRefreshing(false);
    }
  }, [orderId]);

  useEffect(() => {
    queueMicrotask(() => void load(false));
  }, [load]);

  const notFound = initialError instanceof ApiError
    && initialError.status === 404
    && initialError.code === "order_not_found";

  if (!inspection && !initialError) return <LoadingState label="Loading authoritative order state" />;

  if (!inspection && notFound) {
    return (
      <Card className="max-w-2xl">
        <CardHeader><CardTitle>Order not found</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <p className="text-sm text-muted-foreground">No CommerceCore order exists with ID:</p>
          <p className="break-all font-mono text-sm">{orderId}</p>
          <Link href="/orders" className="inline-flex text-sm text-blue-400 hover:text-blue-300">Inspect another order</Link>
        </CardContent>
      </Card>
    );
  }

  if (!inspection) {
    return (
      <div className="max-w-2xl space-y-4">
        <ErrorState message="Order state unavailable. CommerceCore could not provide the current order state." />
        <Button variant="outline" onClick={() => void load(false)}><RefreshCw className="size-4" />Retry</Button>
      </div>
    );
  }

  const { order, payment, reservations, reconciliation } = inspection;
  const reservationStates = [...new Set(reservations.map((reservation) => reservation.status))];

  return (
    <div className="space-y-5">
      <div className="flex flex-col justify-between gap-4 border-b border-border pb-4 sm:flex-row sm:items-end">
        <div className="min-w-0">
          <p className="text-[10px] font-semibold uppercase tracking-[0.16em] text-muted-foreground">Order Inspector</p>
          <div className="mt-1.5 flex flex-wrap items-center gap-3">
            <h1 className="break-all text-xl font-semibold tracking-tight">Order <span className="font-mono text-lg">{order.orderId}</span></h1>
            <StatusBadge tone={toneForStatus(order.status)}>{order.status}</StatusBadge>
          </div>
          <p className="mt-1.5 text-xs text-muted-foreground">
            <span className="font-mono text-foreground">{formatMoney(order.total)}</span> · {order.items.length} {order.items.length === 1 ? "item" : "items"} · Current state fetched {lastFetchedAt ? formatFetchTime(lastFetchedAt) : "just now"}
          </p>
        </div>
        <Button variant="outline" onClick={() => void load(true)} disabled={refreshing}>
          <RefreshCw className={refreshing ? "size-4 animate-spin" : "size-4"} />
          {refreshing ? "Refreshing…" : "Refresh state"}
        </Button>
      </div>

      {refreshError && (
        <div className="flex items-start gap-3 rounded-md border border-amber-900 bg-amber-950/20 p-4 text-sm text-amber-200" role="alert">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" />
          <div><p className="font-medium">Refresh failed — showing last successful state</p><p className="mt-1 text-xs">{refreshError}</p></div>
        </div>
      )}

      <section aria-labelledby="current-state-heading">
        <div className="mb-2.5">
          <h2 id="current-state-heading" className="text-[10px] font-semibold uppercase tracking-[0.16em] text-muted-foreground">Current state</h2>
          <p className="mt-1 text-xs text-muted-foreground">Authoritative values read from CommerceCore. This is not an event history.</p>
        </div>
        <Card>
          <CardContent className="pt-4">
            <dl className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
              <StateValue label="Order" state={order.status} />
              {payment ? <StateValue label="Payment" state={payment.status} /> : <div><dt className="text-xs text-muted-foreground">Payment</dt><dd className="mt-1"><StatusBadge>NOT INITIATED</StatusBadge></dd></div>}
              <div><dt className="text-xs text-muted-foreground">Reservations</dt><dd className="mt-1 flex flex-wrap gap-1.5">{reservationStates.length === 0 ? <StatusBadge>NONE</StatusBadge> : reservationStates.map((state) => <StatusBadge key={state} tone={toneForStatus(state)}>{state}</StatusBadge>)}</dd><p className="mt-2 text-xs text-muted-foreground">{reservations.length} {reservations.length === 1 ? "record" : "records"}</p></div>
              {reconciliation?.lastProviderStatus
                ? <StateValue label="Last provider observation" state={reconciliation.lastProviderStatus} />
                : <div><dt className="text-xs text-muted-foreground">Provider observation</dt><dd className="mt-1"><StatusBadge>NOT OBSERVED</StatusBadge></dd><p className="mt-2 text-xs text-muted-foreground">No reconciliation observation recorded</p></div>}
            </dl>
            {reconciliation && <div className="mt-4 border-t border-border pt-3"><StateValue label="Reconciliation" state={reconciliation.status} /></div>}
          </CardContent>
        </Card>
      </section>

      <Card>
        <CardHeader><CardTitle>Order details</CardTitle></CardHeader>
        <CardContent>
          <dl className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
            <div><dt className="text-xs text-muted-foreground">Authoritative total</dt><dd className="mt-1 font-mono text-lg font-semibold">{formatMoney(order.total)}</dd></div>
            <div><dt className="text-xs text-muted-foreground">Line items</dt><dd className="mt-1 font-mono text-sm">{order.items.length}</dd></div>
            <div><dt className="text-xs text-muted-foreground">Created</dt><dd className="mt-1 text-sm">{new Date(inspection.orderCreatedAt).toLocaleString()}</dd></div>
            <IdValue label="Order ID" value={order.orderId} />
          </dl>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>Related state</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 border border-border bg-muted/20 p-3 md:grid-cols-[1fr_auto_1fr] md:items-center">
            <div><p className="text-xs text-muted-foreground">Order</p><p className="mt-1 break-all font-mono text-xs">{order.orderId}</p></div>
            <span className="hidden text-muted-foreground md:block">→</span>
            <div><p className="text-xs text-muted-foreground">Payment / provider request</p><p className="mt-1 break-all font-mono text-xs">{payment?.paymentId ?? "No payment recorded"}</p></div>
          </div>
          {payment && (
            <dl className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
              <IdValue label="Payment ID" value={payment.paymentId} />
              <IdValue label="Provider request ID" value={payment.paymentId} />
              <div><dt className="text-xs text-muted-foreground">Provider reference</dt><dd className="mt-1 break-all font-mono text-xs">{payment.providerReference ?? "Not recorded"}</dd></div>
              <div><dt className="text-xs text-muted-foreground">Payment amount</dt><dd className="mt-1 font-mono text-sm">{formatMoney(payment.amount)}</dd></div>
            </dl>
          )}
          {reconciliation && (
            <div className="rounded-md border border-border p-4">
              <div className="flex flex-wrap items-center gap-3"><p className="text-sm font-medium">Reconciliation case</p><StatusBadge tone={toneForStatus(reconciliation.status)}>{reconciliation.status}</StatusBadge></div>
              {reconciliation.reason && <p className="mt-2 font-mono text-xs text-muted-foreground">Reason: {reconciliation.reason}</p>}
              {explanationForStatus(reconciliation.status) && <p className="mt-2 text-xs leading-5 text-muted-foreground">{explanationForStatus(reconciliation.status)}</p>}
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>Reservations</CardTitle></CardHeader>
        <CardContent>
          {reservations.length === 0 ? <p className="text-sm text-muted-foreground">No reservations are associated with this order.</p> : (
            <div>
              {reservations.map((reservation) => (
                <div key={reservation.id} className="grid gap-3 border-b border-border py-3 first:pt-0 last:border-0 last:pb-0 sm:grid-cols-[1fr_auto]">
                  <div><p className="font-mono text-xs">{reservation.sku} × {reservation.quantity}</p><div className="mt-2 flex items-center gap-1"><span className="break-all font-mono text-[11px] text-muted-foreground">{reservation.id}</span><CopyId value={reservation.id} label="reservation ID" /></div></div>
                  <div className="sm:text-right"><StatusBadge tone={toneForStatus(reservation.status)}>{reservation.status}</StatusBadge><p className="mt-2 text-[11px] text-muted-foreground">Expires {new Date(reservation.expiresAt).toLocaleString()}</p></div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>Order items</CardTitle></CardHeader>
        <CardContent className="overflow-x-auto">
          <table className="w-full min-w-[560px] text-left text-sm">
            <thead className="border-b border-border text-xs text-muted-foreground"><tr><th className="pb-3 font-medium">SKU</th><th className="pb-3 text-right font-medium">Quantity</th><th className="pb-3 text-right font-medium">Unit price</th><th className="pb-3 text-right font-medium">Subtotal</th></tr></thead>
            <tbody>{order.items.map((item) => <tr key={item.sku} className="border-b border-border last:border-0"><td className="py-3 font-mono text-xs">{item.sku}</td><td className="py-3 text-right font-mono">{item.quantity}</td><td className="py-3 text-right font-mono">{formatMoney(item.unitPrice)}</td><td className="py-3 text-right font-mono">{formatMoney(item.lineTotal)}</td></tr>)}</tbody>
          </table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center gap-2"><Boxes className="size-4 text-muted-foreground" /><CardTitle>State model</CardTitle></div>
          <p className="text-xs font-medium text-amber-300">Documented possibilities — not observed order history</p>
        </CardHeader>
        <CardContent className="space-y-4 text-sm text-muted-foreground">
          <div className="grid gap-3 md:grid-cols-3">
            <div className="rounded-md border border-border p-4"><p className="font-mono text-foreground">Payment AUTHORIZED</p><p className="mt-2 text-xs leading-5">The workflow may confirm an intact reservation and move the order to CONFIRMED. Lost ownership produces review evidence instead.</p></div>
            <div className="rounded-md border border-border p-4"><p className="font-mono text-foreground">Payment FAILED</p><p className="mt-2 text-xs leading-5">The workflow may cancel a pending order and release active reservations, restoring inventory once.</p></div>
            <div className="rounded-md border border-border p-4"><p className="font-mono text-amber-300">Payment UNKNOWN</p><p className="mt-2 text-xs leading-5">CommerceCore preserves ambiguity. A webhook or read-only provider lookup may later supply definitive evidence.</p></div>
          </div>
          <p className="border-t border-border pt-4 text-xs">No sequence or timestamp above is asserted for this order. Use Event Explorer for persisted event evidence.</p>
        </CardContent>
      </Card>

      <div className="flex flex-wrap gap-4 text-sm">
        <Link href="/store" className="inline-flex items-center gap-2 text-blue-400 hover:text-blue-300"><ArrowLeft className="size-4" />Back to Store</Link>
        <Link href="/orders" className="inline-flex items-center gap-2 text-blue-400 hover:text-blue-300"><CreditCard className="size-4" />Inspect another order</Link>
        <Link href={`/events?aggregateId=${encodeURIComponent(order.orderId)}`} className="inline-flex items-center gap-2 text-blue-400 hover:text-blue-300"><Activity className="size-4" />View related events</Link>
      </div>
    </div>
  );
}
