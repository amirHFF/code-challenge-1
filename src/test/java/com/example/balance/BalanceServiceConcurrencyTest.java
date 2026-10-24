package com.example.balance;

import com.example.balance.BalanceException.InsufficientFundsException;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(60)
class BalanceServiceConcurrencyTest {

    /** Runs all tasks as simultaneously as possible (start gate) and rethrows unexpected errors. */
    private static void runConcurrently(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(64, tasks.size()));
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
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

}
