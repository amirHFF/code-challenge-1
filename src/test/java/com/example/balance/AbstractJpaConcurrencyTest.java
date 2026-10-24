package com.example.balance;

import com.example.balance.BalanceException.InsufficientFundsException;
import com.example.balance.service.JpaBalanceService;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Concurrency scenarios against real PostgreSQL (+ Redis). Subclasses switch the Redis lock on or off. */
@Timeout(180)
abstract class AbstractJpaConcurrencyTest extends AbstractIntegrationTest {

    @Autowired JpaBalanceService svc;

    private static void runConcurrently(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(32, tasks.size()));
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable t : tasks) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    t.run();
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private String account(String prefix, long balance) {
        String id = prefix + "-" + UUID.randomUUID();
        svc.createAccount(id, balance);
        return id;
    }

    @RepeatedTest(20)
    void twoConcurrentDebitsOf700OnlyOneSucceeds() throws Exception {
        String a = account("A", 1_000);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String tx = "TX-" + UUID.randomUUID();
            tasks.add(() -> {
                try {
                    svc.debit(a, 700, tx);
                    ok.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficient.incrementAndGet();
                }
            });
        }
        runConcurrently(tasks);

        assertThat(ok.get()).isEqualTo(1);
        assertThat(insufficient.get()).isEqualTo(1);
        assertThat(svc.getBalance(a)).isEqualTo(300);
    }

    @Test
    void thousandConcurrentMixedOperationsKeepBalanceCorrect() throws Exception {
        String a = account("A", 100_000);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String c = "C-" + UUID.randomUUID(), d = "D-" + UUID.randomUUID();
            tasks.add(() -> svc.credit(a, 20, c));
            tasks.add(() -> svc.debit(a, 10, d));
        }
        runConcurrently(tasks);
        assertThat(svc.getBalance(a)).isEqualTo(105_000);
    }

    @Test
    void concurrentDuplicatesOfSameTransactionAreAppliedOnce() throws Exception {
        String a = account("A", 1_000);
        String b = account("B", 0);
        String creditTx = "TX-" + UUID.randomUUID(), transferTx = "TX-" + UUID.randomUUID();

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> svc.credit(a, 500, creditTx));
            tasks.add(() -> svc.transfer(a, b, 300, transferTx));
        }
        runConcurrently(tasks);

        assertThat(svc.getBalance(a)).isEqualTo(1_000 + 500 - 300);
        assertThat(svc.getBalance(b)).isEqualTo(300);
    }

    @Test
    void oppositeTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        String a = account("A", 1_000_000);
        String b = account("B", 1_000_000);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String t1 = "AB-" + UUID.randomUUID(), t2 = "BA-" + UUID.randomUUID();
            tasks.add(() -> svc.transfer(a, b, 7, t1));
            tasks.add(() -> svc.transfer(b, a, 7, t2));
        }
        runConcurrently(tasks);

        assertThat(svc.getBalance(a) + svc.getBalance(b)).isEqualTo(2_000_000);
        assertThat(svc.getBalance(a)).isEqualTo(1_000_000);
    }

    @Test
    void randomTransfersAcrossManyAccountsConserveTotalAndNeverGoNegative() throws Exception {
        int n = 8;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(account("ACC", 1_000));
        }
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 1_500; i++) {
            String tx = "TX-" + UUID.randomUUID();
            ThreadLocalRandom r = ThreadLocalRandom.current();
            int from = r.nextInt(n);
            int to = (from + 1 + r.nextInt(n - 1)) % n;
            long amount = 1 + r.nextInt(400);
            tasks.add(() -> {
                try {
                    svc.transfer(ids.get(from), ids.get(to), amount, tx);
                } catch (InsufficientFundsException ignored) {
                    // expected for some transfers
                }
            });
        }
        runConcurrently(tasks);

        long total = 0;
        for (String id : ids) {
            long b = svc.getBalance(id);
            assertThat(b).isGreaterThanOrEqualTo(0);
            total += b;
        }
        assertThat(total).isEqualTo(n * 1_000L);
    }

    @Test
    void manyDistinctTransactionsOnOneAccountAllApply() throws Exception {
        // Every operation has its own transactionId, so all 600 must be applied exactly once.
        String a = account("A", 100_000);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String tx = "TX-" + UUID.randomUUID();
            tasks.add(() -> svc.credit(a, 10, tx));
            tasks.add(() -> svc.debit(a, 5, "D-" + tx));
        }
        runConcurrently(tasks);
        assertThat(svc.getBalance(a)).isEqualTo(100_000 + 300 * 10 - 300 * 5);
    }
}
