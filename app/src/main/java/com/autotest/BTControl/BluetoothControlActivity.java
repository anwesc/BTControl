package com.autotest.BTControl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.IntentSender;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.autotest.BTControl.adapters.BluetoothDeviceAdapter;
import com.autotest.BTControl.adapters.SectionHeaderAdapter;
import com.autotest.BTControl.utils.BluetoothOperationUtils;
import com.autotest.BTControl.utils.BluetoothDiscoveryUtils;
import com.autotest.BTControl.utils.BtctlReporter;
import com.autotest.BTControl.utils.ScanAdvertCache;
import com.autotest.BTControl.utils.ShizukuBridge;
import com.autotest.BTControl.utils.ToastUtils;
import com.autotest.BTControl.update.UpdateManager;
import com.autotest.BTControl.update.UpdateManifest;
import com.autotest.BTControl.automation.BluetoothAutomationManager;

import java.io.File;

import org.json.JSONObject;

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
    private TextView statusTextView;
    private View btctlBar;                   // 底部回执条：只有 adb/脚本触发过动作才出现
    private TextView btctlResultTextView;    // 回执条文字（点按复制 JSON，长按看环境）
    private TextView versionTextView;        // 底部版本入口：点按看版本/检查更新，长按进更新设置
    /** 更新下载/安装的进度弹窗；同一时刻只允许一个。 */
    private AlertDialog updateDialog;
    private AlertDialog updatePageDialog;
    private ImageView imageAccessibilitySettings; // 无障碍设置按钮

    // 整页只有一个列表：三块设备列表靠 ConcatAdapter 拼在一起，标题行也是 item
    private RecyclerView deviceRecyclerView;
    private SectionHeaderAdapter savedHeaderAdapter;
    private SectionHeaderAdapter nearbyHeaderAdapter;
    private SectionHeaderAdapter nfHeaderAdapter;
    
    // 搜索相关UI组件
    private EditText searchEditText;
    private ImageButton clearSearchButton;

    // Activity Result API launcher for overlay permission
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<IntentSenderRequest> cdmAssociateLauncher;

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

    // 适配器真正渲染的列表：源列表（Saved/NR/NF）只负责数据，渲染时按搜索与收起状态挑一份
    
    // 搜索状态
    private boolean isSearching = false;

    /** 「非常用设备」默认收起：这类设备多半是没名字的 BLE 广播源，列出来只会把页面撑很长。 */
    private boolean nfExpanded = false;
    
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

    // 自动化请求上下文（结果上报用）
    private String btctlReq;
    private String btctlAction;
    private String btctlAddr;
    private boolean btctlFinish;
    private boolean btctlEmitted;
    private int btctlProfileTries;
    private List<BluetoothDevice> btctlScanDevices;
    /** 本次 adb 请求限定的 profile：all / a2dp / hfp / leaudio */
    private String btctlProfile = "all";
    private String btctlVia = "native";
    /** 「开关」动作改完后的目标值；null 表示本次动作不是开关。 */
    private Boolean btctlSwitchEnabled;
    private long btctlStartMs;

    // 线程管理
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;

    /** 主线程 Handler，用于「已保存设备」刷新防抖。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 防抖后的刷新动作：多次状态广播合并成一次重建。 */
    private final Runnable savedDeviceRefreshRunnable = this::updateSavedDeviceList;

    /** 监听连接/配对状态变化的接收器（有它就不用每秒轮询）。 */
    private BroadcastReceiver savedDeviceStateReceiver;

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

        // 版本自检：只有用户手动打开才查，adb 自动化请求一律不弹窗
        autoCheckOnLaunch();

        if (hasBluetoothPermissions()){
            initializeBluetooth();
            setupEventListeners();
            startDeviceMonitoring();
            startBluetoothDiscovery();
            processIntentParameters();
        } else if (getIntent() != null && getIntent().getStringExtra("auto_action") != null) {
            Intent it = getIntent();
            // 版本检查/更新不碰蓝牙，没蓝牙权限也照常执行
            if (isUpdateAction(it.getStringExtra("auto_action"))) {
                processIntentParameters();
                return;
            }
            // 没权限时 processIntentParameters 进不去，这里补一条明确失败，别让调用方干等到超时
            btctlReq = it.getStringExtra("req");
            btctlAction = it.getStringExtra("auto_action");
            btctlAddr = it.getStringExtra("bluetooth_address");
            btctlStartMs = System.currentTimeMillis();
            emitBtctlResult(false, "bluetooth_permission_denied");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTop：复用实例时必须重新走一遍参数处理，否则 adb 第二次请求会被静默忽略
        setIntent(intent);
        processIntentParameters();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 使用工具类注册蓝牙广播接收器
        if (discoveryUtils != null) {
            discoveryUtils.registerBluetoothReceiver();
        }

        // 回到前台时刷新状态条：蓝牙/无障碍/profile 可能在别处被改过
        refreshBtctlBar();
        refreshVersionDot();
        refreshKeyStatus();
        scheduleSavedDeviceRefresh();
        resumed = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;

        // 这里刻意不停扫描、也不注销接收器：AI 用 adb 触发扫描时界面经常被切到后台，
        // 在 onPause 收摊就等于「扫到一半变聋」。收摊统一放到 onDestroy 的 cleanup()。
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
                setKeyStatus("蓝牙权限未授予，部分功能可能无法使用");
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

        unregisterSavedDeviceStateReceiver();
        mainHandler.removeCallbacks(savedDeviceRefreshRunnable);
        
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
                            showEvent("悬浮窗权限未授予，部分功能可能无法使用", false);
                        }
                    }
                }
        );

        // CDM 关联选择器：系统弹出的「选择要关联的设备」列表，选完才真正建立关联
        cdmAssociateLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    boolean associated = hasCdmAssociation(btctlAddr);
                    Log.d(TAG, "CDM 选择器返回 code=" + result.getResultCode() + " associated=" + associated);
                    emitBtctlResult(associated, associated ? "" : "associate_cancelled");
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
        statusTextView = findViewById(R.id.statusTextView);
        setupBtctlBar();

        // 初始化无障碍设置按钮
        imageAccessibilitySettings = findViewById(R.id.image_accessibility_settings);

        // 初始化搜索相关UI组件
        searchEditText = findViewById(R.id.searchEditText);
        clearSearchButton = findViewById(R.id.clearSearchButton);

        // 唯一的设备列表（标题行也在这个列表里）
        deviceRecyclerView = findViewById(R.id.deviceRecyclerView);

        // 设置RecyclerView
        setupRecyclerViews();

        // 设置搜索功能
        setupSearchFunctionality();

        setupVersionEntry();
    }

    /** 底部版本入口：不额外占首屏，点按看版本/检查更新，长按直接进更新设置。 */
    private void setupVersionEntry() {
        versionTextView = findViewById(R.id.versionTextView);
        if (versionTextView == null) {
            return;
        }
        updateVersionEntryText();
        versionTextView.setOnClickListener(v -> showVersionDialog());
        versionTextView.setOnLongClickListener(v -> {
            showUpdateSettings();
            return true;
        });
    }

    private void updateVersionEntryText() {
        if (versionTextView == null) {
            return;
        }
        String name = UpdateManager.currentVersionName(this);
        versionTextView.setText(name.isEmpty() ? getString(R.string.版本_未知) : "v" + name);
        refreshVersionDot();
    }

    /**
     * 有新版本只在入口上点一个红点，不弹窗——测试途中最烦的就是界面被系统对话框打断。
     * 待安装清单在装好后自动失效（当前版本已经不低于它），所以从安装器回来后红点会自己消失。
     */
    private void refreshVersionDot() {
        if (versionTextView == null) {
            return;
        }
        boolean has = UpdateManager.pendingManifest(this) != null;
        versionTextView.setCompoundDrawablesWithIntrinsicBounds(
                0, 0, has ? R.drawable.update_dot : 0, 0);
        versionTextView.setCompoundDrawablePadding(has ? dpToPx(6) : 0);
        versionTextView.setContentDescription(has
                ? getString(R.string.版本_红点) : getString(R.string.版本_入口));
    }

    private int dpToPx(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    /**
     * 设置事件监听器
     */
    private void setupEventListeners() {
        // 设置无障碍设置按钮点击事件
        if (imageAccessibilitySettings != null) {
            imageAccessibilitySettings.setOnClickListener(v -> {
                // 检查无障碍服务是否已启用
                if (!isAccessibilityServiceEnabled()) {
                    // 跳转到无障碍设置页面
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                    ToastUtils.showLong(this, "请开启 BTControl 无障碍功能（组件 com.autotest.BTControl/.MyAccessibilityService；"
                            + "也可用 adb shell settings put secure enabled_accessibility_services 追加它）");
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
        // 已保存设备：整行点击不再进详情页（避免误触），只有右侧箭头进；长按仍是设备操作面板
        SavedDeviceListAdapter = createBluetoothDeviceAdapter(SavedDeviceList,
            null,
            (position, deviceItem) -> confirmUnpairDevice(deviceItem));
        SavedDeviceListAdapter.setOnDetailClickListener(
            (position, deviceItem) -> DeviceDetailActivity.start(this, deviceItem.getDeviceAddress()));
        
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
        
        // 三块列表 + 三个标题行拼成一个列表：整页只滚这一个 RecyclerView
        savedHeaderAdapter = new SectionHeaderAdapter(getString(R.string.已保存设备));
        nearbyHeaderAdapter = new SectionHeaderAdapter(getString(R.string.周围设备))
                .withAction(getString(R.string.开始扫描), v -> onScanButtonClick());
        nfHeaderAdapter = new SectionHeaderAdapter(getString(R.string.非常用设备))
                .withAction(getString(R.string.展开), v -> toggleNfExpanded());

        if (deviceRecyclerView != null) {
            deviceRecyclerView.setLayoutManager(new LinearLayoutManager(this));
            deviceRecyclerView.setAdapter(new ConcatAdapter(
                    savedHeaderAdapter, SavedDeviceListAdapter,
                    nearbyHeaderAdapter, NRDeviceListAdapter,
                    nfHeaderAdapter, NFDeviceListAdapter));
        }

        // 分组为空时把标题行一起收起；「周围设备」常驻，因为它带扫描按钮
        RecyclerView.AdapterDataObserver sectionVisibilityObserver = new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                refreshSectionVisibility();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                refreshSectionVisibility();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                refreshSectionVisibility();
            }
        };
        SavedDeviceListAdapter.registerAdapterDataObserver(sectionVisibilityObserver);
        NFDeviceListAdapter.registerAdapterDataObserver(sectionVisibilityObserver);
        refreshSectionVisibility();
    }

    /** 搜索时强制展开，否则「非常用设备」里的命中项会被收起状态藏掉。 */
    private boolean isNfVisible() {
        return nfExpanded || isSearching;
    }

    private void toggleNfExpanded() {
        nfExpanded = !nfExpanded;
        if (isSearching) {
            updateAdaptersWithFilteredList();
        } else {
            updateAdaptersNotFilteredList();
        }
    }

    /** 标题里带数量，并同步右侧「展开/收起」按钮。 */
    private void refreshNfHeader() {
        if (nfHeaderAdapter == null) {
            return;
        }
        int count = isSearching ? filteredNFDeviceList.size() : NFDeviceList.size();
        nfHeaderAdapter.setTitle(getString(R.string.非常用设备数量, count));
        nfHeaderAdapter.setActionText(getString(nfExpanded || isSearching ? R.string.收起 : R.string.展开));
    }

    /** 列表内容变了就同步标题行显隐，避免出现「已保存设备」下面一片空白。 */
    private void refreshSectionVisibility() {
        if (savedHeaderAdapter != null && SavedDeviceListAdapter != null) {
            savedHeaderAdapter.setVisible(SavedDeviceListAdapter.getItemCount() > 0);
        }
        if (nfHeaderAdapter != null) {
            int nfCount = isSearching ? filteredNFDeviceList.size() : NFDeviceList.size();
            nfHeaderAdapter.setVisible(nfCount > 0);
        }
    }

    /** 扫描按钮：扫描中再点一次就是停。 */
    private void onScanButtonClick() {
        if (isScanning.get()) {
            stopBluetoothDiscovery();
        } else {
            // 点击"开始扫描"按钮仅扫描一次
            startBluetoothDiscovery(false);
        }
    }

    /** 扫描按钮文案统一走这里；按钮在列表标题行里，可能存在也可能还没绑定。 */
    private void setScanButtonText(String text) {
        if (nearbyHeaderAdapter != null) {
            nearbyHeaderAdapter.setActionText(text);
        }
    }

    /** 扫描进度圈：绑定在「周围设备」标题行上。 */
    private void setScanProgress(boolean scanning) {
        if (nearbyHeaderAdapter != null) {
            nearbyHeaderAdapter.setProgressVisible(scanning);
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
        Log.d(TAG, "开始监听设备连接状态变化");
        updateSavedDeviceList();
        registerSavedDeviceStateReceiver();
    }

    /**
     * 登记刷新「已保存设备」的防抖回调。
     *
     * <p>以前是每秒 scheduleWithFixedDelay 全量重建，副作用有两个：
     * 一是 ConcatAdapter 每秒 notifyDataSetChanged，行被重建，长按手势会被 cancel（操作面板点不出来）；
     * 二是每秒 getBondedDevices() + 逐台 getProfileConnectionState()，后台一直在跑。
     * 现在改成「连接/配对状态广播触发 + 400ms 防抖」，只在真的变了才重建。
     */
    private void scheduleSavedDeviceRefresh() {
        mainHandler.removeCallbacks(savedDeviceRefreshRunnable);
        mainHandler.postDelayed(savedDeviceRefreshRunnable, 400);
    }

    /** 订阅会让「已保存设备」状态变化的系统广播。重复调用是安全的。 */
    private void registerSavedDeviceStateReceiver() {
        if (savedDeviceStateReceiver != null) {
            return;
        }
        savedDeviceStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                Log.d(TAG, "设备状态变化: " + action);
                // 顶部只挂蓝牙开关状态，开关一变就跟着刷，别等后台广播绕一圈
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    refreshKeyStatus();
                }
                scheduleSavedDeviceRefresh();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(savedDeviceStateReceiver, filter);
    }

    private void unregisterSavedDeviceStateReceiver() {
        if (savedDeviceStateReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(savedDeviceStateReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "注销设备状态接收器失败", e);
        } finally {
            savedDeviceStateReceiver = null;
        }
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
     *
     * <p>历史实现在这里拼了个不存在的组件名
     * （{@code getPackageName() + "/.automation.BTControlAccessibilityService"}），
     * 而真实服务是 {@code com.autotest.BTControl/.MyAccessibilityService}，
     * 于是永远判成「没开」、按钮永远只会跳设置页。现在统一交给
     * {@link BtctlReporter#isA11yEnabled(android.content.Context)} 判定。
     */
    /**
     * 自动化预检：这个动作是不是注定要人工介入。
     *
     * <p>两条边界是实测出来的：
     * <ul>
     *   <li>配对/重新配对必须有人点掉系统的配对确认框，而点框全靠无障碍服务；关着的时候
     *       只会 30 秒后报「配对超时」，看不出根因。</li>
     *   <li>关联（CDM）只有走 Shizuku 才能按 MAC 静默完成；没有 Shizuku 就只能人工在
     *       系统「选择要关联的设备」页里挑，自动化等不到结果。</li>
     * </ul>
     *
     * @return 拦截原因；可以继续执行时返回 null
     */
    private String preflightBlocker(String action) {
        if ("配对".equals(action) || "重新配对".equals(action)) {
            if (!isAccessibilityServiceEnabled()) {
                return "needs_a11y: 系统配对框要靠无障碍点掉，现在无障碍服务没开"
                        + "（设置 → 无障碍 → BTControl 自动化）；也可以先把设备配好再跑动作";
            }
        }
        if ("关联".equals(action)) {
            String shizukuStatus = com.autotest.BTControl.utils.ShizukuBridge.status();
            if (!"ok".equals(shizukuStatus)) {
                return "needs_shizuku: 按 MAC 建 CDM 关联要 Shizuku，当前 shizuku=" + shizukuStatus
                        + "（详情页连接配置卡的 Shizuku 行里授权）；没有 Shizuku 只能人工在系统选择器里点";
            }
        }
        return null;
    }

    private boolean isAccessibilityServiceEnabled() {
        return BtctlReporter.isA11yEnabled(this);
    }

    /**
     * 按设备名在「已配对」列表里找地址。
     *
     * <p>调试脚本手里常常只有设备名（DM_Speaker 这类），MAC 还得另找人问。这里先精确
     * 匹配、再退化成包含匹配（忽略大小写）。找不到或没权限就返回 null，由调用方报
     * {@code missing_device_address}，不猜。
     */
    private String resolveBondedAddressByName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String want = name.trim();
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled() || !hasBluetoothPermissions()) {
                Log.w(TAG, "按名字定位设备: 蓝牙不可用或无权限");
                return null;
            }
            BluetoothDevice fuzzy = null;
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String n = d.getName();
                if (n == null) {
                    continue;
                }
                if (n.equalsIgnoreCase(want)) {
                    return d.getAddress();
                }
                if (fuzzy == null && n.toLowerCase().contains(want.toLowerCase())) {
                    fuzzy = d;
                }
            }
            if (fuzzy == null) {
                Log.w(TAG, "按名字定位设备: 已配对列表里没有 " + want);
                return null;
            }
            return fuzzy.getAddress();
        } catch (Throwable t) {
            Log.w(TAG, "按名字定位设备失败: " + t);
            return null;
        }
    }

    // ==================== 版本与自动更新 ====================

    /** 版本检查 / 更新：不针对设备，自动化入口也用这两个名字。 */
    private static boolean isUpdateAction(String action) {
        return "检查更新".equals(action) || "更新".equals(action);
    }

    /** 手动打开 App 时的静默自检：只更新入口红点，绝不弹窗；网络失败只记日志。 */
    private void autoCheckOnLaunch() {
        UpdateManager.cleanupCache(this);
        Intent it = getIntent();
        if (it != null && it.getStringExtra("auto_action") != null) {
            return;
        }
        UpdateManager.maybeAutoCheck(this, result -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (result.error != null) {
                Log.d(TAG, "自动检查更新失败: " + result.error);
                return;
            }
            applyCheckResult(result, false);
        });
    }

    /** 把检查结果落到「待安装清单 + 红点」上；refreshPage 时顺手重绘已打开的更新页。 */
    private void applyCheckResult(UpdateManager.CheckResult result, boolean refreshPage) {
        if (result.error != null) {
            // 网络抖动不清掉已知的新版本公告，免得红点闪一下又没了
            if (refreshPage) {
                renderUpdatePage(result);
            }
            return;
        }
        if (result.installable(Build.VERSION.SDK_INT)) {
            UpdateManager.setPending(this, result.manifest);
        } else {
            UpdateManager.clearPending(this);
        }
        refreshVersionDot();
        if (refreshPage) {
            renderUpdatePage(result);
        }
    }

    /** 更新页：当前版本 + 更新公告 + 立即更新；打开时顺手静默刷新一次。 */
    private void showVersionDialog() {
        if (updatePageDialog != null && updatePageDialog.isShowing()) {
            return;
        }
        View content = getLayoutInflater().inflate(R.layout.dialog_update_info, null);
        updatePageStatusView = content.findViewById(R.id.updatePageStatus);
        updatePageCurrentView = content.findViewById(R.id.updatePageCurrent);
        updatePageChangelogView = content.findViewById(R.id.updatePageChangelog);
        updatePageTransitionView = content.findViewById(R.id.updatePageTransition);
        renderUpdatePage(null);
        updatePageDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.版本_入口)
                .setView(content)
                .setPositiveButton(R.string.版本_立即更新, null)
                .setNeutralButton(R.string.版本_设置, null)
                .setNegativeButton(R.string.版本_关闭, null)
                .create();
        // 按钮自己接管：检查完/装完都留在这一页，别把用户弹来弹去
        updatePageDialog.setOnShowListener(d -> {
            updatePageDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> onUpdatePagePrimary());
            updatePageDialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(v -> showUpdateSettings());
        });
        updatePageDialog.show();
        checkForUpdateManually();
    }

    /** 更新页主按钮：有公告就直接装，没有就先查。 */
    private void onUpdatePagePrimary() {
        UpdateManifest pending = UpdateManager.pendingManifest(this);
        if (pending == null) {
            checkForUpdateManually();
            return;
        }
        dismissUpdatePage();
        startUpdate(pending);
    }

    /** 重绘更新页：状态行 / 当前版本 / 公告 / 版本变化 / 主按钮文字。 */
    private void renderUpdatePage(UpdateManager.CheckResult result) {
        if (updatePageStatusView == null) {
            return;
        }
        String curName = UpdateManager.currentVersionName(this);
        long curCode = UpdateManager.currentVersionCode(this);
        updatePageCurrentView.setText(getString(R.string.版本_当前, curName, curCode));
        UpdateManifest m = UpdateManager.pendingManifest(this);
        if (m == null && result != null && result.error == null && result.available) {
            UpdateManager.setPending(this, result.manifest);
            m = result.manifest;
            refreshVersionDot();
        }
        if (m != null) {
            updatePageStatusView.setText(getString(R.string.版本_页_有新版本, m.versionName, m.versionCode));
            updatePageChangelogView.setText(m.changelog.isEmpty()
                    ? getString(R.string.版本_无更新说明) : m.changelog);
            updatePageTransitionView.setText(getString(R.string.版本_版本变化,
                    curName, curCode, m.versionName, m.versionCode));
        } else if (result != null && result.error != null) {
            updatePageStatusView.setText(getString(R.string.版本_检查失败, result.error));
            updatePageChangelogView.setText(getString(R.string.版本_无更新说明));
            updatePageTransitionView.setText("");
        } else {
            updatePageStatusView.setText(result == null
                    ? getString(R.string.版本_页_检查中) : getString(R.string.版本_页_最新));
            updatePageChangelogView.setText(getString(R.string.版本_无更新说明));
            updatePageTransitionView.setText("");
        }
        if (updatePageDialog != null) {
            Button positive = updatePageDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setText(m != null ? R.string.版本_立即更新 : R.string.版本_检查更新);
            }
        }
    }

    private void dismissUpdatePage() {
        if (updatePageDialog != null) {
            try {
                updatePageDialog.dismiss();
            } catch (Throwable ignored) {
                // 已销毁
            }
            updatePageDialog = null;
        }
    }

    /** 更新设置：自动检查 / Shizuku 静默安装 / 更新源。 */
    private void showUpdateSettings() {
        View content = getLayoutInflater().inflate(R.layout.dialog_update_settings, null);
        Switch autoSwitch = content.findViewById(R.id.autoCheckSwitch);
        Switch silentSwitch = content.findViewById(R.id.silentInstallSwitch);
        EditText urlEdit = content.findViewById(R.id.manifestUrlEdit);
        TextView env = content.findViewById(R.id.updateEnvText);
        autoSwitch.setChecked(UpdateManager.autoCheck(this));
        silentSwitch.setChecked(UpdateManager.silentInstall(this));
        urlEdit.setText(UpdateManager.manifestUrl(this));
        env.setText("Shizuku：" + ShizukuBridge.status()
                + "  ·  安装权限：" + (UpdateManager.hasInstallPermission(this) ? "已授予" : "未授予")
                + "  ·  默认源：" + UpdateManager.DEFAULT_MANIFEST_URL);
        new AlertDialog.Builder(this)
                .setTitle(R.string.版本_设置)
                .setView(content)
                .setPositiveButton(R.string.版本_保存, (d, w) -> {
                    UpdateManager.setAutoCheck(this, autoSwitch.isChecked());
                    UpdateManager.setSilentInstall(this, silentSwitch.isChecked());
                    UpdateManager.setManifestUrl(this, urlEdit.getText().toString());
                    ToastUtils.showShort(this, "更新设置已保存");
                })
                .setNeutralButton(R.string.版本_恢复默认, (d, w) -> {
                    UpdateManager.setManifestUrl(this, "");
                    UpdateManager.setAutoCheck(this, true);
                    UpdateManager.setSilentInstall(this, true);
                    ToastUtils.showShort(this, "已恢复默认更新设置");
                })
                .setNegativeButton(R.string.版本_取消, null)
                .show();
    }

    private void checkForUpdateManually() {
        ToastUtils.showShort(this, getString(R.string.版本_检查中));
        if (updatePageStatusView != null) {
            updatePageStatusView.setText(R.string.版本_页_检查中);
        }
        UpdateManager.check(this, result -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (result.error == null && result.available
                    && result.manifest.requiresNewerSdk(Build.VERSION.SDK_INT)) {
                if (updatePageStatusView != null) {
                    updatePageStatusView.setText("新版本 v" + result.manifest.versionName
                            + " 要求 Android SDK " + result.manifest.minSdk
                            + "，当前设备是 " + Build.VERSION.SDK_INT);
                }
                return;
            }
            applyCheckResult(result, updatePageDialog != null && updatePageDialog.isShowing());
        });
    }

    private void showUpdateDialog(String message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.版本_入口)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 下载并安装一次更新（带进度弹窗）。 */
    private void startUpdate(final UpdateManifest m) {
        if (updateDialog != null && updateDialog.isShowing()) {
            return;
        }
        boolean canSilent = UpdateManager.silentInstall(this) && "ok".equals(ShizukuBridge.status());
        if (!canSilent && !UpdateManager.hasInstallPermission(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.版本_入口)
                    .setMessage(R.string.版本_安装权限缺失)
                    .setPositiveButton(R.string.版本_去授权, (d, w) -> {
                        try {
                            startActivity(UpdateManager.installPermissionIntent(this));
                        } catch (Throwable t) {
                            ToastUtils.showLong(this, getString(R.string.版本_没有下载器));
                        }
                    })
                    .setNegativeButton(R.string.版本_取消, null)
                    .show();
            return;
        }
        showUpdateProgress(getString(R.string.版本_下载标题));
        final boolean[] resumed = {false};
        UpdateManager.download(this, m, new UpdateManager.DownloadCallback() {
            @Override
            public void onProgress(long done, long total) {
                if (!resumed[0] && done > 0) {
                    // 第一次回调就带着已下载字节 → 这次是接着上次下的
                    resumed[0] = true;
                }
                int percent = percentOf(done, total);
                updateProgressLine(getString(resumed[0] ? R.string.版本_继续下载 : R.string.版本_下载中,
                        percent, formatSize(done), formatSize(total)), percent);
            }

            @Override
            public void onFinished(File apk, String error) {
                if (error != null) {
                    dismissUpdateDialog();
                    ToastUtils.showLong(BluetoothControlActivity.this,
                            getString(R.string.版本_下载失败, friendlyDownloadError(error)));
                    return;
                }
                String bad = UpdateManager.verifyApk(BluetoothControlActivity.this, apk, m);
                if (bad != null) {
                    dismissUpdateDialog();
                    ToastUtils.showLong(BluetoothControlActivity.this,
                            getString(R.string.版本_安装包校验失败, bad));
                    return;
                }
                setUpdateProgressTitle(getString(R.string.版本_下载完成));
                updateProgressLine(getString(R.string.版本_安装中), 100);
                installDownloadedApk(apk);
            }
        });
    }

    private void installDownloadedApk(File apk) {
        setUpdateProgressTitle(getString(R.string.版本_安装中));
        UpdateManager.install(this, apk, error -> {
            dismissUpdateDialog();
            if (error == null) {
                return; // 已交给系统安装器（或静默安装成功后进程即将被杀）
            }
            if ("needs_install_permission".equals(error)) {
                ToastUtils.showLong(this, getString(R.string.版本_安装权限缺失));
                try {
                    startActivity(UpdateManager.installPermissionIntent(this));
                } catch (Throwable ignored) {
                    // 没有可用设置页就只留提示
                }
                return;
            }
            ToastUtils.showLong(this, getString(R.string.版本_安装失败, error));
        });
    }

    // ---- 进度弹窗 ----

    private TextView updatePageStatusView;
    private TextView updatePageCurrentView;
    private TextView updatePageChangelogView;
    private TextView updatePageTransitionView;

    private ProgressBar updateProgressBar;
    private TextView updateProgressTitleView;
    private TextView updateProgressLineView;

    private void showUpdateProgress(String title) {
        dismissUpdateDialog();
        View content = getLayoutInflater().inflate(R.layout.dialog_update_progress, null);
        updateProgressTitleView = content.findViewById(R.id.updateProgressTitle);
        updateProgressBar = content.findViewById(R.id.updateProgressBar);
        updateProgressLineView = content.findViewById(R.id.updateProgressText);
        updateProgressTitleView.setText(title);
        updateProgressBar.setProgress(0);
        updateProgressLineView.setText("");
        updateDialog = new AlertDialog.Builder(this)
                .setView(content)
                .setCancelable(false)
                .create();
        updateDialog.show();
    }

    private void setUpdateProgressTitle(String title) {
        if (updateProgressTitleView != null) {
            updateProgressTitleView.setText(title);
        }
    }

    private void updateProgressLine(String line, int percent) {
        if (updateProgressLineView != null) {
            updateProgressLineView.setText(line);
        }
        if (updateProgressBar != null) {
            updateProgressBar.setProgress(Math.max(0, Math.min(100, percent)));
        }
    }

    private void dismissUpdateDialog() {
        if (updateDialog != null) {
            try {
                updateDialog.dismiss();
            } catch (Throwable ignored) {
                // 页面已销毁
            }
            updateDialog = null;
        }
    }

    // ---- 自动化入口：检查更新 / 更新 ----

    /**
     * 自动化执行「检查更新」或「更新」。
     *
     * <p>结果 JSON 复用 BTCTL RESULT 通道，额外字段：{@code phase}、{@code update_available}、
     * {@code current_versionCode/Name}、{@code latest_versionCode/Name}、{@code install}。
     * 「更新」在安装真正开始时上报 {@code phase=install_dispatched}——静默安装会替换进程，
     * 之后没有回调可等，调用方要按版本号复查。
     */
    private void runUpdateAction(final String action, String sourceOverride) {
        String url = sourceOverride == null || sourceOverride.trim().isEmpty()
                ? UpdateManager.manifestUrl(this) : sourceOverride.trim();
        UpdateManager.checkUrl(this, url, result -> {
            if (result.error != null) {
                emitUpdateResult(action, false, "check_failed: " + result.error, null);
                return;
            }
            if ("检查更新".equals(action)) {
                // 自动化检查也把结果落成红点，人手打开就能看到公告
                applyCheckResult(result, false);
                emitUpdateResult(action, true, "", updateInfo(result, "checked"));
                return;
            }
            if (!result.available) {
                emitUpdateResult(action, true, "already_latest", updateInfo(result, "checked"));
                return;
            }
            UpdateManifest m = result.manifest;
            if (m.requiresNewerSdk(Build.VERSION.SDK_INT)) {
                emitUpdateResult(action, false, "needs_newer_sdk: 需要 SDK " + m.minSdk
                        + "，当前 " + Build.VERSION.SDK_INT, updateInfo(result, "checked"));
                return;
            }
            applyCheckResult(result, false);
            boolean silent = UpdateManager.silentInstall(this) && "ok".equals(ShizukuBridge.status());
            if (!silent && !UpdateManager.hasInstallPermission(this)) {
                emitUpdateResult(action, false, "needs_install_permission: 允许安装未知应用，"
                        + "或让 Shizuku 可用（appops set com.autotest.BTControl REQUEST_INSTALL_PACKAGES allow）",
                        updateInfo(result, "checked"));
                return;
            }
            downloadForAutomation(action, result);
        });
    }

    private void downloadForAutomation(final String action, final UpdateManager.CheckResult result) {
        UpdateManager.download(this, result.manifest, new UpdateManager.DownloadCallback() {
            @Override
            public void onProgress(long done, long total) {
                // 自动化不弹窗，进度只走 logcat，避免挡住界面
                Log.d(TAG, "更新下载 " + percentOf(done, total) + "%");
            }

            @Override
            public void onFinished(File apk, String error) {
                if (error != null) {
                    emitUpdateResult(action, false, "download_failed: " + error, updateInfo(result, "checked"));
                    return;
                }
                String bad = UpdateManager.verifyApk(BluetoothControlActivity.this, apk, result.manifest);
                if (bad != null) {
                    emitUpdateResult(action, false, "apk_verify_failed: " + bad, updateInfo(result, "checked"));
                    return;
                }
                boolean silent = UpdateManager.silentInstall(BluetoothControlActivity.this)
                        && "ok".equals(ShizukuBridge.status());
                if (!silent) {
                    emitUpdateResult(action, false,
                            "installer_prompted: 已弹出系统安装器，需用户点「更新」；想免确认就给 Shizuku 授权",
                            updateInfo(result, "installer_prompted"));
                    installDownloadedApk(apk);
                    return;
                }
                // 静默安装会替换进程：先报「已派发」，再动手
                emitUpdateResult(action, true, "silent_install_dispatched", updateInfo(result, "install_dispatched"));
                installDownloadedApk(apk);
            }
        });
    }

    private org.json.JSONObject updateInfo(UpdateManager.CheckResult r, String phase) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("phase", phase);
            o.put("update_available", r.available);
            o.put("current_versionCode", r.currentVersionCode);
            o.put("current_versionName", r.currentVersionName);
            o.put("manifest_url", r.manifestUrl);
            if (r.manifest != null) {
                o.put("latest_versionCode", r.manifest.versionCode);
                o.put("latest_versionName", r.manifest.versionName);
                o.put("latest_size", r.manifest.size);
                o.put("latest_apk", r.manifest.apkUrl);
            }
        } catch (Throwable ignored) {
            // 附加上下文失败不影响主结果
        }
        return o;
    }

    /** 更新动作的结果上报：不碰蓝牙字段，避免在没有蓝牙权限时抛异常。 */
    private void emitUpdateResult(String action, boolean ok, String reason, org.json.JSONObject extra) {
        long elapsed = btctlStartMs <= 0 ? 0 : System.currentTimeMillis() - btctlStartMs;
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("req", btctlReq == null ? "" : btctlReq);
            o.put("action", action);
            o.put("addr", "");
            o.put("ok", ok);
            o.put("reason", reason == null ? "" : reason);
            o.put("elapsed_ms", elapsed);
            o.put("ts", System.currentTimeMillis());
            o.put("silent_install", UpdateManager.silentInstall(this));
            o.put("shizuku", ShizukuBridge.status());
            if (extra != null) {
                java.util.Iterator<String> keys = extra.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    o.put(k, extra.get(k));
                }
            }
        } catch (Throwable ignored) {
            // 少一个字段不影响主结果
        }
        btctlEmitted = true;
        BtctlReporter.emit(this, o);
        runOnUiThread(this::refreshBtctlBar);
        if (btctlFinish) {
            Log.d(TAG, "自动化: 按请求结束界面");
            finishAndRemoveTask();
        }
    }

    private static int percentOf(long done, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, done * 100 / total));
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", kb / 1024.0);
    }

    private String friendlyDownloadError(String error) {
        if (error.startsWith("sha256_mismatch")) {
            return getString(R.string.版本_校验失败);
        }
        if (error.startsWith("http_404")) {
            return "更新源返回 404（version.json 里的 apkUrl 可能指错了）";
        }
        return error;
    }

    // ==================== 自动化操作方法 ====================

    /**
     * 处理Intent参数
     */
    @SuppressLint("MissingPermission")
    private void processIntentParameters() {
        Intent intent = getIntent();
        Log.d(TAG, "自动化: 开始处理Intent参数");

        if (intent == null) {
            Log.d(TAG, "自动化: Intent为null");
            return;
        }

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
        if (targetAction == null) {
            return;
        }

        // 支持两种参数名：device_address 和 bluetooth_address
        String targetDeviceAddress = intent.getStringExtra("device_address");
        if (targetDeviceAddress == null) {
            targetDeviceAddress = intent.getStringExtra("bluetooth_address");
        }
        String requestedName = intent.getStringExtra("auto_name");
        // 调用方到底有没有「点名」某台设备：地址或名字给了任意一个就算。
        // 点名了但解析不出来，绝不能静默退化成「全局状态」——AI 会以为查的就是它。
        boolean deviceRequested = (targetDeviceAddress != null && !targetDeviceAddress.trim().isEmpty())
                || (requestedName != null && !requestedName.trim().isEmpty());
        // 只有设备名、没有 MAC 时，按名字在已配对列表里定位（AI 侧通常只知道名字）
        if (targetDeviceAddress == null || targetDeviceAddress.trim().isEmpty()) {
            targetDeviceAddress = resolveBondedAddressByName(requestedName);
        }

        // 结果上报上下文：req 原样回显，调用方用它对齐自己那一次请求
        btctlReq = intent.getStringExtra("req");
        btctlAction = targetAction;
        btctlAddr = targetDeviceAddress;
        btctlFinish = intent.getBooleanExtra("finish", false) || intent.getBooleanExtra("finish_after", false);
        String requestedProfile = intent.getStringExtra("auto_profile");
        btctlProfile = (requestedProfile == null || requestedProfile.trim().isEmpty())
                ? "all" : requestedProfile.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"all".equals(btctlProfile) && !"a2dp".equals(btctlProfile)
                && !"hfp".equals(btctlProfile) && !"leaudio".equals(btctlProfile)) {
            Log.d(TAG, "自动化: 未知 profile " + requestedProfile);
            emitBtctlResult(false, "unknown_profile: " + requestedProfile + "（可用 a2dp/hfp/leaudio/all）");
            return;
        }
        btctlEmitted = false;
        btctlProfileTries = 0;
        btctlScanDevices = null;
        btctlStartMs = System.currentTimeMillis();

        if (deviceRequested && (targetDeviceAddress == null || targetDeviceAddress.trim().isEmpty())) {
            Log.d(TAG, "自动化: 设备标识无法解析 auto_name=" + requestedName
                    + ", address=" + intent.getStringExtra("device_address"));
            emitBtctlResult(false, "missing_device_address");
            return;
        }


        // 只读状态查询：不改状态、不等动作，立刻返回，AI 先用它探路
        if ("state".equals(targetAction)) {
            emitBtctlResult(true, "");
            return;
        }

        // 版本检查/更新：不涉及任何设备，先处理掉，免得被后面的 requiresDevice/preflight 拦
        if (isUpdateAction(targetAction)) {
            runUpdateAction(targetAction, intent.getStringExtra("update_source"));
            return;
        }

        // 扫描但没给目标设备：按「列表扫描」跑，扫 scan_sec 秒把发现的设备一起报回来。
        // 原来的扫描语义是「一直扫到指定设备出现」，AI 手里没有目标设备时根本没法用。
        if ("扫描".equals(targetAction) && targetDeviceAddress == null) {
            runScanList(parseIntExtra(intent.getStringExtra("scan_sec"), 12));
            return;
        }

        if (targetDeviceAddress == null && requiresDevice(targetAction)) {
            Log.d(TAG, "自动化: 参数不完整 - targetAction=" + targetAction + ", targetDeviceAddress=" + targetDeviceAddress);
            emitBtctlResult(false, "missing_device_address");
            return;
        }

        // 预检：有些动作注定要人工介入（点系统弹框 / 授权），先给一条可诊断的失败，
        // 别让 adb 侧白等 25 秒超时——超时只能说明「没结果」，看不出到底缺什么。
        String blocker = preflightBlocker(targetAction);
        if (blocker != null) {
            Log.w(TAG, "自动化预检拦截: " + blocker);
            emitBtctlResult(false, blocker);
            return;
        }

        // 「开关」= 直接对这个 profile 连 / 断：auto_enable 1 → 连接，0 → 断开。
        // 详情页的开关现在就是连接状态本身，所以不再有「只改偏好」的语义。
        if ("开关".equals(targetAction)) {
            String translated = translateSwitchAction(intent);
            if (translated == null) {
                return; // 参数不合法，错误已经上报
            }
            targetAction = translated;
            btctlAction = targetAction;
        }

        // CDM 关联：BluetoothDevice.connect() 对普通应用要求「已与本设备建立 CompanionDevice 关联」，
        // 否则抛 "does not have a CDM association with the Bluetooth Device"。关联一次之后
        // 整机 connect()/disconnect() 才不会被拦，HFP 也才能由系统侧拉起。
        if ("关联".equals(targetAction)) {
            startCdmAssociation(targetDeviceAddress);
            return;
        }

        // Shizuku：借 shell(uid 2000) 的身份调 bluetooth_headset，绕开普通应用缺的
        // MODIFY_PHONE_STATE。显式开关（auto_via=shizuku），不悄悄改默认路径。
        String requestedVia = intent.getStringExtra("auto_via");
        btctlVia = (requestedVia == null || requestedVia.trim().isEmpty())
                ? "native" : requestedVia.trim().toLowerCase(java.util.Locale.ROOT);
        if ("shizuku".equals(btctlVia)) {
            runViaShizuku(targetAction, targetDeviceAddress);
            return;
        }

        String successMsg = "正在执行自动化操作请求: " + targetAction + ", 设备地址: " + targetDeviceAddress;
        showSuccessMessage(successMsg);
        autoAction.set(true);

        // 设置状态监听器
        automationManager.setStatusListener(new BluetoothAutomationManager.AutomationStatusListener() {

            @Override
            public void onOperationComplete(String action, boolean success, String message) {
                runOnUiThread(() -> {
                    // 结果统一由底部回执条（emitBtctlResult -> refreshBtctlBar）展示，顶部不再重复
                    autoAction.set(false);
                    emitBtctlResult(success, message);
                });
            }

            @Override
            public void onDeviceDiscovered(BluetoothDevice device) {
                // 列表自己会刷新，自动化期间每发现一台就写一条状态只会刷屏
                Log.d(TAG, "自动化发现设备: " + device.getName());
            }
        });

        // 执行自动化操作（auto_profile 非 all 时只动指定的那个 profile）
        automationManager.executeAutoAction(targetAction, targetDeviceAddress, btctlProfile);
    }

    /**
     * 把「开关」翻译成对应的连接动作。
     *
     * <p>详情页的开关已经等于连接状态，所以 {@code auto_enable 1} 等价于「连接这个 profile」、
     * {@code 0} 等价于「断链这个 profile」，不用再维护一份独立的偏好。
     *
     * @return 翻译后的动作名；参数不合法时返回 null（错误已经上报）
     */
    private String translateSwitchAction(Intent intent) {
        if (!"a2dp".equals(btctlProfile) && !"hfp".equals(btctlProfile) && !"leaudio".equals(btctlProfile)) {
            emitBtctlResult(false, "switch_requires_profile: auto_profile 需指定 a2dp/hfp/leaudio");
            return null;
        }
        String raw = intent.getStringExtra("auto_enable");
        if (raw == null || raw.trim().isEmpty()) {
            emitBtctlResult(false, "missing_auto_enable: auto_enable=0|1");
            return null;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        boolean enabled = "1".equals(v) || "true".equals(v) || "on".equals(v);
        btctlSwitchEnabled = enabled;
        Log.d(TAG, "自动化: 开关 " + btctlProfile + " = " + enabled);
        return enabled ? "连接" : "断链";
    }

    /**
     * 走 Shizuku 代理执行单 profile 的连接/断链。
     *
     * <p>需要 shell 身份的两条：HFP 被 {@code MODIFY_PHONE_STATE} 拒，LE Audio 的
     * {@code BluetoothLeAudio.connect()} 是 @SystemApi、普通反射拿不到。A2DP 本来就能连，
     * 走 native 就够。
     */
    private void runViaShizuku(String action, String address) {
        boolean hfp = "hfp".equals(btctlProfile);
        boolean leAudio = "leaudio".equals(btctlProfile);
        if (!hfp && !leAudio) {
            emitBtctlResult(false, "shizuku_unsupported: auto_via=shizuku 只支持 --profile hfp|leaudio");
            return;
        }
        if (!"连接".equals(action) && !"断链".equals(action)) {
            emitBtctlResult(false, "shizuku_unsupported: auto_via=shizuku 只支持 连接/断链");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            emitBtctlResult(false, "bluetooth_disabled");
            return;
        }
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            emitBtctlResult(false, "invalid_address: " + address);
            return;
        }
        String status = ShizukuBridge.status();
        if (!"ok".equals(status)) {
            if ("shizuku_unauthorized".equals(status)) {
                ShizukuBridge.requestPermission();
            }
            emitBtctlResult(false, status);
            return;
        }
        final long startMs = System.currentTimeMillis();
        BluetoothOperationUtils.OperationCallback cb = new BluetoothOperationUtils.OperationCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> emitBtctlResult(true, ""));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> emitBtctlResult(false, error));
            }
        };
        if ("连接".equals(action)) {
            if (hfp) {
                operationUtils.connectHfpViaShizuku(device, cb);
            } else {
                operationUtils.connectLeAudioViaShizuku(device, cb);
            }
        } else {
            if (hfp) {
                operationUtils.disconnectHfpViaShizuku(device, cb);
            } else {
                operationUtils.disconnectLeAudioViaShizuku(device, cb);
            }
        }
        Log.d(TAG, "自动化: shizuku " + action + " profile=" + btctlProfile + " 已发起 t=" + startMs);
    }

    /**
     * 列表扫描：不针对某台设备，扫 scanSec 秒后把期间发现的设备一起上报。
     *
     * <p>结果里带 {@code devices:[{name,addr,bond}]}，没扫到设备时 ok=false、
     * reason=no_device_found —— AI 至少能分清「扫到了什么」和「一台都没有」。
     */
    private void runScanList(final int scanSec) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            emitBtctlResult(false, "bluetooth_disabled");
            return;
        }
        startBluetoothDiscovery(false);
        executorService.execute(() -> {
            try {
                Thread.sleep(Math.max(3, scanSec) * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<BluetoothDevice> found = new ArrayList<>();
            synchronized (NRDevicesMap) {
                found.addAll(NRDevicesMap.values());
            }
            synchronized (NFDevicesMap) {
                found.addAll(NFDevicesMap.values());
            }
            // 停扫描会动 UI（改按钮文案、隐藏进度条），必须回主线程，别在工作线程里调
            runOnUiThread(() -> {
                stopBluetoothDiscovery();
                btctlScanDevices = found;
                emitBtctlResult(!found.isEmpty(), found.isEmpty() ? "no_device_found" : "");
            });
        });
    }

    /**
     * 该动作是否需要目标设备地址。
     *
     * <p>扫描与状态查询本身就不针对某台设备，早期实现一律要求地址，导致
     * {@code am start ... -e auto_action 扫描} 直接回报 {@code missing_device_address}。
     */
    private static boolean requiresDevice(String action) {
        return !("扫描".equals(action) || "state".equals(action) || isUpdateAction(action));
    }

    /**
     * 发起一次 CompanionDeviceManager 关联（系统会弹确认框，需人工/无障碍点「允许」）。
     *
     * <p>关联成功后 {@code BluetoothDevice.connect()} 才会被系统放行；没有关联时它抛
     * {@code does not have a CDM association with the Bluetooth Device}。
     */
    private void startCdmAssociation(final String address) {
        if (address == null || address.trim().isEmpty()) {
            emitBtctlResult(false, "missing_device_address");
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            emitBtctlResult(false, "bluetooth_disabled");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            emitBtctlResult(false, "bluetooth_permission_denied");
            return;
        }
        CompanionDeviceManager cdm = getSystemService(CompanionDeviceManager.class);
        if (cdm == null) {
            emitBtctlResult(false, "cdm_unavailable");
            return;
        }
        if (hasCdmAssociation(address)) {
            emitBtctlResult(true, "already_associated");
            return;
        }
        // 有 Shizuku 就走 shell 按 MAC 建关联：系统选择器只列正在广播的设备，
        // 已连上的设备不可发现，基本必然选不中。
        if ("ok".equals(ShizukuBridge.status())) {
            executorService.execute(() -> {
                String result = ShizukuBridge.associateCompanionDevice(this, address);
                runOnUiThread(() -> {
                    if (result.startsWith("ok")) {
                        Log.i(TAG, "Shizuku 按 MAC 关联成功: " + address);
                        emitBtctlResult(true, "shizuku_mac");
                    } else {
                        Log.w(TAG, "Shizuku 按 MAC 关联失败，退回选择器: " + result);
                        launchCdmChooser(address);
                    }
                });
            });
            return;
        }
        launchCdmChooser(address);
    }

    /** 公开 API 的关联流程：加过滤器，由系统选择器让用户挑设备。 */
    @SuppressLint("MissingPermission")
    private void launchCdmChooser(final String address) {
        CompanionDeviceManager cdm = getSystemService(CompanionDeviceManager.class);
        if (cdm == null) {
            emitBtctlResult(false, "cdm_unavailable");
            return;
        }
        final BluetoothDevice device;
        try {
            device = bluetoothAdapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            emitBtctlResult(false, "invalid_device_address: " + address);
            return;
        }
        // 注意：AssociationRequest.Builder#addDevice(BluetoothDevice) 是 @SystemApi，普通应用用不了；
        // 公开 API 只能加过滤器，由系统弹出选择器让用户挑设备（onDeviceFound）。
        AssociationRequest request = new AssociationRequest.Builder()
                .addDeviceFilter(new BluetoothDeviceFilter.Builder().build())
                .setSingleDevice(true)
                .setDisplayName(device.getName() == null ? "BTControl" : device.getName())
                .build();
        CompanionDeviceManager.Callback callback = new CompanionDeviceManager.Callback() {
            @Override
            public void onDeviceFound(IntentSender chooserLauncher) {
                Log.d(TAG, "CDM 关联选择器已弹出");
                cdmAssociateLauncher.launch(new IntentSenderRequest.Builder(chooserLauncher).build());
            }

            @Override
            public void onAssociationCreated(AssociationInfo associationInfo) {
                String associatedMac = associationInfo.getDeviceMacAddress() == null
                        ? null : associationInfo.getDeviceMacAddress().toString();
                if (associatedMac != null && !associatedMac.equalsIgnoreCase(address)) {
                    Log.w(TAG, "CDM 选择器挑中了别的设备 " + associatedMac + "，撤销");
                    BtctlReporter.disassociate(BluetoothControlActivity.this, associatedMac);
                    emitBtctlResult(false, "associate_wrong_device: " + associatedMac);
                } else {
                    Log.d(TAG, "CDM 关联成功: " + associationInfo);
                    emitBtctlResult(true, "");
                }
            }

            @Override
            public void onFailure(CharSequence error) {
                Log.e(TAG, "CDM 关联失败: " + error);
                emitBtctlResult(false, "associate_failed: " + error);
            }
        };
        try {
            cdm.associate(request, callback, null);
        } catch (Exception e) {
            Log.e(TAG, "CDM 关联异常", e);
            emitBtctlResult(false, "associate_error: " + e);
        }
    }

    /** 目标设备是否已经与本源建立 CDM 关联（逻辑在 BtctlReporter，界面也要用）。 */
    private boolean hasCdmAssociation(String address) {
        return BtctlReporter.hasAssociation(this, address);
    }

    /**
     * 长按已保存设备：直接确认「取消配对」。
     *
     * <p>CDM 关联入口已经在设备详情页的「CDM 关联」行，长按不再叠一层操作面板。
     */
    private void confirmUnpairDevice(BluetoothDeviceAdapter.BluetoothDeviceItem deviceItem) {
        final BluetoothDevice device = deviceItem.getBluetoothDevice();
        if (device == null) {
            return;
        }
        @SuppressLint("MissingPermission") final String address = device.getAddress();
        @SuppressLint("MissingPermission") final String name = device.getName();
        String title = (name == null || name.isEmpty()) ? address : name;
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.详情_取消配对标题)
                .setMessage(getString(R.string.详情_取消配对正文, title))
                .setPositiveButton(R.string.详情_取消配对,
                        (d, which) -> unpairDevice(device))
                .setNegativeButton(R.string.详情_取消, null)
                .show();
        tintDestructiveButton(dialog);
    }

    /** 危险确认键标红：AlertDialog 的 positive 按钮默认取 colorPrimary。 */
    void tintDestructiveButton(androidx.appcompat.app.AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        Button positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.action_destructive));
        }
    }

    /**
     * 读 int 型 extra。
     *
     * <p>`am start -e scan_sec 15` 传的是**字符串** extra，直接 getIntExtra 会静默拿到默认值，
     * 之前 scan_sec 一直生效不了就是这个原因。
     */
    private static int parseIntExtra(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            Log.w(TAG, "int extra 解析失败: " + raw + "，回退到 " + fallback);
            return fallback;
        }
    }

    /**
     * 自动化结果上报：一行 logcat（tag=BTCTL）+ 一份 JSON 文件兜底。
     *
     * <p>同一次请求只报一次：扫描超时时底层会先报一次失败，动作线程结束时又会报一次。
     */
    private void emitBtctlResult(boolean success, String reason) {
        if (btctlEmitted) {
            return;
        }
        // 连接类动作返回时 profile 状态往往还没刷新（A2DP 的 STATE_CONNECTED 是异步广播），
        // 立刻采样会出现 ok=true 但 a2dp=UNKNOWN，AI 会误判成「连上了却没走音频」。
        // 这里最多再等两次（每次 500ms）让快照读到 CONNECTED。
        boolean profileAction = "连接".equals(btctlAction) || "配对".equals(btctlAction)
                || "重新配对".equals(btctlAction) || "断链".equals(btctlAction);
        // 连接/配对等 CONNECTED，断链等「连不上」，两个方向的等待条件不一样
        boolean expectConnected = !"断链".equals(btctlAction);
        // 最多等 3s：实测 A2DP 从 connect 发出到 CONNECTED 要 ~2.5s，等 2×500ms 会读到旧值
        if (success && profileAction
                && !BtctlReporter.profileSettled(this, btctlAddr, btctlProfile, expectConnected)) {
            if (btctlProfileTries < 6) {
                btctlProfileTries++;
                Log.d(TAG, "自动化: profile 未刷新，500ms 后重采样(" + btctlProfileTries + ")");
                final String pendingReason = reason;
                new Handler(Looper.getMainLooper()).postDelayed(() -> emitBtctlResult(true, pendingReason), 500);
                return;
            }
            // 代理报成功但 3s 后状态仍没变（比如对端不支持这个 profile）：
            // 别让 ok=true 骗人，改成可诊断的失败。
            success = false;
            reason = "profile_not_connected: " + btctlProfile + " 等 "
                    + (expectConnected ? "CONNECTED" : "DISCONNECTED") + " 超时";
            Log.w(TAG, "自动化: " + reason);
        }
        btctlEmitted = true;
        long elapsed = btctlStartMs <= 0 ? 0 : System.currentTimeMillis() - btctlStartMs;
        JSONObject snapshot = BtctlReporter.snapshot(this, btctlReq, btctlAction, btctlAddr, success, reason, elapsed);
        try {
            // 让调用方知道我请求的是「整机」还是某一个 profile
            snapshot.put("profile", btctlProfile);
            snapshot.put("via", btctlVia);
            snapshot.put("shizuku", ShizukuBridge.status());
            if (btctlSwitchEnabled != null) {
                snapshot.put("enabled", btctlSwitchEnabled);
            }
            // 顺带回一次 CDM 关联状态：没有关联时整机 connect() 必被拒
            snapshot.put("assoc", hasCdmAssociation(btctlAddr));
        } catch (Throwable ignored) {
            // 少一个字段不影响主结果
        }
        java.util.List<String> warnings = automationManager.getLastWarnings();
        if (warnings != null && !warnings.isEmpty()) {
            try {
                // 整机动作里某个 profile 被拒（典型：HFP 缺 MODIFY_PHONE_STATE），ok 可能是 true，但要如实列出来
                snapshot.put("warnings", new org.json.JSONArray(warnings));
            } catch (Throwable ignored) {
                // 附加上下文失败不影响主结果
            }
        }
        if (btctlScanDevices != null) {
            try {
                snapshot.put("devices", BtctlReporter.devicesJson(btctlScanDevices));
            } catch (Throwable ignored) {
                // 设备列表塞不进去也不影响主结果
            }
        }
        BtctlReporter.emit(this, snapshot);
        runOnUiThread(this::refreshBtctlBar);
        if (btctlFinish) {
            Log.d(TAG, "自动化: 按请求结束界面");
            finishAndRemoveTask();
        }
    }

    /** 绑定顶部状态条：环境一行、最近结果一行，点结果行复制 JSON。 */
    private void setupBtctlBar() {
        btctlBar = findViewById(R.id.btctlBar);
        btctlResultTextView = findViewById(R.id.btctlResultTextView);
        if (btctlResultTextView != null) {
            btctlResultTextView.setOnClickListener(v -> copyLastResult());
            // 长按看环境详情：蓝牙/无障碍/profile，不用它时完全不占屏幕
            btctlResultTextView.setOnLongClickListener(v -> {
                showEnvDialog();
                return true;
            });
        }
    }

    /** 非自动化事件在底部停留多久（毫秒），到点回落到上一条自动化结果。 */
    private static final long EVENT_BAR_TTL_MS = 6000L;

    /** 页面是否在前台：前台时事件全走底部回执条，不再叠一层 Toast。 */
    private boolean resumed = false;

    /** 事件序号：事件条翻篇（来新事件/刷回执）后，旧的延时恢复直接作废。 */
    private int eventBarToken = 0;

    /** 当前挂在事件条上的事件文本，用来合并「扫描重启」这类重复事件。 */
    private String eventBarText;

    /** 在底部回执条上闪一条普通事件/错误，不占用顶部状态条。 */
    private void showEvent(String text, boolean error) {
        if (btctlBar == null || btctlResultTextView == null || text == null || text.isEmpty()) {
            return;
        }
        final int token = ++eventBarToken;
        boolean same = text.equals(eventBarText);
        eventBarText = text;
        btctlBar.setVisibility(View.VISIBLE);
        if (!same) {
            btctlResultTextView.setText(text);
            btctlResultTextView.setTextColor(getColor(error
                    ? android.R.color.holo_red_dark : R.color.grey_700));
        }
        mainHandler.postDelayed(() -> {
            if (token != eventBarToken) {
                return;
            }
            eventBarText = null;
            refreshBtctlBar();
        }, EVENT_BAR_TTL_MS);
    }

    /** 刷新底部回执条：没触发过自动化就一直藏着，别占用户首屏。 */
    private void refreshBtctlBar() {
        if (btctlResultTextView == null || btctlBar == null) {
            return;
        }
        // 事件条回到自动化回执：作废还没到点的事件恢复
        eventBarText = null;
        eventBarToken++;
        JSONObject last = BtctlReporter.lastJson;
        if (last == null) {
            btctlBar.setVisibility(View.GONE);
            return;
        }
        boolean ok = last.optBoolean("ok", false);
        String reason = last.optString("reason", "");
        StringBuilder sb = new StringBuilder();
        sb.append(ok ? "✅ " : "⚠️ ").append(last.optString("action", "?"));
        sb.append("  ").append(last.optLong("elapsed_ms", 0)).append("ms");
        if (!reason.isEmpty()) {
            sb.append("  ").append(reason);
        }
        String addr = last.optString("addr", "");
        if (!addr.isEmpty()) {
            sb.append("  ").append(addr);
        }
        sb.append("   · 点按复制");
        btctlResultTextView.setText(sb.toString());
        btctlResultTextView.setTextColor(getColor(ok ? R.color.grey_700 : android.R.color.holo_red_dark));
        btctlBar.setVisibility(View.VISIBLE);
    }

    /** 环境摘要（蓝牙开关/无障碍/各 profile 状态）弹窗，平时不占界面。 */
    private void showEnvDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("运行环境")
                .setMessage(BtctlReporter.envLine(this))
                .setPositiveButton("复制 JSON", (d, which) -> copyLastResult())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 把最近一次结果 JSON 复制到剪贴板，方便人调试时直接贴给 AI。 */
    private void copyLastResult() {
        JSONObject last = BtctlReporter.lastJson;
        if (last == null) {
            ToastUtils.showShort(this, getString(R.string.btctl_result_no_data));
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("BTCTL RESULT", last.toString()));
        ToastUtils.showShort(this, getString(R.string.btctl_result_copied));
    }

    /**
     * 停止自动化操作
     */
    private void stopAutomation() {        autoAction.set(false);
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
     * 连续扫描时同一台设备会反复被回调：只把最新 RSSI 写回已有的列表项。
     *
     * <p>不重建 item、不重排列表，避免扫描中列表跳动。
     */
    private void refreshDeviceRssi(String address) {
        int rssi = ScanAdvertCache.rssiOf(address);
        if (rssi == ScanAdvertCache.RSSI_UNKNOWN) {
            return;
        }
        boolean changed = false;
        for (BluetoothDeviceAdapter.BluetoothDeviceItem item : NRDeviceList) {
            if (address.equalsIgnoreCase(item.getDeviceAddress())) {
                item.setRssi(rssi);
                changed = true;
                break;
            }
        }
        if (!changed) {
            for (BluetoothDeviceAdapter.BluetoothDeviceItem item : NFDeviceList) {
                if (address.equalsIgnoreCase(item.getDeviceAddress())) {
                    item.setRssi(rssi);
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            runOnUiThread(this::renderDeviceLists);
        }
    }

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
        ScanAdvertCache.clear(); // 信号强度/UUID 是上一轮的事，别残留
        NRDevicesMap.clear();
        NRDeviceList.clear();
        NFDevicesMap.clear();
        NFDeviceList.clear();
        
        // 清空过滤列表
        filteredNRDeviceList.clear();
        filteredNFDeviceList.clear();

        // 更新适配器
        renderDeviceLists();

        setScanButtonText("停止扫描");
        setScanProgress(true);
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
                    setScanButtonText("停止扫描");
                    setScanProgress(true);
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
                        // 设备已经有了，但连续扫描时 RSSI 会变：只刷新信号强度，不重排队
                        Log.d(TAG, "onDeviceFound: 设备已在发现列表中，只刷新 RSSI - " + deviceName);
                        refreshDeviceRssi(deviceAddress);
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
                            deviceItem.setRssi(ScanAdvertCache.rssiOf(deviceAddress));
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
                            deviceItem.setRssi(ScanAdvertCache.rssiOf(deviceAddress));
                            NFDeviceList.add(deviceItem);
                        }
                    }
                } else {
                    Log.w(TAG, "onDeviceFound: 设备为null");
                }
                // 在UI线程中更新设备列表
                runOnUiThread(BluetoothControlActivity.this::renderDeviceLists);
            }
            
            @Override
            public void onDiscoveryFinished() {
                runOnUiThread(() -> {
                    showSuccessMessage("设备扫描完成");
                    setScanButtonText("开始扫描");
                    setScanProgress(false);
                    isScanning.set(false);
                });
            }
            
            @Override
            public void onDiscoveryError(String error) {
                runOnUiThread(() -> {
                    showEvent("设备扫描错误: " + error, true);
                    setScanButtonText("开始扫描");
                    setScanProgress(false);
                    isScanning.set(false);
                });
            }
            
            @Override
            public void onBluetoothStateChanged(int state) {
                runOnUiThread(() -> {
                    switch (state) {
                        case BluetoothAdapter.STATE_ON:
                            setKeyStatus("蓝牙已开启");
                            if (hasBluetoothPermissions()) {
                                startBluetoothDiscovery();
                            }
                            break;
                        case BluetoothAdapter.STATE_OFF:
                            setKeyStatus("蓝牙已关闭");
                            break;
                        case BluetoothAdapter.STATE_TURNING_ON:
                            setKeyStatus("蓝牙正在开启...");
                            break;
                        case BluetoothAdapter.STATE_TURNING_OFF:
                            setKeyStatus("蓝牙正在关闭...");
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

        setScanButtonText("开始扫描");
        setScanProgress(false);

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
            if (finalRemovedFromNR || finalRemovedFromNF) {
                renderDeviceLists();
            }
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
                    renderDeviceLists();
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
                    renderDeviceLists();

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
        renderDeviceLists();
    }

    /**
     * 取消过滤后的列表更新适配器
     */
    private void updateAdaptersNotFilteredList() {
        renderDeviceLists();
    }

    /**
     * 三块列表唯一的渲染入口：按搜索状态与「非常用设备」收起状态决定各自展示哪份数据。
     * 适配器渲染的是 display 列表，源列表（Saved/NR/NF）不直接被 UI 引用，收起才收得掉。
     */
    private void renderDeviceLists() {
        if (SavedDeviceListAdapter == null || NRDeviceListAdapter == null || NFDeviceListAdapter == null) {
            return;
        }
        // 传副本：适配器的 updateDeviceList 是 clear + addAll 自身列表，传同一个对象会先清空再拷空
        SavedDeviceListAdapter.updateDeviceList(
                new ArrayList<>(isSearching ? filteredSavedDeviceList : SavedDeviceList));
        NRDeviceListAdapter.updateDeviceList(
                new ArrayList<>(isSearching ? filteredNRDeviceList : NRDeviceList));
        if (isNfVisible()) {
            NFDeviceListAdapter.updateDeviceList(
                    new ArrayList<>(isSearching ? filteredNFDeviceList : NFDeviceList));
        } else {
            NFDeviceListAdapter.updateDeviceList(new ArrayList<>());
        }
        refreshSectionVisibility();
        refreshNfHeader();
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
            // 行里只留一个状态点，连接配置（A2DP/HFP/LE Audio）在详情页看
            statusText = "● 已连接";
            profilesText = null;
        } else {
            // 未连接设备不显示状态字符串
            statusText = "";
            // 未连接设备不显示配置文件信息
            profilesText = null;
        }
        
        return new DeviceStatusInfo(isConnected, statusText, profilesText);
    }

    /**
     * 顶部状态条只留关键信息：蓝牙开关状态，以及不解决就什么都干不了的阻塞项（没权限之类）。
     * 连接/配对/扫描这些操作流水走底部回执条，同一句话不在页面上下各出现一次。
     */
    private void setKeyStatus(String status) {
        if (statusTextView != null) {
            statusTextView.setText(status);
        }
        Log.d(TAG, status);
    }

    /** 把蓝牙适配器的当前状态写到顶部，别让用户对着「......」猜。 */
    private void refreshKeyStatus() {
        if (bluetoothAdapter == null && bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
        if (bluetoothAdapter == null) {
            setKeyStatus("蓝牙不可用");
            return;
        }
        switch (bluetoothAdapter.getState()) {
            case BluetoothAdapter.STATE_ON:
                setKeyStatus("蓝牙已开启");
                break;
            case BluetoothAdapter.STATE_OFF:
                setKeyStatus("蓝牙已关闭");
                break;
            case BluetoothAdapter.STATE_TURNING_ON:
                setKeyStatus("蓝牙正在开启...");
                break;
            case BluetoothAdapter.STATE_TURNING_OFF:
                setKeyStatus("蓝牙正在关闭...");
                break;
            default:
                setKeyStatus("蓝牙状态未知");
                break;
        }
    }

    /**
     * 操作流水统一落到页面底部的回执条上：几秒后自动回落到上一条自动化结果，没有结果就收起。
     */
    private void updateStatus(String status) {
        Log.d(TAG, status);
        // 文案里带「失败/错误」的按错误标红，别让失败提示跟普通进度长一个样
        boolean error = status.contains("失败") || status.contains("错误") || status.contains("异常");
        showEvent(status, error);
    }
    
    /**
     * 显示错误消息（使用Toast和状态文本）
     */
    private void showErrorMessage(String message) {
        showEvent(message, true);
        if (!resumed) {
            ToastUtils.showError(this, message);
        }
        Log.e(TAG, "错误: " + message);
    }
    
    /**
     * 显示成功消息（使用Toast和状态文本）
     */
    private void showSuccessMessage(String message) {
        updateStatus(message);
        if (!resumed) {
            ToastUtils.showSuccess(this, message);
        }
        Log.i(TAG, "成功: " + message);
    }
    
    /**
     * 显示警告消息（使用Toast和状态文本）
     */
    private void showWarningMessage(String message) {
        updateStatus(message);
        if (!resumed) {
            ToastUtils.showWarning(this, message);
        }
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
