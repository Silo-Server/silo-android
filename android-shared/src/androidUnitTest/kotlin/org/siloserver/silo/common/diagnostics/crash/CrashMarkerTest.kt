package org.siloserver.silo.common.diagnostics.crash

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Parse-side test for the crash marker file, against a hand-written JSON body
 * in exactly the shape [CrashCapture]'s dying-thread writer emits.
 */
class CrashMarkerTest {

    // Field-for-field the writer's output shape (see CrashCapture.writeMarkerBestEffort).
    private val markerJson = """
        {"marker_version":1,
         "written_at_ms":1789000123456,
         "process_name":"org.siloserver.silo",
         "pid":4242,
         "capture_session_id":"run_deadbeefcafe",
         "thread_name":"main",
         "exception_class":"java.lang.NullPointerException",
         "exception_message":"player was null",
         "stack_text":"java.lang.NullPointerException: player was null\n\tat org.siloserver.silo.PlaybackSessionManager.start(PlaybackSessionManager.kt:42)",
         "foreground":true,
         "session":{
           "server_instance_id":"srv_home_01",
           "account_user_id":"user_a",
           "profile_id":"prof_living_room",
           "consent_mode":"always",
           "notice_version":2,
           "app_version":"1.4.2",
           "app_build":"20841",
           "platform":"android-tv",
           "os_version":"11 (API 30)"},
         "device_snapshot":{"captured_at":"2026-07-19T18:22:31Z","provenance":"pre_failure"},
         "playback_session_ids":["ps_9f2a","ps_8e1b"],
         "dropped_lines":12,
         "log_lines":[
           {"ts":"2026-07-19T18:22:29.412Z","run":"run_deadbeefcafe","lvl":"W","cat":"playback","tag":"AudioCapabilityMgr","msg":"passthrough suppressed"},
           {"ts":"2026-07-19T18:22:30Z","run":"run_deadbeefcafe","lvl":"I","cat":"network","tag":"HTTPClient","msg":"request completed"}]}
    """.trimIndent()

    @Test
    fun `parse round-trips a representative marker`() {
        val marker = assertNotNull(CrashMarker.parse(markerJson.encodeToByteArray()))

        assertEquals(1, marker.markerVersion)
        assertEquals(1789000123456L, marker.writtenAtMs)
        assertEquals("org.siloserver.silo", marker.processName)
        assertEquals(4242, marker.pid)
        assertEquals("run_deadbeefcafe", marker.captureSessionId)
        assertEquals("main", marker.threadName)
        assertEquals("java.lang.NullPointerException", marker.exceptionClass)
        assertEquals("player was null", marker.exceptionMessage)
        assertEquals(
            "java.lang.NullPointerException: player was null\n" +
                "\tat org.siloserver.silo.PlaybackSessionManager.start(PlaybackSessionManager.kt:42)",
            marker.stackText,
        )
        assertEquals(true, marker.foreground)

        val session = assertNotNull(marker.session)
        assertEquals("srv_home_01", session.serverInstanceId)
        assertEquals("user_a", session.accountUserId)
        assertEquals("prof_living_room", session.profileId)
        assertEquals("always", session.consentMode)
        assertEquals(2, session.noticeVersion)
        assertEquals("1.4.2", session.appVersion)
        assertEquals("20841", session.appBuild)
        assertEquals("android-tv", session.platform)
        assertEquals("11 (API 30)", session.osVersion)

        val device = assertNotNull(marker.deviceSnapshot)
        assertEquals("2026-07-19T18:22:31Z", device.getValue("captured_at").jsonPrimitive.content)

        assertEquals(listOf("ps_9f2a", "ps_8e1b"), marker.playbackSessionIds)
        assertEquals(12L, marker.droppedLines)
        assertEquals(2, marker.logLines.size)
        assertEquals("playback", marker.logLines[0].getValue("cat").jsonPrimitive.content)
        assertEquals("request completed", marker.logLines[1].getValue("msg").jsonPrimitive.content)
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val marker = assertNotNull(
            CrashMarker.parse("""{"marker_version":1,"written_at_ms":5}""".encodeToByteArray()),
        )
        assertEquals(5L, marker.writtenAtMs)
        assertEquals("", marker.processName)
        assertEquals(0, marker.pid)
        assertNull(marker.session)
        assertNull(marker.deviceSnapshot)
        assertEquals(emptyList(), marker.playbackSessionIds)
        assertEquals(0L, marker.droppedLines)
        assertEquals(emptyList(), marker.logLines)
    }

    @Test
    fun `corrupt bytes parse to null`() {
        assertNull(CrashMarker.parse(byteArrayOf(0x00, 0xFF.toByte(), 0x12, 0x88.toByte())))
        assertNull(CrashMarker.parse("not json at all".encodeToByteArray()))
        // Torn marker: truncated mid-object (killed process before fsync).
        assertNull(CrashMarker.parse(markerJson.substring(0, markerJson.length / 2).encodeToByteArray()))
        assertNull(CrashMarker.parse(ByteArray(0)))
    }

    @Test
    fun `unknown fields are ignored`() {
        val withFuture = markerJson.replaceFirst(
            "\"marker_version\":1,",
            "\"marker_version\":1,\"future_field\":{\"nested\":true},\"another\":42,",
        )
        val marker = assertNotNull(CrashMarker.parse(withFuture.encodeToByteArray()))
        assertEquals("run_deadbeefcafe", marker.captureSessionId)
    }
}
