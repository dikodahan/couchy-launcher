package com.conreo.couchytv.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalAdbTest {
    private class TestKey : AdbAuthKey {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        var signatures = 0
        override fun signToken(token: ByteArray): ByteArray {
            signatures++
            return Signature.getInstance("NONEwithRSA").run {
                initSign(pair.private); update(AdbRsaAuth.SHA1_DIGEST_INFO); update(token); sign()
            }
        }
        override fun publicKeyPacket() = AdbRsaAuth.publicKeyPacket(pair.public as RSAPublicKey)
    }

    @Test fun packetHeaderPayloadAndChecksumsRoundTrip() {
        val bytes = encoded(AdbPacket(AdbWire.WRTE, 91, 1, "hello".toByteArray()))
        val packet = AdbWire.read(ByteArrayInputStream(bytes), true)
        assertEquals(AdbWire.WRTE, packet.command); assertEquals(91, packet.arg0)
        assertEquals("hello", packet.payload.toString(Charsets.UTF_8))
        val corruptPayload = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertTrue(runCatching { AdbWire.read(ByteArrayInputStream(corruptPayload), true) }.exceptionOrNull() is IOException)
        val corruptMagic = bytes.copyOf().apply { this[20] = (this[20].toInt() xor 1).toByte() }
        assertTrue(runCatching { AdbWire.read(ByteArrayInputStream(corruptMagic), false) }.exceptionOrNull() is IOException)
    }

    @Test fun packetSizeIsRejectedBeforeReadingOrAllocatingPayload() {
        val bytes = encoded(AdbPacket(AdbWire.WRTE, 1, 2))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, AdbWire.MAX_PACKET_BYTES + 1)
        val failure = runCatching { AdbWire.read(ByteArrayInputStream(bytes), false) }.exceptionOrNull()
        assertTrue(failure is IOException); assertTrue(failure!!.message!!.contains("payload limit"))
    }

    @Test fun checksumSkipNegotiationStillRejectsBadNonzeroChecksums() {
        val bytes = encoded(AdbPacket(AdbWire.WRTE, 1, 2, byteArrayOf(1, 2, 3)))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0)
        assertArrayEquals(byteArrayOf(1, 2, 3), AdbWire.read(ByteArrayInputStream(bytes), false).payload)
        assertTrue(runCatching { AdbWire.read(ByteArrayInputStream(bytes), true) }.isFailure)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 5)
        assertTrue(runCatching { AdbWire.read(ByteArrayInputStream(bytes), false) }.isFailure)
    }

    @Test fun androidPublicKeyUsesTheAdbRsaStruct() {
        val key = TestKey().pair.public as RSAPublicKey
        val packet = AdbRsaAuth.publicKeyPacket(key).toString(Charsets.US_ASCII)
        assertTrue(packet.endsWith(" couchy@android\u0000"))
        val struct = Base64.getDecoder().decode(packet.substringBefore(' '))
        assertEquals(524, struct.size)
        val view = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, view.int)
        val n0inv = view.int.toLong() and 0xffffffffL
        val modulus = ByteArray(256).also { view.get(it) }.reversedArray()
        val rr = ByteArray(256).also { view.get(it) }.reversedArray()
        assertEquals(65537, view.int)
        assertEquals(key.modulus, BigInteger(1, modulus))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(key.modulus), BigInteger(1, rr))
        val two32 = BigInteger.ONE.shiftLeft(32)
        assertEquals(two32.subtract(BigInteger.ONE), key.modulus.multiply(BigInteger.valueOf(n0inv)).mod(two32))
    }

    @Test fun tokenSignaturePublicKeyPromptAndSplitShellOutputAreHandled() = runBlocking {
        val key = TestKey()
        fakeServer(handler = { socket ->
            val input = socket.getInputStream(); val output = socket.getOutputStream()
            val connect = AdbWire.read(input, false)
            assertEquals(AdbWire.CNXN, connect.command)
            val token = ByteArray(20) { it.toByte() }
            AdbWire.write(output, AdbPacket(AdbWire.AUTH, 1, 0, token))
            val signed = AdbWire.read(input, false)
            assertEquals(2, signed.arg0)
            assertTrue(Signature.getInstance("NONEwithRSA").run {
                initVerify(key.pair.public); update(AdbRsaAuth.SHA1_DIGEST_INFO); update(token); verify(signed.payload)
            })
            AdbWire.write(output, AdbPacket(AdbWire.AUTH, 1, 0, token.reversedArray()))
            val public = AdbWire.read(input, false)
            assertEquals(3, public.arg0); assertArrayEquals(key.publicKeyPacket(), public.payload)
            authorize(output)
            val open = AdbWire.read(input, false)
            assertEquals(AdbWire.OPEN, open.command)
            val marker = Regex("__COUCHY_EXIT_[a-f0-9]+:").find(open.payload.toString(Charsets.UTF_8))!!.value
            AdbWire.write(output, AdbPacket(AdbWire.OKAY, 91, open.arg0))
            val response = "selected launcher\n\n${marker}7\n".toByteArray()
            for (part in listOf(response.copyOfRange(0, response.size - 3), response.copyOfRange(response.size - 3, response.size))) {
                AdbWire.write(output, AdbPacket(AdbWire.WRTE, 91, open.arg0, part))
                assertEquals(AdbWire.OKAY, AdbWire.read(input, false).command)
            }
            AdbWire.write(output, AdbPacket(AdbWire.CLSE, 91, open.arg0))
            assertEquals(AdbWire.CLSE, AdbWire.read(input, false).command)
        }) { port ->
            val result = LocalAdb(key, port).shell("printf 'selected launcher\\n'; false")
            assertEquals(7, result.exitCode); assertEquals("selected launcher\n", result.output)
        }
        assertEquals(1, key.signatures)
    }


    @Test fun sequenceUsesOneConnectionInOrderAndStopsAtFirstFailure() = runBlocking {
        fakeServer(handler = { socket ->
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            assertEquals(AdbWire.CNXN, AdbWire.read(input, false).command)
            authorize(output)
            for ((index, status) in listOf(0, 7).withIndex()) {
                val open = AdbWire.read(input, false)
                val id = index + 1
                val remoteId = 90 + id
                assertEquals(AdbWire.OPEN, open.command)
                assertEquals(id, open.arg0)
                val service = open.payload.decodeToString()
                assertTrue(service.contains("\ngetprop step$id\n"))
                val marker = Regex("__COUCHY_EXIT_[a-f0-9]+:").find(service)!!.value
                AdbWire.write(output, AdbPacket(AdbWire.OKAY, remoteId, id))
                AdbWire.write(output, AdbPacket(AdbWire.WRTE, remoteId, id, "$id\n$marker$status\n".toByteArray()))
                assertEquals(AdbWire.OKAY, AdbWire.read(input, false).command)
                AdbWire.write(output, AdbPacket(AdbWire.CLSE, remoteId, id))
                assertEquals(AdbWire.CLSE, AdbWire.read(input, false).command)
                if (index == 0) AdbWire.write(output, AdbPacket(AdbWire.CLSE, remoteId, id))
            }
            assertEquals(-1, input.read())
        }) { port ->
            val results = LocalAdb(TestKey(), port).shellSequence((1..3).map { "getprop step$it" })
            assertEquals(listOf(AdbShellResult(0, "1"), AdbShellResult(7, "2")), results)
        }
    }

    @Test fun missingExitMarkerCannotReportSuccess() = runBlocking {
        val key = TestKey()
        fakeServer(handler = { socket ->
            val input = socket.getInputStream(); val output = socket.getOutputStream()
            AdbWire.read(input, false); authorize(output)
            val open = AdbWire.read(input, false)
            AdbWire.write(output, AdbPacket(AdbWire.OKAY, 91, open.arg0))
            AdbWire.write(output, AdbPacket(AdbWire.WRTE, 91, open.arg0, "truncated".toByteArray()))
            AdbWire.read(input, false)
            AdbWire.write(output, AdbPacket(AdbWire.CLSE, 91, open.arg0)); AdbWire.read(input, false)
        }) { port ->
            val error = runCatching { LocalAdb(key, port).shell("true") }.exceptionOrNull()
            assertTrue(error is IOException); assertTrue(error!!.message!!.contains("exit status"))
        }
        assertEquals(0, key.signatures)
    }

    @Test fun responseCapAndInvalidCommandBoundsFailClosed() = runBlocking {
        val key = TestKey()
        fakeServer(handler = { socket ->
            val input = socket.getInputStream(); val output = socket.getOutputStream()
            AdbWire.read(input, false); authorize(output)
            val open = AdbWire.read(input, false)
            AdbWire.write(output, AdbPacket(AdbWire.OKAY, 91, open.arg0))
            AdbWire.write(output, AdbPacket(AdbWire.WRTE, 91, open.arg0, ByteArray(256 * 1024 + 1)))
        }) { port ->
            assertTrue(runCatching { LocalAdb(key, port).shell("true") }.exceptionOrNull() is IOException)
        }
        assertTrue(runCatching { LocalAdb(key, 1).shell("x".repeat(8193)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { LocalAdb(key, 1).shell("true\u0000false") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun cancellationClosesAnUnresponsiveAuthenticationSocket() = runBlocking {
        val key = TestKey()
        val connected = CountDownLatch(1)
        fakeServer(handler = { socket ->
            AdbWire.read(socket.getInputStream(), false)
            connected.countDown()
            assertEquals(-1, socket.getInputStream().read())
        }) { port ->
            val task = async(Dispatchers.Default) { LocalAdb(key, port).shell("true") }
            assertTrue(connected.await(3, TimeUnit.SECONDS))
            val started = System.nanoTime()
            task.cancelAndJoin()
            assertTrue(task.isCancelled)
            assertTrue("Cancellation must not wait for the authorization timeout", System.nanoTime() - started < 2_000_000_000L)
        }
    }

    @Test fun authenticationAndOperationDeadlinesAreBounded() = runBlocking {
        val key = TestKey()
        fakeServer(handler = { socket ->
            AdbWire.read(socket.getInputStream(), false)
            assertEquals(-1, socket.getInputStream().read())
        }) { port ->
            val started = System.nanoTime()
            val failure = runCatching { LocalAdb(key, port, authTimeoutMs = 150).shell("true") }.exceptionOrNull()
            assertTrue(failure is java.net.SocketTimeoutException)
            assertTrue(System.nanoTime() - started < 2_500_000_000L)
        }
        fakeServer(handler = { socket ->
            val input = socket.getInputStream(); val output = socket.getOutputStream()
            AdbWire.read(input, false); authorize(output)
            val open = AdbWire.read(input, false)
            AdbWire.write(output, AdbPacket(AdbWire.OKAY, 91, open.arg0))
            assertEquals(-1, input.read())
        }) { port ->
            val failure = runCatching { LocalAdb(key, port, operationTimeoutMs = 150).shell("true") }.exceptionOrNull()
            assertTrue(failure is java.net.SocketTimeoutException)
        }
    }

    @Test fun overallDeadlineClosesABlockedPacketWriter() = runBlocking {
        val closed = CountDownLatch(1)
        val writing = CountDownLatch(1)
        val socket = object : Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) = Unit
            override fun setSoTimeout(timeout: Int) = Unit
            override fun setTcpNoDelay(enabled: Boolean) = Unit
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream() = object : java.io.OutputStream() {
                override fun write(value: Int) {
                    writing.countDown()
                    // Simulate a peer that never reads: SO_TIMEOUT does not cover socket writes.
                    if (!closed.await(10, TimeUnit.SECONDS)) throw IOException("Test writer was not cancelled")
                    throw java.net.SocketException("Closed by cancellation")
                }
            }
            override fun close() { closed.countDown() }
        }
        val key = TestKey()
        val started = System.nanoTime()
        val failure = runCatching {
            LocalAdb(key, 1, socketFactory = { socket }, authTimeoutMs = 100, operationTimeoutMs = 100).shell("true")
        }.exceptionOrNull()
        assertTrue(writing.await(0, TimeUnit.SECONDS))
        assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(0L, closed.count)
        assertTrue("A blocked writer must obey the complete operation budget", System.nanoTime() - started < 8_000_000_000L)
    }

    private fun authorize(output: java.io.OutputStream) =
        AdbWire.write(output, AdbPacket(AdbWire.CNXN, AdbWire.VERSION, AdbWire.MAX_PACKET_BYTES, "device::\u0000".toByteArray()))

    private fun encoded(packet: AdbPacket) = ByteArrayOutputStream().also { AdbWire.write(it, packet) }.toByteArray()

    private suspend fun fakeServer(handler: (Socket) -> Unit, block: suspend (Int) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val accepted = AtomicReference<Socket?>()
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.submit {
            server.accept().also { accepted.set(it); it.soTimeout = 4_000 }.use(handler)
        }
        try { block(server.localPort); worker.get(5, TimeUnit.SECONDS) }
        finally {
            accepted.get()?.close(); server.close(); worker.cancel(true); executor.shutdownNow()
        }
    }
}
