package com.autotest.BTControl.adapters;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.autotest.BTControl.R;

/**
 * 设备列表的分组标题行（已保存设备 / 周围设备 / 非常用设备），本身就是 RecyclerView 的一个 item。
 *
 * <p>以前三块列表各是一个 RecyclerView、塞在 ScrollView 里，只能靠手工测量高度凑布局；
 * 现在整页只有 {@code deviceRecyclerView}，标题和按钮跟着一起滚，嵌套滚动那套坑就没了。
 */
public class SectionHeaderAdapter extends RecyclerView.Adapter<SectionHeaderAdapter.HeaderHolder> {

    private String title;
    private boolean visible = true;
    private boolean progressVisible;
    private String actionText;
    private View.OnClickListener actionClickListener;

    public SectionHeaderAdapter(String title) {
        this.title = title;
    }

    /** 带右侧按钮的标题行，例如「周围设备 + 开始扫描」。 */
    public SectionHeaderAdapter withAction(String text, View.OnClickListener listener) {
        this.actionText = text;
        this.actionClickListener = listener;
        return this;
    }

    /** 对应列表为空时整行收起，只留「周围设备」这种常驻分组。 */
    public void setVisible(boolean visible) {
        if (this.visible == visible) {
            return;
        }
        this.visible = visible;
        notifyDataSetChanged();
    }

    public void setTitle(String title) {
        if (TextUtils.equals(this.title, title)) {
            return;
        }
        this.title = title;
        notifyItemChanged(0);
    }

    public void setProgressVisible(boolean visible) {
        if (this.progressVisible == visible) {
            return;
        }
        this.progressVisible = visible;
        notifyItemChanged(0);
    }

    public void setActionText(String text) {
        if (TextUtils.equals(this.actionText, text)) {
            return;
        }
        this.actionText = text;
        notifyItemChanged(0);
    }

    @NonNull
    @Override
    public HeaderHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_section_header, parent, false);
        return new HeaderHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull HeaderHolder holder, int position) {
        holder.title.setText(title);
        holder.progress.setVisibility(progressVisible ? View.VISIBLE : View.GONE);
        if (TextUtils.isEmpty(actionText)) {
            holder.action.setVisibility(View.GONE);
            holder.action.setOnClickListener(null);
        } else {
            holder.action.setVisibility(View.VISIBLE);
            holder.action.setText(actionText);
            holder.action.setOnClickListener(actionClickListener);
        }
    }

    @Override
    public int getItemCount() {
        return visible ? 1 : 0;
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final ProgressBar progress;
        final Button action;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.sectionTitleTextView);
            progress = itemView.findViewById(R.id.sectionProgressBar);
            action = itemView.findViewById(R.id.sectionActionButton);
        }
    }
}
