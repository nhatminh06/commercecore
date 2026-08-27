package com.commercecore.outbox;

public record PublishResult(int claimed, int published) {
}
