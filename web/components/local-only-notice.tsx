import { LockKeyhole } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export function LocalOnlyNotice({ title, description, results }: { title: string; description: string; results: string[] }) {
  return <div className="space-y-5">
    <PageHeader title={title} description="Documented locally; intentionally non-interactive on the public portfolio deployment." />
    <Card><CardHeader><div className="flex items-center gap-2"><LockKeyhole className="size-4 text-amber-300" /><CardTitle>Available in the local development environment</CardTitle></div></CardHeader><CardContent className="space-y-4"><p className="text-sm leading-6 text-muted-foreground">{description}</p><div><p className="text-[10px] font-semibold uppercase tracking-[.14em] text-muted-foreground">Verified development evidence</p><ul className="mt-2 space-y-2 text-sm">{results.map((result) => <li key={result} className="border-l-2 border-blue-800 pl-3">{result}</li>)}</ul></div><p className="text-xs leading-5 text-muted-foreground">The production stack does not activate Spring&apos;s dev profile, and the browser has no proxy route to the Payment Service controls.</p></CardContent></Card>
  </div>;
}
