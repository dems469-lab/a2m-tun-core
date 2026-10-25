package io.nekohasekai.sfa.compose.screen.configuration

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkParserTest {
    @Test
    fun parsesSupportedShareLinksIntoTunConfigurations() {
        val links = listOf(
            "vless://11111111-1111-4111-8111-111111111111@edge.example.invalid:443?security=reality&pbk=test-public-key&sid=abcd&type=ws&path=%2Fsocket#VLESS",
            "trojan://test-password@edge.example.invalid:443?security=tls&sni=edge.example.invalid#Trojan",
            "ss://YWVzLTI1Ni1nY206dGVzdC1wYXNz@edge.example.invalid:8388#Shadowsocks",
            "hysteria2://test-password@edge.example.invalid:443?sni=edge.example.invalid#Hysteria2",
            "tuic://22222222-2222-4222-8222-222222222222:ignored@edge.example.invalid:443?password=test-password&sni=edge.example.invalid#TUIC",
        )

        links.forEach { link ->
            val result = ShareLinkParser.parse(link)
            assertEquals(1, result.serverCount)
            assertTrue(result.config.contains("\"type\":\"tun\""))
            assertTrue(result.config.contains("\"final\":\"proxy-1\""))
        }
    }

    @Test
    fun parsesVmessAndBase64SubscriptionIntoUrltestGroup() {
        val vmessPayload = """{"v":"2","ps":"VMess","add":"edge.example.invalid","port":"443","id":"33333333-3333-4333-8333-333333333333","aid":"0","net":"ws","path":"/ws","tls":"tls","sni":"edge.example.invalid"}"""
        val vmess = "vmess://${Base64.getEncoder().encodeToString(vmessPayload.toByteArray())}"
        val vless = "vless://44444444-4444-4444-8444-444444444444@second.example.invalid:443?security=tls&sni=second.example.invalid#Second"
        val subscription = Base64.getEncoder().encodeToString("$vmess\n$vless".toByteArray())

        val result = ShareLinkParser.parse(subscription, "Test subscription")

        assertEquals(2, result.serverCount)
        assertTrue(result.config.contains("\"type\":\"urltest\""))
        assertTrue(result.config.contains("\"final\":\"auto\""))
        assertTrue(result.config.contains("\"type\":\"vmess\""))
        assertTrue(result.config.contains("\"type\":\"vless\""))
    }
}
