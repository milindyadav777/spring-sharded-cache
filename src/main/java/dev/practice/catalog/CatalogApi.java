package dev.practice.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Optional;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "app.role", havingValue = "api")
@RequestMapping("/tenants/{tenant}/products/{sku}")
class CatalogApi {
    record Input(@NotBlank @Size(max = 180) String name) {}
    enum Consistency { eventual, strong }
    private final Catalog catalog;
    private final RegionalCaches cache;
    private final MeterRegistry metrics;

    CatalogApi(Catalog catalog, RegionalCaches cache, MeterRegistry metrics) {
        this.catalog = catalog;
        this.cache = cache;
        this.metrics = metrics;
    }

    @PutMapping
    Catalog.Item put(@PathVariable @Positive long tenant,
                     @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String sku,
                     @Valid @RequestBody Input input) {
        return catalog.put(tenant, sku, input.name());
    }

    @GetMapping
    ResponseEntity<Catalog.Item> get(@PathVariable @Positive long tenant,
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String sku,
            @RequestParam(defaultValue = "EAST") Region region,
            @RequestParam(defaultValue = "eventual") Consistency consistency) {
        boolean strong = consistency == Consistency.strong;
        if (!strong) {
            try {
                Optional<Catalog.Item> hit = cache.get(region, tenant, sku);
                if (hit.isPresent()) return response("redis-" + region, hit.get());
            } catch (DataAccessException unavailable) {
                metrics.counter("catalog.cache.fallback", "region", region.name()).increment();
            }
        }
        var item = catalog.get(tenant, sku, strong).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return response(strong ? "primary" : "replica", item);
    }

    private ResponseEntity<Catalog.Item> response(String source, Catalog.Item item) {
        return ResponseEntity.ok().header("X-Read-Source", source).body(item);
    }
}
