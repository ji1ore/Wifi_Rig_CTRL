package com.ji1ore.wifi_rig_ctrl

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

private const val TAG = "LocalPiProxy"

internal fun computeCertFingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        .joinToString("") { "%02x".format(it) }

private class PinningTrustManager(initialPinned: String) : X509TrustManager {
    @Volatile var pinnedFp: String = initialPinned
    @Volatile var lastSeenFp: String = ""

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val fp = computeCertFingerprint(chain[0])
        lastSeenFp = fp
        if (pinnedFp.isEmpty() || fp != pinnedFp)
            throw CertificateException("cert not pinned/mismatch: seen=$fp pinned=$pinnedFp")
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class LocalPiProxy(
    private val piBaseUrl: String,
    initialPinnedFp: String,
    private val onCertError: (fingerprint: String, onAccept: (String) -> Unit, onCancel: () -> Unit) -> Unit
) {
    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort

    private val tm = PinningTrustManager(initialPinnedFp)
    private val okClient: OkHttpClient
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false
    @Volatile private var certErrorReported = false

    init {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        okClient = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun updatePinnedCert(fp: String) {
        tm.pinnedFp = fp
        certErrorReported = false
    }

    fun start() {
        running = true
        executor.execute {
            while (running) {
                try {
                    val socket = serverSocket.accept()
                    executor.execute { handleConnection(socket) }
                } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }

    private fun handleConnection(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val requestLine = readHttpLine(input).trim()
            if (requestLine.isEmpty()) return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readHttpLine(input)
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes: ByteArray? = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var offset = 0
                while (offset < contentLength) {
                    val n = input.read(buf, offset, contentLength - offset)
                    if (n < 0) break
                    offset += n
                }
                buf
            } else null

            val reqBuilder = Request.Builder().url("$piBaseUrl$path")
            headers.forEach { (k, v) ->
                if (k !in setOf("host", "connection", "transfer-encoding", "keep-alive")) {
                    try { reqBuilder.addHeader(k, v) } catch (_: Exception) {}
                }
            }
            val reqBody = when {
                bodyBytes != null -> {
                    val ct = headers["content-type"] ?: "application/octet-stream"
                    bodyBytes.toRequestBody(ct.toMediaType())
                }
                method == "POST" -> ByteArray(0).toRequestBody(null)
                else -> null
            }
            reqBuilder.method(method, reqBody)

            try {
                val response = okClient.newCall(reqBuilder.build()).execute()
                certErrorReported = false
                forwardResponse(output, response)
            } catch (e: SSLException) {
                val fp = tm.lastSeenFp
                Log.w(TAG, "SSL error for $path: ${e.message}, fp=$fp")
                sendError(output, 503, "SSL Certificate Error")
                if (fp.isNotEmpty() && !certErrorReported) {
                    certErrorReported = true
                    onCertError(fp, { newFp -> updatePinnedCert(newFp) }, { certErrorReported = false })
                }
            } catch (e: Exception) {
                Log.w(TAG, "proxy error for $path: ${e.message}")
                sendError(output, 502, "Bad Gateway")
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun forwardResponse(out: OutputStream, response: okhttp3.Response) {
        response.use { r ->
            out.write("HTTP/1.1 ${r.code} ${r.message.ifEmpty { "OK" }}\r\n".toByteArray())
            out.write("Connection: close\r\n".toByteArray())
            // Force no-store for JS and HTML so a Pi webft8 update is always served fresh.
            // WASM is large and stable, so it is allowed to cache normally.
            val path = r.request.url.encodedPath
            val noStore = path.endsWith(".js") || path == "/" || path == "/index.html"
                    || path.startsWith("/index.html?")
            r.headers.forEach { (k, v) ->
                val lk = k.lowercase()
                if (lk !in setOf("transfer-encoding", "connection") &&
                    !(noStore && lk == "cache-control")) {
                    out.write("$k: $v\r\n".toByteArray())
                }
            }
            if (noStore) out.write("Cache-Control: no-store\r\n".toByteArray())
            out.write("\r\n".toByteArray())
            out.flush()
            r.body?.byteStream()?.copyTo(out, bufferSize = 4096)
            out.flush()
        }
    }

    private fun sendError(out: OutputStream, code: Int, msg: String) {
        try {
            val body = "<html><body>$msg</body></html>"
            out.write(
                ("HTTP/1.1 $code $msg\r\nContent-Type: text/html\r\n" +
                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
                    .toByteArray()
            )
            out.flush()
        } catch (_: Exception) {}
    }
}

private fun readHttpLine(input: InputStream): String {
    val sb = StringBuilder()
    var prev = -1
    while (true) {
        val b = input.read()
        if (b == -1) break
        if (b == '\n'.code && prev == '\r'.code) {
            if (sb.isNotEmpty()) sb.deleteCharAt(sb.lastIndex)
            break
        }
        sb.append(b.toChar())
        prev = b
    }
    return sb.toString()
}