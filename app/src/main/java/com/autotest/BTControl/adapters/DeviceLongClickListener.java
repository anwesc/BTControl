package com.autotest.BTControl.adapters;

/**
 * 设备长按监听器接口
 */
public interface DeviceLongClickListener {
    /**
     * 设备被长按时调用
     * @param position 设备在列表中的位置
     * @param deviceInfo 设备信息
     */
    void onDeviceLongClick(int position, String deviceInfo);
}