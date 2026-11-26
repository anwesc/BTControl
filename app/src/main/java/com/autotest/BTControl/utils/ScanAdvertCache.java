package com.autotest.BTControl.utils;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 扫描期实况缓存：每个地址的最近一次 RSSI 与 BLE 服务 UUID。
 *
 * <p>位置说明：扫描列表只保存 {@link BluetoothDevice}（拿不到信号强度，更拿不到广播内容），
 * 而 RSSI 与 UUID 都是「发现的那一刻」才知道的信息，用后即弃，所以单独放在这里，
 * 由 {@link BluetoothDiscoveryUtils} 在回调里登记，界面与 btctl 结果各自来取。
 *
 * <p>语义边界：RSSI 只有经典发现/LE 扫描回调才带，其它地方取不到（返回 {@link #RSSI_UNKNOWN}）；
 * UUID 只有 BLE 广播里有，经典设备（音箱、车机）基本都是空列表，别把它当能力判定。
 */
public final class ScanAdvertCache {

    private static final String TAG = "ScanAdvertCache";

    /** 17 位基础 UUID 的 MSB：`0000xxxx-0000-1000-8000-00805F9B34FB`。 */
    private static final long UUID_BASE_MSB = 0x0000000000001000L;

    /** 没有 RSSI 时的哨兵值（不是合法的 dBm，别直接显示）。 */
    public static final int RSSI_UNKNOWN = Short.MIN_VALUE;

    private static final Map<String, Integer> RSSI = new LinkedHashMap<>();
    private static final Map<String, List<String>> UUIDS = new LinkedHashMap<>();

    private ScanAdvertCache() {
    }

    /** 扫描开始时清空：上一轮的信号强度不该残留到这一轮。 */
    public static synchronized void clear() {
        RSSI.clear();
        UUIDS.clear();
    }

    /** 登记一次发现（经典发现只有 RSSI）。 */
    public static synchronized void putRssi(BluetoothDevice device, int rssi) {
        if (device == null || rssi == 0) {
            return;
        }
        RSSI.put(key(device.getAddress()), rssi);
    }

    /** 登记一次 LE 广播：RSSI 与 16-bit 服务 UUID 一起收。 */
    public static synchronized void putScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return;
        }
        String addr = key(result.getDevice().getAddress());
        putRssi(result.getDevice(), result.getRssi());
        List<String> uuids = parseServiceUuids(result.getScanRecord());
        if (!uuids.isEmpty()) {
            UUIDS.put(addr, uuids);
        }
    }

    /**
     * 经典发现的广播里偶尔也带一个 UUID（{@code BluetoothDevice.EXTRA_UUID}，常见于 BLE 设备的
     * 经典扫描结果）：拿到就并进同一份缓存，拿不到不影响别的字段。
     */
    public static synchronized void putUuid(BluetoothDevice device, android.os.ParcelUuid parcelUuid) {
        if (device == null || parcelUuid == null || parcelUuid.getUuid() == null) {
            return;
        }
        String shortForm = shortUuid(parcelUuid.getUuid());
        if (shortForm == null) {
            return;
        }
        String addr = key(device.getAddress());
        List<String> list = UUIDS.get(addr);
        if (list == null) {
            list = new ArrayList<>();
            UUIDS.put(addr, list);
        }
        if (!list.contains(shortForm)) {
            list.add(shortForm);
        }
    }

    /** 最近一次 RSSI；没有就返回 {@link #RSSI_UNKNOWN}。 */
    public static synchronized int rssiOf(String address) {
        Integer v = RSSI.get(key(address));
        return v == null ? RSSI_UNKNOWN : v;
    }

    /** 最近一次 BLE 广播里的服务 UUID（16-bit 用 4 位大写十六进制，其余用完整 UUID）。 */
    public static synchronized List<String> uuidsOf(String address) {
        List<String> v = UUIDS.get(key(address));
        return v == null ? new ArrayList<>() : new ArrayList<>(v);
    }

    /**
     * 从广播记录里取服务 UUID。
     *
     * <p>优先 {@link ScanRecord#getServiceUuids()}（系统已解析好的），拿不到再按原始字节扫
     * 「长度 + 0x03/0x02 类型 + UUID」的段——部分厂商包只给原始字节。
     */
    private static List<String> parseServiceUuids(ScanRecord record) {
        List<String> out = new ArrayList<>();
        if (record == null) {
            return out;
        }
        try {
            List<android.os.ParcelUuid> parsed = record.getServiceUuids();
            if (parsed != null) {
                for (android.os.ParcelUuid p : parsed) {
                    String s = shortUuid(p.getUuid());
                    if (s != null && !out.contains(s)) {
                        out.add(s);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读 getServiceUuids 失败", t);
        }
        if (!out.isEmpty()) {
            return out;
        }
        try {
            byte[] data = record.getBytes();
            if (data == null) {
                return out;
            }
            for (int i = 0; i < data.length - 2; ) {
                int len = data[i] & 0xFF;
                if (len == 0) {
                    break;
                }
                int type = data[i + 1] & 0xFF;
                if ((type == 0x02 || type == 0x03) && len >= 3) {
                    for (int off = i + 2; off + 1 < i + 1 + len; off += 2) {
                        int v = (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
                        String s = String.format(Locale.ROOT, "%04X", v);
                        if (!out.contains(s)) {
                            out.add(s);
                        }
                    }
                }
                i += len + 1;
            }
        } catch (Throwable t) {
            Log.w(TAG, "原始广播字节解析 UUID 失败", t);
        }
        return out;
    }

    /** 16-bit 基础 UUID 缩短成 4 位；其它保持完整字符串。 */
    private static String shortUuid(java.util.UUID uuid) {
        if (uuid == null) {
            return null;
        }
        if (uuid.getMostSignificantBits() == UUID_BASE_MSB) {
            return String.format(Locale.ROOT, "%04X", (uuid.getLeastSignificantBits() >>> 32) & 0xFFFF);
        }
        return uuid.toString().toUpperCase(Locale.ROOT);
    }

    private static String key(String address) {
        return address == null ? "" : address.toUpperCase(Locale.ROOT);
    }
}
