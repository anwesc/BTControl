package com.autotest.BTControl;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 蓝牙控制无障碍服务
 * 主要功能：
 * 1. 自动处理蓝牙配对请求
 * 2. 处理系统弹窗（如USB连接、音量警告等）
 * 3. 保持设备唤醒和屏幕常亮
 * 4. 监控服务状态，自动重启异常停止的服务
 */
public class MyAccessibilityService extends AccessibilityService {

    private static final String TAG = "BTControl:AccessibilityService";
    
    // 通知相关常量
    private static final int FOREGROUND_NOTIFICATION_ID = 2001;
    private static final String CHANNEL_ID = "BTControlServiceChannel";
    private static final String CHANNEL_NAME = "蓝牙控制服务";
    
    // 时间相关常量
    private static final long EVENT_DEBOUNCE_TIME = 1000; // 事件防抖时间（毫秒）
    private static final long WAKELOCK_TIMEOUT = 10 * 60 * 1000L; // WakeLock超时时间（10分钟）
    private static final long OVERLAY_REMOVE_DELAY = 1000; // 覆盖层移除延迟（毫秒）
    private static final long MONITOR_INTERVAL = 5000; // 服务监控间隔（毫秒）
    private static final long CLICK_DELAY_BASE = 1000; // 点击延迟基础时间（毫秒）

    // 资源管理
    private PowerManager.WakeLock wakeLock;
    private View overlayView;
    private KeyguardManager.KeyguardLock keyguardLock;
    
    // 线程管理
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // 状态跟踪
    private long lastEventTime = 0;
    private String lastNodeText;
    private boolean isServiceRunning = false;
    private final String deviceBrand = Build.BRAND;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "服务创建 - 设备品牌: " + deviceBrand);
    }

    @SuppressLint({"Wakelock", "ObsoleteSdkInt", "WakelockTimeout"})
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "无障碍服务已连接");
        
        isServiceRunning = true;
        
        // 初始化服务
        initializeService();
        
        // 启动服务监控
        startServiceMonitoring();
    }

    /**
     * 初始化服务组件
     */
    private void initializeService() {
        try {
            // 启动前台服务
            startForegroundService();
            
            // 解除屏幕锁定
            disableKeyguard();
            
            // 获取WakeLock，防止CPU休眠
            acquireWakeLock();
            
            // 保持屏幕常亮
            keepScreenOn();
            
            Log.d(TAG, "服务初始化完成");
        } catch (Exception e) {
            Log.e(TAG, "服务初始化失败", e);
        }
    }

    /**
     * 解除屏幕锁定
     */
    @SuppressLint("ObsoleteSdkInt")
    private void disableKeyguard() {
        try {
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                keyguardLock = keyguardManager.newKeyguardLock(TAG + ":KeyguardLock");
                keyguardLock.disableKeyguard();
                Log.d(TAG, "键盘锁已禁用");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "禁用键盘锁失败", e);
        }
    }

    /**
     * 获取WakeLock，防止CPU休眠
     */
    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        TAG + ":WakeLock"
                );
                wakeLock.acquire(WAKELOCK_TIMEOUT);
                Log.d(TAG, "WakeLock已获取");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "获取WakeLock失败", e);
        }
    }

    /**
     * 通过WindowManager设置屏幕常亮
     */
    private void keepScreenOn() {
        try {
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e(TAG, "WindowManager为空");
                return;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    android.graphics.PixelFormat.TRANSLUCENT
            );

            params.gravity = Gravity.TOP;

            overlayView = new LinearLayout(this);
            overlayView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
            ));

            windowManager.addView(overlayView, params);
            Log.d(TAG, "屏幕通过WindowManager覆盖保持开启状态");

            // 延迟移除遮罩
            mainHandler.postDelayed(this::removeOverlayView, OVERLAY_REMOVE_DELAY);
        } catch (Exception e) {
            Log.e(TAG, "设置屏幕常亮失败", e);
        }
    }

    /**
     * 移除覆盖视图
     */
    private void removeOverlayView() {
        try {
            if (overlayView != null) {
                WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (windowManager != null) {
                    windowManager.removeView(overlayView);
                    overlayView = null;
                    Log.d(TAG, "覆盖视图已移除");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "移除覆盖视图失败", e);
        }
    }

    /**
     * 启动前台服务，减少被系统杀死的可能性
     */
    @SuppressLint({"ObsoleteSdkInt", "ForegroundServiceType", "NewApi"})
    private void startForegroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel();
            }

            Notification notification = createNotification();
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification, 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            }
            
            Log.d(TAG, "前台服务已启动");
        } catch (Exception e) {
            Log.e(TAG, "启动前台服务失败", e);
        }
    }

    /**
     * 创建通知渠道（Android 8.0及以上需要）
     */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
        );
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * 创建通知
     */
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("蓝牙控制服务")
                .setContentText("无障碍服务运行中...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }

    /**
     * 启动服务监控
     */
    private void startServiceMonitoring() {
        monitorExecutor.scheduleWithFixedDelay(() -> {
            try {
                AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
                if (am == null || !am.isEnabled() || !isServiceRunning) {
                    Log.w(TAG, "无障碍服务异常停止，尝试重启...");
                    restartService();
                }
            } catch (Exception e) {
                Log.e(TAG, "监控服务状态时出错", e);
            }
        }, MONITOR_INTERVAL, MONITOR_INTERVAL, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !isServiceRunning) return;

        try {
            // 过滤掉自身应用的事件
            String packageName = String.valueOf(event.getPackageName());
            if (packageName.equals("com.autotest.BTControl")) return;
            
            Log.d(TAG + "/Package", "事件包名: " + packageName);
            Log.d(TAG, "事件类型: " + event.getEventType());

            // 只处理特定类型的事件
            if (!isTargetEventType(event.getEventType())) {
                return;
            }

            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) return;

            // 防抖处理
            if (isDuplicateEvent(rootNode)) {
                Log.d(TAG, "跳过重复事件");
                return;
            }

            // 更新事件跟踪
            updateEventTracking(rootNode);

            // 处理窗口变化事件
            handleWindowChangeEvent(rootNode);

        } catch (Exception e) {
            Log.e(TAG, "处理无障碍事件时出错", e);
        }
    }

    /**
     * 判断是否为目标事件类型
     */
    private boolean isTargetEventType(int eventType) {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
               eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
               eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED;
    }

    /**
     * 判断是否为重复事件
     */
    private boolean isDuplicateEvent(AccessibilityNodeInfo rootNode) {
        long currentTime = System.currentTimeMillis();
        String currentNodeText = getRootChildText(rootNode);

        return currentTime - lastEventTime < EVENT_DEBOUNCE_TIME &&
                Objects.equals(currentNodeText, lastNodeText);
    }

    /**
     * 更新事件跟踪
     */
    private void updateEventTracking(AccessibilityNodeInfo rootNode) {
        lastEventTime = System.currentTimeMillis();
        lastNodeText = getRootChildText(rootNode);
    }

    /**
     * 获取根节点子节点的文本
     */
    private String getRootChildText(AccessibilityNodeInfo node) {
        if (node == null) return null;

        try {
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo childNode = node.getChild(i);
                if (childNode != null) {
                    CharSequence text = childNode.getText();
                    if (text != null) {
                        return text.toString();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取根节点子节点文本时出错", e);
        }
        
        return null;
    }

    /**
     * 处理窗口变化事件
     */
    private void handleWindowChangeEvent(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return;

        try {
            // 处理蓝牙配对
            handleBluetoothPairing(rootNode);
            
            // 处理其他系统弹窗
            handleSystemDialogs(rootNode);
        } catch (Exception e) {
            Log.e(TAG, "处理窗口变化事件时出错", e);
        }
    }

    /**
     * 处理蓝牙配对请求
     */
    private void handleBluetoothPairing(AccessibilityNodeInfo rootNode) {
        executor.execute(() -> {
            try {
                List<AccessibilityNodeInfo> pairNodes = rootNode.findAccessibilityNodeInfosByText("配对");
                for (AccessibilityNodeInfo pairNode : pairNodes) {
                    CharSequence text = pairNode.getText();
                    if (text != null && isValidPairingButton(text.toString()) && pairNode.isClickable()) {
                        mainHandler.postDelayed(() -> {
                            try {
                                boolean result = pairNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                Log.d(TAG, "点击'配对'按钮: " + text + ", 结果: " + result);
                            } catch (Exception e) {
                                Log.e(TAG, "点击配对按钮时出错", e);
                            }
                        }, CLICK_DELAY_BASE);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "处理蓝牙配对时出错", e);
            }
        });
    }

    /**
     * 判断是否为有效的配对按钮
     */
    private boolean isValidPairingButton(String buttonText) {
        return buttonText.contains("配对") && 
               !buttonText.matches(".*(已|取消|与|通讯录|请求).*");
    }

    /**
     * 处理其他系统弹窗
     */
    private void handleSystemDialogs(AccessibilityNodeInfo rootNode) {
        // 处理音量警告弹窗
        handleVolumeWarningDialog(rootNode);
        
        // 处理USB连接弹窗
        handleUsbConnectionDialog(rootNode);
    }

    /**
     * 处理音量警告弹窗
     */
    private void handleVolumeWarningDialog(AccessibilityNodeInfo rootNode) {
        executor.execute(() -> {
            try {
                List<AccessibilityNodeInfo> warningNodes = rootNode.findAccessibilityNodeInfosByText("长时间保持高音量可能会损伤听力");
                if (!warningNodes.isEmpty()) {
                    List<AccessibilityNodeInfo> confirmNodes = rootNode.findAccessibilityNodeInfosByText("确定");
                    for (AccessibilityNodeInfo confirmNode : confirmNodes) {
                        if (confirmNode.isClickable()) {
                            mainHandler.postDelayed(() -> {
                                try {
                                    boolean result = confirmNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    Log.d(TAG, "点击音量警告'确定'按钮，结果: " + result);
                                } catch (Exception e) {
                                    Log.e(TAG, "点击音量警告确定按钮时出错", e);
                                }
                            }, CLICK_DELAY_BASE);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "处理音量警告弹窗时出错", e);
            }
        });
    }

    /**
     * 处理USB连接弹窗
     */
    private void handleUsbConnectionDialog(AccessibilityNodeInfo rootNode) {
        executor.execute(() -> {
            try {
                // 处理USB用途选择弹窗
                List<AccessibilityNodeInfo> usbNodes = rootNode.findAccessibilityNodeInfosByText("USB 用于");
                for (AccessibilityNodeInfo node : usbNodes) {
                    CharSequence text = node.getText();
                    if (text != null && text.toString().contains("USB 用于")) {
                        mainHandler.postDelayed(() -> {
                            try {
                                performGlobalAction(GLOBAL_ACTION_BACK);
                                Log.d(TAG, "关闭USB用途选择弹窗: " + text);
                            } catch (Exception e) {
                                Log.e(TAG, "关闭USB用途选择弹窗时出错", e);
                            }
                        }, CLICK_DELAY_BASE * 2);
                    }
                }
                
                // 处理USB连接方式选择弹窗
                List<AccessibilityNodeInfo> usbConnectNodes = rootNode.findAccessibilityNodeInfosByText("USB 连接方式");
                if (!usbConnectNodes.isEmpty()) {
                    List<AccessibilityNodeInfo> cancelNodes = rootNode.findAccessibilityNodeInfosByText("取消");
                    for (AccessibilityNodeInfo cancelNode : cancelNodes) {
                        if (cancelNode.isClickable()) {
                            mainHandler.postDelayed(() -> {
                                try {
                                    boolean result = cancelNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    Log.d(TAG, "点击USB连接方式'取消'按钮，结果: " + result);
                                } catch (Exception e) {
                                    Log.e(TAG, "点击USB连接方式取消按钮时出错", e);
                                }
                            }, CLICK_DELAY_BASE * 2);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "处理USB连接弹窗时出错", e);
            }
        });
    }

    /**
     * 重新启动无障碍服务
     */
    private void restartService() {
        try {
            ComponentName componentName = new ComponentName(this, MyAccessibilityService.class);
            PackageManager pm = getPackageManager();

            // 先禁用组件
            pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );

            // 再启用组件
            pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );

            Log.d(TAG, "无障碍服务重启请求已发送");
        } catch (Exception e) {
            Log.e(TAG, "重启无障碍服务时出错", e);
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "无障碍服务被中断");
        isServiceRunning = false;
        
        // 尝试重启服务
        restartService();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "无障碍服务正在销毁");
        isServiceRunning = false;
        
        // 释放资源
        releaseResources();
        
        // 停止监控
        stopServiceMonitoring();
        
        super.onDestroy();
    }

    /**
     * 释放资源
     */
    private void releaseResources() {
        try {
            // 释放WakeLock
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
                Log.d(TAG, "WakeLock已释放");
            }

            // 移除覆盖视图
            removeOverlayView();

            // 重新启用键盘锁
            if (keyguardLock != null) {
                try {
                    keyguardLock.reenableKeyguard();
                    keyguardLock = null;
                    Log.d(TAG, "键盘锁已重新启用");
                } catch (Exception e) {
                    Log.e(TAG, "重新启用键盘锁时出错", e);
                }
            }

            // 关闭线程池
            if (!executor.isShutdown()) {
                executor.shutdown();
                Log.d(TAG, "执行器已关闭");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "释放资源时出错", e);
        }
    }

    /**
     * 停止服务监控
     */
    private void stopServiceMonitoring() {
        try {
            if (!monitorExecutor.isShutdown()) {
                monitorExecutor.shutdown();
                Log.d(TAG, "监控执行器已关闭");
            }
        } catch (Exception e) {
            Log.e(TAG, "停止服务监控时出错", e);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "任务被移除，尝试重启服务");
        
        // 重启服务，保持服务活跃
        try {
            Intent restartService = new Intent(getApplicationContext(), MyAccessibilityService.class);
            startService(restartService);
        } catch (Exception e) {
            Log.e(TAG, "重启服务时出错", e);
        }
    }
}
