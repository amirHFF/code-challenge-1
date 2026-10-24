package com.example.balance;

import com.example.balance.BalanceException.BalanceOverflowException;
import com.example.balance.BalanceException.InsufficientFundsException;

/**
 * Final, deterministic result of a transaction. Persisted (DB) and cached (Redis) so that retries of the
 * same transactionId replay exactly this result.
 *
 * @param executed true if this call performed the work, false if it replayed an earlier result
 */
public record TxOutcome(Status status, String message, boolean executed) {

    public enum Status { SUCCESS, INSUFFICIENT_FUNDS, BALANCE_OVERFLOW }

    public void throwIfFailed() {
        switch (status) {
            case SUCCESS -> { }
            case INSUFFICIENT_FUNDS -> throw InsufficientFundsException.ofMessage(message);
            case BALANCE_OVERFLOW -> throw BalanceOverflowException.ofMessage(message);
        }
    }
}
