package com.winlator.teknoparrot;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.teknoparrot.bridge.v1.ITeknoParrotWinlatorService;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class TeknoParrotBridgeService extends Service {
    private static final String TAG = "TeknoParrotBridge";
    private static final String ACTION_RETAIN_PREPARED_SESSION =
        "com.winlator.teknoparrot.action.RETAIN_PREPARED_SESSION";
    private static final String ACTION_RELEASE_PREPARED_SESSION =
        "com.winlator.teknoparrot.action.RELEASE_PREPARED_SESSION";
    private static final String EXTRA_SESSION_ID =
        "com.winlator.teknoparrot.extra.SESSION_ID";
    private static final String GUEST_BACKEND_CLASS =
        "com.winlator.teknoparrot.TeknoParrotGuestDiagnosticBackend";
    // A production game is owned by XServerDisplayActivity/the Winlator
    // process, not by one bound Service instance. Android is free to destroy
    // and later recreate that Service after its TeknoParrotUI Binder client
    // dies even while the game Activity and Wine guest remain alive. Keep the
    // prepared session process-scoped so a new Service instance can rebind to
    // the exact page, input token and launch registry without starting a
    // duplicate guest.
    private static final Object lock = new Object();
    private static final Handler processExitHandler =
        new Handler(Looper.getMainLooper());
    private static final long PROCESS_EXIT_AFTER_UNBIND_DELAY_MS = 1500L;
    private static final long PROCESS_EXIT_FALLBACK_DELAY_MS = 10000L;
    private static BridgeSession session;
    private static boolean retainSessionFilesOnDestroy;
    private static boolean runtimePackageInstallInProgress;
    private static boolean bridgeClientBound;
    private static boolean productionProcessExitPending;
    private static Runnable scheduledProcessExit;

    private final ITeknoParrotWinlatorService.Stub binder = new ITeknoParrotWinlatorService.Stub() {
        @Override
        public int getProtocolVersion() {
            return SessionContract.SERVICE_PROTOCOL_VERSION;
        }

        @Override
        public byte[] getCapabilities(int clientProtocolVersion) {
            return SessionContract.capabilities(clientProtocolVersion);
        }

        @Override
        public byte[] prepareSession(byte[] spec) {
            SessionContract.PreparedRequest request = SessionContract.parse(spec);
            byte[] prepared;
            synchronized (lock) {
                cancelProductionProcessExitLocked();
                if (runtimePackageInstallInProgress)
                    throw new IllegalStateException(
                        "Wait for the runtime package installation to finish before starting a game.");
                // Debug sessions and their evidence are retained until an
                // explicit, identity-checked production cleanup path exists.
                closeSessionLocked(false);
                try {
                    session = new BridgeSession(getFilesDir(), request);
                    if (request.flags == SessionContract.SESSION_FLAG_PRODUCTION) {
                        String guestPagePath = invokeGuestBackend(
                            "prepareProduction",
                            new Class<?>[]{android.content.Context.class, int.class,
                                String.class, String.class},
                            getApplicationContext(), request.containerId,
                            session.getPagePath(), request.sessionId);
                        if (guestPagePath == null ||
                            !guestPagePath.startsWith("C:\\teknoparrot-service\\"))
                            throw new IllegalStateException(
                                "Could not expose the production bridge page: " + guestPagePath);
                        session.setGuestPagePath(guestPagePath);
                    }
                    ForwardedInputSessionRegistry.register(
                            request.sessionId,
                            request.pipePort,
                            BridgeProtocol.hexToBytes(request.tokenHex, 32));
                    retainSessionFilesOnDestroy = true;
                    prepared = SessionContract.prepared(request);
                }
                catch (IOException error) {
                    closeSessionLocked(false);
                    throw new IllegalStateException("Could not prepare versioned bridge session.", error);
                }
                catch (RuntimeException error) {
                    closeSessionLocked(false);
                    throw error;
                }
            }

            // A prepared game must outlive the TeknoParrotUI Binder connection.
            // Keeping the service started prevents Android from calling
            // onDestroy merely because the UI process (or its Activity) goes
            // away while XServerDisplayActivity is still running the game.
            retainPreparedSession(request.sessionId);
            return prepared;
        }

        @Override
        public String launchPreparedGuestDiagnostic(String sessionId) {
            SessionContract.PreparedRequest request;
            String pagePath;
            synchronized (lock) {
                BridgeSession current = findSessionLocked(sessionId);
                if (current == null || current.getPreparedRequest() == null)
                    throw new IllegalStateException("A prepared bridge session is required.");
                request = current.getPreparedRequest();
                pagePath = current.getPagePath();
                retainSessionFilesOnDestroy = true;
            }

            return invokeGuestBackend(
                "launchAuthenticated",
                new Class<?>[]{android.content.Context.class, String.class, int.class, int.class,
                    String.class, String.class, String.class, String.class},
                getApplicationContext(), sessionId, request.containerId, request.pipePort,
                pagePath, request.tokenHex, request.pipeName64, request.pipeName32);
        }

        @Override
        public String prepareTestSession(String clientName) {
            if (clientName == null || clientName.trim().isEmpty() || clientName.length() > 80)
                throw new IllegalArgumentException("A short client name is required.");

            synchronized (lock) {
                closeSessionLocked();
                try {
                    session = new BridgeSession(getFilesDir());
                    return session.id;
                }
                catch (IOException error) {
                    throw new IllegalStateException("Could not prepare bridge session.", error);
                }
            }
        }

        @Override
        public String getSessionStatus(String sessionId) {
            synchronized (lock) {
                BridgeSession current = findSessionLocked(sessionId);
                return current != null ? current.getStatus() : "state=missing";
            }
        }

        @Override
        public String runPipeProbe(String sessionId, int port, String tokenHex) {
            BridgeSession current;
            synchronized (lock) {
                current = findSessionLocked(sessionId);
                if (current == null)
                    throw new IllegalStateException("Bridge session is not active.");
            }

            try {
                return current.runPipeProbe(port, BridgeProtocol.hexToBytes(tokenHex, 32));
            }
            catch (IOException error) {
                current.setError(error.getClass().getSimpleName() + ": " + error.getMessage());
                throw new IllegalStateException("Winlator pipe probe failed.", error);
            }
        }

        @Override
        public String launchGuestBridgeDiagnostic(String sessionId, int containerId, int port) {
            String pagePath;
            synchronized (lock) {
                BridgeSession current = findSessionLocked(sessionId);
                if (current == null)
                    throw new IllegalStateException("Bridge session is not active.");
                pagePath = current.getPagePath();
                retainSessionFilesOnDestroy = true;
            }

            return invokeGuestBackend(
                "launch",
                new Class<?>[]{android.content.Context.class, String.class, int.class, int.class, String.class},
                getApplicationContext(), sessionId, containerId, port, pagePath);
        }

        @Override
        public String getGuestBridgeDiagnosticStatus(String sessionId) {
            synchronized (lock) {
                if (findSessionLocked(sessionId) == null)
                    return "state=missing";
            }
            return invokeGuestBackend(
                "getStatus",
                new Class<?>[]{String.class},
                sessionId);
        }

        @Override
        public void stopGuestBridgeDiagnostic(String sessionId) {
            synchronized (lock) {
                if (findSessionLocked(sessionId) == null)
                    return;
            }
            invokeGuestBackend("stop", new Class<?>[]{String.class}, sessionId);
        }

        @Override
        public void stopTestSession(String sessionId) {
            boolean stopped = false;
            boolean recycleProductionProcess = false;
            synchronized (lock) {
                BridgeSession current = findSessionLocked(sessionId);
                if (current != null) {
                    recycleProductionProcess = isProductionSession(current);
                    closeSessionLocked();
                    if (recycleProductionProcess)
                        requestProductionProcessExitLocked();
                    stopped = true;
                }
            }
            if (stopped)
                stopSelf();
        }

        @Override
        public String runPreparedInputDiagnostic(String sessionId) {
            BridgeSession current;
            synchronized (lock) {
                current = findSessionLocked(sessionId);
                if (current == null || current.getPreparedRequest() == null)
                    throw new IllegalStateException("A prepared v2 bridge session is required.");
            }

            try {
                return current.runPreparedInputDiagnostic();
            }
            catch (IOException error) {
                current.setError(error.getClass().getSimpleName() + ": " + error.getMessage());
                throw new IllegalStateException("Winlator forwarded-input diagnostic failed.", error);
            }
        }

        @Override
        public String launchPreparedInputActivityDiagnostic(String sessionId) {
            SessionContract.PreparedRequest request;
            synchronized (lock) {
                BridgeSession current = findSessionLocked(sessionId);
                if (current == null || current.getPreparedRequest() == null)
                    throw new IllegalStateException("A prepared v2 bridge session is required.");
                request = current.getPreparedRequest();
            }
            return launchPreparedActivityInternal(
                ActivityLaunchContract.forwardedInputDiagnostic(request));
        }

        @Override
        public String launchPreparedActivity(byte[] requestBytes) {
            return launchPreparedActivityInternal(ActivityLaunchContract.parse(requestBytes));
        }

        @Override
        public String ensureTeknoParrotEnvironment(int preferredContainerId) {
            if (preferredContainerId <= 0)
                throw new IllegalArgumentException("A positive preferred container id is required.");
            synchronized (lock) {
                if (runtimePackageInstallInProgress)
                    throw new IllegalStateException(
                        "Wait for the runtime package installation to finish before provisioning Winlator.");
            }
            return invokeGuestBackend(
                "ensureEnvironment",
                new Class<?>[]{android.content.Context.class, int.class},
                getApplicationContext(), preferredContainerId);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == BridgeProtocol.QUERY_RUNTIME_PACKAGES_TRANSACTION) {
                data.enforceInterface(BridgeProtocol.DESCRIPTOR);
                try {
                    String status = TeknoParrotRuntimePackageInstaller
                        .queryInstalledPackages(getApplicationContext());
                    reply.writeNoException();
                    reply.writeString(status);
                    return true;
                }
                catch (IOException error) {
                    throw new RemoteException(
                        "Could not query installed runtime packages: " + error.getMessage());
                }
            }
            if (code == BridgeProtocol.INSTALL_RUNTIME_PACKAGE_TRANSACTION) {
                data.enforceInterface(BridgeProtocol.DESCRIPTOR);
                String packageId = data.readString();
                String version = data.readString();
                String digest = data.readString();
                try (ParcelFileDescriptor descriptor = data.readFileDescriptor()) {
                    if (descriptor == null)
                        throw new IllegalArgumentException(
                            "The runtime-package descriptor is missing.");
                    synchronized (lock) {
                        if (session != null)
                            throw new IllegalStateException(
                                "Stop the running game before updating runtime packages.");
                        if (runtimePackageInstallInProgress)
                            throw new IllegalStateException(
                                "A runtime package installation is already running.");
                        runtimePackageInstallInProgress = true;
                    }
                    try {
                        String status = TeknoParrotRuntimePackageInstaller.install(
                            getApplicationContext(),
                            descriptor,
                            packageId,
                            version,
                            digest);
                        reply.writeNoException();
                        reply.writeString(status);
                        return true;
                    }
                    finally {
                        synchronized (lock) {
                            runtimePackageInstallInProgress = false;
                        }
                    }
                }
                catch (IOException error) {
                    throw new RemoteException(
                        "Runtime package installation failed: " + error.getMessage());
                }
            }
            if (code != BridgeProtocol.OPEN_PAGE_TRANSACTION)
                return super.onTransact(code, data, reply, flags);

            data.enforceInterface(BridgeProtocol.DESCRIPTOR);
            String sessionId = data.readString();
            synchronized (lock) {
                BridgeSession current = findSessionLocked(sessionId);
                if (current == null)
                    throw new IllegalStateException("Bridge session is not active.");

                try (ParcelFileDescriptor descriptor = current.openPage()) {
                    reply.writeNoException();
                    reply.writeFileDescriptor(descriptor.getFileDescriptor());
                    return true;
                }
                catch (IOException error) {
                    throw new RemoteException("Could not duplicate the shared-page descriptor: " + error.getMessage());
                }
            }
        }
    };

    private String launchPreparedActivityInternal(ActivityLaunchContract.Request request) {
        synchronized (lock) {
            BridgeSession current = findSessionLocked(request.sessionId);
            if (current == null || current.getPreparedRequest() == null)
                throw new IllegalStateException("A prepared bridge session is required.");
            return PreparedSessionActivityLauncher.launch(
                getApplicationContext(), request, current.getPreparedRequest(),
                current.getGuestPagePath());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null || !BridgeProtocol.ACTION.equals(intent.getAction()))
            return null;
        synchronized (lock) {
            bridgeClientBound = true;
            cancelProductionProcessExitLocked();
        }
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        synchronized (lock) {
            bridgeClientBound = false;
            if (productionProcessExitPending)
                scheduleProductionProcessExitLocked(
                    PROCESS_EXIT_AFTER_UNBIND_DELAY_MS);
        }
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null)
            return START_NOT_STICKY;

        String action = intent.getAction();
        String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        if (ACTION_RELEASE_PREPARED_SESSION.equals(action)) {
            boolean stopReleasedService = false;
            synchronized (lock) {
                BridgeSession current = findSessionLocked(sessionId);
                if (current != null) {
                    boolean recycleProductionProcess = isProductionSession(current);
                    closeSessionLocked();
                    if (recycleProductionProcess)
                        requestProductionProcessExitLocked();
                    stopReleasedService = true;
                }
                // TPUI normally closes the authenticated session over Binder
                // before the Activity's onDestroy fallback arrives. In that
                // order there is no session left to release, but startService
                // above still created a new started-service record. Stop that
                // record without touching a different, newer session.
                else if (session == null)
                    stopReleasedService = true;
            }
            if (stopReleasedService)
                stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_RETAIN_PREPARED_SESSION.equals(action)) {
            synchronized (lock) {
                if (findSessionLocked(sessionId) == null)
                    stopSelf(startId);
            }
            return START_NOT_STICKY;
        }

        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        synchronized (lock) {
            if (!retainSessionFilesOnDestroy) {
                closeSessionLocked();
            }
            else if (session != null) {
                Log.i(TAG, "Retaining prepared session across Service recreation: " + session.id);
            }
        }
        super.onDestroy();
    }

    public static void releasePreparedSession(android.content.Context context, String sessionId) {
        if (context == null || sessionId == null || sessionId.isEmpty())
            return;
        Intent intent = new Intent(context, TeknoParrotBridgeService.class)
            .setAction(ACTION_RELEASE_PREPARED_SESSION)
            .putExtra(EXTRA_SESSION_ID, sessionId);
        try {
            context.startService(intent);
        }
        catch (IllegalStateException error) {
            // The UI-side foreground session normally releases through AIDL.
            // This is the same-process fallback for an Activity whose remote
            // host disappeared before the game closed.
            Log.w(TAG, "Android deferred prepared-session release.", error);
        }
    }

    private void retainPreparedSession(String sessionId) {
        Intent intent = new Intent(this, TeknoParrotBridgeService.class)
            .setAction(ACTION_RETAIN_PREPARED_SESSION)
            .putExtra(EXTRA_SESSION_ID, sessionId);
        startService(intent);
    }

    private BridgeSession findSessionLocked(String sessionId) {
        return session != null && session.id.equals(sessionId) ? session : null;
    }

    private static boolean isProductionSession(BridgeSession candidate) {
        SessionContract.PreparedRequest request =
            candidate != null ? candidate.getPreparedRequest() : null;
        return request != null &&
            request.flags == SessionContract.SESSION_FLAG_PRODUCTION;
    }

    /**
     * A managed game can allocate hundreds of MiB in Wine, Box64, EGL and
     * native graphics libraries. Activity teardown terminates the guest, but
     * Android normally caches this companion process and therefore retains a
     * large portion of that address space for the next game. Recycle only
     * after the production session is closed and TPUI has unbound; diagnostics
     * and ordinary Winlator activity lifetimes are unaffected.
     */
    private static void requestProductionProcessExitLocked() {
        productionProcessExitPending = true;
        scheduleProductionProcessExitLocked(
            bridgeClientBound
                ? PROCESS_EXIT_FALLBACK_DELAY_MS
                : PROCESS_EXIT_AFTER_UNBIND_DELAY_MS);
    }

    private static void cancelProductionProcessExitLocked() {
        productionProcessExitPending = false;
        if (scheduledProcessExit != null) {
            processExitHandler.removeCallbacks(scheduledProcessExit);
            scheduledProcessExit = null;
        }
    }

    private static void scheduleProductionProcessExitLocked(long delayMs) {
        if (!productionProcessExitPending || session != null)
            return;
        if (scheduledProcessExit != null)
            processExitHandler.removeCallbacks(scheduledProcessExit);

        scheduledProcessExit = () -> {
            synchronized (lock) {
                if (!productionProcessExitPending || bridgeClientBound ||
                    session != null) {
                    scheduledProcessExit = null;
                    return;
                }
                productionProcessExitPending = false;
                scheduledProcessExit = null;
            }
            Log.i(TAG,
                "Recycling the managed Winlator process after production session teardown.");
            android.os.Process.killProcess(android.os.Process.myPid());
        };
        processExitHandler.postDelayed(scheduledProcessExit, delayMs);
    }

    private void closeSessionLocked() {
        closeSessionLocked(true);
    }

    private void closeSessionLocked(boolean removeFiles) {
        if (session != null) {
            invokeGuestBackend("stop", new Class<?>[]{String.class}, session.id);
            ForwardedInputSessionRegistry.unregister(session.id);
            session.close(removeFiles);
            session = null;
        }
        retainSessionFilesOnDestroy = false;
    }

    private String invokeGuestBackend(String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Class<?> backendClass = Class.forName(GUEST_BACKEND_CLASS);
            Method method = backendClass.getMethod(methodName, parameterTypes);
            Object result = method.invoke(null, arguments);
            return result != null ? result.toString() : "state=stopped";
        }
        catch (ClassNotFoundException error) {
            return "state=unsupported;error=full Winlator guest backend is not installed";
        }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            Log.e(TAG, "Guest backend " + methodName + " failed.", cause);
            return "state=fault;error=" + message.replace(';', ',');
        }
        catch (ReflectiveOperationException error) {
            Log.e(TAG, "Could not invoke guest backend " + methodName + ".", error);
            return "state=fault;error=" + error.getClass().getSimpleName();
        }
    }

    private static final class BridgeSession implements AutoCloseable {
        private final Object pageLock = new Object();
        private final String id;
        private final SessionContract.PreparedRequest preparedRequest;
        private final File sessionDirectory;
        private final File pageFile;
        private final RandomAccessFile randomAccessFile;
        private final MappedByteBuffer page;
        private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        private int pipeMessages;
        private String lastError = "";
        private String guestPagePath;
        private boolean closed;

        BridgeSession(File filesDirectory) throws IOException {
            this(filesDirectory, null);
        }

        BridgeSession(File filesDirectory, SessionContract.PreparedRequest request) throws IOException {
            preparedRequest = request;
            id = request != null ? request.sessionId : BridgeProtocol.newSessionId();
            sessionDirectory = new File(filesDirectory, "teknoparrot/sessions/" + id);
            if (request != null && sessionDirectory.exists())
                throw new IOException("The requested session id already exists and cannot be reused.");
            if (!sessionDirectory.exists() && !sessionDirectory.mkdirs())
                throw new IOException("Could not create Winlator bridge session directory.");

            pageFile = new File(sessionDirectory, "TeknoParrot_JvsState.page");
            randomAccessFile = new RandomAccessFile(pageFile, "rw");
            randomAccessFile.setLength(BridgeProtocol.PAGE_SIZE);
            page = randomAccessFile.getChannel()
                .map(FileChannel.MapMode.READ_WRITE, 0, BridgeProtocol.PAGE_SIZE);
            page.order(ByteOrder.LITTLE_ENDIAN);
            initializePage();
            heartbeat.scheduleAtFixedRate(this::publishHeartbeat, 0, 50, TimeUnit.MILLISECONDS);
        }

        ParcelFileDescriptor openPage() throws IOException {
            return ParcelFileDescriptor.open(pageFile, ParcelFileDescriptor.MODE_READ_WRITE);
        }

        String getPagePath() {
            return pageFile.getAbsolutePath();
        }

        String getGuestPagePath() {
            return guestPagePath;
        }

        void setGuestPagePath(String value) {
            guestPagePath = value;
        }

        SessionContract.PreparedRequest getPreparedRequest() {
            return preparedRequest;
        }

        String getStatus() {
            synchronized (pageLock) {
                int hostSequence = page.getInt(BridgeProtocol.HOST_SEQUENCE_OFFSET);
                int guestSequence = page.getInt(BridgeProtocol.GUEST_SEQUENCE_OFFSET);
                int flags = page.getInt(BridgeProtocol.FLAGS_OFFSET);
                if (hostSequence != 0) {
                    flags |= BridgeProtocol.FLAG_GUEST_TOUCHED_PAGE;
                    page.putInt(BridgeProtocol.FLAGS_OFFSET, flags);
                }
                return "state=ready;session=" + id +
                    ";contract=" + (preparedRequest != null ? preparedRequest.protocolVersion : 1) +
                    ";pipeMessages=" + pipeMessages +
                    ";hostSeq=" + Integer.toUnsignedString(hostSequence) +
                    ";guestSeq=" + Integer.toUnsignedString(guestSequence) +
                    ";flags=0x" + String.format("%08X", flags) + ";error=" + lastError;
            }
        }

        String runPipeProbe(int port, byte[] token) throws IOException {
            if (port < 1 || port > 65535)
                throw new IllegalArgumentException("Invalid loopback port.");

            long started = System.nanoTime();
            long maximumNanos = 0;
            try (Socket socket = new Socket()) {
                socket.setTcpNoDelay(true);
                // Match TeknoParrotUI's explicit IPAddress.Loopback listener.
                // InetAddress.getLoopbackAddress() may select ::1 on Android,
                // which cannot reach an IPv4-only listener.
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                socket.setSoTimeout(5000);

                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                DataInputStream input = new DataInputStream(socket.getInputStream());
                output.write(BridgeProtocol.createHandshake(id, token));
                output.flush();

                byte[] acknowledgement = new byte[4];
                input.readFully(acknowledgement);
                if (!MessageDigest.isEqual(acknowledgement, "OKAY".getBytes(StandardCharsets.US_ASCII)))
                    throw new IOException("TeknoParrotUI rejected the TPB1 handshake.");

                synchronized (pageLock) {
                    page.putInt(BridgeProtocol.FLAGS_OFFSET,
                        page.getInt(BridgeProtocol.FLAGS_OFFSET) | BridgeProtocol.FLAG_PIPE_AUTHENTICATED);
                }

                for (int i = 0; i < 16; i++) {
                    byte[] payload = ("winlator-frame-" + String.format("%02d", i) + "-" + System.nanoTime())
                        .getBytes(StandardCharsets.UTF_8);
                    long frameStarted = System.nanoTime();
                    output.writeInt(payload.length);
                    output.write(payload);
                    output.flush();

                    int responseLength = input.readInt();
                    if (responseLength != payload.length || responseLength > BridgeProtocol.MAX_FRAME_BYTES)
                        throw new IOException("Echo frame length mismatch.");
                    byte[] response = new byte[responseLength];
                    input.readFully(response);
                    if (!Arrays.equals(payload, response))
                        throw new IOException("Echo frame payload mismatch.");

                    maximumNanos = Math.max(maximumNanos, System.nanoTime() - frameStarted);
                    synchronized (pageLock) {
                        pipeMessages++;
                    }
                }
            }

            double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
            return "frames=16;elapsedMs=" + String.format("%.2f", elapsedMs) +
                ";maxFrameMs=" + String.format("%.2f", maximumNanos / 1_000_000.0);
        }

        String runPreparedInputDiagnostic() throws IOException {
            if (preparedRequest == null)
                throw new IllegalStateException("A prepared request is required for forwarded input.");

            final long deviceStableId = 0xA0B0C0D0L;
            try (ForwardedInputClient input = new ForwardedInputClient(
                    id,
                    preparedRequest.pipePort,
                    BridgeProtocol.hexToBytes(preparedRequest.tokenHex, 32))) {
                input.start();
                if (!input.awaitConnected(5000))
                    throw new IOException(
                            "The production TPI1 client did not authenticate: " + input.getLastError());

                input.sendButton(deviceStableId, 0, ForwardedInputProtocol.BUTTON_COIN,
                        true, System.nanoTime());
                input.sendAxis(deviceStableId, 0, 2, (short)-12345, 256, System.nanoTime());
                input.sendPointerAbsolute(deviceStableId, 0, 2,
                        12345, 23456, 30000, 9, 1, System.nanoTime());
                input.sendButton(deviceStableId, 0, ForwardedInputProtocol.BUTTON_COIN,
                        false, System.nanoTime());
                input.sendButton(deviceStableId, 0, ForwardedInputProtocol.BUTTON_START,
                        true, System.nanoTime());
                input.sendFocus(false, System.nanoTime());

                if (!input.awaitDataFramesSent(6, 5000))
                    throw new IOException(
                            "The production TPI1 client did not drain its queue: " +
                            input.getLastError());
                return "frames=" + input.getDataFramesSent() +
                        ";queueRemaining=" + input.getQueueSize() +
                        ";resync=" + input.getResynchronizations() +
                        ";dropped=" + input.getDroppedFrames();
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("The production TPI1 diagnostic was interrupted.", error);
            }
        }

        void setError(String message) {
            synchronized (pageLock) {
                lastError = message == null ? "unknown" : message.replace(';', ',');
                page.putInt(BridgeProtocol.FLAGS_OFFSET,
                    page.getInt(BridgeProtocol.FLAGS_OFFSET) | BridgeProtocol.FLAG_FAULT);
            }
        }

        private void initializePage() {
            synchronized (pageLock) {
                for (int i = 0; i < BridgeProtocol.PAGE_SIZE; i++)
                    page.put(i, (byte)0);
                page.position(BridgeProtocol.MAGIC_OFFSET);
                page.put("TPJ1".getBytes(StandardCharsets.US_ASCII));
                page.putShort(BridgeProtocol.LAYOUT_VERSION_OFFSET, (short)BridgeProtocol.VERSION);
                page.putShort(BridgeProtocol.HEADER_SIZE_OFFSET, (short)128);
                page.putInt(BridgeProtocol.TOTAL_SIZE_OFFSET, BridgeProtocol.PAGE_SIZE);
                page.putInt(BridgeProtocol.GUEST_SEQUENCE_OFFSET, 1);
                page.putLong(BridgeProtocol.GUEST_TIMESTAMP_OFFSET, System.nanoTime());
            }
        }

        private void publishHeartbeat() {
            synchronized (pageLock) {
                if (closed)
                    return;
                page.putLong(BridgeProtocol.GUEST_TIMESTAMP_OFFSET, System.nanoTime());
                page.putInt(BridgeProtocol.GUEST_SEQUENCE_OFFSET,
                    page.getInt(BridgeProtocol.GUEST_SEQUENCE_OFFSET) + 1);
            }
        }

        @Override
        public void close() {
            close(true);
        }

        void close(boolean removeFiles) {
            synchronized (pageLock) {
                if (closed)
                    return;
                closed = true;
                page.putInt(BridgeProtocol.FLAGS_OFFSET,
                    page.getInt(BridgeProtocol.FLAGS_OFFSET) | BridgeProtocol.FLAG_STOPPING);
            }

            heartbeat.shutdownNow();
            try {
                randomAccessFile.close();
            }
            catch (IOException ignored) {
            }

            if (removeFiles) {
                if (pageFile.exists() && !pageFile.delete())
                    Log.w(TAG, "Could not delete bridge page for session " + id);
                if (sessionDirectory.exists() && !sessionDirectory.delete())
                    Log.w(TAG, "Could not delete empty bridge directory for session " + id);
            }
        }
    }
}
