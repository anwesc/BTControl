package com.autotest.BTControl.utils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothA2dp;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 蓝牙操作工具类
 * 提供蓝牙设备的配对、连接、断开连接等通用操作
 */
public class BluetoothOperationUtils {
    private static final String TAG = "BluetoothOperationUtils";
    
    // 操作超时时间
    private static final long DEVICE_OPERATION_TIMEOUT = 30000; // 30秒
    private static final long DISCONNECT_TIMEOUT = 15000; // 15秒
    
    private final Context context;
    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter bluetoothAdapter;
    private final ExecutorService executorService;

    private final UIUpdateCallback uiUpdateCallback;
    
    // 蓝牙Profile代理对象，用于代理连接和断链方案
    private BluetoothA2dp bluetoothA2dp;
    private BluetoothHeadset bluetoothHeadset;
    private BluetoothLeAudio bluetoothLeAudio;
    
    /**
     * UI更新回调接口
     */
    public interface UIUpdateCallback {
        void updateStatus(String status);
        void runOnUiThread(Runnable action);
    }
    
    /**
     * 构造函数
     * @param context 上下文
     * @param executorService 执行器服务
     * @param uiUpdateCallback UI更新回调
     */
    public BluetoothOperationUtils(Context context, ExecutorService executorService,
                                 UIUpdateCallback uiUpdateCallback) {
        this.context = context;
        this.executorService = executorService;
        this.uiUpdateCallback = uiUpdateCallback;
        
        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
    }
    
    /**
     * 检查是否有蓝牙权限
     * @return 是否有权限（true = 已授权）
     */
    public boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED;
        }
    }
    
    /**
     * 检查设备是否已连接（使用profile proxy检查）
     * @param device 蓝牙设备
     * @return 是否已连接
     */
    @SuppressLint({"MissingPermission", "InlinedApi"})
    public boolean isDeviceConnected(BluetoothDevice device) {
        if (bluetoothManager == null || device == null || bluetoothAdapter == null) {
            return false;
        }

        // 首先检查GATT连接状态
        boolean isGattConnected = false;
        try {
            List<BluetoothDevice> connectedGattDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
            for (BluetoothDevice connectedDevice : connectedGattDevices) {
                if (connectedDevice.getAddress().equals(device.getAddress())) {
                    isGattConnected = true;
                    break;
                }
            }
        } catch (Exception e) {
            // GATT不支持或出错，继续检查其他配置文件
            Log.w(TAG, "检查GATT连接状态失败", e);
        }

        // 使用更可靠的方式检查每个配置文件的连接状态
        // 通过ServiceListener获取代理对象，避免不支持的配置文件导致异常

        // 创建一个CountDownLatch来等待所有配置文件检查完成
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(4);
        java.util.concurrent.atomic.AtomicBoolean isConnected = new java.util.concurrent.atomic.AtomicBoolean(false);

        // 检查A2DP连接状态
        checkProfileConnection(device, BluetoothProfile.A2DP, isConnected, latch);

        // 检查HEADSET连接状态
        checkProfileConnection(device, BluetoothProfile.HEADSET, isConnected, latch);

        // 检查LE_AUDIO连接状态（如果支持）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkProfileConnection(device, BluetoothProfile.LE_AUDIO, isConnected, latch);
        } else {
            // 如果不支持，直接减少计数
            latch.countDown();
        }

        // 检查GATT_SERVER连接状态
        checkProfileConnection(device, BluetoothProfile.GATT_SERVER, isConnected, latch);

        // 等待所有配置文件检查完成（加超时，避免阻塞）
        try {
            boolean finished = latch.await(2000, TimeUnit.MILLISECONDS); // 等待最长2秒
            if (!finished) {
                Log.w(TAG, "等待profile检查超时，采用已获取的结果返回");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "等待profile检查被中断", e);
        }

        // 如果GATT连接已建立，也返回true
        if (isGattConnected) {
            return true;
        }

        return isConnected.get();
    }
    
    /**
     * 检查特定profile的连接状态
     * @param device 蓝牙设备
     * @param profile 蓝牙profile类型
     * @param isConnected 连接状态标志
     * @param latch CountDownLatch
     */
    @SuppressLint("MissingPermission")
    private void checkProfileConnection(BluetoothDevice device, int profile, 
                                       java.util.concurrent.atomic.AtomicBoolean isConnected, 
                                       java.util.concurrent.CountDownLatch latch) {
        // 为每个配置文件创建ServiceListener
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    // 获取该配置文件的连接设备列表
                    List<BluetoothDevice> profileDevices = proxy.getConnectedDevices();
                    for (BluetoothDevice connectedDevice : profileDevices) {
                        if (connectedDevice.getAddress().equals(device.getAddress())) {
                            isConnected.set(true);
                            // 只在调试模式下记录日志
                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                Log.d(TAG, getProfileName(profile) + " connected for device: " + device.getName());
                            }
                            break;
                        }
                    }
                } catch (Exception e) {
                    // 不记录错误日志，避免"蓝牙配置文件不支持"的提示
                } finally {
                    // 释放代理资源
                    bluetoothAdapter.closeProfileProxy(profile, proxy);
                    latch.countDown();
                }
            }
            
            @Override
            public void onServiceDisconnected(int profile) {
                latch.countDown();
            }
        };
        
        // 获取配置文件代理
        if (!bluetoothAdapter.getProfileProxy(context, listener, profile)) {
            // 如果获取代理失败，直接减少计数器
            latch.countDown();
        }
    }
    
    /**
     * 获取蓝牙配置文件的名称
     */
    private String getProfileName(int profile) {
        return switch (profile) {
            case BluetoothProfile.A2DP -> "A2DP";
            case BluetoothProfile.HEADSET -> "HEADSET";
            case BluetoothProfile.GATT -> "GATT";
            case BluetoothProfile.GATT_SERVER -> "GATT_SERVER";
            case BluetoothProfile.LE_AUDIO -> "LE_AUDIO";
            default -> "UNKNOWN(" + profile + ")";
        };
    }
    
    
    /**
     * 配对设备
     * @param device 蓝牙设备
     * @param onComplete 完成回调
     */
    @SuppressLint("MissingPermission")
    public void pairDevice(BluetoothDevice device, OperationCallback onComplete) {
        if (hasBluetoothPermissions()) {
            if (onComplete != null) {
                onComplete.onError("没有蓝牙权限");
            }
            return;
        }
        
        if (device == null) {
            if (onComplete != null) {
                onComplete.onError("设备为空");
            }
            return;
        }
        
        executorService.submit(() -> {
            try {
                if (device.getBondState() == BluetoothDevice.BOND_NONE) {

                    boolean pairingResult = device.createBond();
                    
                    uiUpdateCallback.runOnUiThread(() -> {
                        if (pairingResult) {
                            uiUpdateCallback.updateStatus("配对请求已发起，请在系统设置中确认");
                            Log.d(TAG, "配对请求已发起，请在系统设置中确认");
                        } else {
                            uiUpdateCallback.updateStatus("配对失败: " + device.getName());
                            Log.d(TAG, "配对失败: " + device.getName());
                        }
                    });
                    
                    // 等待配对完成
                    AtomicBoolean completed = new AtomicBoolean(false);
                    long startTime = System.currentTimeMillis();
                    
                    while (System.currentTimeMillis() - startTime < DEVICE_OPERATION_TIMEOUT) {
                        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                            completed.set(true);
                            String deviceName = device.getName();
                            Log.i(TAG, deviceName + "已连接");
                            Log.i(TAG, "配对时间: " + (System.currentTimeMillis() - startTime) + " ms");
                            uiUpdateCallback.runOnUiThread(() -> uiUpdateCallback.updateStatus("设备已配对: " + deviceName));
                            break;
                        }
                        Thread.sleep(500);
                    }
                    
                    if (completed.get()) {
                        if (onComplete != null) {
                            onComplete.onSuccess();
                        }
                    } else {
                        String errorMsg = device.getBondState() != BluetoothDevice.BOND_BONDED ? 
                                "设备配对超时" : "配对失败";
                        String detailedError = errorMsg + ": " + device.getName() + " (" + device.getAddress() + ")";
                        uiUpdateCallback.runOnUiThread(() -> {
                            uiUpdateCallback.updateStatus(detailedError);
                            Log.d(TAG, detailedError);
                        });
                        if (onComplete != null) {
                            onComplete.onError(detailedError);
                        }
                    }
                } else {
                    uiUpdateCallback.runOnUiThread(() -> {
                        String successMsg = "设备已配对";
                        String detailedSuccessMsg = successMsg + ": " + device.getName() + " (" + device.getAddress() + ")";
                        uiUpdateCallback.updateStatus(detailedSuccessMsg);
                        Log.d(TAG, detailedSuccessMsg);
                    });
                    if (onComplete != null) {
                        onComplete.onSuccess();
                    }
                }
            } catch (Exception e) {
                String errorMsg = "配对设备时出错";
                String detailedError = errorMsg + ": " + e.getMessage();
                Log.e(TAG, detailedError, e);
                uiUpdateCallback.runOnUiThread(() -> uiUpdateCallback.updateStatus(detailedError));
                if (onComplete != null) {
                    onComplete.onError(detailedError);
                }
            }
        });
    }
    
    /**
     * 连接设备
     * @param device 蓝牙设备
     * @param onComplete 完成回调
     */
    @SuppressLint("MissingPermission")
    public void connectToDevice(BluetoothDevice device, OperationCallback onComplete) {
         // 检查蓝牙权限
        if (hasBluetoothPermissions()) {
             Log.e(TAG, "没有蓝牙权限，无法连接设备");
             if (onComplete != null) {
                 onComplete.onError("没有蓝牙权限");
             }
             return;
         }

        // 检查设备是否为空
        if (device == null) {
            Log.e(TAG, "设备为空，无法连接");
            if (onComplete != null) {
                onComplete.onError("设备为空");
            }
            return;
        }
        
        executorService.submit(() -> {
            try {
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, "开始连接设备: " + device.getName() + " (" + device.getAddress() + ")");
                    uiUpdateCallback.runOnUiThread(() -> {
                        uiUpdateCallback.updateStatus("设备正在连接: " + device.getName());
                        Log.d(TAG, "设备正在连接: " + device.getName());
                    });
                    
                    // 尝试使用反射方法连接
                    try {
                        Method connectMethod = device.getClass().getMethod("connect");
                        boolean result = (Boolean) connectMethod.invoke(device);
                        if (result) {
                            Log.d(TAG, "连接命令已发送，等待连接完成: " + device.getName());
                        } else {
                            Log.e(TAG, "连接命令发送失败: " + device.getName());
                        }
                    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                        Log.e(TAG, "反射连接方法失败，使用代理方法", e);
                        
                        // 反射失败，尝试使用代理方案
                        Log.d(TAG, "尝试使用代理方案连接设备: " + device.getName());
                        uiUpdateCallback.runOnUiThread(() -> uiUpdateCallback.updateStatus("尝试使用代理方案连接: " + device.getName()));
                        
                        // 尝试使用所有可用的连接方法
                        AtomicBoolean anySuccess = new AtomicBoolean(false);
                        AtomicInteger completedOperations = new AtomicInteger(0);
                        
                        // 计算需要尝试的操作数量
                        final int totalOperations = 1; // A2DP
                        final int totalOperationsFinal = totalOperations + 1; // HFP
                        final int totalOperationsFinalWithLeAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? 
                            totalOperationsFinal + 1 : totalOperationsFinal;
                        
                        // 定义所有操作完成后的处理逻辑
                        final Runnable onAllOperationsComplete = () -> {
                            if (!anySuccess.get()) {
                                Log.e(TAG, "所有连接方案均失败");
                                uiUpdateCallback.runOnUiThread(() -> uiUpdateCallback.updateStatus("所有连接方案均失败: " + device.getName()));
                            } else {
                                Log.d(TAG, "至少一个连接方案成功");
                            }
                        };
                        
                        // 尝试使用 A2DP 连接
                        connectA2dp(device, new OperationCallback() {
                            @Override
                            public void onSuccess() {
                                Log.d(TAG, "A2DP 代理连接成功: " + device.getName());
                                anySuccess.set(true);
                                
                                // 检查是否所有操作都已完成
                                if (completedOperations.incrementAndGet() == totalOperationsFinalWithLeAudio) {
                                    onAllOperationsComplete.run();
                                }
                            }
                            
                            @Override
                            public void onError(String error) {
                                Log.e(TAG, "A2DP 代理连接失败: " + error);
                                
                                // 检查是否所有操作都已完成
                                if (completedOperations.incrementAndGet() == totalOperationsFinalWithLeAudio) {
                                    onAllOperationsComplete.run();
                                }
                            }
                        });
                        
                        // 尝试使用 HFP 连接
                        connectHfp(device, new OperationCallback() {
                            @Override
                            public void onSuccess() {
                                Log.d(TAG, "HFP 代理连接成功: " + device.getName());
                                anySuccess.set(true);
                                
                                // 检查是否所有操作都已完成
                                if (completedOperations.incrementAndGet() == totalOperationsFinalWithLeAudio) {
                                    onAllOperationsComplete.run();
                                }
                            }
                            
                            @Override
                            public void onError(String error) {
                                Log.e(TAG, "HFP 代理连接失败: " + error);
                                
                                // 检查是否所有操作都已完成
                                if (completedOperations.incrementAndGet() == totalOperationsFinalWithLeAudio) {
                                    onAllOperationsComplete.run();
                                }
                            }
                        });
                        
                        // 尝试使用 LE Audio 连接（如果支持）
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            connectLeAudio(device, new OperationCallback() {
                                @Override
                                public void onSuccess() {
                                    Log.d(TAG, "LE Audio 代理连接成功: " + device.getName());
                                    anySuccess.set(true);
                                    
                                    // 检查是否所有操作都已完成
                                    if (completedOperations.incrementAndGet() == totalOperationsFinalWithLeAudio) {
                                        onAllOperationsComplete.run();
                                    }
                                }
                                
                                @Override
                                public void onError(String error) {
                                    Log.e(TAG, "LE Audio 代理连接失败: " + error);
                                    
                                    // 检查是否所有操作都已完成
                                    if (completedOperations.incrementAndGet() == totalOperationsFinalWithLeAudio) {
                                        onAllOperationsComplete.run();
                                    }
                                }
                            });
                        } else {
                            // 如果不支持 LE Audio，直接增加已完成操作数
                            completedOperations.addAndGet(2); // A2DP和HFP已完成，LE Audio不需要
                        }
                    }
                    
                    // 等待连接完成
                    AtomicBoolean completed = new AtomicBoolean(false);
                    long startTime = System.currentTimeMillis();
                    
                    while (System.currentTimeMillis() - startTime < DEVICE_OPERATION_TIMEOUT) {
                        if (isDeviceConnected(device)) {
                            completed.set(true);
                            String DeviceName = device.getName();
                            Log.i(TAG, DeviceName + "已连接");
                            uiUpdateCallback.runOnUiThread(() -> {
                                uiUpdateCallback.updateStatus("设备已连接: " + DeviceName);
                                Log.d(TAG, "设备已连接: " + DeviceName);
                            });
                            break;
                        }
                        Thread.sleep(500);
                    }
                    
                    if (completed.get()) {
                        if (onComplete != null) {
                            onComplete.onSuccess();
                        }
                    } else {
                        String errorMsg = "设备连接超时";
                        String detailedError = errorMsg + ": " + device.getName() + " (" + device.getAddress() + ")";
                        Log.e(TAG, detailedError);
                        uiUpdateCallback.runOnUiThread(() -> {
                            uiUpdateCallback.updateStatus(detailedError);
                            Log.d(TAG, detailedError);
                        });
                        if (onComplete != null) {
                            onComplete.onError(detailedError);
                        }
                    }
                } else {
                    String errorMsg = "设备未配对，无法连接";
                    String detailedError = errorMsg + ": " + device.getName() + " (" + device.getAddress() + ")";
                    Log.w(TAG, detailedError);
                    uiUpdateCallback.runOnUiThread(() -> {
                        uiUpdateCallback.updateStatus(detailedError);
                        Log.d(TAG, detailedError);
                    });
                    if (onComplete != null) {
                        onComplete.onError(detailedError);
                    }
                }
            } catch (Exception e) {
                String errorMsg = "连接设备时出错";
                String detailedError = errorMsg + ": " + e.getMessage();
                Log.e(TAG, detailedError, e);
                uiUpdateCallback.runOnUiThread(() -> uiUpdateCallback.updateStatus(detailedError));
                if (onComplete != null) {
                    onComplete.onError(detailedError);
                }
            }
        });
    }
    
    /**
     * 断开设备连接
     * @param device 蓝牙设备
     * @param onComplete 完成回调
     */
    @SuppressLint("MissingPermission")
    public void disconnectDevice(BluetoothDevice device, OperationCallback onComplete) {
         // 检查蓝牙权限
        if (hasBluetoothPermissions()) {
             Log.e(TAG, "没有蓝牙权限，无法断开设备连接");
             if (onComplete != null) {
                 onComplete.onError("没有蓝牙权限");
             }
             return;
         }

        // 检查设备是否为空
        if (device == null) {
            Log.e(TAG, "设备为空，无法断开连接");
            if (onComplete != null) {
                onComplete.onError("设备为空");
            }
            return;
        }
        
        Log.d(TAG, "开始断开设备连接: " + device.getName() + " (" + device.getAddress() + ")");
        
        executorService.submit(() -> {
            try {
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    uiUpdateCallback.runOnUiThread(() -> {
                        uiUpdateCallback.updateStatus("设备正在断开连接: " + device.getName());
                        Log.d(TAG, "设备正在断开连接: " + device.getName());
                    });
                    
                    // 尝试使用反射方法断开连接
                    try {
                        Method disconnectMethod = device.getClass().getMethod("disconnect");
                        boolean result = (Boolean) disconnectMethod.invoke(device);
                        if (result) {
                            Log.d(TAG, "断开连接方法调用成功");
                        } else {
                            Log.e(TAG, "断开连接方法调用失败");
                        }

                    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                        Log.e(TAG, "反射断开连接方法失败，使用代理方法", e);
                        
                        // 反射失败，尝试使用代理方案
                        Log.d(TAG, "尝试使用代理方案断开设备连接: " + device.getName());
                        uiUpdateCallback.runOnUiThread(() -> uiUpdateCallback.updateStatus("尝试使用代理方案断开连接: " + device.getName()));
                        
                        // 尝试使用所有可用的断开连接方法
                        AtomicBoolean anySuccess = new AtomicBoolean(false);
                        AtomicInteger completedOperations = new AtomicInteger(0);
                        
                        // 计算需要尝试的操作数量
                        final Runnable checkAllOperationsComplete = getRunnable(device, anySuccess, completedOperations);

                        // 尝试使用 A2DP 断开连接
                        disconnectA2dp(device, new OperationCallback() {
                            @Override
                            public void onSuccess() {
                                Log.d(TAG, "A2DP 代理断开连接成功: " + device.getName());
                                anySuccess.set(true);
                                checkAllOperationsComplete.run();
                            }
                            
                            @Override
                            public void onError(String error) {
                                Log.e(TAG, "A2DP 代理断开连接失败: " + error);
                                checkAllOperationsComplete.run();
                            }
                        });
                        
                        // 尝试使用 HFP 断开连接
                        disconnectHfp(device, new OperationCallback() {
                            @Override
                            public void onSuccess() {
                                Log.d(TAG, "HFP 代理断开连接成功: " + device.getName());
                                anySuccess.set(true);
                                checkAllOperationsComplete.run();
                            }
                            
                            @Override
                            public void onError(String error) {
                                Log.e(TAG, "HFP 代理断开连接失败: " + error);
                                checkAllOperationsComplete.run();
                            }
                        });
                        
                        // 尝试使用 LE Audio 断开连接（如果支持）
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            disconnectLeAudio(device, new OperationCallback() {
                                @Override
                                public void onSuccess() {
                                    Log.d(TAG, "LE Audio 代理断开连接成功: " + device.getName());
                                    anySuccess.set(true);
                                    checkAllOperationsComplete.run();
                                }
                                
                                @Override
                                public void onError(String error) {
                                    Log.e(TAG, "LE Audio 代理断开连接失败: " + error);
                                    checkAllOperationsComplete.run();
                                }
                            });
                        } else {
                            // 如果不支持 LE Audio，直接增加已完成操作数
                            completedOperations.addAndGet(2); // A2DP和HFP已完成，LE Audio不需要
                        }
                    }
                    
                    // 等待断开连接完成
                    AtomicBoolean completed = new AtomicBoolean(false);
                    long startTime = System.currentTimeMillis();
                    
                    while (System.currentTimeMillis() - startTime < DISCONNECT_TIMEOUT) {
                        if (!isDeviceConnected(device)) {
                            completed.set(true);
                            String DeviceName = device.getName();
                            Log.i(TAG, DeviceName + "已断链");
                            uiUpdateCallback.runOnUiThread(() -> {
                                uiUpdateCallback.updateStatus("设备已断开连接: " + DeviceName);
                                Log.d(TAG, "设备已断开连接: " + DeviceName);
                            });
                            break;
                        }
                        Thread.sleep(500);
                    }
                    
                    if (completed.get()) {
                        if (onComplete != null) {
                            onComplete.onSuccess();
                        }
                    } else {
                        String errorMsg = "设备断开连接超时";
                        String detailedError = errorMsg + ": " + device.getName() + " (" + device.getAddress() + ")";
                        Log.e(TAG, detailedError);
                        uiUpdateCallback.runOnUiThread(() -> {
                            uiUpdateCallback.updateStatus(detailedError);
                            Log.d(TAG, detailedError);
                        });
                        if (onComplete != null) {
                            onComplete.onError(detailedError);
                        }
                    }
                } else {
                    String errorMsg = "设备未配对";
                    String detailedError = errorMsg + ": " + device.getName() + " (" + device.getAddress() + ")";
                    Log.w(TAG, detailedError);
                    uiUpdateCallback.runOnUiThread(() -> {
                        uiUpdateCallback.updateStatus(detailedError);
                        Log.d(TAG, detailedError);
                    });
                    if (onComplete != null) {
                        onComplete.onError(detailedError);
                    }
                }
            } catch (Exception e) {
                String errorMsg = "断开设备连接时出错";
                String detailedError = errorMsg + ": " + e.getMessage();
                Log.e(TAG, detailedError, e);
                uiUpdateCallback.runOnUiThread(() -> uiUpdateCallback.updateStatus(detailedError));
                if (onComplete != null) {
                    onComplete.onError(detailedError);
                }
            }
        });
    }

    @NonNull
    @SuppressLint("MissingPermission")
    private Runnable getRunnable(BluetoothDevice device, AtomicBoolean anySuccess, AtomicInteger completedOperations) {
        final int totalOperations = 1; // A2DP
        final int totalOperationsFinal = totalOperations + 1; // HFP
        final int totalOperationsFinalWithLeAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
            totalOperationsFinal + 1 : totalOperationsFinal;

        // 定义所有操作完成后的处理逻辑
        final Runnable onAllOperationsComplete = () -> {
            if (!anySuccess.get()) {
                Log.e(TAG, "所有断开连接方案均失败");
                uiUpdateCallback.runOnUiThread(() -> uiUpdateCallback.updateStatus("所有断开连接方案均失败: " + device.getName()));
            } else {
                Log.d(TAG, "至少一个断开连接方案成功");
            }
        };

        // 统一的完成检查方法，用于所有代理回调中
        return () -> {
            if (completedOperations.incrementAndGet() == totalOperationsFinalWithLeAudio) {
                onAllOperationsComplete.run();
            }
        };
    }

    /**
     * 取消设备配对
     * @param device 蓝牙设备
     * @param onComplete 完成回调
     */
    @SuppressLint("MissingPermission")
    public void unpairDevice(BluetoothDevice device, OperationCallback onComplete) {
        if (hasBluetoothPermissions()) {
            if (onComplete != null) {
                onComplete.onError("没有蓝牙权限");
            }
            return;
        }
        
        if (device == null) {
            if (onComplete != null) {
                onComplete.onError("设备为空");
            }
            return;
        }
        
        executorService.submit(() -> {
            try {
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    Method removeBondMethod = device.getClass().getMethod("removeBond");
                    boolean result = (Boolean) removeBondMethod.invoke(device);
                    
                    uiUpdateCallback.runOnUiThread(() -> {
                        if (result) {
                            String successMsg = "设备已取消配对";
                            String detailedSuccessMsg = successMsg + ": " + device.getName() + " (" + device.getAddress() + ")";
                            uiUpdateCallback.updateStatus(detailedSuccessMsg);
                            Log.d(TAG, detailedSuccessMsg);
                        } else {
                            String errorMsg = "取消配对失败";
                            String detailedErrorMsg = errorMsg + ": " + device.getName() + " (" + device.getAddress() + ")";
                            uiUpdateCallback.updateStatus(detailedErrorMsg);
                            Log.d(TAG, detailedErrorMsg);
                        }
                    });
                    
                    if (result) {
                        if (onComplete != null) {
                            onComplete.onSuccess();
                            Log.i(TAG, device.getName() + "解除配对成功");
                        }
                    } else {
                        if (onComplete != null) {
                            onComplete.onError("取消配对失败");
                        }
                    }
                } else {
                    String errorMsg = "设备未配对";
                    String detailedError = errorMsg + ": " + device.getName() + " (" + device.getAddress() + ")";
                    uiUpdateCallback.runOnUiThread(() -> {
                        uiUpdateCallback.updateStatus(detailedError);
                        Log.d(TAG, detailedError);
                    });
                    if (onComplete != null) {
                        onComplete.onError(detailedError);
                    }
                }
            } catch (Exception e) {
                String errorMsg = "取消设备配对时出错";
                String detailedError = errorMsg + ": " + e.getMessage();
                Log.e(TAG, detailedError, e);
                uiUpdateCallback.runOnUiThread(() -> uiUpdateCallback.updateStatus(detailedError));
                if (onComplete != null) {
                    onComplete.onError(detailedError);
                }
            }
        });
    }
    
    /**
     * 操作回调接口
     */
    public interface OperationCallback {
        void onSuccess();
        void onError(String error);
    }
    
    /**
     * 初始化 A2DP 代理对象
     * @param onInitialized 初始化完成回调
     */
    private void initializeA2dpProxy(Runnable onInitialized) {
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) {
                    bluetoothA2dp = (BluetoothA2dp) proxy;
                    onInitialized.run();
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.A2DP) {
                    bluetoothA2dp = null;
                }
            }
        };
        bluetoothAdapter.getProfileProxy(context, listener, BluetoothProfile.A2DP);
    }
    
    /**
     * 释放 A2DP 代理对象
     */
    private void closeA2dpProxy() {
        if (bluetoothA2dp != null && bluetoothAdapter != null) {
            try {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp);
                bluetoothA2dp = null;
            } catch (Exception e) {
                Log.e(TAG, "释放 A2DP 代理失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 初始化 LeAudio 代理对象
     * @param onInitialized 初始化完成回调
     */
    @SuppressLint("InlinedApi")
    private void initializeLeAudioProxy(Runnable onInitialized) {
        bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.LE_AUDIO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        bluetoothLeAudio = (BluetoothLeAudio) proxy;
                    }
                    onInitialized.run(); // 初始化完成后调用回调
                } else {
                    Log.e(TAG, "错误的 BluetoothProfile 类型: " + profile);
                    onInitialized.run(); // 确保回调被调用
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                Log.e(TAG, "BluetoothLeAudio 服务断开");
                bluetoothLeAudio = null; // 服务断开时清除代理
            }
        }, BluetoothProfile.LE_AUDIO);
    }
    
    /**
     * 释放 LeAudio 代理对象
     */
    @SuppressLint("InlinedApi")
    private void closeLeAudioProxy() {
        if (bluetoothLeAudio != null && bluetoothAdapter != null) {
            try {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.LE_AUDIO, bluetoothLeAudio);
                bluetoothLeAudio = null;
            } catch (Exception e) {
                Log.e(TAG, "释放 LE Audio 代理失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 初始化 HFP 代理对象
     * @param onInitialized 初始化完成回调
     */
    private void initializeHeadsetProxy(Runnable onInitialized) {
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HEADSET) {
                    bluetoothHeadset = (BluetoothHeadset) proxy;
                    onInitialized.run();
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.HEADSET) {
                    bluetoothHeadset = null;
                }
            }
        };
        bluetoothAdapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET);
    }
    
    /**
     * 释放 Headset 代理对象
     */
    private void closeHeadsetProxy() {
        if (bluetoothHeadset != null && bluetoothAdapter != null) {
            try {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset);
                bluetoothHeadset = null;
            } catch (Exception e) {
                Log.e(TAG, "释放 Headset 代理失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 使用 A2DP 代理连接设备（代理方案）
     * @param device 要连接的蓝牙设备
     * @param callback 连接结果回调
     */
    private void connectA2dp(BluetoothDevice device, OperationCallback callback) {
        initializeA2dpProxy(() -> {
            if (bluetoothA2dp != null) {
                try {
                    Method connectMethod = BluetoothA2dp.class.getMethod("connect", BluetoothDevice.class);
                    boolean result = (Boolean) connectMethod.invoke(bluetoothA2dp, device);
                    if (result) {
                        callback.onSuccess();
                    } else {
                        callback.onError("A2DP 连接失败");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "A2DP 连接异常: " + e.getMessage());
                    callback.onError("A2DP 连接异常: " + e.getMessage());
                } finally {
                    // 使用完代理后释放资源
                    closeA2dpProxy();
                }
            } else {
                callback.onError("A2DP 代理未初始化");
            }
        });
    }
    
    /**
     * 使用 HFP 代理连接设备（代理方案）
     * @param device 要连接的蓝牙设备
     * @param callback 连接结果回调
     */
    private void connectHfp(BluetoothDevice device, OperationCallback callback) {
        initializeHeadsetProxy(() -> {
            if (bluetoothHeadset != null) {
                try {
                    Method connectMethod = BluetoothHeadset.class.getMethod("connect", BluetoothDevice.class);
                    boolean result = (Boolean) connectMethod.invoke(bluetoothHeadset, device);
                    if (result) {
                        callback.onSuccess();
                    } else {
                        callback.onError("HFP 连接失败");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "HFP 连接异常: " + e.getMessage());
                    callback.onError("HFP 连接异常: " + e.getMessage());
                } finally {
                    // 使用完代理后释放资源
                    closeHeadsetProxy();
                }
            } else {
                callback.onError("HFP 代理未初始化");
            }
        });
    }
    
    /**
     * 使用 LE Audio 代理连接设备（代理方案）
     * @param device 要连接的蓝牙设备
     * @param callback 连接结果回调
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private void connectLeAudio(BluetoothDevice device, OperationCallback callback) {
        initializeLeAudioProxy(() -> {
            if (bluetoothLeAudio != null) {
                try {
                    Method connectMethod = BluetoothLeAudio.class.getMethod("connect", BluetoothDevice.class);
                    boolean result = (Boolean) connectMethod.invoke(bluetoothLeAudio, device);
                    if (result) {
                        callback.onSuccess();
                    } else {
                        callback.onError("LE Audio 连接失败");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "LE Audio 连接异常: " + e.getMessage());
                    callback.onError("LE Audio 连接异常: " + e.getMessage());
                } finally {
                    // 使用完代理后释放资源
                    closeLeAudioProxy();
                }
            } else {
                callback.onError("LE Audio 代理未初始化");
            }
        });
    }
    
    /**
     * 使用 A2DP 代理断开设备连接（代理方案）
     * @param device 要断开的蓝牙设备
     * @param callback 断开结果回调
     */
    private void disconnectA2dp(BluetoothDevice device, OperationCallback callback) {
        initializeA2dpProxy(() -> {
            if (bluetoothA2dp != null) {
                try {
                    Method disconnectMethod = BluetoothA2dp.class.getMethod("disconnect", BluetoothDevice.class);
                    boolean result = (Boolean) disconnectMethod.invoke(bluetoothA2dp, device);
                    if (result) {
                        callback.onSuccess();
                    } else {
                        callback.onError("A2DP 断开失败");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "A2DP 断开异常: " + e.getMessage());
                    callback.onError("A2DP 断开异常: " + e.getMessage());
                } finally {
                    // 使用完代理后释放资源
                    closeA2dpProxy();
                }
            } else {
                callback.onError("A2DP 代理未初始化");
            }
        });
    }
    
    /**
     * 使用 HFP 代理断开设备连接（代理方案）
     * @param device 要断开的蓝牙设备
     * @param callback 断开结果回调
     */
    private void disconnectHfp(BluetoothDevice device, OperationCallback callback) {
        initializeHeadsetProxy(() -> {
            if (bluetoothHeadset != null) {
                try {
                    Method disconnectMethod = BluetoothHeadset.class.getMethod("disconnect", BluetoothDevice.class);
                    boolean result = (Boolean) disconnectMethod.invoke(bluetoothHeadset, device);
                    if (result) {
                        callback.onSuccess();
                    } else {
                        callback.onError("HFP 断开失败");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "HFP 断开异常: " + e.getMessage());
                    callback.onError("HFP 断开异常: " + e.getMessage());
                } finally {
                    // 使用完代理后释放资源
                    closeHeadsetProxy();
                }
            } else {
                callback.onError("HFP 代理未初始化");
            }
        });
    }
    
    /**
     * 使用 LE Audio 代理断开设备连接（代理方案）
     * @param device 要断开的蓝牙设备
     * @param callback 断开结果回调
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private void disconnectLeAudio(BluetoothDevice device, OperationCallback callback) {
        initializeLeAudioProxy(() -> {
            if (bluetoothLeAudio != null) {
                try {
                    Method disconnectMethod = BluetoothLeAudio.class.getMethod("disconnect", BluetoothDevice.class);
                    boolean result = (Boolean) disconnectMethod.invoke(bluetoothLeAudio, device);
                    if (result) {
                        callback.onSuccess();
                    } else {
                        callback.onError("LE Audio 断开失败");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "LE Audio 断开异常: " + e.getMessage());
                    callback.onError("LE Audio 断开异常: " + e.getMessage());
                } finally {
                    // 使用完代理后释放资源
                    closeLeAudioProxy();
                }
            } else {
                callback.onError("LE Audio 代理未初始化");
            }
        });
    }
}
