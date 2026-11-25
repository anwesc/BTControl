package com.autotest.BTControl.utils;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import com.autotest.BTControl.MyAccessibilityService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 自动化结果的唯一出口：给 adb / AI 消费，不要再让调用方从中文 UI 文案里猜。
 *
 * <p>每次自动化动作结束（成功、失败、超时都算）输出一条：
 * <pre>BTCTL RESULT {"req":"...","action":"配对","addr":"XX:..","ok":true,...}</pre>
 * 同时覆盖写一份 {@code btctl_result.json}，因为 logcat 环形缓冲会被冲掉。
 *
 * <p>字段：req / action / addr / ok / reason / elapsed_ms / ts / adapter /
 * name / bond / a2dp / hfp / leaudio。
 */
public final class BtctlReporter {

    public static final String TAG = "BTCTL";
    public static final String PREFIX = "BTCTL RESULT ";
    public static final String FILE_NAME = "btctl_result.json";

    private BtctlReporter() {
    }

    /**
     * 采集当前状态并生成结果对象。
     *
     * <p>任何异常都吞掉：少几个字段也比让自动化流程崩掉强。
     */
    @SuppressLint("MissingPermission")
    public static JSONObject snapshot(Context ctx, String req, String action, String addr,
                                      boolean ok, String reason, long elapsedMs) {
        JSONObject o = new JSONObject();
        try {
            o.put("req", req == null ? "" : req);
            o.put("action", action == null ? "" : action);
            o.put("addr", addr == null ? "" : addr);
            o.put("ok", ok);
            o.put("reason", reason == null ? "" : reason);
            o.put("elapsed_ms", elapsedMs);
            o.put("ts", System.currentTimeMillis());
            // 无障碍关着时配对/连接常因系统弹窗没人点而失败，把这一位带上便于定位
            o.put("a11y", isA11yEnabled(ctx) ? "ON" : "OFF");

            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null) {
                o.put("adapter", "UNAVAILABLE");
                return o;
            }
            o.put("adapter", adapter.isEnabled() ? "ON" : "OFF");

            BluetoothDevice device = null;
            if (addr != null && !addr.isEmpty()) {
                try {
                    device = adapter.getRemoteDevice(addr);
                } catch (IllegalArgumentException ignored) {
                    // 地址格式不对就当没有设备，字段留空
                }
            }
            if (device != null) {
                o.put("name", device.getName() == null ? "" : device.getName());
                o.put("bond", bondName(device.getBondState()));
                o.put("a2dp", profileName(bm, adapter, BluetoothProfile.A2DP, device));
                o.put("hfp", profileName(bm, adapter, BluetoothProfile.HEADSET, device));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    o.put("leaudio", profileName(bm, adapter, BluetoothProfile.LE_AUDIO, device));
                }
            } else {
                // 没指定设备时别报一个空对象：把已配对清单带上，AI 才知道该连哪台
                o.put("bonded", devicesJson(adapter.getBondedDevices()));
            }
        } catch (Throwable t) {
            Log.e(TAG, "构造结果失败", t);
        }
        return o;
    }

    /**
     * 目标 profile 是否已经到达期望状态：连接类动作等 CONNECTED，断链类动作等「不再是 CONNECTED」。
     *
     * <p>profile 级 connect/disconnect 的 ack 先回来、状态广播后到，直接用动作前的快照会读到旧值
     * （实测断链 HFP 后结果里仍写 hfp=CONNECTED，而 dumpsys 已经是 Disconnected）。
     */
    public static boolean profileSettled(Context ctx, String addr, String profile, boolean expectConnected) {
        if (addr == null || addr.isEmpty()) {
            return true;
        }
        JSONObject o = snapshot(ctx, "", "", addr, true, "", 0);
        String p = (profile == null || profile.isEmpty()) ? "all" : profile;
        switch (p) {
            case "a2dp":
                return expectConnected ? "CONNECTED".equals(o.optString("a2dp"))
                        : !"CONNECTED".equals(o.optString("a2dp"));
            case "hfp":
                return expectConnected ? "CONNECTED".equals(o.optString("hfp"))
                        : !"CONNECTED".equals(o.optString("hfp"));
            case "leaudio":
                return expectConnected ? "CONNECTED".equals(o.optString("leaudio"))
                        : !"CONNECTED".equals(o.optString("leaudio"));
            default:
                boolean a2dp = "CONNECTED".equals(o.optString("a2dp"));
                boolean hfp = "CONNECTED".equals(o.optString("hfp"));
                return expectConnected ? (a2dp || hfp) : (!a2dp && !hfp);
        }
    }

    /** 最近一次结果，供界面状态条显示；跨线程读，简单 volatile 足够。 */
    public static volatile JSONObject lastJson;

    /** 输出：一行 logcat（AI 侧按 PREFIX 抓）+ 一份文件兜底。 */
    public static void emit(Context ctx, JSONObject o) {
        String line = o.toString();
        Log.i(TAG, PREFIX + line);
        writeSnapshot(ctx, line);
        lastJson = o;
    }

    /**
     * 界面顶部状态条用的环境摘要，例如 {@code BT=ON  A11Y=ON  A2DP=CONNECTED  HFP=DISCONNECTED}。
     *
     * <p>与 logcat 里那份 JSON 同源，避免「屏幕显示的」和「AI 读到的」是两套话术。
     */
    public static String envLine(Context ctx) {
        StringBuilder sb = new StringBuilder();
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            return "BT=UNAVAILABLE  A11Y=" + (isA11yEnabled(ctx) ? "ON" : "OFF");
        }
        sb.append("BT=").append(adapter.isEnabled() ? "ON" : "OFF");
        sb.append("  A11Y=").append(isA11yEnabled(ctx) ? "ON" : "OFF");
        if (adapter.isEnabled()) {
            sb.append("  A2DP=").append(profileState(adapter, BluetoothProfile.A2DP));
            sb.append("  HFP=").append(profileState(adapter, BluetoothProfile.HEADSET));
        }
        return sb.toString();
    }

    private static String profileState(BluetoothAdapter adapter, int profile) {
        try {
            switch (adapter.getProfileConnectionState(profile)) {
                case BluetoothProfile.STATE_CONNECTED:
                    return "CONNECTED";
                case BluetoothProfile.STATE_CONNECTING:
                    return "CONNECTING";
                case BluetoothProfile.STATE_DISCONNECTING:
                    return "DISCONNECTING";
                default:
                    return "DISCONNECTED";
            }
        } catch (Throwable t) {
            // 部分 ROM 会直接抛，别让状态条把界面带崩
            return "UNKNOWN";
        }
    }

    private static void writeSnapshot(Context ctx, String line) {
        File dir = null;
        try {
            dir = ctx.getExternalFilesDir(null);
        } catch (Throwable ignored) {
            // 外部存储不可用就走内部目录
        }
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        if (dir == null) {
            return;
        }
        File tmp = new File(dir, FILE_NAME + ".tmp");
        File dst = new File(dir, FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(line.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable t) {
            Log.w(TAG, "写结果文件失败: " + t);
            return;
        }
        if (!tmp.renameTo(dst)) {
            Log.w(TAG, "替换结果文件失败");
        }
    }

    /**
     * 无障碍服务是否开着。
     *
     * <p>自动化里「点掉系统配对/授权弹窗」全靠这个服务，关着的时候配对经常卡在弹窗上没人点，
     * 所以结果里要带一位 {@code a11y}。先问 AccessibilityManager（准），再兜一次
     * Settings 字符串（MIUI 上 AccessibilityManager 偶尔滞后），两种组件名写法都认。
     */
    /**
     * 查某个地址有没有 CompanionDeviceManager 关联。
     *
     * <p>没有关联时 {@code BluetoothDevice.connect()} 会被拒（does not have a CDM association），
     * 所以这个值同时是「整机连接能不能用」的判据。API 33+ 用 getMyAssociations，
     * 再兜底老接口 getAssociations。
     */
    public static boolean hasAssociation(Context ctx, String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        try {
            CompanionDeviceManager cdm = (CompanionDeviceManager)
                    ctx.getSystemService(Context.COMPANION_DEVICE_SERVICE);
            if (cdm == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                for (AssociationInfo info : cdm.getMyAssociations()) {
                    if (info.getDeviceMacAddress() != null
                            && address.equalsIgnoreCase(info.getDeviceMacAddress().toString())) {
                        return true;
                    }
                }
            }
            for (String mac : cdm.getAssociations()) {
                if (address.equalsIgnoreCase(mac)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "查询 CDM 关联失败 " + address, t);
        }
        return false;
    }

    /**
     * 取消某个地址的 CDM 关联，返回是否确实删掉了。
     */
    public static boolean disassociate(Context ctx, String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        try {
            CompanionDeviceManager cdm = (CompanionDeviceManager)
                    ctx.getSystemService(Context.COMPANION_DEVICE_SERVICE);
            if (cdm == null) {
                return false;
            }
            // disassociate(AssociationInfo) 不是公开 API，只能用按地址的老接口
            cdm.disassociate(address);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "取消 CDM 关联失败 " + address, t);
            return false;
        }
    }

    public static boolean isA11yEnabled(Context ctx) {
        try {
            AccessibilityManager am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                ComponentName target = new ComponentName(ctx, MyAccessibilityService.class);
                for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                    ResolveInfo ri = info.getResolveInfo();
                    // ServiceInfo.getComponentName() 是隐藏 API，不能用；手动拼 ComponentName
                    if (ri != null && ri.serviceInfo != null
                            && target.equals(new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name))) {
                        return true;
                    }
                }
            }
            String enabled = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) {
                return false;
            }
            ComponentName target = new ComponentName(ctx, MyAccessibilityService.class);
            // 系统里可能记 pkg/.Cls（短），也可能记 pkg/pkg.Cls（全），两种都判
            return enabled.contains(target.flattenToShortString()) || enabled.contains(target.flattenToString());
        } catch (Throwable t) {
            Log.w(TAG, "查无障碍状态失败: " + t);
            return false;
        }
    }

    /**
     * 设备集合 → JSON 数组（扫描列表、已配对清单共用）。
     *
     * <p>逐个取名字/地址/配对态；单台设备取不到就跳过，不让一个坏设备毁掉整份结果。
     */
    @SuppressLint("MissingPermission")
    public static JSONArray devicesJson(java.util.Collection<BluetoothDevice> devices) {
        JSONArray arr = new JSONArray();
        if (devices == null) {
            return arr;
        }
        for (BluetoothDevice d : devices) {
            if (d == null) {
                continue;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("name", d.getName() == null ? "" : d.getName());
                item.put("addr", d.getAddress());
                item.put("bond", bondName(d.getBondState()));
                arr.put(item);
            } catch (Throwable ignored) {
                // 忽略单台设备
            }
        }
        return arr;
    }

    private static String bondName(int state) {
        switch (state) {
            case BluetoothDevice.BOND_BONDED:
                return "BONDED";
            case BluetoothDevice.BOND_BONDING:
                return "BONDING";
            case BluetoothDevice.BOND_NONE:
                return "NONE";
            default:
                return "UNKNOWN";
        }
    }

    @SuppressLint("MissingPermission")
    private static String profileName(BluetoothManager bm, BluetoothAdapter adapter, int profile,
                                      BluetoothDevice device) {
        try {
            for (BluetoothDevice d : bm.getConnectedDevices(profile)) {
                if (d != null && device.getAddress().equals(d.getAddress())) {
                    return "CONNECTED";
                }
            }
            return "DISCONNECTED";
        } catch (IllegalArgumentException notSupported) {
            // 本机（Xiaomi / Android 16 / sdk 37）实测 BluetoothManager 直接拒绝按 int 查已连接设备：
            // java.lang.IllegalArgumentException: Profile not supported: 2
            // 退回 profile 粒度的连接状态查询，至少能答「这个 profile 现在通没通」。
            try {
                return adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED
                        ? "CONNECTED" : "DISCONNECTED";
            } catch (Throwable t) {
                Log.w(TAG, "profile 状态查询失败 profile=" + profile, t);
                return "UNKNOWN";
            }
        } catch (Throwable t) {
            // 权限/Profile 不支持（部分机型没有 LE_AUDIO）都归 UNKNOWN，不当作失败
            Log.w(TAG, "profile 状态查询失败 profile=" + profile, t);
            return "UNKNOWN";
        }
    }
}
