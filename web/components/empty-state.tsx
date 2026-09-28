import { CircleDashed } from "lucide-react";

import { Card, CardTitle } from "@/components/ui/card";

type EmptyStateProps = {
  title: string;
  description: string;
};

export function EmptyState({ title, description }: EmptyStateProps) {
  return (
    <Card className="flex items-start gap-3 p-4">
      <div className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded border border-border bg-muted">
        <CircleDashed className="size-3.5 text-muted-foreground" aria-hidden="true" />
      </div>
      <div>
        <CardTitle>{title}</CardTitle>
        <p className="mt-1 max-w-2xl text-[13px] leading-5 text-muted-foreground">{description}</p>
      </div>
    </Card>
  );
}
