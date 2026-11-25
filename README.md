# BTControl

用于测试 A2DP/HFP(Headset) 设备的安卓端自动化控制 App。

## 给 adb / AI 用的接口

启动时通过 Intent extras 传参：

```bash
adb shell am start -n com.autotest.BTControl/.BluetoothControlActivity \
  -e auto_action "配对" \
  -e bluetooth_address "AA:BB:CC:DD:EE:FF" \
  -e req "abc123" \
  --ez finish true
```

| extra | 说明 |
|---|---|
| `auto_action` | `state` / `扫描` / `配对` / `重新配对` / `连接` / `断链` / `取消配对` |
| `bluetooth_address` | 目标设备地址（也接受 `device_address`）；`state` 与不带地址的 `扫描` 可不传 |
| `auto_name` | 目标设备名（如 `DM_Speaker`）。没地址时按名字在**已配对**列表里找，先精确匹配再包含匹配；找不到就报 `missing_device_address`，不瞎猜（3.1.0+ 点名了却解析不出来直接失败，不再静默当全局查询） |
| `scan_sec` | 列表扫描跑几秒（默认 12，最小 3）。只在 `扫描` 不带地址时生效 |
| `req` | 调用方自带的请求号，原样回显在结果里，用于对齐「哪一次请求」 |
| `finish` | 结束后自动关界面（`finish_after` 同义） |

`auto_action=state` 是只读查询：不改状态、不等动作，立刻返回当前 bond / profile 状态。

**不传地址也能查到东西**（别让 adb 任务空跑）：

- `state` 不带地址时结果里多一个 `bonded` 数组，列出现有已配对设备（`name`/`addr`/`bond`）。
- `扫描` 不带地址时不再「一直等某台设备出现」，而是**列表扫描** `scan_sec` 秒，结果里多一个
  `devices` 数组：扫到什么报什么，扫不到就 `ok=false`、`reason=no_device_found`。
  已经配对的设备不会出现在 `devices` 里（UI 列表本来就不显示已配对项），要它们看 `bonded`。

### 结果怎么拿（3.1.0+）

每次动作结束（成功、失败、超时都算）固定输出一条：

```
I/BTCTL: BTCTL RESULT {"req":"abc123","action":"配对","addr":"AA:..","ok":true,
 "reason":"","elapsed_ms":8421,"ts":1732490000000,"a11y":"ON","adapter":"ON","name":"DM_Speaker",
 "bond":"BONDED","a2dp":"CONNECTED","hfp":"CONNECTED","leaudio":"DISCONNECTED"}
```

`a11y` 是「无障碍服务开着没」——关着的时候系统配对弹窗没人点，配对/连接会莫名失败，
看到 `a11y:OFF` 就先把无障碍开起来再看结论。

同时覆盖写 `/sdcard/Android/data/com.autotest.BTControl/files/btctl_result.json` 兜底，
因为 logcat 环形缓冲会被冲掉。

```bash
adb logcat -d -s BTCTL:I            # 按 tag 抓
adb shell cat /sdcard/Android/data/com.autotest.BTControl/files/btctl_result.json
```

**不要再从中文 UI 文案（`自动化操作完成: 配对`）里猜结果**，那个只给人和截图看。

### 界面底部回执条（3.1.0+）

自动化结果只在**底部**冒一行，没有 adb/脚本触发就不出现，不占用户首屏：

```
✅ 扫描  12006ms   · 点按复制
```

- 内容：`✅|⚠️ + 动作 + elapsed_ms + reason + 地址`，失败整行变红；和 logcat 里那份 JSON 同源。
- 点按 → 完整 JSON 复制到剪贴板，人调试时直接贴给 AI，不用抓 logcat。
- 长按 → 弹「运行环境」：蓝牙开关 / 无障碍是否开启 / A2DP、HFP 状态（`BtctlReporter.envLine`）。
- 这块最初是常驻顶部两行，占首屏且对普通使用无意义，后来挪到底部并按需出现。

### 界面列表（3.1.0+）

整页只有一个 `RecyclerView`（`deviceRecyclerView`），「已保存设备 / 周围设备 / 非常用设备」三块列表和它们的标题行用 `ConcatAdapter` 拼在一起，一起滚 —— 以前是三个 `RecyclerView` 塞进 `ScrollView`、还要手工测量高度，嵌套滚动和空白高度的问题都是这么来的。

- 「周围设备」标题行右侧就是扫描按钮（扫描中变成「停止扫描」+ 转圈），空的「已保存设备」整行自动收起。
- 「非常用设备」（多半是没名字的 BLE 广播源）**默认收起**，标题上带数量、右侧是「展开/收起」，避免为了看一个设备滑半天；搜索时自动展开。
- 深色模式：卡片背景/边框来自 `res/drawable-night`，文字走 `?android:attr/textColorPrimary/Secondary`；夜间主题父类与白天一致（都是 NoActionBar）。

### 一键调用

`mist/scripts/board_verify/phone.py` 已经把它封装成一条命令，自带等结果与退出码：

```bash
python scripts/board_verify/phone.py btctl state --addr AA:BB:CC:DD:EE:FF
python scripts/board_verify/phone.py btctl 配对 --addr AA:BB:CC:DD:EE:FF --timeout 130
python scripts/board_verify/phone.py btctl 连接 --name DM_Speaker --timeout 150   # 只有名字也行
python scripts/board_verify/phone.py btctl state                # 不带地址：看 bonded 清单
python scripts/board_verify/phone.py btctl 扫描 --scan-sec 15    # 列表扫描，列出周边设备
```

退出码：`0` 成功、`1` 动作失败、`2` 参数错或超时。

## 无障碍服务

`MyAccessibilityService` 负责自动点掉配对确认框、高音量警告、USB 弹窗等系统弹窗。
可以用 adb 打开，不必去设置页点：

```bash
adb shell settings put secure enabled_accessibility_services \
  "$(adb shell settings get secure enabled_accessibility_services):com.autotest.BTControl/.MyAccessibilityService"
adb shell settings put secure accessibility_enabled 1
```

`phone.py btctl` 会自动做这个增量写入（保留其它已启用的无障碍服务）。

## 直接手动用

`auto_action` 之外，界面上还能扫描、配对、连接、断开、取消配对，以及关键字搜索设备。

## 3.1.0 顺手修掉的三个坑

1. `BluetoothOperationUtils.hasBluetoothPermissions()` 原来返回的是「**缺少**权限」（`!= GRANTED`），
   而名字和 Javadoc 都说 `true = 已授权`，与 Activity 里的同名方法正好相反。现在统一为 `true = 已授权`，
   所有调用点改成 `if (!hasBluetoothPermissions())`。
2. `isAccessibilityServiceEnabled()` 拼的是 `getPackageName() + "/.automation.BTControlAccessibilityService"`——
   这个组件根本不存在（真实是 `com.autotest.BTControl/.MyAccessibilityService`），于是永远判成「没开」，
   无障碍按钮永远只会跳设置页。现在用 `AccessibilityManager` + Settings 双路判定，短名/全名两种写法都认。
3. 没蓝牙权限时 `executeAutoAction` 静默 `return`，调用方只能等到超时；现在立刻回一条
   `missing bluetooth permission` 的失败结果。

## 能力表（3.1.0+，一次查清每个动作要什么、回什么）

| `auto_action` | 需要目标设备 | 结果里关键字段 | 备注 |
|---|---|---|---|
| `state` | 否 | `adapter` `a11y`；**不带地址时**多一个 `bonded[]` | 只读，立刻返回，不改状态 |
| `扫描` | 否（带地址则退回旧语义） | **不带地址时** `devices[]`（`name`/`addr`/`bond`） | 列表扫描，跑 `scan_sec` 秒，默认 12、最小 3；后台不中断；扫不到 → `ok=false, reason=no_device_found` |
| `配对` / `重新配对` | 是 | `bond` `a2dp` `hfp` | 依赖无障碍点掉系统确认框，`a11y=OFF` 基本必失败 |
| `连接` | 是 | `a2dp` `hfp` `leaudio` | 成功后会额外等 profile 刷新（最多 2×500ms）再上报 |
| `断链` | 是 | `a2dp` `hfp` | — |
| `取消配对` | 是 | `bond` | 取消后再连需重新配对 |

公共字段：`req`、`action`、`addr`、`ok`、`reason`、`elapsed_ms`、`ts`、`a11y`、`adapter`。

`ok=false` 时 `reason` 取值：`missing_device_address`（没给地址也找不到名字；
**点名了却解析不出来一定报这个，不会静默退化成全局状态**）、
`bluetooth_permission_denied`、`bluetooth_disabled`、`no_device_found`、`missing bluetooth permission`，
以及动作自身的失败原因（如 `连接超时`、`扫描等待超时`）。

### 踩过的坑（改这块之前先读）

- **`auto_name` 只在已配对列表里找**（3.1.0+）：没配对的设备用 `--name` 会直接
  `missing_device_address`，别指望它顺手扫出来；未配对的场景先用 `--addr`。
- **`-e` 传的都是字符串**：`-e scan_sec 15` 用 `getIntExtra` 取会静默拿到默认值，必须 `getStringExtra` + 解析。
- **已配对设备不出现在 `devices`**：UI 列表本来就不显示已配对项，要它们看 `state` 的 `bonded`。
- **广播接收器注册必须幂等**：`onResume` 注册、`onDestroy` 注销，重复调会让 `ACTION_FOUND` 回调翻倍；
  扫描开始前由 `startDiscovery()` 自己兜底注册，别只依赖 Activity 生命周期。
- **别在 `onPause` 里停扫描/注销接收器**：adb 触发时界面常被切后台，那样会「扫到一半变聋」。
- **`stopBluetoothDiscovery()` 会动 UI**，只能在主线程调；工作线程里调会 `CalledFromWrongThreadException`。
- **`BluetoothManager.getConnectedDevices(profile)` 在部分机型直接抛**
  `IllegalArgumentException: Profile not supported`（实测小米 Android 16），
  `BtctlReporter` 已退回 `adapter.getProfileConnectionState()`。
