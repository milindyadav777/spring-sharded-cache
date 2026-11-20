package dev.practice.catalog;

import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
final class CacheRelay {
    private static final Logger log = LoggerFactory.getLogger(CacheRelay.class);
    private final ShardMap map;
    private final RegionalCaches caches;
    private final MeterRegistry metrics;
    record Job(Catalog.Item item, Region region, UUID token) {}

    CacheRelay(ShardMap map, RegionalCaches caches, MeterRegistry metrics) {
        this.map = map;
        this.caches = caches;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${RELAY_DELAY_MS:250}")
    void drain() {
        for (var shard : map.shards) {
            try {
                for (int i = 0; i < 16; i++) {
                    var job = claim(shard.primary());
                    if (job.isEmpty()) break;
                    deliver(shard.primary(), job.get());
                }
            } catch (DataAccessException unavailable) {
                log.warn("Relay cannot reach {}", shard.primaryPool().getPoolName(), unavailable);
            }
        }
    }

    // A single autocommitted claim releases its SQL locks before the Redis round trip.
    private Optional<Job> claim(JdbcTemplate db) {
        UUID token = UUID.randomUUID();
        return db.query("""
            WITH candidate AS (
              SELECT tenant, sku, region FROM cache_job WHERE due_at <= now()
              ORDER BY due_at, tenant, sku, region LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE cache_job AS j SET token=?, due_at=now() + interval '30 seconds'
            FROM candidate AS c WHERE (j.tenant,j.sku,j.region)=(c.tenant,c.sku,c.region)
            RETURNING j.tenant, j.sku, j.region, j.name, j.version
            """, (rs, n) -> new Job(Catalog.Item.read(rs,n), Region.valueOf(rs.getString("region")), token),
            token).stream().findFirst();
    }

    private void deliver(JdbcTemplate db, Job job) {
        var item = job.item();
        try {
            caches.apply(job.region(), item);
        } catch (DataAccessException unavailable) {
            db.update("""
                UPDATE cache_job SET token=NULL, attempts=least(attempts+1,10),
                  due_at=now() + make_interval(secs => least(60, 1 << least(attempts, 6)))
                WHERE tenant=? AND sku=? AND region=? AND token=?
                """, item.tenant(), item.sku(), job.region().name(), job.token());
            metrics.counter("cache.relay", "result", "retry", "region", job.region().name()).increment();
            return;
        }
        db.update("DELETE FROM cache_job WHERE tenant=? AND sku=? AND region=? AND token=?",
                  item.tenant(), item.sku(), job.region().name(), job.token());
        metrics.counter("cache.relay", "result", "delivered", "region", job.region().name()).increment();
    }
}
