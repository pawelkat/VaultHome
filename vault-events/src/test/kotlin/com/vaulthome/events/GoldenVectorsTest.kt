package com.vaulthome.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * Cross-language proof: every vector in `vault-events-golden.json` was produced by the Rust crate
 * (`tools/vault-events-golden`, built against VaultSearch `crates/vault-events`). The Kotlin port
 * must give byte-identical encodings and the same decisions.
 */
class GoldenVectorsTest {
    private val golden: JsonObject by lazy {
        val text = javaClass.classLoader.getResource("vault-events-golden.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    private fun arr(name: String): List<JsonObject> = golden[name]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun stamp(e: JsonElement?): Stamp? = (e as? JsonObject)?.let { Stamp.fromJson(it) }

    @Test
    fun keys() {
        val all = arr("keys")
        assertTrue(all.size >= 15)
        for (v in all) {
            val path = v.s("path")!!
            val key = v.s("key")
            if (key != null) {
                assertEquals(path, key, VaultKey.pathToKey(path))
                assertEquals(path, v.s("back"), VaultKey.keyToPath(key))
            } else {
                val err = v.s("error")!!
                try {
                    VaultKey.pathToKey(path); fail("expected $err for $path")
                } catch (e: PathException) {
                    assertEquals(path, err, e.error.name)
                }
            }
        }
        for (v in arr("key_decode")) {
            val key = v.s("key")!!
            val p = v.s("path")
            if (p != null) assertEquals(key, p, VaultKey.keyToPath(key)) else {
                try {
                    VaultKey.keyToPath(key); fail("expected ${v.s("error")} for $key")
                } catch (e: PathException) {
                    assertEquals(key, v.s("error"), e.error.name)
                }
            }
        }
    }

    @Test
    fun eventEncodingIsByteIdentical() {
        val all = arr("events")
        assertEquals(10, all.size)
        for (v in all) {
            val content = v.s("content_b64")?.let { Base64.getDecoder().decode(it) }
            val path = v.s("path")!!
            val origin = v.s("origin")!!
            val version = v["version"]!!.jsonPrimitive.long
            val ts = v["ts_ms"]!!.jsonPrimitive.long
            val prev = v.s("prev_sha256")
            val ev = if (v["deleted"]!!.jsonPrimitive.boolean) NoteEvent.delete(path, origin, version, prev, ts)
            else NoteEvent.put(path, content!!, origin, version, prev, ts)
            val name = v.s("name")
            assertEquals(name, v.s("encoded"), ev.encodeToString())
            assertEquals(name, v.s("key"), ev.key())
            assertEquals(name, v.s("sha256"), ev.stamp.sha256)
            // And decoding the Rust bytes gives the same event back.
            val back = NoteEvent.decode(v.s("encoded")!!.toByteArray(Charsets.UTF_8), v.s("key"))
            assertEquals(name, ev, back)
        }
    }

    @Test
    fun labelledEventsAreByteIdentical() {
        val all = arr("labelled_events")
        assertEquals(4, all.size)
        for (v in all) {
            val name = v.s("name")
            val content = v.s("content_b64")?.let { Base64.getDecoder().decode(it) }
            val path = v.s("path")!!
            val origin = v.s("origin")!!
            val version = v["version"]!!.jsonPrimitive.long
            val ts = v["ts_ms"]!!.jsonPrimitive.long
            val prev = v.s("prev_sha256")
            var ev = if (v["deleted"]!!.jsonPrimitive.boolean) NoteEvent.delete(path, origin, version, prev, ts)
            else NoteEvent.put(path, content!!, origin, version, prev, ts)
            ev = ev.by(v.s("writer")!!, v.s("cause")!!)
            (v["conflict"] as? JsonObject)?.let { c ->
                ev = ev.withConflict(
                    ConflictInfo(
                        c.s("of")!!, c.s("winner_sha256")!!, c["winner_size"]!!.jsonPrimitive.long, c.s("winner_origin")!!,
                        c.s("loser_sha256")!!, c["loser_size"]!!.jsonPrimitive.long, c.s("loser_origin")!!,
                    ),
                )
            }
            assertEquals(name, v.s("encoded"), ev.encodeToString())
            assertEquals(name, ev, NoteEvent.decode(v.s("encoded")!!.toByteArray(Charsets.UTF_8), v.s("key")))
        }
    }

    @Test
    fun blobReferencesAreByteIdentical() {
        val all = arr("blob_events")
        assertEquals(3, all.size)
        for (v in all) {
            val name = v.s("name")
            val content = Base64.getDecoder().decode(v.s("content_b64")!!)
            var full = NoteEvent.put(v.s("path")!!, content, v.s("origin")!!, v["version"]!!.jsonPrimitive.long, v.s("prev_sha256"), v["ts_ms"]!!.jsonPrimitive.long)
                .by(v.s("writer")!!, v.s("cause")!!)
            (v["conflict"] as? JsonObject)?.let { c ->
                full = full.withConflict(
                    ConflictInfo(
                        c.s("of")!!, c.s("winner_sha256")!!, c["winner_size"]!!.jsonPrimitive.long, c.s("winner_origin")!!,
                        c.s("loser_sha256")!!, c["loser_size"]!!.jsonPrimitive.long, c.s("loser_origin")!!,
                    ),
                )
            }
            val ref = full.toBlobRef(v["inline_max"]!!.jsonPrimitive.long, v["chunk_size"]!!.jsonPrimitive.long)!!
            assertEquals(name, v.s("key"), ref.key())
            assertEquals(name, v.s("encoded"), ref.encodeToString())
            val back = NoteEvent.decode(v.s("encoded")!!.toByteArray(Charsets.UTF_8), v.s("key"))
            assertEquals(name, ref, back)
            assertEquals(name, full, back.withContent(content))
        }
    }

    @Test
    fun decodeAcceptsAndRejectsLikeRust() {
        val all = arr("decode")
        assertEquals(22, all.size)
        for (v in all) {
            val payload = v.s("payload")!!
            val key = v.s("key")
            val ok = v["ok"]!!.jsonPrimitive.boolean
            try {
                val ev = NoteEvent.decode(payload.toByteArray(Charsets.UTF_8), key)
                if (!ok) fail("Rust rejected (${v.s("error")}) but Kotlin accepted: $payload")
                assertEquals(payload, v.s("reencoded"), ev.encodeToString())
            } catch (e: DecodeException) {
                if (ok) fail("Rust accepted but Kotlin rejected ${e.kind}: $payload")
                assertEquals(payload, v.s("error"), e.kind.name)
            }
        }
    }

    @Test
    fun orderingMatches() {
        val all = arr("beats")
        assertEquals(64, all.size)
        for (v in all) {
            val a = stamp(v["a"])!!
            val b = stamp(v["b"])!!
            assertEquals("$a vs $b", v["beats"]!!.jsonPrimitive.boolean, a.beats(b))
            assertEquals("$a same $b", v["same"]!!.jsonPrimitive.boolean, a.sameState(b))
        }
    }

    @Test
    fun datesAndConflictNames() {
        for (v in arr("dates")) assertEquals(v.s("date"), Conflict.utcDate(v["ts_ms"]!!.jsonPrimitive.long))
        for (v in arr("conflict_names")) {
            val n = Conflict.conflictNameFor(v.s("path")!!, v.s("origin")!!, v["ts_ms"]!!.jsonPrimitive.long)
            assertEquals(v.s("name"), n)
            assertEquals(v["is_copy"]!!.jsonPrimitive.boolean, Conflict.isConflictCopy(n))
        }
    }

    @Test
    fun decideMatrixMatches() {
        val all = arr("decide")
        assertEquals(600, all.size)
        val kinds = HashMap<String, Int>()
        for (v in all) {
            val state = stamp(v["state"])
            val disk = v.s("disk")
            val inc = stamp(v["incoming"])!!
            val got = Conflict.decide(state, disk, inc, v.s("me")!!, v["local_ts_ms"]!!.jsonPrimitive.long)
            val want = v["decision"]!!.jsonObject
            val kind = want.s("kind")!!
            kinds.merge(kind, 1, Int::plus)
            val ctx = "state=$state disk=$disk inc=$inc"
            when (kind) {
                "ignore" -> assertEquals(ctx, Decision.Ignore, got)
                "adopt" -> assertEquals(ctx, Decision.Adopt, got)
                "apply" -> assertEquals(ctx, Decision.Apply(want.s("save_local_as")), got)
                "keep_local" -> assertEquals(ctx, Decision.KeepLocal(stamp(want["local"])!!, want.s("save_incoming_as")), got)
                else -> fail(kind)
            }
        }
        // The matrix exercises every branch.
        assertEquals(setOf("ignore", "adopt", "apply", "keep_local"), kinds.keys)
    }

    @Test
    fun ignoreRules() {
        for (v in arr("ignored")) assertEquals(v.s("path"), v["ignored"]!!.jsonPrimitive.boolean, VaultRules.isIgnored(v.s("path")!!))
    }

    @Test
    fun indexReplyRoundTrip() {
        val rust = golden["index_reply"]!!.jsonPrimitive.content
        val r = IndexReply.decode(rust.toByteArray())
        assertEquals("box", r.hub)
        assertEquals(2, r.stamps.size)
        assertTrue(r.stamps[1].deleted)
        assertEquals(rust, r.encodeToString())
    }

}
