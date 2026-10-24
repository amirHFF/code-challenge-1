package com.example.balance;

public abstract class BalanceException extends RuntimeException {

    protected BalanceException(String message) {
        super(message);
    }

    public abstract static class BusinessRuleException extends BalanceException {
        protected BusinessRuleException(String message) {
            super(message);
        }
    }

    public static class InsufficientFundsException extends BusinessRuleException {
        public InsufficientFundsException(String accountId, long balance, long amount) {
            super("Insufficient funds on account " + accountId + ": balance=" + balance + ", requested=" + amount);
        }

        private InsufficientFundsException(String message, boolean raw) {
            super(message);
        }

        /** Re-creates a recorded failure from its stored message. */
        public static InsufficientFundsException ofMessage(String message) {
            return new InsufficientFundsException(message, true);
        }
    }

    public static class BalanceOverflowException extends BusinessRuleException {
        public BalanceOverflowException(String accountId) {
            super("Balance overflow on account " + accountId);
        }

        private BalanceOverflowException(String message, boolean raw) {
            super(message);
        }

        public static BalanceOverflowException ofMessage(String message) {
            return new BalanceOverflowException(message, true);
        }
    }

    public static class InvalidRequestException extends BalanceException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }

    public static class AccountNotFoundException extends BalanceException {
        public AccountNotFoundException(String accountId) {
            super("Unknown account: " + accountId);
        }
    }

    public static class IdempotencyConflictException extends BalanceException {
        public IdempotencyConflictException(String transactionId) {
            super("transactionId " + transactionId + " was already used with different parameters");
        }
    }
}
