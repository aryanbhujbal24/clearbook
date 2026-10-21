package com.aryanbhujbal.clearbook.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    Optional<AccountEntity> findByCode(String code);

    List<AccountEntity> findByCodeIn(Collection<String> codes);

    List<AccountEntity> findByOwnerId(String ownerId);

    boolean existsByCode(String code);
}
