package com.winlator.teknoparrot;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Session-scoped TPI1 producer. UI callbacks only encode into the bounded SPSC
 * queue; a dedicated thread owns authentication and socket writes. A reconnect
 * or queue overflow publishes a focus reset before later events so the host
 * cannot retain stale controls.
 */
public final class ForwardedInputClient implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 256;
    private static final int MAXIMUM_TRACKED_DEVICES = 32;
    private static final long LIFECYCLE_DEVICE_ID = 0;
    private static final long RECONNECT_DELAY_MILLISECONDS = 250;
    private static final long IDLE_HEARTBEAT_MILLISECONDS = 1000;

    private final Object signal = new Object();
    private final String sessionId;
    private final int port;
    private final byte[] token;
    private final ForwardedInputQueue queue = new ForwardedInputQueue(QUEUE_CAPACITY);
    private final long[] deviceIds = new long[MAXIMUM_TRACKED_DEVICES];
    private final long[] deviceSequences = new long[MAXIMUM_TRACKED_DEVICES];
    private final byte[] resetFrame = new byte[
            ForwardedInputProtocol.HEADER_BYTES + ForwardedInputProtocol.FOCUS_PAYLOAD_BYTES];
    private final byte[] focusedFrame = new byte[
            ForwardedInputProtocol.HEADER_BYTES + ForwardedInputProtocol.FOCUS_PAYLOAD_BYTES];
    private final byte[] heartbeatFrame = new byte[
            ForwardedInputProtocol.HEADER_BYTES + ForwardedInputProtocol.FOCUS_PAYLOAD_BYTES];
    private final Thread socketThread;

    private volatile Socket activeSocket;
    private volatile boolean connected;
    private volatile boolean closed;
    private volatile String lastError = "";
    private int trackedDeviceCount;
    private boolean focused = true;
    private boolean resynchronizationRequired = true;
    private long dataFramesSent;
    private long droppedFrames;
    private long resynchronizations;

    public ForwardedInputClient(String sessionId, int port, byte[] token) {
        if (sessionId == null || sessionId.length() != 32)
            throw new IllegalArgumentException("A 32-character session id is required.");
        BridgeProtocol.hexToBytes(sessionId, 16);
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("The forwarded-input port is invalid.");
        if (token == null || token.length != 32)
            throw new IllegalArgumentException("A 256-bit forwarded-input token is required.");

        this.sessionId = sessionId;
        this.port = port;
        this.token = token.clone();
        socketThread = new Thread(this::runSocketLoop, "TeknoParrot-TPI1");
        socketThread.setDaemon(true);
    }

    public void start() {
        synchronized (signal) {
            if (closed)
                throw new IllegalStateException("The forwarded-input client is closed.");
            if (socketThread.getState() != Thread.State.NEW)
                return;
            socketThread.start();
        }
    }

    public boolean sendButton(
            long deviceStableId,
            int player,
            int button,
            boolean pressed,
            long eventTimeNanoseconds) {
        synchronized (signal) {
            byte[] destination = acquireWriteBufferLocked();
            if (destination == null)
                return false;
            try {
                int length = ForwardedInputProtocol.writeButtonFrame(
                        destination,
                        nextSequenceLocked(deviceStableId),
                        normalizedEventTime(eventTimeNanoseconds),
                        deviceStableId,
                        player,
                        button,
                        pressed);
                publishWriteLocked(length);
                return true;
            }
            catch (RuntimeException error) {
                queue.cancelWrite();
                throw error;
            }
        }
    }

    public boolean sendAxis(
            long deviceStableId,
            int player,
            int axisId,
            short valueQ15,
            int flatQ15,
            long eventTimeNanoseconds) {
        synchronized (signal) {
            byte[] destination = acquireWriteBufferLocked();
            if (destination == null)
                return false;
            try {
                int length = ForwardedInputProtocol.writeAxisFrame(
                        destination,
                        nextSequenceLocked(deviceStableId),
                        normalizedEventTime(eventTimeNanoseconds),
                        deviceStableId,
                        player,
                        axisId,
                        valueQ15,
                        flatQ15);
                publishWriteLocked(length);
                return true;
            }
            catch (RuntimeException error) {
                queue.cancelWrite();
                throw error;
            }
        }
    }

    public boolean sendPointerAbsolute(
            long deviceStableId,
            int player,
            int toolType,
            int x,
            int y,
            int pressure,
            long pointerId,
            long buttons,
            long eventTimeNanoseconds) {
        synchronized (signal) {
            byte[] destination = acquireWriteBufferLocked();
            if (destination == null)
                return false;
            try {
                int length = ForwardedInputProtocol.writePointerAbsoluteFrame(
                        destination,
                        nextSequenceLocked(deviceStableId),
                        normalizedEventTime(eventTimeNanoseconds),
                        deviceStableId,
                        player,
                        toolType,
                        x,
                        y,
                        pressure,
                        pointerId,
                        buttons);
                publishWriteLocked(length);
                return true;
            }
            catch (RuntimeException error) {
                queue.cancelWrite();
                throw error;
            }
        }
    }

    public boolean sendFocus(boolean hasFocus, long eventTimeNanoseconds) {
        synchronized (signal) {
            focused = hasFocus;
            byte[] destination = acquireWriteBufferLocked();
            if (destination == null)
                return false;
            try {
                int length = ForwardedInputProtocol.writeFocusFrame(
                        destination,
                        nextSequenceLocked(LIFECYCLE_DEVICE_ID),
                        normalizedEventTime(eventTimeNanoseconds),
                        LIFECYCLE_DEVICE_ID,
                        hasFocus);
                publishWriteLocked(length);
                return true;
            }
            catch (RuntimeException error) {
                queue.cancelWrite();
                throw error;
            }
        }
    }

    public boolean sendDeviceRemoved(long deviceStableId, long eventTimeNanoseconds) {
        synchronized (signal) {
            byte[] destination = acquireWriteBufferLocked();
            if (destination == null)
                return false;
            try {
                int length = ForwardedInputProtocol.writeEmptyFrame(
                        destination,
                        ForwardedInputProtocol.TYPE_DEVICE_REMOVED,
                        nextSequenceLocked(deviceStableId),
                        normalizedEventTime(eventTimeNanoseconds),
                        deviceStableId);
                publishWriteLocked(length);
                removeSequenceLocked(deviceStableId);
                return true;
            }
            catch (RuntimeException error) {
                queue.cancelWrite();
                throw error;
            }
        }
    }

    public boolean awaitConnected(long timeoutMilliseconds) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMilliseconds * 1_000_000L;
        synchronized (signal) {
            while (!connected && !closed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    return false;
                signal.wait(Math.max(1, remaining / 1_000_000L));
            }
            return connected;
        }
    }

    public boolean awaitDataFramesSent(long expected, long timeoutMilliseconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMilliseconds * 1_000_000L;
        synchronized (signal) {
            while (dataFramesSent < expected && !closed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    return false;
                signal.wait(Math.max(1, remaining / 1_000_000L));
            }
            return dataFramesSent >= expected;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public long getDataFramesSent() {
        synchronized (signal) {
            return dataFramesSent;
        }
    }

    public long getDroppedFrames() {
        synchronized (signal) {
            return droppedFrames;
        }
    }

    public long getResynchronizations() {
        synchronized (signal) {
            return resynchronizations;
        }
    }

    public int getQueueSize() {
        return queue.size();
    }

    public String getLastError() {
        return lastError;
    }

    @Override
    public void close() {
        Socket socket;
        synchronized (signal) {
            if (closed)
                return;
            closed = true;
            connected = false;
            socket = activeSocket;
            signal.notifyAll();
        }

        closeQuietly(socket);
        socketThread.interrupt();
        if (Thread.currentThread() != socketThread) {
            try {
                socketThread.join(2000);
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        Arrays.fill(token, (byte)0);
    }

    private byte[] acquireWriteBufferLocked() {
        if (closed)
            return null;
        byte[] destination = queue.tryAcquireWriteBuffer();
        if (destination == null) {
            droppedFrames++;
            resynchronizationRequired = true;
            signal.notifyAll();
        }
        return destination;
    }

    private void publishWriteLocked(int length) {
        queue.publishWrite(length);
        signal.notifyAll();
    }

    private long nextSequenceLocked(long deviceStableId) {
        if (deviceStableId < 0 || deviceStableId > 0xffffffffL)
            throw new IllegalArgumentException("The stable device id is outside the uint32 range.");
        for (int index = 0; index < trackedDeviceCount; index++) {
            if (deviceIds[index] == deviceStableId) {
                deviceSequences[index] = (deviceSequences[index] + 1) & 0xffffffffL;
                return deviceSequences[index];
            }
        }
        if (trackedDeviceCount >= MAXIMUM_TRACKED_DEVICES)
            throw new IllegalStateException("Too many forwarded-input devices are active.");
        deviceIds[trackedDeviceCount] = deviceStableId;
        deviceSequences[trackedDeviceCount] = 1;
        trackedDeviceCount++;
        return 1;
    }

    private void removeSequenceLocked(long deviceStableId) {
        if (deviceStableId == LIFECYCLE_DEVICE_ID)
            return;
        for (int index = 0; index < trackedDeviceCount; index++) {
            if (deviceIds[index] != deviceStableId)
                continue;
            int last = trackedDeviceCount - 1;
            deviceIds[index] = deviceIds[last];
            deviceSequences[index] = deviceSequences[last];
            trackedDeviceCount = last;
            return;
        }
    }

    private void runSocketLoop() {
        while (!closed) {
            try {
                connectAndPump();
            }
            catch (IOException error) {
                lastError = safeMessage(error);
            }
            finally {
                Socket socket;
                synchronized (signal) {
                    connected = false;
                    resynchronizationRequired = true;
                    socket = activeSocket;
                    activeSocket = null;
                    signal.notifyAll();
                }
                closeQuietly(socket);
            }

            if (!closed) {
                synchronized (signal) {
                    try {
                        signal.wait(RECONNECT_DELAY_MILLISECONDS);
                    }
                    catch (InterruptedException error) {
                        if (!closed)
                            Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private void connectAndPump() throws IOException {
        Socket socket = new Socket();
        synchronized (signal) {
            if (closed) {
                closeQuietly(socket);
                return;
            }
            activeSocket = socket;
        }
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(5000);

        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        DataInputStream input = new DataInputStream(socket.getInputStream());
        output.write(BridgeProtocol.createAuthenticatedHandshake(
                sessionId,
                token,
                BridgeProtocol.CHANNEL_KIND_FORWARDED_INPUT,
                BridgeProtocol.FORWARDED_INPUT_CHANNEL_NAME));
        output.flush();

        byte[] acknowledgement = new byte[4];
        input.readFully(acknowledgement);
        if (!MessageDigest.isEqual(
                acknowledgement, "OKAY".getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("TeknoParrotUI rejected the forwarded-input handshake.");

        writeResynchronization(output);
        synchronized (signal) {
            connected = true;
            lastError = "";
            signal.notifyAll();
        }

        while (!closed) {
            boolean reset;
            byte[] packet = null;
            int packetLength = 0;
            boolean heartbeat = false;
            synchronized (signal) {
                while (!closed && !resynchronizationRequired && queue.size() == 0) {
                    try {
                        signal.wait(IDLE_HEARTBEAT_MILLISECONDS);
                    }
                    catch (InterruptedException error) {
                        if (closed)
                            return;
                    }

                    if (!closed && !resynchronizationRequired && queue.size() == 0) {
                        heartbeat = true;
                        break;
                    }
                }
                if (closed)
                    return;
                reset = resynchronizationRequired;
                if (!reset && !heartbeat) {
                    packet = queue.tryAcquireReadBuffer();
                    if (packet != null)
                        packetLength = queue.acquiredReadLength();
                }
            }

            if (reset) {
                writeResynchronization(output);
                continue;
            }
            if (heartbeat) {
                writeHeartbeat(output);
                continue;
            }
            if (packet == null)
                continue;

            boolean sent = false;
            try {
                output.write(packet, 0, packetLength);
                output.flush();
                sent = true;
            }
            finally {
                synchronized (signal) {
                    queue.releaseRead();
                    if (sent)
                        dataFramesSent++;
                    else
                        resynchronizationRequired = true;
                    signal.notifyAll();
                }
            }
        }
    }

    /**
     * Keeps an otherwise idle half-duplex input connection observable. Without
     * this write, Android can kill and recreate the UI-side service while this
     * client waits forever for a local input event and never notices the peer
     * socket has closed. Repeating the current focus state is idempotent for
     * the host and makes the normal reconnect loop run within one second.
     */
    private void writeHeartbeat(DataOutputStream output) throws IOException {
        int length;
        synchronized (signal) {
            length = ForwardedInputProtocol.writeFocusFrame(
                    heartbeatFrame,
                    nextSequenceLocked(LIFECYCLE_DEVICE_ID),
                    System.nanoTime(),
                    LIFECYCLE_DEVICE_ID,
                    focused);
        }
        output.write(heartbeatFrame, 0, length);
        output.flush();
    }

    private void writeResynchronization(DataOutputStream output) throws IOException {
        int resetLength;
        int focusedLength = 0;
        synchronized (signal) {
            byte[] queued;
            while ((queued = queue.tryAcquireReadBuffer()) != null)
                queue.releaseRead();

            resetLength = ForwardedInputProtocol.writeFocusFrame(
                    resetFrame,
                    nextSequenceLocked(LIFECYCLE_DEVICE_ID),
                    System.nanoTime(),
                    LIFECYCLE_DEVICE_ID,
                    false);
            if (focused) {
                focusedLength = ForwardedInputProtocol.writeFocusFrame(
                        focusedFrame,
                        nextSequenceLocked(LIFECYCLE_DEVICE_ID),
                        System.nanoTime(),
                        LIFECYCLE_DEVICE_ID,
                        true);
            }
            resynchronizationRequired = false;
        }

        output.write(resetFrame, 0, resetLength);
        if (focusedLength != 0)
            output.write(focusedFrame, 0, focusedLength);
        output.flush();
        synchronized (signal) {
            resynchronizations++;
            signal.notifyAll();
        }
    }

    private static long normalizedEventTime(long eventTimeNanoseconds) {
        return eventTimeNanoseconds > 0 ? eventTimeNanoseconds : System.nanoTime();
    }

    private static String safeMessage(IOException error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty())
            return error.getClass().getSimpleName();
        return message.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null)
            return;
        try {
            socket.close();
        }
        catch (IOException ignored) {
        }
    }
}
