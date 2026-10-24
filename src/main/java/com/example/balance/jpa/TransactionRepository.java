package com.example.balance.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {

    @Modifying
    @Query(value = "INSERT INTO transactions (transaction_id, fingerprint, status) VALUES (:id, :fp, 'SUCCESS') "
            + "ON CONFLICT (transaction_id) DO NOTHING", nativeQuery = true)
    int claim(@Param("id") String id, @Param("fp") String fingerprint);

    @Modifying
    @Query(value = "UPDATE transactions SET status = :status, failure_message = :msg "
            + "WHERE transaction_id = :id", nativeQuery = true)
    int markFailed(@Param("id") String id, @Param("status") String status, @Param("msg") String message);
}
