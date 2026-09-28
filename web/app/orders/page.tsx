import { OrderLookup } from "@/components/order-lookup";
import { PageHeader } from "@/components/page-header";

export default function OrdersPage() {
  return (
    <div className="space-y-5">
      <PageHeader title="Order Inspector" description="Inspect authoritative order, reservation, and payment state." />
      <OrderLookup />
    </div>
  );
}
