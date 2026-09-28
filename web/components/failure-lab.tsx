"use client";

import { AlertTriangle, ArrowRight, Beaker, CheckCircle2, RefreshCw, ShieldAlert } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { CopyId } from "@/components/copy-id";
import { ErrorState } from "@/components/error-state";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { FailureLabError, inspectOrder, publishOutbox, reconcilePayment, runFailureExperiment } from "@/lib/failure-api";
import { canReconcile, eventLink, failureScenarios, observedExpectedOutcome, orderLink, type ExperimentResult, type FailureScenario } from "@/lib/failure-lab";
import { toneForStatus } from "@/lib/order-inspection";

type LabPhase = "idle" | "running" | "complete" | "reconciling" | "error";

function errorMessage(error: unknown) {
  if (error instanceof FailureLabError) return `${error.kind === "configuration" ? "Configuration error" : error.kind === "business" ? "Business rejection" : "Infrastructure/API failure"}: ${error.message}`;
  return error instanceof Error ? `Infrastructure/API failure: ${error.message}` : "Infrastructure/API failure: an unexpected error occurred.";
}

function StateCell({ label, value }: { label: string; value: string }) {
  return <div><dt className="text-xs text-muted-foreground">{label}</dt><dd className="mt-1"><StatusBadge tone={toneForStatus(value)}>{value}</StatusBadge></dd></div>;
}

export function FailureLab() {
  const [selected, setSelected] = useState<FailureScenario | null>(null);
  const [phase, setPhase] = useState<LabPhase>("idle");
  const [result, setResult] = useState<ExperimentResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);

  async function run(scenario: FailureScenario) {
    setSelected(scenario); setResult(null); setError(null); setRefreshError(null); setPhase("running");
    try { setResult(await runFailureExperiment(scenario)); setPhase("complete"); }
    catch (failure) { setError(errorMessage(failure)); setPhase("error"); }
  }

  async function refresh() {
    if (!result) return;
    setRefreshError(null);
    try { setResult({ ...result, inspection: await inspectOrder(result.orderId) }); }
    catch (failure) { setRefreshError(errorMessage(failure)); }
  }

  async function reconcile() {
    if (!result || !canReconcile(result)) return;
    setPhase("reconciling"); setError(null); setRefreshError(null);
    try {
      await reconcilePayment(result.paymentId);
      const publication = await publishOutbox();
      setResult({ ...result, publication, inspection: await inspectOrder(result.orderId) });
      setPhase("complete");
    } catch (failure) { setError(errorMessage(failure)); setPhase("error"); }
  }

  const inspection = result?.inspection;
  const reservationStates = [...new Set(inspection?.reservations.map((reservation) => reservation.status) ?? [])];

  return <div className="min-w-0 space-y-5">
    <PageHeader title="Failure Lab" description="Trigger controlled distributed-system outcomes and inspect real persisted CommerceCore state." />
    <div className="flex items-start gap-3 rounded-md border border-amber-900/70 bg-amber-950/15 p-3 text-xs text-amber-100"><ShieldAlert className="mt-0.5 size-4 shrink-0" /><div><p className="font-medium">Development control</p><p className="mt-1 leading-5 text-amber-200/75">Failure Lab changes the simulated provider&apos;s next one-shot outcome and creates real products, carts, orders, payments, and events. It is unavailable without development profiles.</p></div></div>

    <section aria-labelledby="scenarios-heading"><div className="mb-2.5 border-b border-border pb-2"><h2 id="scenarios-heading" className="text-[10px] font-semibold uppercase tracking-[0.16em] text-muted-foreground">Scenarios</h2></div><div className="grid gap-3 lg:grid-cols-3">{failureScenarios.map((scenario) => <Card key={scenario.id} className={selected?.id === scenario.id ? "border-blue-900" : ""}><CardHeader><div className="flex items-start justify-between gap-3"><div><CardTitle>{scenario.title}</CardTitle><p className="mt-2 text-xs leading-5 text-muted-foreground">{scenario.injectedBehavior}</p></div><Beaker className="size-4 shrink-0 text-muted-foreground" /></div></CardHeader><CardContent className="space-y-4"><div><p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">Demonstrates</p><p className="mt-1.5 text-xs text-foreground">{scenario.demonstrates.join(" · ")}</p></div><p className="text-xs leading-5 text-muted-foreground">Expected semantic outcome: {scenario.semanticOutcome}</p><Button className="w-full" variant={scenario.id === "TIMEOUT_AFTER_COMMIT" ? "default" : "outline"} onClick={() => void run(scenario)} disabled={phase === "running" || phase === "reconciling"}>{phase === "running" && selected?.id === scenario.id ? "Running real flow…" : "Run experiment"}</Button></CardContent></Card>)}</div></section>

    {error && <ErrorState message={error} />}
    {refreshError && <div className="flex items-start gap-3 rounded-md border border-amber-900 bg-amber-950/20 p-4 text-sm text-amber-200"><AlertTriangle className="mt-0.5 size-4 shrink-0" /><div><p className="font-medium">Refresh failed — showing last successfully fetched state</p><p className="mt-1 text-xs">{refreshError}</p></div></div>}

    {result && inspection && <section aria-labelledby="result-heading" className="space-y-4"><div className="flex flex-col justify-between gap-3 border-b border-border pb-3 sm:flex-row sm:items-end"><div><h2 id="result-heading" className="text-[10px] font-semibold uppercase tracking-[0.16em] text-muted-foreground">Current experiment</h2><p className="mt-1 text-sm font-medium">{result.scenario.title}</p></div><div className="flex flex-wrap gap-2"><Button variant="outline" size="sm" onClick={() => void refresh()} disabled={phase === "reconciling"}><RefreshCw className="size-3.5" />Refresh state</Button>{canReconcile(result) && <Button size="sm" onClick={() => void reconcile()} disabled={phase === "reconciling"}>{phase === "reconciling" ? "Reconciling…" : "Reconcile provider state"}</Button>}</div></div>

      <div className="grid gap-3 lg:grid-cols-3"><Card><CardHeader><CardTitle>Injected behavior</CardTitle></CardHeader><CardContent><p className="font-mono text-xs">{result.scenario.providerOutcome}</p><p className="mt-2 text-xs leading-5 text-muted-foreground">{result.scenario.injectedBehavior}</p><p className="mt-3 text-[11px] text-muted-foreground">One-shot in-memory provider configuration; resets to SUCCESS when consumed.</p></CardContent></Card><Card><CardHeader><CardTitle>Observed result</CardTitle></CardHeader><CardContent className="space-y-3"><StateCell label="Payment operation returned" value={result.operationPaymentStatus} /><p className="text-xs leading-5 text-muted-foreground">{result.operationPaymentStatus === "UNKNOWN" ? "The provider call exceeded the deadline. CommerceCore did not treat ambiguity as failure or retry authorization." : "CommerceCore received a definitive provider response."}</p>{observedExpectedOutcome(result) && <p className="flex items-center gap-2 text-xs text-emerald-300"><CheckCircle2 className="size-3.5" />Expected observation persisted</p>}</CardContent></Card><Card><CardHeader><CardTitle>Persisted state</CardTitle></CardHeader><CardContent><dl className="grid grid-cols-2 gap-4"><StateCell label="Order" value={inspection.order.status} />{inspection.payment && <StateCell label="Payment" value={inspection.payment.status} />}<div><dt className="text-xs text-muted-foreground">Reservations</dt><dd className="mt-1 flex flex-wrap gap-1">{reservationStates.map((state) => <StatusBadge key={state} tone={toneForStatus(state)}>{state}</StatusBadge>)}</dd></div><div><dt className="text-xs text-muted-foreground">Reconciliation</dt><dd className="mt-1"><StatusBadge tone={inspection.reconciliation ? toneForStatus(inspection.reconciliation.status) : "neutral"}>{inspection.reconciliation?.status ?? "NOT RUN"}</StatusBadge></dd></div></dl></CardContent></Card></div>

      <Card><CardHeader><CardTitle>Identity and evidence</CardTitle></CardHeader><CardContent className="space-y-4"><dl className="grid gap-4 sm:grid-cols-2"><div><dt className="text-xs text-muted-foreground">Order</dt><dd className="mt-1 flex min-w-0 items-center gap-1"><span className="break-all font-mono text-xs">{result.orderId}</span><CopyId value={result.orderId} label="order ID" /></dd></div><div><dt className="text-xs text-muted-foreground">Payment / provider request</dt><dd className="mt-1 flex min-w-0 items-center gap-1"><span className="break-all font-mono text-xs">{result.providerRequestId}</span><CopyId value={result.providerRequestId} label="provider request ID" /></dd></div></dl><div className="grid gap-3 border-t border-border pt-4 sm:grid-cols-2"><div><p className="text-xs font-medium">Stable request identity</p><p className="mt-1 text-xs leading-5 text-muted-foreground">CommerceCore uses the payment UUID as the provider request ID. Reconciliation looks up this same ID.</p></div><div><p className="text-xs font-medium">Authorization call count</p><p className="mt-1 text-xs leading-5 text-muted-foreground">Not exposed by persisted inspection evidence. Existing backend tests verify reconciliation does not call authorize again.</p></div></div>{result.publication && <p className="text-xs text-muted-foreground">Outbox publisher claimed {result.publication.claimed} and marked {result.publication.published} event(s) published. This is not a Kafka delivery count.</p>}<div className="flex flex-wrap gap-4 border-t border-border pt-4 text-sm"><Link href={orderLink(result.orderId)} className="inline-flex items-center gap-2 text-blue-400 hover:text-blue-300">Inspect Order <ArrowRight className="size-4" /></Link><Link href={eventLink(result.orderId)} className="inline-flex items-center gap-2 text-blue-400 hover:text-blue-300">View Related Events <ArrowRight className="size-4" /></Link></div></CardContent></Card>
    </section>}
  </div>;
}
