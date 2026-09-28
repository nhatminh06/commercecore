import { EventExplorer } from "@/components/event-explorer";
import { LocalOnlyNotice } from "@/components/local-only-notice";

export default async function EventsPage({ searchParams }: { searchParams: Promise<{ aggregateId?: string }> }) {
  if (process.env.NEXT_PUBLIC_DEMO_MODE === "public") return <LocalOnlyNotice title="Event Explorer" description="Raw outbox payload and consumer-receipt inspection remains local-only because its current API is part of the development inspection surface." results={["Transactional outbox commits with business state", "Published does not mean consumed", "Persistent receipts deduplicate logical processing"]} />;
  const { aggregateId = "" } = await searchParams;
  return <EventExplorer initialSearch={aggregateId} />;
}
