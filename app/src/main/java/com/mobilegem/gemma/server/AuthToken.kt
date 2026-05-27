package com.mobilegem.gemma.server

import java.util.UUID

/**
 * A random per-process token used to authenticate requests from the in-app
 * WebView to the local HTTP server. Generated once at [AppContainer]
 * construction; never persisted; rotates on every app launch.
 */
class AuthToken(val value: String = UUID.randomUUID().toString())
