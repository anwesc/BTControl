package com.autotest.BTControl.adapters;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.autotest.BTControl.R;
import com.autotest.BTControl.utils.ScanAdvertCache;
import com.autotest.BTControl.utils.VibrationUtils;

import java.util.List;
import java.util.Locale;

/**
 * 蓝牙设备列表适配器
 */
public class BluetoothDeviceAdapter extends RecyclerView.Adapter<BluetoothDeviceAdapter.DeviceViewHolder> {
    
    private final List<BluetoothDeviceItem> deviceList;
    private final DeviceClickListener clickListener;
    private final DeviceLongClickListener longClickListener;
    /** 右侧箭头的监听器：为 null 时不显示箭头（周围设备 / 非常用设备不需要进详情页）。 */
    private DeviceClickListener detailClickListener;
    
    /**
     * 构造函数
     * @param deviceList 设备列表
     * @param clickListener 点击监听器
     * @param longClickListener 长按监听器
     */
    public BluetoothDeviceAdapter(List<BluetoothDeviceItem> deviceList, DeviceClickListener clickListener, 
                                DeviceLongClickListener longClickListener) {
        this.deviceList = deviceList;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }
    
    /** 给「已保存设备」装上右侧箭头：箭头和整行都进详情页，长按仍是操作面板。 */
    public void setOnDetailClickListener(DeviceClickListener listener) {
        this.detailClickListener = listener;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bluetooth_device, parent, false);
        return new DeviceViewHolder(view);
    }
    
    @Override
        public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
            BluetoothDeviceItem deviceItem = deviceList.get(position);
            String deviceName = deviceItem.getDeviceName();
            
            // 名字为空就直接 GONE：约束布局会自己塌掉这一行，别再换 LayoutParams
            // （换成 LinearLayout.LayoutParams 会把 ConstraintLayout 的约束全丢掉，状态药丸会被挤到箭头下面）
            if (deviceName == null || deviceName.isEmpty()) {
                holder.deviceNameTextView.setVisibility(View.GONE);
            } else {
                holder.deviceNameTextView.setVisibility(View.VISIBLE);
                holder.deviceNameTextView.setText(deviceName);
            }
            
            holder.deviceAddressTextView.setText(deviceItem.getDeviceAddress());
            String deviceStatus = deviceItem.getDeviceStatus();
            
            // 行内只显示状态（详情页才有 profile 明细）
            if (deviceStatus != null && !deviceStatus.isEmpty()) {
                holder.deviceStatusTextView.setVisibility(View.VISIBLE);
                holder.deviceStatusTextView.setText(deviceStatus);
            } else {
                holder.deviceStatusTextView.setVisibility(View.GONE);
            }
            holder.deviceProfilesTextView.setVisibility(View.GONE);

            // 信号强度：扫描到的周围设备才有值，已保存设备一直是 RSSI_UNKNOWN
            if (deviceItem.getRssi() != ScanAdvertCache.RSSI_UNKNOWN) {
                holder.deviceRssiTextView.setVisibility(View.VISIBLE);
                holder.deviceRssiTextView.setText(
                        String.format(Locale.ROOT, "%d dBm", deviceItem.getRssi()));
            } else {
                holder.deviceRssiTextView.setVisibility(View.GONE);
            }

            // 右侧箭头：只有已保存设备才有，且只有它能把用户带进详情页
            ImageView detailImageView = holder.deviceDetailImageView;
            detailImageView.setVisibility(detailClickListener != null ? View.VISIBLE : View.GONE);
            detailImageView.setOnClickListener(detailClickListener == null ? null : v -> {
                VibrationUtils.performLightVibration(v.getContext());
                detailClickListener.onDeviceClick(holder.getBindingAdapterPosition(), deviceItem);
            });

            // 设置点击事件
            holder.itemView.setOnClickListener(v -> {
                // 添加点击震动反馈
                VibrationUtils.performLightVibration(v.getContext());
                
                // 添加点击动画效果
                v.startAnimation(android.view.animation.AnimationUtils.loadAnimation(v.getContext(), R.anim.item_click_scale_down));
                
                if (clickListener != null) {
                    clickListener.onDeviceClick(position, deviceItem);
                }
                
                // 恢复动画
                v.postDelayed(() -> v.startAnimation(android.view.animation.AnimationUtils.loadAnimation(v.getContext(), R.anim.item_click_scale_up)), 100);
            });
            
            // 设置长按事件
            holder.itemView.setOnLongClickListener(v -> {
                // 添加长按震动反馈
                VibrationUtils.performLongPressVibration(v.getContext());
                
                // 添加长按动画效果
                v.startAnimation(android.view.animation.AnimationUtils.loadAnimation(v.getContext(), R.anim.item_long_press_scale_down));
                
                if (longClickListener != null) {
                    longClickListener.onDeviceLongClick(position, deviceItem);
                    
                    // 恢复动画
                    v.postDelayed(() -> v.startAnimation(android.view.animation.AnimationUtils.loadAnimation(v.getContext(), R.anim.item_long_press_scale_up)), 150);
                    
                    return true;
                }
                return false;
            });
        }
    
    @Override
    public int getItemCount() {
        return deviceList.size();
    }
    
    /**
     * 更新设备列表
     * @param newDeviceList 新的设备列表
     */
    @SuppressLint("NotifyDataSetChanged")
    public void updateDeviceList(List<BluetoothDeviceItem> newDeviceList) {
        this.deviceList.clear();
        this.deviceList.addAll(newDeviceList);
        notifyDataSetChanged();
    }
    
    /**
     * 设备ViewHolder
     */
    static class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView deviceNameTextView;
        TextView deviceAddressTextView;
        TextView deviceStatusTextView;
        TextView deviceProfilesTextView;
        TextView deviceRssiTextView;
        ImageView deviceDetailImageView;
        
        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceNameTextView = itemView.findViewById(R.id.deviceNameTextView);
            deviceAddressTextView = itemView.findViewById(R.id.deviceAddressTextView);
            deviceStatusTextView = itemView.findViewById(R.id.deviceStatusTextView);
            deviceProfilesTextView = itemView.findViewById(R.id.deviceProfilesTextView);
            deviceRssiTextView = itemView.findViewById(R.id.deviceRssiTextView);
            deviceDetailImageView = itemView.findViewById(R.id.deviceDetailImageView);
        }
    }
    
    /**
     * 设备点击监听器
     */
    public interface DeviceClickListener {
        void onDeviceClick(int position, BluetoothDeviceItem deviceItem);
    }
    
    /**
     * 设备长按监听器接口
     */
    public interface DeviceLongClickListener {
        void onDeviceLongClick(int position, BluetoothDeviceItem device);
    }
    
    /**
     * 蓝牙设备项数据类
     */
    public static class BluetoothDeviceItem {
        private String deviceName;
        private String deviceAddress;
        private String deviceStatus;
        private String profilesText; // 新增：存储已连接的蓝牙配置文件信息
        private boolean isConnected;
        private boolean isPaired;
        private BluetoothDevice bluetoothDevice;
        private boolean updateStatus; // 是否更新状态
        private boolean showUnpairedStatus; // 是否显示"未配对"状态
        /** 最近一次扫描到的信号强度；没扫到过就是 {@link ScanAdvertCache#RSSI_UNKNOWN}。 */
        private int rssi = ScanAdvertCache.RSSI_UNKNOWN;

        public BluetoothDeviceItem(BluetoothDevice device, boolean isPaired, boolean isConnected) {
            this.bluetoothDevice = device;
            @SuppressLint("MissingPermission") String name = device.getName();
            this.deviceName = (name != null && !name.equals("未知设备")) ? name : "";
            this.deviceAddress = device.getAddress();
            this.isPaired = isPaired;
            this.isConnected = isConnected;
            
            // 如果不更新状态，初始化为空字符串
            if (!updateStatus) {
                this.deviceStatus = "";
            } else {
                updateStatus();
            }
        }

        // 新增构造函数，支持设置配置文件信息
        public BluetoothDeviceItem(BluetoothDevice device, boolean isPaired, boolean isConnected, 
                                 String customStatusText, String profilesText) {
            this.bluetoothDevice = device;
            @SuppressLint("MissingPermission") String name = device.getName();
            this.deviceName = (name != null && !name.equals("未知设备")) ? name : "";
            this.deviceAddress = device.getAddress();
            this.isPaired = isPaired;
            this.isConnected = isConnected;
            this.updateStatus = false; // 使用自定义状态文本，不自动更新
            this.showUnpairedStatus = false; // 不显示"未配对"状态
            
            // 直接设置自定义状态文本
            this.deviceStatus = customStatusText;
            
            // 设置配置文件信息
            this.profilesText = profilesText;
        }
        
        private void updateStatus() {
            if (!updateStatus) {
                // 如果不更新状态，保持当前状态不变
                return;
            }
            
            if (isConnected) {
                deviceStatus = "已连接";
            } else if (isPaired) {
                deviceStatus = "已配对";
            } else {
                // 根据showUnpairedStatus参数决定是否显示"未配对"状态
                if (showUnpairedStatus) {
                    deviceStatus = "未配对";
                } else {
                    deviceStatus = ""; // 不显示"未配对"状态
                }
            }
        }
        
        // Getter和Setter方法
        public String getDeviceName() {
            return deviceName;
        }

        public void setDeviceName(String deviceName) {
            this.deviceName = deviceName;
        }

        public String getDeviceAddress() {
            return deviceAddress;
        }
        
        public void setDeviceAddress(String deviceAddress) {
            this.deviceAddress = deviceAddress;
        }
        
        public String getDeviceStatus() {
            return deviceStatus;
        }
        
        public void setDeviceStatus(String deviceStatus) {
            this.deviceStatus = deviceStatus;
        }
        
        public boolean isConnected() {
            return isConnected;
        }

        public int getRssi() {
            return rssi;
        }

        public void setRssi(int rssi) {
            this.rssi = rssi;
        }
        
        public boolean shouldUpdateStatus() {
            return updateStatus;
        }
        
        public void setUpdateStatus(boolean updateStatus) {
            this.updateStatus = updateStatus;
            updateStatus();
        }
        
        public void setConnected(boolean connected) {
            isConnected = connected;
            updateStatus();
        }
        
        public boolean isPaired() {
            return isPaired;
        }
        
        public void setPaired(boolean paired) {
            isPaired = paired;
            updateStatus();
        }
        
        public BluetoothDevice getBluetoothDevice() {
            return bluetoothDevice;
        }
        
        public void setBluetoothDevice(BluetoothDevice bluetoothDevice) {
            this.bluetoothDevice = bluetoothDevice;
        }
        
        public boolean isShowUnpairedStatus() {
            return showUnpairedStatus;
        }

        public void setShowUnpairedStatus(boolean showUnpairedStatus) {
            this.showUnpairedStatus = showUnpairedStatus;
            updateStatus();
        }
        
        public String getProfilesText() {
            return profilesText;
        }
        
        public void setProfilesText(String profilesText) {
            this.profilesText = profilesText;
        }
    }
}