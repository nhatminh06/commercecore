"use client";

import { AlertTriangle, ArrowLeft, ArrowRight, Check, Copy, Database, Radio, RefreshCw } from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";

import { ErrorState } from "@/components/error-state";
import { LoadingState } from "@/components/loading-state";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiError } from "@/lib/api";
import { getEvent } from "@/lib/event-api";
import { publicationLabel, type EventInspection } from "@/lib/event-evidence";

function EvidenceStage({ title, value, detail, observed }: { title: string; value: string; detail: string; observed: boolean }) {
  return <div className="min-w-0 flex-1 rounded-md border border-border bg-card p-3"><div className="flex items-center justify-between gap-2"><p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">{title}</p>{observed ? <Check className="size-3.5 text-emerald-400" /> : <span className="text-muted-foreground">—</span>}</div><p className="mt-2 font-mono text-xs text-foreground">{value}</p><p className="mt-1.5 text-[11px] leading-4 text-muted-foreground">{detail}</p></div>;
}

export function EventInspector({ eventId }: { eventId: string }) {
  const [event, setEvent] = useState<EventInspection | null>(null);
  const [initialError, setInitialError] = useState<unknown>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async (refresh: boolean) => {
    if (refresh) setRefreshing(true);
    setRefreshError(null);
    try { setEvent(await getEvent(eventId)); setInitialError(null); }
    catch (error) { if (refresh && event) setRefreshError(error instanceof Error ? error.message : "Refresh failed."); else setInitialError(error); }
    finally { if (refresh) setRefreshing(false); }
  }, [event, eventId]);

  useEffect(() => { if (!event && !initialError) queueMicrotask(() => void load(false)); }, [event, initialError, load]);

  if (!event && !initialError) return <LoadingState label="Loading persisted event evidence" />;
  const notFound = initialError instanceof ApiError && initialError.status === 404 && initialError.code === "event_not_found";
  if (!event && notFound) return <Card className="max-w-2xl"><CardHeader><CardTitle>Event not found</CardTitle></CardHeader><CardContent className="space-y-4"><p className="text-sm text-muted-foreground">CommerceCore has no persisted inspection evidence for this event ID.</p><p className="break-all font-mono text-xs">{eventId}</p><Link href="/events" className="text-sm text-blue-400 hover:text-blue-300">Back to Event Explorer</Link></CardContent></Card>;
  if (!event) return <div className="max-w-2xl space-y-3"><ErrorState message="Event inspection unavailable. CommerceCore could not provide persisted evidence." /><Button variant="outline" onClick={() => void load(false)}>Retry</Button></div>;

  const proof = event.consumers.find((consumer) => consumer.name === "commercecore-proof-consumer");
  const workflow = event.consumers.find((consumer) => consumer.name === "commercecore-order-workflow");
  const anyReceipt = event.consumers.some((consumer) => consumer.receiptExists);

  return <div className="min-w-0 space-y-5">
    <div className="flex flex-col justify-between gap-4 border-b border-border pb-4 sm:flex-row sm:items-end"><div className="min-w-0"><p className="text-[10px] font-semibold uppercase tracking-[0.16em] text-muted-foreground">Event Explorer</p><div className="mt-1.5 flex flex-wrap items-center gap-3"><h1 className="text-xl font-semibold tracking-tight">{event.eventType}</h1><StatusBadge tone={event.published ? "success" : "warning"}>{publicationLabel(event)}</StatusBadge></div><p className="mt-2 break-all font-mono text-xs text-muted-foreground">{event.eventId}</p></div><Button variant="outline" onClick={() => void load(true)} disabled={refreshing}><RefreshCw className={refreshing ? "size-4 animate-spin" : "size-4"} />{refreshing ? "Refreshing…" : "Refresh"}</Button></div>
    {refreshError && <div className="flex items-start gap-3 rounded-md border border-amber-900 bg-amber-950/20 p-4 text-sm text-amber-200" role="alert"><AlertTriangle className="mt-0.5 size-4 shrink-0" /><div><p className="font-medium">Refresh failed — showing last successfully fetched evidence</p><p className="mt-1 text-xs">{refreshError}</p></div></div>}

    <Card><CardHeader><CardTitle>Persisted identity</CardTitle></CardHeader><CardContent><dl className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4"><div><dt className="text-xs text-muted-foreground">Event ID</dt><dd className="mt-1 break-all font-mono text-xs">{event.eventId}</dd></div><div><dt className="text-xs text-muted-foreground">Aggregate</dt><dd className="mt-1 text-xs">{event.aggregateType} <span className="break-all font-mono">{event.aggregateId}</span></dd></div><div><dt className="text-xs text-muted-foreground">Business fact committed</dt><dd className="mt-1 text-xs">{new Date(event.createdAt).toLocaleString()}</dd></div><div><dt className="text-xs text-muted-foreground">Publisher claims</dt><dd className="mt-1 font-mono text-xs">{event.publicationAttemptCount}</dd><p className="mt-1 text-[11px] text-muted-foreground">Not a Kafka delivery count</p></div></dl>{event.relatedOrderId && <Link href={`/orders/${event.relatedOrderId}`} className="mt-4 inline-flex items-center gap-2 text-sm text-blue-400 hover:text-blue-300">Inspect related order <ArrowRight className="size-4" /></Link>}</CardContent></Card>

    <section aria-labelledby="delivery-evidence"><div className="mb-2.5"><h2 id="delivery-evidence" className="text-[10px] font-semibold uppercase tracking-[0.16em] text-muted-foreground">Observed evidence</h2><p className="mt-1 text-xs text-muted-foreground">Each successful state below is backed by a persisted CommerceCore row.</p></div><div className="flex min-w-0 flex-col gap-2 xl:flex-row xl:items-stretch"><EvidenceStage title="Business fact" value="COMMITTED" detail="The outbox record exists with a persisted creation time." observed /><ArrowRight className="hidden size-4 shrink-0 self-center text-muted-foreground xl:block" /><EvidenceStage title="Outbox" value={event.published ? "PUBLISHED" : "PENDING PUBLICATION"} detail={event.published ? `Marked ${new Date(event.publishedAt!).toLocaleString()}. This does not prove consumer processing.` : "The fact is committed locally; CommerceCore has not marked publication complete."} observed={event.published} /><ArrowRight className="hidden size-4 shrink-0 self-center text-muted-foreground xl:block" /><EvidenceStage title="Proof consumer" value={proof?.receiptExists ? "RECEIPT EXISTS" : "NO RECEIPT EVIDENCE"} detail={proof?.processedAt ? `Persisted ${new Date(proof.processedAt).toLocaleString()}.` : "No persisted proof-consumer receipt is available."} observed={Boolean(proof?.receiptExists)} />{workflow && <><ArrowRight className="hidden size-4 shrink-0 self-center text-muted-foreground xl:block" /><EvidenceStage title="Order workflow" value={workflow.receiptExists ? "TRANSACTION COMMITTED" : "NO RECEIPT EVIDENCE"} detail={workflow.receiptExists ? "Receipt and idempotent workflow outcome committed in one transaction." : "No persisted workflow receipt is available."} observed={workflow.receiptExists} /></>}</div></section>

    <Card><CardHeader><div className="flex items-center gap-2"><Radio className="size-4 text-muted-foreground" /><CardTitle>Architecture, not per-event evidence</CardTitle></div></CardHeader><CardContent className="grid gap-4 text-xs sm:grid-cols-2"><div><p className="font-medium text-foreground">Kafka transport</p><p className="mt-1 leading-5 text-muted-foreground">At-least-once. CommerceCore does not persist every physical delivery attempt and cannot report a delivery count.</p></div><div><p className="font-medium text-foreground">Idempotent effects</p><p className="mt-1 leading-5 text-muted-foreground">A consumer receipt protects one logical effect per event ID. It does not prove exactly-once transport.</p></div></CardContent></Card>

    <Card><CardHeader><div className="flex items-center gap-2"><Database className="size-4 text-muted-foreground" /><CardTitle>Consumer receipts</CardTitle></div></CardHeader><CardContent><div className="divide-y divide-border">{event.consumers.map((consumer) => <div key={consumer.name} className="grid gap-2 py-3 first:pt-0 last:pb-0 sm:grid-cols-[1fr_auto]"><div><p className="font-mono text-xs">{consumer.name}</p><p className="mt-1 text-xs text-muted-foreground">{consumer.purpose}</p></div><div className="sm:text-right"><StatusBadge tone={consumer.receiptExists ? "success" : "neutral"}>{consumer.receiptExists ? "RECEIPT EXISTS" : "NOT OBSERVED"}</StatusBadge>{consumer.processedAt && <p className="mt-1 text-[11px] text-muted-foreground">{new Date(consumer.processedAt).toLocaleString()}</p>}</div></div>)}</div><p className="mt-4 border-t border-border pt-3 text-xs leading-5 text-muted-foreground">A persistent receipt records that the named consumer has already committed its logical processing for this event ID. Re-delivery can safely avoid applying the same logical effect again. {anyReceipt ? "At least one such receipt is observed." : "No receipt evidence is currently observed."}</p></CardContent></Card>

    <Card><CardHeader><div className="flex items-center justify-between gap-3"><CardTitle>Persisted payload</CardTitle><Copy className="size-3.5 text-muted-foreground" /></div></CardHeader><CardContent><pre className="max-w-full overflow-x-auto rounded-md border border-border bg-background p-4 font-mono text-xs leading-5 text-muted-foreground">{JSON.stringify(event.payload, null, 2)}</pre></CardContent></Card>
    <Link href="/events" className="inline-flex items-center gap-2 text-sm text-blue-400 hover:text-blue-300"><ArrowLeft className="size-4" />Back to Event Explorer</Link>
  </div>;
}
