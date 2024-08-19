package com.autotest.BTControl;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;

import java.util.Collection;
import java.util.List;

public class MyAccessibilityService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        Log.d("AccessibilityService", "event type: " + eventType);

        // 监听不同类型的事件
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            Log.d("AccessibilityService", "rootNode: " + rootNode);

            if (rootNode != null) {
                // 打印节点树以调试
                printNodeTree(rootNode, 0);

                // 查找“配对”按钮
                List<AccessibilityNodeInfo> nodes = findPairButton(rootNode);

                for (AccessibilityNodeInfo node : nodes) {
                    if (node.isClickable()) {
                        // 点击“配对”按钮
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        Log.d("AccessibilityService", String.valueOf(node));
                        return;
                    } else {
                        Log.d("AccessibilityService", "未找到配对按钮");
                }
                }
            }
        }
    }

    // 打印节点树
    private void printNodeTree(AccessibilityNodeInfo node, int depth) {
        if (node == null) return;

        String indent = new String(new char[depth]).replace("\0", "  ");
        CharSequence text = node.getText();
        Log.d("AccessibilityService", indent + "Node: " + (text != null ? text.toString() : "no text"));

        for (int i = 0; i < node.getChildCount(); i++) {
            printNodeTree(node.getChild(i), depth + 1);
        }
    }


    private List<AccessibilityNodeInfo> findPairButton(AccessibilityNodeInfo rootNode) {
        // 假设配对按钮的文本为“配对”，你可以根据实际情况修改
        return rootNode.findAccessibilityNodeInfosByText("配对");
    }

    @Override
    public void onInterrupt() {
        // 处理服务中断
    }
}
