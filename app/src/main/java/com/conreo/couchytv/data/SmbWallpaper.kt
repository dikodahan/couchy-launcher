package com.conreo.couchytv.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.CIFSContext
import jcifs.SmbConstants
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.dcerpc.DcerpcHandle
import jcifs.dcerpc.msrpc.MsrpcShareEnum
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import jcifs.util.Crypto
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.jcajce.provider.digest.MD4
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Provider
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val SMB_SCHEME = "couchy-smb"
private const val KEY_ALIAS = "couchy.smb.credentials"
private const val IV_SIZE = 12

@Serializable
data class SmbSetup(val server: String, val name: String, val username: String) {
    internal fun isStorable(): Boolean =
        server.isNotBlank() && server.length <= 512 && name.isNotBlank() && name.length <= 128 &&
            username.isNotBlank() && username.length <= 256

    internal fun isValid(): Boolean = isStorable() && SmbProfile.validServer(server)
}

@Serializable
internal class SmbProfile(val setup: SmbSetup, val password: String, val verified: Boolean = true) {
    val name: String get() = setup.name
    val host: String get() = setup.server.substringBefore('/')
    val share: String get() = setup.server.substringAfter('/', "")
    val id: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(setup.server.lowercase().toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
    }

    val root: String get() = Uri.Builder().scheme(SMB_SCHEME).authority(id).build().toString()

    companion object {
        fun validServer(value: String): Boolean = runCatching {
            val uri = URI("smb://${value.substringBefore('/')}")
            val authority = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
            val share = value.substringAfter('/', "")
            value.length <= 512 && uri.host != null && (uri.port == -1 || uri.port in 1..65535) &&
                uri.rawAuthority == authority && uri.path.isEmpty() &&
                uri.rawQuery == null && uri.rawFragment == null &&
                ('/' !in value || (validSegment(share) && '#' !in share))
        }.getOrDefault(false)
    }
}

private fun validSegment(segment: String): Boolean =
    segment.isNotBlank() && segment != "." && segment != ".." &&
        segment.none { it == '/' || it == '\\' || it == '?' || it.isISOControl() }

internal object SmbProfiles {
    private fun credentials(context: Context) = AtomicFile(File(context.noBackupFilesDir, "smb-credentials"))

    @Synchronized
    fun read(context: Context): SmbProfile? = try {
        val bytes = credentials(context).readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
        Json.decodeFromString<SmbProfile>(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).decodeToString())
    } catch (_: FileNotFoundException) {
        null
    }

    fun matching(context: Context, setup: SmbSetup?): SmbProfile? {
        if (setup == null || !setup.isStorable()) return null
        val saved = read(context) ?: return null
        if (!saved.setup.server.equals(setup.server, ignoreCase = true) || saved.setup.username != setup.username) return null
        return SmbProfile(setup, saved.password, saved.verified && setup.isValid())
    }

    @Synchronized
    fun save(context: Context, profile: SmbProfile) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == IV_SIZE)
        val encrypted = cipher.iv + cipher.doFinal(Json.encodeToString(SmbProfile.serializer(), profile).encodeToByteArray())
        val file = credentials(context)
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun remove(context: Context) = credentials(context).delete()

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }
}

internal data class SmbEntry(val uri: String, val name: String, val folder: Boolean)

internal fun isSmbWallpaper(source: String): Boolean = source.startsWith("$SMB_SCHEME://")

// Register the NTLM digest in jCIFS only. Its constructor stays reachable in minified
// builds, and the app does not change Android's process-wide security providers.
private val smbCrypto = lazy {
    Crypto.initProvider(object : Provider("CouchySmb", 1.0, "SMB NTLM digest") {
        init {
            putService(object : Service(this, "MessageDigest", "MD4", MD4.Digest::class.java.name,
                emptyList(), emptyMap()) {
                override fun newInstance(constructorParameter: Any?): Any = MD4.Digest()
            })
        }
    })
}

internal class SmbConnection(private val profile: SmbProfile) : AutoCloseable {
    private val base = run {
        smbCrypto.value
        BaseContext(PropertyConfiguration(Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB210")
            setProperty("jcifs.smb.client.useSMB2Negotiation", "true")
            setProperty("jcifs.smb.client.signingEnforced", "true")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.smb.client.connTimeout", "5000")
            setProperty("jcifs.smb.client.responseTimeout", "6000")
            setProperty("jcifs.smb.client.soTimeout", "12000")
            setProperty("jcifs.smb.client.maxRequestRetries", "1")
            setProperty("jcifs.resolveOrder", "DNS")
        }))
    }
    private val user = profile.setup.username.substringAfter('\\')
    private val domain = profile.setup.username.substringBefore('\\', "")
    private val context: CIFSContext = base.withCredentials(NtlmPasswordAuthenticator(domain, user, profile.password))

    private fun open(source: String, folder: Boolean): SmbFile {
        val uri = Uri.parse(source)
        val segments = uri.pathSegments
        require(uri.scheme == SMB_SCHEME && uri.encodedAuthority == profile.id && uri.query == null && uri.fragment == null)
        require(segments.all(::validSegment))
        val parts = if (profile.share.isEmpty()) segments else listOf(profile.share) + segments
        val path = parts.joinToString("/")
        val suffix = if (folder && path.isNotEmpty()) "/" else ""
        return SmbFile("smb://${profile.host}/$path$suffix", context)
    }

    private fun shares(): List<SmbEntry> {
        val host = URI("smb://${profile.host}")
        val names = if (host.port == -1) {
            open(profile.root, folder = true).use { dir ->
                dir.children().use { children ->
                    buildList {
                        while (children.hasNext()) children.next().use { child ->
                            if (child.type == SmbConstants.TYPE_SHARE) add(child.name.trimEnd('/'))
                        }
                    }
                }
            }
        } else {
            // The library's automatic discovery opens the RPC pipe on port 445.
            DcerpcHandle.getHandle("ncacn_np:${profile.host}[\\PIPE\\srvsvc]", context).use { rpc ->
                val shares = MsrpcShareEnum(host.host)
                rpc.sendrecv(shares)
                if (shares.retval != 0) throw IOException("Could not enumerate SMB shares")
                shares.entries.filter { it.type == SmbConstants.TYPE_SHARE }.map { it.name }
            }
        }
        return names.filter { validSegment(it) && !it.endsWith('$') }.sortedBy { it.lowercase() }.map { name ->
            val uri = Uri.parse(profile.root).buildUpon().appendPath(name).build().toString()
            SmbEntry(uri, name, folder = true)
        }
    }

    suspend fun list(source: String, photo: Boolean = false): List<SmbEntry> {
        if (source == profile.root && profile.share.isEmpty()) return shares()
        val job = currentCoroutineContext()
        return open(source, folder = true).use { dir ->
            buildList {
                dir.children().use { children ->
                    while (children.hasNext()) {
                        job.ensureActive()
                        children.next().use child@{ child ->
                            val name = child.name.trimEnd('/')
                            if (!validSegment(name)) return@child
                            val folder = child.isDirectory
                            val supported = if (photo) isWallpaperPhoto(name) else isWallpaperVideoDocument("", name)
                            if (folder || supported) {
                                val uri = Uri.parse(source).buildUpon().appendPath(name).build().toString()
                                add(SmbEntry(uri, name, folder))
                            }
                        }
                    }
                }
            }
        }.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))
    }

    suspend fun validate() {
        if (profile.share.isNotEmpty()) {
            list(profile.root)
            return
        }
        // Enumeration alone can succeed through a guest IPC session. Open a disk share too.
        val job = currentCoroutineContext()
        if (shares().none { share ->
            job.ensureActive()
            try {
                open(share.uri, folder = true).use { it.children().use { children -> children.hasNext() } }
                true
            } catch (_: IOException) {
                false
            }
        }) throw IOException("No readable SMB share")
    }

    suspend fun <T> readPhoto(source: String, reader: suspend (InputStream) -> T): T =
        open(source, folder = false).use { it.openInputStream().use { input -> reader(input) } }

    fun openVideo(source: String): SmbRandomAccessFile = open(source, folder = false).use { it.openRandomAccess("r") }

    override fun close() {
        base.close()
    }
}

@UnstableApi
internal class SmbVideoDataSource(private val context: Context) : BaseDataSource(true) {
    private var connection: SmbConnection? = null
    private var file: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            val profile = SmbProfiles.read(context)?.takeIf { it.verified }
                ?: throw IOException("SMB setup is unverified")
            val smb = SmbConnection(profile).also { connection = it }
            val video = smb.openVideo(dataSpec.uri.toString()).also { file = it }
            val length = video.length() - dataSpec.position
            if (length < 0) throw IOException("Invalid SMB playback position")
            video.seek(dataSpec.position)
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) length else minOf(length, dataSpec.length)
            uri = dataSpec.uri
            transferStarted(dataSpec)
            return remaining
        } catch (_: Exception) {
            close()
            throw IOException("Could not open SMB video")
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = try {
            file?.read(buffer, offset, minOf(length.toLong(), remaining).toInt()) ?: C.RESULT_END_OF_INPUT
        } catch (_: IOException) {
            throw IOException("Could not read SMB video")
        }
        if (count > 0) {
            remaining -= count
            bytesTransferred(count)
        }
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { file?.close() }
        runCatching { connection?.close() }
        file = null
        connection = null
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    companion object {
        fun factory(context: Context) = DataSource.Factory { SmbVideoDataSource(context.applicationContext) }
    }
}
