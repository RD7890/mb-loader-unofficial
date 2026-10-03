package io.bambosan.mbloader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Saves launcher + Minecraft logs to  /storage/emulated/0/mbl-logs/latestlogs.txt
 * (falls back to Android/data/io.bambosan.mbloader/files/mbl-logs/ when "All files access" is not granted).
 *
 * What ends up in latestlogs.txt for one launch attempt:
 *   1. a header (device, Android, loader + Minecraft versions, native lib dir listing)
 *   2. every line shown in the launcher log
 *   3. live logcat of this process (Java + native + Minecraft output), flushed line by line
 *   4. uncaught Java exceptions
 *   5. after a hard crash / kill: the exit reason of the dead process, appended when the loader is opened again
 * The log of the previous launch attempt is kept as previous-logs.txt.
 */
public final class MblLog {

    private static final String TAG = "MBL";
    private static final String DIR_NAME = "mbl-logs";
    private static final String LATEST = "latestlogs.txt";
    private static final String PREVIOUS = "previous-logs.txt";
    private static final String PREFS = "mbl_log";
    private static final String KEY_PID = "last_session_pid";
    private static final long MAX_BYTES = 30L * 1024 * 1024;

    private static Writer writer;
    private static long written;
    private static java.lang.Process logcatProc;

    private MblLog() {}

    // ---------------------------------------------------------------- storage access

    public static boolean hasAllFilesAccess(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Asks (once per app start) for storage access so logs can go to /sdcard/mbl-logs. */
    public static void ensureStorageAccess(Activity activity) {
        if (hasAllFilesAccess(activity)) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4242);
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Allow log saving")
                .setMessage("To save logs to the mbl-logs folder in your storage, allow \"All files access\" "
                        + "for MB Loader. Without it, logs go to Android/data/" + activity.getPackageName()
                        + "/files/mbl-logs instead.")
                .setPositiveButton("Allow", (d, w) -> {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + activity.getPackageName())));
                    } catch (Exception e) {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Exception ignored) {
                            Log.w(TAG, "cannot open all-files-access settings", ignored);
                        }
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private static boolean canWriteTo(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            File probe = new File(dir, ".probe");
            try (FileOutputStream f = new FileOutputStream(probe)) {
                f.write(1);
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static File resolveDir(Context ctx) {
        File shared = new File(Environment.getExternalStorageDirectory(), DIR_NAME);
        if (canWriteTo(shared)) return shared;
        File appExt = ctx.getExternalFilesDir(DIR_NAME);
        if (appExt != null && canWriteTo(appExt)) return appExt;
        File internal = new File(ctx.getFilesDir(), DIR_NAME);
        //noinspection ResultOfMethodCallIgnored
        internal.mkdirs();
        return internal;
    }

    // ---------------------------------------------------------------- session

    /** Call when the user taps Launch. Rotates the old log and starts a fresh latestlogs.txt. */
    public static synchronized void startSession(Context ctx, String launcherDex, String mcPackage) {
        try {
            closeWriter();
            File dir = resolveDir(ctx);
            File latest = new File(dir, LATEST);
            File previous = new File(dir, PREVIOUS);
            if (latest.exists() && latest.length() > 0) {
                //noinspection ResultOfMethodCallIgnored
                previous.delete();
                //noinspection ResultOfMethodCallIgnored
                latest.renameTo(previous);
            }
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(latest), StandardCharsets.UTF_8));
            written = 0;

            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_PID, Process.myPid()).apply();

            writeHeader(ctx, dir, launcherDex, mcPackage);
            startLogcat();
        } catch (Throwable t) {
            Log.e(TAG, "startSession failed", t);
        }
    }

    private static void writeHeader(Context ctx, File dir, String launcherDex, String mcPackage) {
        write("=== MB Loader (Unofficial) log ===");
        write("time        : " + now());
        write("log file    : " + new File(dir, LATEST).getAbsolutePath());
        write("all files   : " + hasAllFilesAccess(ctx));
        write("device      : " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        write("android     : " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT);
        write("abis        : " + Arrays.toString(Build.SUPPORTED_ABIS));
        write("pid         : " + Process.myPid());
        write("launcher dex: " + launcherDex);
        try {
            PackageInfo me = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            write("loader      : " + me.versionName + " (" + versionCode(me) + ")");
        } catch (Exception ignored) { }
        try {
            PackageInfo mc = ctx.getPackageManager().getPackageInfo(mcPackage, 0);
            ApplicationInfo ai = mc.applicationInfo;
            write("minecraft   : " + mcPackage + " " + mc.versionName + " (" + versionCode(mc) + ")");
            if (ai != null) {
                write("mc sourceDir: " + ai.sourceDir);
                write("mc splits   : " + Arrays.toString(ai.splitSourceDirs));
                write("mc nativeDir: " + ai.nativeLibraryDir);
                write("mc extract  : " + ((ai.flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0));
                File[] libs = new File(ai.nativeLibraryDir).listFiles();
                if (libs == null || libs.length == 0) {
                    write("mc libs     : (nativeLibraryDir empty or unreadable)");
                } else {
                    Arrays.sort(libs);
                    for (File f : libs) write("  lib       : " + f.getName() + "  " + f.length() + " bytes");
                }
            }
        } catch (Exception e) {
            write("minecraft   : not readable (" + e + ")");
        }
        write("==================================");
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
    }

    private static void startLogcat() {
        stopLogcat();
        try {
            logcatProc = new ProcessBuilder("logcat", "-v", "threadtime", "--pid=" + Process.myPid())
                    .redirectErrorStream(true).start();
        } catch (Exception e) {
            write("[MBL] could not start logcat: " + e);
            return;
        }
        final java.lang.Process proc = logcatProc;
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) write(line);
            } catch (IOException ignored) {
            }
        }, "mbl-logcat");
        t.setDaemon(true);
        t.start();
    }

    private static void stopLogcat() {
        if (logcatProc != null) {
            logcatProc.destroy();
            logcatProc = null;
        }
    }

    private static void closeWriter() {
        stopLogcat();
        if (writer != null) {
            try { writer.flush(); writer.close(); } catch (IOException ignored) { }
            writer = null;
        }
    }

    // ---------------------------------------------------------------- writing

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    /** Thread-safe, flushed after every line so nothing is lost when the process dies. */
    public static synchronized void write(String line) {
        if (writer == null || written > MAX_BYTES) return;
        try {
            writer.write(line);
            writer.write('\n');
            writer.flush();
            written += line.length() + 1;
        } catch (IOException ignored) {
        }
    }

    /** Mirror of TextView.append used by the launcher log. */
    public static void ui(TextView view, CharSequence text) {
        view.append(text);
        write("[UI] " + text.toString().trim());
    }

    /** Mirror of TextView.setText used by the launcher log. */
    public static void uiSet(TextView view, CharSequence text) {
        view.setText(text);
        write("[UI] " + text.toString().trim());
    }

    public static synchronized void logThrowable(Context ctx, String what, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        if (writer == null) {
            // crash before/without a launch session: append to the existing file
            try {
                File latest = new File(resolveDir(ctx), LATEST);
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(latest, true), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return;
            }
        }
        write("[" + now() + "] " + what);
        write(sw.toString());
    }

    // ---------------------------------------------------------------- previous process exit info

    /** Call from Application.onCreate. Appends why the previous launch process died (API 30+). */
    @SuppressLint("NewApi")
    public static void recordPreviousExit(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                appendExitInfo(app);
            } catch (Throwable t) {
                Log.w(TAG, "recordPreviousExit failed", t);
            }
        }, "mbl-exitinfo").start();
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private static void appendExitInfo(Context ctx) throws IOException {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int pid = sp.getInt(KEY_PID, -1);
        if (pid < 0 || pid == Process.myPid()) return;
        sp.edit().remove(KEY_PID).apply();

        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(ctx.getPackageName(), pid, 1);
        if (list == null || list.isEmpty()) return;
        ApplicationExitInfo info = list.get(0);

        File latest = new File(resolveDir(ctx), LATEST);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(latest, true), StandardCharsets.UTF_8)) {
            w.write("\n=== PREVIOUS PROCESS EXIT (pid " + pid + ") ===\n");
            w.write("time       : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(info.getTimestamp())) + "\n");
            w.write("reason     : " + reasonName(info.getReason()) + " (" + info.getReason() + ")\n");
            w.write("status     : " + info.getStatus()
                    + (info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
                    ? "  (= signal number, 6 SIGABRT, 11 SIGSEGV, 4 SIGILL, 7 SIGBUS)" : "") + "\n");
            w.write("process    : " + info.getProcessName() + "\n");
            w.write("description: " + info.getDescription() + "\n");
            w.write("rss/pss kB : " + info.getRss() + " / " + info.getPss() + "\n");

            try (InputStream in = info.getTraceInputStream()) {
                if (in != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0 && bo.size() < 2 * 1024 * 1024) bo.write(buf, 0, n);
                    w.write("--- trace/tombstone (readable strings extracted) ---\n");
                    w.write(printableStrings(bo.toByteArray()));
                }
            } catch (Exception e) {
                w.write("(trace not readable: " + e + ")\n");
            }
            w.write("=== END PREVIOUS PROCESS EXIT ===\n");
        }
    }

    /** Tombstones on Android 12+ are protobuf; pulling out the printable runs keeps lib names, frames, abort message. */
    private static String printableStrings(byte[] data) {
        StringBuilder out = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        for (byte b : data) {
            if (b >= 32 && b < 127) {
                cur.append((char) b);
            } else {
                if (cur.length() >= 5) out.append(cur).append('\n');
                cur.setLength(0);
            }
        }
        if (cur.length() >= 5) out.append(cur).append('\n');
        return out.toString();
    }

    private static String reasonName(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH (Java exception)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE (signal)";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF (System.exit)";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY (killed by system)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN";
        }
    }
}
