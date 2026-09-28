import { ConcurrencyLab } from "@/components/concurrency-lab";
import { LocalOnlyNotice } from "@/components/local-only-notice";

export default function ExperimentsPage() {
  if (process.env.NEXT_PUBLIC_DEMO_MODE === "public") return <LocalOnlyNotice title="Concurrency Lab" description="These backend-coordinated experiments create real domain state and are intentionally disabled on the public deployment." results={[
    "100 buyers / 10 units → 10 succeeded, 90 rejected",
    "20 concurrent checkouts → one logical order",
    "20 provider requests → one provider payment",
    "Duplicate processing → one consumer receipt",
  ]} />;
  return <ConcurrencyLab />;
}
