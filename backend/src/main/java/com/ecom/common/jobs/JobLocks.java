package com.ecom.common.jobs;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Cluster-wide "only one instance runs this job" using a Postgres transaction-scoped advisory lock
 * (released automatically at commit/rollback). Call inside a @Transactional method.
 */
@Component
public class JobLocks {

    public static final long REFRESH_TOKEN_CLEANUP = 4_201L;
    public static final long IDEMPOTENCY_CLEANUP = 4_202L;

    @PersistenceContext
    private EntityManager entityManager;

    public boolean tryLock(long key) {
        Object result = entityManager.createNativeQuery("select pg_try_advisory_xact_lock(?1)")
                .setParameter(1, key)
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }
}
