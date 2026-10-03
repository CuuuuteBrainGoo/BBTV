package top.bilitv

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.sponsor.SponsorBlockApi
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SponsorReadCancellationTest {
    @Test fun `cancelling community lookup closes headers and body reads without contacting the backup`() = runBlocking {
        val backupCalls = AtomicInteger()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                if (chain.request().url.encodedPath.startsWith("/backup")) backupCalls.incrementAndGet()
                chain.proceed(chain.request())
            }.build()
        try {
            for (bodyStarted in listOf(false, true)) {
                val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                val peer = AtomicReference<Socket?>()
                val ready = CompletableDeferred<Unit>()
                val disconnected = CompletableDeferred<Unit>()
                val worker = thread(isDaemon = true, name = "BBTV-sponsor-cancel-test") {
                    try {
                        server.accept().use { socket ->
                            peer.set(socket); socket.soTimeout = 5000
                            val input = socket.getInputStream().bufferedReader()
                            while (!input.readLine().isNullOrEmpty()) { }
                            if (bodyStarted) socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n[".toByteArray()); flush()
                            }
                            ready.complete(Unit)
                            assertEquals(-1, socket.getInputStream().read())
                            disconnected.complete(Unit)
                        }
                    } catch (e: Throwable) { ready.completeExceptionally(e); disconnected.completeExceptionally(e) }
                }
                val base = "http://127.0.0.1:${server.localPort}"
                val job = async { SponsorBlockApi(client, listOf(base, "$base/backup")).fetchSegments("BV1test", 1) }
                try {
                    withTimeout(4000) { ready.await(); job.cancelAndJoin(); disconnected.await() }
                    assertTrue(job.isCancelled); assertEquals(0, backupCalls.get())
                } finally { job.cancelAndJoin(); peer.get()?.close(); server.close(); worker.join(1000) }
            }
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}
