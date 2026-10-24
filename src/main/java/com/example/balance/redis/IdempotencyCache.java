package com.example.balance.redis;

import com.example.balance.BalanceProperties;
import com.example.balance.TxOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);

    public record Entry(String fingerprint, TxOutcome.Status status, String message) {}

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public IdempotencyCache(StringRedisTemplate redis, ObjectMapper mapper, BalanceProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = props.idempotency().ttl();
    }

    public Optional<Entry> find(String transactionId) {
        try {
            String json = redis.opsForValue().get(key(transactionId));
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, Entry.class));
        } catch (Exception e) {
            log.warn("Idempotency cache read failed, falling back to DB: {}", e.toString());
            return Optional.empty();
        }
    }

    public void store(String transactionId, String fingerprint, TxOutcome outcome) {
        try {
            String json = mapper.writeValueAsString(new Entry(fingerprint, outcome.status(), outcome.message()));
            redis.opsForValue().set(key(transactionId), json, ttl);
        } catch (Exception e) {
            log.warn("Idempotency cache write failed (DB remains authoritative): {}", e.toString());
        }
    }

    private static String key(String transactionId) {
        return "idem:" + transactionId;
    }
}
