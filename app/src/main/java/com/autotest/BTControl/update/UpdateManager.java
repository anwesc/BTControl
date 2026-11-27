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
    private static final String KEY_PENDING_JSON = "pending_json";
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

    /**
     * 下载到缓存目录并校验 SHA-256；回调在主线程（进度按 ~150ms 节流）。
     *
     * <p>支持断点续传：上次中断留下的 {@code .part} 会被复用，用 HTTP Range 只取剩下的字节；
     * 服务器不认 Range（回 200）就丢掉断点从头下。校验不过（SHA-256/size）会删掉断点，
     * 免得每次重试都拿到同一份坏数据。
     */
    public static void download(Context ctx, final UpdateManifest m, final DownloadCallback cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            File dir = new File(app.getCacheDir(), "updates");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                finishDownload(cb, null, "mkdir_failed: " + dir);
                return;
            }
            cleanCache(app, m.versionName);
            File target = new File(dir, "BTControl_v" + m.versionName + ".apk");
            File part = new File(dir, target.getName() + ".part");
            HttpURLConnection conn = null;
            try {
                long start = part.isFile() ? part.length() : 0;
                if (start > 0 && m.size > 0 && start > m.size) {
                    part.delete();
                    start = 0;
                }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                if (start > 0) {
                    Log.i(TAG, "断点续传：已有 " + start + " 字节，从断点继续");
                    updateDigest(md, part);
                }
                conn = openConnection(m.apkUrl);
                if (start > 0) {
                    conn.setRequestProperty("Range", "bytes=" + start + "-");
                }
                int code = conn.getResponseCode();
                if (start > 0 && code == 200) {
                    // 服务器不支持 Range，或者远端文件换了：断点作废，整包重下
                    Log.i(TAG, "服务器返回 200，放弃断点从头下载");
                    part.delete();
                    start = 0;
                    md = MessageDigest.getInstance("SHA-256");
                } else if (code != 200 && code != 206) {
                    throw new IllegalStateException("http_" + code);
                }
                long total = m.size;
                if (total <= 0) {
                    long len = conn.getContentLengthLong();
                    if (len > 0) {
                        total = start + len;
                    }
                }
                long done = start;
                long lastReport = 0;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(part, start > 0)) {
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
                // 保留 .part：下次进来还能接着下（校验失败的分支已经把它删了）
                Log.w(TAG, "下载更新包失败", t);
                finishDownload(cb, null, errorText(t));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    // ---------------------------------------------------------------- 缓存清理

    /** 启动时清一次：没有待安装版本就清空，有就只留它（旧的包和断点文件都删）。 */
    public static void cleanupCache(Context ctx) {
        cleanCache(ctx.getApplicationContext(), pendingVersionName(ctx));
    }

    private static void cleanCache(Context app, String keepVersionName) {
        File dir = new File(app.getCacheDir(), "updates");
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        String keep = keepVersionName == null || keepVersionName.isEmpty()
                ? null : "BTControl_v" + keepVersionName + ".apk";
        for (File f : files) {
            if (keep != null && (f.getName().equals(keep) || f.getName().equals(keep + ".part"))) {
                continue;
            }
            boolean ok = f.isDirectory() ? deleteTree(f) : f.delete();
            if (!ok) {
                Log.w(TAG, "清理更新缓存失败: " + f);
            }
        }
    }

    private static boolean deleteTree(File dir) {
        File[] children = dir.listFiles();
        boolean ok = true;
        if (children != null) {
            for (File c : children) {
                ok &= c.isDirectory() ? deleteTree(c) : c.delete();
            }
        }
        return ok && dir.delete();
    }

    // ---------------------------------------------------------------- 安装

    /**
     * 安装已下载的 APK。优先 Shizuku 静默装（开关打开且 Shizuku 就绪），失败则退回系统安装器。
     * 回调在主线程；静默安装成功时进程通常已被杀，回调不一定执行。
     */
    public static void install(Context ctx, final File apk, final InstallCallback cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            String bad = verifyApk(app, apk, null);
            if (bad != null) {
                Log.w(TAG, "安装包校验失败，拒绝安装: " + bad);
                MAIN.post(() -> cb.onResult(bad));
                return;
            }
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

    // ---------------------------------------------------------------- 安装包校验

    /**
     * 安装前校验 APK 是不是「我们的、更新的、签名一致的」包。
     * 清单可以写错（比如填了旧包），装上去会变成降级或签名冲突，装之前先挡住。
     *
     * @param expect 期望的清单，可为 null（自动化里没读清单时只做基本校验）
     * @return null 表示通过，否则是可直接展示的原因串
     */
    public static String verifyApk(Context ctx, File apk, UpdateManifest expect) {
        try {
            if (apk == null || !apk.isFile() || apk.length() == 0) {
                return "apk_missing";
            }
            PackageManager pm = ctx.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (info == null) {
                return "apk_unreadable";
            }
            if (!ctx.getPackageName().equals(info.packageName)) {
                return "apk_package_mismatch: " + info.packageName;
            }
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            if (code <= 0) {
                return "apk_versioncode_invalid";
            }
            if (code < currentVersionCode(ctx)) {
                return "apk_versioncode_older: " + code + "/" + currentVersionCode(ctx);
            }
            if (expect != null && expect.versionCode > 0 && code != expect.versionCode) {
                return "apk_versioncode_mismatch: " + code + "/" + expect.versionCode;
            }
            String apkSig = firstSignature(info);
            String selfSig = firstSignature(pm.getPackageInfo(ctx.getPackageName(), flags));
            if (apkSig != null && selfSig != null && !apkSig.equals(selfSig)) {
                return "apk_signature_mismatch";
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "安装包校验异常", t);
            return "apk_verify_failed: " + errorText(t);
        }
    }

    private static String firstSignature(PackageInfo info) {
        try {
            android.content.pm.Signature[] arr;
            if (Build.VERSION.SDK_INT >= 28) {
                if (info.signingInfo == null) {
                    return null;
                }
                arr = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
            } else {
                arr = info.signatures;
            }
            if (arr == null || arr.length == 0) {
                return null;
            }
            return hex(MessageDigest.getInstance("SHA-256").digest(arr[0].toByteArray()));
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 待安装版本（红点）

    /** 记下「发现的新版本」：界面只在入口点个红点，不弹窗打扰。 */
    public static void setPending(Context c, UpdateManifest m) {
        prefs(c).edit().putString(KEY_PENDING_JSON, m.toJson().toString()).apply();
    }

    public static void clearPending(Context c) {
        prefs(c).edit().remove(KEY_PENDING_JSON).apply();
    }

    /** 待安装清单；已经装上（当前版本已经不低于它）就返回 null，红点自动消失。 */
    public static UpdateManifest pendingManifest(Context c) {
        String json = prefs(c).getString(KEY_PENDING_JSON, null);
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            UpdateManifest m = UpdateManifest.fromJson(new JSONObject(json), manifestUrl(c));
            return m.versionCode > currentVersionCode(c) ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static String pendingVersionName(Context c) {
        UpdateManifest m = pendingManifest(c);
        return m == null ? null : m.versionName;
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

    private static void updateDigest(MessageDigest md, File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
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
