package com.nuvio.app.core.uifixture

/** Android preview builds do not read fixture arguments. The gate stays disabled. */
actual fun activeUiFixture(): UiFixture? = null

actual fun uiTestSkipsOnboarding(): Boolean = false

internal actual fun startConnectivityFixturePoller() = Unit
