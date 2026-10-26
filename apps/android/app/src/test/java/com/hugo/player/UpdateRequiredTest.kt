package com.hugo.player

import com.hugo.player.data.ApiException
import com.hugo.player.data.isUpdateRequired
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRequiredTest {
    @Test fun only426OrUpdateRequiredShowsUpdateScreen() {
        assertTrue(isUpdateRequired(ApiException(426, "x", "update_required")))
        assertTrue(isUpdateRequired(ApiException(426, "x")))
        assertTrue(isUpdateRequired(ApiException(400, "x", "update_required")))
        // rede/servidor/credenciais: comportamento normal (não bloqueia à toa)
        assertFalse(isUpdateRequired(ApiException(401, "x", "invalid_credentials")))
        assertFalse(isUpdateRequired(ApiException(502, "x")))
        assertFalse(isUpdateRequired(java.net.UnknownHostException("sem rede")))
        assertFalse(isUpdateRequired(null))
    }
}
