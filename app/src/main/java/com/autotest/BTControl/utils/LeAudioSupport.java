package com.autotest.BTControl.utils;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.List;

/**
 * LE Audio 的两个前置条件：本机（手机）支持 + 对端（音响）支持。
 *
 * <p>本机有公开 API {@code BluetoothAdapter.isLeAudioSupported()}（Android 13+）；
 * 对端没有对应 API，只能用「看到过证据」来判断：
 * <ul>
 *   <li>曾经连上过 LE Audio（最硬的证据，连上就记一笔）；</li>
 *   <li>扫描时对端广播了 LE Audio 相关 16-bit 服务 UUID（ASCS/BASS/PACS/VCS/MICS/CAS）。</li>
 * </ul>
 * 两者都没有就当「还不支持」，详情页不显示 LE Audio 行——宁可藏起来，也不让用户点了必然失败。
 */
public final class LeAudioSupport {
    private static final String TAG = "LeAudioSupport";
    private static final String PREFS = "le_audio_peer";

    /** 与 LE Audio 强绑定的 16-bit 服务 UUID：ASCS 0x184E / BASS 0x184F / PACS 0x1850 / VCS 0x1844 / MICS 0x184D / CAS 0x1853。 */
    private static final int[] LE_AUDIO_SERVICE_UUIDS = {0x184E, 0x184F, 0x1850, 0x1844, 0x184D, 0x1853};

    /** 16-bit UUID 展开成 128-bit 后的高位与低位掩码。 */
    private static final long UUID_BASE_MSB = 0x0000000000001000L;
    private static final long UUID_BASE_LSB_MASK = 0xFFFF000000000000L;

    private LeAudioSupport() {
    }

    /** 本机是否支持 LE Audio（Android 13 起才有公开 API，更早一律按不支持）。 */
    public static boolean phoneSupports(BluetoothAdapter adapter) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || adapter == null) {
            return false;
        }
        try {
            return adapter.isLeAudioSupported() == BluetoothStatusCodes.SUCCESS;
        } catch (Throwable t) {
            Log.w(TAG, "查询本机 LE Audio 支持情况失败", t);
            return false;
        }
    }

    /** 这个 MAC 是否已知支持 LE Audio（连上过，或广播里见过相关服务）。 */
    public static boolean peerKnown(Context context, String address) {
        if (address == null) {
            return false;
        }
        return prefs(context).getBoolean(address.toUpperCase(), false);
    }

    /** 记一笔「对端支持 LE Audio」，之后详情页就会显示这一行。 */
    public static void markPeer(Context context, String address) {
        if (address == null) {
            return;
        }
        prefs(context).edit().putBoolean(address.toUpperCase(), true).apply();
    }

    /** 扫描回调里顺手看一眼广播包，命中 LE Audio 服务就记住这台设备。 */
    public static void observeScanResult(Context context, ScanResult result) {
        if (context == null || result == null || result.getDevice() == null) {
            return;
        }
        ScanRecord record = result.getScanRecord();
        if (record == null) {
            return;
        }
        List<ParcelUuid> uuids = record.getServiceUuids();
        if (uuids == null) {
            return;
        }
        for (ParcelUuid parcelUuid : uuids) {
            if (parcelUuid == null || parcelUuid.getUuid() == null) {
                continue;
            }
            if (parcelUuid.getUuid().getMostSignificantBits() != UUID_BASE_MSB) {
                continue;
            }
            long least = parcelUuid.getUuid().getLeastSignificantBits();
            if ((least & UUID_BASE_LSB_MASK) != 0x8000000000000000L) {
                continue;
            }
            int shortId = (int) ((least >>> 32) & 0xFFFF);
            for (int candidate : LE_AUDIO_SERVICE_UUIDS) {
                if (shortId == candidate) {
                    markPeer(context, result.getDevice().getAddress());
                    return;
                }
            }
        }
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
