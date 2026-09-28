import { request } from "./api";
import type { EventInspection, EventSummary } from "./event-evidence";

export function getEvents() {
  return request<EventSummary[]>("/dev/events");
}

export function getEvent(eventId: string) {
  return request<EventInspection>(`/dev/events/${encodeURIComponent(eventId)}`);
}
