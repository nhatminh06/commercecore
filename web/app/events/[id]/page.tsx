import { EventInspector } from "@/components/event-inspector";
import { LocalOnlyNotice } from "@/components/local-only-notice";

export default async function EventPage({ params }: { params: Promise<{ id: string }> }) {
  if (process.env.NEXT_PUBLIC_DEMO_MODE === "public") return <LocalOnlyNotice title="Event Inspector" description="Persisted event payload inspection is not exposed by the production API profile." results={["Outbox intent is atomic with its business fact", "Kafka delivery remains at least once", "Consumer effects are receipt-idempotent"]} />;
  const { id } = await params;
  return <EventInspector eventId={id} />;
}
