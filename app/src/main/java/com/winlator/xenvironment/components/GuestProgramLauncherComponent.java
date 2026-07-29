package com.winlator.xenvironment.components;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64Preset;
import com.winlator.box64.Box64PresetManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PackagePathCompat;
import com.winlator.core.ProcessHelper;
import com.winlator.widget.LogView;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private static final String TAG = "GuestProgramLauncher";
    private static final int ELF_PROGRAM_HEADER_TYPE_INTERP = 3;
    private static final String WINE_GSTREAMER_BOX64_ENV = "TP_BOX64_WINEGSTREAMER_FIX";
    private static final String WINE_GSTREAMER_BOX64_NAME = "box64-winegstreamer";
    private static final long BOX64_GSTREAMER_SET_NEEDED_LIBS_CALL = 0x350C840CL;
    private static final int BOX64_GSTREAMER_SET_NEEDED_LIBS_INSTRUCTION = 0x97E87C65;
    private static final int AARCH64_NOP_INSTRUCTION = 0xD503201F;
    private String guestExecutable;
    private int pid = -1;
    private EnvVars envVars;
    private String box64Preset = Box64Preset.CONSERVATIVE;
    private Callback<Integer> terminationCallback;
    private final Object lock = new Object();

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            extractBox64File();
            if (!ensureBox64Interpreter()) {
                pid = -1;
                return;
            }
            if (usesWineGStreamerBox64() && !ensureWineGStreamerBox64()) {
                pid = -1;
                return;
            }
            if (!PackagePathCompat.ensureRootfs(environment.getContext(), environment.getRootFS().getRootDir())) {
                pid = -1;
                return;
            }
            copyDefaultBox64RCFile();
            pid = execGuestProgram();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    private int execGuestProgram() {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();

        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars);
        LocaleHelper.setEnvVars(envVars);

        envVars.put("HOME", rootDir+RootFS.HOME_PATH);
        envVars.put("USER", RootFS.USER);
        envVars.put("TMPDIR", rootDir+"/tmp");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", rootDir+rootFS.getWinePath()+"/bin:"+rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");
        envVars.put("LD_LIBRARY_PATH", rootFS.getLibDir().getPath());
        envVars.put("BOX64_LD_LIBRARY_PATH", rootDir+"/lib/x86_64-linux-gnu");
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);

        if (this.envVars != null) envVars.putAll(this.envVars);

        File shmDir = new File(rootDir, "/tmp/shm");
        if (!shmDir.isDirectory()) shmDir.mkdirs();

        String box64Name = usesWineGStreamerBox64() ? WINE_GSTREAMER_BOX64_NAME : "box64";
        String command = rootDir+"/usr/local/bin/"+box64Name+" "+guestExecutable;

        final boolean suppressExitLog = ProcessHelper.isOutputSuppressed();
        return ProcessHelper.exec(command, envVars, rootDir, (status) -> {
            if (!suppressExitLog)
                Log.i(TAG, "Box64 guest process exited with status "+status+".");
            synchronized (lock) {
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        });
    }

    /**
     * The bundled Box64 binary is pinned and may contain the upstream package
     * name in its ELF PT_INTERP entry.  Calling the loader explicitly only
     * fixes the first process: Box64 must be directly executable when Wine
     * starts another x86 process.  Point PT_INTERP at a short, package-local
     * symlink so companion application IDs work without rebuilding Box64.
     */
    private boolean ensureBox64Interpreter() {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        File box64File = new File(rootFS.getRootDir(), "/usr/local/bin/box64");
        File loaderFile = new File(rootFS.getRootDir(), "/lib/ld-linux-aarch64.so.1");
        File interpreterLink = new File(context.getDataDir(), "ld");

        try {
            ensureSymlink(interpreterLink, loaderFile);
            patchElfInterpreter(box64File, interpreterLink.getPath());
            return true;
        }
        catch (Exception e) {
            Log.e(TAG, "Could not prepare Box64 for application ID "+context.getPackageName()+".", e);
            return false;
        }
    }

    /**
     * Box64 0.4.0's GStreamer wrapper has an erroneous hard dependency on
     * libgtk-3.so.0. Winlator's compact rootfs intentionally has no GTK, and
     * BOX64_NOGTK disables GStreamer itself. Keep the normal Box64 executable
     * pristine and create a media-only copy that skips GStreamer's call to
     * setNeededLibs("libgtk-3.so.0"). Patching the one call instruction is
     * important: the compiler shares the libgtk string and its data relocation
     * with Box64's actual GTK loader, so changing either breaks Wine Explorer.
     *
     * Box64 also discovers native GStreamer plugins by comparing them with a
     * Wine-relative x86 plugin directory. Mirror only the filenames; Box64 then
     * loads the real AArch64 plugins from /usr/lib/gstreamer-1.0.
     */
    private boolean ensureWineGStreamerBox64() {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();
        File source = new File(rootDir, "/usr/local/bin/box64");
        File patched = new File(rootDir, "/usr/local/bin/"+WINE_GSTREAMER_BOX64_NAME);

        try {
            if (!isCurrentWineGStreamerBox64(source, patched)) {
                if (!FileUtils.copy(source, patched))
                    throw new IOException("Could not copy Box64 for Wine-GStreamer.");
                patchBox64GStreamerDependency(patched);
                if (!patched.setLastModified(source.lastModified()))
                    Log.w(TAG, "Could not preserve the Wine-GStreamer Box64 timestamp.");
            }
            Os.chmod(patched.getPath(), 0700);
            ensureWineGStreamerPluginMirror(rootFS);
            return true;
        }
        catch (Exception e) {
            Log.e(TAG, "Could not prepare the Wine-GStreamer Box64 runtime.", e);
            return false;
        }
    }

    private boolean usesWineGStreamerBox64() {
        return envVars != null && "1".equals(envVars.get(WINE_GSTREAMER_BOX64_ENV));
    }

    private static boolean isCurrentWineGStreamerBox64(File source, File patched)
            throws IOException {
        if (!source.isFile() || !patched.isFile() || source.length() != patched.length() ||
            source.lastModified() != patched.lastModified()) return false;
        return readBox64GStreamerDependencyInstruction(source) ==
                BOX64_GSTREAMER_SET_NEEDED_LIBS_INSTRUCTION &&
            readBox64GStreamerDependencyInstruction(patched) == AARCH64_NOP_INSTRUCTION;
    }

    private static void patchBox64GStreamerDependency(File executable) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(executable, "rw")) {
            long instructionOffset = findElfVirtualAddressFileOffset(
                file, BOX64_GSTREAMER_SET_NEEDED_LIBS_CALL, Integer.BYTES);
            file.seek(instructionOffset);
            int instruction = readIntLE(file);
            if (instruction != BOX64_GSTREAMER_SET_NEEDED_LIBS_INSTRUCTION)
                throw new IOException("Unexpected Box64 GStreamer dependency instruction.");
            file.seek(instructionOffset);
            writeIntLE(file, AARCH64_NOP_INSTRUCTION);
        }

        if (readBox64GStreamerDependencyInstruction(executable) != AARCH64_NOP_INSTRUCTION)
            throw new IOException("Box64 Wine-GStreamer instruction patch did not validate.");
        Log.i(TAG, "Prepared the media-only Box64 Wine-GStreamer executable.");
    }

    private static int readBox64GStreamerDependencyInstruction(File executable)
            throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(executable, "r")) {
            long instructionOffset = findElfVirtualAddressFileOffset(
                file, BOX64_GSTREAMER_SET_NEEDED_LIBS_CALL, Integer.BYTES);
            file.seek(instructionOffset);
            return readIntLE(file);
        }
    }

    private static long findElfVirtualAddressFileOffset(
            RandomAccessFile file, long virtualAddress, int byteCount) throws IOException {
        byte[] ident = new byte[16];
        file.seek(0);
        file.readFully(ident);
        if (ident[0] != 0x7f || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F' ||
            ident[4] != 2 || ident[5] != 1) {
            throw new IOException("Box64 is not a little-endian ELF64 executable.");
        }

        file.seek(40);
        long sectionHeaderOffset = readLongLE(file);
        file.seek(58);
        int sectionHeaderSize = readUnsignedShortLE(file);
        int sectionHeaderCount = readUnsignedShortLE(file);
        if (sectionHeaderSize < 64 || sectionHeaderOffset <= 0 ||
            sectionHeaderOffset > file.length()-(long)sectionHeaderSize*sectionHeaderCount) {
            throw new IOException("Box64 has an invalid ELF section table.");
        }

        for (int section = 0; section < sectionHeaderCount; section++) {
            long headerOffset = sectionHeaderOffset+(long)section*sectionHeaderSize;
            file.seek(headerOffset+16);
            long sectionAddress = readLongLE(file);
            long sectionFileOffset = readLongLE(file);
            long sectionSize = readLongLE(file);
            if (sectionSize < byteCount || virtualAddress < sectionAddress ||
                virtualAddress-sectionAddress > sectionSize-byteCount) continue;

            long result = sectionFileOffset+(virtualAddress-sectionAddress);
            if (result < 0 || result > file.length()-byteCount)
                throw new IOException("Box64 ELF virtual address has no file backing.");
            return result;
        }
        throw new IOException("Box64 GStreamer instruction address was not found.");
    }

    private static void ensureWineGStreamerPluginMirror(RootFS rootFS) throws IOException {
        File rootDir = rootFS.getRootDir();
        File nativePluginDir = new File(rootDir, "/usr/lib/gstreamer-1.0");
        String[] pluginNames = nativePluginDir.list((dir, name) ->
            name.startsWith("libgst") && name.endsWith(".so"));
        if (pluginNames == null || pluginNames.length == 0)
            throw new IOException("Native GStreamer plugins are missing from the rootfs.");

        // Box64 derives this folder from the executable it receives. Cover
        // both bin/wine and Wine's x86_64-unix preloader target; Winlator uses
        // the latter, which resolves ../lib64 below opt/wine/lib/wine.
        String wineRoot = rootDir.getPath()+rootFS.getWinePath();
        String[] mirrorPaths = {
            wineRoot+"/lib64/gstreamer-1.0",
            wineRoot+"/lib/wine/lib64/gstreamer-1.0"
        };
        for (String mirrorPath : mirrorPaths) {
            File winePluginDir = new File(mirrorPath);
            if (!winePluginDir.isDirectory() && !winePluginDir.mkdirs())
                throw new IOException("Could not create the Box64 GStreamer plugin mirror.");

            for (String pluginName : pluginNames) {
                File marker = new File(winePluginDir, pluginName);
                if (!marker.exists() && !marker.createNewFile())
                    throw new IOException("Could not mirror GStreamer plugin "+pluginName+".");
            }
        }
        Log.i(TAG, "Exposed "+pluginNames.length+
            " native GStreamer plugins through "+mirrorPaths.length+" Box64 search paths.");
    }

    private static void ensureSymlink(File link, File target) throws IOException, ErrnoException {
        try {
            String currentTarget = Os.readlink(link.getPath());
            if (!currentTarget.equals(target.getPath())) {
                throw new IOException("Unexpected Box64 interpreter symlink target: "+currentTarget);
            }
        }
        catch (ErrnoException e) {
            if (e.errno != OsConstants.ENOENT) throw e;
            Os.symlink(target.getPath(), link.getPath());
        }
    }

    private static void patchElfInterpreter(File executable, String interpreterPath) throws IOException {
        byte[] replacement = interpreterPath.getBytes(StandardCharsets.UTF_8);
        long interpreterOffset = -1;
        int interpreterSize = 0;

        // Inspect the running Box64 image read-only first. Android rejects an
        // O_RDWR open with ETXTBSY after the bridge launcher has started it,
        // even when the interpreter is already correct and no write is needed.
        try (RandomAccessFile file = new RandomAccessFile(executable, "r")) {
            byte[] ident = new byte[16];
            file.readFully(ident);
            if (ident[0] != 0x7f || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F' ||
                ident[4] != 2 || ident[5] != 1) {
                throw new IOException("Box64 is not a little-endian ELF64 executable.");
            }

            file.seek(32);
            long programHeaderOffset = readLongLE(file);
            file.seek(54);
            int programHeaderSize = readUnsignedShortLE(file);
            int programHeaderCount = readUnsignedShortLE(file);

            for (int i = 0; i < programHeaderCount; i++) {
                long headerOffset = programHeaderOffset+(long)i*programHeaderSize;
                file.seek(headerOffset);
                int type = readIntLE(file);
                if (type != ELF_PROGRAM_HEADER_TYPE_INTERP) continue;

                file.seek(headerOffset+8);
                interpreterOffset = readLongLE(file);
                file.seek(headerOffset+32);
                long interpreterSizeLong = readLongLE(file);
                if (interpreterSizeLong <= 1 || interpreterSizeLong > Integer.MAX_VALUE ||
                    replacement.length+1 > interpreterSizeLong) {
                    throw new IOException("Package-local ELF interpreter path does not fit in Box64.");
                }
                interpreterSize = (int)interpreterSizeLong;

                byte[] currentBytes = new byte[interpreterSize];
                file.seek(interpreterOffset);
                file.readFully(currentBytes);
                int currentLength = 0;
                while (currentLength < currentBytes.length && currentBytes[currentLength] != 0) currentLength++;
                String currentPath = new String(currentBytes, 0, currentLength, StandardCharsets.UTF_8);
                if (currentPath.equals(interpreterPath)) return;
                if (!currentPath.endsWith("/lib/ld-linux-aarch64.so.1")) {
                    throw new IOException("Unexpected Box64 ELF interpreter: "+currentPath);
                }
                break;
            }
        }

        if (interpreterOffset < 0)
            throw new IOException("Box64 ELF PT_INTERP entry was not found.");

        byte[] patchedBytes = new byte[interpreterSize];
        Arrays.fill(patchedBytes, (byte)0);
        System.arraycopy(replacement, 0, patchedBytes, 0, replacement.length);
        try (RandomAccessFile file = new RandomAccessFile(executable, "rw")) {
            file.seek(interpreterOffset);
            file.write(patchedBytes);
        }
        Log.i(TAG, "Patched Box64 ELF interpreter to "+interpreterPath+".");
    }

    private static int readUnsignedShortLE(RandomAccessFile file) throws IOException {
        return file.readUnsignedByte() | file.readUnsignedByte() << 8;
    }

    private static int readIntLE(RandomAccessFile file) throws IOException {
        return file.readUnsignedByte() |
            file.readUnsignedByte() << 8 |
            file.readUnsignedByte() << 16 |
            file.readUnsignedByte() << 24;
    }

    private static long readLongLE(RandomAccessFile file) throws IOException {
        return Integer.toUnsignedLong(readIntLE(file)) |
            Integer.toUnsignedLong(readIntLE(file)) << 32;
    }

    private static void writeIntLE(RandomAccessFile file, int value) throws IOException {
        for (int i = 0; i < Integer.BYTES; i++) {
            file.write((int)(value >>> i*8) & 0xff);
        }
    }

    private void extractBox64File() {
        Context context = environment.getContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String box64Version = preferences.getString("box64_version", DefaultVersion.BOX64);
        String currentBox64Version = preferences.getString("current_box64_version", "");

        if (!box64Version.equals(currentBox64Version)) {
            GeneralComponents.extractFile(GeneralComponents.Type.BOX64, context, box64Version, DefaultVersion.BOX64);
            preferences.edit().putString("current_box64_version", box64Version).apply();
        }
    }

    private void copyDefaultBox64RCFile() {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        FileUtils.copy(context, "box64/default.box64rc", new File(rootFS.getRootDir(), "/etc/config.box64rc"));
    }

    private void addBox64EnvVars(EnvVars envVars) {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        int box64Logs = preferences.getInt("box64_logs", 0);
        boolean saveToFile = preferences.getBoolean("save_logs_to_file", false);

        envVars.put("BOX64_NOBANNER", box64Logs >= 1 ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");
        envVars.put("BOX64_UNITYPLAYER", "0");

        if (box64Logs >= 1) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");

            if (box64Logs == 2) {
                envVars.put("BOX64_SHOWSEGV", "1");
                envVars.put("BOX64_DLSYM_ERROR", "1");
                envVars.put("BOX64_TRACE_FILE", "stderr");

                if (saveToFile) {
                    File parent = (new File(preferences.getString("log_file", LogView.getLogFile().getPath()))).getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        File traceDir = new File(parent, "trace");
                        if (!traceDir.isDirectory()) traceDir.mkdirs();
                        FileUtils.clear(traceDir);

                        envVars.put("BOX64_TRACE_FILE", traceDir+"/box64-%pid.txt");
                    }
                }
            }
        }

        envVars.putAll(Box64PresetManager.getEnvVars(context, box64Preset));

        File box64RCFile = new File(rootFS.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    @Override
    public void onPause() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = processes.size()-1; i >= 0; i--) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state != ProcessHelper.PState.STOPPED) {
                        ProcessHelper.suspendProcess(process.pid);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = 0; i < processes.size(); i++) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state == ProcessHelper.PState.STOPPED) {
                        ProcessHelper.resumeProcess(process.pid);
                    }
                }
            }
        }
    }
}
