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
        boolean ok = status == PackageInstaller.STATUS_SUCCESS;
        Log.i(TAG, "安装回执 status=" + status + " ok=" + ok + " message=" + message);

        JSONObject o = new JSONObject();
        try {
            o.put("req", "");
            o.put("action", "安装更新");
            o.put("addr", "");
            o.put("ok", ok);
            o.put("reason", ok ? "" : "install_status_" + status + (message == null ? "" : ": " + message));
            o.put("elapsed_ms", 0);
            o.put("ts", System.currentTimeMillis());
            o.put("phase", "install_result");
            if (message != null) {
                o.put("status_message", message);
            }
        } catch (Exception ignored) {
            // JSONObject.put 在固定的 String key 上不会失败
        }
        BtctlReporter.emit(context, o);

        String text = ok
                ? context.getString(R.string.版本_安装中)
                : context.getString(R.string.版本_安装失败,
                        message == null ? String.valueOf(status) : message);
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
