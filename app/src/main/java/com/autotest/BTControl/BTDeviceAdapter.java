package com.autotest.BTControl;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.util.List;

public class BTDeviceAdapter extends RecyclerView.Adapter<BTDeviceAdapter.BTDeviceViewHolder> {

    private List<String> btDeviceList;
    private OnItemClickListener onItemClickListener;
    private OnItemLongClickListener onItemLongClickListener;
    private int selectedPosition = RecyclerView.NO_POSITION; // 当前选中的位置，用于高亮

    public BTDeviceAdapter(List<String> btDeviceList, OnItemClickListener onItemClickListener) {
        this.btDeviceList = btDeviceList;
        this.onItemClickListener = onItemClickListener;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.onItemLongClickListener = listener;
    }

    @NonNull
    @Override
    public BTDeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bt_device, parent, false);
        return new BTDeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BTDeviceViewHolder holder, int position) {

        if (holder.deviceTextView != null) {
            String deviceName = btDeviceList.get(position);
            holder.deviceTextView.setText(deviceName);

            MaterialCardView cardView = (MaterialCardView) holder.itemView;
            int defaultBorderColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.card_stroke_color);
            cardView.setStrokeColor(defaultBorderColor);

            int selectedColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.selected_bg_color);
            int normalColor = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSurface);
            holder.itemView.setBackgroundColor(selectedColor);
            holder.itemView.setBackgroundColor(normalColor);

        } else {
            Log.e("BTDeviceAdapter", "deviceTextView is NULL!! 检查你的item_bt_device.xml文件！");
        }
    }


    @Override
    public int getItemCount() {
        return btDeviceList != null ? btDeviceList.size() : 0;
    }

    public class BTDeviceViewHolder extends RecyclerView.ViewHolder {
        TextView deviceTextView;

        public BTDeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceTextView = itemView.findViewById(R.id.deviceTextView);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && onItemClickListener != null) {
                    // 点击动画：缩小再弹回来
                    v.animate()
                            .scaleX(0.95f)
                            .scaleY(0.95f)
                            .setDuration(100)
                            .withEndAction(() -> {
                                v.animate()
                                        .scaleX(1f)
                                        .scaleY(1f)
                                        .setDuration(100)
                                        .start();
                                onItemClickListener.onItemClick(position);

                                // 更新选中状态
                                int oldSelected = selectedPosition;
                                selectedPosition = position;
                                notifyItemChanged(oldSelected);
                                notifyItemChanged(selectedPosition);
                            })
                            .start();
                }
            });

            itemView.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && onItemLongClickListener != null) {
                    // 长按震动
                    Vibrator vibrator = (Vibrator) v.getContext().getSystemService(Context.VIBRATOR_SERVICE);
                    if (vibrator != null && vibrator.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                        } else {
                            vibrator.vibrate(50);
                        }
                    }
                    onItemLongClickListener.onItemLongClick(position);
                    return true;
                }
                return false;
            });
        }
    }

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(int position);
    }
}
