package com.ticketing.service;

import com.ticketing.config.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Programmatic cache eviction for code paths where the affected {@code eventId}
 * only becomes known after loading entities (booking hold/confirm/cancel and the
 * expiry job). Declarative {@code @CacheEvict} with SpEL is used where the id is a
 * direct method argument (see {@code EventService}); this covers the rest.
 *
 * <p>Any seat state change alters an event's availability, so we evict that event's
 * detail and seat-map entries and clear the (small, short-TTL) search cache.
 *
 * <h3>Why evict AFTER commit</h3>
 * When called inside a transaction, eviction is deferred to {@code afterCommit}. If
 * we evicted mid-transaction, a concurrent reader could re-populate the cache with
 * the still-uncommitted old state before our commit lands, leaving stale data with
 * a fresh TTL. Deferring to after-commit closes that window. (If there's no active
 * transaction, we evict immediately.) Short TTLs in {@link CacheConfig} remain the
 * backstop.
 */
@Component
public class CacheInvalidator {

    private final CacheManager cacheManager;

    public CacheInvalidator(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** Evict all cached reads whose contents depend on this event's availability. */
    public void evictEventAvailability(Long eventId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doEvict(eventId);
                }
            });
        } else {
            doEvict(eventId);
        }
    }

    private void doEvict(Long eventId) {
        evict(CacheConfig.EVENTS, eventId);
        evict(CacheConfig.EVENT_SEATS, eventId);
        clear(CacheConfig.EVENT_SEARCH);
    }

    private void evict(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evictIfPresent(key);
        }
    }

    private void clear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }
}
