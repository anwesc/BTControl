package com.autotest.BTControl.utils;

import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

/**
 * 借 Shizuku 把 binder 调用交给 shell(uid 2000) 执行。
 *
 * <p>背景：普通应用调 {@code BluetoothHeadset.connect()} 会被系统以
 * {@code MODIFY_PHONE_STATE} 拒掉；shell 有这个权限位，但没有命令行能触发 HFP 连接。
 * 于是先按常规拿到 profile 代理（这一步 app 是被允许的），从里面取出
 * {@code bluetooth_headset} 的原始 binder，再包进 {@link ShizukuBinderWrapper}，
 * 让事务由 Shizuku 服务端以 shell 身份发出。
 *
 * <p>注意：Android 12+ 没有 {@code bluetooth_headset} 这个 ServiceManager 名字了
 * （{@code service list} 查不到），所以不能靠 {@code SystemServiceHelper}，只能从代理里反射取。
 * {@code IBluetoothHeadset} 是 @hide 接口，全部走反射；方法签名在 12 之后多了
 * AttributionSource，按参数个数自适应。
 */
public final class ShizukuBridge {

    private static final String TAG = "BtShizuku";

    private ShizukuBridge() {
    }

    /** 返回 ok / shizuku_not_running / shizuku_unauthorized / shizuku_unavailable: xxx。 */
    public static String status() {
        try {
            if (!Shizuku.pingBinder()) {
                return "shizuku_not_running";
            }
            if (Shizuku.isPreV11()) {
                return "shizuku_too_old";
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "shizuku_unauthorized";
            }
            return "ok";
        } catch (Throwable t) {
            return "shizuku_unavailable: " + t;
        }
    }

    /** 弹 Shizuku 的授权框（用户在 Shizuku 里点允许）；失败只记日志。 */
    public static void requestPermission() {
        try {
            Shizuku.requestPermission(0);
        } catch (Throwable t) {
            Log.w(TAG, "请求 Shizuku 权限失败", t);
        }
    }

    /** Shizuku 服务端 AIDL 的 stub：只有它能 newProcess 起一个 shell 进程。 */
    private static final String SHIZUKU_SERVICE_STUB = "moe.shizuku.server.IShizukuService$Stub";

    /**
     * 以 shell 身份按 MAC 建立 CompanionDeviceManager 关联。
     *
     * <p>公开 API 的 {@code associate()} 只能带过滤器，由系统弹选择器让用户挑设备；而选择器
     * 只列「正在广播/可发现」的设备——设备连上 A2DP 后就不可发现了，想关联一个已连上的设备
     * 几乎必然失败。shell 有 {@code cmd companiondevice associate <userId> <pkg> <mac>}，
     * 可以直接指定 MAC，因此这里借 Shizuku 跑这条命令绕开选择器。
     *
     * @return "ok" 开头表示关联成功；其余为可直接上报的原因串
     */
    public static String associateCompanionDevice(android.content.Context ctx, String macAddress) {
        if (ctx == null || macAddress == null || macAddress.trim().isEmpty()) {
            return "shizuku_bad_args";
        }
        return shellExec("cmd", "companiondevice", "associate",
                String.valueOf(currentUserId()), ctx.getPackageName(), macAddress);
    }

    /** 当前用户 id；{@code UserHandle.myUserId()} 是隐藏 API，只能反射，取不到就按主用户 0。 */
    private static int currentUserId() {
        try {
            Object value = Class.forName("android.os.UserHandle")
                    .getMethod("myUserId")
                    .invoke(null);
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable t) {
            Log.d(TAG, "取不到 userId，按 0 处理: " + t);
            return 0;
        }
    }

    /** 以 shell 身份执行一条命令；waitFor 取退出码，非 0 时把 stderr 带回。 */
    private static String shellExec(String... command) {
        String st = status();
        if (!"ok".equals(st)) {
            return st;
        }
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                return "shizuku_binder_missing";
            }
            Object service = Class.forName(SHIZUKU_SERVICE_STUB)
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            Object process = service.getClass()
                    .getMethod("newProcess", String[].class, String[].class, String.class)
                    .invoke(service, command, null, null);
            if (process == null) {
                return "shizuku_process_missing";
            }
            Object code = process.getClass().getMethod("waitFor").invoke(process);
            int exit = code instanceof Integer ? (Integer) code : -1;
            String out = readProcessStream(process, "getInputStream");
            String err = readProcessStream(process, "getErrorStream");
            Log.i(TAG, "shizuku exec " + String.join(" ", command) + " -> " + exit
                    + " out=" + out + " err=" + err);
            if (exit != 0) {
                String detail = err.isEmpty() ? out : err;
                return "shell_exit(" + exit + "): " + detail.trim();
            }
            return "ok";
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            Log.w(TAG, "shizuku exec 抛异常", cause);
            return "shizuku_exec_failed: " + cause;
        } catch (Throwable t) {
            Log.w(TAG, "shizuku exec 失败", t);
            return "shizuku_exec_failed: " + t;
        }
    }

    /** 读远端进程的 stdout/stderr（ParcelFileDescriptor 包的文件流）。 */
    private static String readProcessStream(Object process, String method) {
        try {
            android.os.ParcelFileDescriptor pfd =
                    (android.os.ParcelFileDescriptor) process.getClass().getMethod(method).invoke(process);
            if (pfd == null) {
                return "";
            }
            try (java.io.InputStream in = new java.io.FileInputStream(pfd.getFileDescriptor())) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] tmp = new byte[512];
                int n;
                while ((n = in.read(tmp)) > 0 && buf.size() < 8192) {
                    buf.write(tmp, 0, n);
                }
                return buf.toString("UTF-8");
            } finally {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                    // 进程已退出
                }
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 以 shell 身份调 {@code IBluetoothHeadset.connect}。
     *
     * @param headsetProxy 常规 {@code getProfileProxy} 拿到的 BluetoothHeadset 代理
     * @return 以 "ok" 开头表示调用成行；其余为可直接上报的原因串
     */
    public static String connectHfp(android.content.Context ctx, Object headsetProxy, BluetoothDevice device) {
        return call(ctx, headsetProxy, HEADSET_DESCRIPTOR, HEADSET_STUB, "connect", device);
    }

    public static String disconnectHfp(android.content.Context ctx, Object headsetProxy, BluetoothDevice device) {
        return call(ctx, headsetProxy, HEADSET_DESCRIPTOR, HEADSET_STUB, "disconnect", device);
    }

    /** 以 shell 身份调 {@code IBluetoothLeAudio.connect}（普通应用反射不到 @SystemApi 的 connect）。 */
    public static String connectLeAudio(android.content.Context ctx, Object leAudioProxy, BluetoothDevice device) {
        return call(ctx, leAudioProxy, LE_AUDIO_DESCRIPTOR, LE_AUDIO_STUB, "connect", device);
    }

    public static String disconnectLeAudio(android.content.Context ctx, Object leAudioProxy, BluetoothDevice device) {
        return call(ctx, leAudioProxy, LE_AUDIO_DESCRIPTOR, LE_AUDIO_STUB, "disconnect", device);
    }

    private static String call(android.content.Context ctx, Object proxy, String descriptor, String stubClass,
                               String methodName, BluetoothDevice device) {
        String st = status();
        if (!"ok".equals(st)) {
            return st;
        }
        try {
            IBinder raw = extractBinder(proxy, descriptor);
            if (raw == null) {
                return "shizuku_binder_missing: " + descriptor;
            }
            Object iface = Class.forName(stubClass)
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, new ShizukuBinderWrapper(raw));
            Method target = pickMethod(iface, methodName);
            if (target == null) {
                return "shizuku_method_missing: " + methodName;
            }
            Object[] args = new Object[target.getParameterCount()];
            args[0] = device;
            if (args.length > 1) {
                args[1] = attributionSource();
            }
            Object result = target.invoke(iface, args);
            Log.i(TAG, "shizuku " + methodName + " -> " + result);
            // 代理返回 false = 系统受理但拒绝（例如对端根本不做 LE Audio），别当成成功
            if (Boolean.FALSE.equals(result)) {
                return "shizuku_rejected: " + methodName + " 返回 false（" + descriptor + "）";
            }
            return "ok: " + result;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            Log.w(TAG, "shizuku " + methodName + " 抛异常", cause);
            return "shizuku_call_failed: " + cause;
        } catch (Throwable t) {
            Log.w(TAG, "shizuku " + methodName + " 失败", t);
            return "shizuku_call_failed: " + t;
        }
    }

    /** 从 BluetoothHeadset 代理里取出 IBluetoothHeadset 的 binder。
     *
     * <p>Android 16 的 BluetoothHeadset 不再直接持有 IBluetoothHeadset，而是塞在 @hide 的
     * {@code BluetoothProfileConnector} 里（字段 mProfileConnector），所以要豁免隐藏 API 限制，
     * 再对该对象调 getService() 取真身。
     */
    private static final String HEADSET_DESCRIPTOR = "android.bluetooth.IBluetoothHeadset";
    private static final String HEADSET_STUB = "android.bluetooth.IBluetoothHeadset$Stub";
    private static final String LE_AUDIO_DESCRIPTOR = "android.bluetooth.IBluetoothLeAudio";
    private static final String LE_AUDIO_STUB = "android.bluetooth.IBluetoothLeAudio$Stub";

    private static IBinder extractBinder(Object proxy, String wantDescriptor) throws Exception {
        if (proxy == null) {
            return null;
        }
        try {
            HiddenApiBypass.addHiddenApiExemptions("");
        } catch (Throwable t) {
            Log.w(TAG, "隐藏 API 豁免失败（继续用普通反射试）", t);
        }
        Class<?> cls = proxy.getClass();
        IBinder firstAny = null;
        StringBuilder seen = new StringBuilder();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                seen.append(cls.getSimpleName()).append('.').append(f.getName())
                        .append(':').append(f.getType().getSimpleName()).append(' ');                try {
                    f.setAccessible(true);
                    Object value = f.get(proxy);
                    for (IBinder candidate : candidates(value)) {
                        String desc = descriptor(candidate);
                        if (wantDescriptor.equals(desc)) {
                            return candidate;
                        }
                        if (firstAny == null) {
                            firstAny = candidate;
                        }
                        Log.d(TAG, "候选 binder " + cls.getSimpleName() + "." + f.getName()
                                + " -> " + desc);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "读字段失败 " + cls.getSimpleName() + "." + f.getName() + ": " + t);
                }
            }
            cls = cls.getSuperclass();
        }
        Log.i(TAG, "代理类字段: " + seen);
        if (firstAny == null) {
            Log.w(TAG, "没找到 binder，代理类=" + proxy.getClass().getName());
        }
        return firstAny;
    }

    /** 一个字段里可能直接是 binder，也可能是 @hide 的 connector 壳子，两种都要试。 */
    private static java.util.List<IBinder> candidates(Object value) {
        java.util.List<IBinder> out = new java.util.ArrayList<>();
        IBinder direct = asBinder(value);
        if (direct != null) {
            out.add(direct);
            return out;
        }
        if (value == null || !value.getClass().getName().startsWith("android.bluetooth")) {
            return out;
        }
        if (value instanceof android.bluetooth.BluetoothAdapter) {
            // BluetoothAdapter 里塞着一堆别的 binder（IBluetooth/IBluetoothManager…），
            // 钻进去只会误导，真身不在这
            return out;
        }
        try {
            Method m = value.getClass().getDeclaredMethod("getService");
            m.setAccessible(true);
            IBinder nested = asBinder(m.invoke(value));
            if (nested != null) {
                out.add(nested);
            }
        } catch (Throwable ignored) {
            // 不是 connector
        }
        if (out.isEmpty()) {
            for (Field f : value.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    IBinder nested = asBinder(f.get(value));
                    if (nested != null) {
                        out.add(nested);
                    }
                } catch (Throwable ignored) {
                    // 下一个
                }
            }
        }
        return out;
    }

    private static String descriptor(IBinder binder) {
        try {
            return binder.getInterfaceDescriptor();
        } catch (Throwable t) {
            return "(取不到: " + t + ")";
        }
    }

    private static IBinder asBinder(Object value) {
        if (value instanceof IBinder) {
            return (IBinder) value;
        }
        if (value instanceof IInterface) {
            return ((IInterface) value).asBinder();
        }
        return null;
    }

    private static Method pickMethod(Object iface, String name) {
        Method fallback = null;
        for (Method m : iface.getClass().getMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] types = m.getParameterTypes();
            if (types.length < 1 || !types[0].equals(BluetoothDevice.class)) {
                continue;
            }
            if (types.length == 1) {
                return m;
            }
            if (fallback == null) {
                fallback = m;
            }
        }
        return fallback;
    }

    /**
     * 构造 AttributionSource。
     *
     * <p>系统会校验「binder 调用 uid == source uid」，而这次事务是 Shizuku 服务端以
     * shell(2000) 身份发出的，所以 source 必须跟着写 shell，写本应用 uid 会被拒：
     * {@code Calling uid: 2000 doesn't match source uid: 10319}。
     */
    private static Object attributionSource() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        try {
            int uid = 2000;
            try {
                Method m = Shizuku.class.getMethod("getUid");
                uid = (Integer) m.invoke(null);
            } catch (Throwable ignored) {
                // 老版本 api 没有 getUid，shell 是 2000
            }
            Class<?> builderCls = Class.forName("android.content.AttributionSource$Builder");
            Object builder = builderCls.getConstructor(int.class).newInstance(uid);
            builderCls.getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
            return builderCls.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            Log.w(TAG, "构造 AttributionSource 失败", t);
            return null;
        }
    }
}
