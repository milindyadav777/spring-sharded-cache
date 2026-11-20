package dev.practice.catalog;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
final class ShardMap {
    record Shard(HikariDataSource primaryPool, HikariDataSource replicaPool,
                 JdbcTemplate primary, JdbcTemplate replica, TransactionTemplate transaction) {}
    final List<Shard> shards;

    ShardMap(Environment env) {
        shards = List.of(open(env, 0), open(env, 1));
    }

    static int index(long tenant) {
        if (tenant < 1) throw new IllegalArgumentException("tenant must be positive");
        return (int) (tenant % 2);
    }

    Shard forTenant(long tenant) {
        return shards.get(index(tenant));
    }

    private Shard open(Environment env, int index) {
        var primary = pool(env, index, false);
        var replica = pool(env, index, true);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(primary));
        transaction.setTimeout(5);
        return new Shard(primary, replica, new JdbcTemplate(primary), new JdbcTemplate(replica), transaction);
    }

    private HikariDataSource pool(Environment env, int index, boolean readOnly) {
        String role = readOnly ? "REPLICA" : "PRIMARY";
        var cfg = new HikariConfig();
        cfg.setPoolName("shard-" + index + "-" + role);
        cfg.setJdbcUrl(env.getRequiredProperty("SHARD" + index + "_" + role + "_URL"));
        cfg.setUsername(env.getRequiredProperty("DB_USER"));
        cfg.setPassword(env.getRequiredProperty("DB_PASSWORD"));
        cfg.addDataSourceProperty("targetServerType", readOnly ? "secondary" : "primary");
        cfg.addDataSourceProperty("connectTimeout", "2");
        cfg.addDataSourceProperty("socketTimeout", "5");
        cfg.setReadOnly(readOnly);
        cfg.setMinimumIdle(0);
        cfg.setMaximumPoolSize(4);
        cfg.setInitializationFailTimeout(-1);
        cfg.setConnectionTimeout(2500);
        return new HikariDataSource(cfg);
    }

    @PreDestroy
    void close() {
        for (var shard : shards) {
            shard.replicaPool().close();
            shard.primaryPool().close();
        }
    }
}
