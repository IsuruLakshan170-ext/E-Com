package com.ecom.security;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ecom.user.TokenState;
import com.ecom.user.UserRepository;

/**
 * M7: tiny read-through cache of (enabled, tokenVersion) per user, so every JWT request can be checked
 * against the live account without a DB hit each time. Entries live 15 s; local changes evict at commit.
 * With several instances, other nodes see a change within the TTL.
 */
@Component
public class UserTokenStateCache {

    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final int MAX_ENTRIES = 50_000;

    private record Entry(Optional<TokenState> state, long loadedAt) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final UserRepository users;

    public UserTokenStateCache(UserRepository users) {
        this.users = users;
    }

    public Optional<TokenState> get(UUID userId) {
        long now = System.nanoTime();
        Entry entry = entries.get(userId);
        if (entry == null || now - entry.loadedAt() > TTL_NANOS) {
            if (entries.size() > MAX_ENTRIES) {
                entries.clear();
            }
            entry = new Entry(users.findTokenState(userId), now);
            entries.put(userId, entry);
        }
        return entry.state();
    }

    /** Evict once the surrounding transaction commits (immediately if there is none). */
    public void evictAfterCommit(UUID userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    entries.remove(userId);
                }
            });
        } else {
            entries.remove(userId);
        }
    }
}
