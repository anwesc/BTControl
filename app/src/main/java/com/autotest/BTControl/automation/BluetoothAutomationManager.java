package com.autotest.BTControl.automation;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.util.Log;

import com.autotest.BTControl.utils.BluetoothOperationUtils;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 蓝牙自动化操作管理器
 * 负责处理蓝牙设备的自动化操作逻辑
 */
public class BluetoothAutomationManager {
    private static final String TAG = "BTAutomationManager";
    
    // 自动化操作超时时间和重试参数
    private static final long AUTO_CONNECT_PERIOD = 120000; // 120秒
    private static final long RETRY_INTERVAL = 6000; // 6秒
    private static final int MAX_RETRIES = 10; // 最大重试次数

    private final BluetoothOperationUtils operationUtils;
    private final BluetoothAdapter bluetoothAdapter;
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;
    
    // 自动化执行状态
    private final AtomicBoolean autoAction = new AtomicBoolean(false);
    private final AtomicBoolean autoScan = new AtomicBoolean(false);

    // 设备映射（从BluetoothControlActivity传入）
    private final Map<String, BluetoothDevice> savedDevicesMap;
    private final Map<String, BluetoothDevice> nrDevicesMap;
    private final Map<String, BluetoothDevice> nfDevicesMap;

    /**
     * 自动化操作状态监听器
     */
    public interface AutomationStatusListener {
        void onOperationComplete(String action, boolean success, String message);
        void onDeviceDiscovered(BluetoothDevice device);
    }
    
    private AutomationStatusListener statusListener;
    
    /**
     * 构造函数
     * @param operationUtils 蓝牙操作工具类
     * @param executorService 执行器服务
     * @param scheduledExecutorService 调度执行器服务
     * @param savedDevicesMap 已保存设备映射
     * @param nrDevicesMap NR设备映射
     * @param nfDevicesMap NF设备映射
     */
    public BluetoothAutomationManager(Context context, BluetoothOperationUtils operationUtils,
                                      ExecutorService executorService, ScheduledExecutorService scheduledExecutorService,
                                      Map<String, BluetoothDevice> savedDevicesMap,
                                      Map<String, BluetoothDevice> nrDevicesMap,
                                      Map<String, BluetoothDevice> nfDevicesMap) {
        this.operationUtils = operationUtils;
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.savedDevicesMap = savedDevicesMap;
        this.nrDevicesMap = nrDevicesMap;
        this.nfDevicesMap = nfDevicesMap;
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

    }
    
    /**
     * 设置状态监听器
     * @param listener 监听器
     */
    public void setStatusListener(AutomationStatusListener listener) {
        this.statusListener = listener;
    }

    /**
     * 执行自动化操作
     * @param action 操作类型
     * @param deviceAddress 设备地址
     */
    public void executeAutoAction(String action, String deviceAddress) {
        if (operationUtils.hasBluetoothPermissions()) {
            return;
        }
        
        autoAction.set(true);
        
        executorService.submit(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long startTime = System.currentTimeMillis();

            // 执行具体操作
            boolean success = false;
            String message;
            
            switch (action) {
                case "扫描":
                    success = executeAutoScan(deviceAddress);
                    message = success ? "扫描成功" : "扫描失败或超时";
                    break;
                case "配对":
                    success = executeAutoPair(deviceAddress);
                    message = success ? "配对成功" : "配对失败或超时";
                    break;
                case "重新配对":
                    success = executeAutoRePair(deviceAddress);
                    message = success ? "重新配对成功" : "重新配对失败或超时";
                    break;
                case "连接":
                    success = executeAutoConnect(deviceAddress);
                    message = success ? "连接成功" : "连接失败或超时";
                    break;
                case "断链":
                    success = executeAutoDisconnect(deviceAddress);
                    message = success ? "断链成功" : "断链失败或超时";
                    break;
                case "取消配对":
                    success = executeAutoUnPair(deviceAddress);
                    message = success ? "取消配对成功" : "取消配对失败或超时";
                    break;
                default:
                    Log.e(TAG, "未知的自动化操作: " + action);
                    message = "未知的自动化操作: " + action;
                    autoAction.set(false);
            }
            
            // 通知操作完成
            notifyOperationComplete(action, success, message);
            
            // 等待操作完成
            while (System.currentTimeMillis() - startTime < AUTO_CONNECT_PERIOD && autoAction.get()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            autoAction.set(false);
            Log.d(TAG, "自动化操作线程已停止");
        });
    }
    
    /**
     * 停止自动化操作
     */
    public void stopAutoAction() {
        autoAction.set(false);
        autoScan.set(false);
    }
    
    /**
     * 执行自动扫描
     */
    @SuppressLint("MissingPermission")
    private boolean executeAutoScan(String deviceAddress) {
        Log.d(TAG, "执行扫描设备");

        AtomicInteger retryCount = new AtomicInteger(0);
        AtomicBoolean found = new AtomicBoolean(false);
        
        Runnable scanRetryTask = new Runnable() {
            @Override
            public void run() {
                if (!autoAction.get() || retryCount.get() >= MAX_RETRIES) {
                    autoScan.set(false);
                    return;
                }
                BluetoothDevice device = savedDevicesMap.get(deviceAddress);
                if (device != null) {
                    Log.d(TAG, "设备已配对，先取消配对");

                    final AtomicBoolean unpairComplete = new AtomicBoolean(false);
                    operationUtils.unpairDevice(device, new BluetoothOperationUtils.OperationCallback() {
                        @Override
                        public void onSuccess() {
                            unpairComplete.set(true);
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "取消配对失败: " + error);
                            unpairComplete.set(true);
                        }
                    });
                }

                // 等待设备被发现
                try {
                    Thread.sleep(6000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                printNRDevices();
                device = nrDevicesMap.get(deviceAddress);
                if (device == null) {
                    device = nfDevicesMap.get(deviceAddress);
                }
                if (device != null) {
                    Log.d(TAG, "已发现设备: " + device.getName());
                    notifyDeviceDiscovered(device);
                    found.set(true);
                    autoScan.set(false);
                } else {
                    Log.d(TAG, "未发现设备，重试中... 尝试次数: " + (retryCount.get() + 1));
                    retryCount.incrementAndGet();
                    scheduledExecutorService.schedule(this, RETRY_INTERVAL, TimeUnit.MILLISECONDS);
                }
            }
        };
        
        scheduledExecutorService.schedule(scanRetryTask, RETRY_INTERVAL, TimeUnit.MILLISECONDS);
        
        // 等待扫描完成
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < AUTO_CONNECT_PERIOD && autoAction.get() && !found.get()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        autoScan.set(false);
        return found.get();
    }
    
    /**
     * 执行自动配对
     */
    @SuppressLint("MissingPermission")
    private boolean executeAutoPair(String deviceAddress) {
        Log.d(TAG, "执行自动配对");
        
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicInteger retryCount = new AtomicInteger(0);
        
        while (retryCount.get() < MAX_RETRIES && autoAction.get()) {
            // 检查设备是否已配对，如果是，先取消配对
            BluetoothDevice deviceToPair = savedDevicesMap.get(deviceAddress);
            if (deviceToPair != null) {
                Log.d(TAG, "设备已配对，先取消配对");
                
                final AtomicBoolean unpairComplete = new AtomicBoolean(false);
                operationUtils.unpairDevice(deviceToPair, new BluetoothOperationUtils.OperationCallback() {
                    @Override
                    public void onSuccess() {
                        unpairComplete.set(true);
                    }
                    
                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "取消配对失败: " + error);
                        unpairComplete.set(true);
                    }
                });
                
                // 等待取消配对完成
                long startTime = System.currentTimeMillis();
                while (!unpairComplete.get() && System.currentTimeMillis() - startTime < 20000) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }

            // 等待设备被发现
            try {
                Thread.sleep(6000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            printNRDevices();
            BluetoothDevice device = nrDevicesMap.get(deviceAddress);
            if (device == null) {
                device = nfDevicesMap.get(deviceAddress);
            }
            if (device != null) {
                Log.d(TAG, "已发现设备: " + device.getName());
                autoScan.set(false);
                
                // 执行配对
                final AtomicBoolean pairComplete = new AtomicBoolean(false);
                operationUtils.pairDevice(device, new BluetoothOperationUtils.OperationCallback() {
                    @Override
                    public void onSuccess() {
                        pairComplete.set(true);
                        success.set(true);
                    }
                    
                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "配对失败: " + error);
                        pairComplete.set(true);
                    }
                });
                
                // 等待配对完成
                long startTime = System.currentTimeMillis();
                while (!pairComplete.get() && System.currentTimeMillis() - startTime < 30000) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                
                if (success.get()) {
                    break;
                }
            } else {
                Log.d(TAG, "未发现设备，重试中... 尝试次数: " + (retryCount.get() + 1));
                retryCount.incrementAndGet();
            }
        }
        
        autoScan.set(false);
        return success.get();
    }

    @SuppressLint("MissingPermission")
    private boolean executeAutoUnPair(String deviceAddress) {
        Log.d(TAG, "执行自动取消配对");

        final AtomicBoolean unpairSuccess = new AtomicBoolean(false);
        final AtomicBoolean unpairComplete = new AtomicBoolean(false);

        // 获取已配对的设备
        BluetoothDevice deviceToUnpair = savedDevicesMap.get(deviceAddress);
        if (deviceToUnpair != null) {
            operationUtils.unpairDevice(deviceToUnpair, new BluetoothOperationUtils.OperationCallback() {
                @Override
                public void onSuccess() {
                    unpairSuccess.set(true);
                    unpairComplete.set(true);
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "取消配对失败: " + error);
                    unpairComplete.set(true);
                }
            });

            // 等待取消配对完成
            long unpairStartTime = System.currentTimeMillis();
            while (!unpairComplete.get() && System.currentTimeMillis() - unpairStartTime < 20000) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

        } else {
            Log.e(TAG, "未找到要取消配对的设备: " + deviceAddress);
        }
        return  unpairSuccess.get();
    }

    /**
     * 执行自动重新配对
     */
    @SuppressLint("MissingPermission")
    private boolean executeAutoRePair(String deviceAddress) {
        Log.d(TAG, "执行自动重新配对");

        printSavedDevices();
        
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicInteger retryCount = new AtomicInteger(0);
        
        while (retryCount.get() < MAX_RETRIES && autoAction.get()) {
            // 检查设备是否已连接，如果是，先取消配对
            BluetoothDevice deviceToUnpair = savedDevicesMap.get(deviceAddress);
            if (deviceToUnpair != null) {
                Log.d(TAG, "设备已配对，先取消配对");

                final AtomicBoolean unpairComplete = new AtomicBoolean(false);
                operationUtils.unpairDevice(deviceToUnpair, new BluetoothOperationUtils.OperationCallback() {
                    @Override
                    public void onSuccess() {
                        unpairComplete.set(true);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "取消配对失败: " + error);
                        unpairComplete.set(true);
                    }
                });

                // 等待取消配对完成
                long startTime = System.currentTimeMillis();
                while (!unpairComplete.get() && System.currentTimeMillis() - startTime < 20000) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }

            // 等待设备被发现
            try {
                Thread.sleep(6000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            printNRDevices();
            BluetoothDevice device = nrDevicesMap.get(deviceAddress);
            if (device == null) {
                device = nfDevicesMap.get(deviceAddress);
            }
            if (device != null) {
                Log.d(TAG, "已发现设备: " + device.getName());

                autoScan.set(false);
                
                // 执行配对
                final AtomicBoolean pairComplete = new AtomicBoolean(false);
                operationUtils.pairDevice(device, new BluetoothOperationUtils.OperationCallback() {
                    @Override
                    public void onSuccess() {
                        pairComplete.set(true);
                        success.set(true);
                    }
                    
                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "配对失败: " + error);
                        pairComplete.set(true);
                    }
                });
                
                // 等待配对完成
                long startTime = System.currentTimeMillis();
                while (!pairComplete.get() && System.currentTimeMillis() - startTime < 30000) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                
                if (success.get()) {
                    break;
                }
            } else {
                Log.d(TAG, "未发现设备，重试中... 尝试次数: " + (retryCount.get() + 1));
                retryCount.incrementAndGet();
            }
        }
        
        autoScan.set(false);
        return success.get();
    }
    
    /**
     * 执行自动连接
     */
    private boolean executeAutoConnect(String deviceAddress) {
        Log.d(TAG, "执行自动连接");

        printSavedDevices();
        BluetoothDevice device = null;
        
        // 首先从已配对设备中查找
        if (savedDevicesMap.containsKey(deviceAddress)) {
            device = savedDevicesMap.get(deviceAddress);
        }

        if (device == null) {
            Log.e(TAG, "未找到要连接的设备: " + deviceAddress);
            return false;
        }
        
        AtomicBoolean connectComplete = new AtomicBoolean(false);
        AtomicBoolean connectSuccess = new AtomicBoolean(false);
        
        operationUtils.connectToDevice(device, new BluetoothOperationUtils.OperationCallback() {
            @Override
            public void onSuccess() {
                connectSuccess.set(true);
                connectComplete.set(true);
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "连接失败: " + error);
                connectComplete.set(true);
            }
        });
        
        // 等待连接完成
        long startTime = System.currentTimeMillis();
        while (!connectComplete.get() && System.currentTimeMillis() - startTime < 30000) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        return connectSuccess.get();
    }
    
    /**
     * 执行自动断链
     */
    private boolean executeAutoDisconnect(String deviceAddress) {
        Log.d(TAG, "执行自动断链");
        
        BluetoothDevice device = null;

        printSavedDevices();

        // 从已连接设备中查找
        if (savedDevicesMap.containsKey(deviceAddress)) {
            device = savedDevicesMap.get(deviceAddress);
        }
        
        if (device == null) {
            Log.e(TAG, "未找到要断链的设备: " + deviceAddress);
            return false;
        }
        
        AtomicBoolean disconnectComplete = new AtomicBoolean(false);
        AtomicBoolean disconnectSuccess = new AtomicBoolean(false);
        
        operationUtils.disconnectDevice(device, new BluetoothOperationUtils.OperationCallback() {
            @Override
            public void onSuccess() {
                disconnectSuccess.set(true);
                disconnectComplete.set(true);
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "断链失败: " + error);
                disconnectComplete.set(true);
            }
        });
        
        // 等待断链完成
        long startTime = System.currentTimeMillis();
        while (!disconnectComplete.get() && System.currentTimeMillis() - startTime < 30000) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        return disconnectSuccess.get();
    }
    

    /**
     * 通知操作完成
     */
    private void notifyOperationComplete(String action, boolean success, String message) {
        if (statusListener != null) {
            statusListener.onOperationComplete(action, success, message);
        }
    }
    
    /**
     * 通知设备发现
     */
    private void notifyDeviceDiscovered(BluetoothDevice device) {
        if (statusListener != null) {
            statusListener.onDeviceDiscovered(device);
        }
    }
    
    /**
     * 打印已保存设备映射中的所有设备
     */
    @SuppressLint("MissingPermission")
    public void printSavedDevices() {
        Log.d(TAG, "=== 打印已保存设备列表 ===");
        if (savedDevicesMap.isEmpty()) {
            Log.d(TAG, "已保存设备列表为空");
            return;
        }
        
        Log.d(TAG, "已保存设备总数: " + savedDevicesMap.size());
        for (Map.Entry<String, BluetoothDevice> entry : savedDevicesMap.entrySet()) {
            String address = entry.getKey();
            BluetoothDevice device = entry.getValue();
             String name = device.getName() != null ? device.getName() : "未知设备";
            Log.d(TAG, String.format("设备地址: %s, 设备名称: %s", address, name));
        }
        Log.d(TAG, "=== 已保存设备列表打印完成 ===");
    }

    @SuppressLint("MissingPermission")
    public void printNRDevices() {
        Log.d(TAG, "=== 打印附近设备列表 ===");
        if (nrDevicesMap.isEmpty()) {
            Log.d(TAG, "附近设备列表为空");
            return;
        }

        Log.d(TAG, "已扫描设备总数: " + nrDevicesMap.size());
        for (Map.Entry<String, BluetoothDevice> entry : nrDevicesMap.entrySet()) {
            String address = entry.getKey();
            BluetoothDevice device = entry.getValue();
            String name = device.getName() != null ? device.getName() : "未知设备";
            Log.d(TAG, String.format("设备地址: %s, 设备名称: %s", address, name));
        }
        Log.d(TAG, "=== 附近设备列表打印完成 ===");
    }
}