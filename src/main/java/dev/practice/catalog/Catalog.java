package dev.practice.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class Catalog {
    record Item(long tenant, String sku, long version, String name) {
        static Item read(ResultSet row, int ignored) throws SQLException {
            return new Item(row.getLong("tenant"), row.getString("sku"),
                            row.getLong("version"), row.getString("name"));
        }
    }
    private final ShardMap map;

    Catalog(ShardMap map) { this.map = map; }

    Item put(long tenant, String sku, String name) {
        var shard = map.forTenant(tenant);
        return shard.transaction().execute(tx -> {
            var db = shard.primary();
            var item = db.queryForObject("""
                INSERT INTO product (tenant, sku, name, version) VALUES (?, ?, ?, 1)
                ON CONFLICT (tenant, sku) DO UPDATE SET name = excluded.name, version = product.version + 1
                RETURNING tenant, sku, version, name
                """, Item::read, tenant, sku, name);
            for (var region : Region.values()) {
                db.update("""
                    INSERT INTO cache_job(tenant, sku, region, version, name) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (tenant, sku, region) DO UPDATE SET
                      version = excluded.version, name = excluded.name, due_at = now(), attempts = 0, token = NULL
                    """, tenant, sku, region.name(), item.version(), name);
            }
            return item;
        });
    }

    Optional<Item> get(long tenant, String sku, boolean strong) {
        var shard = map.forTenant(tenant);
        JdbcTemplate db = strong ? shard.primary() : shard.replica();
        return db.query("SELECT tenant, sku, version, name FROM product WHERE tenant=? AND sku=?",
                        Item::read, tenant, sku).stream().findFirst();
    }
}
