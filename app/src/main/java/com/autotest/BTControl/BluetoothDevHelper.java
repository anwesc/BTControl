package com.autotest.BTControl;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothHeadset;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class BluetoothDevHelper {

    private static final String TAG = "BluetoothDevHelper";
    private final BluetoothAdapter bluetoothAdapter;
    BluetoothA2dp bluetoothA2dp;
    BluetoothHeadset bluetoothHeadset;
    BluetoothLeAudio bluetoothLeAudio;
    private final Context context;

    private final Map<String, BluetoothGatt> gattMap = new HashMap<>();

    // 构造函数传递 Context
    public BluetoothDevHelper(Context context) {
        this.context = context;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    // 检查是否具有必要的蓝牙权限
    private boolean hasBluetoothPermissions() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing Bluetooth permissions");
            return false;
        }
        return true;
    }

    // 连接 A2DP 设备
    public void connectA2dp(BluetoothDevice device) {
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) {
                    bluetoothA2dp = (BluetoothA2dp) proxy;
                    invokeHiddenMethod(bluetoothA2dp,"connect", device);
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

    // 连接 HFP 设备
    public void connectHfp(BluetoothDevice device) {
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HEADSET) {
                    bluetoothHeadset = (BluetoothHeadset) proxy;
                    invokeHiddenMethod(bluetoothHeadset,"connect", device);
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

    // 连接 LE Audio 设备
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public void connectLeAudio(BluetoothDevice device) {
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.LE_AUDIO) {
                    bluetoothLeAudio = (BluetoothLeAudio) proxy;
                    invokeHiddenMethod(bluetoothLeAudio,"connect", device);
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.LE_AUDIO) {
                    bluetoothLeAudio = null;
                }
            }
        };

        bluetoothAdapter.getProfileProxy(context, listener, BluetoothProfile.LE_AUDIO);
    }


    // 断开 A2DP 设备连接
    public void disconnectA2dp(BluetoothDevice device) {
        if (bluetoothA2dp == null) {
            initializeA2dpProxy(() -> disconnectDevice(bluetoothA2dp, device));
        } else {
            disconnectDevice(bluetoothA2dp, device);
        }
    }

    // 断开 HFP 设备连接
    public void disconnectHfp(BluetoothDevice device) {
        if (bluetoothHeadset == null) {
            initializeHeadsetProxy(() -> disconnectDevice(bluetoothHeadset, device));
        } else {
            disconnectDevice(bluetoothHeadset, device);
        }
    }

    // 断开 LE Audio 设备连接
    public void disconnectLeAudio(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (bluetoothLeAudio == null) {
                initializeLeAudioProxy(() -> {
                    // 在初始化完成后尝试断开设备连接
                    if (bluetoothLeAudio != null) {
                        disconnectDevice(bluetoothLeAudio, device);
                    } else {
                        Log.e(TAG, "BluetoothLeAudio 初始化失败，无法断开连接。");
                    }
                });
            } else {
                // bluetoothLeAudio 已经初始化，直接断开设备连接
                disconnectDevice(bluetoothLeAudio, device);
            }
        } else {
            Log.e(TAG, "设备不支持 LE Audio 功能，无法断开连接。");
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

    // 初始化 LeAudio 代理对象
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

    // 初始化 HFP 代理对象
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

    // 执行断开设备连接的操作
    private void disconnectDevice(BluetoothProfile profileProxy, BluetoothDevice device) {
        try {
            Method method = profileProxy.getClass().getDeclaredMethod("disconnect", BluetoothDevice.class);
            method.setAccessible(true);
            method.invoke(profileProxy, device);
            Log.d(TAG, "断开设备连接请求已发送");
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "断开设备连接失败: " + e.getMessage());
        }
    }

    // 检查设备的 A2DP 或 Le Audio 或 HFP 连接状态
    public boolean isDeviceConnected(BluetoothProfile profileProxy, String deviceAddress, int profileType) {
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);

        if (profileProxy == null || device == null) {
            return false;
        } else {
            try {
                // 获取不同配置文件的连接状态
                Method method = profileProxy.getClass().getDeclaredMethod("getConnectionState", BluetoothDevice.class);
                method.setAccessible(true);
                int connectionState = (int) method.invoke(profileProxy, device);

                // 根据传入的 profileType 检查相应配置文件的连接状态
                switch (profileType) {
                    case BluetoothProfile.A2DP: // A2DP (Advanced Audio Distribution Profile)
                        return connectionState == BluetoothProfile.STATE_CONNECTED;
                    case BluetoothProfile.HEADSET: // HFP (Hands-Free Profile)
                        return connectionState == BluetoothProfile.STATE_CONNECTED;
                    case BluetoothProfile.LE_AUDIO: // LE Audio (Bluetooth Low Energy Audio)
                        return connectionState == BluetoothProfile.STATE_CONNECTED;
                    default:
                        return false;
                }
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }
    }


    // 使用反射调用隐藏方法
    private void invokeHiddenMethod(BluetoothProfile profileProxy, String methodName, BluetoothDevice device) {
        try {
            Method method = profileProxy.getClass().getDeclaredMethod(methodName, BluetoothDevice.class);
            method.setAccessible(true);
            method.invoke(profileProxy, device);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
