package com.autotest.BTControl.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.core.content.ContextCompat;

/**
 * 震动工具类，提供兼容不同Android版本的震动功能
 */
public class VibrationUtils {
    
    /**
     * 执行轻微震动（用于点击事件）
     * @param context 上下文
     */
    @SuppressLint("ObsoleteSdkInt")
    public static void performLightVibration(Context context) {
        if (context == null) return;
        
        Vibrator vibrator = getVibrator(context);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0及以上使用VibrationEffect
            VibrationEffect effect = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE);
            vibrator.vibrate(effect);
        } else {
            // 旧版本直接使用vibrate方法
            vibrator.vibrate(50);
        }
    }
    
    /**
     * 执行长按震动（用于长按事件）
     * @param context 上下文
     */
    public static void performLongPressVibration(Context context) {
        if (context == null) return;
        
        Vibrator vibrator = getVibrator(context);
        if (vibrator == null || !vibrator.hasVibrator()) return;

        long[] pattern = {0, 100, 50, 100};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0及以上使用VibrationEffect
            // 震动模式：震动100毫秒，停止50毫秒，再震动100毫秒
            VibrationEffect effect = VibrationEffect.createWaveform(pattern, -1);
            vibrator.vibrate(effect);
        } else {
            // 旧版本直接使用vibrate方法
            vibrator.vibrate(pattern, -1);
        }
    }
    
    /**
     * 获取震动器实例，兼容不同Android版本
     * @param context 上下文
     * @return 震动器实例
     */
    private static Vibrator getVibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12及以上使用VibratorManager
            VibratorManager vibratorManager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vibratorManager != null) {
                return vibratorManager.getDefaultVibrator();
            }
        }
        // 使用 ContextCompat.getSystemService 以兼容旧版本并避免使用已弃用的 Context.VIBRATOR_SERVICE 常量
        return ContextCompat.getSystemService(context, Vibrator.class);
    }
}