import { FailureLab } from "@/components/failure-lab";
import { LocalOnlyNotice } from "@/components/local-only-notice";

export default function FailureLabPage() {
  if (process.env.NEXT_PUBLIC_DEMO_MODE === "public") return <LocalOnlyNotice title="Failure Lab" description="This lab deliberately injects provider outcomes to demonstrate ambiguous payment handling and reconciliation. Mutation controls are not exposed to internet visitors." results={[
    "Timeout after provider commit → local UNKNOWN",
    "Read-only provider lookup → authoritative AUTHORIZED",
    "Reconciliation resolves ambiguity without re-authorization",
  ]} />;
  return <FailureLab />;
}
