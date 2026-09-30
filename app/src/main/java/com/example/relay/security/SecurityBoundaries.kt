package com.example.relay.security

interface MessageSigner { suspend fun sign(message: ByteArray): ByteArray }
interface MessageVerifier { suspend fun verify(message: ByteArray, signature: ByteArray?): Boolean }
