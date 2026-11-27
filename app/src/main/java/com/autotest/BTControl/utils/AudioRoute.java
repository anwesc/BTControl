package com.autotest.BTControl.utils;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import java.util.Locale;

/**
 * 判断「这台蓝牙设备此刻是不是音频输出目标」。
 *
 * <p>「已连接」只说明 A2DP/HFP 的控制通道通了，声音仍可能从手机喇叭出来；
 * 系统蓝牙设置里那个「使用中 / Active」看的是音频路由。三方 App 拿不到
 * {@code BluetoothA2dp#getActiveDevice()}（系统接口），只能从 AudioManager 侧证：
 * <ul>
 *   <li>通话：API 31+ {@link AudioManager#getCommunicationDevice()} 指向它；</li>
 *   <li>媒体：{@link AudioManager#isBluetoothA2dpOn()} 为真，且当前只有它这一台 A2DP 输出。</li>
 * </ul>
 */
public final class AudioRoute {

    private AudioRoute() {
    }

    /** @param mac 设备 MAC；返回 true 表示音频正走在它上面。 */
    public static boolean isActiveOutput(Context context, String mac) {
        if (context == null || mac == null) {
            return false;
        }
        String target = normalize(mac);
        AudioManager am = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo comm = am.getCommunicationDevice();
            if (comm != null && isBluetooth(comm.getType())
                    && target.equals(normalize(comm.getAddress()))) {
                return true;
            }
        }

        // 媒体路由：只有一台 A2DP 输出时，isBluetoothA2dpOn() 就是它
        boolean a2dpOn;
        try {
            //noinspection deprecation
            a2dpOn = am.isBluetoothA2dpOn();
        } catch (Exception e) {
            a2dpOn = false;
        }
        if (!a2dpOn) {
            return false;
        }
        int a2dpCount = 0;
        String a2dpMac = null;
        for (AudioDeviceInfo info : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (info.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || !info.isSink()) {
                continue;
            }
            a2dpCount++;
            a2dpMac = info.getAddress();
        }
        return a2dpCount == 1 && target.equals(normalize(a2dpMac));
    }

    private static boolean isBluetooth(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER));
    }

    private static String normalize(String mac) {
        return mac == null ? "" : mac.trim().toUpperCase(Locale.ROOT);
    }
}
