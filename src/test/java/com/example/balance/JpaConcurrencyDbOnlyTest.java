package com.example.balance;

import org.springframework.test.context.TestPropertySource;

/**
 * Same scenarios with the Redis lock disabled: proves correctness comes from PostgreSQL (row locks + unique
 * constraint), not from Redis.
 */
@TestPropertySource(properties = "balance.lock.enabled=false")
class JpaConcurrencyDbOnlyTest extends AbstractJpaConcurrencyTest {
}
