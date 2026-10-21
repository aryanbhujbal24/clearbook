package com.aryanbhujbal.clearbook.ledger;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BalanceCheckpointRepository
        extends JpaRepository<BalanceCheckpointEntity, BalanceCheckpointEntity.Key> {

    Optional<BalanceCheckpointEntity> findByKey(BalanceCheckpointEntity.Key key);
}
