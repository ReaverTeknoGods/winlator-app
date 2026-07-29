package com.winlator.core;

import android.os.Process;
import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.BuildConfig;
import com.winlator.MainActivity;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public abstract class ProcessHelper {
    private static final String TAG = "WinlatorProcess";
    public enum PState {RUNNING, SLEEPING, WAITING, ZOMBIE, STOPPED, DEAD, OTHER}
    private static final ArrayList<Callback<String>> debugCallbacks = new ArrayList<>();
    private static volatile boolean outputSuppressed;
    private static final byte SIGCONT = 18;
    private static final byte SIGSTOP = 19;

    public static class PStat {
        public int pid = 0;
        public String name = "";
        public PState state = PState.OTHER;
        public int parentPID = 0;
        public boolean guestProcess = false;

        @NonNull
        @Override
        public String toString() {
            return pid+" "+name+" "+state+" "+parentPID+" "+guestProcess;
        }
    }

    public static void suspendProcess(int pid) {
        Process.sendSignal(pid, SIGSTOP);
    }

    public static void resumeProcess(int pid) {
        Process.sendSignal(pid, SIGCONT);
    }

    /** Terminates Wine/Windows children owned by this Winlator process. */
    public static void killGuestProcesses() {
        int ownPid = Os.getpid();
        for (PStat process : getChildProcesses()) {
            if (!process.guestProcess || process.pid <= 0 || process.pid == ownPid)
                continue;
            // A background XServer Activity suspends guest processes. Resume
            // them first so SIGKILL is observed consistently on device kernels.
            if (process.state == PState.STOPPED)
                resumeProcess(process.pid);
            Process.killProcess(process.pid);
        }
    }

    /**
     * Returns whether this application still owns a live guest whose process
     * name contains the requested marker.
     *
     * Do not match the complete command line here. Parent Wine/Explorer
     * processes retain the launched executable as an argument after the real
     * child exits and would otherwise keep a failed session alive forever.
     */
    public static boolean hasLiveGuestProcessName(String marker) {
        if (marker == null || marker.isEmpty()) return false;
        String normalizedMarker = marker.toLowerCase(java.util.Locale.ROOT);
        for (PStat process : getChildProcesses()) {
            if (!process.guestProcess ||
                process.state == PState.ZOMBIE ||
                process.state == PState.DEAD)
                continue;
            if (process.name.toLowerCase(java.util.Locale.ROOT)
                    .contains(normalizedMarker))
                return true;
        }
        return false;
    }

    public static int exec(String command) {
        return exec(command, null);
    }

    public static int exec(String command, EnvVars envVars) {
        return exec(command, envVars, null);
    }

    public static int exec(String command, EnvVars envVars, File workingDir) {
        return exec(command, envVars, workingDir, null);
    }

    public static int exec(String command, EnvVars envVars, File workingDir, Callback<Integer> terminationCallback) {
        int pid = -1;
        try {
            ProcessBuilder processBuilder = (new ProcessBuilder(splitCommand(command))).directory(workingDir);
            if (outputSuppressed || (debugCallbacks.isEmpty() && !BuildConfig.DEBUG)) {
                processBuilder.redirectOutput(new File("/dev/null")).redirectErrorStream(true);
            }

            Map<String, String> environment = processBuilder.environment();
            if (envVars != null) {
                for (String name : envVars) environment.put(name, envVars.get(name));
            }

            java.lang.Process process = processBuilder.start();
            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            pid = pidField.getInt(process);
            pidField.setAccessible(false);

            if (outputSuppressed) {
                // Performance-mode prepared launches discard guest output at
                // the OS boundary, including in debug-signed test APKs.
            }
            else if (!debugCallbacks.isEmpty()) {
                createDebugThread(process.getInputStream());
                createDebugThread(process.getErrorStream());
            }
            else if (BuildConfig.DEBUG) {
                createLogThread(process.getInputStream());
                createLogThread(process.getErrorStream());
            }

            if (terminationCallback != null) createWaitForThread(process, terminationCallback);
        }
        catch (Exception e) {
            Log.e(TAG, "Could not execute guest process.", e);
        }
        return pid;
    }

    public static void setOutputSuppressed(boolean suppressed) {
        outputSuppressed = suppressed;
    }

    public static boolean isOutputSuppressed() {
        return outputSuppressed;
    }

    private static void createLogThread(final InputStream inputStream) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) Log.d(TAG, line);
            }
            catch (IOException e) {
                Log.e(TAG, "Could not read guest process output.", e);
            }
        });
    }

    private static void createDebugThread(final InputStream inputStream) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Reaching this path already requires an explicitly enabled
                    // debug callback. Keep per-game troubleshooting useful in
                    // release APKs as well as debug builds, while normal launches
                    // continue to discard guest output at the process boundary.
                    Log.d(TAG, line);
                    synchronized (debugCallbacks) {
                        if (!debugCallbacks.isEmpty()) {
                            for (Callback<String> callback : debugCallbacks) callback.call(line);
                        }
                        else if (MainActivity.DEBUG_MODE) System.out.println(line);
                    }
                }
            }
            catch (IOException e) {}
        });
    }

    private static void createWaitForThread(java.lang.Process process, final Callback<Integer> terminationCallback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                int status = process.waitFor();
                terminationCallback.call(status);
            }
            catch (InterruptedException e) {}
        });
    }

    public static void removeAllDebugCallbacks() {
        synchronized (debugCallbacks) {
            debugCallbacks.clear();
        }
    }

    public static void addDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            if (!debugCallbacks.contains(callback)) debugCallbacks.add(callback);
        }
    }

    public static void removeDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            debugCallbacks.remove(callback);
        }
    }

    public static String[] splitCommand(String command) {
        ArrayList<String> result = new ArrayList<>();
        boolean startedQuotes = false;
        String value = "";
        char currChar, nextChar;
        for (int i = 0, count = command.length(); i < count; i++) {
            currChar = command.charAt(i);

            if (startedQuotes) {
                if (currChar == '"') {
                    startedQuotes = false;
                    if (!value.isEmpty()) {
                        value += '"';
                        result.add(value);
                        value = "";
                    }
                }
                else value += currChar;
            }
            else if (currChar == '"') {
                startedQuotes = true;
                value += '"';
            }
            else {
                nextChar = i < count-1 ? command.charAt(i+1) : '\0';
                if (currChar == ' ' || (currChar == '\\' && nextChar == ' ')) {
                    if (currChar == '\\') {
                        value += ' ';
                        i++;
                    }
                    else if (!value.isEmpty()) {
                        result.add(value);
                        value = "";
                    }
                }
                else {
                    value += currChar;
                    if (i == count-1) {
                        result.add(value);
                        value = "";
                    }
                }
            }
        }

        return result.toArray(new String[0]);
    }

    public static String getAffinityMaskAsHexString(String cpuList) {
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return Integer.toHexString(affinityMask);
    }

    public static int getAffinityMask(String cpuList) {
        if (cpuList == null || cpuList.isEmpty()) return 0;
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return affinityMask;
    }

    public static int getAffinityMask(boolean[] cpuList) {
        int affinityMask = 0;
        for (int i = 0; i < cpuList.length; i++) {
            if (cpuList[i]) affinityMask |= (int)Math.pow(2, i);
        }
        return affinityMask;
    }

    public static int getAffinityMask(int from, int to) {
        int affinityMask = 0;
        for (int i = from; i < to; i++) affinityMask |= (int)Math.pow(2, i);
        return affinityMask;
    }

    public static List<PStat> getChildProcesses() {
        File procFile = new File("/proc");
        String[] pids = procFile.list((file, name) -> (new File(file, name)).isDirectory() && name.matches("[0-9]+"));
        if (pids == null) return Collections.emptyList();
        ArrayList<PStat> result = new ArrayList<>();
        int parentPID = Os.getpid();

        for (String pid : pids) {
            String processPath = "/proc/"+pid;
            try (Scanner scanner = new Scanner(new FileInputStream(processPath+"/stat"))) {
                PStat pstat = new PStat();
                int index = 0;

                while (scanner.hasNext() && index < 4) {
                    switch (index++) {
                        case 0:
                            pstat.pid = scanner.nextInt();
                            break;
                        case 1:
                            Pattern oldDelimiter = scanner.delimiter();
                            scanner.useDelimiter("\\)");
                            pstat.name = scanner.hasNext() ? scanner.next().substring(2) : "";
                            scanner.useDelimiter(oldDelimiter);
                            if (scanner.hasNext()) scanner.next();
                            break;
                        case 2: {
                            switch (scanner.next()) {
                                case "R":
                                    pstat.state = PState.RUNNING;
                                    break;
                                case "S":
                                    pstat.state = PState.SLEEPING;
                                    break;
                                case "D":
                                    pstat.state = PState.WAITING;
                                    break;
                                case "Z":
                                    pstat.state = PState.ZOMBIE;
                                    break;
                                case "T":
                                    pstat.state = PState.STOPPED;
                                    break;
                                case "X":
                                    pstat.state = PState.DEAD;
                                    break;
                            }
                            break;
                        }
                        case 3:
                            pstat.parentPID = scanner.nextInt();
                            break;
                    }
                }

                String commandLine = readProcCommandLine(processPath+"/cmdline");
                String normalizedName = pstat.name.toLowerCase(java.util.Locale.ROOT);
                String normalizedCommandLine = commandLine.toLowerCase(java.util.Locale.ROOT);
                boolean ownedByApplication = Os.stat(processPath).st_uid == Os.getuid();

                // Linux truncates /proc/<pid>/stat's comm field to 15
                // characters, so long titles such as SWArcGame-Win64-
                // Shipping.exe lose the suffix that identifies them as Wine
                // guests. The complete cmdline retains Wine's rootfs path.
                // Scope that test to this Android application's UID so cleanup
                // can never target another package or a host process.
                pstat.guestProcess = ownedByApplication &&
                    (normalizedName.contains("wine") ||
                     normalizedName.contains(".exe") ||
                     normalizedName.startsWith("openparrot") ||
                     normalizedName.startsWith("pipehelper") ||
                     normalizedName.startsWith("bridgeguest") ||
                     normalizedName.startsWith("teknoparrot-") ||
                     normalizedCommandLine.contains("/rootfs/opt/wine/") ||
                     normalizedCommandLine.contains(".exe"));

                if (pstat.parentPID == parentPID ||
                    pstat.pid > parentPID ||
                    pstat.guestProcess) {
                    result.add(pstat);
                }
            }
            catch (Exception e) {
                // Processes routinely disappear while /proc is enumerated.
                // Keep the entries already collected so one normal exit does
                // not cancel cleanup for every remaining guest process.
                continue;
            }
        }

        return result;
    }

    private static String readProcCommandLine(String filename) {
        try (InputStream inputStream = new FileInputStream(filename);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = inputStream.read(buffer)) > 0 && outputStream.size() < 16384) {
                outputStream.write(buffer, 0, Math.min(count, 16384 - outputStream.size()));
            }
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8)
                .replace('\0', ' ');
        }
        catch (IOException ignored) {
            // The process may exit between the stat and cmdline reads.
            return "";
        }
    }
}
