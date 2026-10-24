package com.example.balance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "balance")
public record BalanceProperties(
        @DefaultValue Lock lock,
        @DefaultValue Idempotency idempotency,
        @DefaultValue BalanceCache balanceCache) {

    /** Redis lock: reduces DB lock queuing. Correctness never depends on it. */
    public record Lock(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10s") Duration lease,
            @DefaultValue("3s") Duration acquireTimeout) {}

    /** TTL of completed-transaction results cached in Redis. The DB keeps them permanently. */
    public record Idempotency(@DefaultValue("24h") Duration ttl) {}

    public record BalanceCache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") Duration ttl) {}
}
