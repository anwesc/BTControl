package com.autotest.BTControl;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;
import android.widget.LinearLayout;

import java.util.List;

public class MyAccessibilityService extends AccessibilityService {

    private static final String TAG = "BTControl:(MyAccessibilityService)";
    private PowerManager.WakeLock wakeLock;
    private View overlayView;

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
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON, // 关键 FLAG
                android.graphics.PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP;

        overlayView = new LinearLayout(this);
        overlayView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        windowManager.addView(overlayView, params);
        Log.d(TAG, "Screen kept ON via WindowManager overlay.");
    }


    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();
        Log.d(TAG, "Event type: " + eventType);

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowChangeEvent();
        }
    }

    private void handleWindowChangeEvent() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        // 处理USB弹窗
        handleUsbDialogs(rootNode);

        // 处理蓝牙配对
        handleBluetoothPairing(rootNode);
    }

    /**
     * 处理 USB 相关的弹窗
     */
    private void handleUsbDialogs(AccessibilityNodeInfo rootNode) {
        List<AccessibilityNodeInfo> usbNodes = rootNode.findAccessibilityNodeInfosByText("USB 用于");
        for (AccessibilityNodeInfo node : usbNodes) {
            CharSequence text = node.getText();
            if (text != null && text.toString().contains("USB 用于")) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // 标记中断状态
                }
                performGlobalAction(GLOBAL_ACTION_BACK);
                Log.d(TAG, "GLOBAL_ACTION_BACK: " + text);
            }
        }

        List<AccessibilityNodeInfo> usbConnectsNodes = rootNode.findAccessibilityNodeInfosByText("USB 连接方式");
        if (!usbConnectsNodes.isEmpty()) {
            List<AccessibilityNodeInfo> cancelNodes = rootNode.findAccessibilityNodeInfosByText("取消");
            for (AccessibilityNodeInfo cancelNode : cancelNodes) {
                if (cancelNode.isClickable()) {
                    cancelNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "Clicked '取消' to dismiss USB dialog.");
                }
            }
        }

        List<AccessibilityNodeInfo> warningNodes = rootNode.findAccessibilityNodeInfosByText("长时间保持高音量可能会损伤听力");
        if (!warningNodes.isEmpty()) {
            List<AccessibilityNodeInfo> confirmNodes = rootNode.findAccessibilityNodeInfosByText("确定");
            for (AccessibilityNodeInfo confirmNode : confirmNodes) {
                if (confirmNode.isClickable()) {
                    confirmNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "Clicked '确定' to dismiss high volume warning.");
                }
            }
        }
    }

    /**
     * 处理蓝牙配对
     */
    private void handleBluetoothPairing(AccessibilityNodeInfo rootNode) {
        List<AccessibilityNodeInfo> pairsNodes = rootNode.findAccessibilityNodeInfosByText("配对");
        for (AccessibilityNodeInfo pairsNode : pairsNodes) {
            CharSequence text = pairsNode.getText();
            if (text != null && text.toString().contains("配对") &&
                    !text.toString().matches(".*(已|取消|与|通讯录).*")) {
                if (pairsNode.isClickable()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); // 标记中断状态
                    }
                    pairsNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "Clicked '配对' button: " + text);
                }
            }
        }
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
    }
}