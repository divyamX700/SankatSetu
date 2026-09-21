package com.sankatsetu.app.mesh.router

/**
 * One live connection to one peer, as seen by [MessageRouter]. The router
 * doesn't know or care whether this is a GATT-server (peripheral) or
 * GATT-client (central) connection — [com.sankatsetu.app.mesh.transport.MeshTransport]
 * owns that distinction and hands the router a flat list of links.
 */
interface MeshLink {
    /** Stable for the lifetime of this connection; not necessarily the peer's mesh peer ID (that's learned via announce). */
    val linkId: String

    /** Best-effort send. Returns false if the link is gone (caller should treat this the same as a disconnect). */
    suspend fun send(bytes: ByteArray): Boolean
}
