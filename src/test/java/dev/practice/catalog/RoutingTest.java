package dev.practice.catalog;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoutingTest {
    @Test void stablePlacementForPositiveTenantIds() {
        assertEquals(0, ShardMap.index(42));
        assertEquals(1, ShardMap.index(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ShardMap.index(0));
    }
    @Test void redisVersionsSortEvenPastDoublePrecision() {
        assertTrue(RegionalCaches.version(9).compareTo(RegionalCaches.version(10)) < 0);
        assertTrue(RegionalCaches.version(9007199254740992L)
                .compareTo(RegionalCaches.version(9007199254740993L)) < 0);
    }
}
