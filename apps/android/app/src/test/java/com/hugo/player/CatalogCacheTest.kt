package com.hugo.player

import com.hugo.player.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogCacheTest {
    private val h = 3_600_000L
    private val now = 1_800_000_000_000L

    @Test fun defaultIs48hAndOffered() {
        assertEquals(48, AppSettings.DEFAULT_CATALOG_TTL_HOURS)
        assertTrue(AppSettings.CATALOG_TTL_OPTIONS.containsAll(listOf(24, 48)))
        // sem Configurações carregadas (ex.: teste) usa o padrão
        assertEquals(48, AppSettings.catalogTtlHours)
    }

    @Test fun freshWithinTtl() {
        assertTrue(AppSettings.isCatalogFresh(100, now - 47 * h, now, 48))
        assertFalse(AppSettings.isCatalogFresh(100, now - 49 * h, now, 48))
        assertTrue(AppSettings.isCatalogFresh(100, now - 11 * h, now, 12))
        assertFalse(AppSettings.isCatalogFresh(100, now - 13 * h, now, 12))
    }

    @Test fun emptyOrNeverSyncedIsNotFresh() {
        assertFalse(AppSettings.isCatalogFresh(0, now - h, now, 48))
        assertFalse(AppSettings.isCatalogFresh(100, null, now, 48))
        // relógio do aparelho voltou no tempo: não confia
        assertFalse(AppSettings.isCatalogFresh(100, now + h, now, 48))
    }
}
