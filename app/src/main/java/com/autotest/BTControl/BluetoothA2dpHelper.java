package com.autotest.BTControl;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothA2dp;
import android.content.Context;

import java.lang.reflect.Method;

public class BluetoothA2dpHelper {

    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothA2dp bluetoothA2dp;
    private final Context context;

    // 构造函数传递 Context
    public BluetoothA2dpHelper(Context context) {
        this.context = context;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    // 连接 A2DP 设备
    public void connectA2dp(String deviceAddress) {
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);

        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) {
                    bluetoothA2dp = (BluetoothA2dp) proxy;
                    invokeHiddenMethod("connect", device);
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

    // 断开 A2DP 设备连接
    public void disconnectA2dp(String deviceAddress) {
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);

        if (bluetoothA2dp == null) {
            initializeA2dpProxy(() -> disconnectDevice(device));
        } else {
            disconnectDevice(device);
        }
    }

    // 初始化 A2DP 代理对象
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

    // 执行断开设备连接的操作
    private void disconnectDevice(BluetoothDevice device) {
        try {
            int connectionState = bluetoothA2dp.getConnectionState(device);
            if (connectionState != BluetoothProfile.STATE_CONNECTED) {
                throw new IllegalStateException("设备未连接");
            }
            invokeHiddenMethod("disconnect", device);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 检查设备的 A2DP 连接状态
    public boolean isDeviceConnected(String deviceAddress) {
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);

        if (bluetoothA2dp == null) {
            initializeA2dpProxy(() -> {
                // 检查连接状态
                boolean isConnected = checkDeviceConnection(device);
                // 可以通过回调或其他机制通知调用者
            });
            return false;
        } else {
            return checkDeviceConnection(device);
        }
    }

    // 通过代理对象检查设备连接状态
    private boolean checkDeviceConnection(BluetoothDevice device) {
        try {
            int connectionState = bluetoothA2dp.getConnectionState(device);
            return connectionState == BluetoothProfile.STATE_CONNECTED;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // 使用反射调用隐藏方法
    private void invokeHiddenMethod(String methodName, BluetoothDevice device) {
        try {
            Method method = BluetoothA2dp.class.getDeclaredMethod(methodName, BluetoothDevice.class);
            method.setAccessible(true);
            method.invoke(bluetoothA2dp, device);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
