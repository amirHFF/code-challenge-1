package com.example.balance.redis;

import com.example.balance.BalanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RedisDistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);

    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private record Held(String key, String token) {}

    public static final class Handle implements AutoCloseable {
        static final Handle NONE = new Handle(null, List.of());

        private final RedisDistributedLock owner;
        private final List<Held> held;

        private Handle(RedisDistributedLock owner, List<Held> held) {
            this.owner = owner;
            this.held = held;
        }

        @Override
        public void close() {
            if (owner != null) {
                owner.release(held);
            }
        }
    }

    private final StringRedisTemplate redis;
    private final BalanceProperties.Lock cfg;

    public RedisDistributedLock(StringRedisTemplate redis, BalanceProperties props) {
        this.redis = redis;
        this.cfg = props.lock();
    }

    /** @param sortedAccountIds must be sorted: a consistent order prevents lock-order deadlocks. */
    public Handle acquireAll(List<String> sortedAccountIds) {
        if (!cfg.enabled() || sortedAccountIds.isEmpty()) {
            return Handle.NONE;
        }
        List<Held> held = new ArrayList<>();
        long deadline = System.nanoTime() + cfg.acquireTimeout().toNanos();
        try {
            for (String id : sortedAccountIds) {
                Held h = new Held("lock:account:" + id, UUID.randomUUID().toString());
                if (!tryLock(h, deadline)) {
                    log.warn("Redis lock timeout for account {}, proceeding with DB row locks only", id);
                    release(held);
                    return Handle.NONE;
                }
                held.add(h);
            }
            return new Handle(this, held);
        } catch (RuntimeException e) {
            log.warn("Redis lock unavailable, proceeding with DB row locks only: {}", e.toString());
            release(held);
            return Handle.NONE;
        }
    }

    private boolean tryLock(Held h, long deadlineNanos) {
        while (true) {
            Boolean ok = redis.opsForValue().setIfAbsent(h.key(), h.token(), cfg.lease());
            if (Boolean.TRUE.equals(ok)) {
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(2, 12));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void release(List<Held> held) {
        for (int i = held.size() - 1; i >= 0; i--) {
            Held h = held.get(i);
            try {
                redis.execute(UNLOCK, List.of(h.key()), h.token());
            } catch (RuntimeException e) {
                log.warn("Could not release Redis lock {} (it will expire): {}", h.key(), e.toString());
            }
        }
    }
}
