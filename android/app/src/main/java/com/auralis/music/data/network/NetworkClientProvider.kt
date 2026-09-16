package com.auralis.music.data.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object NetworkClientProvider {
    val okHttpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 64
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    /**
     * Resilient DNS resolver that bypasses carrier-level ISP DNS sinkholes (e.g. Reliance Jio in India
     * returning 49.44.79.236 for music.163.com) by transparently falling back to official NetEase
     * overseas edge CDN IP addresses (103.135.240.77 / 78).
     */
    object ResilientLyricsDns : okhttp3.Dns {
        private val OVERSEAS_NETEASE_IPS by lazy {
            listOf(
                java.net.InetAddress.getByAddress("music.163.com", byteArrayOf(103.toByte(), 135.toByte(), 240.toByte(), 77.toByte())),
                java.net.InetAddress.getByAddress("music.163.com", byteArrayOf(103.toByte(), 135.toByte(), 240.toByte(), 78.toByte()))
            )
        }
        private val BLOCKED_SINKHOLE_IPS = setOf("49.44.79.236", "127.0.0.1", "0.0.0.0")

        override fun lookup(hostname: String): List<java.net.InetAddress> {
            if (hostname.equals("music.163.com", ignoreCase = true) ||
                hostname.equals("interface.music.163.com", ignoreCase = true) ||
                hostname.endsWith(".music.163.com", ignoreCase = true)) {
                try {
                    val sysIps = okhttp3.Dns.SYSTEM.lookup(hostname)
                    if (sysIps.isNotEmpty() && sysIps.none { BLOCKED_SINKHOLE_IPS.contains(it.hostAddress) }) {
                        return sysIps
                    }
                } catch (_: Exception) {}
                return OVERSEAS_NETEASE_IPS
            }
            return okhttp3.Dns.SYSTEM.lookup(hostname)
        }
    }

    /**
     * Dedicated ultra-fast HTTP client for parallel lyrics retrieval.
     * Features aggressive connection timeouts, resilient DNS, and high connection reuse.
     */
    val lyricsHttpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(ResilientLyricsDns)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(3000, TimeUnit.MILLISECONDS)
            .callTimeout(3800, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false) // Fail fast to let other parallel providers win
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                    .build()
                chain.proceed(request)
            }
            .build()
    }
}
