package com.commercecore.outbox;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only control surface for {@link RecordingEventSink} specifically — only exists
 * when that sink is the active one (i.e. outside the {@code kafka} profile; see
 * {@code docs/kafka.md}). Not a real production API.
 */
@RestController
@RequestMapping("/api/dev/outbox/sink")
@Profile("!kafka")
public class DevRecordingSinkController {

    private final RecordingEventSink sink;

    public DevRecordingSinkController(RecordingEventSink sink) {
        this.sink = sink;
    }

    public record NextOutcomeRequest(RecordingEventSink.NextOutcome outcome) {
    }

    @PostMapping("/next-outcome")
    public void setSinkNextOutcome(@RequestBody NextOutcomeRequest request) {
        sink.nextOutcome(request.outcome());
    }
}
