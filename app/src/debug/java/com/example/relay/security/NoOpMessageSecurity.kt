package com.example.relay.security

/**
 * Test-only stand-ins. They are deliberately compiled only into debug APKs;
 * release code cannot accidentally accept unsigned messages through this path.
 */
@Deprecated("Debug test double only; never use for release trust decisions")
object NoOpMessageSigner : MessageSigner {
    override suspend fun sign(message: ByteArray): ByteArray = byteArrayOf()
}

@Deprecated("Debug test double only; never use for release trust decisions")
object NoOpMessageVerifier : MessageVerifier {
    override suspend fun verify(message: ByteArray, signature: ByteArray?): Boolean = true
}
