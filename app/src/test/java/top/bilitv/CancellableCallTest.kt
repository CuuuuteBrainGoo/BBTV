package top.bilitv

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.api.readCancellable
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CancellableCallTest {
    @Test fun `normal body completes and cancelling before headers or mid body closes the real connection`() = runBlocking {
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(10, TimeUnit.SECONDS).build()
        try {
            for (phase in 0..2) {
                val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                val peer = AtomicReference<Socket?>()
                val ready = CompletableDeferred<Unit>()
                val disconnected = CompletableDeferred<Unit>()
                val worker = thread(isDaemon = true, name = "BBTV-cancel-test") {
                    try {
                        server.accept().use { socket ->
                            peer.set(socket); socket.soTimeout = 5000
                            val input = socket.getInputStream().bufferedReader()
                            while (!input.readLine().isNullOrEmpty()) { }
                            if (phase != 1) {
                                socket.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\n${if (phase == 0) "ABCD" else "AB"}".toByteArray())
                                    flush()
                                }
                            }
                            ready.complete(Unit)
                            if (phase != 0) assertEquals(-1, socket.getInputStream().read())
                            disconnected.complete(Unit)
                        }
                    } catch (e: Exception) {
                        ready.completeExceptionally(e); disconnected.completeExceptionally(e)
                    }
                }
                val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
                val job = async(Dispatchers.IO) { call.readCancellable { it.body!!.string() } }
                try {
                    withTimeout(4000) {
                        ready.await()
                        if (phase == 0) assertEquals("ABCD", job.await())
                        else { job.cancelAndJoin(); assertTrue(call.isCanceled()); disconnected.await() }
                    }
                } finally {
                    job.cancelAndJoin(); call.cancel(); peer.get()?.close(); server.close(); worker.join(1000)
                }
            }
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}
