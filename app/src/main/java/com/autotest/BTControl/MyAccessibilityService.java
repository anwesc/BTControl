package com.autotest.BTControl;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.Context;
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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MyAccessibilityService extends AccessibilityService {

    private static final String TAG = "BTControl:(MyAccessibilityService)";
    private PowerManager.WakeLock wakeLock;
    private View overlayView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastEventTime = 0;
    private static final long EVENT_DEBOUNCE_TIME = 500; // 500ms 防抖

    @SuppressLint("Wakelock")
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Service Connected");

        // 1. 申请 WakeLock，防止 CPU 进入休眠
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "BTControl:WakeLock"
            );
            wakeLock.acquire();
            Log.d(TAG, "WakeLock acquired: Preventing CPU sleep.");
        }

        // 2. 解除屏幕锁定
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null) {
            KeyguardManager.KeyguardLock keyguardLock = keyguardManager.newKeyguardLock("BTControl:KeyguardLock");
            keyguardLock.disableKeyguard();
            Log.d(TAG, "Keyguard disabled: Unlocking screen.");
        }

        // 3. 通过 WindowManager 让屏幕保持亮起
        keepScreenOn();
    }

    /**
     * 通过 WindowManager 设置屏幕常亮
     */
    private void keepScreenOn() {
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "WindowManager is null");
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
        Log.d(TAG, "Screen kept ON via WindowManager overlay.");

        // 5秒后移除遮罩
        mainHandler.postDelayed(() -> {
            if (overlayView != null) {
                windowManager.removeView(overlayView);
                overlayView = null;
                Log.d(TAG, "Overlay removed after delay.");
            }
        }, 5000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();
        Log.d(TAG, "事件类型: " + eventType);

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.WINDOWS_CHANGE_FOCUSED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED){
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastEventTime < EVENT_DEBOUNCE_TIME) {
            Log.d(TAG, "跳过短时间内的重复事件");
            return;
        }
        lastEventTime = currentTime;

        handleWindowChangeEvent();
    }

    private void handleWindowChangeEvent() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
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
                        Log.d(TAG, "延迟3秒后执行: 关闭 USB 弹窗 " + text);
                    }, 3000); // 延迟3秒执行
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
                            Log.d(TAG, "延迟3秒后点击'取消'关闭 USB 弹窗.");
                        }, 3000); // 延迟3秒执行
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
                        !text.toString().matches(".*(已|取消|与|通讯录).*")) {
                    if (pairsNode.isClickable()) {
                        mainHandler.postDelayed(() -> {
                            pairsNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            Log.d(TAG, "延迟1秒点击'配对'按钮: " + text);
                        }, 1000); // 延迟1秒执行
                    }
                }
            }
        });
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service Interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
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
    }
}
