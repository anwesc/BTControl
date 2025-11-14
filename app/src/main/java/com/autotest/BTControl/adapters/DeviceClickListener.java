package com.autotest.BTControl.adapters;

/**
 * 设备点击监听器接口
 */
public interface DeviceClickListener {
    /**
     * 设备被点击时调用
     * @param position 设备在列表中的位置
     * @param deviceInfo 设备信息
     */
    void onDeviceClick(int position, String deviceInfo);
}