package com.autotest.BTControl;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class BluetoothControlActivity extends Activity {

    // Constants
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;
    private static final long AUTO_CONNECT_PERIOD = 60000;
    private final Handler handler = new Handler();
    // 添加 ExecutorService 作为成员变量
    private ExecutorService executorService;

    // UI Elements
    private TextView statusTextView;
    private ProgressBar progressBar;
    private ListView deviceListView;
    private ListView nfdeviceListView;
    private ListView connectedDeviceListView;
    private Button actionButton;

    // Bluetooth-related
    private BluetoothAdapter bluetoothAdapter;
    private String targetDeviceAddress;
    private String targetDeviceName;
    private String targetAction;

    // List Adapters
    private ArrayAdapter<String> deviceListAdapter;
    private ArrayAdapter<String> connectedDeviceListAdapter;
    private MyArrayAdapter nfdeviceListAdapter;

    // Device Lists
    private ArrayList<String> deviceList = new ArrayList<>();
    private ArrayList<String> nfdeviceList = new ArrayList<>();
    private ArrayList<String> connectedDeviceList = new ArrayList<>();
    private HashMap<String, BluetoothDevice> discoveredDevicesMap = new HashMap<>();
    private HashMap<String, BluetoothDevice> connectedDevicesMap = new HashMap<>();
    private HashMap<String, BluetoothDevice> NFDevicesMap = new HashMap<>();


    // Bluetooth Helper
    private BluetoothA2dpHelper a2dpHelper = new BluetoothA2dpHelper(this);

    public static boolean auto_action = true;
    public static boolean auto_update = true;
    public static boolean auto_scan = true;
    private static final String TAG = "BTControl";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_connection);

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
        nfdeviceListView = findViewById(R.id.NFDeviceListView );
        connectedDeviceListView = findViewById(R.id.connectedDeviceListView);
        actionButton = findViewById(R.id.actionButton);

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
            Log.d(TAG,"param: " + targetDeviceName);
        }
        targetAction = getIntent().getStringExtra("auto_action");
        if (targetAction != null){
            Log.d(TAG,"param: " + targetAction);
        }
    }

    private void checkAndRequestPermissions() {
        if (!hasPermissions()) {
            updateStatus("请求权限中...");
            requestPermissions();
        } else {
            auto_scan = true;
            startBluetoothDiscovery();
        }
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                },
                REQUEST_BLUETOOTH_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                updateStatus("权限授予成功，开始扫描...");
                startBluetoothDiscovery();

            } else {
                updateStatus("权限未授予");
                finish();
            }
        }
    }

    private void setupListViewListeners() {
        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            String deviceInfo = deviceList.get(position);
            BluetoothDevice device = discoveredDevicesMap.get(getDeviceAddress(deviceInfo));
            if (device != null) {
                updateStatus("正在连接设备: " + device.getName() + " [" + device.getAddress() + "]");
                pairDevice(device);
            } else {
                updateStatus("设备信息未找到");
            }
        });

        connectedDeviceListView.setOnItemClickListener((parent, view, position, id) -> {
            String deviceInfo = connectedDeviceList.get(position);
            BluetoothDevice device = connectedDevicesMap.get(getDeviceAddress(deviceInfo));
            if (device != null) {
                if (isDeviceConnected(device)) {
                    disconnectDevice(device);
                } else {
                    connectToDevice(device);
                }
            } else {
                updateStatus("设备信息未找到");
            }
        });

        connectedDeviceListView.setOnItemLongClickListener((parent, view, position, id) -> {
            String deviceInfo = connectedDeviceList.get(position);
            BluetoothDevice device = connectedDevicesMap.get(getDeviceAddress(deviceInfo));
            if (device != null) {
                unpairDevice(device);
            } else {
                updateStatus("设备信息未找到");
            }
            return true;
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
            e.printStackTrace();
            return ""; // Return an empty string if parsing fails
        }
    }


    private void setupActionButtonListener() {
        actionButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                resetDeviceLists();
                registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            } else {
                updateStatus("BLUETOOTH_SCAN 权限未授予");
            }
            if (bluetoothAdapter.isDiscovering()) {
                stopDiscovery_click();
            } else {
                startDiscovery_click();
            }
        });
    }

    private Runnable discoveryRunnable = new Runnable() {
        @Override
        public void run() {
            if (auto_scan) {
                bluetoothAdapter.startDiscovery();
                handler.postDelayed(this, AUTO_CONNECT_PERIOD); // Schedule next scan
            }
        }
    };

    private void startBluetoothDiscovery() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            resetDeviceLists();
            registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));

            auto_action = true;
            auto_update = true;
            startAutoThread();

            updateStatus("正在扫描...");
//            bluetoothAdapter.startDiscovery();
            auto_scan = true;
            discoveryRunnable.run();

        } else {
            updateStatus("BLUETOOTH_SCAN 权限未授予");
        }
    }


    private void resetDeviceLists() {
        deviceList.clear();
        discoveredDevicesMap.clear();
        deviceListAdapter.notifyDataSetChanged();
    }

    private void startAutoThread() {
        // 自动更新 UI 列表
        executorService.submit(() -> {
            while (!Thread.currentThread().isInterrupted()&&auto_update) {
//                Log.d(TAG, "auto_update: " + "正在自动刷新ui1");
                updateConnectedDeviceLists();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // 标记中断状态
                    break;
                }
            }
            Log.d(TAG, "auto_update: " + "自动刷新ui1线程已停止");
        });

        // 自动更新进度条和按钮
        executorService.submit(() -> {
            while (!Thread.currentThread().isInterrupted()&&auto_update) {
//                Log.d(TAG, "auto_update: " + "正在自动刷新ui2");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // 标记中断状态
                    break;
                }
                if (bluetoothAdapter.isDiscovering()) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.VISIBLE);
                        actionButton.setText("停止");
                    });
                } else {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        actionButton.setText("扫描");
                        if (auto_scan) {
                            updateStatus("扫描已停止");
                            auto_scan = false;
                        }
                    });
                }
            }
            Log.d(TAG, "auto_update: " + "自动刷新ui2线程已停止");
        });

        // 自动化操作线程
        executorService.submit(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 标记中断状态
            }
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < AUTO_CONNECT_PERIOD&&auto_action) {
                if (targetDeviceAddress != null && !targetDeviceAddress.isEmpty()) {
                    handleAutoAction();
                }else {
                    break;
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // 标记中断状态
                    break;
                }
            }
            Log.d(TAG, "auto_update: " + "自动化操作线程已停止");
        });
    }

    private void handleAutoAction() {
        Log.d(TAG, "auto_action: " + "正在执行自动任务");
        if (Objects.equals(targetAction, "配对")) {
            BluetoothDevice device = discoveredDevicesMap.get(targetDeviceAddress);
            if (device != null) {
                Log.d(TAG, "已发现设备: " + device.getName());
                BluetoothDevice connected_device = connectedDevicesMap.get(targetDeviceAddress);
                if (connected_device != null) {
                    unpairDevice(device);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                pairDevice(device);
                auto_action = false;
            }
        } else if (Objects.equals(targetAction, "连接")) {
            BluetoothDevice connected_device = connectedDevicesMap.get(targetDeviceAddress);
            if (connected_device != null) {
                connectToDevice(connected_device);
                auto_action = false;
            }
        } else if (Objects.equals(targetAction, "断链")) {
            BluetoothDevice connected_device = connectedDevicesMap.get(targetDeviceAddress);
            if (connected_device != null) {
                disconnectDevice(connected_device);
                auto_action = false;
            }
        } else {
            Log.e(TAG, "auto_action: " + "自动化操作未指定");
        }
    }


    private void Bluetooth_Discovery() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            resetDeviceLists();
            registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));

            updateStatus("正在扫描...");
            bluetoothAdapter.startDiscovery();
        } else {
            updateStatus("BLUETOOTH_SCAN 权限未授予");
        }
    }

    private void stopDiscovery_click() {
        progressBar.setVisibility(View.GONE);
        actionButton.setText("扫描");
        bluetoothAdapter.cancelDiscovery();
        updateStatus("扫描已停止");
        auto_scan = false;
        handler.removeCallbacks(discoveryRunnable);
    }

    private void startDiscovery_click() {
        progressBar.setVisibility(View.VISIBLE);
        actionButton.setText("停止");
        Bluetooth_Discovery();
        updateStatus("正在扫描");
    }

    private void updateConnectedDeviceLists() {
        connectedDevicesMap.clear();
        connectedDeviceList.clear();
//        Log.d(TAG, "updateConnected: " +"正在更新已保存设备");

        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : bondedDevices) {
            String deviceStatus = isDeviceConnected(device) ? "已连接" : "已配对";
            String deviceInfo = String.format("名称: %-32s%3s\n地址: %s\n",
                    device.getName(),
                    deviceStatus,
                    device.getAddress()
            );
            connectedDevicesMap.put(device.getAddress(), device);
            connectedDeviceList.add(deviceInfo);
        }

        runOnUiThread(() -> connectedDeviceListAdapter.notifyDataSetChanged());
    }

    private boolean isDeviceConnected(BluetoothDevice device) {
        try {
            Method isConnectedMethod = device.getClass().getMethod("isConnected");
            return (Boolean) isConnectedMethod.invoke(device);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void pairDevice(BluetoothDevice device) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            new Thread(() -> {
                String devicename = null;
                try {
                    devicename = device.getName();
                    if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                        boolean pairingResult = device.createBond();
                        runOnUiThread(() -> {
                            if (pairingResult) {
                                updateStatus("配对请求已发起，请在系统设置中确认配对");
                            } else {
                                updateStatus("配对失败: " + device.getName());
                            }
                        });
                    }
                    Thread.sleep(1000);

                    long startTime = System.currentTimeMillis();
                    long duration = 20000;
                    while (System.currentTimeMillis() - startTime < duration) {
                        boolean isConnected = a2dpHelper.isDeviceConnected(device.getAddress());
                        if (isConnected) {
                            updateStatus("设备已连接: " + device.getName());
                            Log.d(TAG, "result: " + device.getName() + "已连接");
                            return;
                        }
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                updateStatus("设备连接失败: " + devicename);
                Log.d(TAG, "result: " + devicename + "连接失败");
            }).start();
        } else {
            updateStatus("BLUETOOTH_CONNECT 权限未授予");
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            new Thread(() -> {
                String devicename = null;
                boolean repair = false;
                try {
                    devicename = device.getName();
                    if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                        a2dpHelper.connectA2dp(device.getAddress());
                        updateStatus("设备正在连接: " + device.getName());
                    }
                    Thread.sleep(1000);

                    long startTime = System.currentTimeMillis();
                    long duration = 15000;
                    while (System.currentTimeMillis() - startTime < duration) {
                        boolean isConnected = a2dpHelper.isDeviceConnected(device.getAddress());
                        if (isConnected) {
                            updateStatus("设备已连接: " + device.getName());
                            Log.d(TAG, "result: " + device.getName() + "已连接");
                            return;
                        }
                        if (System.currentTimeMillis() - startTime > 5000 && !repair) {
                            BluetoothDevice near_device = discoveredDevicesMap.get(device.getAddress());
                            if (Objects.equals(device, near_device)) {
                                unpairDevice(device);
                                pairDevice(device);
                                repair = true;
                            }
                        }
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                updateStatus("设备连接失败: " + devicename);
                Log.d(TAG, "result: " + devicename + "连接失败");
            }).start();
        } else {
            updateStatus("BLUETOOTH_CONNECT 权限未授予");
        }
    }

    private void disconnectDevice(BluetoothDevice device) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            new Thread(() -> {
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    a2dpHelper.disconnectA2dp(device.getAddress());
                    updateStatus("设备正在断链: " + device.getName());

                    long startTime = System.currentTimeMillis();
                    long duration = 20000;
                    while (System.currentTimeMillis() - startTime < duration) {
                        if (isDeviceConnected(device)) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        } else {
                            updateStatus("设备已断链: " + device.getName());
                            Log.d(TAG, "result: " + device.getName() + "已断链");
                            return;
                        }
                    }
                    updateStatus("设备断链失败: " + device.getName());
                    Log.d(TAG, "result: " + device.getName() + "断链失败");
                } else {
                    updateStatus("设备未连接");
                }
            }).start();
        } else {
            updateStatus("BLUETOOTH_CONNECT 权限未授予");
        }
    }

    private void unpairDevice(BluetoothDevice device) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            new Thread(() -> {
                try {
                    Method removeBondMethod = device.getClass().getMethod("removeBond");
                    boolean result = (Boolean) removeBondMethod.invoke(device);
                    runOnUiThread(() -> {
                        if (result) {
                            updateStatus("设备已解除配对: " + device.getName());
                            Log.d(TAG, "result: " + device.getName() + "取消配对成功");
                        } else {
                            updateStatus("解除配对失败: " + device.getName());
                            Log.d(TAG, "result: " + device.getName() + "取消配对失败");
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
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
                    String deviceName = device.getName();
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
                if (!executorService.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
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
//        startAccessibilityService();
    }

    private void startAccessibilityService() {
        if (!isAccessibilityServiceEnabled(this, MyAccessibilityService.class)) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "请手动开启无障碍服务", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "无障碍服务已启用", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isAccessibilityServiceEnabled(Context context, Class<?> serviceClass) {
        String service = context.getPackageName() + "/" + serviceClass.getCanonicalName();
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabledServices != null && enabledServices.contains(service);
    }

}
