package io.xbot.tdlib

/** Thin TDLib JSON client. Native library is packaged from tdlib-kmp's AAR. */
object TdLib {
    fun createClientId(): Int = NativeBridge.createClientId()
    fun send(clientId: Int, request: String) = NativeBridge.send(clientId, request)
    // A new TDLib JSON client emits no updates until it receives its first request.
    internal fun requestAuthorizationState(clientId: Int) =
        send(clientId, """{"@type":"getAuthorizationState"}""")

    fun receive(timeout: Double): String? = NativeBridge.receive(timeout)
    fun execute(request: String): String? = NativeBridge.execute(request)
}
