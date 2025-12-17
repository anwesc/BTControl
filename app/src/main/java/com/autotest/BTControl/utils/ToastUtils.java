package com.autotest.BTControl.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Toast工具类，用于管理Toast消息的显示
 * 解决多个Toast同时显示导致的消息堆积问题
 */
public class ToastUtils {
    private static Toast currentToast;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    /**
     * 显示短时Toast消息
     * @param context 上下文
     * @param message 消息内容
     */
    public static void showShort(Context context, String message) {
        showToast(context, message, Toast.LENGTH_SHORT);
    }
    
    /**
     * 显示长时Toast消息
     * @param context 上下文
     * @param message 消息内容
     */
    public static void showLong(Context context, String message) {
        showToast(context, message, Toast.LENGTH_LONG);
    }
    
    /**
     * 显示自定义时长的Toast消息
     * @param context 上下文
     * @param message 消息内容
     * @param duration 显示时长（毫秒）
     */
    public static void showCustomDuration(Context context, String message, int duration) {
        // 如果已有Toast在显示，先取消
        cancelCurrentToast();
        
        currentToast = Toast.makeText(context, message, Toast.LENGTH_LONG);
        currentToast.show();
        
        // 使用Handler在指定时间后取消Toast
        mainHandler.postDelayed(() -> {
            if (currentToast != null) {
                currentToast.cancel();
                currentToast = null;
            }
        }, duration);
    }
    
    /**
     * 显示Toast消息的通用方法
     * @param context 上下文
     * @param message 消息内容
     * @param duration 显示时长
     */
    private static void showToast(Context context, String message, int duration) {
        // 如果已有Toast在显示，先取消
        cancelCurrentToast();
        
        // 创建新的Toast
        currentToast = Toast.makeText(context, message, duration);
        currentToast.show();
    }
    
    /**
     * 取消当前显示的Toast
     */
    public static void cancelCurrentToast() {
        if (currentToast != null) {
            currentToast.cancel();
            currentToast = null;
        }
    }
    
    /**
     * 延迟显示Toast消息
     * @param context 上下文
     * @param message 消息内容
     * @param duration 显示时长
     * @param delayMillis 延迟时间（毫秒）
     */
    public static void showDelayed(Context context, String message, int duration, long delayMillis) {
        mainHandler.postDelayed(() -> showToast(context, message, duration), delayMillis);
    }
    
    /**
     * 显示成功消息（绿色样式）
     * @param context 上下文
     * @param message 消息内容
     */
    public static void showSuccess(Context context, String message) {
        showShort(context, message);
    }
    
    /**
     * 显示错误消息（红色样式）
     * @param context 上下文
     * @param message 消息内容
     */
    public static void showError(Context context, String message) {
        showLong(context, message);
    }
    
    /**
     * 显示警告消息（黄色样式）
     * @param context 上下文
     * @param message 消息内容
     */
    public static void showWarning(Context context, String message) {
        showLong(context, message);
    }
    
    /**
     * 显示信息消息（蓝色样式）
     * @param context 上下文
     * @param message 消息内容
     */
    public static void showInfo(Context context, String message) {
        showShort(context, message);
    }
}