package com.conreo.couchytv.data

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/** Skip rejected streams immediately. Transient failures keep Media3's retry behavior. */
@UnstableApi
internal object AerialLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val rejectedPeer = generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }
            .any { it is CertificateException || it is SSLPeerUnverifiedException }
        return if (rejectedPeer) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
    }
}
