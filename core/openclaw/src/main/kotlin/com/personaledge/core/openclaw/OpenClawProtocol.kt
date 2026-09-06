package com.personaledge.core.openclaw

/** Exact contract derived from the signed OpenClaw v2026.8.1 release. */
object OpenClawProtocol {
    /** The deployed baseline this client's contract was derived from. */
    const val RELEASE_VERSION: String = "2026.8.1"

    /**
     * Releases accepted at hello. A release joins this set only after its wire contract has been
     * diffed against [RELEASE_VERSION] and found equivalent for every surface this client touches:
     * the connect params, the `agent`/`agent.wait`/`chat.abort` param schemas and their required
     * scopes, the `agent`/`chat` event names, and the response/event payload field sets.
     *
     * 2026.9.2 was qualified that way on 2026-09-06. Its `PROTOCOL_VERSION` and
     * `MIN_CLIENT_PROTOCOL_VERSION` are byte-identical to 2026.8.1, all three param schemas are
     * unchanged, and the only payload change on this client's surface is the optional
     * `errorDetail` object on a chat error event.
     *
     * An unlisted release still fails closed. This is a qualification gate, not a version range,
     * so it must never be widened to a prefix, a comparison, or a wildcard.
     */
    val QUALIFIED_RELEASE_VERSIONS: Set<String> = setOf(RELEASE_VERSION, "2026.9.2")

    const val WIRE_VERSION: Int = 4
    const val MIN_CLIENT_WIRE_VERSION: Int = 4

    /**
     * Personal Edge is an external protocol client. `openclaw-android` is reserved for the
     * first-party native app and receives native-app/setup-code pairing policy on the Gateway.
     */
    const val CLIENT_ID: String = "gateway-client"
    const val CLIENT_MODE: String = "ui"
    const val ROLE: String = "operator"

    const val METHOD_AGENT: String = "agent"
    const val METHOD_AGENT_WAIT: String = "agent.wait"
    const val METHOD_CHAT_ABORT: String = "chat.abort"
    const val METHOD_HEALTH_READ: String = "personaledge.health.read"

    /** Required methods for the text-only adapter, including unextended vanilla Gateways. */
    val APP_METHODS: Set<String> = setOf(METHOD_AGENT, METHOD_AGENT_WAIT, METHOD_CHAT_ABORT)

    /** Optional capabilities are never prerequisites for ordinary pairing or model runs. */
    val OPTIONAL_APP_METHODS: Set<String> = setOf(METHOD_HEALTH_READ)

    /**
     * Events required by the text-only adapter. `chat` carries bounded output/progress while
     * `agent` lets the client fail closed if the supposedly tool-free run emits tool activity.
     */
    val APP_EVENTS: Set<String> = setOf("agent", "chat")

    val SCOPES: List<String> = listOf("operator.read", "operator.write")

    const val MAX_PREAUTH_PAYLOAD_BYTES: Int = 64 * 1_024
    const val MAX_LOCAL_FRAME_BYTES: Int = 1 * 1_024 * 1_024
    const val MAX_LOCAL_BUFFERED_BYTES: Long = 2L * 1_024 * 1_024
    const val MAX_PENDING_REQUESTS: Int = 32
    const val MAX_PREAUTH_METADATA_EVENTS: Int = 64
    const val MAX_ORDERLY_ABORT_REQUESTS: Int = 64
    const val MAX_JSON_DEPTH: Int = 32
    const val MAX_JSON_FIELDS_PER_OBJECT: Int = 256
    const val MAX_JSON_ARRAY_ITEMS: Int = 2_048
    const val MAX_JSON_STRING_UTF8_BYTES: Int = 256 * 1_024

    const val CONNECT_TIMEOUT_MILLIS: Long = 10_000L
    const val PREAUTH_TIMEOUT_MILLIS: Long = 15_000L
    const val REQUEST_TIMEOUT_MILLIS: Long = 30_000L
    const val WRITE_TIMEOUT_MILLIS: Long = 60_000L
    const val PING_INTERVAL_MILLIS: Long = 30_000L
    const val STOP_TIMEOUT_MILLIS: Long = 1_000L
}
