package com.minis.ultra.storage

import java.net.URI
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 免 AWS SDK 的 S3 SigV4 预签名（GET/PUT 通用）。
 * 直接塞进 APK，约 3KB 字节码，替换 aws-android-sdk-s3（≈2.4MB）。
 *
 * 用法：
 *   val url = S3Presigner.presign("GET", "http://127.0.0.1:9000", "minis-attachments",
 *       "sessions/2026-11-14/clip.mp4", ak, sk, ttlSec = 3600)
 *   webView.loadUrl(url)          // 支持 Range，视频可拖动进度
 */
object S3Presigner {

    private const val ALGO = "AWS4-HMAC-SHA256"
    private val AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x))
        return sb.toString()
    }

    /** RFC3986：只保留 A-Z a-z 0-9 - _ . ~，其余按 UTF-8 字节 %XX */
    private fun uriEncode(s: String, keepSlash: Boolean = false): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            val safe = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '-' || ch == '_' || ch == '.' || ch == '~' || (keepSlash && ch == '/')
            if (safe) sb.append(ch) else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    fun presign(
        method: String,
        endpoint: String,
        bucket: String,
        key: String,
        accessKey: String,
        secretKey: String,
        region: String = "us-east-1",
        ttlSec: Long = 3600,
        extraQuery: Map<String, String> = emptyMap()
    ): String {
        val uri = URI(endpoint)
        val host = uri.host + if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""

        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val amzDate = now.format(AMZ_DATE)
        val dateStamp = amzDate.substring(0, 8)
        val scope = "$dateStamp/$region/s3/aws4_request"

        val path = "/" + uriEncode(bucket) + "/" + key.split("/").joinToString("/") { uriEncode(it) }

        val params = sortedMapOf(
            "X-Amz-Algorithm" to ALGO,
            "X-Amz-Credential" to "$accessKey/$scope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to ttlSec.toString(),
            "X-Amz-SignedHeaders" to "host"
        )
        extraQuery.forEach { (k, v) -> params[k] = v }
        val query = params.entries.joinToString("&") { "${uriEncode(it.key)}=${uriEncode(it.value)}" }

        val canonicalRequest = buildString {
            append(method.uppercase()).append('\n')
            append(path).append('\n')
            append(query).append('\n')
            append("host:").append(host).append('\n')
            append('\n')
            append("host").append('\n')
            append("UNSIGNED-PAYLOAD")
        }

        val stringToSign = buildString {
            append(ALGO).append('\n')
            append(amzDate).append('\n')
            append(scope).append('\n')
            append(hex(MessageDigest.getInstance("SHA-256").digest(canonicalRequest.toByteArray(Charsets.UTF_8))))
        }

        var k = hmac(("AWS4" + secretKey).toByteArray(Charsets.UTF_8), dateStamp)
        k = hmac(k, region)
        k = hmac(k, "s3")
        k = hmac(k, "aws4_request")
        val signature = hex(hmac(k, stringToSign))

        return "$endpoint$path?$query&X-Amz-Signature=$signature"
    }
}
