package com.example.balance.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "fingerprint", nullable = false)
    private String fingerprint;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "failure_message")
    private String failureMessage;

    protected TransactionEntity() {
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getStatus() {
        return status;
    }

    public String getFailureMessage() {
        return failureMessage;
    }
}
