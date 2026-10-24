package com.example.balance;

import com.example.balance.BalanceException.*;
import com.example.balance.jpa.AccountRepository;
import com.example.balance.service.JpaBalanceService;
import com.example.balance.jpa.TransactionRepository;
import com.example.balance.redis.IdempotencyCache;
import com.example.balance.redis.RedisDistributedLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Functional + idempotency tests against real PostgreSQL and Redis. Unique ids per test: no cleanup needed. */
class JpaBalanceServiceTest extends AbstractIntegrationTest {

    @Autowired JpaBalanceService svc;
    @Autowired StringRedisTemplate redis;
    @Autowired AccountRepository accountRepo;
    @Autowired TransactionRepository txRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired IdempotencyCache idempotencyCache;
    @Autowired RedisDistributedLock lock;
    @Autowired ObjectMapper mapper;

    String a, b;

    @BeforeEach
    void setUp() {
        a = "A-" + UUID.randomUUID();
        b = "B-" + UUID.randomUUID();
        svc.createAccount(a, 1_000);
        svc.createAccount(b, 500);
    }

    static String tx() {
        return "TX-" + UUID.randomUUID();
    }

    // ---- basic behaviour ----

    @Test
    void creditDebitTransfer() {
        svc.credit(a, 500, tx());
        assertThat(svc.getBalance(a)).isEqualTo(1_500);
        svc.debit(a, 700, tx());
        assertThat(svc.getBalance(a)).isEqualTo(800);
        svc.transfer(a, b, 300, tx());
        assertThat(svc.getBalance(a)).isEqualTo(500);
        assertThat(svc.getBalance(b)).isEqualTo(800);
    }

    @Test
    void insufficientFundsLeavesBalancesUntouched() {
        assertThatThrownBy(() -> svc.debit(a, 1_200, tx())).isInstanceOf(InsufficientFundsException.class);
        assertThatThrownBy(() -> svc.transfer(a, b, 5_000, tx())).isInstanceOf(InsufficientFundsException.class);
        assertThat(svc.getBalance(a)).isEqualTo(1_000);
        assertThat(svc.getBalance(b)).isEqualTo(500);
    }

    @Test
    void transferToOverflowingDestinationChangesNothing() {
        String max = "MAX-" + UUID.randomUUID();
        svc.createAccount(max, Long.MAX_VALUE);
        assertThatThrownBy(() -> svc.transfer(a, max, 1, tx())).isInstanceOf(BalanceOverflowException.class);
        assertThat(svc.getBalance(a)).isEqualTo(1_000);
        assertThat(svc.getBalance(max)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void persistsAcrossServiceInstances() {
        // A second service instance (as a second application node would be) sees the same durable state.
        JpaBalanceService other = new JpaBalanceService(accountRepo, txRepo, txManager, idempotencyCache, lock);
        svc.credit(a, 100, tx());
        assertThat(other.getBalance(a)).isEqualTo(1_100);
        other.debit(a, 100, tx());
        assertThat(svc.getBalance(a)).isEqualTo(1_000);
    }

    // ---- validation ----

    @Test
    void validation() {
        assertThatThrownBy(() -> svc.credit(a, 0, tx())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> svc.debit(a, -5, tx())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> svc.transfer(a, b, 0, tx())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> svc.transfer(a, a, 100, tx())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> svc.credit("nope-" + UUID.randomUUID(), 10, tx())).isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> svc.transfer(a, "nope-" + UUID.randomUUID(), 10, tx())).isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> svc.getBalance("nope-" + UUID.randomUUID())).isInstanceOf(AccountNotFoundException.class);
        assertThat(svc.getBalance(a)).isEqualTo(1_000);
    }

    @Test
    void duplicateAccountCreationIsRejected() {
        assertThatThrownBy(() -> svc.createAccount(a, 5)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectedRequestDoesNotBurnTransactionId() {
        String id = "LATE-" + UUID.randomUUID();
        String t = tx();
        assertThatThrownBy(() -> svc.credit(id, 10, t)).isInstanceOf(AccountNotFoundException.class);
        svc.createAccount(id, 0);
        svc.credit(id, 10, t); // same transactionId now succeeds: the failed attempt rolled back its claim
        assertThat(svc.getBalance(id)).isEqualTo(10);
    }

    // ---- idempotency ----

    @Test
    void creditDebitTransferAreIdempotent() {
        String t1 = tx(), t2 = tx(), t3 = tx();
        for (int i = 0; i < 3; i++) {
            svc.credit(a, 100, t1);
            svc.debit(a, 50, t2);
            svc.transfer(a, b, 300, t3);
        }
        assertThat(svc.getBalance(a)).isEqualTo(1_000 + 100 - 50 - 300);
        assertThat(svc.getBalance(b)).isEqualTo(800);
    }

    @Test
    void idempotencyHoldsWhenRedisIsFlushed() {
        // Redis is only a cache: the DB unique constraint must still stop a double apply.
        String t = tx();
        svc.credit(a, 100, t);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        svc.credit(a, 100, t);
        assertThat(svc.getBalance(a)).isEqualTo(1_100);
    }

    @Test
    void failedDebitReplaysSameFailureEvenIfFundsArriveLater() {
        String t = tx();
        assertThatThrownBy(() -> svc.debit(a, 1_200, t)).isInstanceOf(InsufficientFundsException.class);
        svc.credit(a, 1_000, tx());
        assertThatThrownBy(() -> svc.debit(a, 1_200, t)).isInstanceOf(InsufficientFundsException.class);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        assertThatThrownBy(() -> svc.debit(a, 1_200, t)).isInstanceOf(InsufficientFundsException.class); // from DB
        assertThat(svc.getBalance(a)).isEqualTo(2_000);
    }

    @Test
    void reusingTransactionIdWithDifferentParametersIsRejected() {
        String t = tx();
        svc.credit(a, 100, t);
        assertThatThrownBy(() -> svc.credit(a, 999, t)).isInstanceOf(IdempotencyConflictException.class); // Redis path
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        assertThatThrownBy(() -> svc.debit(a, 100, t)).isInstanceOf(IdempotencyConflictException.class); // DB path
        assertThat(svc.getBalance(a)).isEqualTo(1_100);
    }


    @Test
    void readsAreFreshAfterEveryWrite() {
        assertThat(svc.getBalance(a)).isEqualTo(1_000); // populates the cache from the DB
        svc.credit(a, 1, tx());
        assertThat(svc.getBalance(a)).isEqualTo(1_001);
        svc.transfer(a, b, 1, tx());
        assertThat(svc.getBalance(a)).isEqualTo(1_000);
        assertThat(svc.getBalance(b)).isEqualTo(501);
    }

}
