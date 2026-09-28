package com.commercecore.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Development-only, read-only access to persisted event-delivery evidence. */
@RestController
@Profile("dev")
@RequestMapping("/api/dev/events")
public class DevEventInspectionController {

    private final EventInspectionService inspectionService;

    public DevEventInspectionController(EventInspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @GetMapping
    public List<EventSummaryResponse> list() {
        return inspectionService.newestEvents();
    }

    @GetMapping("/{eventId}")
    public EventInspectionResponse inspect(@PathVariable UUID eventId) {
        return inspectionService.inspect(eventId);
    }
}
