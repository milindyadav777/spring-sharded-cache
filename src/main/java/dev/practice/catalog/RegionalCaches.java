package dev.practice.catalog;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import jakarta.annotation.PreDestroy;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
final class RegionalCaches {
    // Padded decimal strings preserve BIGINT order beyond Lua's exact integer range.
    private static final DefaultRedisScript<Long> UPDATE = new DefaultRedisScript<>("""
        local current = redis.call('HGET', KEYS[1], 'version')
        if current and current >= ARGV[1] then return 0 end
        redis.call('HSET', KEYS[1], 'version', ARGV[1], 'name', ARGV[2])
        redis.call('EXPIRE', KEYS[1], ARGV[3])
        return 1
        """, Long.class);
    private final ClientResources resources = DefaultClientResources.builder()
            .ioThreadPoolSize(2).computationThreadPoolSize(2).build();
    private final EnumMap<Region, LettuceConnectionFactory> factories = new EnumMap<>(Region.class);
    private final EnumMap<Region, StringRedisTemplate> caches = new EnumMap<>(Region.class);

    RegionalCaches(Environment env) {
        for (var region : Region.values()) {
            String prefix = "REDIS_" + region;
            var nodes = Arrays.asList(env.getRequiredProperty(prefix + "_NODES").split(","));
            var cluster = new RedisClusterConfiguration(nodes);
            cluster.setPassword(env.getRequiredProperty(prefix + "_PASSWORD"));
            var options = ClusterClientOptions.builder()
                    .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
                    .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                            .enableAllAdaptiveRefreshTriggers().enablePeriodicRefresh(Duration.ofSeconds(30)).build())
                    .build();
            var client = LettuceClientConfiguration.builder().clientResources(resources)
                    .clientOptions(options).commandTimeout(Duration.ofSeconds(2));
            if (env.getProperty(prefix + "_TLS", Boolean.class, false)) client.useSsl();
            var factory = new LettuceConnectionFactory(cluster, client.build());
            factory.afterPropertiesSet();
            factories.put(region, factory);
            caches.put(region, new StringRedisTemplate(factory));
        }
    }

    static String key(long tenant, String sku) {
        return "product:" + tenant + ":" + sku;
    }

    static String version(long version) {
        return String.format(Locale.ROOT, "%019d", version);
    }

    void apply(Region region, Catalog.Item item) {
        caches.get(region).execute(UPDATE, List.of(key(item.tenant(), item.sku())),
                                   version(item.version()), item.name(), "300");
    }

    Optional<Catalog.Item> get(Region region, long tenant, String sku) {
        var fields = caches.get(region).opsForHash().entries(key(tenant, sku));
        if (fields.isEmpty()) return Optional.empty();
        return Optional.of(new Catalog.Item(tenant, sku,
                Long.parseLong((String) fields.get("version")), (String) fields.get("name")));
    }

    @PreDestroy
    void close() {
        factories.values().forEach(LettuceConnectionFactory::destroy);
        resources.shutdown();
    }
}
