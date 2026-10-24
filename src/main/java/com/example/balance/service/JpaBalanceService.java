package com.example.balance.service;

import com.example.balance.BalanceException.*;
import com.example.balance.TxOutcome;
import com.example.balance.TxOutcome.Status;
import com.example.balance.jpa.AccountEntity;
import com.example.balance.jpa.AccountRepository;
import com.example.balance.jpa.TransactionEntity;
import com.example.balance.jpa.TransactionRepository;
import com.example.balance.redis.IdempotencyCache;
import com.example.balance.redis.RedisDistributedLock;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class JpaBalanceService implements BalanceService, AccountManagement {

    private static final int MAX_DB_ATTEMPTS = 3;

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;
    private final IdempotencyCache idempotencyCache;
    private final RedisDistributedLock locks;

    public JpaBalanceService(AccountRepository accounts,
                             TransactionRepository transactions,
                             PlatformTransactionManager txManager,
                             IdempotencyCache idempotencyCache,
                             RedisDistributedLock locks) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.idempotencyCache = idempotencyCache;
        this.locks = locks;
    }

    // ---- AccountManagement ----

    @Override
    public void createAccount(String accountId, long initialBalance) {
        requireText(accountId, "accountId");
        if (initialBalance < 0) {
            throw new InvalidRequestException("initialBalance must be >= 0");
        }
        Integer inserted = writeTx.execute(s -> accounts.insertIfAbsent(accountId, initialBalance));
        if (inserted == null || inserted == 0) {
            throw new InvalidRequestException("Account already exists: " + accountId);
        }
    }

    // ---- BalanceService ----

    @Override
    public void credit(String accountId, long amount, String transactionId) {
        validateAmount(amount);
        requireText(transactionId, "transactionId");
        requireText(accountId, "accountId");
        execute(transactionId, "CREDIT|" + accountId + "|" + amount, List.of(accountId), () -> {
            AccountEntity acc = lockAccount(accountId);
            acc.setBalance(safeAdd(acc, amount));
            return List.of(acc);
        });
    }

    @Override
    public void debit(String accountId, long amount, String transactionId) {
        validateAmount(amount);
        requireText(transactionId, "transactionId");
        requireText(accountId, "accountId");
        execute(transactionId, "DEBIT|" + accountId + "|" + amount, List.of(accountId), () -> {
            AccountEntity acc = lockAccount(accountId);
            if (acc.getBalance() < amount) {
                throw new InsufficientFundsException(accountId, acc.getBalance(), amount);
            }
            acc.setBalance(acc.getBalance() - amount);
            return List.of(acc);
        });
    }

    @Override
    public void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
        validateAmount(amount);
        requireText(transactionId, "transactionId");
        requireText(sourceAccountId, "sourceAccountId");
        requireText(destinationAccountId, "destinationAccountId");
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new InvalidRequestException("Source and destination accounts must differ");
        }
        String fingerprint = "TRANSFER|" + sourceAccountId + "|" + destinationAccountId + "|" + amount;

        execute(transactionId, fingerprint, List.of(sourceAccountId, destinationAccountId), () -> {
            // Global lock order (by id) -> no circular wait between opposite transfers.
            boolean srcFirst = sourceAccountId.compareTo(destinationAccountId) < 0;
            AccountEntity first = lockAccount(srcFirst ? sourceAccountId : destinationAccountId);
            AccountEntity second = lockAccount(srcFirst ? destinationAccountId : sourceAccountId);
            AccountEntity src = srcFirst ? first : second;
            AccountEntity dst = srcFirst ? second : first;

            // All checks before any mutation; both writes are flushed in the same DB transaction.
            if (src.getBalance() < amount) {
                throw new InsufficientFundsException(sourceAccountId, src.getBalance(), amount);
            }
            long newDst = safeAdd(dst, amount);
            src.setBalance(src.getBalance() - amount);
            dst.setBalance(newDst);
            return List.of(src, dst);
        });
    }

    @Override
    public long getBalance(String accountId) {
        requireText(accountId, "accountId");
        Long balance = readTx.execute(s ->
                accounts.findBalance(accountId).orElseThrow(() -> new AccountNotFoundException(accountId)));
        return Objects.requireNonNull(balance);
    }

    // ---- idempotent execution ----

    private void execute(String transactionId, String fingerprint, List<String> accountIds,
                         Supplier<List<AccountEntity>> operation) {
        // 1. Redis fast path: only ever contains results that were already committed to the DB.
        Optional<IdempotencyCache.Entry> hit = idempotencyCache.find(transactionId);
        if (hit.isPresent()) {
            IdempotencyCache.Entry e = hit.get();
            if (!e.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(transactionId);
            }
            new TxOutcome(e.status(), e.message(), false).throwIfFailed();
            return;
        }

        // 2. Redis lock (sorted keys). Falls through without a lock on timeout / Redis outage.
        List<String> lockKeys = accountIds.stream().distinct().sorted().toList();
        try (RedisDistributedLock.Handle ignored = locks.acquireAll(lockKeys)) {
            // 3. Authoritative path (includes the balance write-through).
            TxOutcome outcome = runWithRetry(transactionId, fingerprint, operation);
            // 4. After commit.
            idempotencyCache.store(transactionId, fingerprint, outcome);
            outcome.throwIfFailed();
        }
    }

    private TxOutcome runWithRetry(String transactionId, String fingerprint, Supplier<List<AccountEntity>> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return Objects.requireNonNull(
                        writeTx.execute(s -> inTransaction(transactionId, fingerprint, operation)));
            } catch (PessimisticLockingFailureException e) {
                // Deadlock victim / lock timeout: the whole DB tx (including the claim) was rolled back, so a
                // retry is safe.
                if (attempt >= MAX_DB_ATTEMPTS) {
                    throw e;
                }
                pause(10L * attempt);
            }
        }
    }

    private TxOutcome inTransaction(String transactionId, String fingerprint, Supplier<List<AccountEntity>> operation) {
        if (transactions.claim(transactionId, fingerprint) == 0) {
            TransactionEntity existing = transactions.findById(transactionId).orElseThrow(() ->
                    new IllegalStateException("Claim conflicted but no ledger row found for " + transactionId));
            if (!existing.getFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(transactionId);
            }
            return new TxOutcome(Status.valueOf(existing.getStatus()), existing.getFailureMessage(), false);
        }

        List<AccountEntity> changed;
        try {
            changed = operation.get();
        } catch (BusinessRuleException e) {
            // Nothing was mutated (checks precede writes). Record the failure and COMMIT so retries replay it.
            Status status = e instanceof InsufficientFundsException ? Status.INSUFFICIENT_FUNDS : Status.BALANCE_OVERFLOW;
            transactions.markFailed(transactionId, status.name(), e.getMessage());
            return new TxOutcome(status, e.getMessage(), true);
        }

        accounts.flush();

        return new TxOutcome(Status.SUCCESS, null, true);
    }

    // ---- helpers ----

    private AccountEntity lockAccount(String accountId) {
        return accounts.findForUpdate(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private static long safeAdd(AccountEntity acc, long amount) {
        try {
            return Math.addExact(acc.getBalance(), amount);
        } catch (ArithmeticException e) {
            throw new BalanceOverflowException(acc.getId());
        }
    }

    private static void validateAmount(long amount) {
        if (amount <= 0) {
            throw new InvalidRequestException("amount must be > 0");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(name + " must not be blank");
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
