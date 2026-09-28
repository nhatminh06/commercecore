"use client";

import { usePathname } from "next/navigation";

const labels: Record<string, string> = {
  "/": "Overview",
  "/store": "Store Simulator",
  "/orders": "Order Inspector",
  "/events": "Event Explorer",
  "/failure-lab": "Failure Lab",
  "/experiments": "Concurrency Lab",
};

export function AppHeader() {
  const pathname = usePathname();
  const root = pathname === "/" ? "/" : `/${pathname.split("/")[1]}`;
  const page = labels[root] ?? "Control Room";

  return (
    <header className="sticky top-0 z-20 flex h-[52px] items-center justify-between border-b border-border bg-background/95 px-4 backdrop-blur sm:px-6 lg:px-8">
      <div className="flex min-w-0 items-center gap-2 text-xs">
        <span className="font-semibold text-foreground lg:text-muted-foreground">CommerceCore</span>
        <span className="text-muted-foreground">/</span>
        <span className="truncate text-muted-foreground lg:text-foreground">{page}</span>
      </div>
      <span className="rounded border border-border bg-muted/60 px-2 py-1 font-mono text-[9px] font-medium tracking-[0.12em] text-muted-foreground">
        {process.env.NODE_ENV === "development" ? "DEVELOPMENT" : "PRODUCTION"}
      </span>
    </header>
  );
}
