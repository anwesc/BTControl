package com.autotest.BTControl;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class BluetoothControlActivity extends Activity {

    // Constants
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;
    private static final long AUTO_CONNECT_PERIOD = 60000;

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
    public static boolean auto_scan = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_connection);

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
            Log.d("param",targetDeviceAddress);
        }
        targetDeviceName = getIntent().getStringExtra("bluetooth_name");
        if (targetDeviceName != null) {
            Log.d("param",targetDeviceName);
        }
        targetAction = getIntent().getStringExtra("auto_action");
        if (targetAction != null){
            Log.d("param",targetAction);
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
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
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
            if (bluetoothAdapter.isDiscovering()) {
                stopDiscovery();
            } else {
                startDiscovery();
            }
        });
    }

    private void startBluetoothDiscovery() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            resetDeviceLists();
            registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));

            startAutoUpdateThread();
            startAutoConnectThread();

            updateStatus("正在扫描...");
            bluetoothAdapter.startDiscovery();
            auto_scan = true;

        } else {
            updateStatus("BLUETOOTH_SCAN 权限未授予");
        }
    }


    private void resetDeviceLists() {
        deviceList.clear();
        discoveredDevicesMap.clear();
        deviceListAdapter.notifyDataSetChanged();
    }

    private void startAutoUpdateThread() {
        new Thread(() -> {
            while (true) {
//                Log.d("auto_update", "正在更新已保存设备");
                updateConnectedDeviceLists();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
        new Thread(() -> {
            while (true) {
//                Log.d("update_discover", "正在更新扫描配置");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (bluetoothAdapter.isDiscovering()) {
                    // 使用 runOnUiThread 确保 UI 更新在主线程中执行
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
        }).start();
    }

    private void startAutoConnectThread() {
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
//            updateConnectedDeviceLists();
            while (System.currentTimeMillis() - startTime < AUTO_CONNECT_PERIOD) {

                Log.d("auto_action","正在执行自动任务");
                if (targetDeviceAddress != null && !targetDeviceAddress.isEmpty()) {
                    if (Objects.equals(targetAction, "配对")){
                        BluetoothDevice device = discoveredDevicesMap.get(targetDeviceAddress);
                        if (device != null) {
                            Log.d("targetdevice", device.getName());
                            BluetoothDevice connected_device = connectedDevicesMap.get(targetDeviceAddress);
                            if (connected_device != null) {
                                Log.d("connected_device", connected_device.getName());
                                unpairDevice(device);
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                            pairDevice(device);
                            break;
                        }
                    } else if (Objects.equals(targetAction, "连接")) {
                        BluetoothDevice device = connectedDevicesMap.get(targetDeviceAddress);
                        if (device == null){
                            device = connectedDevicesMap.get(targetDeviceAddress);
                        }
                        if (device != null) {
                            connectToDevice(device);
                            break;
                        }
                        
                    }else if (Objects.equals(targetAction, "断链")){
                        BluetoothDevice device = connectedDevicesMap.get(targetDeviceAddress);
                        if (device == null){
                            device = connectedDevicesMap.get(targetDeviceAddress);
                        }
                        disconnectDevice(device);
                        break;
                    }else {
                        Log.e("auto_action","自动化操作未指定");
                        break;
                    }
                }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            auto_action = false;
            }
        }).start();
    }

    private void click_Discovery() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            resetDeviceLists();
            registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));

            updateStatus("正在扫描...");
            bluetoothAdapter.startDiscovery();

        } else {
            updateStatus("BLUETOOTH_SCAN 权限未授予");
        }
    }

    private void stopDiscovery() {
        progressBar.setVisibility(View.GONE);
        actionButton.setText("扫描");
        bluetoothAdapter.cancelDiscovery();
        updateStatus("扫描已停止");
    }

    private void startDiscovery() {
        progressBar.setVisibility(View.VISIBLE);
        actionButton.setText("停止");
        click_Discovery();
        updateStatus("正在扫描");
    }

    private void updateConnectedDeviceLists() {
        connectedDevicesMap.clear();
        connectedDeviceList.clear();
//        Log.d("updateConnected", "正在更新已保存设备");

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
                    // 等待设备连接状态更新
                    Thread.sleep(1000);

                    long startTime = System.currentTimeMillis();
                    long duration = 20000; // 20秒的持续时间
                    while (System.currentTimeMillis() - startTime < duration) {
                        boolean isConnected = a2dpHelper.isDeviceConnected(device.getAddress());
                        if (isConnected) {
                            updateStatus("设备已连接: " + device.getName());
                            Log.d("result", device.getName() + "已连接");
                            return;
                        }
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                updateStatus("设备连接失败: " + devicename);
                Log.d("result", devicename + "连接失败");
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
                    // 等待设备连接状态更新
                    Thread.sleep(1000);

                    long startTime = System.currentTimeMillis();
                    long duration = 15000; // 20秒的持续时间
                    while (System.currentTimeMillis() - startTime < duration) {
                        boolean isConnected = a2dpHelper.isDeviceConnected(device.getAddress());
                        if (isConnected) {
                            updateStatus("设备已连接: " + device.getName());
                            Log.d("result", device.getName() + "已连接");
                            return;
                        }
                        if (System.currentTimeMillis() - startTime > 5000 && !repair){
                            BluetoothDevice near_device = discoveredDevicesMap.get(device.getAddress());
                            if (Objects.equals(device, near_device)){
                                unpairDevice(device);
                                pairDevice(device);
                                repair = true;
                            };
                        }
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                updateStatus("设备连接失败: " + devicename);
                Log.d("result", devicename + "连接失败");
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
                    // 尝试获取设备的连接状态

                    long startTime = System.currentTimeMillis();
                    long duration = 20000; // 20秒的持续时间
                    while (System.currentTimeMillis() - startTime < duration){
                        if (isDeviceConnected(device)) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            updateStatus("设备已断链: " + device.getName());
                            Log.d("result", device.getName() + "已断链");
                            return;
                        }
                    }
                    updateStatus("设备断链失败: " + device.getName());
                    Log.d("result", device.getName() + "断链失败");
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
                    Method method = device.getClass().getMethod("removeBond");
                    method.invoke(device);
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
        super.onDestroy();
        stopAccessibilityService();
        unregisterReceiver(receiver);
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            stopDiscovery();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        startAccessibilityService();
    }
    private void stopAccessibilityService() {
        Intent intent = new Intent(this, MyAccessibilityService.class);
        stopService(intent);
    }

    private void startAccessibilityService() {
        if (!isAccessibilityServiceRunning(MyAccessibilityService.class)) {
            Intent intent = new Intent(this, MyAccessibilityService.class);
            startService(intent);
        }
    }

    private boolean isAccessibilityServiceRunning(Class<?> serviceClass) {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

}
