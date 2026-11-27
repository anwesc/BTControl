package com.autotest.BTControl.update;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.autotest.BTControl.utils.ShizukuBridge;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 版本检查 / 下载 / 安装的唯一入口。
 *
 * <p>更新源是一份 {@code version.json}（流水线随每个 Release 上传），默认走
 * GitHub 的 {@code releases/latest/download/version.json}——它不经过 API，没有速率限制。
 * 内网或镜像可以在设置里换成任意 http(s) 地址。
 *
 * <p>安装分两条路：Shizuku 可用时用 {@code pm install} 静默装（不弹系统框、被自动化场景需要），
 * 否则走 {@link PackageInstaller}，由用户点一次「更新」。两条路都不做「悄悄替换」。
 */
public final class UpdateManager {

    private static final String TAG = "BtUpdate";

    /** 结果广播的 action，见 AndroidManifest 里的 InstallResultReceiver。 */
    public static final String ACTION_INSTALL_RESULT = "com.autotest.BTControl.INSTALL_RESULT";

    /** 默认更新源：GitHub 会把它重定向到最新 Release 的对应资产。 */
    public static final String DEFAULT_MANIFEST_URL =
            "https://github.com/anwesc/BTControl/releases/latest/download/version.json";

    private static final String PREFS = "btcontrol_update";
    private static final String KEY_MANIFEST_URL = "manifest_url";
    private static final String KEY_AUTO_CHECK = "auto_check";
    private static final String KEY_SILENT_INSTALL = "silent_install";
    private static final String KEY_LAST_CHECK = "last_check_at";

    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final String USER_AGENT = "BTControl-Update";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "btcontrol-update");
        t.setDaemon(true);
        return t;
    });

    private UpdateManager() {
    }

    // ---------------------------------------------------------------- 偏好

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String manifestUrl(Context c) {
        String s = prefs(c).getString(KEY_MANIFEST_URL, "");
        return s == null || s.trim().isEmpty() ? DEFAULT_MANIFEST_URL : s.trim();
    }

    /** 传空串表示恢复默认。 */
    public static void setManifestUrl(Context c, String url) {
        prefs(c).edit().putString(KEY_MANIFEST_URL, url == null ? "" : url.trim()).apply();
    }

    public static boolean autoCheck(Context c) {
        return prefs(c).getBoolean(KEY_AUTO_CHECK, true);
    }

    public static void setAutoCheck(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_AUTO_CHECK, on).apply();
    }

    /** Shizuku 静默安装开关（关掉就总是走系统安装器）。 */
    public static boolean silentInstall(Context c) {
        return prefs(c).getBoolean(KEY_SILENT_INSTALL, true);
    }

    public static void setSilentInstall(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_SILENT_INSTALL, on).apply();
    }

    public static long lastCheckAt(Context c) {
        return prefs(c).getLong(KEY_LAST_CHECK, 0L);
    }

    public static void setLastCheckAt(Context c, long at) {
        prefs(c).edit().putLong(KEY_LAST_CHECK, at).apply();
    }

    // ---------------------------------------------------------------- 版本

    public static int currentVersionCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Throwable t) {
            Log.w(TAG, "读不到 versionCode", t);
            return 0;
        }
    }

    public static String currentVersionName(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName == null ? "" : p.versionName;
        } catch (Throwable t) {
            Log.w(TAG, "读不到 versionName", t);
            return "";
        }
    }

    // ---------------------------------------------------------------- 检查

    public interface CheckCallback {
        void onResult(CheckResult result);
    }

    public interface DownloadCallback {
        void onProgress(long done, long total);

        /** 成功时 apk 非空且 error 为空；失败时相反。 */
        void onFinished(File apk, String error);
    }

    public interface InstallCallback {
        /** null 表示已交给系统安装器（或静默安装成功但进程即将被杀）；否则是失败原因。 */
        void onResult(String error);
    }

    public static final class CheckResult {
        /** null 表示检查成功。 */
        public final String error;
        public final UpdateManifest manifest;
        public final boolean available;
        public final int currentVersionCode;
        public final String currentVersionName;
        public final String manifestUrl;

        CheckResult(String error, UpdateManifest manifest, boolean available,
                    int currentVersionCode, String currentVersionName, String manifestUrl) {
            this.error = error;
            this.manifest = manifest;
            this.available = available;
            this.currentVersionCode = currentVersionCode;
            this.currentVersionName = currentVersionName;
            this.manifestUrl = manifestUrl;
        }

        /** 是否有更新的版本可装（也校验 minSdk）。 */
        public boolean installable(int deviceSdk) {
            return available && manifest != null && !manifest.requiresNewerSdk(deviceSdk);
        }
    }

    /** 查一次远端清单；回调在主线程。 */
    public static void check(Context ctx, CheckCallback cb) {
        checkUrl(ctx, manifestUrl(ctx), cb);
    }

    /** 用指定地址查一次清单（自动化请求可临时带 update_source，不落偏好）。 */
    public static void checkUrl(Context ctx, final String url, final CheckCallback cb) {
        final Context app = ctx.getApplicationContext();
        final int curCode = currentVersionCode(app);
        final String curName = currentVersionName(app);
        IO.execute(() -> {
            CheckResult result;
            try {
                UpdateManifest m = fetchManifest(url);
                result = new CheckResult(null, m, m.versionCode > curCode, curCode, curName, url);
            } catch (Throwable t) {
                Log.w(TAG, "检查更新失败: " + url, t);
                result = new CheckResult(errorText(t), null, false, curCode, curName, url);
            }
            final CheckResult r = result;
            MAIN.post(() -> cb.onResult(r));
        });
    }

    /**
     * 启动时自动检查：只在「开启」且距上次检查超过 24 小时时触发。
     * 先记录时间戳再请求，失败不会每次启动都打一遍网络。
     */
    public static void maybeAutoCheck(Context ctx, CheckCallback cb) {
        if (!autoCheck(ctx)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheckAt(ctx) < AUTO_CHECK_INTERVAL_MS) {
            return;
        }
        setLastCheckAt(ctx, now);
        check(ctx, cb);
    }

    private static UpdateManifest fetchManifest(String manifestUrl) throws Exception {
        byte[] body = readAll(new URL(manifestUrl).openConnection(), MAX_MANIFEST_BYTES);
        String text = new String(body, "UTF-8").trim();
        if (text.isEmpty()) {
            throw new IllegalStateException("version.json 是空文件");
        }
        return UpdateManifest.fromJson(new JSONObject(text), manifestUrl);
    }

    // ---------------------------------------------------------------- 下载

    /** 下载到缓存目录并校验 SHA-256；回调在主线程（进度按 ~150ms 节流）。 */
    public static void download(Context ctx, final UpdateManifest m, final DownloadCallback cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            File dir = new File(app.getCacheDir(), "updates");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                finishDownload(cb, null, "mkdir_failed: " + dir);
                return;
            }
            File target = new File(dir, "BTControl_v" + m.versionName + ".apk");
            File part = new File(dir, target.getName() + ".part");
            if (part.exists() && !part.delete()) {
                finishDownload(cb, null, "清理旧下载失败: " + part);
                return;
            }
            HttpURLConnection conn = null;
            try {
                conn = openConnection(m.apkUrl);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("http_" + code);
                }
                long total = conn.getContentLengthLong();
                if (total <= 0) {
                    total = m.size;
                }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long done = 0;
                long lastReport = 0;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - lastReport > 150) {
                            lastReport = now;
                            reportProgress(cb, done, total);
                        }
                    }
                    out.flush();
                }
                reportProgress(cb, done, total);
                String sha = hex(md.digest());
                if (!sha.equals(m.sha256)) {
                    part.delete();
                    finishDownload(cb, null, "sha256_mismatch: " + sha);
                    return;
                }
                if (m.size > 0 && done != m.size) {
                    part.delete();
                    finishDownload(cb, null, "size_mismatch: " + done + "/" + m.size);
                    return;
                }
                if (target.exists() && !target.delete()) {
                    part.delete();
                    finishDownload(cb, null, "覆盖旧包失败: " + target);
                    return;
                }
                if (!part.renameTo(target)) {
                    finishDownload(cb, null, "重命名失败: " + target);
                    return;
                }
                finishDownload(cb, target, null);
            } catch (Throwable t) {
                Log.w(TAG, "下载更新包失败", t);
                part.delete();
                finishDownload(cb, null, errorText(t));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    // ---------------------------------------------------------------- 安装

    /**
     * 安装已下载的 APK。优先 Shizuku 静默装（开关打开且 Shizuku 就绪），失败则退回系统安装器。
     * 回调在主线程；静默安装成功时进程通常已被杀，回调不一定执行。
     */
    public static void install(Context ctx, final File apk, final InstallCallback cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            if (silentInstall(app) && "ok".equals(ShizukuBridge.status())) {
                String r = ShizukuBridge.installApk(apk);
                if ("ok".equals(r)) {
                    MAIN.post(() -> cb.onResult(null));
                    return;
                }
                Log.w(TAG, "静默安装失败，退回系统安装器: " + r);
            }
            final String error = installWithPackageInstaller(app, apk);
            MAIN.post(() -> cb.onResult(error));
        });
    }

    /** @return null 表示已调起系统安装器；否则是失败原因。 */
    private static String installWithPackageInstaller(Context app, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26
                    && !app.getPackageManager().canRequestPackageInstalls()) {
                return "needs_install_permission";
            }
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(app.getPackageName());
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            try {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("BTControl.apk", 0, apk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                    session.fsync(out);
                }
            } finally {
                session.close();
            }
            Intent result = new Intent(ACTION_INSTALL_RESULT).setPackage(app.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(app, sessionId, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            Log.i(TAG, "已调起系统安装器 session=" + sessionId + " apk=" + apk);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "PackageInstaller 安装失败", t);
            return "installer_failed: " + t;
        }
    }

    /** 「安装未知应用」授权页。 */
    public static Intent installPermissionIntent(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + c.getPackageName()));
        }
        return new Intent(Settings.ACTION_SECURITY_SETTINGS);
    }

    public static boolean hasInstallPermission(Context c) {
        try {
            return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 工具

    private static HttpURLConnection openConnection(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static byte[] readAll(java.net.URLConnection conn, int maxBytes) throws Exception {
        try {
            int code = ((HttpURLConnection) conn).getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("http_" + code + ": " + conn.getURL());
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) {
                        throw new IllegalStateException("清单文件过大（>" + maxBytes + " 字节）");
                    }
                }
                return out.toByteArray();
            }
        } finally {
            ((HttpURLConnection) conn).disconnect();
        }
    }

    private static void reportProgress(DownloadCallback cb, long done, long total) {
        MAIN.post(() -> cb.onProgress(done, total));
    }

    private static void finishDownload(DownloadCallback cb, File apk, String error) {
        MAIN.post(() -> cb.onFinished(apk, error));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String errorText(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.trim().isEmpty() ? t.toString() : msg;
    }
}
