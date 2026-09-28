"use client";

import { Search } from "lucide-react";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

export function OrderLookup() {
  const router = useRouter();
  const [orderId, setOrderId] = useState("");

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const id = orderId.trim();
    if (id) router.push(`/orders/${encodeURIComponent(id)}`);
  }

  return (
    <Card className="max-w-3xl">
      <CardHeader>
        <CardTitle>Inspect an existing order</CardTitle>
      </CardHeader>
      <CardContent>
        <form className="space-y-3" onSubmit={submit}>
          <label htmlFor="order-id" className="text-xs font-medium">Order ID</label>
          <div className="flex flex-col gap-3 sm:flex-row">
            <Input
              id="order-id"
              className="font-mono"
              value={orderId}
              onChange={(event) => setOrderId(event.target.value)}
              placeholder="UUID"
              autoComplete="off"
            />
            <Button type="submit" disabled={!orderId.trim()}><Search className="size-4" />Inspect order</Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}
