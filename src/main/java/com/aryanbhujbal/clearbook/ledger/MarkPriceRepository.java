package com.aryanbhujbal.clearbook.ledger;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MarkPriceRepository extends JpaRepository<MarkPriceEntity, MarkPriceEntity.Key> {

    /**
     * The most recent mark at or before a date.
     *
     * <p>At or before, rather than exactly on, because a holiday or a missing
     * file should carry the last known price forward rather than leave a
     * position unvaluable. Refusing to value a position is the right answer
     * when there has never been a price, not when today's has not arrived.
     */
    @Query("""
            SELECT m FROM MarkPriceEntity m
             WHERE m.key.symbol = :symbol AND m.key.businessDate <= :asOf
             ORDER BY m.key.businessDate DESC
             LIMIT 1
            """)
    List<MarkPriceEntity> findLatest(@Param("symbol") String symbol,
                                     @Param("asOf") LocalDate asOf);
}
