package com.aryanbhujbal.clearbook.ledger;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JournalLineRepository extends JpaRepository<JournalLineEntity, Long> {

    /**
     * The whole balance, folded from the journal.
     *
     * <p>{@code COALESCE} matters: an account with no lines has a balance of
     * zero, not null, and the difference between those two is a
     * {@code NullPointerException} on a client's first statement.
     */
    @Query("""
            SELECT COALESCE(SUM(l.amount), 0) FROM JournalLineEntity l
             WHERE l.account.id = :accountId AND l.asset = :asset
            """)
    long sumFor(@Param("accountId") Long accountId, @Param("asset") String asset);

    /** The tail after a checkpoint. */
    @Query("""
            SELECT COALESCE(SUM(l.amount), 0) FROM JournalLineEntity l
             WHERE l.account.id = :accountId AND l.asset = :asset AND l.id > :afterLineId
            """)
    long sumAfter(@Param("accountId") Long accountId,
                  @Param("asset") String asset,
                  @Param("afterLineId") long afterLineId);

    /** Every asset this account holds a non-zero amount of. */
    @Query("""
            SELECT l.asset, SUM(l.amount) FROM JournalLineEntity l
             WHERE l.account.id = :accountId
             GROUP BY l.asset
            HAVING SUM(l.amount) <> 0
            """)
    List<Object[]> balancesByAsset(@Param("accountId") Long accountId);

    /** The highest line id this account has, for taking a checkpoint. */
    @Query("""
            SELECT COALESCE(MAX(l.id), 0) FROM JournalLineEntity l
             WHERE l.account.id = :accountId AND l.asset = :asset
            """)
    long highestLineId(@Param("accountId") Long accountId, @Param("asset") String asset);

    /**
     * The global conservation check: no asset may net to anything but zero
     * across the entire ledger. Used by the test suite after every scenario,
     * and exposed as a health indicator.
     */
    @Query("""
            SELECT l.asset, SUM(l.amount) FROM JournalLineEntity l
             GROUP BY l.asset
            HAVING SUM(l.amount) <> 0
            """)
    List<Object[]> assetsThatDoNotNetToZero();
}
