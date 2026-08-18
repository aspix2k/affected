package com.aspix2k.affected.build;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ProcessSupervisorMain {
    public static final int PROTOCOL_MAGIC = 0x41464643;
    public static final int PROTOCOL_VERSION = 1;
    public static final int FRAME_HELLO = 1;
    public static final int FRAME_CONFIG = 2;
    public static final int FRAME_READY = 3;
    public static final int FRAME_START = 4;
    public static final int FRAME_STARTED = 5;
    public static final int FRAME_TARGET_EXIT = 6;
    public static final int FRAME_RELEASE = 7;
    public static final int FRAME_ERROR = 8;
    public static final int MAX_CONTROL_FRAME_BYTES = 4 * 1024 * 1024;
    public static final int MAX_ARGUMENTS = 16_384;
    public static final int MAX_ENVIRONMENT_ENTRIES = 4_096;
    public static final int MAX_STRING_BYTES = 1024 * 1024;
    public static final int TOKEN_BYTES = 32;

    private static final int ESRCH = 3;
    private static final int JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION = 1;
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final int CANCEL_EXIT_CODE = 1;
    private static final long RELEASE_TIMEOUT_SECONDS = 15;
    private static final long OUTPUT_DRAIN_TIMEOUT_MILLIS = 2_000;
    private static final long OUTPUT_RELAY_POLL_MILLIS = 5;
    private static final long SESSION_TERMINATION_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_SESSION_PROCESSES = 4_096;
    private static final AtomicBoolean POSIX_SESSION_ESTABLISHED = new AtomicBoolean();

    private ProcessSupervisorMain() {
    }

    public static void main(String[] arguments) {
        int exitCode = 1;
        try {
            exitCode = run(arguments);
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            emergencyStop();
        }
        Runtime.getRuntime().halt(exitCode);
    }

    public static boolean verifyPosixSession(long pid, long sid, long pgid) {
        if (pid <= 0 || pid > Integer.MAX_VALUE || sid != pid || pgid != pid) return false;
        int nativePid = (int) pid;
        return Posix.INSTANCE.getsid(nativePid) == nativePid && Posix.INSTANCE.getpgid(nativePid) == nativePid;
    }

    public static InetAddress controlAddress() throws IOException {
        return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
    }

    public static boolean terminatePosixSession(ProcessHandle leader, long timeoutMillis) {
        if (Platform.isWindows() || timeoutMillis <= 0) return false;
        long pid = leader.pid();
        if (pid <= 0 || pid > Integer.MAX_VALUE) return false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        boolean interrupted = Thread.interrupted();
        try {
            SessionDrain result = drainPosixSession(leader, deadline, true);
            interrupted |= result.interrupted();
            return result.proven();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static SessionDrain drainPosixSession(ProcessHandle leader, long deadline, boolean terminateLeader) {
        boolean interrupted = false;
        while (System.nanoTime() < deadline) {
            SessionSnapshot snapshot = sessionSnapshot(leader, deadline);
            if (snapshot == null || !snapshot.leaderPresent()) return new SessionDrain(false, interrupted);
            if (snapshot.members().isEmpty()) {
                if (!terminateLeader) return new SessionDrain(true, interrupted);
                if (leader.isAlive() && !leader.destroyForcibly() && leader.isAlive()) {
                    return new SessionDrain(false, interrupted);
                }
                while (leader.isAlive() && System.nanoTime() < deadline) {
                    interrupted |= sleepForTerminationPoll();
                }
                if (leader.isAlive()) return new SessionDrain(false, interrupted);
                SessionSnapshot finalSnapshot = sessionSnapshot(leader.pid(), deadline);
                boolean empty = finalSnapshot != null && !finalSnapshot.leaderPresent()
                    && finalSnapshot.members().isEmpty();
                return new SessionDrain(empty, interrupted);
            }
            for (ProcessHandle member : snapshot.members()) {
                if (member.isAlive() && !member.destroyForcibly() && member.isAlive()) {
                    return new SessionDrain(false, interrupted);
                }
            }
            interrupted |= sleepForTerminationPoll();
        }
        return new SessionDrain(false, interrupted);
    }

    private static SessionSnapshot sessionSnapshot(ProcessHandle leader, long deadline) {
        if (!leader.isAlive() || Posix.INSTANCE.getsid((int) leader.pid()) != (int) leader.pid()) return null;
        return sessionSnapshot(leader.pid(), deadline);
    }

    private static SessionSnapshot sessionSnapshot(long sessionId, long deadline) {
        List<ProcessHandle> members = new ArrayList<>();
        boolean leaderPresent = false;
        try (var processes = ProcessHandle.allProcesses()) {
            var iterator = processes.iterator();
            while (iterator.hasNext()) {
                if (System.nanoTime() >= deadline) return null;
                ProcessHandle process = iterator.next();
                long candidate = process.pid();
                if (candidate <= 0 || candidate > Integer.MAX_VALUE) continue;
                int sid = Posix.INSTANCE.getsid((int) candidate);
                if (sid < 0) {
                    if (Native.getLastError() != ESRCH && process.isAlive()) return null;
                    continue;
                }
                if (sid != sessionId) continue;
                if (candidate == sessionId) {
                    leaderPresent = true;
                } else {
                    if (members.size() >= MAX_SESSION_PROCESSES) return null;
                    members.add(process);
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return new SessionSnapshot(leaderPresent, List.copyOf(members));
    }

    private static boolean sleepForTerminationPoll() {
        try {
            Thread.sleep(25);
            return false;
        } catch (InterruptedException ignored) {
            return true;
        }
    }

    private static int run(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected control port");
        int port = Integer.parseInt(arguments[0]);
        byte[] token = System.in.readNBytes(TOKEN_BYTES);
        if (token.length != TOKEN_BYTES) throw new IOException("Incomplete control token");
        long pid = ProcessHandle.current().pid();
        long sid = 0;
        long pgid = 0;
        if (!Platform.isWindows()) {
            if (!Platform.isLinux() && !Platform.isMac()) throw new IOException("Unsupported operating system");
            int established = Posix.INSTANCE.setsid();
            if (established <= 0 || established != pid) throw new IOException("Could not establish process session");
            sid = Posix.INSTANCE.getsid(0);
            pgid = Posix.INSTANCE.getpgid(0);
            if (sid != pid || pgid != pid) throw new IOException("Process session identity is invalid");
            POSIX_SESSION_ESTABLISHED.set(true);
        }
        AtomicBoolean released = new AtomicBoolean();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(controlAddress(), port), 10_000);
            socket.setTcpNoDelay(true);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            writeHello(output, token, pid, sid, pgid);
            TargetConfig config = readConfig(input);
            writeFrame(output, FRAME_READY, new byte[0]);
            requireEmptyFrame(input, FRAME_START);
            Process target;
            try {
                target = startTarget(config);
            } catch (Throwable error) {
                writeError(output, "Affected could not start the contained command: " + safeMessage(error));
                return 1;
            }
            OutputRelay relay = OutputRelay.start(target, config.redirectErrorStream());
            writeLongFrame(output, FRAME_STARTED, target.pid());
            closeInheritedInput();
            AtomicBoolean targetExited = new AtomicBoolean();
            CountDownLatch release = new CountDownLatch(1);
            Thread control = new Thread(() -> watchControl(input, targetExited, released, release),
                "Affected process supervisor control");
            control.setDaemon(true);
            control.start();
            int targetExit = target.waitFor();
            if (!relay.finish(OUTPUT_DRAIN_TIMEOUT_MILLIS)) {
                throw new IOException("Could not drain contained process output");
            }
            closeInheritedOutput();
            targetExited.set(true);
            writeIntFrame(output, FRAME_TARGET_EXIT, targetExit);
            if (!release.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("The release decision timed out");
            }
            return targetExit;
        } finally {
            if (!released.get()) emergencyStop();
        }
    }

    private static Process startTarget(TargetConfig config) throws IOException {
        ProcessBuilder target = new ProcessBuilder(config.arguments());
        target.environment().clear();
        target.environment().putAll(config.environment());
        target.redirectErrorStream(config.redirectErrorStream());
        target.redirectInput(ProcessBuilder.Redirect.INHERIT);
        return target.start();
    }

    private static void watchControl(
        DataInputStream input,
        AtomicBoolean targetExited,
        AtomicBoolean released,
        CountDownLatch release
    ) {
        try {
            requireEmptyFrame(input, FRAME_RELEASE);
            if (!targetExited.get()) throw new IOException("Release arrived before target exit");
            released.set(true);
            release.countDown();
        } catch (Throwable ignored) {
            emergencyStop();
        }
    }

    private static TargetConfig readConfig(DataInputStream input) throws IOException {
        Frame frame = readFrame(input);
        if (frame.type() != FRAME_CONFIG) throw new IOException("Expected process configuration");
        DataInputStream payload = new DataInputStream(new ByteArrayInputStream(frame.payload()));
        int argumentCount = readCount(payload, MAX_ARGUMENTS, "arguments");
        if (argumentCount == 0) throw new IOException("The target command is empty");
        List<String> arguments = new ArrayList<>(argumentCount);
        for (int index = 0; index < argumentCount; index++) arguments.add(readString(payload));
        int environmentCount = readCount(payload, MAX_ENVIRONMENT_ENTRIES, "environment entries");
        Map<String, String> environment = new LinkedHashMap<>(environmentCount);
        for (int index = 0; index < environmentCount; index++) {
            String name = readString(payload);
            String value = readString(payload);
            if (name.isEmpty() || environment.put(name, value) != null) {
                throw new IOException("The target environment is invalid");
            }
        }
        boolean redirectErrorStream = payload.readBoolean();
        if (payload.available() != 0) throw new IOException("Unexpected process configuration data");
        return new TargetConfig(List.copyOf(arguments), Map.copyOf(environment), redirectErrorStream);
    }

    private static int readCount(DataInputStream input, int maximum, String label) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) throw new IOException("Too many " + label);
        return count;
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > input.available()) {
            throw new IOException("Invalid UTF-8 string length");
        }
        byte[] bytes = input.readNBytes(length);
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
            if (value.indexOf('\0') >= 0) throw new IOException("NUL is not allowed in process configuration");
            return value;
        } catch (CharacterCodingException error) {
            throw new IOException("Invalid UTF-8 process configuration", error);
        }
    }

    private static void writeHello(
        DataOutputStream output,
        byte[] token,
        long pid,
        long sid,
        long pgid
    ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream payload = new DataOutputStream(bytes)) {
            payload.writeInt(PROTOCOL_MAGIC);
            payload.writeInt(PROTOCOL_VERSION);
            payload.writeInt(token.length);
            payload.write(token);
            payload.writeLong(pid);
            payload.writeLong(sid);
            payload.writeLong(pgid);
        }
        writeFrame(output, FRAME_HELLO, bytes.toByteArray());
    }

    private static void writeIntFrame(DataOutputStream output, int type, int value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Integer.BYTES);
        try (DataOutputStream payload = new DataOutputStream(bytes)) {
            payload.writeInt(value);
        }
        writeFrame(output, type, bytes.toByteArray());
    }

    private static void writeLongFrame(DataOutputStream output, int type, long value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Long.BYTES);
        try (DataOutputStream payload = new DataOutputStream(bytes)) {
            payload.writeLong(value);
        }
        writeFrame(output, type, bytes.toByteArray());
    }

    private static void writeError(DataOutputStream output, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        writeFrame(output, FRAME_ERROR, Arrays.copyOf(bytes, Math.min(bytes.length, MAX_STRING_BYTES)));
    }

    private static void writeFrame(DataOutputStream output, int type, byte[] payload) throws IOException {
        if (payload.length > MAX_CONTROL_FRAME_BYTES) throw new IOException("Control frame is too large");
        output.writeInt(type);
        output.writeInt(payload.length);
        output.write(payload);
        output.flush();
    }

    private static Frame readFrame(DataInputStream input) throws IOException {
        int type;
        try {
            type = input.readInt();
        } catch (EOFException error) {
            throw new IOException("Control channel closed", error);
        }
        int length = input.readInt();
        if (length < 0 || length > MAX_CONTROL_FRAME_BYTES) throw new IOException("Invalid control frame length");
        byte[] payload = input.readNBytes(length);
        if (payload.length != length) throw new IOException("Incomplete control frame");
        return new Frame(type, payload);
    }

    private static void requireEmptyFrame(DataInputStream input, int expectedType) throws IOException {
        Frame frame = readFrame(input);
        if (frame.type() != expectedType || frame.payload().length != 0) {
            throw new IOException("Unexpected control frame");
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static void closeInheritedInput() {
        try {
            System.in.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeInheritedOutput() {
        System.out.close();
        System.err.close();
    }

    private static void emergencyStop() {
        if (!Platform.isWindows() && POSIX_SESSION_ESTABLISHED.get()) {
            try {
                ProcessHandle leader = ProcessHandle.current();
                long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(SESSION_TERMINATION_TIMEOUT_MILLIS);
                drainPosixSession(leader, deadline, false);
            } catch (Throwable ignored) {
            }
        }
        Runtime.getRuntime().halt(CANCEL_EXIT_CODE);
    }

    static final class OutputRelay {
        private final Process target;
        private final List<Thread> threads;
        private final AtomicBoolean closing = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private OutputRelay(Process target, List<Thread> threads) {
            this.target = target;
            this.threads = threads;
        }

        static OutputRelay start(Process target, boolean redirectErrorStream) {
            List<Thread> threads = new ArrayList<>();
            OutputRelay relay = new OutputRelay(target, threads);
            threads.add(relay.start(target.getInputStream(), System.out, "stdout"));
            if (!redirectErrorStream) threads.add(relay.start(target.getErrorStream(), System.err, "stderr"));
            return relay;
        }

        private Thread start(InputStream input, OutputStream output, String stream) {
            Thread thread = new Thread(() -> {
                try {
                    copyAvailable(input, output);
                } catch (Throwable error) {
                    if (!closing.get()) failure.compareAndSet(null, error);
                }
            }, "Affected process supervisor " + stream);
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        private void copyAvailable(InputStream input, OutputStream output) throws IOException, InterruptedException {
            byte[] buffer = new byte[8_192];
            boolean rootExited = false;
            while (!closing.get()) {
                int available = input.available();
                if (available > 0) {
                    int read = input.read(buffer, 0, Math.min(buffer.length, available));
                    if (read < 0) return;
                    output.write(buffer, 0, read);
                    output.flush();
                } else if (rootExited) {
                    output.flush();
                    return;
                } else if (!target.isAlive()) {
                    rootExited = true;
                } else {
                    Thread.sleep(OUTPUT_RELAY_POLL_MILLIS);
                }
            }
        }

        boolean finish(long timeoutMillis) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            boolean interrupted = false;
            try {
                for (Thread thread : threads) {
                    while (thread.isAlive() && System.nanoTime() < deadline) {
                        long remaining = deadline - System.nanoTime();
                        try {
                            thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                }
                boolean forcedClose = threads.stream().anyMatch(Thread::isAlive);
                if (forcedClose) {
                    closing.set(true);
                    try {
                        target.getInputStream().close();
                    } catch (IOException ignored) {
                    }
                    try {
                        target.getErrorStream().close();
                    } catch (IOException ignored) {
                    }
                }
                for (Thread thread : threads) {
                    if (thread.isAlive()) {
                        try {
                            thread.join(250);
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                }
                return !forcedClose && threads.stream().noneMatch(Thread::isAlive) && failure.get() == null;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private record Frame(int type, byte[] payload) {
    }

    private record SessionSnapshot(boolean leaderPresent, List<ProcessHandle> members) {
    }

    private record SessionDrain(boolean proven, boolean interrupted) {
    }

    private record TargetConfig(
        List<String> arguments,
        Map<String, String> environment,
        boolean redirectErrorStream
    ) {
    }

    private interface Posix extends Library {
        Posix INSTANCE = Native.load(Platform.C_LIBRARY_NAME, Posix.class);

        int setsid();

        int getsid(int pid);

        int getpgid(int pid);

    }

    public static final class WindowsJob implements AutoCloseable {
        private final WinNT.HANDLE handle;
        private final AtomicBoolean closed = new AtomicBoolean();

        private WindowsJob(WinNT.HANDLE handle) {
            this.handle = handle;
        }

        public static WindowsJob create() throws IOException {
            WinNT.HANDLE handle = WindowsKernel.INSTANCE.CreateJobObjectW(null, null);
            if (isNull(handle)) throw windowsError("Could not create a process job");
            WindowsJob job = new WindowsJob(handle);
            if (!job.setKillOnClose(true)) {
                job.close();
                throw windowsError("Could not configure the process job");
            }
            return job;
        }

        public void assign(long pid) throws IOException {
            if (pid <= 0 || pid > Integer.MAX_VALUE) throw new IOException("Invalid helper process id");
            int access = PROCESS_TERMINATE | PROCESS_SET_QUOTA | PROCESS_QUERY_LIMITED_INFORMATION;
            WinNT.HANDLE process = WindowsKernel.INSTANCE.OpenProcess(access, false, (int) pid);
            if (isNull(process)) throw windowsError("Could not open the helper process");
            try {
                if (!WindowsKernel.INSTANCE.AssignProcessToJobObject(handle, process)) {
                    throw windowsError("Could not contain the helper process");
                }
                IntByReference assigned = new IntByReference();
                if (!WindowsKernel.INSTANCE.IsProcessInJob(process, handle, assigned) || assigned.getValue() == 0) {
                    throw windowsError("Could not verify the helper process job");
                }
            } finally {
                WindowsKernel.INSTANCE.CloseHandle(process);
            }
        }

        public boolean terminateAndAwait(long timeoutMillis) {
            if (closed.get() || timeoutMillis <= 0) return false;
            if (!WindowsKernel.INSTANCE.TerminateJobObject(handle, CANCEL_EXIT_CODE)) return false;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            boolean interrupted = Thread.interrupted();
            try {
                while (System.nanoTime() < deadline) {
                    Integer active = activeProcesses();
                    if (active != null && active == 0) return true;
                    if (active == null) return false;
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                Integer active = activeProcesses();
                return active != null && active == 0;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        public boolean release() {
            if (closed.get() || !setKillOnClose(false)) return false;
            return closeHandle();
        }

        @Override
        public void close() {
            closeHandle();
        }

        private boolean closeHandle() {
            if (!closed.compareAndSet(false, true)) return true;
            if (WindowsKernel.INSTANCE.CloseHandle(handle)) return true;
            closed.set(false);
            return false;
        }

        private Integer activeProcesses() {
            BasicAccounting information = new BasicAccounting();
            IntByReference returned = new IntByReference();
            if (!WindowsKernel.INSTANCE.QueryInformationJobObject(
                handle,
                JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION,
                information,
                information.size(),
                returned
            )) return null;
            information.read();
            return information.activeProcesses;
        }

        private boolean setKillOnClose(boolean enabled) {
            ExtendedLimit information = new ExtendedLimit();
            information.basicLimitInformation.limitFlags = enabled ? JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE : 0;
            information.write();
            return WindowsKernel.INSTANCE.SetInformationJobObject(
                handle,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                information,
                information.size()
            );
        }

        private static boolean isNull(WinNT.HANDLE handle) {
            return handle == null || handle.getPointer() == null || Pointer.nativeValue(handle.getPointer()) == 0;
        }

        private static IOException windowsError(String message) {
            return new IOException(message + " (Windows error " + Native.getLastError() + ")");
        }
    }

    @Structure.FieldOrder({
        "perProcessUserTimeLimit",
        "perJobUserTimeLimit",
        "limitFlags",
        "minimumWorkingSetSize",
        "maximumWorkingSetSize",
        "activeProcessLimit",
        "affinity",
        "priorityClass",
        "schedulingClass"
    })
    public static final class BasicLimit extends Structure {
        public long perProcessUserTimeLimit;
        public long perJobUserTimeLimit;
        public int limitFlags;
        public BaseTSD.SIZE_T minimumWorkingSetSize = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T maximumWorkingSetSize = new BaseTSD.SIZE_T();
        public int activeProcessLimit;
        public BaseTSD.ULONG_PTR affinity = new BaseTSD.ULONG_PTR();
        public int priorityClass;
        public int schedulingClass;
    }

    @Structure.FieldOrder({
        "readOperationCount",
        "writeOperationCount",
        "otherOperationCount",
        "readTransferCount",
        "writeTransferCount",
        "otherTransferCount"
    })
    public static final class IoCounters extends Structure {
        public long readOperationCount;
        public long writeOperationCount;
        public long otherOperationCount;
        public long readTransferCount;
        public long writeTransferCount;
        public long otherTransferCount;
    }

    @Structure.FieldOrder({
        "basicLimitInformation",
        "ioInfo",
        "processMemoryLimit",
        "jobMemoryLimit",
        "peakProcessMemoryUsed",
        "peakJobMemoryUsed"
    })
    public static final class ExtendedLimit extends Structure {
        public BasicLimit basicLimitInformation = new BasicLimit();
        public IoCounters ioInfo = new IoCounters();
        public BaseTSD.SIZE_T processMemoryLimit = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T jobMemoryLimit = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T peakProcessMemoryUsed = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T peakJobMemoryUsed = new BaseTSD.SIZE_T();
    }

    @Structure.FieldOrder({
        "totalUserTime",
        "totalKernelTime",
        "thisPeriodTotalUserTime",
        "thisPeriodTotalKernelTime",
        "totalPageFaultCount",
        "totalProcesses",
        "activeProcesses",
        "totalTerminatedProcesses"
    })
    public static final class BasicAccounting extends Structure {
        public long totalUserTime;
        public long totalKernelTime;
        public long thisPeriodTotalUserTime;
        public long thisPeriodTotalKernelTime;
        public int totalPageFaultCount;
        public int totalProcesses;
        public int activeProcesses;
        public int totalTerminatedProcesses;
    }

    private interface WindowsKernel extends StdCallLibrary {
        WindowsKernel INSTANCE = Native.load("kernel32", WindowsKernel.class, W32APIOptions.DEFAULT_OPTIONS);

        WinNT.HANDLE CreateJobObjectW(Pointer attributes, WString name);

        boolean SetInformationJobObject(WinNT.HANDLE job, int informationClass, Structure information, int length);

        boolean AssignProcessToJobObject(WinNT.HANDLE job, WinNT.HANDLE process);

        boolean IsProcessInJob(WinNT.HANDLE process, WinNT.HANDLE job, IntByReference result);

        WinNT.HANDLE OpenProcess(int access, boolean inheritHandle, int processId);

        boolean TerminateJobObject(WinNT.HANDLE job, int exitCode);

        boolean QueryInformationJobObject(
            WinNT.HANDLE job,
            int informationClass,
            Structure information,
            int length,
            IntByReference returnedLength
        );

        boolean CloseHandle(WinNT.HANDLE handle);
    }
}
