package xyz.limo060719.goclaw.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the tolerant gateway payload parsers against field-name regressions — the single most
 * common source of "screen shows all zeros/empty" bugs, since the backend's JSON shapes are
 * undocumented and inconsistent. Samples marked "real frame" are captured from the live gateway.
 */
class WsParseTest {

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject
    private fun el(s: String) = Json.parseToJsonElement(s)

    /* ---- primitive field pickers ---- */

    @Test fun strP_returns_first_non_blank_candidate() {
        val o = obj("""{"a":"","b":"hit","c":"skip"}""")
        assertEquals("hit", strP(o, "a", "b", "c"))
    }

    @Test fun strP_skips_json_null_and_missing() {
        val o = obj("""{"a":null,"b":"ok"}""")
        assertEquals("ok", strP(o, "missing", "a", "b"))
        assertEquals("", strP(o, "missing"))
        assertEquals("", strP(null, "a"))
    }

    @Test fun strP_keeps_literal_string_null() {
        // A quoted "null" is a real string value, not JSON null — must be returned.
        assertEquals("null", strP(obj("""{"a":"null"}"""), "a"))
    }

    @Test fun strA_serializes_nested_objects_and_arrays() {
        val o = obj("""{"obj":{"x":1},"arr":[1,2]}""")
        assertTrue(strA(o, "obj").contains("\"x\""))
        assertTrue(strA(o, "arr").contains("1"))
    }

    @Test fun boolP_parses_only_strict_booleans() {
        assertTrue(boolP(obj("""{"a":true}"""), "a"))
        assertFalse(boolP(obj("""{"a":false}"""), "a"))
        assertFalse(boolP(obj("""{"a":"yes"}"""), "a")) // not a strict boolean → false
        assertFalse(boolP(null, "a"))
    }

    /* ---- sessions.list ---- */

    @Test fun sessions_parses_snake_and_camel_case() {
        val list = parseSessions(el("""
            [{"session_key":"agent:x:ws:direct:1","title":"Hi","agent_id":"x","message_count":4,"updated_at":"t1"},
             {"key":"k2","label":"L","agentKey":"y","count":"2"}]
        """.trimIndent()))
        assertEquals(2, list.size)
        assertEquals("agent:x:ws:direct:1", list[0].key)
        assertEquals(4, list[0].messageCount)
        assertEquals("x", list[0].agent)
        assertEquals("L", list[1].title)
        assertEquals(2, list[1].messageCount)
    }

    @Test fun sessions_unwraps_object_wrapper_and_drops_keyless() {
        val list = parseSessions(el("""{"sessions":[{"key":"a"},{"title":"no key"}]}"""))
        assertEquals(1, list.size)
        assertEquals("a", list[0].key)
    }

    /* ---- device.pair.list (real frame: approved live under "paired") ---- */

    @Test fun pairings_real_frame_uses_paired_for_approved() {
        val list = parsePairings(el("""
            {"paired":[{"sender_id":"u1","channel":"telegram","chat_id":"c1","paired_at":"1719000000000","paired_by":"app"}],
             "pending":[]}
        """.trimIndent()))
        assertEquals(1, list.size)
        val p = list[0]
        assertEquals("u1", p.senderId)
        assertEquals("telegram", p.channel)
        assertEquals("app", p.approvedBy)
        assertTrue(p.isApproved)
        assertFalse(p.isPending)
    }

    @Test fun pairings_split_form_tags_pending_and_approved() {
        val list = parsePairings(el("""
            {"pending":[{"code":"123456","channel":"telegram"}],
             "approved":[{"sender_id":"u9","channel":"slack"}]}
        """.trimIndent()))
        assertEquals(2, list.size)
        assertTrue(list.first { it.code == "123456" }.isPending)
        assertTrue(list.first { it.senderId == "u9" }.isApproved)
    }

    /* ---- exec.approval.list ---- */

    @Test fun approvals_reads_command_and_drops_idless() {
        val list = parseApprovals(el("""
            [{"id":"a1","command":"rm -rf /tmp/x","agent_id":"ag","cwd":"/w","reason":"cleanup"},
             {"command":"no id"}]
        """.trimIndent()))
        assertEquals(1, list.size)
        assertEquals("rm -rf /tmp/x", list[0].command)
        assertEquals("ag", list[0].agentId)
    }

    /* ---- api_keys.list ---- */

    @Test fun apiKeys_parses_scopes_and_revoked_status() {
        val list = parseApiKeys(el("""
            {"keys":[
              {"id":"k1","name":"ci","scopes":["operator.read","operator.write"],"prefix":"goclaw_ab","expires_at":null},
              {"id":"k2","name":"old","status":"revoked"}
            ]}
        """.trimIndent()))
        assertEquals(2, list.size)
        assertEquals(listOf("operator.read", "operator.write"), list[0].scopes)
        assertFalse(list[0].revoked)
        assertTrue(list[1].revoked)
    }

    /* ---- log stream events ---- */

    @Test fun logEvent_reads_nested_payload_payload() {
        val line = parseLogEvent(obj("""
            {"event":"logs.line","payload":{"payload":{"level":"warn","message":"disk high","time":"t","source":"gw"}}}
        """.trimIndent()))
        assertEquals("warn", line?.level)
        assertEquals("disk high", line?.message)
        assertEquals("gw", line?.source)
    }

    @Test fun logEvent_ignores_non_log_events_and_empty_messages() {
        assertNull(parseLogEvent(obj("""{"event":"agent","payload":{"message":"x"}}""")))
        assertNull(parseLogEvent(obj("""{"event":"logs.line","payload":{"level":"info"}}""")))
    }

    /* ---- heartbeat.get ---- */

    @Test fun heartbeatConfig_unwraps_and_defaults_interval() {
        val cfg = parseHeartbeatConfig(el("""{"heartbeat":{"enabled":true,"interval_sec":"600","prompt":"ping"}}"""))
        assertTrue(cfg!!.enabled)
        assertEquals(600, cfg.intervalSec)
        assertEquals("ping", cfg.prompt)
        // Missing interval falls back to the 300s floor.
        assertEquals(300, parseHeartbeatConfig(el("""{"enabled":false}"""))!!.intervalSec)
    }

    /* ---- final-result media array ---- */

    @Test fun media_reads_path_url_variants_and_drops_empty() {
        val items = parseMedia(obj("""
            {"media":[
              {"file_path":"/workspace/a.png","mime_type":"image/png","name":"a.png"},
              {"url":"https://x/y.pdf"},
              {"note":"no path or url"}
            ]}
        """.trimIndent()))
        assertEquals(2, items.size)
        assertEquals("/workspace/a.png", items[0].path)
        assertEquals("https://x/y.pdf", items[1].url)
    }

    /* ---- error + session id extraction ---- */

    @Test fun errorMessage_reads_nested_error_message() {
        assertEquals("boom", errorMessage(obj("""{"error":{"code":"ERR","message":"boom"}}""")))
        assertNull(errorMessage(obj("""{"ok":true}""")))
    }

    @Test fun extractSessionId_prefers_payload_then_top() {
        assertEquals("s1", extractSessionId(obj("""{"session_id":"s1"}"""), obj("""{}""")))
        assertEquals("t1", extractSessionId(obj("""{}"""), obj("""{"sessionId":"t1"}""")))
    }
}
