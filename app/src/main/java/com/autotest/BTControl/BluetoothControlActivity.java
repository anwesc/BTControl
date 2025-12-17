package com.autotest.BTControl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.autotest.BTControl.adapters.BluetoothDeviceAdapter;
import com.autotest.BTControl.utils.BluetoothOperationUtils;
import com.autotest.BTControl.utils.BluetoothDiscoveryUtils;
import com.autotest.BTControl.utils.ToastUtils;
import com.autotest.BTControl.automation.BluetoothAutomationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 蓝牙控制活动类
 * 主要功能：
 * 1. 蓝牙设备的扫描、配对、连接和断开
 * 2. 支持自动化执行蓝牙操作
 * 3. 支持通过Intent传递参数进行自动化测试
 */
public class BluetoothControlActivity extends AppCompatActivity {
    private static final String TAG = "BTControlActivity";
    
    // 请求码
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 100;
//    private static final int REQUEST_BLUETOOTH_CONNECT_PERMISSIONS = 101; // unused - 保留注释
//    private static final int REQUEST_BLUETOOTH_SCAN_PERMISSIONS = 102; // unused - 保留注释
//    private static final int REQUEST_BLUETOOTH_ADMIN_PERMISSIONS = 103; // unused - 保留注释
//    private static final int REQUEST_LOCATION_PERMISSIONS = 104; // unused - 保留注释
//    private static final int REQUEST_SYSTEM_ALERT_WINDOW_PERMISSION = 105; // replaced by Activity Result API

    // UI组件
    private Button actionButton; // 布局中唯一的按钮，用于扫描/停止扫描
    private TextView statusTextView;
    private ImageView imageAccessibilitySettings; // 无障碍设置按钮
    private ProgressBar progressBar; // 扫描进度指示器

    private RecyclerView SavedDeviceRecyclerView;
    private RecyclerView NRDeviceRecyclerView;
    private RecyclerView NFDeviceRecyclerView;
    
    // 搜索相关UI组件
    private EditText searchEditText;
    private ImageButton clearSearchButton;

    // Activity Result API launcher for overlay permission
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;

    // 蓝牙相关
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothManager bluetoothManager;

    
    // 工具类实例
    private BluetoothOperationUtils operationUtils;
    private BluetoothAutomationManager automationManager;

    private BluetoothDiscoveryUtils discoveryUtils;
    
    // 设备列表和适配器
    private final List<BluetoothDeviceAdapter.BluetoothDeviceItem> SavedDeviceList = new ArrayList<>();
    private final List<BluetoothDeviceAdapter.BluetoothDeviceItem> NRDeviceList = new ArrayList<>();
    private final List<BluetoothDeviceAdapter.BluetoothDeviceItem> NFDeviceList = new ArrayList<>();
    
    // 过滤后的设备列表
    private final List<BluetoothDeviceAdapter.BluetoothDeviceItem> filteredSavedDeviceList = new ArrayList<>();
    private final List<BluetoothDeviceAdapter.BluetoothDeviceItem> filteredNRDeviceList = new ArrayList<>();
    private final List<BluetoothDeviceAdapter.BluetoothDeviceItem> filteredNFDeviceList = new ArrayList<>();
    
    // 搜索状态
    private boolean isSearching = false;
    
    // 设备映射 - 只保留与三个列表对应的映射
    private final Map<String, BluetoothDevice> SavedDevicesMap = new HashMap<>();
    private final Map<String, BluetoothDevice> NRDevicesMap = new HashMap<>();
    private final Map<String, BluetoothDevice> NFDevicesMap = new HashMap<>();

    // 适配器
    private BluetoothDeviceAdapter SavedDeviceListAdapter;
    private BluetoothDeviceAdapter NRDeviceListAdapter;
    private BluetoothDeviceAdapter NFDeviceListAdapter;

    private final AtomicBoolean autoAction = new AtomicBoolean(false);
    private final AtomicBoolean autoScan = new AtomicBoolean(false);
    private final AtomicBoolean isScanning = new AtomicBoolean(false); // 扫描状态

    // 线程管理
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;

    // ==================== 生命周期方法 ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_control);
        
        initializeComponents();

        // 检查蓝牙权限
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
        }
        
        // 检查并请求悬浮窗权限
        if (!hasSystemAlertWindowPermission()) {
            requestSystemAlertWindowPermission();
        }

        setupUI();

        if (hasBluetoothPermissions()){
            initializeBluetooth();
            setupEventListeners();
            startDeviceMonitoring();
            startBluetoothDiscovery();
            processIntentParameters();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 使用工具类注册蓝牙广播接收器
        if (discoveryUtils != null) {
            discoveryUtils.registerBluetoothReceiver();
        }

    }

    @Override
    protected void onPause() {
        super.onPause();
        
        // 使用工具类注销蓝牙广播接收器
        if (discoveryUtils != null) {
            discoveryUtils.unregisterBluetoothReceiver();
        }
        
        // 使用工具类停止设备扫描
        if (discoveryUtils != null) {
            discoveryUtils.stopDiscovery();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                updateStatus("蓝牙权限已授予");
                initializeBluetooth();
                setupEventListeners();
                startDeviceMonitoring();
                startBluetoothDiscovery();
            } else {
                updateStatus("蓝牙权限未授予，部分功能可能无法使用");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        Log.d(TAG, "正在关闭应用");
        
        // 停止自动化操作
        stopAutomation();
        
        // 关闭线程池
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
        
        if (scheduledExecutorService != null && !scheduledExecutorService.isShutdown()) {
            scheduledExecutorService.shutdownNow();
        }
        
        // 释放工具类资源
        if (automationManager != null) {
            automationManager.stopAutoAction();
        }

        if (discoveryUtils != null) {
            discoveryUtils.cleanup();
        }
        
        // 取消所有Toast消息
        ToastUtils.cancelCurrentToast();
    }

    // ==================== 初始化方法 ====================

    /**
     * 初始化组件
     */
    private void initializeComponents() {
        executorService = Executors.newCachedThreadPool();
        scheduledExecutorService = Executors.newScheduledThreadPool(2);
        
        // 初始化工具类
        operationUtils = new BluetoothOperationUtils(this, executorService, new BluetoothOperationUtils.UIUpdateCallback() {
            @Override
            public void updateStatus(String status) {
                BluetoothControlActivity.this.updateStatus(status);
            }
            
            @Override
            public void runOnUiThread(Runnable action) {
                BluetoothControlActivity.this.runOnUiThread(action);
            }
        });
        automationManager = new BluetoothAutomationManager(
                operationUtils,
                executorService,
                scheduledExecutorService,
                SavedDevicesMap,
                NRDevicesMap,
                NFDevicesMap
        );
        discoveryUtils = new BluetoothDiscoveryUtils(this);

        // 注册 Activity Result 回调，用于请求悬浮窗权限（替代已弃用的 startActivityForResult）
        overlayPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (Settings.canDrawOverlays(this)) {
                            updateStatus("悬浮窗权限已授予");
                        } else {
                            updateStatus("悬浮窗权限未授予，部分功能可能无法使用");
                        }
                    }
                }
        );

    }

    /**
     * 初始化蓝牙
     */
    private void initializeBluetooth() {
        Log.d(TAG, "开始初始化蓝牙");
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            Log.d(TAG, "BluetoothManager获取成功");
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null) {
                Log.d(TAG, "BluetoothAdapter获取成功，是否启用: " + bluetoothAdapter.isEnabled());
            } else {
                Log.e(TAG, "BluetoothAdapter获取失败，设备不支持蓝牙");
            }
        } else {
            Log.e(TAG, "BluetoothManager获取失败");
        }
    }

    /**
     * 设置UI
     */
    private void setupUI() {
        // 布局中只有一个Button，ID为actionButton
        actionButton = findViewById(R.id.actionButton);
        statusTextView = findViewById(R.id.statusTextView);
        
        // 初始化无障碍设置按钮
        imageAccessibilitySettings = findViewById(R.id.image_accessibility_settings);
        
        // 初始化进度条
        progressBar = findViewById(R.id.progressBar);
        
        // 初始化搜索相关UI组件
        searchEditText = findViewById(R.id.searchEditText);
        clearSearchButton = findViewById(R.id.clearSearchButton);
        
        // 初始化RecyclerView
        SavedDeviceRecyclerView = findViewById(R.id.SavedDeviceRecyclerView);
        NRDeviceRecyclerView = findViewById(R.id.NRDeviceRecyclerView);
        NFDeviceRecyclerView = findViewById(R.id.NFDeviceRecyclerView);
        
        // 设置RecyclerView
        setupRecyclerViews();
        
        // 设置搜索功能
        setupSearchFunctionality();
    }

    /**
     * 设置事件监听器
     */
    private void setupEventListeners() {
        // 使用布局中唯一的actionButton，设置为扫描功能
        if (actionButton != null) {
            actionButton.setOnClickListener(v -> {
                if (isScanning.get()) {
                    stopBluetoothDiscovery();
                } else {
                    // 点击"开始扫描"按钮仅扫描一次
                    startBluetoothDiscovery(false);
                }
            });
        }
        
        // 设置无障碍设置按钮点击事件
        if (imageAccessibilitySettings != null) {
            imageAccessibilitySettings.setOnClickListener(v -> {
                // 检查无障碍服务是否已启用
                if (!isAccessibilityServiceEnabled()) {
                    // 跳转到无障碍设置页面
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                    ToastUtils.showLong(this, "请开启BTControl无障碍功能");
                } else {
                    ToastUtils.showShort(this, "BTControl无障碍功能已开启");
                }
            });
        }
    }

    /**
     * 设置RecyclerView
     */
    private void setupRecyclerViews() {
        
        // 已连接设备列表
        SavedDeviceListAdapter = createBluetoothDeviceAdapter(SavedDeviceList,
            (position, deviceItem) -> {
                BluetoothDevice device = deviceItem.getBluetoothDevice();
                if (device != null) {
                    if (deviceItem.isConnected()) {
                        disconnectDevice(device);
                    } else {
                        connectToDevice(device);
                    }
                }
            },
            (position, deviceItem) -> {
                BluetoothDevice device = deviceItem.getBluetoothDevice();
                if (device != null) {
                    unpairDevice(device);
                }
            });
        
        // NR设备列表（周围设备）
        NRDeviceListAdapter = createBluetoothDeviceAdapter(NRDeviceList,
            (position, deviceItem) -> {
                BluetoothDevice device = deviceItem.getBluetoothDevice();
                if (device != null) {
                    pairDevice(device);
                }
            },
            null); // 长按不处理
        
        // NF设备列表（非常用设备）
        NFDeviceListAdapter = createBluetoothDeviceAdapter(NFDeviceList,
            (position, deviceItem) -> {
                BluetoothDevice device = deviceItem.getBluetoothDevice();
                if (device != null) {
                    if (deviceItem.isPaired()) {
                        if (deviceItem.isConnected()) {
                            disconnectDevice(device);
                        } else {
                            connectToDevice(device);
                        }
                    } else {
                        pairDevice(device);
                    }
                }
            },
            (position, deviceItem) -> {
                BluetoothDevice device = deviceItem.getBluetoothDevice();
                if (device != null) {
                    unpairDevice(device);
                }
            });
        
        // 设置RecyclerView
        setupRecyclerView(SavedDeviceRecyclerView, SavedDeviceListAdapter);
        setupRecyclerView(NRDeviceRecyclerView, NRDeviceListAdapter);
        setupRecyclerView(NFDeviceRecyclerView, NFDeviceListAdapter);
        
        // 初始调整NRDeviceRecyclerView的高度
        if (NRDeviceRecyclerView != null) {
            adjustNRDeviceRecyclerViewHeight();
        }
    }
    
    /**
     * 创建蓝牙设备适配器的通用方法
     */
    private BluetoothDeviceAdapter createBluetoothDeviceAdapter(
            List<BluetoothDeviceAdapter.BluetoothDeviceItem> deviceList,
            BluetoothDeviceAdapter.DeviceClickListener clickListener,
            BluetoothDeviceAdapter.DeviceLongClickListener longClickListener) {
        
        // 创建默认的监听器
        BluetoothDeviceAdapter.DeviceClickListener defaultClickListener = clickListener != null ? 
            clickListener :
                (position, deviceItem) -> {
                    // 默认不处理点击事件
                };
            
        BluetoothDeviceAdapter.DeviceLongClickListener defaultLongClickListener = longClickListener != null ? 
            longClickListener :
                (position, deviceItem) -> {
                    // 默认不处理长按事件
                };
        
        return new BluetoothDeviceAdapter(deviceList, defaultClickListener, defaultLongClickListener);
    }
    
    /**
     * 设置RecyclerView的通用方法
     */
    private void setupRecyclerView(RecyclerView recyclerView, BluetoothDeviceAdapter adapter) {
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(adapter);
        }
    }
    
    /**
     * 设置搜索功能
     */
    private void setupSearchFunctionality() {
        if (searchEditText == null || clearSearchButton == null) {
            return;
        }
        
        // 设置搜索文本监听器
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // 不需要处理
            }
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 当文本变化时执行扫描
                String query = s.toString().trim();
                isSearching = !query.isEmpty();
                
                // 显示或隐藏清除按钮
                clearSearchButton.setVisibility(isSearching ? View.VISIBLE : View.GONE);
                
                // 执行扫描
                filterDevices(query);
            }
            
            @Override
            public void afterTextChanged(Editable s) {
                // 不需要处理
            }
        });
        
        // 设置清除按钮点击事件
        clearSearchButton.setOnClickListener(v -> {
            searchEditText.setText("");
            clearSearchButton.setVisibility(View.GONE);
            isSearching = false;
            filterDevices("");
        });
        
        // 设置搜索键盘的扫描按钮
        searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // 隐藏键盘
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(searchEditText.getWindowToken(), 0);
                return true;
            }
            return false;
        });
    }

    // ==================== 设备状态监控 ====================

    /**
     * 启动设备监控
     */
    private void startDeviceMonitoring() {
        Log.d(TAG, "首次updateSavedDeviceList完成，开始定期监控");
        updateSavedDeviceList();
        // 启动定期检查设备连接状态的任务
        updateSavedDeviceListWithCallback(() -> {
            scheduledExecutorService.scheduleWithFixedDelay(this::updateSavedDeviceList,
                    0, 1, TimeUnit.SECONDS); // 从1秒后开始，每1秒执行一次
        });
    }

    // ==================== 权限相关方法 ====================

    /**
     * 检查悬浮窗权限
     */
    @SuppressLint("ObsoleteSdkInt")
    private boolean hasSystemAlertWindowPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || 
               Settings.canDrawOverlays(this);
    }

    /**
     * 请求悬浮窗权限
     */
    @SuppressLint("ObsoleteSdkInt")
    private void requestSystemAlertWindowPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            // 使用 Activity Result API 发起请求
            overlayPermissionLauncher.launch(intent);
        }
    }

    /**
     * 检查蓝牙权限
     */
    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * 请求蓝牙权限
     */
    private void requestBluetoothPermissions() {
        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            permissions.add(Manifest.permission.BLUETOOTH);
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), REQUEST_BLUETOOTH_PERMISSIONS);
    }

    /**
     * 检查无障碍服务是否已启用
     */
    private boolean isAccessibilityServiceEnabled() {
        String serviceId = getPackageName() + "/.automation.BTControlAccessibilityService";
        try {
            int accessibilityEnabled = Settings.Secure.getInt(
                getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED
            );
            
            if (accessibilityEnabled == 1) {
                // 检查特定服务是否启用
                String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                );
                return enabledServices != null && enabledServices.contains(serviceId);
            }
        } catch (Settings.SettingNotFoundException e) {
            Log.e(TAG, "无法检查无障碍服务状态", e);
        }
        return false;
    }

    // ==================== 自动化操作方法 ====================

    /**
     * 处理Intent参数
     */
    @SuppressLint("MissingPermission")
    private void processIntentParameters() {
        Intent intent = getIntent();
        Log.d(TAG, "自动化: 开始处理Intent参数");

        if (intent != null) {
            // 打印所有Extras
            Bundle extras = intent.getExtras();
            if (extras != null) {
                Log.d(TAG, "自动化: Intent包含以下Extras:");
                for (String key : extras.keySet()) {
                    Object value = extras.getString(key);
                    Log.d(TAG, "自动化: Key=" + key + ", Value=" + value);
                }
            } else {
                Log.d(TAG, "自动化: Intent没有Extras");
            }

            // 自动化执行相关
            String targetAction = intent.getStringExtra("auto_action");
            Log.d(TAG, "自动化: targetAction=" + targetAction);

            // 支持两种参数名：device_address 和 bluetooth_address
            String targetDeviceAddress = intent.getStringExtra("device_address");
            if (targetDeviceAddress == null) {
                targetDeviceAddress = intent.getStringExtra("bluetooth_address");
            }

            if (targetAction != null && targetDeviceAddress != null) {
                String successMsg = "正在执行自动化操作请求: " + targetAction + ", 设备地址: " + targetDeviceAddress;
                showSuccessMessage(successMsg);
                autoAction.set(true);

                // 设置状态监听器
                automationManager.setStatusListener(new BluetoothAutomationManager.AutomationStatusListener() {

                    @Override
                    public void onOperationComplete(String action, boolean success, String message) {
                        runOnUiThread(() -> {
                            if (success) {
                                updateStatus("自动化操作完成: " + action);
                            } else {
                                updateStatus("自动化操作失败: " + action + " - " + message);
                            }
                            autoAction.set(false);
                        });
                    }

                    @Override
                    public void onDeviceDiscovered(BluetoothDevice device) {
                        runOnUiThread(() -> updateStatus("自动化发现设备: " + device.getName()));
                    }
                });

                // 执行自动化操作
                automationManager.executeAutoAction(targetAction, targetDeviceAddress);
            } else {
                Log.d(TAG, "自动化: 参数不完整 - targetAction=" + targetAction + ", targetDeviceAddress=" + targetDeviceAddress);
            }
        } else {
            Log.d(TAG, "自动化: Intent为null");
        }
    }

    /**
     * 停止自动化操作
     */
    private void stopAutomation() {
        autoAction.set(false);
        autoScan.set(false);
        
        // 使用BluetoothAutomationManager停止自动化操作
        if (automationManager != null) {
            automationManager.stopAutoAction();
        }
        
        // 使用工具类停止设备发现
        if (discoveryUtils != null) {
            discoveryUtils.stopDiscovery();
        }
    }

    // ==================== 蓝牙操作方法 ====================

    /**
     * 连接设备
     */
    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "connectToDevice: 设备为空，无法连接");
            showErrorMessage("设备为空，无法连接");
            return;
        }
        
        // 添加权限检查的详细日志
        boolean hasPermissions = hasBluetoothPermissions();
        Log.d(TAG, "connectToDevice: 权限检查结果 - " + hasPermissions);
        
        if (!hasPermissions) {
            Log.e(TAG, "connectToDevice: 没有蓝牙权限，无法连接设备");
            showErrorMessage("没有蓝牙权限，无法连接设备");
            return;
        }
        
        String deviceName = device.getName() != null ? device.getName() : "未知设备";
        Log.d(TAG, "connectToDevice: 开始连接设备 - " + deviceName + ", 地址: " + device.getAddress());
        updateStatus("正在连接设备: " + deviceName);
        
        // 使用工具类连接设备
        operationUtils.connectToDevice(device, new BluetoothOperationUtils.OperationCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    String successMsg = "设备连接成功: " + deviceName;
                    showSuccessMessage(successMsg);
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    String errorMsg = "设备连接失败: " + deviceName + " - " + error;
                    showErrorMessage(errorMsg);
                });
            }
        });
    }

    /**
     * 断开设备连接
     */
    @SuppressLint("MissingPermission")
    private void disconnectDevice(BluetoothDevice device) {
        if (device == null) {
            showErrorMessage("设备为空，无法断开连接");
            return;
        }
        
        if (!hasBluetoothPermissions()) {
            showErrorMessage("没有蓝牙权限，无法断开设备连接");
            return;
        }
        
        String deviceName = device.getName() != null ? device.getName() : "未知设备";
        updateStatus("正在断开设备连接: " + deviceName);
        
        // 使用工具类断开设备连接
        operationUtils.disconnectDevice(device, new BluetoothOperationUtils.OperationCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    String successMsg = "设备已断开连接: " + deviceName;
                    showSuccessMessage(successMsg);
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    String errorMsg = "设备断开连接失败: " + deviceName + " - " + error;
                    showErrorMessage(errorMsg);
                });
            }
        });
    }

    /**
     * 配对设备
     */
    @SuppressLint("MissingPermission")
    private void pairDevice(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "pairDevice: 设备为空，无法配对");
            showErrorMessage("设备为空，无法配对");
            return;
        }
        
        // 添加权限检查的详细日志
        boolean hasPermissions = hasBluetoothPermissions();
        Log.d(TAG, "pairDevice: 权限检查结果 - " + hasPermissions);
        
        if (!hasPermissions) {
            Log.e(TAG, "pairDevice: 没有蓝牙权限，无法配对设备");
            showErrorMessage("没有蓝牙权限，无法配对设备");
            return;
        }
        
        String deviceName = device.getName() != null ? device.getName() : "未知设备";
        Log.d(TAG, "pairDevice: 开始配对设备 - " + deviceName + ", 地址: " + device.getAddress());
        updateStatus("正在配对设备: " + deviceName);
        
        // 停止扫描
        stopBluetoothDiscovery();
        
        // 使用工具类配对设备
        operationUtils.pairDevice(device, new BluetoothOperationUtils.OperationCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    if (SavedDevicesMap.containsKey(device.getAddress())){
                        String successMsg = "设备配对成功: " + deviceName;
                        showSuccessMessage(successMsg);
                        // 从NR或NF设备列表中移除已配对的设备
                        removeDeviceFromDiscoveryLists(device.getAddress());
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    String errorMsg = "设备配对失败: " + deviceName + " - " + error;
                    showErrorMessage(errorMsg);
                });
            }
        });
    }

    /**
     * 取消配对设备
     */
    @SuppressLint("MissingPermission")
    private void unpairDevice(BluetoothDevice device) {
        if (device == null) {
            showErrorMessage("设备为空，无法取消配对");
            return;
        }
        
        if (!hasBluetoothPermissions()) {
            showErrorMessage("没有蓝牙权限，无法取消配对");
            return;
        }
        
        String deviceName = device.getName() != null ? device.getName() : "未知设备";
        updateStatus("正在取消配对设备: " + deviceName);
        
        // 使用工具类取消配对设备
        operationUtils.unpairDevice(device, new BluetoothOperationUtils.OperationCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    String successMsg = "设备已取消配对: " + deviceName;
                    showSuccessMessage(successMsg);
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    String errorMsg = "取消配对失败: " + deviceName + " - " + error;
                    showErrorMessage(errorMsg);
                });
            }
        });
    }

    // ==================== 设备扫描相关方法 ====================

    /**
     * 开始扫描蓝牙设备（连续扫描模式）
     */
    @SuppressLint({"NotifyDataSetChanged", "MissingPermission"})
    private void startBluetoothDiscovery() {
        startBluetoothDiscovery(true);
    }
    
    /**
     * 开始扫描蓝牙设备
     * @param continuousScan 是否使用连续扫描模式（true=连续扫描60秒，false=单次扫描12秒）
     */
    @SuppressLint({"NotifyDataSetChanged", "MissingPermission"})
    private void startBluetoothDiscovery(boolean continuousScan) {
        Log.d(TAG, "开始扫描蓝牙设备");
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "没有蓝牙权限，无法扫描设备");
            showErrorMessage("没有蓝牙权限，无法扫描设备");
            return;
        }
        
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "蓝牙未开启，无法扫描设备");
            showWarningMessage("蓝牙未开启，无法扫描设备");
            return;
        }
        
        if (isScanning.get()) {
            Log.w(TAG, "正在扫描中...");
            showWarningMessage("正在扫描中...");
            return;
        }
        
        Log.d(TAG, "开始扫描蓝牙设备...");
        updateStatus("开始扫描蓝牙设备...");
        
        // 清空周围设备和非常用设备列表
        NRDevicesMap.clear();
        NRDeviceList.clear();
        NFDevicesMap.clear();
        NFDeviceList.clear();
        adjustNRDeviceRecyclerViewHeight();
        
        // 清空过滤列表
        filteredNRDeviceList.clear();
        filteredNFDeviceList.clear();

        // 更新适配器
        NRDeviceListAdapter.notifyDataSetChanged();
        NFDeviceListAdapter.notifyDataSetChanged();

        actionButton.setText("停止扫描");
        progressBar.setVisibility(View.VISIBLE);
        isScanning.set(true);

        Log.d(TAG, "调用discoveryUtils.startDiscovery()开始扫描蓝牙设备，扫描模式: " + (continuousScan ? "连续扫描" : "单次扫描"));
        // 使用工具类开始扫描蓝牙设备
        // 开始设备发现
        discoveryUtils.startDiscovery(continuousScan, new BluetoothDiscoveryUtils.DiscoveryCallback() {
            @Override
            public void onDiscoveryStarted() {
                runOnUiThread(() -> {
                    Log.d(TAG, "蓝牙设备发现已启动 (" + (continuousScan ? "连续扫描" : "单次扫描") + ")");
                    isScanning.set(true);
                    actionButton.setText("停止扫描");
                    progressBar.setVisibility(View.VISIBLE);
                    addStatusMessage("开始扫描蓝牙设备 (" + (continuousScan ? "连续扫描" : "单次扫描") + ")");
                });
            }
            
            @Override
            public void onDeviceFound(BluetoothDevice device, int rssi) {
                Log.d(TAG, "onDeviceFound: 扫描设备回调被触发，RSSI: " + rssi);
                if (device != null) {
                    String deviceAddress = device.getAddress();
                    @SuppressLint("MissingPermission") String deviceName = device.getName();

                    // 只检查设备是否已经在已保存列表中，避免重复处理
                    if (SavedDevicesMap.containsKey(deviceAddress)) {
                        Log.d(TAG, "onDeviceFound: 设备已在已保存列表中，跳过 - " + deviceName);
                        return;
                    }

                    if (NRDevicesMap.containsKey(deviceAddress) || NFDevicesMap.containsKey(deviceAddress)){
                        Log.d(TAG, "onDeviceFound: 设备已在发现列表中，跳过 - " + deviceName);
                        return;
                    }
                    Log.d(TAG, "onDeviceFound: 发现设备 - " + deviceName + ", 地址: " + deviceAddress + ", RSSI: " + rssi);

                    // 根据设备名称分类并添加到相应的列表
                    if (deviceName != null && !deviceName.isEmpty()) {
                        Log.d(TAG, "onDeviceFound: 添加到NR设备列表 - " + deviceName);
                        // 有名称的设备，添加到NR设备列表
                        synchronized (NRDevicesMap) {
                            NRDevicesMap.put(deviceAddress, device);
                            BluetoothDeviceAdapter.BluetoothDeviceItem deviceItem =
                                    new BluetoothDeviceAdapter.BluetoothDeviceItem(
                                            device,
                                            false, // 新发现的设备未配对
                                            false // 扫描到的设备默认为未连接
                                    );
                            NRDeviceList.add(deviceItem);
                        }
                    } else {
                        Log.d(TAG, "onDeviceFound: 添加到NF设备列表 - " + deviceAddress);
                        // 无名称的设备，添加到NF设备列表，
                        synchronized (NFDevicesMap) {
                            NFDevicesMap.put(deviceAddress, device);
                            BluetoothDeviceAdapter.BluetoothDeviceItem deviceItem =
                                    new BluetoothDeviceAdapter.BluetoothDeviceItem(
                                            device,
                                            false, // 新发现的设备未配对
                                            false // 扫描到的设备默认为未连接
                                    );
                            NFDeviceList.add(deviceItem);
                        }
                    }
                } else {
                    Log.w(TAG, "onDeviceFound: 设备为null");
                }
                // 在UI线程中更新设备列表
                runOnUiThread(() -> {
                    // 通知NR设备列表适配器更新
                    NRDeviceListAdapter.notifyDataSetChanged();
                    adjustNRDeviceRecyclerViewHeight();
                    // 通知NF设备列表适配器更新
                    NFDeviceListAdapter.notifyDataSetChanged();
                });
            }
            
            @Override
            public void onDiscoveryFinished() {
                runOnUiThread(() -> {
                    showSuccessMessage("设备扫描完成");
                    actionButton.setText("开始扫描");
                    progressBar.setVisibility(View.GONE);
                    isScanning.set(false);
                });
            }
            
            @Override
            public void onDiscoveryError(String error) {
                runOnUiThread(() -> {
                    updateStatus("设备扫描错误: " + error);
                    actionButton.setText("开始扫描");
                    progressBar.setVisibility(View.GONE);
                    isScanning.set(false);
                });
            }
            
            @Override
            public void onBluetoothStateChanged(int state) {
                runOnUiThread(() -> {
                    switch (state) {
                        case BluetoothAdapter.STATE_ON:
                            updateStatus("蓝牙已开启");
                            if (hasBluetoothPermissions()) {
                                startBluetoothDiscovery();
                            }
                            break;
                        case BluetoothAdapter.STATE_OFF:
                            updateStatus("蓝牙已关闭");
                            break;
                        case BluetoothAdapter.STATE_TURNING_ON:
                            updateStatus("蓝牙正在开启...");
                            break;
                        case BluetoothAdapter.STATE_TURNING_OFF:
                            updateStatus("蓝牙正在关闭...");
                            break;
                    }
                });
            }
            
            @Override
            public void onBondStateChanged(BluetoothDevice device, int bondState) {
                runOnUiThread(() -> {
                    String deviceName = device.getName() != null ? device.getName() : "未知设备";
                    
                    switch (bondState) {
                        case BluetoothDevice.BOND_BONDED:
                            updateStatus("设备已配对: " + deviceName);
                            updateSavedDeviceList();
                            // 从NR或NF设备列表中移除已配对的设备
                            removeDeviceFromDiscoveryLists(device.getAddress());
                            break;
                        case BluetoothDevice.BOND_BONDING:
                            updateStatus("正在配对设备: " + deviceName);
                            break;
                        case BluetoothDevice.BOND_NONE:
//                            updateStatus("未配对: " + deviceName);
                            updateSavedDeviceList();
                            break;
                    }
                });
            }
        });
    }

    /**
     * 停止扫描蓝牙设备
     */
    private void stopBluetoothDiscovery() {
        if (!isScanning.get()) {
            return;
        }
        
        updateStatus("停止扫描蓝牙设备...");

        actionButton.setText("开始扫描");
        progressBar.setVisibility(View.GONE);

        // 使用工具类停止扫描蓝牙设备
        discoveryUtils.stopDiscovery();
        isScanning.set(false);
    }

    // ==================== 设备列表管理方法 ====================

    /**
     * 从NR或NF设备列表中移除已配对的设备
     */
    @SuppressLint("NotifyDataSetChanged")
    private void removeDeviceFromDiscoveryLists(String deviceAddress) {
        // 从NR设备列表中移除
        boolean removedFromNR = false;
        synchronized (NRDevicesMap) {
            if (NRDevicesMap.containsKey(deviceAddress)) {
                NRDevicesMap.remove(deviceAddress);
                // 从NRDeviceList中移除对应项
                for (int i = 0; i < NRDeviceList.size(); i++) {
                    if (NRDeviceList.get(i).getDeviceAddress().equals(deviceAddress)) {
                        NRDeviceList.remove(i);
                        removedFromNR = true;
                        break;
                    }
                }
            }
        }
        
        // 从NF设备列表中移除
        boolean removedFromNF = false;
        synchronized (NFDevicesMap) {
            if (NFDevicesMap.containsKey(deviceAddress)) {
                NFDevicesMap.remove(deviceAddress);
                // 从NFDeviceList中移除对应项
                for (int i = 0; i < NFDeviceList.size(); i++) {
                    if (NFDeviceList.get(i).getDeviceAddress().equals(deviceAddress)) {
                        NFDeviceList.remove(i);
                        removedFromNF = true;
                        break;
                    }
                }
            }
        }
        
        // 更新UI
        boolean finalRemovedFromNR = removedFromNR;
        boolean finalRemovedFromNF = removedFromNF;
        runOnUiThread(() -> {
            if (finalRemovedFromNR) {
                NRDeviceListAdapter.notifyDataSetChanged();
            }
            if (finalRemovedFromNF) {
                NFDeviceListAdapter.notifyDataSetChanged();
            }
            // 调整NR设备列表高度
            adjustNRDeviceRecyclerViewHeight();
        });
        
        Log.d(TAG, "removeDeviceFromDiscoveryLists: 设备 " + deviceAddress + 
              " 从NR列表移除: " + removedFromNR + ", 从NF列表移除: " + removedFromNF);
    }

    /**
     * 更新已保存设备列表
     */
    @SuppressLint({"NotifyDataSetChanged", "MissingPermission"})
    private void updateSavedDeviceList() {
        Log.d(TAG, "updateSavedDeviceList: 更新已保存设备列表");
        if (bluetoothManager == null) {
            return;
        }
    
        if (!hasBluetoothPermissions()) {
            return;
        }
        // 将耗时操作提交到 executorService 执行，避免裸 new Thread
        executorService.submit(() -> {
            try {
                // 创建临时列表和映射
                List<BluetoothDeviceAdapter.BluetoothDeviceItem> tempSavedDeviceList = new ArrayList<>();
                Map<String, BluetoothDevice> tempSavedDevicesMap = new HashMap<>();
                // 获取已配对设备列表
                Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
                if (bondedDevices != null) {
                    for (BluetoothDevice device : bondedDevices) {
                        // 在后台线程中获取设备状态信息
                        DeviceStatusInfo statusInfo = getSavedDeviceProfile(device);
                        BluetoothDeviceAdapter.BluetoothDeviceItem deviceItem =
                                new BluetoothDeviceAdapter.BluetoothDeviceItem(
                                        device,
                                        true, // 已配对
                                        statusInfo.isConnected, // 连接状态
                                        statusInfo.statusText, // 状态文本
                                        statusInfo.profilesText // 配置文件信息
                                );
                        tempSavedDevicesMap.put(device.getAddress(), device);
                        tempSavedDeviceList.add(deviceItem);
                    }
                }

                // 在UI线程更新列表
                runOnUiThread(() -> {
                    SavedDeviceList.clear();
                    SavedDevicesMap.clear();
                    SavedDeviceList.addAll(tempSavedDeviceList);
                    SavedDevicesMap.putAll(tempSavedDevicesMap);
                    SavedDeviceListAdapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                Log.e(TAG, "updateSavedDeviceList: 后台任务异常", e);
            }
        });
    }
    
    /**
     * 更新已保存设备列表（带回调）
     */
    @SuppressLint({"NotifyDataSetChanged", "MissingPermission"})
    private void updateSavedDeviceListWithCallback(Runnable onComplete) {
        Log.d(TAG, "updateSavedDeviceListWithCallback: 更新已保存设备列表");
        if (bluetoothManager == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        if (!hasBluetoothPermissions()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        // 将耗时操作提交到 executorService 执行，避免裸 new Thread
        executorService.submit(() -> {
            try {
                // 创建临时列表和映射
                List<BluetoothDeviceAdapter.BluetoothDeviceItem> tempSavedDeviceList = new ArrayList<>();
                Map<String, BluetoothDevice> tempSavedDevicesMap = new HashMap<>();
                // 获取已配对设备列表
                Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
                if (bondedDevices != null) {
                    for (BluetoothDevice device : bondedDevices) {
                        // 在后台线程中获取设备状态信息
                        DeviceStatusInfo statusInfo = getSavedDeviceProfile(device);
                        BluetoothDeviceAdapter.BluetoothDeviceItem deviceItem =
                                new BluetoothDeviceAdapter.BluetoothDeviceItem(
                                        device,
                                        true, // 已配对
                                        statusInfo.isConnected, // 连接状态
                                        statusInfo.statusText, // 状态文本
                                        statusInfo.profilesText // 配置文件信息
                                );
                        tempSavedDevicesMap.put(device.getAddress(), device);
                        tempSavedDeviceList.add(deviceItem);

                    }
                }

                // 在UI线程更新列表并执行回调
                runOnUiThread(() -> {
                    SavedDeviceList.clear();
                    SavedDevicesMap.clear();
                    SavedDeviceList.addAll(tempSavedDeviceList);
                    SavedDevicesMap.putAll(tempSavedDevicesMap);
                    SavedDeviceListAdapter.notifyDataSetChanged();

                    // 执行回调
                    if (onComplete != null) {
                        try {
                            onComplete.run();
                        } catch (Exception e) {
                            Log.e(TAG, "updateSavedDeviceListWithCallback: 回调执行异常", e);
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "updateSavedDeviceListWithCallback: 后台任务异常", e);
                // 即使发生异常，也确保回调在UI线程被调用，避免调用者等待
                runOnUiThread(() -> {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            }
        });
    }

    /**
     * 过滤设备列表
     */
    private void filterDevices(String query) {
        // 如果查询为空，显示所有设备
        if (query.isEmpty()) {
            filteredSavedDeviceList.clear();
            filteredNRDeviceList.clear();
            filteredNFDeviceList.clear();

            updateAdaptersNotFilteredList();
        } else {
            // 清空过滤列表
            filteredSavedDeviceList.clear();
            filteredNRDeviceList.clear();
            filteredNFDeviceList.clear();
            
            // 过滤已连接设备
            for (BluetoothDeviceAdapter.BluetoothDeviceItem device : SavedDeviceList) {
                if (deviceMatchesQuery(device, query)) {
                    filteredSavedDeviceList.add(device);
                }
            }
            
            // 过滤周围设备
            for (BluetoothDeviceAdapter.BluetoothDeviceItem device : NRDeviceList) {
                if (deviceMatchesQuery(device, query)) {
                    filteredNRDeviceList.add(device);
                }
            }
            
            // 过滤非常用设备
            for (BluetoothDeviceAdapter.BluetoothDeviceItem device : NFDeviceList) {
                if (deviceMatchesQuery(device, query)) {
                    filteredNFDeviceList.add(device);
                }
            }
            
            // 对过滤后的已保存设备列表进行排序，已连接的设备排在前面
            filteredSavedDeviceList.sort((device1, device2) -> {
                // 如果两个设备连接状态不同，已连接的排在前面
                if (device1.isConnected() != device2.isConnected()) {
                    return device1.isConnected() ? -1 : 1;
                }
                // 如果连接状态相同，按设备名称排序
                String name1 = device1.getDeviceName() != null ? device1.getDeviceName() : "";
                String name2 = device2.getDeviceName() != null ? device2.getDeviceName() : "";
                return name1.compareToIgnoreCase(name2);
            });
        }
        
        // 更新适配器
        updateAdaptersWithFilteredList();
    }
    
    /**
     * 检查设备是否匹配查询条件
     */
    private boolean deviceMatchesQuery(BluetoothDeviceAdapter.BluetoothDeviceItem device, String query) {
        String deviceName = device.getDeviceName();
        String deviceAddress = device.getDeviceAddress();
        
        // 检查设备名称/地址是否包含查询字符串（不区分大小写）
        if (deviceName != null && deviceName.toLowerCase().contains(query.toLowerCase())) {
            return true;
        } else return deviceAddress != null && deviceAddress.toLowerCase().contains(query.toLowerCase());
    }

    /**
     * 使用过滤后的列表更新适配器
     */
    private void updateAdaptersWithFilteredList() {
        if (SavedDeviceListAdapter != null) {
            SavedDeviceListAdapter.updateDeviceList(filteredSavedDeviceList);
        }
        
        if (NRDeviceListAdapter != null) {
            NRDeviceListAdapter.updateDeviceList(filteredNRDeviceList);
            adjustNRDeviceRecyclerViewHeight();
        }
        
        if (NFDeviceListAdapter != null) {
            NFDeviceListAdapter.updateDeviceList(filteredNFDeviceList);
        }
    }

    /**
     * 取消过滤后的列表更新适配器
     */
    private void updateAdaptersNotFilteredList() {
        if (SavedDeviceListAdapter != null) {
            SavedDeviceListAdapter.updateDeviceList(SavedDeviceList);
        }

        if (NRDeviceListAdapter != null) {
            NRDeviceListAdapter.updateDeviceList(NRDeviceList);
            adjustNRDeviceRecyclerViewHeight();
        }

        if (NFDeviceListAdapter != null) {
            NFDeviceListAdapter.updateDeviceList(NFDeviceList);
        }
    }

    /**
     * 动态调整NRDeviceRecyclerView的高度
     */
    private void adjustNRDeviceRecyclerViewHeight() {
        if (NRDeviceRecyclerView == null || NRDeviceListAdapter == null) {
            return;
        }
        
        // 在UI线程中执行
        runOnUiThread(() -> {
            // 测量RecyclerView的内容高度
            NRDeviceRecyclerView.measure(
                View.MeasureSpec.makeMeasureSpec(NRDeviceRecyclerView.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            
            int contentHeight = NRDeviceRecyclerView.getMeasuredHeight();
            
            // 将360dp转换为像素
            float density = getResources().getDisplayMetrics().density;
            int maxHeightPixels = (int) (360 * density);
            
            // 根据内容高度设置RecyclerView的高度
            ViewGroup.LayoutParams layoutParams = NRDeviceRecyclerView.getLayoutParams();
            if (contentHeight > maxHeightPixels) {
                // 如果内容高度超过360dp，固定高度为360dp
                layoutParams.height = maxHeightPixels;
            } else {
                // 否则使用wrap_content
                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            }
            
            NRDeviceRecyclerView.setLayoutParams(layoutParams);
        });
    }

    // ==================== 辅助工具方法 ====================

    /**
     * 获取设备连接信息（连接状态和已连接的配置文件）
     */
    @SuppressLint({"NewApi","MissingPermission"})
    private DeviceConnectionInfo getDeviceConnectionInfo(BluetoothDevice device) {
        if (device == null || bluetoothAdapter == null) {
            return new DeviceConnectionInfo(false, new StringBuilder());
        }
        
        // 检查常见的蓝牙配置文件
        int[] profiles = {
            BluetoothProfile.A2DP,       // 音频分发传输协议
            BluetoothProfile.HEADSET,    // 耳机配置文件
            BluetoothProfile.LE_AUDIO    // 低功耗音频
        };
        
        StringBuilder profilesBuilder = new StringBuilder("连接配置: ");
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(profiles.length);
        java.util.concurrent.atomic.AtomicBoolean isConnected = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean hasConnectedProfile = new java.util.concurrent.atomic.AtomicBoolean(false);
        
        // 检查每个配置文件的连接状态
        for (int profile : profiles) {
            // 为每个配置文件创建ServiceListener
            StringBuilder finalProfilesBuilder = profilesBuilder;
            BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        // 获取该配置文件的连接设备列表
                        List<BluetoothDevice> profileDevices = proxy.getConnectedDevices();
                        for (BluetoothDevice connectedDevice : profileDevices) {
                            if (connectedDevice.getAddress().equals(device.getAddress())) {
                                // 设备在此配置文件中已连接
                                isConnected.set(true);
                                finalProfilesBuilder.append(getProfileName(profile)).append(",");
                                hasConnectedProfile.set(true);
                                Log.d(TAG, getProfileName(profile) + " connected for device: " + device.getName());
                                break;
                            }
                        }
                    } catch (Exception e) {
                        // 不记录错误日志
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
            if (!bluetoothAdapter.getProfileProxy(this, listener, profile)) {
                // 如果获取代理失败，直接减少计数器
                latch.countDown();
            }
        }
        
        // 等待所有配置文件检查完成，但加上超时，避免无限阻塞
        try {
            // 总超时：profiles数量 * 700ms，最小500ms，最大3000ms
            long timeoutMillis = Math.max(profiles.length * 700L, 500L);
            boolean completed = latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!completed) {
                Log.w(TAG, "getDeviceConnectionInfo: profiles check timed out after " + timeoutMillis + "ms for device " + device.getAddress());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "getDeviceConnectionInfo: 被中断", e);
        }
        
        // 如果没有已连接的配置文件，移除"连接配置: "前缀
        if (!hasConnectedProfile.get()) {
            profilesBuilder = new StringBuilder();
        }
        
        return new DeviceConnectionInfo(isConnected.get(), profilesBuilder);
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

    @SuppressLint("NewApi")
    private DeviceStatusInfo getSavedDeviceProfile(BluetoothDevice device) {
        String statusText;
        String profilesText;

        // 使用getDeviceConnectionInfo方法获取设备连接信息
        DeviceConnectionInfo connectionInfo = getDeviceConnectionInfo(device);
        boolean isConnected = connectionInfo.isConnected;

        if (isConnected) {
            statusText = "已连接";

            // 去除profilesBuilder末尾的逗号
            String profilesStr = connectionInfo.profilesBuilder.toString().trim();
            if (profilesStr.endsWith(",")) {
                profilesStr = profilesStr.substring(0, profilesStr.length() - 1);
            }
            profilesText = "(" + profilesStr + ")";
        } else {
            // 未连接设备不显示状态字符串
            statusText = "";
            // 未连接设备不显示配置文件信息
            profilesText = null;
        }
        
        return new DeviceStatusInfo(isConnected, statusText, profilesText);
    }

    /**
     * 更新状态文本
     */
    private void updateStatus(String status) {
        if (statusTextView != null) {
            statusTextView.setText(status);
        }
        Log.d(TAG, status);
    }
    
    /**
     * 显示错误消息（使用Toast和状态文本）
     */
    private void showErrorMessage(String message) {
        updateStatus(message);
        ToastUtils.showError(this, message);
        Log.e(TAG, "错误: " + message);
    }
    
    /**
     * 显示成功消息（使用Toast和状态文本）
     */
    private void showSuccessMessage(String message) {
        updateStatus(message);
        ToastUtils.showSuccess(this, message);
        Log.i(TAG, "成功: " + message);
    }
    
    /**
     * 显示警告消息（使用Toast和状态文本）
     */
    private void showWarningMessage(String message) {
        updateStatus(message);
        ToastUtils.showWarning(this, message);
        Log.w(TAG, "警告: " + message);
    }
    
    /*
      清空设备列表
     */
    // clearScanDeviceItem 方法当前未在其他位置使用，保留注释以便未来恢复
    // @SuppressLint("NotifyDataSetChanged")
    // private void clearScanDeviceItem() {
    //     NRDevicesMap.clear();
    //     NRDeviceList.clear();
    //     NFDevicesMap.clear();
    //     NFDeviceList.clear();
    //     if (NRDeviceListAdapter != null) {
    //         NRDeviceListAdapter.notifyDataSetChanged();
    //     }
    //     if (NFDeviceListAdapter != null) {
    //         NFDeviceListAdapter.notifyDataSetChanged();
    //     }
    // }

    /**
     * 添加状态消息
     */
    private void addStatusMessage(String message) {
        updateStatus(message);
    }

    // ==================== 内部类定义 ====================

    /**
     * 设备连接信息封装类
     */
    private static class DeviceConnectionInfo {
        public boolean isConnected;
        public StringBuilder profilesBuilder;
        
        public DeviceConnectionInfo(boolean isConnected, StringBuilder profilesBuilder) {
            this.isConnected = isConnected;
            this.profilesBuilder = profilesBuilder;
        }
    }

    /**
     * 设备状态信息封装类
     */
    private static class DeviceStatusInfo {
        public boolean isConnected;
        public String statusText;
        public String profilesText;
        
        public DeviceStatusInfo(boolean isConnected, String statusText, String profilesText) {
            this.isConnected = isConnected;
            this.statusText = statusText;
            this.profilesText = profilesText;
        }
    }
}
