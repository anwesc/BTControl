package com.autotest.BTControl.utils;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.autotest.BTControl.R;

/**
 * 卡片外圈的一圈流光：设备正在连接时绕着整张卡片转，比行内小转圈更能说明「这一条在忙」。
 *
 * <p>实现上是一个 {@link SweepGradient} 圆角描边，靠 ValueAnimator 每帧旋转 shader 的
 * local matrix。只在 {@link #VISIBLE} 时启动动画，列表滑出屏幕/绑定到别的行就自动停。
 */
public class ConnectingRingView extends View {

    private static final long PERIOD_MS = 1200L;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private final float strokeWidth;

    private SweepGradient gradient;
    private ValueAnimator animator;
    private float rotation;
    private float cornerRadius;

    public ConnectingRingView(Context context) {
        this(context, null);
    }

    public ConnectingRingView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ConnectingRingView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        strokeWidth = dp(2f);
        cornerRadius = dp(8f); // 和 device_item_complete_selector 的 8dp 圆角对齐

        basePaint.setStyle(Paint.Style.STROKE);
        basePaint.setStrokeWidth(dp(1.5f));
        basePaint.setColor(0x55_1565C0); // 整圈底色一直看得见，亮的那段沿它跑

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(strokeWidth);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 卡片背景是 layer-list，白底比控件边界内缩约 2dp；环再往里收一点，才贴着白卡而不是压着阴影
        // 外层是 FrameLayout（无 padding），本 View 就是整张卡片大小；只留一点内缩避免压到阴影。
        float inset = strokeWidth / 2f + dp(1.5f);
        ringRect.set(inset, inset, w - inset, h - inset);
        if (ringRect.width() <= 0 || ringRect.height() <= 0) {
            return;
        }
        int accent = ContextCompat.getColor(getContext(), R.color.colorPrimary);
        // 一小段亮色 + 一大段透明，转起来就是一个「彗星」绕着卡片跑
        gradient = new SweepGradient(ringRect.centerX(), ringRect.centerY(),
                new int[]{Color.TRANSPARENT, accent, Color.TRANSPARENT, Color.TRANSPARENT},
                new float[]{0f, 0.16f, 0.34f, 1f});
        ringPaint.setShader(gradient);
        updateShaderMatrix();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRoundRect(ringRect, cornerRadius, cornerRadius, basePaint);
        canvas.drawRoundRect(ringRect, cornerRadius, cornerRadius, ringPaint);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            start();
        } else {
            stop();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) {
            start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void start() {
        if (animator != null && animator.isRunning()) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(PERIOD_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            rotation = (float) animation.getAnimatedValue();
            updateShaderMatrix();
            invalidate();
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void updateShaderMatrix() {
        if (gradient == null) {
            return;
        }
        shaderMatrix.setRotate(rotation, ringRect.centerX(), ringRect.centerY());
        gradient.setLocalMatrix(shaderMatrix);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
