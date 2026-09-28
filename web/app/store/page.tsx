import { PageHeader } from "@/components/page-header";
import { StoreSimulator } from "@/components/store-simulator";

export default function StorePage() {
  return (
    <div className="space-y-5">
      <PageHeader title="Store Simulator" description="Generate realistic commerce activity against CommerceCore." />
      <StoreSimulator />
    </div>
  );
}
