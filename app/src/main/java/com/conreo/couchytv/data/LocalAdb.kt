package com.conreo.couchytv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

internal data class AdbShellResult(val exitCode: Int, val output: String)

/**
 * One authenticated connection to this TV's existing adbd, created only by an explicit shell call.
 * There is no discovery, remote-host option, pairing bypass or bundled ADB executable.
 */
internal class LocalAdb private constructor(
    private val keyProvider: () -> AdbAuthKey,
    private val socketFactory: () -> Socket,
    private val port: Int,
    private val authTimeoutMs: Long,
    private val operationTimeoutMs: Long,
) {
    constructor(context: Context) : this(
        { AndroidAdbAuthKey(context.applicationContext.packageName + ".local-adb.v1") },
        ::Socket, 5555, 45_000, 15_000,
    )

    /** Test-only injection keeps the destination loopback-only, including in fake-server tests. */
    internal constructor(
        key: AdbAuthKey,
        port: Int,
        socketFactory: () -> Socket = ::Socket,
        authTimeoutMs: Long = 45_000,
        operationTimeoutMs: Long = 15_000,
    ) : this({ key }, socketFactory, port, authTimeoutMs, operationTimeoutMs)

    private val key by lazy(keyProvider)

    suspend fun shell(command: String): AdbShellResult {
        require(command.isNotBlank() && '\u0000' !in command) { "Invalid ADB command" }
        require(command.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES) { "ADB command is too long" }
        return withTimeout(CONNECT_TIMEOUT_MS.toLong() + authTimeoutMs + operationTimeoutMs + TIMEOUT_HEADROOM_MS) {
            withContext(Dispatchers.IO) {
                // The cancellation handler closes a blocked socket immediately; read polling also checks
                // cancellation/deadlines every second. No detached worker survives the calling coroutine.
                suspendCancellableCoroutine { continuation ->
                    val socket = socketFactory()
                    continuation.invokeOnCancellation { runCatching { socket.close() } }
                    try {
                        continuation.context.ensureActive()
                        socket.soTimeout = READ_POLL_MS
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), CONNECT_TIMEOUT_MS)
                        val session = AdbSession(socket.getInputStream(), socket.getOutputStream(), continuation.context)
                        val result = session.shell(command, { key }, authTimeoutMs, operationTimeoutMs)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (cancelled: CancellationException) {
                        continuation.cancel(cancelled)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val TIMEOUT_HEADROOM_MS = 1_000L
        const val MAX_COMMAND_BYTES = 8 * 1024
        const val READ_POLL_MS = 1_000
    }
}

/** AOSP ADB protocol packets. The payload bound is checked before allocating a buffer. */
internal data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = byteArrayOf(),
    val wireChecksum: Int = AdbWire.checksum(payload),
)

internal object AdbWire {
    const val VERSION = 0x01000001
    const val VERSION_LEGACY = 0x01000000
    const val MAX_PACKET_BYTES = 1024 * 1024
    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val WRTE = 0x45545257
    const val CLSE = 0x45534c43

    fun checksum(bytes: ByteArray): Int = bytes.fold(0) { sum, byte -> sum + (byte.toInt() and 0xff) }

    fun write(output: OutputStream, packet: AdbPacket) {
        if (packet.payload.size > MAX_PACKET_BYTES) throw IOException("ADB packet is too large")
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(packet.command).putInt(packet.arg0).putInt(packet.arg1)
            .putInt(packet.payload.size).putInt(checksum(packet.payload)).putInt(packet.command.inv()).array()
        output.write(header)
        output.write(packet.payload)
        output.flush()
    }

    fun read(input: InputStream, requireChecksum: Boolean, check: () -> Unit = {}): AdbPacket {
        val header = ByteBuffer.wrap(readExactly(input, 24, check)).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val length = header.int
        val checksum = header.int
        val magic = header.int
        if (magic != command.inv()) throw IOException("Invalid ADB packet magic")
        if (length < 0 || length > MAX_PACKET_BYTES) throw IOException("ADB packet exceeds the payload limit")
        val payload = readExactly(input, length, check)
        val packet = AdbPacket(command, arg0, arg1, payload, checksum)
        checkChecksum(packet, requireChecksum)
        return packet
    }

    fun checkChecksum(packet: AdbPacket, required: Boolean) {
        // Revision 0x01000001 permits zero checksums; a nonzero checksum is still checked.
        if ((required || packet.wireChecksum != 0) && packet.wireChecksum != checksum(packet.payload))
            throw IOException("Invalid ADB packet checksum")
    }

    private fun readExactly(input: InputStream, length: Int, check: () -> Unit): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            check()
            try {
                val count = input.read(bytes, offset, length - offset)
                if (count < 0) throw EOFException("Local ADB connection closed")
                if (count > 0) offset += count
            } catch (_: SocketTimeoutException) {
                check()
            }
        }
        check()
        return bytes
    }
}

private class AdbSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val context: CoroutineContext,
) {
    private var legacyChecksums = false

    fun shell(command: String, keyProvider: () -> AdbAuthKey, authTimeoutMs: Long, operationTimeoutMs: Long): AdbShellResult {
        send(AdbWire.CNXN, AdbWire.VERSION, AdbWire.MAX_PACKET_BYTES, "host::\u0000".toByteArray())
        val authDeadline = deadline(authTimeoutMs)
        var authResponses = 0
        var remoteMax = 0
        while (remoteMax == 0) {
            val packet = read(authDeadline)
            when (packet.command) {
                AdbWire.AUTH -> {
                    if (packet.arg0 != 1 || packet.arg1 != 0 || packet.payload.size != 20)
                        throw IOException("Invalid local ADB authentication challenge")
                    val key = keyProvider()
                    when (authResponses++) {
                        0 -> send(AdbWire.AUTH, 2, 0, key.signToken(packet.payload))
                        1 -> send(AdbWire.AUTH, 3, 0, key.publicKeyPacket())
                        else -> throw IOException("Local ADB authorization was not accepted")
                    }
                }
                AdbWire.CNXN -> {
                    if (packet.arg0 < AdbWire.VERSION_LEGACY || packet.arg1 !in 1..AdbWire.MAX_PACKET_BYTES)
                        throw IOException("Unsupported local ADB protocol")
                    legacyChecksums = packet.arg0 < AdbWire.VERSION
                    AdbWire.checkChecksum(packet, legacyChecksums)
                    remoteMax = packet.arg1
                }
                else -> throw IOException("Local ADB connection was not authorized")
            }
        }

        val operationDeadline = deadline(operationTimeoutMs)
        val marker = "__COUCHY_EXIT_${UUID.randomUUID().toString().replace("-", "")}:"
        // Run in a subshell so an explicit exit or shell state change cannot skip the exit marker.
        val script = "($command)\n__couchy_exit=\$?\nprintf '\\n${marker}%s\\n' \"\$__couchy_exit\""
        val service = ("shell:" + script + '\u0000').toByteArray(Charsets.UTF_8)
        if (service.size > remoteMax) throw IOException("Command exceeds this ADB daemon's service limit")
        val localId = 1
        send(AdbWire.OPEN, localId, 0, service)
        val opened = read(operationDeadline)
        if (opened.command != AdbWire.OKAY || opened.arg0 == 0 || opened.arg1 != localId || opened.payload.isNotEmpty())
            throw IOException("Local ADB shell service was refused")
        val remoteId = opened.arg0
        val response = ByteArrayOutputStream()
        while (true) {
            val packet = read(operationDeadline)
            if (packet.arg0 != remoteId || packet.arg1 != localId)
                throw IOException("Local ADB shell stream did not match")
            when (packet.command) {
                AdbWire.WRTE -> {
                    if (response.size() + packet.payload.size > MAX_RESPONSE_BYTES)
                        throw IOException("Local ADB response exceeds the output limit")
                    response.write(packet.payload)
                    send(AdbWire.OKAY, localId, remoteId)
                }
                AdbWire.CLSE -> {
                    if (packet.payload.isNotEmpty()) throw IOException("Invalid local ADB close packet")
                    send(AdbWire.CLSE, localId, remoteId)
                    return parseResult(response.toString("UTF-8"), marker)
                }
                else -> throw IOException("Unexpected local ADB shell packet")
            }
        }
    }

    private fun parseResult(text: String, marker: String): AdbShellResult {
        val match = Regex("\\r?\\n${Regex.escape(marker)}([0-9]{1,3})\\r?\\n?\\z").find(text)
            ?: throw IOException("Local ADB shell did not report an exit status")
        val exitCode = match.groupValues[1].toInt()
        if (exitCode !in 0..255) throw IOException("Invalid local ADB exit status")
        return AdbShellResult(exitCode, text.substring(0, match.range.first))
    }

    private fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray = byteArrayOf()) {
        context.ensureActive()
        AdbWire.write(output, AdbPacket(command, arg0, arg1, payload))
    }

    private fun read(deadline: Long): AdbPacket = AdbWire.read(input, legacyChecksums) {
        context.ensureActive()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Local ADB operation interrupted")
        if (System.nanoTime() >= deadline) throw SocketTimeoutException("Local ADB authorization or operation timed out")
    }

    private fun deadline(timeoutMs: Long): Long = System.nanoTime() + timeoutMs * 1_000_000L

    private companion object { const val MAX_RESPONSE_BYTES = 256 * 1024 }
}

internal interface AdbAuthKey {
    fun signToken(token: ByteArray): ByteArray
    fun publicKeyPacket(): ByteArray
}

/** AndroidKeyStore private material stays in the platform provider and is never serialized. */
internal class AndroidAdbAuthKey(alias: String) : AdbAuthKey {
    private val entry = loadOrCreate(alias)
    private val publicKey = entry.certificate.publicKey as RSAPublicKey

    override fun signToken(token: ByteArray): ByteArray {
        require(token.size == 20) { "ADB authentication token must contain 20 bytes" }
        return Signature.getInstance("NONEwithRSA").run {
            initSign(entry.privateKey)
            update(AdbRsaAuth.SHA1_DIGEST_INFO)
            update(token)
            sign()
        }
    }

    override fun publicKeyPacket(): ByteArray = AdbRsaAuth.publicKeyPacket(publicKey)

    private companion object {
        @Synchronized
        fun loadOrCreate(alias: String): KeyStore.PrivateKeyEntry {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setUserAuthenticationRequired(false)
                    .build())
                generateKeyPair()
            }
            return store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                ?: throw IOException("AndroidKeyStore could not retain the local ADB key")
        }
    }
}

internal object AdbRsaAuth {
    val SHA1_DIGEST_INFO = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    private val TWO_32 = BigInteger.ONE.shiftLeft(32)

    /** Android's 524-byte RSAPublicKey struct, not an X.509 SubjectPublicKeyInfo. */
    fun publicKeyPacket(key: RSAPublicKey): ByteArray {
        require(key.modulus.bitLength() == 2048 && key.publicExponent == BigInteger.valueOf(65537)) {
            "ADB requires a 2048-bit RSA key with exponent 65537"
        }
        val modulus = key.modulus
        val n0inv = modulus.mod(TWO_32).modInverse(TWO_32).negate().mod(TWO_32).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(modulus)
        val encoded = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64).putInt(n0inv).put(littleEndian(modulus)).put(littleEndian(rr))
            .putInt(65537).array()
        return (Base64.getEncoder().encodeToString(encoded) + " couchy@android\u0000").toByteArray(Charsets.US_ASCII)
    }

    private fun littleEndian(number: BigInteger): ByteArray {
        val bytes = number.toByteArray()
        return ByteArray(256) { index -> bytes.getOrElse(bytes.lastIndex - index) { 0 } }
    }
}
