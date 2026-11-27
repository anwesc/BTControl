package com.autotest.BTControl.update;

import org.json.JSONObject;

import java.net.URL;
import java.util.Locale;

/**
 * 远端 {@code version.json} 的内存表示。
 *
 * <p>流水线（.github/workflows/release.yml）每发一个 Release 就上传一份，
 * 默认地址是 {@code https://github.com/anwesc/BTControl/releases/latest/download/version.json}。
 * 字段：
 * <pre>
 * {
 *   "versionCode": 3002000,
 *   "versionName": "3.2.0",
 *   "apkUrl": "https://.../BTControl_v3.2.0.apk",
 *   "sha256": "....",
 *   "size": 1234567,
 *   "minSdk": 27,
 *   "changelog": "- xxx\n- yyy",
 *   "publishedAt": "2026-11-27T02:00:00Z"
 * }
 * </pre>
 */
public final class UpdateManifest {

    public final int versionCode;
    public final String versionName;
    public final String apkUrl;
    public final String sha256;
    public final long size;
    public final int minSdk;
    public final String changelog;
    public final String publishedAt;

    private UpdateManifest(int versionCode, String versionName, String apkUrl, String sha256,
                           long size, int minSdk, String changelog, String publishedAt) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUrl = apkUrl;
        this.sha256 = sha256;
        this.size = size;
        this.minSdk = minSdk;
        this.changelog = changelog;
        this.publishedAt = publishedAt;
    }

    /**
     * 解析清单。{@code apkUrl} 允许相对路径，按清单地址解析，方便自建镜像。
     *
     * @throws IllegalArgumentException 关键字段缺失或非法（消息可直接回给用户）
     */
    public static UpdateManifest fromJson(JSONObject o, String manifestUrl) {
        int code = o.optInt("versionCode", 0);
        String name = o.optString("versionName", "").trim();
        String apk = o.optString("apkUrl", "").trim();
        if (code <= 0) {
            throw new IllegalArgumentException("version.json 缺少合法的 versionCode");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("version.json 缺少 versionName");
        }
        if (apk.isEmpty()) {
            throw new IllegalArgumentException("version.json 缺少 apkUrl");
        }
        String resolved;
        try {
            resolved = new URL(new URL(manifestUrl), apk).toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("apkUrl 不是合法地址: " + e.getMessage());
        }
        String sha = o.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
        if (!sha.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("version.json 的 sha256 不是 64 位十六进制");
        }
        return new UpdateManifest(code, name, resolved, sha,
                Math.max(0L, o.optLong("size", 0L)),
                o.optInt("minSdk", 0),
                o.optString("changelog", "").trim(),
                o.optString("publishedAt", ""));
    }

    /** 是否明确要求更高的系统版本（缺省 0 表示不限制）。 */
    public boolean requiresNewerSdk(int deviceSdk) {
        return minSdk > 0 && minSdk > deviceSdk;
    }

    /** 存进 SharedPreferences 用：清单原样序列化，红点状态重启后还在。 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("versionCode", versionCode);
            o.put("versionName", versionName);
            o.put("apkUrl", apkUrl);
            o.put("sha256", sha256);
            o.put("size", size);
            o.put("minSdk", minSdk);
            o.put("changelog", changelog == null ? "" : changelog);
            o.put("publishedAt", publishedAt == null ? "" : publishedAt);
        } catch (Exception ignored) {
            // 固定 key 的 put 不会失败
        }
        return o;
    }
}
