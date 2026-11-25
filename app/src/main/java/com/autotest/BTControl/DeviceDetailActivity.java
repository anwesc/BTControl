package com.autotest.BTControl;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.autotest.BTControl.utils.BluetoothOperationUtils;
import com.autotest.BTControl.utils.BtctlReporter;
import com.autotest.BTControl.utils.LeAudioSupport;
import com.autotest.BTControl.utils.ShizukuBridge;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;

/**
 * 设备详情页：把连接配置（A2DP / HFP / LE Audio）、整机开关、CDM 关联状态从列表里收进来。
 *
 * <p>列表行只留「名字 / 地址 / 状态」，因为之前把 profile 明细直接写在一行里，
 * 一屏能看的设备太少，而且状态每变一次整行都在抖。
 *
 * <p>状态刷新有两个来源，别再退回「操作后等 600ms 读一次」：
 * <ul>
 *   <li>系统广播（配对 / ACL / A2DP / HFP / LE Audio / 蓝牙开关）→ 250ms 防抖后整页刷新；</li>
 *   <li>动作下发后按 300ms 轮询 {@link BtctlReporter#profileSettled}，最多 12s，
 *       等到终态才给「已连接/已断开」，超时就如实报「状态没变化」。</li>
 * </ul>
 * 实测 A2DP 从 connect 发出到 CONNECTED 约 2.5s，一次性延迟必然读到旧值。
 */
public class DeviceDetailActivity extends AppCompatActivity {

    /** 打开详情页时传的设备地址。 */
    public static final String EXTRA_ADDRESS = "device_address";

    private static final String TAG = "BTControlDetail";
    /** BluetoothProfile.LE_AUDIO 的常量值，minSdk 27 上没有这个常量。 */
    private static final int PROFILE_LE_AUDIO = 22;

    private static final String PROFILE_A2DP = "a2dp";
    private static final String PROFILE_HFP = "hfp";
    private static final String PROFILE_LEAUDIO = "leaudio";

    /** 动作下发后轮询终态的间隔。 */
    private static final long POLL_INTERVAL_MS = 300;
    /** 等终态的上限；与 BluetoothOperationUtils 的 profile 看门狗保持同一量级。 */
    private static final long SETTLE_TIMEOUT_MS = 12_000;
    /** 广播防抖：一次状态切换会连着来好几条广播。 */
    private static final long REFRESH_DEBOUNCE_MS = 250;
    /**
     * 过渡态看门狗：广播到达的瞬间 adapter 读出来的可能还是上一次状态，
     * 之后再没有广播就会一直挂在「连接中…」，所以只要还有行处于 CONNECTING/DISCONNECTING
     * 就自己每 500ms 再刷一次，直到状态落定或超过 SETTLE_TIMEOUT_MS。
     */
    private static final long TRANSITION_POLL_MS = 500;
    /** 取消配对成功后停留时间，让用户看到 ✅ 文案再退回列表。 */
    private static final long FINISH_DELAY_MS = 1200;

    private String address;
    private BluetoothAdapter adapter;
    private BluetoothDevice device;
    private ExecutorService executorService;
    private BluetoothOperationUtils operationUtils;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 取消配对成功后自动退回列表（该设备已从已保存列表移除）。 */
    private final Runnable finishAfterUnpair = () -> {
        if (!isFinishing()) {
            finish();
        }
    };

    private MaterialToolbar toolbar;
    private TextView nameTextView;
    private TextView addressTextView;
    private TextView refreshedTextView;
    private TextView bannerTextView;
    private TextView messageTextView;
    private TextView a2dpStateTextView;
    private TextView hfpStateTextView;
    private TextView leAudioStateTextView;
    private Button cdmActionButton;
    private TextView cdmRowLabel;
    /** Shizuku 可用时 CDM 才能按 MAC 建关联；不可用时 CDM 行降级为「可选」。 */
    private boolean shizukuOk;
    /** 独立 Shizuku 行：授权/启动/安装都在这里，不再挂在 CDM 按钮上。 */
    private View shizukuRow;
    private TextView shizukuStatusTextView;
    /** 上次探测到的 Shizuku 状态串（ok / shizuku_not_running / ...），点击行时复用。 */
    private String shizukuStatus = "";
    private Button unpairButton;
    /** 整机连接/断开合并成一个按钮：按当前状态显示「连接」或「断开连接」。 */
    private Button wholeActionButton;
    private SwitchMaterial a2dpEnabledSwitch;
    private SwitchMaterial hfpEnabledSwitch;
    private SwitchMaterial leAudioEnabledSwitch;
    /** LE Audio 整行容器：手机和对端都支持才显示，否则整行藏起来。 */
    private View leAudioRow;
    /** 开关只是「允许该 profile 参与连接」的本地偏好，不触发任何蓝牙动作。 */
    private static final String PREFS_PROFILE = "detail_profile_switch";

    /** 正在等终态的 profile，键是 a2dp / hfp / leaudio；只在主线程读写。 */
    private final Set<String> busyProfiles = new HashSet<>();
    /** 每个 busy 行在等哪个方向：true=等连上，false=等断开；旋转后靠它重建轮询。 */
    private final Map<String, Boolean> busyExpectConnected = new LinkedHashMap<>();
    /** 整机动作进行中：所有按钮都不许再点。 */
    private boolean wholeBusy;
    private boolean wholeExpectConnected;

    /** 系统 CDM 关联确认框；结果回来只负责刷新页面，成功提示在 onAssociationCreated。 */
    private ActivityResultLauncher<IntentSenderRequest> cdmAssociateLauncher;

    private static final String STATE_BUSY_PROFILES = "busy_profiles";
    private static final String STATE_WHOLE_BUSY = "whole_busy";
    private static final String STATE_WHOLE_EXPECT = "whole_expect";
    private static final String STATE_MESSAGE = "detail_message";
    private static final String STATE_EXPECT_PREFIX = "expect_";

    private BroadcastReceiver stateReceiver;
    private final SimpleDateFormat refreshTimeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final Runnable refreshNow = this::refresh;
    /** 过渡态自己续命的轮询，被广播刷新时会顺延。 */
    private long transitionWatchdogUntil;
    private final Runnable transitionWatchdog = () -> {
        if (SystemClock.elapsedRealtime() > transitionWatchdogUntil) {
            return;
        }
        refresh();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_detail);

        // 必须在 STARTED 之前注册；放在 setContentView 之后、读状态之前
        cdmAssociateLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> refresh());

        address = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ADDRESS);
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        if (adapter != null && address != null) {
            try {
                device = adapter.getRemoteDevice(address);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "设备地址无效: " + address, e);
            }
        }

        executorService = Executors.newCachedThreadPool();
        operationUtils = new BluetoothOperationUtils(this, executorService,
                new BluetoothOperationUtils.UIUpdateCallback() {
                    @Override
                    public void updateStatus(String status) {
                        Log.d(TAG, status);
                    }

                    @Override
                    public void runOnUiThread(Runnable action) {
                        DeviceDetailActivity.this.runOnUiThread(action);
                    }
                });

        bindViews();
        bindActions();
        refresh();
        if (savedInstanceState != null) {
            restoreInFlightState(savedInstanceState);
        }
    }

    /** 旋转/进程重建后把「正在等终态」的操作接回来，别让页面看起来没事、其实断了追踪。 */
    private void restoreInFlightState(Bundle state) {
        String message = state.getString(STATE_MESSAGE);
        if (message != null && messageTextView != null) {
            messageTextView.setText(message);
        }
        wholeBusy = state.getBoolean(STATE_WHOLE_BUSY);
        wholeExpectConnected = state.getBoolean(STATE_WHOLE_EXPECT);

        // 重建时重新计时：旧页面剩下的时间不值得省，宁可多看 12s 也不误报超时
        long deadline = SystemClock.elapsedRealtime() + SETTLE_TIMEOUT_MS;
        ArrayList<String> pending = state.getStringArrayList(STATE_BUSY_PROFILES);
        if (pending != null) {
            for (String profile : pending) {
                boolean expect = state.getBoolean(STATE_EXPECT_PREFIX + profile, true);
                busyProfiles.add(profile);
                busyExpectConnected.put(profile, expect);
                applyProfileBusy(stateViewOf(profile), expect);
            }
        }
        if (wholeBusy) {
            if (busyProfiles.isEmpty()) {
                wholeBusy = false;
            } else {
                startWholeSettlePoll(wholeExpectConnected, deadline, new ArrayList<>(busyProfiles),
                        new LinkedHashMap<>());
            }
        }
        updateActionState();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (messageTextView != null && messageTextView.getText() != null) {
            outState.putString(STATE_MESSAGE, messageTextView.getText().toString());
        }
        outState.putBoolean(STATE_WHOLE_BUSY, wholeBusy);
        outState.putBoolean(STATE_WHOLE_EXPECT, wholeExpectConnected);
        outState.putStringArrayList(STATE_BUSY_PROFILES, new ArrayList<>(busyProfiles));
        for (Map.Entry<String, Boolean> entry : busyExpectConnected.entrySet()) {
            outState.putBoolean(STATE_EXPECT_PREFIX + entry.getKey(), entry.getValue());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 进页面到离开之间，状态变化靠广播推，不靠 onResume 的一次性采样
        registerStateReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Shizuku 状态变化只影响本页的 Shizuku 行与 CDM 行，进页面时探一次即可
        shizukuStatus = ShizukuBridge.status();
        shizukuOk = "ok".equals(shizukuStatus);
        refresh();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(transitionWatchdog);
        transitionWatchdogUntil = 0;
        unregisterStateReceiver();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }

    private void bindViews() {
        toolbar = findViewById(R.id.detailToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        // 无障碍/自动化：返回键没有文字，TalkBack 和 uiautomator 都认不出，补一个
        toolbar.setNavigationContentDescription(R.string.详情_无障碍返回);
        nameTextView = findViewById(R.id.detailNameTextView);
        addressTextView = findViewById(R.id.detailAddressTextView);
        refreshedTextView = findViewById(R.id.detailRefreshedTextView);
        bannerTextView = findViewById(R.id.detailBannerTextView);
        messageTextView = findViewById(R.id.detailMessageTextView);
        a2dpStateTextView = findViewById(R.id.a2dpStateTextView);
        hfpStateTextView = findViewById(R.id.hfpStateTextView);
        leAudioStateTextView = findViewById(R.id.leAudioStateTextView);
        wholeActionButton = findViewById(R.id.wholeActionButton);
        cdmActionButton = findViewById(R.id.cdmActionButton);
        cdmRowLabel = findViewById(R.id.cdmRowLabel);
        shizukuRow = findViewById(R.id.shizukuRow);
        shizukuStatusTextView = findViewById(R.id.shizukuStatusTextView);
        shizukuRow.setOnClickListener(v -> showShizukuGuide(ShizukuBridge.status()));
        unpairButton = findViewById(R.id.unpairButton);
        a2dpEnabledSwitch = findViewById(R.id.a2dpEnabledSwitch);
        hfpEnabledSwitch = findViewById(R.id.hfpEnabledSwitch);
        leAudioEnabledSwitch = findViewById(R.id.leAudioEnabledSwitch);
        leAudioRow = findViewById(R.id.leAudioRow);
        bindProfileSwitch(PROFILE_A2DP, a2dpEnabledSwitch);
        bindProfileSwitch(PROFILE_HFP, hfpEnabledSwitch);
        bindProfileSwitch(PROFILE_LEAUDIO, leAudioEnabledSwitch);
    }

    /**
     * 开关只记「这个 profile 允许连」的本地偏好，绝不调 connect——
     * 真正改变连接状态的是「连接 / 断开」两个按钮。
     */
    private void bindProfileSwitch(String profile, SwitchMaterial sw) {
        if (sw == null) {
            return;
        }
        sw.setChecked(isProfileEnabled(profile));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            setProfileEnabled(profile, isChecked);
            updateActionState();
        });
    }

    private String profileSwitchKey(String profile) {
        return (address == null ? "unknown" : address) + ":" + profile;
    }

    private boolean isProfileEnabled(String profile) {
        return getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE)
                .getBoolean(profileSwitchKey(profile), true);
    }

    private void setProfileEnabled(String profile, boolean enabled) {
        getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE)
                .edit().putBoolean(profileSwitchKey(profile), enabled).apply();
    }

    private void bindActions() {
        findViewById(R.id.wholeActionButton).setOnClickListener(v -> changeAll(!anyProfileConnected()));
        findViewById(R.id.copyJsonButton).setOnClickListener(v -> copySnapshot());
        if (cdmActionButton != null) {
            cdmActionButton.setOnClickListener(v -> onCdmAction());
        }
        if (unpairButton != null) {
            unpairButton.setOnClickListener(v -> confirmUnpair());
        }

        if (bannerTextView != null) {
            bannerTextView.setOnClickListener(v -> requestEnableBluetooth());
        }
    }

    // ---------------------------------------------------------------- 广播驱动

    /** 订阅和列表页同一组状态广播：配对、ACL、A2DP、HFP、LE Audio、蓝牙开关。 */
    private void registerStateReceiver() {
        if (stateReceiver != null) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        }
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (isForThisDevice(intent)) {
                    scheduleRefresh();
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stateReceiver, filter);
            }
        } catch (Throwable t) {
            Log.w(TAG, "注册状态广播失败，退回只在 onResume 刷新", t);
            stateReceiver = null;
        }
    }

    private void unregisterStateReceiver() {
        if (stateReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) {
            // 已经注销过
        }
        stateReceiver = null;
    }

    /** 广播里没带设备的（蓝牙开关）全刷；带设备 extra 的只刷当前这台。 */
    private boolean isForThisDevice(Intent intent) {
        BluetoothDevice changed;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        } else {
            //noinspection deprecation
            changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        }
        if (changed == null) {
            return true;
        }
        return address != null && address.equalsIgnoreCase(changed.getAddress());
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refreshNow);
        handler.postDelayed(refreshNow, REFRESH_DEBOUNCE_MS);
    }

    // ---------------------------------------------------------------- 页面刷新

    /** 刷新页面上的所有状态（进页面、广播、操作结束后都会调）。 */
    @SuppressLint("MissingPermission")
    private void refresh() {
        if (nameTextView == null) {
            return;
        }
        boolean hasDevice = device != null;
        boolean btOn = adapter != null && adapter.isEnabled();
        String name = null;
        String addrText = address == null || address.isEmpty() ? "-" : address;
        if (hasDevice) {
            try {
                name = device.getName();
            } catch (SecurityException e) {
                Log.w(TAG, "读设备名被拒", e);
            }
        }
        // 蓝牙关着时 getName() 往往拿不到，之前会把 MAC 当标题显示，跟地址行重复一遍
        boolean nameUsable = name != null && !name.isEmpty() && !name.equalsIgnoreCase(addrText);
        nameTextView.setText(nameUsable ? name : addrText);
        addressTextView.setText(addrText);
        addressTextView.setVisibility(nameUsable ? View.VISIBLE : View.GONE);

        boolean associated = hasDevice && BtctlReporter.hasAssociation(this, address);
        updateShizukuRow();
        if (cdmRowLabel != null) {
            // 没有 Shizuku 就只能靠系统选择器，而本机整机连接并不需要 CDM：降级成「可选」，别当主功能
            cdmRowLabel.setText(shizukuOk ? R.string.详情_CDM行 : R.string.详情_CDM行可选);
        }
        if (cdmActionButton != null) {
            cdmActionButton.setText(associated ? R.string.详情_取消关联 : R.string.详情_关联CDM);
            cdmActionButton.setContentDescription(getString(associated
                    ? R.string.详情_无障碍CDM取消
                    : R.string.详情_无障碍CDM关联));
        }

        refreshedTextView.setText(getString(R.string.详情_最后刷新,
                refreshTimeFormat.format(new Date())));

        updateBanner(hasDevice, btOn);

        // 正在等终态的行保留「连接中/断开中」，别被旧状态覆盖
        if (!busyProfiles.contains(PROFILE_A2DP)) {
            applyProfileState(a2dpStateTextView, BluetoothProfile.A2DP);
        }
        if (!busyProfiles.contains(PROFILE_HFP)) {
            applyProfileState(hfpStateTextView, BluetoothProfile.HEADSET);
        }
        if (!busyProfiles.contains(PROFILE_LEAUDIO)) {
            applyProfileState(leAudioStateTextView, PROFILE_LE_AUDIO);
        }

        if (isProfileConnected(PROFILE_LE_AUDIO)) {
            // 真连上过就是最硬的证据，以后这台音响一直露出 LE Audio 行
            LeAudioSupport.markPeer(this, address);
        }
        updateLeAudioRow();

        updateActionState();
        scheduleTransitionWatchdog();
    }

    /**
     * 只要还有 profile 在过渡态（含对端主动发起的 HFP SLC），就自己再刷一轮：
     * 广播只在状态切的那一下响，adapter 读到的值可能还差一拍，之后没人再叫我们就永久停在「连接中…」。
     */
    private void scheduleTransitionWatchdog() {
        handler.removeCallbacks(transitionWatchdog);
        if (!hasStackTransition()) {
            transitionWatchdogUntil = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (transitionWatchdogUntil == 0 || now > transitionWatchdogUntil) {
            transitionWatchdogUntil = now + SETTLE_TIMEOUT_MS;
        }
        handler.postDelayed(transitionWatchdog, TRANSITION_POLL_MS);
    }

    private boolean hasStackTransition() {
        if (adapter == null || !adapter.isEnabled()) {
            return false;
        }
        int[] profiles = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET, PROFILE_LE_AUDIO};
        for (int profile : profiles) {
            try {
                int state = adapter.getProfileConnectionState(profile);
                if (state == BluetoothProfile.STATE_CONNECTING
                        || state == BluetoothProfile.STATE_DISCONNECTING) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 个别机型对不支持的 profile 会抛，忽略即可
            }
        }
        return false;
    }

    /** 顶部提示条：蓝牙没开 / 地址非法时代替一堆「未知」。 */
    private void updateBanner(boolean hasDevice, boolean btOn) {
        if (bannerTextView == null) {
            return;
        }
        if (!btOn) {
            bannerTextView.setText(R.string.详情_蓝牙未开启);
            bannerTextView.setVisibility(View.VISIBLE);
        } else if (!hasDevice) {
            bannerTextView.setText(R.string.详情_地址无效);
            bannerTextView.setVisibility(View.VISIBLE);
        } else {
            bannerTextView.setVisibility(View.GONE);
        }
    }

    /** 按钮可用性：无设备/蓝牙关/整机忙/该行忙/系统不支持，一律禁掉。 */
    private void updateActionState() {
        boolean btOn = adapter != null && adapter.isEnabled();
        boolean deviceReady = device != null && btOn;
        boolean idle = deviceReady && !wholeBusy;
        // 开关能不能点看「这一行是否真的存在」，CDM 能不能点只看系统版本，两者别混
        boolean leAudioSupported = leAudioAvailable();
        boolean cdmSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;

        // 开关本身：设备可用且这行没在等终态时才可点；LE Audio 不支持时不给点
        setSwitchEnabled(a2dpEnabledSwitch, idle);
        setSwitchEnabled(hfpEnabledSwitch, idle);
        setSwitchEnabled(leAudioEnabledSwitch, idle && leAudioSupported);
        setButtonsEnabled(idle, R.id.wholeActionButton);
        updateWholeActionButton();

        // CDM 关联/取消关联要 Android 12+；取消配对要求真的已配对
        boolean bonded = false;
        if (device != null) {
            try {
                bonded = device.getBondState() == BluetoothDevice.BOND_BONDED;
            } catch (SecurityException ignored) {
                // 没 BLUETOOTH_CONNECT 时按未配对处理
            }
        }
        if (cdmActionButton != null) {
            cdmActionButton.setEnabled(idle && cdmSupported);
        }
        if (unpairButton != null) {
            unpairButton.setEnabled(idle && bonded);
        }
    }

    private void setButtonsEnabled(boolean enabled, int... ids) {
        for (int id : ids) {
            View v = findViewById(id);
            if (v != null) {
                v.setEnabled(enabled);
            }
        }
    }

    private void setSwitchEnabled(SwitchMaterial sw, boolean enabled) {
        if (sw != null) {
            sw.setEnabled(enabled);
        }
    }

    /** 整机按钮文案跟着真实状态走：有 profile 连着就显示「断开连接」，否则「连接」。 */
    private void updateWholeActionButton() {
        if (wholeActionButton == null) {
            return;
        }
        if (wholeBusy) {
            wholeActionButton.setText(wholeExpectConnected
                    ? R.string.详情_状态连接中
                    : R.string.详情_状态断开中);
            wholeActionButton.setContentDescription(getString(wholeExpectConnected
                    ? R.string.详情_无障碍整机连接中
                    : R.string.详情_无障碍整机断开中));
        } else if (anyProfileConnected()) {
            wholeActionButton.setText(R.string.详情_断开连接);
            wholeActionButton.setContentDescription(getString(R.string.详情_无障碍整机断开));
        } else {
            wholeActionButton.setText(R.string.详情_整机连接);
            wholeActionButton.setContentDescription(getString(R.string.详情_无障碍整机));
        }
    }

    /** 整机层面的「已连接」：A2DP / HFP / LE Audio 任意一路通就算。 */
    private boolean anyProfileConnected() {
        return isProfileConnected(BluetoothProfile.A2DP)
                || isProfileConnected(BluetoothProfile.HEADSET)
                || (leAudioAvailable() && isProfileConnected(PROFILE_LE_AUDIO));
    }

    /** 单个 profile 的行内状态：文案 + 语义色（绿=已连接，橙=过渡中，灰=断开/不支持）。 */
    private void applyProfileState(TextView view, int profile) {
        if (view == null) {
            return;
        }
        view.setText(profileStateTextRes(profile));
        view.setTextColor(getColor(profileStateColorRes(profile)));
    }

    private void applyProfileBusy(TextView view, boolean connect) {
        if (view == null) {
            return;
        }
        view.setText(connect ? R.string.详情_状态连接中 : R.string.详情_状态断开中);
        view.setTextColor(getColor(R.color.state_transition));
    }

    /** Android 33+ 才有 LE_AUDIO 常量，这里用数字兜底。 */
    private int profileStateTextRes(int profile) {
        if (adapter == null) {
            return R.string.详情_状态未知;
        }
        if (profile == PROFILE_LE_AUDIO && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return R.string.详情_不支持;
        }
        try {
            switch (adapter.getProfileConnectionState(profile)) {
                case BluetoothProfile.STATE_CONNECTED:
                    return R.string.详情_状态已连接;
                case BluetoothProfile.STATE_CONNECTING:
                    return R.string.详情_状态连接中;
                case BluetoothProfile.STATE_DISCONNECTED:
                case BluetoothProfile.STATE_DISCONNECTING:
                    return R.string.详情_状态已断开;
                default:
                    return R.string.详情_状态未知;
            }
        } catch (Throwable t) {
            Log.w(TAG, "读 profile 状态失败 profile=" + profile, t);
            return R.string.详情_状态未知;
        }
    }

    private int profileStateColorRes(int profile) {
        if (adapter == null || (profile == PROFILE_LE_AUDIO
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
            return R.color.state_disconnected;
        }
        try {
            switch (adapter.getProfileConnectionState(profile)) {
                case BluetoothProfile.STATE_CONNECTED:
                    return R.color.state_connected;
                case BluetoothProfile.STATE_CONNECTING:
                    return R.color.state_transition;
                default:
                    return R.color.state_disconnected;
            }
        } catch (Throwable t) {
            return R.color.state_disconnected;
        }
    }

    // ---------------------------------------------------------------- 单个 profile

    // ---------------------------------------------------------------- profile 工具

    private String profileLabel(String profile) {
        switch (profile) {
            case PROFILE_A2DP:
                return getString(R.string.详情_a2dp);
            case PROFILE_HFP:
                return getString(R.string.详情_hfp);
            default:
                return getString(R.string.详情_leaudio);
        }
    }

    /**
     * LE Audio 要两个条件同时成立才有意义：手机本身支持，且这台音响已知支持。
     * 对端没有公开 API 可问，只能靠「连上过」或「广播里见过 LE Audio 服务」来判断。
     */
    private boolean leAudioAvailable() {
        return LeAudioSupport.phoneSupports(adapter)
                && (LeAudioSupport.peerKnown(this, address) || isProfileConnected(PROFILE_LE_AUDIO));
    }

    /** LE Audio 行跟着可用性显隐，不用户点一个必然失败的开关。 */
    private void updateLeAudioRow() {
        if (leAudioRow != null) {
            leAudioRow.setVisibility(leAudioAvailable() ? View.VISIBLE : View.GONE);
        }
    }

    /** 这台机器上可能连的 profile，顺序固定；LE Audio 还要对端也支持。 */
    private List<String> supportedProfiles() {
        List<String> list = new ArrayList<>();
        list.add(PROFILE_A2DP);
        list.add(PROFILE_HFP);
        if (leAudioAvailable()) {
            list.add(PROFILE_LEAUDIO);
        }
        return list;
    }

    /** 连接只动开关开着的；断开永远对全体生效，免得留下关不掉的连接。 */
    private List<String> enabledProfiles() {
        List<String> list = new ArrayList<>();
        for (String profile : supportedProfiles()) {
            if (isProfileEnabled(profile)) {
                list.add(profile);
            }
        }
        return list;
    }

    /** 只把「还没到位」的 profile 放进目标：已经连上的不必再连，本来就没连的不必再断。 */
    private List<String> pendingProfiles(boolean connect) {
        List<String> candidates = connect ? enabledProfiles() : supportedProfiles();
        List<String> targets = new ArrayList<>();
        for (String profile : candidates) {
            boolean connected = isProfileConnected(profileIdOf(profile));
            if (connect ? !connected : connected) {
                targets.add(profile);
            }
        }
        return targets;
    }

    private String describeTargets(List<String> targets) {
        StringBuilder sb = new StringBuilder();
        for (String profile : targets) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(profileLabel(profile));
        }
        return sb.toString();
    }

    /**
     * 把一个 profile 的动作分派下去：HFP / LE Audio 有 Shizuku 就走 shell 通道
     * （普通 API 会撞 MODIFY_PHONE_STATE），没有就退回普通路径，把系统原话显示出来。
     */
    private void dispatchProfileAction(boolean connect, String profile,
                                       BluetoothOperationUtils.OperationCallback callback) {
        boolean shizukuReady = "ok".equals(ShizukuBridge.status());
        if (PROFILE_HFP.equals(profile) || PROFILE_LEAUDIO.equals(profile)) {
            if (!shizukuReady) {
                if (connect) {
                    operationUtils.connectProfile(device, profile, callback);
                } else {
                    operationUtils.disconnectProfile(device, profile, callback);
                }
            } else if (PROFILE_HFP.equals(profile)) {
                if (connect) {
                    operationUtils.connectHfpViaShizuku(device, callback);
                } else {
                    operationUtils.disconnectHfpViaShizuku(device, callback);
                }
            } else if (connect) {
                operationUtils.connectLeAudioViaShizuku(device, callback);
            } else {
                operationUtils.disconnectLeAudioViaShizuku(device, callback);
            }
            return;
        }
        if (connect) {
            operationUtils.connectProfile(device, PROFILE_A2DP, callback);
        } else {
            operationUtils.disconnectProfile(device, PROFILE_A2DP, callback);
        }
    }

    private TextView stateViewOf(String profile) {
        switch (profile) {
            case PROFILE_A2DP:
                return a2dpStateTextView;
            case PROFILE_HFP:
                return hfpStateTextView;
            case PROFILE_LEAUDIO:
                return leAudioStateTextView;
            default:
                return null;
        }
    }

    private int profileIdOf(String profile) {
        switch (profile) {
            case PROFILE_A2DP:
                return BluetoothProfile.A2DP;
            case PROFILE_HFP:
                return BluetoothProfile.HEADSET;
            default:
                return PROFILE_LE_AUDIO;
        }
    }

    // ---------------------------------------------------------------- 整机

    private void changeAll(final boolean connect) {
        if (device == null || wholeBusy) {
            return;
        }
        startWholeChange(connect);
    }

    /**
     * 整机动作＝按开关把多个 profile 一起下发，而不是调 device.connect()：
     * 「连哪些」由开关决定，HFP / LE Audio 还能走 Shizuku 通道。
     */
    private void startWholeChange(final boolean connect) {
        final List<String> targets = pendingProfiles(connect);
        if (targets.isEmpty()) {
            if (!connect) {
                showMessage(true, getString(R.string.详情_无需断开));
            } else if (enabledProfiles().isEmpty()) {
                showMessage(false, getString(R.string.详情_无启用项));
            } else {
                showMessage(true, getString(R.string.详情_无需连接));
            }
            return;
        }
        wholeBusy = true;
        wholeExpectConnected = connect;
        busyProfiles.clear();
        busyExpectConnected.clear();
        for (String profile : targets) {
            busyProfiles.add(profile);
            busyExpectConnected.put(profile, connect);
            applyProfileBusy(stateViewOf(profile), connect);
        }
        messageTextView.setText(getString(connect ? R.string.详情_连接中列表
                : R.string.详情_断开中列表, describeTargets(targets)));
        updateActionState();

        final AtomicInteger remaining = new AtomicInteger(targets.size());
        final Map<String, String> errors = Collections.synchronizedMap(new LinkedHashMap<>());
        final long deadline = SystemClock.elapsedRealtime() + SETTLE_TIMEOUT_MS;
        for (final String profile : targets) {
            dispatchProfileAction(connect, profile, new BluetoothOperationUtils.OperationCallback() {
                @Override
                public void onSuccess() {
                    settleOrFinish(errors, remaining, connect, targets, deadline);
                }

                @Override
                public void onError(String error) {
                    errors.put(profile, error);
                    settleOrFinish(errors, remaining, connect, targets, deadline);
                }
            });
        }
    }

    /**
     * 所有 profile 的 ack 都回来后决定下一步：失败的那些记下来，成功的那些继续等终态，
     * 这样「A2DP 连上了、LE Audio 被系统拒了」能一句话说清，而不是只报一半。
     */
    private void settleOrFinish(final Map<String, String> errors, final AtomicInteger remaining,
                                final boolean connect, final List<String> targets,
                                final long deadline) {
        if (remaining.decrementAndGet() > 0) {
            return;
        }
        final List<String> ok = new ArrayList<>();
        for (String profile : targets) {
            if (!errors.containsKey(profile)) {
                ok.add(profile);
            }
        }
        runOnUiThread(() -> {
            if (ok.isEmpty()) {
                finishWholeOperation(false, getString(R.string.详情_部分失败, describeErrors(errors)));
                return;
            }
            messageTextView.setText(R.string.详情_整机等待确认);
            startWholeSettlePoll(connect, deadline, ok, errors);
        });
    }

    private String describeErrors(Map<String, String> errors) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : errors.entrySet()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(profileLabel(entry.getKey())).append("：").append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * 终态：目标里每个 profile 都到位才算成功，任一不到位就等到 12s 超时如实报。
     * 有失败项时把「谁到了、谁没到」一次说全。
     */
    private void startWholeSettlePoll(final boolean expectConnected, final long deadlineElapsed,
                                      final List<String> targets, final Map<String, String> errors) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                boolean allSettled = true;
                for (String profile : targets) {
                    if (!BtctlReporter.profileSettled(DeviceDetailActivity.this, address,
                            profile, expectConnected)) {
                        allSettled = false;
                        break;
                    }
                }
                String detail = describeTargets(targets);
                if (allSettled) {
                    if (errors.isEmpty()) {
                        finishWholeOperation(true, expectConnected
                                ? getString(R.string.详情_整机已连接, detail)
                                : getString(R.string.详情_整机已断开, detail));
                    } else {
                        finishWholeOperation(false, getString(R.string.详情_部分失败,
                                describeErrors(errors)) + "；"
                                + getString(R.string.详情_其余已到位, detail));
                    }
                } else if (SystemClock.elapsedRealtime() < deadlineElapsed) {
                    handler.postDelayed(this, POLL_INTERVAL_MS);
                } else if (errors.isEmpty()) {
                    finishWholeOperation(false, getString(R.string.详情_整机超时));
                } else {
                    finishWholeOperation(false, getString(R.string.详情_部分失败,
                            describeErrors(errors)) + "；" + getString(R.string.详情_整机超时));
                }
            }
        }, POLL_INTERVAL_MS);
    }

    private boolean isProfileConnected(int profile) {
        if (adapter == null) {
            return false;
        }
        try {
            return adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED;
        } catch (Throwable t) {
            Log.w(TAG, "读 profile 连接状态失败 profile=" + profile, t);
            return false;
        }
    }

    private void finishWholeOperation(boolean success, String message) {
        runOnUiThread(() -> {
            wholeBusy = false;
            busyProfiles.clear();
            busyExpectConnected.clear();
            messageTextView.setText((success ? "✅ " : "⚠️ ") + message);
            updateActionState();
            refresh();
        });
    }

    // ---------------------------------------------------------------- CDM / 配对

    /** 「设备操作」主按钮：已关联就取消，未关联就发起系统关联流程。 */
    @SuppressLint("MissingPermission")
    private void onCdmAction() {
        if (device == null || address == null) {
            Toast.makeText(this, R.string.详情_地址无效, Toast.LENGTH_SHORT).show();
            return;
        }
        if (BtctlReporter.hasAssociation(this, address)) {
            confirmDisassociate();
        } else {
            startCdmAssociation();
        }
    }

    private void confirmDisassociate() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.详情_取消关联标题)
                .setMessage(R.string.详情_取消关联正文)
                .setNegativeButton(R.string.详情_取消, null)
                .setPositiveButton(R.string.详情_取消关联, (d, which) -> {
                    boolean ok = BtctlReporter.disassociate(this, address);
                    Log.d(TAG, "取消 CDM 关联 " + address + " ok=" + ok);
                    showMessage(ok, getString(ok ? R.string.详情_已取消关联 : R.string.详情_取消关联失败));
                    refresh();
                })
                .show();
        tintDestructiveButton(dialog);
    }

    /**
     * 发起 CDM 关联：有 Shizuku 就走 shell 按 MAC 直接建，没有才退回系统选择器。
     *
     * <p>选择器只列「正在广播」的设备，而设备连上 A2DP 后就不再可发现，所以按 MAC 建关联
     * 才是可靠路径；shell 命令为 {@code cmd companiondevice associate <userId> <pkg> <mac>}。
     */
    private void startCdmAssociation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, R.string.详情_关联不可用, Toast.LENGTH_SHORT).show();
            return;
        }
        if (address == null) {
            Toast.makeText(this, R.string.详情_地址无效, Toast.LENGTH_SHORT).show();
            return;
        }
        if ("ok".equals(ShizukuBridge.status())) {
            showMessage(true, getString(R.string.详情_关联中));
            executorService.execute(() -> {
                String result = ShizukuBridge.associateCompanionDevice(this, address);
                runOnUiThread(() -> {
                    if (result.startsWith("ok")) {
                        Log.i(TAG, "Shizuku 按 MAC 关联成功: " + address);
                        showMessage(true, getString(R.string.详情_关联成功));
                        refresh();
                    } else {
                        Log.w(TAG, "Shizuku 按 MAC 关联失败，退回选择器: " + result);
                        showMessage(false, getString(R.string.详情_关联失败, result));
                        Toast.makeText(this, R.string.详情_关联选择器, Toast.LENGTH_SHORT).show();
                        launchCdmChooser();
                    }
                });
            });
            return;
        }
        // 没有 Shizuku：引导入口统一在「Shizuku」行，这里只说明原因，不重复弹授权框
        showMessage(false, getString(R.string.详情_先授权Shizuku));
    }

    /**
     * 独立的 Shizuku 行：把「服务在不在、有没有授权」直接写出来。
     *
     * <p>Shizuku 是本页两件事的共用前置条件（按 MAC 建 CDM 关联、主动连 HFP / LE Audio），
     * 所以状态展示放自己一行，而不是塞进 CDM 按钮里。
     */
    private void updateShizukuRow() {
        if (shizukuStatusTextView == null) {
            return;
        }
        int textRes;
        int colorRes;
        if ("ok".equals(shizukuStatus)) {
            textRes = R.string.详情_Shizuku已授权;
            colorRes = R.color.state_connected;
        } else if (shizukuStatus.startsWith("shizuku_not_running")) {
            textRes = R.string.详情_Shizuku状态未运行;
            colorRes = R.color.state_transition;
        } else if (shizukuStatus.startsWith("shizuku_unauthorized")) {
            textRes = R.string.详情_Shizuku状态未授权;
            colorRes = R.color.state_transition;
        } else if (shizukuStatus.startsWith("shizuku_too_old")) {
            textRes = R.string.详情_Shizuku状态太旧;
            colorRes = R.color.state_disconnected;
        } else {
            textRes = R.string.详情_Shizuku状态不可用;
            colorRes = R.color.state_disconnected;
        }
        shizukuStatusTextView.setText(textRes);
        shizukuStatusTextView.setTextColor(ContextCompat.getColor(this, colorRes));
    }

    /**
     * Shizuku 没就绪时给一条可执行的路：装它、启动服务、授权，或者坚持用系统选择器。
     *
     * <p>注意 Shizuku 的服务端必须由 adb / root 以 shell 身份拉起，应用自己无法凭空获得
     * shell 权限，所以这里只能引导，不能代替用户启动。
     */
    private void showShizukuGuide(String status) {
        int messageRes;
        if (status.startsWith("shizuku_unauthorized")) {
            messageRes = R.string.详情_Shizuku未授权;
        } else if (status.startsWith("shizuku_too_old")) {
            messageRes = R.string.详情_Shizuku太旧;
        } else if (status.startsWith("shizuku_not_running")) {
            messageRes = R.string.详情_Shizuku未运行;
        } else {
            messageRes = R.string.详情_Shizuku不可用;
        }
        if ("ok".equals(status)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.详情_Shizuku就绪标题)
                    .setMessage(R.string.详情_Shizuku已就绪)
                    .setPositiveButton(R.string.详情_关闭, null)
                    .show();
            return;
        }
        boolean running = !status.startsWith("shizuku_not_running")
                && !status.startsWith("shizuku_unavailable");
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.详情_Shizuku标题)
                .setMessage(messageRes)
                .setNeutralButton(R.string.详情_仍用选择器, (d, which) -> {
                    showMessage(false, chooserHintText());
                    launchCdmChooser();
                })
                .setNegativeButton(R.string.详情_取消, null);
        if (running) {
            builder.setPositiveButton(R.string.详情_请求授权, (d, which) -> {
                ShizukuBridge.requestPermission();
                showMessage(false, getString(R.string.详情_授权后重试));
            });
        } else {
            builder.setPositiveButton(R.string.详情_安装Shizuku,
                    (d, which) -> openShizukuHome());
        }
        builder.show();
    }

    /** 打开 Shizuku 的发布页，用户照着装/更新。 */
    private void openShizukuHome() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/RikkaApps/Shizuku/releases")));
        } catch (Throwable t) {
            Log.w(TAG, "打不开 Shizuku 发布页", t);
            Toast.makeText(this, R.string.详情_安装Shizuku失败, Toast.LENGTH_SHORT).show();
        }
    }

    /** 没有 Shizuku 时，系统选择器为什么找不到这台设备：文案随「对端是否已连接」变化。 */
    private String chooserHintText() {
        boolean connected = isProfileConnected(BluetoothProfile.A2DP)
                || isProfileConnected(BluetoothProfile.HEADSET)
                || isProfileConnected(PROFILE_LE_AUDIO);
        return getString(connected
                ? R.string.详情_关联选择器已连接
                : R.string.详情_关联选择器提示);
    }

    /** 公开 API 的关联流程：只能加过滤器，由系统选择器让用户挑设备。 */
    @SuppressLint("MissingPermission")
    private void launchCdmChooser() {
        CompanionDeviceManager cdm = getSystemService(CompanionDeviceManager.class);
        if (cdm == null) {
            Toast.makeText(this, R.string.详情_关联不可用, Toast.LENGTH_SHORT).show();
            return;
        }
        String displayName = address;
        try {
            String name = device.getName();
            if (name != null && !name.isEmpty()) {
                displayName = name;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "读设备名被拒，用地址做显示名", e);
        }
        AssociationRequest request = new AssociationRequest.Builder()
                .addDeviceFilter(new BluetoothDeviceFilter.Builder().build())
                .setSingleDevice(true)
                .setDisplayName(displayName)
                .build();
        CompanionDeviceManager.Callback callback = new CompanionDeviceManager.Callback() {
            @Override
            public void onDeviceFound(IntentSender chooserLauncher) {
                try {
                    cdmAssociateLauncher.launch(
                            new IntentSenderRequest.Builder(chooserLauncher).build());
                } catch (Throwable t) {
                    Log.w(TAG, "拉起 CDM 选择器失败", t);
                    showMessage(false, getString(R.string.详情_关联失败, String.valueOf(t)));
                }
            }

            @Override
            public void onAssociationCreated(AssociationInfo associationInfo) {
                // 公开 API 只能靠系统选择器，用户可能挑了别的设备：校验 MAC，别把这台记成已关联
                String associatedMac = associationInfo.getDeviceMacAddress() == null
                        ? null : associationInfo.getDeviceMacAddress().toString();
                if (address != null && associatedMac != null && !address.equalsIgnoreCase(associatedMac)) {
                    Log.w(TAG, "CDM 选择器挑中了别的设备 " + associatedMac + "，撤销");
                    BtctlReporter.disassociate(DeviceDetailActivity.this, associatedMac);
                    showMessage(false, getString(R.string.详情_关联设备不匹配, associatedMac));
                } else {
                    Log.d(TAG, "CDM 关联成功: " + associationInfo);
                    showMessage(true, getString(R.string.详情_关联成功));
                }
                refresh();
            }

            @Override
            public void onFailure(CharSequence error) {
                Log.w(TAG, "CDM 关联失败: " + error);
                if ("ok".equals(ShizukuBridge.status())) {
                    showMessage(false, getString(R.string.详情_关联失败, String.valueOf(error)));
                } else {
                    // 选择器里根本没有这台设备（对端已连接不广播），把原因说清楚比抛 canceled 有用
                    showMessage(false, chooserHintText());
                }
            }
        };
        try {
            cdm.associate(request, callback, null);
        } catch (Throwable t) {
            Log.w(TAG, "CDM 关联异常", t);
            showMessage(false, getString(R.string.详情_关联失败, String.valueOf(t)));
        }
    }

    @SuppressLint("MissingPermission")
    private void confirmUnpair() {
        if (device == null) {
            return;
        }
        String name = null;
        try {
            name = device.getName();
        } catch (SecurityException ignored) {
            // 拿不到名字就显示地址
        }
        String label = name == null || name.isEmpty() ? address : name;
        AlertDialog lastDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.详情_取消配对标题)
                .setMessage(getString(R.string.详情_取消配对正文, label))
                .setNegativeButton(R.string.详情_取消, null)
                .setPositiveButton(R.string.详情_取消配对, (dialog, which) -> unpair())
                .show();
        tintDestructiveButton(lastDialog);
    }

    /** 破坏性确认框的确认按钮改用红色，避免和普通主色按钮混淆。 */
    private void tintDestructiveButton(AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(ContextCompat.getColor(this, R.color.action_destructive));
    }

    private void unpair() {
        if (device == null) {
            return;
        }
        // 回调在工作线程：refresh()/showMessage 都要落到主线程，否则会抛
        // CalledFromWrongThreadException 被工具类 catch 成「取消配对失败」（取消其实已成功）
        operationUtils.unpairDevice(device, new BluetoothOperationUtils.OperationCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    showMessage(true, getString(R.string.详情_已取消配对));
                    refresh();
                    // 解绑后这台设备已从「已保存设备」移除，详情页留着就是幽灵页：给 1.2s 看文案再退回列表
                    handler.postDelayed(finishAfterUnpair, FINISH_DELAY_MS);
                });
            }

            @Override
            public void onError(String error) {
                String detail = String.valueOf(error);
                runOnUiThread(() -> showMessage(false, getString(R.string.详情_取消配对失败, detail)));
            }
        });
    }

    /** 底部提示行统一 ✅ / ⚠️ 前缀，和 profile 操作保持一致。 */
    private void showMessage(boolean success, String message) {
        runOnUiThread(() -> {
            if (messageTextView != null) {
                messageTextView.setText((success ? "✅ " : "⚠️ ") + message);
            }
        });
    }

    // ---------------------------------------------------------------- 其它

    private void requestEnableBluetooth() {
        if (adapter == null || adapter.isEnabled()) {
            return;
        }
        try {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (Throwable t) {
            Log.w(TAG, "拉起蓝牙开关失败", t);
            Toast.makeText(this, R.string.详情_蓝牙未开启, Toast.LENGTH_SHORT).show();
        }
    }

    private void copySnapshot() {
        JSONObject snapshot = BtctlReporter.snapshot(this, "ui", "state", address, true, "", 0);
        String json = snapshot == null ? "{}" : snapshot.toString();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("BTCTL RESULT", json));
        }
        Toast.makeText(this, R.string.详情_快照已复制, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "detail snapshot " + json);
    }

    /** 从列表行点进来时的入口。 */
    public static void start(@NonNull Context context, String deviceAddress) {
        android.content.Intent intent = new android.content.Intent(context, DeviceDetailActivity.class);
        intent.putExtra(EXTRA_ADDRESS, deviceAddress);
        context.startActivity(intent);
    }
}
