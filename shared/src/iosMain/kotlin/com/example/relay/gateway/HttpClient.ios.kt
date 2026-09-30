package com.example.relay.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun defaultHttpClient(): HttpClient = createJsonHttpClient(HttpClient(Darwin))
