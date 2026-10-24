package com.example.balance.jpa;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> findForUpdate(@Param("id") String id);

    @Query("select a.balance from AccountEntity a where a.id = :id")
    Optional<Long> findBalance(@Param("id") String id);

    @Modifying
    @Query(value = "INSERT INTO accounts (account_id, balance, version) VALUES (:id, :balance, 0) "
            + "ON CONFLICT (account_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("balance") long balance);
}
