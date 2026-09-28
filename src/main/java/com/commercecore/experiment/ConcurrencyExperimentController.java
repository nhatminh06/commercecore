package com.commercecore.experiment;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dev/experiments")
@Profile("dev")
public class ConcurrencyExperimentController {
    private final ConcurrencyExperimentService experiments;
    public ConcurrencyExperimentController(ConcurrencyExperimentService experiments) { this.experiments = experiments; }
    @PostMapping("/inventory-contention") ResponseEntity<?> inventory(@RequestBody InventoryExperimentRequest r) { return ResponseEntity.ok(experiments.inventory(r)); }
    @PostMapping("/checkout-idempotency") ResponseEntity<?> checkout(@RequestBody WorkerExperimentRequest r) { return ResponseEntity.ok(experiments.checkout(r)); }
    @PostMapping("/provider-idempotency") ResponseEntity<?> provider(@RequestBody WorkerExperimentRequest r) { return ResponseEntity.ok(experiments.provider(r)); }
    @PostMapping("/duplicate-event") ResponseEntity<?> duplicateEvent(@RequestBody DuplicateEventExperimentRequest r) { return ResponseEntity.ok(experiments.duplicateEvent(r)); }
}
