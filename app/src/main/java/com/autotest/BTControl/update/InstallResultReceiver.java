package com.autotest.BTControl.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

import com.autotest.BTControl.R;
import com.autotest.BTControl.utils.BtctlReporter;

import org.json.JSONObject;

/**
 * 系统安装器（{@link PackageInstaller}）的回执：成功时进程通常已被新版本替换，
 * 只有失败才活得到这里，所以要趁现在把原因写进 logcat 和 btctl_result.json。
 */
public class InstallResultReceiver extends BroadcastReceiver {

    private static final String TAG = "BtUpdate";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !UpdateManager.ACTION_INSTALL_RESULT.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "安装回执 status=" + status + " message=" + message);

        // -1 = STATUS_PENDING_USER_ACTION：系统把「要不要装」的确认界面放在 EXTRA_INTENT 里，
        // 得由我们自己拉起来；不当成功处理就会把正常流程误报成失败。
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = null;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                Object raw = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (raw instanceof Intent) {
                    confirm = (Intent) raw;
                }
            }
            boolean launched = false;
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                    launched = true;
                } catch (Throwable t) {
                    Log.w(TAG, "拉起系统安装确认界面失败", t);
                }
            }
            emit(context, true, "installer_pending_user_action", "install_pending_user_action",
                    message, launched ? "" : "confirm_intent_missing");
            return;
        }

        boolean ok = status == PackageInstaller.STATUS_SUCCESS;
        emit(context, ok, ok ? "" : "install_status_" + status + (message == null ? "" : ": " + message),
                "install_result", message, "");

        String text = ok
                ? context.getString(R.string.版本_安装中)
                : context.getString(R.string.版本_安装失败,
                        message == null ? String.valueOf(status) : message);
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }

    private static void emit(Context context, boolean ok, String reason, String phase,
                             String message, String extra) {
        JSONObject o = new JSONObject();
        try {
            o.put("req", "");
            o.put("action", "安装更新");
            o.put("addr", "");
            o.put("ok", ok);
            o.put("reason", reason);
            o.put("elapsed_ms", 0);
            o.put("ts", System.currentTimeMillis());
            o.put("phase", phase);
            if (message != null) {
                o.put("status_message", message);
            }
            if (!extra.isEmpty()) {
                o.put("detail", extra);
            }
        } catch (Exception ignored) {
            // JSONObject.put 在固定的 String key 上不会失败
        }
        BtctlReporter.emit(context, o);
    }
}
