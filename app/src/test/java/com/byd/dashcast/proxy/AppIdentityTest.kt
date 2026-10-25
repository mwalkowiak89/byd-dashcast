package com.byd.dashcast.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdentityTest {

    @Test
    fun `no device-global name collides with upstream DashCast`() {
        assertNotEquals("com.byd.dashcast", AppIdentity.PACKAGE)
        assertNotEquals("dashcast_proxy", AppIdentity.PROXY_PROCESS)
        assertNotEquals("byd.mirror.daemon", AppIdentity.MIRROR_SPAWN_NAME)
        assertNotEquals("com.byd.dashcast.mirrordaemon", AppIdentity.MIRROR_PROCESS)
        assertNotEquals("byd_proxy_daemon", AppIdentity.PROXY_SERVICE)
        assertNotEquals("byd_mirror_daemon", AppIdentity.MIRROR_SERVICE)
        assertNotEquals("Kiroha/byd-dashcast", AppIdentity.UPDATE_REPO)
        assertFalse(AppIdentity.tmp("_proxy.pid").contains("dashcast_proxy"))
    }

    @Test
    fun `our process names do not contain upstream ones, so ps greps stay disjoint`() {
        for (ours in listOf(AppIdentity.PROXY_PROCESS, AppIdentity.MIRROR_PROCESS, AppIdentity.MIRROR_SPAWN_NAME)) {
            for (theirs in listOf("dashcast_proxy", "com.byd.dashcast.mirrordaemon", "byd.mirror.daemon")) {
                assertFalse("$ours contains $theirs", ours.contains(theirs))
                assertFalse("$theirs contains $ours", theirs.contains(ours))
            }
        }
    }

    @Test
    fun `tmp files live directly under data local tmp`() {
        assertEquals("/data/local/tmp/" + AppIdentity.DEVICE_TAG + "_proxy.pid", AppIdentity.tmp("_proxy.pid"))
    }

    @Test
    fun `self-excluding grep pattern matches the name but not its own command line`() {
        val pattern = Regex(AppIdentity.selfExcludingGrep(AppIdentity.MIRROR_PROCESS))
        assertTrue(pattern.containsMatchIn("shell 123 1 0 0 x 0 S " + AppIdentity.MIRROR_PROCESS))
        assertFalse(pattern.containsMatchIn("grep -E '" + pattern.pattern + "'"))
        assertFalse(pattern.containsMatchIn("shell 9 1 0 0 x 0 S com.byd.dashcast.mirrordaemon"))
    }
}
