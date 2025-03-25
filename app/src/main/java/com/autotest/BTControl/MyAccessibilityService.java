package com.autotest.BTControl;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;
import android.widget.LinearLayout;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityManager;



import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MyAccessibilityService extends AccessibilityService {

    private static final String TAG = "BTControl:(MyAccessibilityService)";
    private PowerManager.WakeLock wakeLock;
    private View overlayView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastEventTime = 0;
    private static String lastNodeText;
    private static final long EVENT_DEBOUNCE_TIME = 1000; // 1000ms 防抖
    private static final int FOREGROUND_NOTIFICATION_ID = 2001;
    private static final String CHANNEL_ID = "BTControlServiceChannel";
    String brand = Build.BRAND;

    @SuppressLint({"Wakelock", "ObsoleteSdkInt", "WakelockTimeout"})
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "手机品牌: " + brand);
        Log.d(TAG, "Service Connected");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(); // 启动前台服务
        }

        // 1. 解除屏幕锁定
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null) {
            KeyguardManager.KeyguardLock keyguardLock = keyguardManager.newKeyguardLock("BTControl:KeyguardLock");
            keyguardLock.disableKeyguard();
            Log.d(TAG, "键盘锁已禁用：解锁屏幕.");
        }

        // 2. 申请 WakeLock，防止 CPU 进入休眠
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "BTControl:AccessibilityWakeLock"
            );
            wakeLock.acquire();
            Log.d(TAG, "已获取 WakeLock：防止 CPU 休眠.");
        }

        // 3. 通过 WindowManager 让屏幕保持亮起
        keepScreenOn();

        // 启动无障碍服务监控
        monitorAccessibilityService();
    }


    /**
     * 启动前台服务，减少被系统杀死的可能性
     */
    @SuppressLint({"ObsoleteSdkInt", "ForegroundServiceType", "NewApi"})
    private void startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 在 Android 8.0 (API 26) 及以上版本创建通知频道
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "BTControl Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }

            // 创建通知
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("蓝牙控制服务")
                    .setContentText("无障碍服务运行中...")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build();

            // 启动前台服务
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 在 Android 9 (API 28) 及以上版本，前台服务需要指定服务类型
                startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            }

            Log.d(TAG, "前台服务已启动");
        } else {
            // 如果版本低于 Android 8.0 (API 26)，直接启动前台服务
            Notification notification = new Notification.Builder(this)
                    .setContentTitle("蓝牙控制服务")
                    .setContentText("无障碍服务运行中...")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build();
            startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            Log.d(TAG, "前台服务已启动");
        }
    }



    /**
     * 通过 WindowManager 设置屏幕常亮
     */
    private void keepScreenOn() {
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
//        overlayView.setBackgroundColor(0x55000000); // 设置半透明背景

        windowManager.addView(overlayView, params);
        Log.d(TAG, "屏幕通过 WindowManager 覆盖保持开启状态.");

        // 1秒后移除遮罩
        mainHandler.postDelayed(() -> {
            if (overlayView != null) {
                windowManager.removeView(overlayView);
                overlayView = null;
                Log.d(TAG, "1s后移除遮罩.");
            }
        }, 1000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();
        String packageName = String.valueOf(event.getPackageName());
        if (packageName.equals("com.autotest.BTControl")) return;
        Log.d(TAG + "/packageNames", "当前事件的包名: " + packageName);
        Log.d(TAG, "事件类型: " + eventType);

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.WINDOWS_CHANGE_FOCUSED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED){
            return;
        }
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        long currentTime = System.currentTimeMillis();
        Log.d(TAG, "lastNodeText: " + lastNodeText);
        if (currentTime - lastEventTime < EVENT_DEBOUNCE_TIME && Objects.equals(getRootChildText(rootNode), lastNodeText)) {
            Log.d(TAG, "跳过短时间内的重复事件");
            return;
        }
        lastEventTime = currentTime;
        lastNodeText = getRootChildText(rootNode);

        printNodeInfo(rootNode, 0);

        handleWindowChangeEvent(rootNode);
    }


    private String getRootChildText(AccessibilityNodeInfo node) {
        if (node == null) return null;

        // 遍历 rootNode 第一层级的子节点
        int childCount = node.getChildCount();

        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo childNode = node.getChild(i);
            if (childNode != null) {
                CharSequence text = childNode.getText();
                Log.d(TAG, "当前节点文本为: " + text);
                return (String) text;
            }
        }
        return null;
    }


    private void printNodeInfo(AccessibilityNodeInfo node, int depth) {
        if (depth > 3) return;
        if (node == null) return;

        // 创建缩进格式，使层级关系更清晰
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indent.append("  "); // 每个层级增加两个空格
        }

        // 打印当前节点的信息
        Log.d("NodeTree", indent + "ClassName: " + node.getClassName());
        Log.d("NodeTree", indent + "Text: " + node.getText());
        Log.d("NodeTree", indent + "Clickable: " + node.isClickable());
//        Log.d("NodeTree", indent + "ContentDescription: " + node.getContentDescription());
//        Log.d("NodeTree", indent + "ViewId: " + node.getViewIdResourceName());
//        Log.d("NodeTree", indent + "Focusable: " + node.isFocusable());
//        Log.d("NodeTree", indent + "Scrollable: " + node.isScrollable());

        // 递归遍历子节点
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                printNodeInfo(child, depth + 1);
            }
        }
    }

    private void handleWindowChangeEvent(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return;

        // 处理蓝牙配对 & 处理 USB 弹窗
        handleBluetoothPairing(rootNode);
        handleOtherDialogs(rootNode);
    }

    /**
     * 处理 USB及其他相关的弹窗
     */
    private void handleOtherDialogs(AccessibilityNodeInfo rootNode) {
        executor.execute(() -> {
            List<AccessibilityNodeInfo> warningNodes = rootNode.findAccessibilityNodeInfosByText("长时间保持高音量可能会损伤听力");
            if (!warningNodes.isEmpty()) {
                List<AccessibilityNodeInfo> confirmNodes = rootNode.findAccessibilityNodeInfosByText("确定");
                for (AccessibilityNodeInfo confirmNode : confirmNodes) {
                    if (confirmNode.isClickable()) {
                        confirmNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        Log.d(TAG, "点击'确定'关闭音量损伤弹窗.");
                    }
                }
            }
        });
        executor.execute(() -> {
            List<AccessibilityNodeInfo> usbNodes = rootNode.findAccessibilityNodeInfosByText("USB 用于");
            for (AccessibilityNodeInfo node : usbNodes) {
                CharSequence text = node.getText();
                if (text != null && text.toString().contains("USB 用于")) {
                    mainHandler.postDelayed(() -> {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        Log.d(TAG, "延迟2秒后执行: 关闭 USB 弹窗 " + text);
                    }, 2000); // 延迟2秒执行
                }
            }
        });
        executor.execute(() -> {
            List<AccessibilityNodeInfo> usbConnectsNodes = rootNode.findAccessibilityNodeInfosByText("USB 连接方式");
            if (!usbConnectsNodes.isEmpty()) {
                List<AccessibilityNodeInfo> cancelNodes = rootNode.findAccessibilityNodeInfosByText("取消");
                for (AccessibilityNodeInfo cancelNode : cancelNodes) {
                    if (cancelNode.isClickable()) {
                        mainHandler.postDelayed(() -> {
                            cancelNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            Log.d(TAG, "延迟2秒后点击'取消'关闭 USB 弹窗.");
                        }, 2000); // 延迟2秒执行
                    }
                }
            }
        });
    }

    /**
     * 处理蓝牙配对
     */
    private void handleBluetoothPairing(AccessibilityNodeInfo rootNode) {
        executor.execute(() -> {
            List<AccessibilityNodeInfo> pairsNodes = rootNode.findAccessibilityNodeInfosByText("配对");
            for (AccessibilityNodeInfo pairsNode : pairsNodes) {
                CharSequence text = pairsNode.getText();
                if (text != null && text.toString().contains("配对") &&
                        !text.toString().matches(".*(已|取消|与|通讯录|请求).*")) {
                    if (pairsNode.isClickable()) {
                        mainHandler.postDelayed(() -> {
                            pairsNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            Log.d(TAG, "延迟1秒点击'配对'按钮: " + text);
                        }, 1000); // 延迟1秒执行
                    }else{
                        Log.d(TAG, "'配对'按钮不可点击: " + text);
                    }
                }
            }
        });
    }

    private final Handler monitorHandler = new Handler(Looper.getMainLooper());
    private final Runnable monitorRunnable = new Runnable() {
        @Override
        public void run() {
            AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null || !am.isEnabled()) {
                Log.w(TAG, "无障碍服务异常停止，尝试重启...");
                restartService();
            }
            // 每5秒检查一次
            monitorHandler.postDelayed(this, 5000);
        }
    };

    /**
     * 启动无障碍服务监控
     */
    private void monitorAccessibilityService() {
        monitorHandler.post(monitorRunnable);
    }

    /**
     * 停止监控（如果需要）
     */
    private void stopMonitorAccessibilityService() {
        monitorHandler.removeCallbacks(monitorRunnable);
    }

    /**
     * 重新启动无障碍服务
     */
    private void restartService() {
        ComponentName componentName = new ComponentName(this, MyAccessibilityService.class);
        PackageManager pm = getPackageManager();

        pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
        );

        pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
        );

        Log.d(TAG, "无障碍服务已重启.");
    }


    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service Interrupted");
        restartService();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG,"正在关闭无障碍服务");
        super.onDestroy();
        if (wakeLock != null&&wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released.");
        }

        if (overlayView != null) {
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager != null) {
                windowManager.removeView(overlayView);
                Log.d(TAG, "Overlay removed.");
            }
        }

        executor.shutdown();
        Log.d(TAG, "ExecutorService shutdown.");

        Log.d(TAG, "Service Destroyed, trying to restart...");
        stopMonitorAccessibilityService();
    }


    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // 重启服务逻辑，保持服务活跃
        Intent restartService = new Intent(getApplicationContext(), MyAccessibilityService.class);
        startService(restartService);
    }

}
