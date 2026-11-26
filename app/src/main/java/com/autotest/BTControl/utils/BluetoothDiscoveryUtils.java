package com.autotest.BTControl.utils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 蓝牙设备发现工具类
 * 负责扫描和发现蓝牙设备
 */
public class BluetoothDiscoveryUtils {
    private static final String TAG = "BluetoothDiscoveryUtils";
    
    private final Context context;
    private BluetoothAdapter bluetoothAdapter;
    private final Handler mainHandler;
    private DiscoveryCallback callback;
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    /** 广播接收器是否已注册：onResume 与 cleanup 都会调注册/注销，重复调必须幂等 */
    private boolean receiverRegistered;
    private long scanStartTime;
    private static final long TOTAL_SCAN_DURATION = 60000; // 总扫描时间60秒
    private static final long SCAN_DURATION = 2000; // 间隔时间2秒
    private Runnable continuousScanRunnable;
    private boolean isContinuousScanMode = true; // 标记是否为连续扫描模式
    
    /**
     * 发现回调接口
     */
    public interface DiscoveryCallback {
        /**
         * 发现开始时调用
         */
        void onDiscoveryStarted();
        
        /**
         * 发现设备时调用
         * @param device 发现的设备
         * @param rssi 设备信号强度
         */
        void onDeviceFound(BluetoothDevice device, int rssi);
        
        /**
         * 发现完成时调用
         */
        void onDiscoveryFinished();
        
        /**
         * 发生错误时调用
         * @param error 错误信息
         */
        void onDiscoveryError(String error);
        
        /**
         * 蓝牙状态变化时调用
         * @param state 蓝牙状态
         */
        void onBluetoothStateChanged(int state);
        
        /**
         * 设备配对状态变化时调用
         * @param device 设备
         * @param bondState 配对状态
         */
        void onBondStateChanged(BluetoothDevice device, int bondState);
    }
    
    /**
     * 构造函数
     * @param context 上下文
     */
    public BluetoothDiscoveryUtils(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        Log.d(TAG, "初始化BluetoothDiscoveryUtils");
        
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            Log.d(TAG, "BluetoothManager获取成功");
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null) {
                Log.d(TAG, "BluetoothAdapter获取成功，是否启用: " + bluetoothAdapter.isEnabled());
                BluetoothLeScanner bleScanner = bluetoothAdapter.getBluetoothLeScanner();
                if (bleScanner != null) {
                    Log.d(TAG, "BLE扫描器获取成功");
                } else {
                    Log.w(TAG, "BLE扫描器获取失败，可能设备不支持BLE");
                }
            } else {
                Log.e(TAG, "BluetoothAdapter获取失败，设备不支持蓝牙");
            }
        } else {
            Log.e(TAG, "BluetoothManager获取失败");
        }
    }
    
    /**
     * 注册蓝牙广播接收器，但不启动设备发现
     * 用于在应用启动时监听蓝牙状态变化
     */
    public void registerBluetoothReceiver() {
        Log.d(TAG, "注册蓝牙广播接收器");

        if (receiverRegistered) {
            // 重复注册会让同一个接收器收到多份 ACTION_FOUND，设备回调翻倍
            Log.d(TAG, "蓝牙广播接收器已注册，跳过重复注册");
            return;
        }

        try {
            // 注册广播接收器
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            context.registerReceiver(discoveryReceiver, filter);
            receiverRegistered = true;
            Log.d(TAG, "已注册蓝牙广播接收器");
        } catch (Exception e) {
            Log.e(TAG, "注册蓝牙广播接收器失败: " + e.getMessage(), e);
            if (callback != null) {
                callback.onDiscoveryError("注册蓝牙广播接收器失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 注销蓝牙广播接收器
     */
    public void unregisterBluetoothReceiver() {
        Log.d(TAG, "注销蓝牙广播接收器");

        if (!receiverRegistered) {
            Log.d(TAG, "蓝牙广播接收器未注册，跳过注销");
            return;
        }

        try {
            context.unregisterReceiver(discoveryReceiver);
            Log.d(TAG, "已注销蓝牙广播接收器");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "广播接收器未注册或已注销: " + e.getMessage());
        } finally {
            receiverRegistered = false;
        }
    }

    /**
     * 开始扫描蓝牙设备
     * @param callback 回调接口
     * @param continuousScan 是否使用连续扫描模式（true=连续扫描60秒，false=单次扫描12秒）
     */
    public void startDiscovery(boolean continuousScan, DiscoveryCallback callback) {
        Log.d(TAG, "开始设备发现，连续扫描模式: " + continuousScan);
        
        if (callback == null) {
            Log.e(TAG, "回调接口不能为null");
            return;
        }
        
        this.callback = callback;

        // 扫描前自己确保接收器在：只靠 Activity 生命周期注册，一旦暂停就变聋，设备全丢
        registerBluetoothReceiver();

        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter为null，无法开始设备发现");
            callback.onDiscoveryError("BluetoothAdapter为null");
            return;
        }
        
        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "蓝牙未启用，无法开始设备发现");
            callback.onDiscoveryError("蓝牙未启用");
            return;
        }
        
//        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
//            Log.e(TAG, "缺少蓝牙扫描权限");
//            callback.onDiscoveryError("缺少蓝牙扫描权限");
//            return;
//        }
        
        // 检查位置权限（经典蓝牙扫描需要）
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "缺少位置权限，经典蓝牙扫描需要位置权限");
            callback.onDiscoveryError("缺少位置权限，经典蓝牙扫描需要位置权限");
            return;
        }
        
        if (isScanning.get()) {
            Log.w(TAG, "已经在扫描中，先停止当前扫描");
            stopDiscovery();
        }
        
        isScanning.set(true);
        scanStartTime = System.currentTimeMillis();
        isContinuousScanMode = continuousScan; // 设置扫描模式标志
        
        if (continuousScan) {
            // 创建连续扫描任务
            continuousScanRunnable = new Runnable() {
                @Override
                public void run() {
                    long elapsedTime = System.currentTimeMillis() - scanStartTime;
                    
                    if (elapsedTime < TOTAL_SCAN_DURATION && isScanning.get()) {
                        // 还未达到总扫描时间，继续扫描
                        Log.d(TAG, "继续扫描，已用时: " + (elapsedTime / 1000) + "秒");
                        startSingleScan();
                        // 计划下一次扫描
                        mainHandler.postDelayed(this, SCAN_DURATION);
                    } else {
                        // 达到总扫描时间或扫描被停止
                        Log.d(TAG, "扫描完成，总用时: " + (elapsedTime / 1000) + "秒");
                        stopDiscovery();
                    }
                }
            };
            
            // 开始第一次扫描
            startSingleScan();
            
            // 通知回调扫描已开始
            mainHandler.post(callback::onDiscoveryStarted);
            
            // 计划下一次扫描
            mainHandler.postDelayed(continuousScanRunnable, SCAN_DURATION);
        } else {
            // 单次扫描模式
            Log.d(TAG, "执行单次扫描模式");
            startSingleScan();
            
            // 通知回调扫描已开始
            mainHandler.post(callback::onDiscoveryStarted);
        }
    }
    
    /** LE Audio 广播里会出现这两个服务：ASCS（音频流控制）/ PACS（发布音频能力）。 */
    private static final String[][] LE_AUDIO_SERVICES = {
            {"0000184E-0000-1000-8000-00805F9B34FB", "ASCS"},
            {"00001850-0000-1000-8000-00805F9B34FB", "PACS"},
    };

    /** BLE 扫描是否已启动，避免连续扫描时重复 startScan。 */
    private final AtomicBoolean leScanning = new AtomicBoolean(false);

    /** 起一轮带 LE Audio 过滤器的 BLE 扫描（失败只记日志，不影响经典发现）。 */
    @SuppressLint("MissingPermission")
    private void startLeAudioScan() {
        BluetoothLeScanner bleScanner = bluetoothAdapter == null ? null : bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null || !leScanning.compareAndSet(false, true)) {
            return;
        }
        try {
            List<ScanFilter> filters = new ArrayList<>();
            for (String[] service : LE_AUDIO_SERVICES) {
                filters.add(new ScanFilter.Builder()
                        .setServiceUuid(android.os.ParcelUuid.fromString(service[0]))
                        .build());
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            bleScanner.startScan(filters, settings, leScanCallback);
            Log.d(TAG, "BLE 扫描已启动（只过滤 LE Audio 服务）");
        } catch (Exception e) {
            leScanning.set(false);
            Log.w(TAG, "BLE 扫描启动失败: " + e.getMessage());
        }
    }

    /**
     * 执行单次扫描
     */
    @SuppressLint("MissingPermission")
    private void startSingleScan() {
        if (!isScanning.get() || bluetoothAdapter == null) {
            Log.w(TAG, "无法开始单次扫描：isScanning=" + isScanning.get() + ", bluetoothAdapter=" + (bluetoothAdapter != null));
            return;
        }
        // 如果系统已经在扫描，则不重复启动
        if (bluetoothAdapter.isDiscovering()) {
           return;
        }
        // 开始经典蓝牙设备发现
        Log.d(TAG, "启动经典蓝牙设备发现");
        boolean classicStarted = bluetoothAdapter.startDiscovery();
        if (!classicStarted) {
            Log.e(TAG, "无法启动经典蓝牙设备发现");
            if (callback != null) {
                callback.onDiscoveryError("无法启动经典蓝牙设备发现");
            }
            return;
        }
        Log.d(TAG, "经典蓝牙设备发现启动成功");

        // BLE 扫描只留着看 LE Audio：广播里的服务 UUID（ASCS/PACS）和 RSSI 只有这里拿得到，
        // 经典发现给不了。带上过滤器，避免把周围一堆随机地址刷进列表。
        startLeAudioScan();
    }
    
    /**
     * 处理发现的设备
     * @param device 发现的设备
     * @param rssi 设备信号强度
     */
    @SuppressLint("MissingPermission")
    private void handleDiscoveredDevice(BluetoothDevice device, int rssi) {
        if (device == null) {
            Log.w(TAG, "发现的设备为null，跳过处理");
            return;
        }
        
        String deviceAddress = device.getAddress();
        String deviceName = device.getName();
        
        Log.d(TAG, "处理发现的设备 - 名称: " + deviceName + ", 地址: " + deviceAddress + ", RSSI: " + rssi);
        ScanAdvertCache.putRssi(device, rssi);
        
        // 通过回调通知Activity发现的设备
        if (callback != null) {
            mainHandler.post(() -> callback.onDeviceFound(device, rssi));
        } else {
            Log.w(TAG, "回调为null，无法通知发现的设备");
        }
    }
    
    /**
     * 停止设备发现
     */
    @SuppressLint("MissingPermission")
    public void stopDiscovery() {
        Log.d(TAG, "停止设备发现");
        
        if (!isScanning.get()) {
            Log.w(TAG, "当前没有在扫描，无需停止");
            return;
        }
        
        isScanning.set(false);
        
        // 取消连续扫描任务
        if (continuousScanRunnable != null) {
            mainHandler.removeCallbacks(continuousScanRunnable);
            continuousScanRunnable = null;
        }
        
        if (bluetoothAdapter != null) {
            Log.d(TAG, "取消蓝牙设备发现");
            bluetoothAdapter.cancelDiscovery();
        } else {
            Log.w(TAG, "无法取消蓝牙设备发现：BluetoothAdapter为null或没有权限");
        }

        /*
        // 暂时关闭BLE设备扫描，只使用经典蓝牙扫描
        BluetoothLeScanner bleScanner = bluetoothAdapter == null ? null : bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner != null && leScanning.compareAndSet(true, false)) {
            bleScanner.stopScan(leScanCallback);
        }
        */

        // 使用局部变量保存callback引用，防止在mainHandler.post执行前callback被设置为null
        final DiscoveryCallback currentCallback = callback;
        if (currentCallback != null) {
            mainHandler.post(() -> {
                Log.d(TAG, "通知回调：设备发现已停止");
                currentCallback.onDiscoveryFinished();
            });
        } else {
            Log.w(TAG, "回调为null，无法通知设备发现已停止");
        }
    }
    
    // 蓝牙设备发现广播接收器
    @SuppressLint("MissingPermission")
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "收到广播: " + action);
            
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) 0);
                // 经典发现偶尔会带一个 UUID（BLE 设备被经典扫描到时），能拿就拿
                android.os.ParcelUuid foundUuid = intent.getParcelableExtra(BluetoothDevice.EXTRA_UUID);

                if (device != null) {
                        Log.d(TAG, "发现蓝牙设备: " + device.getName() + " (" + device.getAddress() + "), RSSI: " + rssi);

                        ScanAdvertCache.putUuid(device, foundUuid);
                        // 使用统一的设备处理方法
                        handleDiscoveredDevice(device, rssi);
                    } else {
                        Log.w(TAG, "发现蓝牙设备为null");
                    }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                Log.d(TAG, "蓝牙设备发现已启动");
                if (callback != null) {
                    callback.onDiscoveryStarted();
                } else {
                    Log.w(TAG, "回调为null，无法通知设备发现已启动");
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.d(TAG, "单次蓝牙设备发现已完成");
                
                if (isContinuousScanMode) {
                    Log.d(TAG, "连续扫描模式，不设置isScanning为false以支持连续扫描");
                    // 连续扫描模式，不设置isScanning为false，以支持连续扫描
                    if (callback == null) {
                        Log.w(TAG, "回调为null，无法通知设备发现已完成");
                    }
                } else {
                    Log.d(TAG, "单次扫描模式，设置isScanning为false并通知扫描完成");
                    // 单次扫描模式，设置isScanning为false并通知扫描完成
                    isScanning.set(false);
                    if (callback != null) {
                        mainHandler.post(() -> callback.onDiscoveryFinished());
                    } else {
                        Log.w(TAG, "回调为null，无法通知设备发现已完成");
                    }
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                Log.d(TAG, "蓝牙状态变化: " + state);
                if (callback != null) {
                    callback.onBluetoothStateChanged(state);
                } else {
                    Log.w(TAG, "回调为null，无法通知蓝牙状态变化");
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (device != null) {
                    Log.d(TAG, "设备配对状态变化: " + device.getName() + ", 状态: " + bondState);
                    if (callback != null) {
                        callback.onBondStateChanged(device, bondState);
                    } else {
                        Log.w(TAG, "回调为null，无法通知设备配对状态变化");
                    }
                } else {
                    Log.w(TAG, "配对状态变化的设备为null");
                }
            }
        }
    };
    
    /**
     * BLE扫描回调
     */
    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            if (device != null) {
                int rssi = result.getRssi();
                // 广播里带了 LE Audio 服务就记一笔，详情页据此决定是否露出这一行
                LeAudioSupport.observeScanResult(context, result);
                // 信号强度与服务 UUID 只在这一刻拿得到，先落缓存给列表和 btctl 结果用
                ScanAdvertCache.putScanResult(result);
                // 使用统一的设备处理方法
                handleDiscoveredDevice(device, rssi);
            }
        }
        
        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for (ScanResult result : results) {
                BluetoothDevice device = result.getDevice();
                if (device != null) {
                    int rssi = result.getRssi();
                    LeAudioSupport.observeScanResult(context, result);
                    ScanAdvertCache.putScanResult(result);
                    // 使用统一的设备处理方法
                    handleDiscoveredDevice(device, rssi);
                }
            }
        }
        
        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            String errorMessage = "BLE扫描失败，错误代码: " + errorCode;
            Log.e(TAG, errorMessage);
            // 使用局部变量保存callback引用，防止在mainHandler.post执行前callback被设置为null
            final DiscoveryCallback currentCallback = callback;
            if (currentCallback != null) {
                mainHandler.post(() -> currentCallback.onDiscoveryError(errorMessage));
            }
        }
    };

    /**
     * 清理资源
     */
    public void cleanup() {
        Log.d(TAG, "清理资源");
        
        stopDiscovery();
        
        // 注销蓝牙广播接收器
        unregisterBluetoothReceiver();
        
        // 清理连续扫描任务
        if (continuousScanRunnable != null) {
            mainHandler.removeCallbacks(continuousScanRunnable);
            continuousScanRunnable = null;
        }
        
        callback = null;
        Log.d(TAG, "资源清理完成");
    }
}