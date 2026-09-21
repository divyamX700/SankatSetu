package com.sankatsetu.app.mesh.authz

import com.cedarpolicy.BasicAuthorizationEngine
import com.cedarpolicy.model.AuthorizationRequest
import com.cedarpolicy.model.entity.Entity
import com.cedarpolicy.model.exception.AuthException
import com.cedarpolicy.model.policy.PolicySet
import com.cedarpolicy.value.EntityTypeName
import com.cedarpolicy.value.EntityUID
import com.cedarpolicy.value.PrimLong
import java.util.concurrent.ConcurrentHashMap

/** One kind of mesh traffic, per `assets/cedar/policies.cedar`'s resource vocabulary. */
enum class MessageKind(val cedarName: String) {
    PUBLIC("public"),
    DIRECTED("directed"),
    SOS("sos"),
    IOU("iou"),
    ANNOUNCE("announce")
}

/**
 * What [com.sankatsetu.app.mesh.router.MessageRouter] actually depends on —
 * a real [CedarAuthorizer] in production, a plain fake/lambda in JVM unit
 * tests. Splitting this out (rather than the router depending on
 * [CedarAuthorizer] directly) is what lets a router test simulate a deny
 * decision without a real native Cedar library, which JVM unit tests never
 * have — see `MessageRouterAuthorizationTest`.
 */
fun interface MeshAuthorizer {
    fun isAllowed(senderIdHex: String, kind: MessageKind): Boolean
}

/**
 * Real per-message flood-control gate for the mesh, evaluated with AWS's
 * Cedar policy engine (`com.cedarpolicy:cedar-java`) — the integration
 * point identified in `handoff.md` §4a: [MessageRouter.handleInboundBytes]
 * (the one real choke point every mesh packet passes through) calls
 * [isAllowed] before accepting or relaying a packet.
 *
 * Scoped to what this app's protocol actually has — see
 * `app/src/main/assets/cedar/policies.cedar`'s header comment — not the
 * original PRD's invented `#chat`/`#sos`/`#official` channel model, which
 * was never built (there are no channels in [com.sankatsetu.app.mesh.protocol.MessageType]).
 *
 * **Fail-open by design**: if the native Cedar library isn't loaded (a JVM
 * unit test, or a device/ABI this app never shipped a `.so` for),
 * [isAvailable] is false and [isAllowed] always returns true. Cedar here is
 * an *additional* flood-control layer on top of [com.sankatsetu.app.mesh.router.SeenMessageCache]'s
 * dedup and the TTL hop budget — both of which run regardless — not the
 * only thing standing between the mesh and a flood. A missing/failed Cedar
 * engine degrading to "allow" (not "drop everything" or "crash") matches
 * this codebase's existing pattern of graceful degradation over hard
 * failure (see [com.sankatsetu.app.assistant.AssistantEngine]'s extractive
 * fallback for the same philosophy applied to the LLM).
 *
 * See `docs/adr/0017-cedar-cross-compile.md` for how the native
 * `libcedar_java_ffi.so` is actually built and loaded on Android — cedar-java
 * has no upstream Android support at all, so both the cross-compile and the
 * loading mechanism (bypassing `com.fizzed:jne`'s desktop-only OS detection
 * via the `CEDAR_JAVA_FFI_LIB` environment variable) are this project's own
 * work, not an off-the-shelf integration.
 */
class CedarAuthorizer(policyText: String) : MeshAuthorizer {

    private val engine: BasicAuthorizationEngine?
    private val policySet: PolicySet?

    // key = "<senderIdHex>:<kind>" -> timestamps (ms) of messages seen from
    // that sender of that kind in roughly the last minute. A plain
    // ArrayList behind a per-key lock is plenty for mesh-scale traffic (a
    // handful of peers, not a real distributed rate limiter) and needs no
    // external dependency.
    private val recentTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    private val blockedPeers = java.util.concurrent.CopyOnWriteArraySet<String>()

    val isAvailable: Boolean get() = engine != null && policySet != null

    init {
        val (loadedEngine, loadedPolicySet) = runCatching {
            val e = BasicAuthorizationEngine()
            val p = PolicySet.parsePolicies(policyText)
            e to p
        }.getOrElse {
            // Native lib not loaded (JVM test, or an ABI without a bundled
            // .so) or a policy syntax error — either way, fail open, never
            // crash the mesh over an authorization add-on. See class doc.
            null to null
        }
        engine = loadedEngine
        policySet = loadedPolicySet
    }

    /** Marks [peerIdHex] as blocked — enforced by the policy set's `principal in Group::"blocked"` rule. */
    fun block(peerIdHex: String) = blockedPeers.add(peerIdHex)
    fun unblock(peerIdHex: String) = blockedPeers.remove(peerIdHex)

    /**
     * Records that a message of [kind] just arrived from [senderIdHex], and
     * returns whether the mesh router should accept/relay it. Recording and
     * checking are the same call (not split) because every inbound packet
     * that reaches this point should count toward its own rate window,
     * whether or not it's ultimately allowed — a rejected flood attempt
     * still counts against the sender for the next check.
     */
    override fun isAllowed(senderIdHex: String, kind: MessageKind): Boolean = isAllowed(senderIdHex, kind, System.currentTimeMillis())

    fun isAllowed(senderIdHex: String, kind: MessageKind, now: Long): Boolean {
        val messagesLastMinute = recordAndCount(senderIdHex, kind, now)
        val e = engine ?: return true
        val p = policySet ?: return true

        return try {
            // Unqualified type names (no namespace) — must match
            // assets/cedar/policies.cedar exactly, which is itself
            // namespace-free. See docs/adr/0017's note on why the schema
            // asset uses a `SankatSetu` namespace for documentation/future
            // validation purposes but the policy text and this evaluation
            // code deliberately don't, to keep them trivially in sync
            // without a schema round-trip neither is actually using yet
            // (no AuthorizationRequest below passes a schema).
            val principalType = EntityTypeName.parse("Peer").get()
            val actionType = EntityTypeName.parse("Action").get()
            val resourceType = EntityTypeName.parse("MessageKind").get()
            val groupType = EntityTypeName.parse("Group").get()

            val principalEuid = EntityUID(principalType, senderIdHex)
            val actionEuid = EntityUID(actionType, "relay")
            val resourceEuid = EntityUID(resourceType, kind.cedarName)

            val isBlocked = senderIdHex in blockedPeers
            val parents = if (isBlocked) setOf(EntityUID(groupType, "blocked")) else emptySet()
            val principalEntity = Entity(principalEuid, parents)
            val entities = if (isBlocked) {
                setOf(principalEntity, Entity(EntityUID(groupType, "blocked")))
            } else {
                setOf(principalEntity)
            }

            val context = mapOf("messagesLastMinute" to PrimLong(messagesLastMinute))
            val request = AuthorizationRequest(principalEuid, actionEuid, resourceEuid, context)

            val response = e.isAuthorized(request, p, entities)
            response.success.map { it.isAllowed() }.orElse(true) // a malformed response fails open too
        } catch (_: AuthException) {
            true // never let an authorization-engine error block real mesh traffic
        }
    }

    private fun recordAndCount(senderIdHex: String, kind: MessageKind, now: Long): Long {
        val key = "$senderIdHex:${kind.cedarName}"
        val list = recentTimestamps.getOrPut(key) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            list.add(now)
            list.removeAll { now - it > WINDOW_MS }
            return list.size.toLong()
        }
    }

    companion object {
        private const val WINDOW_MS = 60_000L

        /**
         * Bypasses cedar-java's `LibraryLoader`, which delegates to
         * `com.fizzed:jne` for OS/ABI detection — a library with no concept
         * of Android as an OS. Setting `CEDAR_JAVA_FFI_LIB` to the absolute
         * path of the `.so` Android already extracted into this app's
         * native library directory (from `jniLibs/<abi>/libcedar_java_ffi.so`
         * at install time) makes `LibraryLoader.loadLibrary()` call
         * `System.load(path)` directly instead, per its own documented
         * override mechanism. Must run before any `com.cedarpolicy.*` class
         * is touched (that package's static initializer is what triggers
         * the load) — call this once, early, from [com.sankatsetu.app.di.AppContainer].
         */
        fun prepareNativeLibraryPath(context: android.content.Context) {
            runCatching {
                val soPath = "${context.applicationInfo.nativeLibraryDir}/libcedar_java_ffi.so"
                if (java.io.File(soPath).exists()) {
                    android.system.Os.setenv("CEDAR_JAVA_FFI_LIB", soPath, true)
                }
            }
            // Deliberately swallow failures here (missing API, missing .so
            // on this ABI, SELinux denial on some OEM ROM): worst case
            // `isAvailable` ends up false and every check fails open — see
            // the class doc's fail-open rationale. This must never be the
            // reason the mesh doesn't start.
        }
    }
}
