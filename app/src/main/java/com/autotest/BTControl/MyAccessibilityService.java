package com.autotest.BTControl;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;

import java.util.List;

public class MyAccessibilityService extends AccessibilityService {

    private static final String TAG = "MyAccessibilityService";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        Log.d(TAG, "Event type: " + eventType);

        // 监听窗口内容变化和窗口状态变化的事件
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

            handleWindowChangeEvent();
        }
    }

    private void handleWindowChangeEvent() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();

        if (rootNode != null) {
//            printNodeTree(rootNode,0);

            List<AccessibilityNodeInfo> usb_nodes = findUSB(rootNode);
            for (AccessibilityNodeInfo node : usb_nodes) {
                CharSequence text = node.getText();
                if (text != null && text.toString().contains("USB")) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    Log.d(TAG, "GLOBAL_ACTION_BACK: " + text);
                    return;
                }
            }

            // 查找“配对”按钮
            List<AccessibilityNodeInfo> nodes = findPairButton(rootNode);

            for (AccessibilityNodeInfo node : nodes) {
                CharSequence text = node.getText();
                if (node.isClickable() && text != null && text.toString().contains("配对") && !text.toString().contains("已配对")) {
                    // 点击“配对”按钮
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "ACTION_CLICK: " + text);
                    return;
                }

            }
        }
    }

    // 打印节点树（可选，用于调试）
    private void printNodeTree(AccessibilityNodeInfo node, int depth) {
        if (node == null) return;

        String indent = new String(new char[depth]).replace("\0", "  ");
        CharSequence text = node.getText();
        Log.d(TAG, indent + "Node: " + (text != null ? text.toString() : "no text"));

        for (int i = 0; i < node.getChildCount(); i++) {
            printNodeTree(node.getChild(i), depth + 1);
        }
    }

    private List<AccessibilityNodeInfo> findPairButton(AccessibilityNodeInfo rootNode) {
        return rootNode.findAccessibilityNodeInfosByText("配对");
    }

    private List<AccessibilityNodeInfo> findUSB(AccessibilityNodeInfo rootNode) {
        return rootNode.findAccessibilityNodeInfosByText("USB");
    }

    @Override
    public void onInterrupt() {
        // 处理服务中断
        Log.d(TAG, "Service Interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // 配置服务的属性
        Log.d(TAG, "Service Connected");
    }
}
