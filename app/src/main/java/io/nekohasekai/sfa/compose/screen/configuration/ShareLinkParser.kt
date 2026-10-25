package io.nekohasekai.sfa.compose.screen.configuration

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Converts common proxy share links into self-contained sing-box TUN configurations. */
object ShareLinkParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val supportedSchemes = setOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "tuic")

    data class Result(val name: String, val config: String, val serverCount: Int)

    fun supports(value: String): Boolean = value.trim().substringBefore(":").lowercase() in supportedSchemes

    fun parse(value: String, fallbackName: String = "Imported subscription"): Result {
        val links = subscriptionLinks(value)
        require(links.isNotEmpty()) { "No supported share link found" }
        val outbounds = links.mapIndexed { index, link -> parseLink(link, index) }
        val name = linkName(links.first()).ifBlank { fallbackName }
        return Result(name, configFor(outbounds), outbounds.size)
    }

    fun isSubscription(value: String): Boolean = subscriptionLinks(value).isNotEmpty()

    private fun subscriptionLinks(value: String): List<String> {
        val plain = value.trim()
        val decoded = decodeBase64(plain)
        val candidates = listOfNotNull(plain.takeIf { it.contains("://") }, decoded)
        return candidates
            .flatMap { it.lineSequence().map(String::trim).toList() }
            .filter(::supports)
            .distinct()
    }

    private fun decodeBase64(value: String): String? {
        if (value.contains("://")) return null
        return runCatching {
        val normalized = value.filterNot(Char::isWhitespace).replace('-', '+').replace('_', '/')
        val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
        Base64.getDecoder().decode(padded).toString(StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun parseLink(link: String, index: Int): JsonObject = when (link.substringBefore(":").lowercase()) {
        "vmess" -> parseVmess(link, index)
        "ss" -> parseShadowsocks(link, index)
        else -> parseUriLink(link, index)
    }

    private fun parseUriLink(link: String, index: Int): JsonObject {
        val uri = URI(link)
        val type = uri.scheme.lowercase().let { if (it == "hy2") "hysteria2" else it }
        val query = query(uri.rawQuery)
        val authority = uri.rawAuthority.substringAfterLast('@')
        val host = authority.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = authority.substringAfterLast(':').toIntOrNull() ?: error("Missing server port")
        val userInfo = uri.rawUserInfo?.let(::decode) ?: ""
        require(host.isNotBlank() && userInfo.isNotBlank()) { "Missing server or credentials" }
        return buildJsonObject {
            put("type", type)
            put("tag", "proxy-${index + 1}")
            put("server", host)
            put("server_port", port)
            when (type) {
                "vless" -> put("uuid", userInfo)
                "trojan", "hysteria2" -> put("password", userInfo)
                "tuic" -> {
                    put("uuid", userInfo.substringBefore(':'))
                    (query["password"] ?: userInfo.substringAfter(':', "").takeIf { it.isNotBlank() })?.let {
                        put("password", it)
                    }
                }
            }
            if (type == "vless") query["flow"]?.let { put("flow", it) }
            if (type == "hysteria2") query["obfs"]?.let { obfs ->
                put("obfs", buildJsonObject {
                    put("type", if (obfs == "salamander") "salamander" else obfs)
                    query["obfs-password"]?.let { put("password", it) }
                })
            }
            query["congestion_control"]?.let { put("congestion_control", it) }
            tls(query)?.let { put("tls", it) }
            transport(query)?.let { put("transport", it) }
        }
    }

    private fun parseVmess(link: String, index: Int): JsonObject {
        val payload = link.substringAfter("://")
        val data = decodeBase64(payload) ?: error("Invalid vmess link")
        val object = json.parseToJsonElement(data).jsonObject
        val host = object.string("add")
        val port = object.string("port").toIntOrNull() ?: error("Missing server port")
        return buildJsonObject {
            put("type", "vmess")
            put("tag", "proxy-${index + 1}")
            put("server", host)
            put("server_port", port)
            put("uuid", object.string("id"))
            object.stringOrNull("aid")?.takeIf { it != "0" }?.let { put("alter_id", it.toInt()) }
            object.stringOrNull("scy")?.takeIf { it.isNotBlank() }?.let { put("security", it) }
            val network = object.stringOrNull("net") ?: "tcp"
            if (network == "ws") {
                put("transport", buildJsonObject {
                    put("type", "ws")
                    object.stringOrNull("path")?.let { put("path", it) }
                    object.stringOrNull("host")?.let { hostHeader ->
                        put("headers", buildJsonObject { put("Host", hostHeader) })
                    }
                })
            }
            if (object.stringOrNull("tls") == "tls") {
                put("tls", buildJsonObject {
                    put("enabled", true)
                    object.stringOrNull("sni")?.takeIf { it.isNotBlank() }?.let { put("server_name", it) }
                })
            }
        }
    }

    private fun parseShadowsocks(link: String, index: Int): JsonObject {
        val withoutScheme = link.substringAfter("://")
        val fragmentFree = withoutScheme.substringBefore('#').substringBefore('?')
        val credentialsAndHost = if ('@' in fragmentFree) fragmentFree else decodeBase64(fragmentFree) ?: error("Invalid ss link")
        val credentials = credentialsAndHost.substringBeforeLast('@')
        val hostPort = credentialsAndHost.substringAfterLast('@')
        val decodedCredentials = decodeBase64(credentials) ?: decode(credentials)
        val method = decodedCredentials.substringBefore(':')
        val password = decodedCredentials.substringAfter(':', "")
        val host = hostPort.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: error("Missing server port")
        require(method.isNotBlank() && password.isNotBlank() && host.isNotBlank()) { "Invalid ss link" }
        return buildJsonObject {
            put("type", "shadowsocks")
            put("tag", "proxy-${index + 1}")
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
    }

    private fun configFor(outbounds: List<JsonObject>): String {
        val proxyTags = outbounds.map { it["tag"]!!.jsonPrimitive.content }
        val finalTag = if (outbounds.size == 1) proxyTags.single() else "auto"
        val completeOutbounds = buildJsonArray {
            outbounds.forEach(::add)
            if (outbounds.size > 1) add(buildJsonObject {
                put("type", "urltest")
                put("tag", "auto")
                put("outbounds", JsonArray(proxyTags.map(::JsonPrimitive)))
                put("url", "https://www.gstatic.com/generate_204")
                put("interval", "10m")
                put("tolerance", 50)
            })
        }
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("address", buildJsonArray {
                        add(JsonPrimitive("172.19.0.1/30"))
                        add(JsonPrimitive("fdfe:dcba:9876::1/126"))
                    })
                    put("auto_route", true)
                    put("strict_route", true)
                })
            })
            put("dns", buildJsonObject {
                put("servers", buildJsonArray { add(buildJsonObject { put("tag", "local"); put("address", "local") }) })
            })
            put("outbounds", completeOutbounds)
            put("route", buildJsonObject { put("auto_detect_interface", true); put("final", finalTag) })
        })
    }

    private fun tls(query: Map<String, String>): JsonObject? {
        val security = query["security"] ?: query["tls"]
        if (security != "tls" && security != "reality" && query["sni"].isNullOrBlank()) return null
        return buildJsonObject {
            put("enabled", true)
            query["sni"]?.let { put("server_name", it) }
            query["fp"]?.let { put("utls", buildJsonObject { put("enabled", true); put("fingerprint", it) }) }
            if (security == "reality" || query.containsKey("pbk")) {
                put("reality", buildJsonObject {
                    put("enabled", true)
                    query["pbk"]?.let { put("public_key", it) }
                    query["sid"]?.let { put("short_id", it) }
                })
            }
            query["insecure"]?.takeIf { it == "1" || it == "true" }?.let { put("insecure", true) }
        }
    }

    private fun transport(query: Map<String, String>): JsonObject? = when (query["type"] ?: query["network"]) {
        "ws" -> buildJsonObject {
            put("type", "ws")
            query["path"]?.let { put("path", it) }
            query["host"]?.let { put("headers", buildJsonObject { put("Host", it) }) }
        }
        "grpc" -> buildJsonObject { put("type", "grpc"); query["serviceName"]?.let { put("service_name", it) } }
        else -> null
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.isNotBlank() }.associate {
        decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
    }

    private fun linkName(link: String): String = link.substringAfter('#', "").let(::decode)

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

    private fun JsonObject.string(name: String): String = stringOrNull(name) ?: error("Missing $name")

    private fun JsonObject.stringOrNull(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
}
