"use client";

import { AlertTriangle, ArrowRight, RefreshCw } from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";

import { ErrorState } from "@/components/error-state";
import { LoadingState } from "@/components/loading-state";
import { PageHeader } from "@/components/page-header";
import { SectionHeader } from "@/components/section-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { getEvents } from "@/lib/event-api";
import { filterEvents, publicationLabel, receiptLabel, shortId, type EventSummary } from "@/lib/event-evidence";

const selectClass = "h-9 rounded-md border border-border bg-background px-3 text-xs text-foreground outline-none focus:border-blue-500";

export function EventExplorer({ initialSearch = "" }: { initialSearch?: string }) {
  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [initialError, setInitialError] = useState(false);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [search, setSearch] = useState(initialSearch);
  const [eventType, setEventType] = useState("ALL");
  const [publication, setPublication] = useState<"ALL" | "PUBLISHED" | "PENDING">("ALL");
  const [receipt, setReceipt] = useState<"ALL" | "RECORDED" | "MISSING">("ALL");

  const load = useCallback(async (refresh: boolean) => {
    if (refresh) setRefreshing(true);
    setRefreshError(null);
    try {
      setEvents(await getEvents());
      setInitialError(false);
    } catch (error) {
      if (refresh && events) setRefreshError(error instanceof Error ? error.message : "Refresh failed.");
      else setInitialError(true);
    } finally {
      if (refresh) setRefreshing(false);
    }
  }, [events]);

  useEffect(() => { if (events === null && !initialError) queueMicrotask(() => void load(false)); }, [events, initialError, load]);

  const eventTypes = useMemo(() => [...new Set((events ?? []).map((event) => event.eventType))].sort(), [events]);
  const filtered = useMemo(() => filterEvents(events ?? [], { search, eventType, publication, receipt }), [events, search, eventType, publication, receipt]);

  return (
    <div className="min-w-0 space-y-5">
      <PageHeader title="Event Explorer" description="Inspect persisted transactional outbox publication and consumer evidence." />
      {refreshError && <div className="flex items-start gap-3 rounded-md border border-amber-900 bg-amber-950/20 p-4 text-sm text-amber-200" role="alert"><AlertTriangle className="mt-0.5 size-4 shrink-0" /><div><p className="font-medium">Refresh failed — showing last successfully fetched evidence</p><p className="mt-1 text-xs">{refreshError}</p></div></div>}
      <section aria-labelledby="events-heading">
        <SectionHeader title="Events" meta="Newest 100 persisted outbox records" />
        <div className="mb-3 flex flex-col gap-2 lg:flex-row">
          <Input aria-label="Filter by event or aggregate ID" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Event ID, aggregate ID, or related order ID" className="lg:max-w-md" />
          <select aria-label="Event type" value={eventType} onChange={(event) => setEventType(event.target.value)} className={selectClass}><option value="ALL">All event types</option>{eventTypes.map((type) => <option key={type}>{type}</option>)}</select>
          <select aria-label="Publication state" value={publication} onChange={(event) => setPublication(event.target.value as typeof publication)} className={selectClass}><option value="ALL">All publication states</option><option value="PUBLISHED">Published</option><option value="PENDING">Pending</option></select>
          <select aria-label="Receipt state" value={receipt} onChange={(event) => setReceipt(event.target.value as typeof receipt)} className={selectClass}><option value="ALL">All receipt states</option><option value="RECORDED">Receipt recorded</option><option value="MISSING">No receipt</option></select>
          <Button variant="outline" onClick={() => void load(true)} disabled={refreshing}><RefreshCw className={refreshing ? "size-4 animate-spin" : "size-4"} />{refreshing ? "Refreshing…" : "Refresh"}</Button>
        </div>
        {events === null && !initialError && <LoadingState label="Loading persisted event evidence" />}
        {initialError && events === null && <div className="space-y-3"><ErrorState message="Event inspection unavailable. CommerceCore could not provide persisted event evidence." /><Button variant="outline" onClick={() => void load(false)}>Retry</Button></div>}
        {events?.length === 0 && <div className="rounded-md border border-dashed border-border p-5"><p className="text-sm font-medium">No event evidence yet.</p><p className="mt-1 text-xs text-muted-foreground">Use the Store Simulator to create a checkout and generate CommerceCore activity.</p><Link href="/store" className="mt-3 inline-flex items-center gap-2 text-sm text-blue-400 hover:text-blue-300">Open Store Simulator <ArrowRight className="size-4" /></Link></div>}
        {events && events.length > 0 && filtered.length === 0 && <p className="rounded-md border border-dashed border-border p-5 text-sm text-muted-foreground">No persisted events match these filters.</p>}
        {filtered.length > 0 && <div className="min-w-0 overflow-x-auto rounded-md border border-border"><table className="w-full min-w-[900px] text-left text-xs"><thead className="border-b border-border bg-muted/30 text-[10px] uppercase tracking-[0.12em] text-muted-foreground"><tr><th className="px-3 py-2.5 font-medium">Event</th><th className="px-3 py-2.5 font-medium">Type</th><th className="px-3 py-2.5 font-medium">Aggregate</th><th className="px-3 py-2.5 font-medium">Outbox</th><th className="px-3 py-2.5 font-medium">Consumer evidence</th><th className="px-3 py-2.5 font-medium">Created</th></tr></thead><tbody>{filtered.map((event) => <tr key={event.eventId} className="border-b border-border last:border-0 hover:bg-muted/25"><td className="px-3 py-3"><Link href={`/events/${event.eventId}`} className="font-mono text-blue-400 hover:text-blue-300" title={event.eventId}>{shortId(event.eventId)}</Link></td><td className="px-3 py-3 font-mono text-foreground">{event.eventType}</td><td className="px-3 py-3"><span className="text-muted-foreground">{event.aggregateType}</span> <span className="font-mono" title={event.aggregateId}>{shortId(event.aggregateId)}</span></td><td className="px-3 py-3"><StatusBadge tone={event.published ? "success" : "warning"}>{publicationLabel(event)}</StatusBadge></td><td className="px-3 py-3"><StatusBadge tone={event.consumers.some((consumer) => consumer.receiptExists) ? "success" : "neutral"}>{receiptLabel(event.consumers)}</StatusBadge></td><td className="whitespace-nowrap px-3 py-3 text-muted-foreground">{new Date(event.createdAt).toLocaleString()}</td></tr>)}</tbody></table></div>}
      </section>
      <p className="text-xs leading-5 text-muted-foreground">Published means CommerceCore marked the outbox row after the sink returned successfully. Consumer receipts are separate persisted evidence. Kafka transport is at least once; no delivery-attempt count is recorded.</p>
    </div>
  );
}
