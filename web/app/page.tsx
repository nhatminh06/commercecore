import { ArrowRight, CheckCircle2, GitBranch, ShieldCheck, ShoppingBasket } from "lucide-react";
import Link from "next/link";
import { PageHeader } from "@/components/page-header";
import { SectionHeader } from "@/components/section-header";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const guarantees = [
  ["Inventory", "Conditional PostgreSQL updates prevent overselling."],
  ["Checkout", "A persisted key and advisory lock produce one logical order."],
  ["Payments", "UNKNOWN preserves ambiguous remote outcomes without guessing."],
  ["Reconciliation", "Provider truth is looked up; authorization is never blindly repeated."],
  ["Events", "Business state and outbox intent commit in one local transaction."],
  ["Consumers", "At-least-once delivery is made logically idempotent by persistent receipts."],
];

export default function OverviewPage() {
  return <div className="space-y-7">
    <PageHeader title="CommerceCore" description="A correctness-first commerce backend for studying concurrency, idempotency, messaging, and ambiguous remote failure." />
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">{guarantees.map(([title, detail]) => <Card key={title}><CardHeader><div className="flex items-center gap-2"><CheckCircle2 className="size-4 text-emerald-400" /><CardTitle>{title}</CardTitle></div></CardHeader><CardContent><p className="text-xs leading-5 text-muted-foreground">{detail}</p></CardContent></Card>)}</div>
    <section><SectionHeader title="Architecture" meta="One VM · private service network" /><Card><CardContent className="grid gap-5 pt-4 md:grid-cols-2"><div><div className="mb-2 flex items-center gap-2 text-sm font-medium"><GitBranch className="size-4 text-blue-400" />Committed event path</div><pre className="overflow-x-auto text-xs leading-6 text-muted-foreground">Checkout{"\n"}  ↓{"\n"}PostgreSQL transaction{"\n"}  ↓{"\n"}Transactional outbox → Kafka{"\n"}  ↓{"\n"}Idempotent consumer receipt</pre></div><div><div className="mb-2 flex items-center gap-2 text-sm font-medium"><ShieldCheck className="size-4 text-amber-300" />Ambiguous payment path</div><pre className="overflow-x-auto text-xs leading-6 text-muted-foreground">Provider timeout{"\n"}  ↓{"\n"}UNKNOWN{"\n"}  ↓{"\n"}Read-only provider lookup{"\n"}  ↓{"\n"}Reconciliation</pre></div></CardContent></Card></section>
    <section><SectionHeader title="Try the system" /><div className="flex flex-wrap gap-2"><Link href="/store" className="inline-flex h-9 items-center gap-2 rounded border border-blue-500 bg-blue-500 px-3 text-xs font-medium text-slate-950 hover:bg-blue-400"><ShoppingBasket className="size-3.5" />Try Store Simulator</Link><Link href="/orders" className="inline-flex h-9 items-center gap-2 rounded border border-border bg-card px-3 text-xs font-medium hover:bg-muted">Inspect an Order <ArrowRight className="size-3.5" /></Link><Link href="/events" className="inline-flex h-9 items-center gap-2 rounded border border-border bg-card px-3 text-xs font-medium hover:bg-muted">Understand Event Evidence <ArrowRight className="size-3.5" /></Link></div></section>
  </div>;
}
