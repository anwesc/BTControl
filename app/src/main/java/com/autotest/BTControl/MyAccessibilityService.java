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

            List<AccessibilityNodeInfo> usb_nodes = rootNode.findAccessibilityNodeInfosByText("USB 用于");
            for (AccessibilityNodeInfo node : usb_nodes) {
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

            List<AccessibilityNodeInfo> usb_connects_nodes = rootNode.findAccessibilityNodeInfosByText("USB 连接方式");
            if (usb_connects_nodes != null  && !usb_connects_nodes.isEmpty()){
                List<AccessibilityNodeInfo> cancelNodes = rootNode.findAccessibilityNodeInfosByText("取消");
                if (cancelNodes != null && !cancelNodes.isEmpty()) {
                    for (AccessibilityNodeInfo cancelNode : cancelNodes) {
                        CharSequence text = cancelNode.getText();
                        Log.d(TAG, "cancelNode:" + text);
                        // 尝试点击确认按钮
                        if (cancelNode.isClickable()) {
                            cancelNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        }
                    }
                }
            }


            List<AccessibilityNodeInfo> warningNodes = rootNode.findAccessibilityNodeInfosByText("长时间保持高音量可能会损伤听力");
            if (warningNodes != null && !warningNodes.isEmpty()) {
                // 找到警告文本后，查找“确定”按钮
                List<AccessibilityNodeInfo> confirmNodes = rootNode.findAccessibilityNodeInfosByText("确定");
                if (confirmNodes != null && !confirmNodes.isEmpty()) {
                    for (AccessibilityNodeInfo confirmNode : confirmNodes) {
                        CharSequence text = confirmNode.getText();
                        Log.d(TAG, "损伤听力:" + text);
                        // 尝试点击确认按钮
                        if (confirmNode.isClickable()) {
                            confirmNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        }
                    }
                }
            }

            // 查找“配对”按钮
            List<AccessibilityNodeInfo> pairsNodes = rootNode.findAccessibilityNodeInfosByText("配对");
            if (pairsNodes != null && !pairsNodes.isEmpty()) {
                for (AccessibilityNodeInfo pairsNode : pairsNodes) {
                    CharSequence text = pairsNode.getText();
                    Log.d(TAG, "配对文本:" + text);
                    if (text.toString().contains("配对") && !text.toString().contains("已") && !text.toString().contains("取消") && !text.toString().contains("与") && !text.toString().contains("通讯录")) {
                        // 点击“配对”按钮
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); // 标记中断状态
                        }
                        if (pairsNode.isClickable()) {
                            pairsNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            Log.d(TAG, "ACTION_CLICK: " + text);
                        }
                    }

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
