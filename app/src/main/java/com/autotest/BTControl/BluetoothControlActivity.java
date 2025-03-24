package com.autotest.BTControl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.app.KeyguardManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;


public class BluetoothControlActivity extends Activity {

    // Constants
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;
    private static final int REQUEST_OVERLAY_PERMISSION = 2;
    private static final long AUTO_CONNECT_PERIOD = 60000;
    private static final int AUTO_DISCOVERY_PERIOD = 2000;
    private final Handler discovery_handler = new Handler(Looper.getMainLooper());
    // 添加 ExecutorService 作为成员变量
    private ExecutorService executorService;

    // UI Elements
    private TextView statusTextView;
    private CheckBox checkBox;
    private ProgressBar progressBar;
    private ListView deviceListView;
    private ListView nfdeviceListView;
    private ListView connectedDeviceListView;
    private Button actionButton;

    // Bluetooth-related
    private BluetoothGatt bluetoothGatt;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAudio bluetoothLeAudio;
    private String targetDeviceAddress;
    private String targetDeviceName;
    private String targetAction;

    // List Adapters
    private ArrayAdapter<String> deviceListAdapter;
    private ArrayAdapter<String> connectedDeviceListAdapter;
    private MyArrayAdapter nfdeviceListAdapter;

    // Device Lists
    private final ArrayList<String> deviceList = new ArrayList<>();
    private final ArrayList<String> nfdeviceList = new ArrayList<>();
    private final HashMap<String, BluetoothDevice> discoveredDevicesMap = new HashMap<>();
    private final HashMap<String, BluetoothDevice> NFDevicesMap = new HashMap<>();

    private final ArrayList<String> connectedDeviceList = new ArrayList<>();
    private final HashMap<String, BluetoothDevice> connectedDevicesMap = new HashMap<>();
    private PowerManager.WakeLock wakeLock;

    // Bluetooth Helper
    private final BluetoothDevHelper DevHelper = new BluetoothDevHelper(this);

    public static boolean auto_action = true;
    public static boolean auto_update = true;
    public static boolean auto_scan = true;
    public static boolean first_get_CtdDev_finish = false;
    private boolean isEventProcessing = false;
    private long lastClickTime = 0;
    private static final long CLICK_DELAY = 1234; // 1秒
    private static final String TAG = "BTControl";
    UUID LE_AUDIO_SERVICE_UUID = UUID.fromString("0000184E-0000-1000-8000-00805F9B34FB");
    UUID LE_AUDIO_CHARACTERISTIC_UUID = UUID.fromString("00002BC4-0000-1000-8000-00805f9b34fb");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_connection);

        // 初始化 WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "BTControl:MyWakelockTag");
        wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);

        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        // 解锁屏幕
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        KeyguardManager.KeyguardLock keyguardLock = keyguardManager.newKeyguardLock("BTControl::MyKeyguardLock");
        keyguardLock.disableKeyguard();

        if (executorService == null) {
            // 如果 executorService 为 null，可能需要重新初始化
            executorService = Executors.newFixedThreadPool(3);
        }

        initializeUI();
        initializeBluetooth();
        checkAndRequestPermissions();
        setupListViewListeners();
        setupActionButtonListener();
    }

    private void initializeUI() {
        statusTextView = findViewById(R.id.statusTextView);
        progressBar = findViewById(R.id.progressBar);
        deviceListView = findViewById(R.id.deviceListView);
        nfdeviceListView = findViewById(R.id.NFDeviceListView);
        connectedDeviceListView = findViewById(R.id.connectedDeviceListView);
        actionButton = findViewById(R.id.actionButton);
        checkBox = findViewById(R.id.checkBox);

        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceList);
        connectedDeviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, connectedDeviceList);

        deviceListView.setAdapter(deviceListAdapter);
        connectedDeviceListView.setAdapter(connectedDeviceListAdapter);

        nfdeviceListAdapter = new MyArrayAdapter(this, nfdeviceList);
        nfdeviceListView.setAdapter(nfdeviceListAdapter);

    }

    private void initializeBluetooth() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        targetDeviceAddress = getIntent().getStringExtra("bluetooth_address");
        if (targetDeviceAddress != null) {
            Log.d(TAG, "param: " + targetDeviceAddress);
        }
        targetDeviceName = getIntent().getStringExtra("bluetooth_name");
        if (targetDeviceName != null) {
            Log.d(TAG, "param: " + targetDeviceName);
        }
        targetAction = getIntent().getStringExtra("auto_action");
        if (targetAction != null) {
            Log.d(TAG, "param: " + targetAction);
            auto_action = true;
        }
    }

    private void checkAndRequestPermissions() {
        isEventProcessing = true;
        if (!hasPermissions()) {
            updateStatus("请求权限中...");
            requestPermissions();
        }
        if (!hasOverlayPermission()) {  // 新增“显示在其他应用上层”权限检查
            requestOverlayPermission();
        }
        isEventProcessing = false;
        startBluetoothDiscovery();
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

    }

    @SuppressLint("ObsoleteSdkInt")
    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 请求 Android S 及以上版本的权限
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    REQUEST_BLUETOOTH_PERMISSIONS
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 请求 Android 10 和 Android 11 的权限
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.BLUETOOTH,
                            Manifest.permission.BLUETOOTH_ADMIN,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    REQUEST_BLUETOOTH_PERMISSIONS
            );
        } else {
            // 请求 Android 9 及以下版本的权限
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.BLUETOOTH,
                            Manifest.permission.BLUETOOTH_ADMIN
                    },
                    REQUEST_BLUETOOTH_PERMISSIONS
            );
        }
    }

    private void requestOverlayPermission() {
        if (!hasOverlayPermission()) {
            updateStatus("请求‘显示在其他应用上层’权限...");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (hasOverlayPermission()) {
                updateStatus("‘显示在其他应用上层’权限已授予");
            } else {
                updateStatus("‘显示在其他应用上层’权限未授予");
                finish();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkAndRequestPermissions();
                updateStatus("权限授予成功，开始扫描...");
            } else {
                updateStatus("权限未授予");
                finish();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void setupListViewListeners() {
        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            if (isEventProcessing) {
                return; // 正在处理其他事件，忽略新的事件
            }
            isEventProcessing = true; // 开始处理事件
            try {
                String deviceInfo = deviceList.get(position);
                BluetoothDevice device = discoveredDevicesMap.get(getDeviceAddress(deviceInfo));
                if (device != null) {
                    updateStatus("正在连接设备: " + device.getName() + " [" + device.getAddress() + "]");
                    pairDevice(device);
                } else {
                    updateStatus("设备信息未找到");
                }
            }catch (Exception e){
                updateStatus("错误信息" + e);
            }finally {
                isEventProcessing = false; // 事件处理完毕
            }
        });

        connectedDeviceListView.setOnItemClickListener((parent, view, position, id) -> {
            try {
                if (position >= 0 && position < connectedDeviceList.size()) {
                    if (System.currentTimeMillis() - lastClickTime < CLICK_DELAY) {
                        return; // 如果在1秒内再次点击则直接返回，不处理
                    }
                    lastClickTime = System.currentTimeMillis(); // 记录上次点击时间

                    if (isEventProcessing) {
                        return; // 正在处理其他事件，忽略新的事件
                    }
                    isEventProcessing = true; // 开始处理事件
                    String deviceInfo = connectedDeviceList.get(position);
                    BluetoothDevice device = connectedDevicesMap.get(getDeviceAddress(deviceInfo));
                    if (device != null) {
                        if (deviceInfo.contains("状态:") && deviceInfo.contains("已连接")) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && deviceInfo.contains("LE Audio")) {
                                Log.d("BluetoothProfile", "这是一个LE Audio设备");
                                Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                                startActivity(intent);
                                Toast.makeText(this, "LE Audio设备请在蓝牙设置页面控制", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            disconnectDevice(device);
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkBox.isChecked()) {
                                Log.d("BluetoothProfile", "这是一个LE Audio设备");
                                Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                                startActivity(intent);
                                Toast.makeText(this, "LE Audio设备请在蓝牙设置页面控制", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            connectToDevice(device);
                        }
                    } else {
                        updateStatus("设备信息未找到");
                    }
                } else {
                    updateStatus("位置无效");
                }
            }catch (Exception e){
                updateStatus("错误信息" + e);
            }finally {
                isEventProcessing = false; // 事件处理完毕
            }
        });

        connectedDeviceListView.setOnItemLongClickListener((parent, view, position, id) -> {
            try {
                if (position >= 0 && position < connectedDeviceList.size()) {
                    if (isEventProcessing) {
                        return false; // 正在处理其他事件，忽略新的事件
                    }
                    isEventProcessing = true; // 开始处理事件
                    String deviceInfo = connectedDeviceList.get(position);
                    BluetoothDevice device = connectedDevicesMap.get(getDeviceAddress(deviceInfo));
                    if (device != null) {
                        unpairDevice(device);
                    } else {
                        updateStatus("设备信息未找到");
                    }
                    return true;
                } else {
                    updateStatus("位置无效");
                    return false;
                }
            }catch (Exception e){
                updateStatus("错误信息" + e);
            }finally {
                isEventProcessing = false; // 事件处理完毕
            }
            return false;
        });

        ImageView imageAccessibilitySettings = findViewById(R.id.image_accessibility_settings);
        imageAccessibilitySettings.setOnClickListener(v -> {
            // 跳转到无障碍设置页面
            startAccessibilityService();
        });
    }

    private String getDeviceAddress(String deviceInfo) {
        try {
            return deviceInfo.split("\n地址: ")[1].split("\n")[0];
        } catch (ArrayIndexOutOfBoundsException e) {
            Log.e(TAG, "error: " + e);
            return ""; // Return an empty string if parsing fails
        }
    }


    @SuppressLint("MissingPermission")
    private void setupActionButtonListener() {
        actionButton.setOnClickListener(v -> {
            if (hasPermissions()) {
                registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            } else {
                updateStatus("BLUETOOTH_SCAN 权限未授予");
            }
            if (bluetoothAdapter.isDiscovering()) {
                stopDiscovery_click();
            } else {
                resetDeviceLists();
                startDiscovery_click();
            }
        });
    }

    private final Runnable discoveryRunnable = new Runnable() {
        @SuppressLint("MissingPermission")
        @Override
        public void run() {
            if (auto_scan) {
                bluetoothAdapter.startDiscovery();
            }
            discovery_handler.postDelayed(this, AUTO_DISCOVERY_PERIOD); // Schedule next scan
        }
    };

    // 定义一个回调接口
    public interface ConnectionCallback {
        void onConnectionChecked(boolean isConnected);
    }

    // 通用的 BluetoothProfile 连接检查函数
    private void checkDeviceConnection(int profileType, String deviceAddress, ConnectionCallback callback) {
        bluetoothAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == profileType) {
                    boolean isConnected = false;

                    switch (profileType) {
                        case BluetoothProfile.A2DP:
                            BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                            isConnected = DevHelper.isDeviceConnected(a2dp, deviceAddress, BluetoothProfile.A2DP);
                            break;

                        case BluetoothProfile.HEADSET:
                            BluetoothHeadset headset = (BluetoothHeadset) proxy;
                            isConnected = DevHelper.isDeviceConnected(headset, deviceAddress, BluetoothProfile.HEADSET);
                            break;

                        case BluetoothProfile.LE_AUDIO:
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                BluetoothLeAudio leAudio = (BluetoothLeAudio) proxy;
                                isConnected = DevHelper.isDeviceConnected(leAudio, deviceAddress, BluetoothProfile.LE_AUDIO);
                            } else {
                                Log.w("DeviceConnection", "LE Audio 不受支持的 Android 版本");
                            }
                            break;

                        default:
                            Log.w("DeviceConnection", "未知的 BluetoothProfile 类型");
                            break;
                    }

                    Log.d("DeviceConnection", "设备连接状态: " + isConnected + " (Profile: " + profileType + ")");

                    // 使用回调函数返回连接状态
                    callback.onConnectionChecked(isConnected);

                    // 释放资源
                    bluetoothAdapter.closeProfileProxy(profileType, proxy);
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == profileType) {
                    callback.onConnectionChecked(false); // 当服务断开时，回调返回未连接状态
                }
            }
        }, profileType);
    }

    // 检查 A2DP 设备是否连接
    public void checkA2dpDeviceConnection(String deviceAddress, ConnectionCallback callback) {
        checkDeviceConnection(BluetoothProfile.A2DP, deviceAddress, callback);
    }

    // 检查 HFP 设备是否连接
    public void checkHfpDeviceConnection(String deviceAddress, ConnectionCallback callback) {
        checkDeviceConnection(BluetoothProfile.HEADSET, deviceAddress, callback);
    }

    // 检查 LE Audio 设备是否连接
    public void checkLeAudioDeviceConnection(String deviceAddress, ConnectionCallback callback) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            checkDeviceConnection(BluetoothProfile.LE_AUDIO, deviceAddress, callback);
        } else {
            Log.w("DeviceConnection", "LE Audio 不受支持的 Android 版本");
            callback.onConnectionChecked(false); // LE Audio 不支持时返回未连接状态
        }
    }


    @SuppressLint("MissingPermission")
    private void startBluetoothDiscovery() {
        if (hasPermissions()) {

            resetDeviceLists();
            registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            first_get_CtdDev_finish = false;
            auto_update = true;
//            if (isLeAudioSupported()) {
//                runOnUiThread(() -> checkBox.setVisibility(View.VISIBLE));
//                checkBox.setChecked(true);
//                boolean checked = checkBox.isChecked();
//            }
            bluetoothAdapter.startDiscovery();
            auto_scan = true;
            discovery_handler.post(discoveryRunnable);
            startAutoThread();
        } else {
            updateStatus("BLUETOOTH_SCAN 权限未授予");
        }
    }


    private void resetDeviceLists() {
        deviceList.clear();
        nfdeviceList.clear();
        discoveredDevicesMap.clear();
        deviceListAdapter.notifyDataSetChanged();
        nfdeviceListAdapter.notifyDataSetChanged();
    }

    private void startAutoThread() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12及以上
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10和Android 11
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else {
            // Android 9及以下
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        }


        // 自动更新 UI 列表
        ScheduledExecutorService scheduler_ui = Executors.newSingleThreadScheduledExecutor();
        scheduler_ui.scheduleWithFixedDelay(() -> {
            if (bluetoothAdapter.isDiscovering()) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.VISIBLE);
                    actionButton.setText("停止");
                    updateStatus("正在扫描...");
                    Log.d(TAG, "正在进行蓝牙扫描...");
                });
            } else {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    actionButton.setText("扫描");
                    updateStatus("扫描已停止!");
                    Log.d(TAG, "蓝牙扫描已停止...");
                });
            }
            if(auto_update){
                updateConnectedDeviceLists();
            }

            if (!auto_update) {
                Log.d(TAG, "auto_update: " + "自动刷新ui线程已停止");
                scheduler_ui.shutdown();
            }

        },1, 1, TimeUnit.SECONDS); // 初始延迟为0，之后每秒执行一次


        // 自动化操作线程
        executorService.submit(() -> {
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < AUTO_CONNECT_PERIOD && !first_get_CtdDev_finish) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // 恢复中断状态
                }
            }
            if (targetDeviceAddress != null && !targetDeviceAddress.isEmpty() && auto_action) {
                handleAutoAction();
            } else {
                Log.d(TAG, "auto_action: " + "未传入正确的参数，自动化操作线程已停止");
            }
            while (System.currentTimeMillis() - startTime < AUTO_CONNECT_PERIOD && auto_action) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // 恢复中断状态
                }
            }
            Log.d(TAG, "auto_action: " + "自动化操作线程已停止");
        });
    }

    private void handleAutoAction() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12及以上
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10和Android 11
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else {
            // Android 9及以下
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        }

        Log.d(TAG, "auto_action: " + "正在执行自动任务");
        int retryCount = 1;
        final int maxRetries = 8;
        if (Objects.equals(targetAction, "扫描")) {
            BluetoothDevice near_device;
            while (retryCount <= maxRetries) {
                near_device = discoveredDevicesMap.get(targetDeviceAddress);
                if (near_device != null) {
                    Log.d(TAG, "已发现设备: " + near_device.getName());
                    auto_scan = false;
                    break; // 如果发现设备，则退出循环
                } else {
                    Log.d(TAG, "未发现设备，重试中... 尝试次数: " + (retryCount + 1));
                    retryCount++;
                    if (retryCount <= maxRetries) {
                        try {
                            Thread.sleep(6000); // 等待6秒
                        } catch (InterruptedException e) {
                            Log.e(TAG, "线程被中断", e);
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            auto_action = false;
        } else if (Objects.equals(targetAction, "配对")) {
            BluetoothDevice near_device;
            while (retryCount <= maxRetries) {
                BluetoothDevice connected_device = connectedDevicesMap.get(targetDeviceAddress);
                if (connected_device != null) {
                    unpairDevice(connected_device);
                    try {
                        Thread.sleep(4800);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "线程被中断", e);
                        Thread.currentThread().interrupt();
                    }
                }
                auto_scan = true;
                near_device = discoveredDevicesMap.get(targetDeviceAddress);
                if (near_device != null) {
                    Log.d(TAG, "已发现设备: " + near_device.getName());
                    auto_scan = false;
                    pairDevice(near_device);
                    break; // 如果发现设备，则配对并退出循环
                } else {
                    Log.d(TAG, "未发现设备，重试中... 尝试次数: " + (retryCount + 1));
                    retryCount++;
                    if (retryCount <= maxRetries) {
                        try {
                            Thread.sleep(6000); // 等待6秒
                        } catch (InterruptedException e) {
                            Log.e(TAG, "线程被中断", e);
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }else if (Objects.equals(targetAction, "重新配对")) {
            BluetoothDevice near_device;
            while (retryCount <= maxRetries) {
                BluetoothDevice connected_device = connectedDevicesMap.get(targetDeviceAddress);
                if (connected_device != null) {
                    unpairDevice(connected_device);
                    try {
                        Thread.sleep(11111);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "线程被中断", e);
                        Thread.currentThread().interrupt();
                    }
                }
                near_device = discoveredDevicesMap.get(targetDeviceAddress);
                if (near_device != null) {
                    Log.d(TAG, "已发现设备: " + near_device.getName());
                    auto_scan = false;
                    pairDevice(near_device);
//                    auto_scan = true;
                    break; // 如果发现设备，则配对并退出循环
                } else {
                    Log.d(TAG, "未发现设备，重试中... 尝试次数: " + (retryCount + 1));
                    retryCount++;
                    if (retryCount <= maxRetries) {
                        try {
                            Thread.sleep(6000); // 等待6秒
                        } catch (InterruptedException e) {
                            Log.e(TAG, "线程被中断", e);
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        } else if (Objects.equals(targetAction, "连接")) {
            auto_scan = false;
            BluetoothDevice connected_device = connectedDevicesMap.get(targetDeviceAddress);
            if (connected_device != null) {
                connectToDevice(connected_device);
            }
        } else if (Objects.equals(targetAction, "断链")) {
            auto_scan = false;
            BluetoothDevice connected_device = connectedDevicesMap.get(targetDeviceAddress);
            if (connected_device != null) {
                disconnectDevice(connected_device);
            }
        }else if (Objects.equals(targetAction, "取消配对")) {
            auto_scan = false;
            BluetoothDevice connected_device = connectedDevicesMap.get(targetDeviceAddress);
            if (connected_device != null) {
                unpairDevice(connected_device);
            }
        } else {
            Log.e(TAG, "auto_action: " + "自动化操作未指定");
        }
    }


    private void Bluetooth_Discovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12及以上
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10和Android 11
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else {
            // Android 9及以下
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        }

        if (hasPermissions()) {
            resetDeviceLists();
            registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            bluetoothAdapter.startDiscovery();

        } else {
            updateStatus("BLUETOOTH_SCAN 权限未授予");
        }
    }

    private void stopDiscovery_click() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12及以上
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10和Android 11
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else {
            // Android 9及以下
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        }
        if (auto_scan) {
            auto_scan = false;
            discovery_handler.removeCallbacks(discoveryRunnable);
        }
        bluetoothAdapter.cancelDiscovery();
    }

    private void startDiscovery_click() {
        Bluetooth_Discovery();
        if (!auto_scan) {
            auto_scan = true;
            discovery_handler.post(discoveryRunnable);
        }
    }

    private void updateConnectedDeviceLists() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12及以上
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10和Android 11
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else {
            // Android 9及以下
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        }

        Log.d(TAG, "updateConnected: " + "正在更新已保存设备");

        new Thread(() -> {
            // 准备数据
            List<String> tempDeviceList = new ArrayList<>();
            Map<String, BluetoothDevice> tempDevicesMap = new HashMap<>();

            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : bondedDevices) {
                ConnectionStatus connectionStatus = isDeviceConnected(device);
                String ConnectedProfiles = connectionStatus.getConnectedProfiles();
                boolean isConnected = connectionStatus.isConnected();
                String deviceStatus = isConnected ? ConnectedProfiles + " 已连接" : "已配对";
                String deviceInfo;
                if (deviceStatus.equals("已配对")) {
                    deviceInfo = String.format("名称: %s\n地址: %s",
                            device.getName(),
                            device.getAddress()
                    );
                } else {
                    deviceInfo = String.format("名称: %s\n地址: %s\n状态: %s",
                            device.getName(),
                            device.getAddress(),
                            deviceStatus
                    );
                }
                tempDevicesMap.put(device.getAddress(), device);
                tempDeviceList.add(deviceInfo);
            }
            runOnUiThread(() -> {
                        if (isEventProcessing) {
                            return; // 正在处理其他事件，忽略新的事件
                        }
                        isEventProcessing = true;
                        connectedDevicesMap.clear();
                        connectedDevicesMap.putAll(tempDevicesMap);
                        connectedDeviceList.clear();
                        connectedDeviceList.addAll(tempDeviceList);
                        connectedDeviceListAdapter.notifyDataSetChanged();
                        isEventProcessing = false;
                    });
            if (!first_get_CtdDev_finish){
                first_get_CtdDev_finish = true;
            }
        }).start();
    }

    public static class ConnectionStatus {
        boolean isConnected;
        String connectedProfiles;

        public ConnectionStatus(boolean isConnected, String connectedProfiles) {
            this.isConnected = isConnected;
            this.connectedProfiles = connectedProfiles;
        }

        public boolean isConnected() {
            return isConnected;
        }

        public String getConnectedProfiles() {
            return connectedProfiles;
        }
    }


    private ConnectionStatus isDeviceConnected(BluetoothDevice device) {
        String deviceAddress = device.getAddress();
        boolean[] current_status = {false,false,false};
        final String[] strConnected = {""};
        final CountDownLatch latch = new CountDownLatch(3);
        try {
            checkHfpDeviceConnection(deviceAddress, isConnected -> {
                // 在这里处理设备连接状态，例如更新UI
                if (isConnected) {
                    current_status[0] = true;
                    Log.d("DeviceConnection", "设备HFP已连接");
                    if (!strConnected[0].isEmpty()) {
                        strConnected[0] += "/HFP"; // 连接状态非空时追加 "/HFP"
                    } else {
                        strConnected[0] = "HFP"; // 连接状态为空时直接赋值
                    }
                } else {
                    Log.d("DeviceConnection", "设备HFP未连接");
                }
                latch.countDown(); // 回调完成，计数器减一
            });

            checkA2dpDeviceConnection(deviceAddress, isConnected -> {
                // 在这里处理设备连接状态，例如更新UI
                if (isConnected) {
                    current_status[1] = true;
                    Log.d("DeviceConnection", "设备A2DP已连接");
                    if (!strConnected[0].isEmpty()) {
                        strConnected[0] += "/A2DP"; // 连接状态非空时追加 "/HFP"
                    } else {
                        strConnected[0] = "A2DP"; // 连接状态为空时直接赋值
                    }
                } else {
                    Log.d("DeviceConnection", "设备A2DP未连接");
                }
                latch.countDown(); // 回调完成，计数器减一
            });

            checkLeAudioDeviceConnection(deviceAddress, isConnected -> {
                // 处理 LE Audio 连接状态
                if (isConnected) {
                    current_status[2] = true;
                    Log.d("DeviceConnection", "设备LE Audio已连接");
                    if (!strConnected[0].isEmpty()) {
                        strConnected[0] += "/LE Audio"; // 连接状态非空时追加 "/HFP"
                    } else {
                        strConnected[0] = "LE Audio"; // 连接状态为空时直接赋值
                    }
                } else {
                    Log.d("DeviceConnection", "设备LE Audio未连接");
                }
                latch.countDown(); // 回调完成，计数器减一
            });

            latch.await();

            for (boolean status : current_status) {
                if (status) {
                    return new ConnectionStatus(true, strConnected[0]);
                }
            }
            return new ConnectionStatus(false, strConnected[0]);

//            Method isConnectedMethod = device.getClass().getMethod("isConnected");
//            return (Boolean) isConnectedMethod.invoke(device);
        } catch (Exception e) {
            Log.e(TAG, "error: " + e);
            return new ConnectionStatus(false, strConnected[0]);
        }
    }

    private void pairDevice(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12及以上
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10和Android 11
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else {
            // Android 9及以下
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        }

        if (hasPermissions()) {
            new Thread(() -> {
                try {
                    long duration = 20000; // 20秒
                    long startTime = 0;

                    if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                        boolean pairingResult = device.createBond();
                        startTime = System.currentTimeMillis();
                        runOnUiThread(() -> {
                            if (pairingResult) {
                                updateStatus("建链请求已发起，请在系统设置中确认");
                                Log.d(TAG, "建链请求已发起，请在系统设置中确认");
                            } else {
                                updateStatus("配对失败: " + device.getName());
                                Log.d(TAG, "配对失败: " + device.getName());
                            }
                        });
                    }
//
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    while (elapsedTime < duration) {
                        if (isDeviceConnected(device).isConnected()) {
//                            try {
//                                for (Method method : device.getClass().getDeclaredMethods()) {
//                                    Log.d("BluetoothMethods", method.getName());
//                                }
//                                Method getUuids = device.getClass().getMethod("getUuids");
//                                ParcelUuid[] uuids = (ParcelUuid[]) getUuids.invoke(device);
//                                if (uuids != null) {
//                                    for (ParcelUuid parcelUuid : uuids) {
//                                        // 获取并打印 UUID
//                                        UUID uuid = parcelUuid.getUuid();
//                                        Log.d("BluetoothUUID", "UUID: " + uuid.toString());
//                                    }
//                                } else {
//                                    Log.d("BluetoothUUID", "No UUIDs available for this device.");
//                                }
//                            } catch (NoSuchMethodException e) {
//                                Log.e("BluetoothMethod", "No such method", e);
//                            } catch (IllegalAccessException e) {
//                                Log.e("BluetoothMethod", "Illegal access", e);
//                            } catch (InvocationTargetException e) {
//                                Log.e("BluetoothMethod", "Invocation target exception", e);
//                            }
                            break;
                        }
                        Thread.sleep(100);
                        elapsedTime = System.currentTimeMillis() - startTime;
                    }
                    if (isDeviceConnected(device).isConnected()){
                        updateStatus("设备已连接: " + device.getName());
                        Log.d(TAG, "result: " + device.getName() + "已连接\n" + "配对时间:" + elapsedTime + "ms");
                    }else{
                        updateStatus("设备配对失败: " + device.getName());
                        Log.d(TAG, "result: " + device.getName() + "配对失败");
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
                auto_action = false;
            }).start();
        } else {
            updateStatus("BLUETOOTH_CONNECT 权限未授予");
        }
    }


    private void connectToDevice(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12及以上
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10和Android 11
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else {
            // Android 9及以下
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        }

        if (hasPermissions()) {
            new Thread(() -> {
                try {
                    if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                        updateStatus("设备正在连接: " + device.getName());
                        Log.d(TAG,"BluetoothProfile:" + "正在连接");
                        try {
                            Method connectDevice = device.getClass().getMethod("connect");
                            boolean result = (Boolean) connectDevice.invoke(device);
                            if (result) {
                                Log.d(TAG,"BluetoothProfile: 连接成功");
                            }

                        } catch (NoSuchMethodException e) {
                            Log.e(TAG, "BluetoothMethods:" + "No such method", e);
                            DevHelper.connectA2dp(device);
                            DevHelper.connectHfp(device);
                        } catch (IllegalAccessException e) {
                            Log.e(TAG, "BluetoothMethods:" + "Illegal access", e);
                            DevHelper.connectA2dp(device);
                            DevHelper.connectHfp(device);
                        } catch (InvocationTargetException e) {
                            Log.e(TAG, "BluetoothMethods:" + "Invocation target exception", e);
                            DevHelper.connectA2dp(device);
                            DevHelper.connectHfp(device);
//                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && device.getType() == BluetoothDevice.DEVICE_TYPE_LE) {
//                                Log.d(TAG, "BluetoothProfile:" + "这是一个LE Audio设备");
//                                return;
//                            }else{
//                                DevHelper.connectA2dp(device);
//                                DevHelper.connectHfp(device);
//                            }
                        }
                    }
                    Thread.sleep(1000);

                    long startTime = System.currentTimeMillis();
                    long duration = 20000; // 20秒超时
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    while (elapsedTime <= duration) {
                        if (isDeviceConnected(device).isConnected()) {
                            updateStatus("设备已连接: " + device.getName());
                            Log.d(TAG, "result: " + device.getName() + "已连接");
                            auto_action = false;
                            return;
                        }
                        Thread.sleep(100);
                        elapsedTime = System.currentTimeMillis() - startTime;
                    }
                    if (!isDeviceConnected(device).isConnected()) {
                        updateStatus("设备连接失败: " + device.getName());
                        Log.d(TAG, "result: " + device.getName() + "连接失败");
                    }
                } catch (InterruptedException e) {
                    updateStatus("设备连接失败: " + device.getName());
                    Log.d(TAG, "result: " + device.getName() + "连接失败");
                }
                auto_action = false;
            }).start();
        } else {
            updateStatus("BLUETOOTH_CONNECT 权限未授予");
        }
    }

    private void disconnectDevice(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12及以上
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10和Android 11
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else {
            // Android 9及以下
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        }

        if (hasPermissions()) {
            new Thread(() -> {
                try{
                    if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                        updateStatus("设备正在断链: " + device.getName());
                        Log.d(TAG, "disconnect: 正在断链");
                        try {
                            Method disconnectDevice = device.getClass().getMethod("disconnect");
                            boolean result = (Boolean) disconnectDevice.invoke(device);
                            if (result) {
                                Log.d(TAG, "disconnect: 断链成功");
                            }

                        } catch (NoSuchMethodException e) {
                            Log.e("BluetoothMethods", "No such method", e);
                            DevHelper.disconnectA2dp(device);
                            DevHelper.disconnectHfp(device);
                        } catch (IllegalAccessException e) {
                            Log.e("BluetoothMethods", "Illegal access", e);
                            DevHelper.disconnectA2dp(device);
                            DevHelper.disconnectHfp(device);
                        } catch (InvocationTargetException e) {
                            Log.e("BluetoothMethods", "Invocation target exception", e);
                            DevHelper.disconnectA2dp(device);
                            DevHelper.disconnectHfp(device);

//                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && device.getType() == BluetoothDevice.DEVICE_TYPE_LE) {
//                                Log.d("BluetoothProfile", "这是一个LE Audio设备");
//                                return;
//                            }else{
//                                DevHelper.disconnectA2dp(device);
//                                DevHelper.disconnectHfp(device);
//                            }
                        }

                        Thread.sleep(1000);

                        long startTime = System.currentTimeMillis();
                        long duration = 15000;
                        while (System.currentTimeMillis() - startTime <= duration) {
                            if (!isDeviceConnected(device).isConnected()) {
                                updateStatus("设备已断链: " + device.getName());
                                Log.d(TAG, "result: " + device.getName() + "已断链");
                                auto_action = false;
                                return;
                            }
                        }
                        if (System.currentTimeMillis() - startTime > duration){
                            updateStatus("设备断链失败: " + device.getName());
                            Log.d(TAG, "result: " + device.getName() + "断链失败");
                        }
                    }
                } catch (InterruptedException e) {
                    updateStatus("设备断链失败: " + device.getName());
                    Log.d(TAG, "result: " + device.getName() + "断链失败");
                }
                auto_action = false;
            }).start();
        } else {
            updateStatus("BLUETOOTH_CONNECT 权限未授予");
        }
    }

    private void unpairDevice(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12及以上
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10和Android 11
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        } else {
            // Android 9及以下
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予
                requestPermissions();
                return;
            }
        }
        if (hasPermissions()) {
            new Thread(() -> {
                try {
                    Method removeBondMethod = device.getClass().getMethod("removeBond");
                    boolean result = (Boolean) removeBondMethod.invoke(device);
                    if (result) {
                        updateStatus("设备已解除配对: " + device.getName());
                        Log.d(TAG, "result: " + device.getName() + "解除配对成功");
                    } else {
                        updateStatus("解除配对失败: " + device.getName());
                        Log.d(TAG, "result: " + device.getName() + "解除配对失败");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "error: " + e);
                }
                if (Objects.equals(targetAction, "取消配对")){
                    auto_action = false;
                }
            }).start();
        } else {
            updateStatus("BLUETOOTH_CONNECT 权限未授予");
        }
    }

    private void updateStatus(String status) {
        runOnUiThread(() -> statusTextView.setText(status));
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    @SuppressLint("MissingPermission") String deviceName = device.getName();
                    String deviceAddress = device.getAddress();

                    String deviceInfo;
                    if (deviceName == null) {
                        deviceInfo = "地址: " + deviceAddress; // Only display the address
                        if (!nfdeviceList.contains(deviceInfo)) {
                            nfdeviceList.add(deviceInfo);
                            NFDevicesMap.put(deviceAddress, device);
                            runOnUiThread(() -> nfdeviceListAdapter.notifyDataSetChanged());
                        }
                    } else {
                        deviceInfo = "名称: " + deviceName + "\n地址: " + deviceAddress;
                        BluetoothDevice connected_device = connectedDevicesMap.get(deviceAddress);
                        if (!deviceList.contains(deviceInfo)){
                            discoveredDevicesMap.put(deviceAddress, device);
                        }
                        if (!deviceList.contains(deviceInfo) && !(Objects.equals(device, connected_device))) {
                            deviceList.add(deviceInfo);
                            runOnUiThread(() -> deviceListAdapter.notifyDataSetChanged());
                        }
                    }
                }
            }
        }
    };


    @SuppressLint("MissingPermission")
    @Override
    protected void onDestroy() {
        Log.d(TAG,"正在关闭APP");
        super.onDestroy();
        unregisterReceiver(receiver);
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        // 关闭 ExecutorService
        if (executorService != null && !executorService.isShutdown()) {
            auto_action = false;
            auto_update = false;
            executorService.shutdown(); // 不接受新任务，但已提交的任务会继续执行
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow(); // 立即停止所有正在执行的任务
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow(); // 如果等待终止时被中断，则立即停止所有任务
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void startAccessibilityService() {
        if (!isAccessibilityServiceEnabled(this)) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "请手动开启无障碍服务", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "无障碍服务已启用", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isAccessibilityServiceEnabled(Context context) {
        String service = context.getPackageName() + "/" + MyAccessibilityService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabledServices != null && enabledServices.contains(service);
    }

}
