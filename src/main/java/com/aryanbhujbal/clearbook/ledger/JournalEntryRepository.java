package com.aryanbhujbal.clearbook.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntryEntity, Long> {

    /** The idempotency lookup: has this trade already posted? */
    Optional<JournalEntryEntity> findByTradeId(String tradeId);

    List<JournalEntryEntity> findByBusinessDateOrderByIdAsc(LocalDate businessDate);

    /**
     * The statement query: every entry touching an account, newest first.
     *
     * <p>Joined on the line rather than filtered on the entry, because an
     * entry belongs to no single account — it is the lines that do.
     */
    @Query("""
            SELECT DISTINCT e FROM JournalEntryEntity e
              JOIN e.lines l
             WHERE l.account.id = :accountId
             ORDER BY e.id DESC
            """)
    List<JournalEntryEntity> findEntriesTouching(@Param("accountId") Long accountId);
}
