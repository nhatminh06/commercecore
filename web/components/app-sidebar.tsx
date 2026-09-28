"use client";

import {
  Activity,
  Boxes,
  FlaskConical,
  Gauge,
  PackageSearch,
  ShoppingBasket,
} from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";

import { cn } from "@/lib/utils";

const navigation = [
  {
    label: "General",
    items: [
      { href: "/", label: "Overview", icon: Gauge },
      { href: "/store", label: "Store", icon: ShoppingBasket },
      { href: "/orders", label: "Orders", icon: PackageSearch },
    ],
  },
  {
    label: "System",
    items: [
      { href: "/events", label: "Events", icon: Activity },
      { href: "/failure-lab", label: "Failure Lab", icon: FlaskConical },
      { href: "/experiments", label: "Experiments", icon: Boxes },
    ],
  },
];

const systems = ["CommerceCore", "PostgreSQL", "Kafka", "Payment Service"];

export function AppSidebar() {
  const pathname = usePathname();

  return (
    <aside className="flex w-full shrink-0 flex-col border-b border-border bg-sidebar lg:sticky lg:top-0 lg:h-screen lg:w-[232px] lg:border-r lg:border-b-0">
      <div className="hidden h-[52px] items-center border-b border-border px-4 lg:flex">
        <div className="flex size-7 items-center justify-center rounded border border-border bg-muted font-mono text-[10px] font-bold">
          CC
        </div>
        <div className="ml-3 leading-tight">
          <p className="text-sm font-semibold tracking-tight">CommerceCore</p>
          <p className="mt-0.5 text-[9px] font-medium uppercase tracking-[0.17em] text-muted-foreground">Control Room</p>
        </div>
      </div>

      <nav className="flex gap-1 overflow-x-auto p-2 lg:flex-col lg:gap-5 lg:p-3" aria-label="Primary navigation">
        {navigation.map((group) => (
          <div key={group.label} className="contents lg:block">
            <p className="mb-1.5 hidden px-2 text-[9px] font-semibold uppercase tracking-[0.16em] text-muted-foreground/70 lg:block">
              {group.label}
            </p>
            <div className="contents lg:block lg:space-y-0.5">
              {group.items.map(({ href, label, icon: Icon }) => {
                const active = href === "/" ? pathname === href : pathname.startsWith(href);
                return (
                  <Link
                    key={href}
                    href={href}
                    aria-current={active ? "page" : undefined}
                    className={cn(
                      "flex h-9 shrink-0 items-center gap-2.5 rounded px-2.5 text-[13px] text-muted-foreground transition-colors hover:bg-sidebar-accent hover:text-foreground",
                      active && "bg-sidebar-accent text-foreground shadow-[inset_2px_0_0_0_var(--accent)]",
                    )}
                  >
                    <Icon className="size-3.5" aria-hidden="true" />
                    {label}
                  </Link>
                );
              })}
            </div>
          </div>
        ))}
      </nav>

      <div className="mt-auto hidden border-t border-border p-4 lg:block">
        <p className="mb-3 text-[9px] font-semibold uppercase tracking-[0.16em] text-muted-foreground/70">
          System
        </p>
        <div className="space-y-2">
          {systems.map((system) => (
            <div key={system} className="flex items-center justify-between gap-2 text-xs">
              <span className="text-foreground/80">{system}</span>
              <span className="font-mono text-muted-foreground" aria-label="Status unknown">—</span>
            </div>
          ))}
        </div>
      </div>
    </aside>
  );
}
