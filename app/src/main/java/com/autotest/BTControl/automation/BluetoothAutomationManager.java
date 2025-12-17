package com.autotest.BTControl.automation;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.util.Log;

import com.autotest.BTControl.utils.BluetoothOperationUtils;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    public BluetoothAutomationManager(BluetoothOperationUtils operationUtils,
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
        // bluetoothAdapter 在本类中未直接使用，故不保存为字段以避免静态分析警告
        // BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        // BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

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
        // 如果没有蓝牙权限，则不执行自动化操作
        if (operationUtils.hasBluetoothPermissions()) {
            Log.w(TAG, "executeAutoAction: 缺少蓝牙权限，取消自动化操作");
            return;
        }
        
        autoAction.set(true);

        executorService.submit(() -> {
            // 保留短延迟以便UI/系统稳定
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            boolean success;
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
                    notifyOperationComplete(action, false, message);
                    return;
            }

            // 通知操作完成
            notifyOperationComplete(action, success, message);

            // 设置一个定时任务在 AUTO_CONNECT_PERIOD 后清理 autoAction 标志，避免长期占用
            scheduledExecutorService.schedule(() -> {
                autoAction.set(false);
                Log.d(TAG, "自动化操作超时/清理: autoAction 已设置为 false");
            }, AUTO_CONNECT_PERIOD, TimeUnit.MILLISECONDS);

            Log.d(TAG, "自动化操作线程已结束: action=" + action + ", success=" + success);
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
        CountDownLatch latch = new CountDownLatch(1);

        // 尝试取消已配对设备的配对（如果存在）并等待回调完成（有超时）
        BluetoothDevice paired = savedDevicesMap.get(deviceAddress);
        if (paired != null) {
            Log.d(TAG, "设备已配对，尝试取消配对");
            CountDownLatch unpairLatch = new CountDownLatch(1);
            operationUtils.unpairDevice(paired, new BluetoothOperationUtils.OperationCallback() {
                @Override
                public void onSuccess() {
                    unpairLatch.countDown();
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "取消配对失败: " + error);
                    unpairLatch.countDown();
                }
            });
            try {
                // 最多等待10秒以便系统处理取消配对
                boolean unpaired = unpairLatch.await(10, TimeUnit.SECONDS);
                if (!unpaired) {
                    Log.w(TAG, "executeAutoScan: 取消配对等待超时 (10s) for " + deviceAddress);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 定期检查附近设备映射，使用 ScheduledFuture 可取消
        final ScheduledFuture<?>[] futureRef = new ScheduledFuture<?>[1];
        Runnable checkTask = () -> {
            if (!autoAction.get() || retryCount.get() >= MAX_RETRIES) {
                autoScan.set(false);
                // 结束等待
                try {
                    latch.countDown();
                } catch (Exception ignored) {}
                if (futureRef[0] != null) futureRef[0].cancel(false);
                return;
            }

            BluetoothDevice device = nrDevicesMap.get(deviceAddress);
            if (device == null) device = nfDevicesMap.get(deviceAddress);
            if (device != null) {
                Log.d(TAG, "已发现设备: " + (device.getName() != null ? device.getName() : device.getAddress()));
                notifyDeviceDiscovered(device);
                found.set(true);
                autoScan.set(false);
                try {
                    latch.countDown();
                } catch (Exception ignored) {}
                if (futureRef[0] != null) futureRef[0].cancel(false);
            } else {
                retryCount.incrementAndGet();
                Log.d(TAG, "未发现设备，重试中... 尝试次数: " + retryCount.get());
            }
        };

        // 首次检查延迟6秒，与之前行为一致
        futureRef[0] = scheduledExecutorService.scheduleWithFixedDelay(checkTask, 6000, RETRY_INTERVAL, TimeUnit.MILLISECONDS);

        try {
            // 等待发现结果或超时
            boolean discovered = latch.await(AUTO_CONNECT_PERIOD, TimeUnit.MILLISECONDS);
            if (!discovered) {
                Log.w(TAG, "executeAutoScan: 等待发现设备超时 (" + AUTO_CONNECT_PERIOD + "ms) for " + deviceAddress);
                // 立即通知超时失败，便于UI层尽快响应
                notifyOperationComplete("扫描", false, "扫描等待超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (futureRef[0] != null && !futureRef[0].isCancelled()) futureRef[0].cancel(false);
            autoScan.set(false);
        }

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
                CountDownLatch unpairLatch = new CountDownLatch(1);
                operationUtils.unpairDevice(deviceToPair, new BluetoothOperationUtils.OperationCallback() {
                    @Override
                    public void onSuccess() {
                        unpairLatch.countDown();
                    }
                    
                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "取消配对失败: " + error);
                        unpairLatch.countDown();
                    }
                });
                try {
                    boolean unpaired = unpairLatch.await(20, TimeUnit.SECONDS);
                    if (!unpaired) {
                        Log.w(TAG, "executeAutoPair: 取消配对等待超时 (20s) for " + deviceAddress);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            // 等待设备被发现（使用调度检查替代睡眠等待）
             CountDownLatch foundLatch = new CountDownLatch(1);
            AtomicInteger localRetry = new AtomicInteger(0);
            final ScheduledFuture<?>[] futureRef = new ScheduledFuture<?>[1];
            Runnable checkTask = () -> {
                if (!autoAction.get() || localRetry.get() >= MAX_RETRIES) {
                    if (futureRef[0] != null) futureRef[0].cancel(false);
                    foundLatch.countDown();
                    return;
                }
                BluetoothDevice device = nrDevicesMap.get(deviceAddress);
                if (device == null) device = nfDevicesMap.get(deviceAddress);
                if (device != null) {
                    Log.d(TAG, "已发现设备: " + (device.getName() != null ? device.getName() : device.getAddress()));
                    foundLatch.countDown();
                    if (futureRef[0] != null) futureRef[0].cancel(false);
                } else {
                    localRetry.incrementAndGet();
                    Log.d(TAG, "自动配对等待发现，尝试次数: " + localRetry.get());
                }
            };
            futureRef[0] = scheduledExecutorService.scheduleWithFixedDelay(checkTask, 6000, RETRY_INTERVAL, TimeUnit.MILLISECONDS);

            try {
                // 等待发现或超时
                boolean foundDevice = foundLatch.await(60, TimeUnit.SECONDS);
                if (!foundDevice) {
                    Log.w(TAG, "executeAutoPair: 等待发现设备超时 (60s) for " + deviceAddress);
                    // 发现等待超时，主动回调失败
                    notifyOperationComplete("配对", false, "等待发现设备超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (futureRef[0] != null) futureRef[0].cancel(false);
                return false;
            }

            // 取到设备后执行配对
            BluetoothDevice device = nrDevicesMap.get(deviceAddress);
            if (device == null) device = nfDevicesMap.get(deviceAddress);
            if (device != null) {
                Log.d(TAG, "开始配对: " + (device.getName() != null ? device.getName() : device.getAddress()));
                CountDownLatch pairLatch = new CountDownLatch(1);
                operationUtils.pairDevice(device, new BluetoothOperationUtils.OperationCallback() {
                    @Override
                    public void onSuccess() {
                        pairLatch.countDown();
                        success.set(true);
                    }
                    
                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "配对失败: " + error);
                        pairLatch.countDown();
                    }
                });

                try {
                    boolean paired = pairLatch.await(30, TimeUnit.SECONDS);
                    if (!paired) {
                        Log.w(TAG, "executeAutoPair: 配对等待超时 (30s) for " + deviceAddress);
                        // 配对操作超时，主动回调失败
                        notifyOperationComplete("配对", false, "配对操作超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
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

        // 获取已配对的设备
        BluetoothDevice deviceToUnpair = savedDevicesMap.get(deviceAddress);
        if (deviceToUnpair != null) {
            CountDownLatch unpairLatch = new CountDownLatch(1);
            operationUtils.unpairDevice(deviceToUnpair, new BluetoothOperationUtils.OperationCallback() {
                @Override
                public void onSuccess() {
                    unpairSuccess.set(true);
                    unpairLatch.countDown();
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "取消配对失败: " + error);
                    unpairLatch.countDown();
                }
            });

            try {
                boolean unpaired = unpairLatch.await(20, TimeUnit.SECONDS);
                if (!unpaired) {
                    Log.w(TAG, "executeAutoUnPair: 取消配对等待超时 (20s) for " + deviceAddress);
                    // 取消配对超时，主动回调失败
                    notifyOperationComplete("取消配对", false, "取消配对超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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

                CountDownLatch unpairLatch = new CountDownLatch(1);
                operationUtils.unpairDevice(deviceToUnpair, new BluetoothOperationUtils.OperationCallback() {
                    @Override
                    public void onSuccess() {
                        unpairLatch.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "取消配对失败: " + error);
                        unpairLatch.countDown();
                    }
                });

                try {
                    boolean unpaired = unpairLatch.await(20, TimeUnit.SECONDS);
                    if (!unpaired) {
                        Log.w(TAG, "executeAutoRePair: 取消配对等待超时 (20s) for " + deviceAddress);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            // 等待设备被发现
            CountDownLatch foundLatch = new CountDownLatch(1);
            AtomicInteger localRetry = new AtomicInteger(0);
            final ScheduledFuture<?>[] futureRef = new ScheduledFuture<?>[1];
            Runnable checkTask = () -> {
                if (!autoAction.get() || localRetry.get() >= MAX_RETRIES) {
                    if (futureRef[0] != null) futureRef[0].cancel(false);
                    foundLatch.countDown();
                    return;
                }
                BluetoothDevice device = nrDevicesMap.get(deviceAddress);
                if (device == null) device = nfDevicesMap.get(deviceAddress);
                if (device != null) {
                    Log.d(TAG, "已发现设备: " + (device.getName() != null ? device.getName() : device.getAddress()));
                    foundLatch.countDown();
                    if (futureRef[0] != null) futureRef[0].cancel(false);
                } else {
                    localRetry.incrementAndGet();
                    Log.d(TAG, "自动重新配对等待发现，尝试次数: " + localRetry.get());
                }
            };
            futureRef[0] = scheduledExecutorService.scheduleWithFixedDelay(checkTask, 6000, RETRY_INTERVAL, TimeUnit.MILLISECONDS);

            try {
                boolean foundDevice = foundLatch.await(60, TimeUnit.SECONDS);
                if (!foundDevice) {
                    Log.w(TAG, "executeAutoRePair: 等待发现设备超时 (60s) for " + deviceAddress);
                    // 重新配对等待发现超时，主动回调失败
                    notifyOperationComplete("重新配对", false, "等待发现设备超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (futureRef[0] != null) futureRef[0].cancel(false);
                return false;
            }

            BluetoothDevice device = nrDevicesMap.get(deviceAddress);
            if (device == null) device = nfDevicesMap.get(deviceAddress);
            if (device != null) {
                Log.d(TAG, "开始配对: " + (device.getName() != null ? device.getName() : device.getAddress()));
                CountDownLatch pairLatch = new CountDownLatch(1);
                operationUtils.pairDevice(device, new BluetoothOperationUtils.OperationCallback() {
                    @Override
                    public void onSuccess() {
                        pairLatch.countDown();
                        success.set(true);
                    }
                    
                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "配对失败: " + error);
                        pairLatch.countDown();
                    }
                });

                try {
                    boolean paired = pairLatch.await(30, TimeUnit.SECONDS);
                    if (!paired) {
                        Log.w(TAG, "executeAutoRePair: 配对等待超时 (30s) for " + deviceAddress);
                        // 重新配对中的配对操作超时，主动回调失败
                        notifyOperationComplete("重新配对", false, "配对操作超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
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
        
        CountDownLatch connectLatch = new CountDownLatch(1);
        AtomicBoolean connectSuccess = new AtomicBoolean(false);
        
        operationUtils.connectToDevice(device, new BluetoothOperationUtils.OperationCallback() {
            @Override
            public void onSuccess() {
                connectSuccess.set(true);
                connectLatch.countDown();
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "连接失败: " + error);
                connectLatch.countDown();
            }
        });
        
        try {
            // 等待连接完成或超时
            boolean connected = connectLatch.await(30, TimeUnit.SECONDS);
            if (!connected) {
                Log.w(TAG, "executeAutoConnect: 等待连接超时 (30s) for " + deviceAddress);
                // 连接超时，主动回调失败
                notifyOperationComplete("连接", false, "连接超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
        
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        AtomicBoolean disconnectSuccess = new AtomicBoolean(false);
        
        operationUtils.disconnectDevice(device, new BluetoothOperationUtils.OperationCallback() {
            @Override
            public void onSuccess() {
                disconnectSuccess.set(true);
                disconnectLatch.countDown();
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "断链失败: " + error);
                disconnectLatch.countDown();
            }
        });
        
        try {
            boolean disconnected = disconnectLatch.await(30, TimeUnit.SECONDS);
            if (!disconnected) {
                Log.w(TAG, "executeAutoDisconnect: 等待断链超时 (30s) for " + deviceAddress);
                // 断链超时，主动回调失败
                notifyOperationComplete("断链", false, "断链超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    // printNRDevices 方法当前未被外部调用，注释保留以便将来启用
    // @SuppressLint("MissingPermission")
    // public void printNRDevices() {
    //     Log.d(TAG, "=== 打印附近设备列表 ===");
    //     if (nrDevicesMap.isEmpty()) {
    //         Log.d(TAG, "附近设备列表为空");
    //         return;
    //     }
    //
    //     Log.d(TAG, "已扫描设备总数: " + nrDevicesMap.size());
    //     for (Map.Entry<String, BluetoothDevice> entry : nrDevicesMap.entrySet()) {
    //         String address = entry.getKey();
    //         BluetoothDevice device = entry.getValue();
    //         String name = device.getName() != null ? device.getName() : "未知设备";
    //         Log.d(TAG, String.format("设备地址: %s, 设备名称: %s", address, name));
    //     }
    //     Log.d(TAG, "=== 附近设备列表打印完成 ===");
    // }
}
