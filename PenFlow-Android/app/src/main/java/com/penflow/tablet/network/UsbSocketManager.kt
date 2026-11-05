package com.penflow.tablet.network

import android.os.Process
import android.util.Log
import com.penflow.tablet.model.PacketConstants
import com.penflow.tablet.model.PacketSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

class UsbSocketManager(
    private val scope: CoroutineScope,
    private val host: String = "127.0.0.1",
    private val port: Int = 9876
) {
    private val TAG = "PenFlow_UsbSocket"

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _samplingRateHz = MutableStateFlow(0)
    val samplingRateHz: StateFlow<Int> = _samplingRateHz.asStateFlow()

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    private val _totalPackets = MutableStateFlow(0L)
    val totalPackets: StateFlow<Long> = _totalPackets.asStateFlow()

    private var socket: Socket? = null
    private var outputStream: java.io.OutputStream? = null
    private val burstWriteBuffer = ByteArray(PacketConstants.PACKET_SIZE * 64)

    // Zero-lag coalescing packet queue guarded by queueLock so stale movement packets NEVER accumulate
    private val queueLock = Object()
    private val packetQueue = ArrayList<ByteArray>(8)

    // Preallocated byte array pool to avoid GC churn
    private val bufferPool = ArrayDeque<ByteArray>()
    private val poolLock = Any()

    private val serializer = PacketSerializer()
    private val packetsThisSecond = AtomicInteger(0)
    private val packetCounter = AtomicLong(0)

    private var connectionJob: Job? = null
    private var metricsJob: Job? = null
    private var pingJob: Job? = null

    init {
        // Pre-populate pool with 128 reusable 24-byte arrays
        repeat(128) {
            bufferPool.add(ByteArray(PacketConstants.PACKET_SIZE))
        }
    }

    fun start() {
        if (connectionJob != null) return

        connectionJob = scope.launch(Dispatchers.IO) {
            manageConnectionLoop()
        }

        // Metrics sampling coroutine (every 1000ms)
        metricsJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000)
                val hz = packetsThisSecond.getAndSet(0)
                _samplingRateHz.value = hz
                _totalPackets.value = packetCounter.get()
            }
        }

        // Heartbeat / RTT latency coroutine
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                    sendPing()
                }
            }
        }
    }

    fun stop() {
        connectionJob?.cancel()
        metricsJob?.cancel()
        pingJob?.cancel()
        closeSocket()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    private suspend fun manageConnectionLoop() {
        while (scope.isActive) {
            try {
                _connectionStatus.value = ConnectionStatus.CONNECTING
                Log.d(TAG, "Attempting to connect to $host:$port (via ADB Reverse)...")

                val newSocket = Socket()
                newSocket.tcpNoDelay = true
                newSocket.sendBufferSize = 4096
                newSocket.receiveBufferSize = 4096
                newSocket.trafficClass = 0x10 // Low-delay IPTOS

                newSocket.connect(InetSocketAddress(host, port), 2000)
                socket = newSocket
                outputStream = newSocket.getOutputStream()
                synchronized(queueLock) {
                    packetQueue.clear()
                }

                _connectionStatus.value = ConnectionStatus.CONNECTED
                Log.i(TAG, "Successfully connected to PenFlow PC Server!")

                // Launch receiver coroutine for pings
                val readJob = scope.launch(Dispatchers.IO) {
                    readLoop(newSocket.getInputStream())
                }

                // Run sendLoop on a dedicated, pinned high-priority OS thread so it never hops coroutine worker threads
                var sendError: Exception? = null
                val senderThread = Thread({
                    try {
                        sendLoop(newSocket, newSocket.getOutputStream())
                    } catch (e: Exception) {
                        sendError = e
                    }
                }, "PenFlow-USB-Sender").apply {
                    isDaemon = true
                    priority = Thread.MAX_PRIORITY
                    start()
                }

                while (scope.isActive && newSocket.isConnected && !newSocket.isClosed && senderThread.isAlive) {
                    delay(100)
                }

                readJob.cancel()
                senderThread.interrupt()
                if (sendError != null) throw sendError!!
            } catch (e: Exception) {
                Log.d(TAG, "Connection failed or lost: ${e.message}. Retrying in 1s...")
                closeSocket()
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                delay(1000)
            }
        }
    }

    private fun sendLoop(activeSocket: Socket, stream: java.io.OutputStream) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Exception) {}

        while (scope.isActive && activeSocket.isConnected && !activeSocket.isClosed) {
            var count = 0
            var byteOffset = 0

            synchronized(queueLock) {
                if (packetQueue.isEmpty()) {
                    try {
                        queueLock.wait(250L)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                while (packetQueue.isNotEmpty() && count < 16) {
                    val next = packetQueue.removeAt(0)
                    System.arraycopy(next, 0, burstWriteBuffer, byteOffset, PacketConstants.PACKET_SIZE)
                    recycleBuffer(next)
                    byteOffset += PacketConstants.PACKET_SIZE
                    count++
                }
            }

            if (count == 0) continue

            // Single direct write syscall to TCP_NODELAY socket stream
            stream.write(burstWriteBuffer, 0, byteOffset)
            stream.flush()

            packetsThisSecond.addAndGet(count)
            packetCounter.addAndGet(count.toLong())
        }
    }

    private fun readLoop(inputStream: InputStream) {
        val buffer = ByteArray(PacketConstants.PACKET_SIZE)
        val byteBuf = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

        while (scope.isActive && socket?.isConnected == true) {
            var bytesRead = 0
            while (bytesRead < PacketConstants.PACKET_SIZE) {
                val r = inputStream.read(buffer, bytesRead, PacketConstants.PACKET_SIZE - bytesRead)
                if (r == -1) return
                bytesRead += r
            }

            byteBuf.rewind()
            val magic = byteBuf.short
            val type = byteBuf.get()

            if (magic == PacketConstants.MAGIC_HEADER && type == PacketConstants.TYPE_PING) {
                byteBuf.get() // action
                val sentTime = byteBuf.long
                val now = System.currentTimeMillis()
                val rtt = (now - sentTime).coerceAtLeast(0)
                _latencyMs.value = rtt
            }
        }
    }

    private fun enqueuePacket(buf: ByteArray) {
        synchronized(queueLock) {
            // Allow up to 8 sub-step packets (1 full 1000Hz hardware burst) to pass through intact,
            // while immediately dropping any older continuous movement packets if the queue exceeds 8
            // so lag can NEVER accumulate!
            while (packetQueue.size >= 8) {
                var droppedIdx = 0
                for (i in 0 until packetQueue.size) {
                    val act = packetQueue[i][3]
                    if (act == PacketConstants.ACTION_HOVER || act == PacketConstants.ACTION_MOVE) {
                        droppedIdx = i
                        break
                    }
                }
                val dropped = packetQueue.removeAt(droppedIdx)
                recycleBuffer(dropped)
            }

            packetQueue.add(buf)
            queueLock.notifyAll()
        }
    }

    private fun sendPing() {
        val buf = obtainBuffer()
        val now = System.currentTimeMillis()
        serializer.serializePing(now, buf)
        enqueuePacket(buf)
    }

    fun sendPointerEvent(
        action: Byte,
        normX: Float,
        normY: Float,
        pressure: Float,
        tiltX: Short,
        tiltY: Short,
        toolType: Byte,
        buttonState: Byte,
        activeWidthMm: Float = 293.76f,
        activeHeightMm: Float = 183.60f
    ) {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) return

        val buf = obtainBuffer()
        serializer.serializePointer(
            action = action,
            normX = normX,
            normY = normY,
            pressure = pressure,
            tiltX = tiltX,
            tiltY = tiltY,
            toolType = toolType,
            buttonState = buttonState,
            activeWidthMm = activeWidthMm,
            activeHeightMm = activeHeightMm,
            outBytes = buf
        )
        enqueuePacket(buf)
    }

    private fun obtainBuffer(): ByteArray {
        synchronized(poolLock) {
            if (bufferPool.isNotEmpty()) {
                return bufferPool.removeLast()
            }
        }
        return ByteArray(PacketConstants.PACKET_SIZE)
    }

    private fun recycleBuffer(buffer: ByteArray) {
        synchronized(poolLock) {
            if (bufferPool.size < 256) {
                bufferPool.add(buffer)
            }
        }
    }

    private fun closeSocket() {
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        outputStream = null
    }
}
