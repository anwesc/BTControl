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
| `auto_profile` | 只动某一个 profile：`a2dp` / `hfp` / `leaudio` / `all`（默认 `all`）。只对 `连接` / `断链` 有意义，见下面「单 profile 连接/断链」 |
| `req` | 调用方自带的请求号，原样回显在结果里，用于对齐「哪一次请求」 |
| `finish` | 结束后自动关界面（`finish_after` 同义） |

`auto_action=state` 是只读查询：不改状态、不等动作，立刻返回当前 bond / profile 状态。

### 单 profile 连接/断链（3.1.0+）

默认动作是「整机」的：`连接` 会调隐藏的 `BluetoothDevice.connect()`，一次把能连的 profile 全连上。
要只动其中一个，加 `auto_profile`：

```bash
adb shell am start -n com.autotest.BTControl/.BluetoothControlActivity \
  -e auto_action 连接 -e auto_name DM_Speaker -e auto_profile a2dp
```

结果里的 `profile` 字段回显本次请求，`a2dp`/`hfp`/`leaudio` 三个字段说明实际状态。
`profile` 级 connect/disconnect 的 ack 比状态广播先到，所以连接类动作最多再采样 6 次（每 500ms）
把状态等出来，否则会读到动作前的旧值。

权限现实（真机实测，Xiaomi / Android 16 / sdk 37，普通签名包）：

| profile | 连接 | 断开 |
|---|---|---|
| `a2dp` | 可用 | 可用 |
| `hfp` | 被拒：`profile_not_permitted: HFP 连接 被拒(Neither user 10345 nor current process has android.permission.MODIFY_PHONE_STATE.)`；**CDM 关联也解不了这个**（见下），实际可用性靠 A2DP 拉起 ACL 后对端自己连 SLC | 可用 |
| `leaudio` | 在这台小米（Android 16 / sdk 37）上**代理永不回调**，动作会一直挂住；Pixel 9 上能返回 `profile_api_unavailable`（见下） | 同左 |

`BluetoothHeadset.connect()` 在这台机器上要 `MODIFY_PHONE_STATE`（signature|privileged），本包不是系统签名
（`dumpsys package` 的 install permissions 里连 `BLUETOOTH_PRIVILEGED` 都没有），所以 HFP 连接被拒。
**别指望换 `key/BTControl_sys.jks` 能通用**：平台签名是各品牌自己的私钥，实测这台小米
`/system/etc/security/otacerts.zip` 里的证书是 `root/tools/sign/miui_keys/releasekey.x509.pem`
（DER SHA-256 `D4:5F:07:6F:...`），与 `BTControl_sys.jks` 的 `C8:A2:E9:BC:...` 不是同一把；
而且非 `/system/priv-app` 安装也一样拿不到 privileged 权限。失败时如实回 `profile_not_permitted` 并带上系统原话。

`leaudio` 在 Pixel 9（Android 16 / sdk 36）上直接回
`profile_api_unavailable: LE Audio 连接 反射不可用（android.bluetooth.BluetoothLeAudio.connect [class android.bluetooth.BluetoothDevice]）`
—— 隐藏 API 反射被拦，普通应用连/断 LE Audio 没有可用入口。单 profile 动作带 12s 看门狗
（`profile_timeout`），避免代理不回调时 adb 侧永远等不到结果。

整机动作（`auto_profile=all`）下 `连接/断链` 也会调这三个 profile：被拒的那个不会让整体失败，
但会出现在结果的 `warnings` 里（例如 `["HFP 连接: profile_not_permitted: ..."]`），别只看 `ok=true` 就以为
三个 profile 都通了。整机 `连接` 用的是隐藏的 `BluetoothDevice.connect()`，实测被
`does not have a CDM association with the Bluetooth Device` 拒绝，真正干活的是后面的 profile 兜底。

**不传地址也能查到东西**（别让 adb 任务空跑）：

- `state` 不带地址时结果里多一个 `bonded` 数组，列出现有已配对设备（`name`/`addr`/`bond`）。
- `扫描` 不带地址时不再「一直等某台设备出现」，而是**列表扫描** `scan_sec` 秒，结果里多一个
  `devices` 数组：扫到什么报什么，扫不到就 `ok=false`、`reason=no_device_found`。
  已经配对的设备不会出现在 `devices` 里（UI 列表本来就不显示已配对项），要它们看 `bonded`。

### 开关＝连接状态，也能传参（3.1.0+）

详情页连接配置里那三个开关**就是连接状态**：拨开＝连这个 profile，拨关＝断这个 profile，拨动后在 12 秒内等系统确认，确认不了会自己拨回原位并把系统原话显示在结果行。

```bash
# 把 A2DP 拨开（等同点 A2DP 那一行）
adb shell am start -W -n com.autotest.BTControl/.BluetoothControlActivity \
  -e auto_action 开关 -e bluetooth_address 20:05:11:01:95:10 \
  -e auto_profile a2dp -e auto_enable 1 -e req sw1 --ez finish true

python mist/scripts/board_verify/phone.py btctl 开关 --name DM_Speaker --profile a2dp --enable 0
```

实现上 `开关` 在入口翻译成 `连接` / `断链` 再走同一套下发（HFP / LE Audio 有 Shizuku 时走 shell 通道），所以 `action` 上报的是翻译后的动作，`enabled` 只是回显你拨的方向；`auto_enable` 认 `0/1`、`true/false`、`on/off`，`auto_profile` 必须是具体 profile（`all` 会被拒：`switch_requires_profile`）。

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

### 取消配对成功后自动回列表（3.1.0+）

详情页点「取消配对」→ 确认 → 底部「✅ 已取消配对」，停留 1.2s（`FINISH_DELAY_MS`）后自动 `finish()` 回列表。原因：解绑后这台设备已从「已保存设备」移除，详情页再留着就是幽灵页。

### Shizuku 授权引导（3.1.0+）

Shizuku 服务端只能由 adb / root 以 shell 身份拉起，应用拿不到这个权限，所以内置的是**引导**而不是自动启动：

1. 装 Shizuku（`https://github.com/RikkaApps/Shizuku/releases`），打开它；
2. 用「无线调试」配对或用 USB 执行它的启动脚本把服务跑起来（或直接跑下面的 `scripts/start_shizuku.sh`）；
3. 详情页「连接配置」卡里有独立的一行 **Shizuku**，点它：未运行 → 「安装 Shizuku」，运行中未授权 → 「请求授权」→ 系统弹框允许；
4. 之后 CDM 走 `cmd companiondevice associate ... <MAC>`，HFP / LE Audio 也走 Shizuku。

这一行是 Shizuku 的**唯一入口**（它同时服务 CDM 关联和 HFP / LE Audio 主动连，不属于某一个功能）。CDM 按钮在没授权时不再弹引导，只在底部提示「先点 Shizuku 行去授权」。

无 Shizuku 时对话框还留了「仍用系统选择器」出口；设备已 root 的话也可以直接 `su -c` 跑同样的命令。

第 2 步可以一条命令搞定（脚本会自己从 `pm path` 推出 `lib/<abi>/libshizuku.so` 并启动，服务已在跑时直接跳过）：

```bash
./scripts/start_shizuku.sh                 # adb 在 PATH 或 ANDROID_HOME 里
ADB=/path/to/adb ./scripts/start_shizuku.sh
./scripts/start_shizuku.sh -s 9e237109     # 多设备时指定序列号
```

实测（小米 15 / Android 17）：`kill` 掉 `shizuku_server` 后脚本重启服务 → `pidof shizuku_server` 拿到新 pid，app 侧 `ShizukuBridge.status()` 仍是 `ok`，**不需要重新授权**（Shizuku 13 把已授权 uid 持久化了）。

### CDM 关联的适用范围与降级（3.1.0+）

- 有 Shizuku：按 MAC 直接建关联（`cmd companiondevice associate`），可靠；状态见「连接配置」卡里的 Shizuku 行。
- 授权入口在 Shizuku 行，不在 CDM 按钮上：点 CDM 只会提示去那一行（避免两处弹同一套引导）。
- 没有 Shizuku：只能走系统选择器，而选择器只列**正在广播**的设备；对端已连接时不广播，候选里往往是它自己的 LE 随机地址（每次还变），所以 CDM 行降级显示「CDM 关联（可选）」，点了才说明原因。
- Android 17 / HyperOS 实测：清空所有 CDM 关联后跑自动化整机 `连接` 仍然成功（`ok:true`、`assoc:false`、`a2dp:CONNECTED`），也就是说新系统整机 `BluetoothDevice.connect()` 不再依赖 CDM；旧系统仍会因缺关联抛 `does not have a CDM association`，所以这个入口保留但不当作主功能。

### CDM 关联的设备校验（3.1.0+）

公开 API 只能交给系统选择器，选择器只列**正在广播**的设备；已连接的对端不会出现，候选里往往是别的 LE 设备。因此 `onAssociationCreated` 一律比对 `AssociationInfo.getDeviceMacAddress()` 与本页地址：不一致就 `BtctlReporter.disassociate(...)` 撤销并提示「关联到了 X，不是这台设备」，避免把这台设备记成已关联。列表页自动化路径同样用 `associate_wrong_device: <mac>` 上报失败。

### 按钮禁用态（3.1.0+）

文字/描边按钮的 `android:textColor`、`app:strokeColor` 一律走 `res/color/*.xml` 的 ColorStateList（`button_destructive_text` / `button_destructive_stroke` / `button_secondary_text`），不要写死 `@color/action_destructive`：写死时按钮 `setEnabled(false)` 外观完全不变，用户以为还能点。

### 长按已保存设备：直接取消配对（3.1.0+）

长按「已保存设备」里的任一行，弹「取消配对？」确认框（正文带设备名，确认键标红），确认后解除配对并从保存列表移除。

- 以前长按弹的是「设备操作面板」（CDM 关联 / 取消关联 / 取消配对 / 关闭）。CDM 关联入口后来搬进了设备详情页的「CDM 关联」行，面板就成了重复入口，已删除：`layout/dialog_device_actions.xml` 一并移除，长按只留最常用的「取消配对」。
- 列表页仍保留 CDM 的自动化路径：`auto_action` 里需要关联时，`startCdmAssociation` 会走 Shizuku 按 MAC 建，没授权再退回系统选择器。

### 免弹框建/删关联：`cmd companiondevice`（AI 调试用这条）

系统那个选择器 adb 点不动，但 shell 有直接操作关联表的特权，成套命令齐全、无需任何弹框：

```bash
adb shell cmd companiondevice list 0                                     # 列出当前用户的关联（ID | 包名 | MAC）
adb shell cmd companiondevice associate 0 com.autotest.BTControl 20:05:11:01:95:10   # 直接建关联
adb shell cmd companiondevice disassociate 0 com.autotest.BTControl 20:05:11:01:95:10  # 按 MAC 删，别的关联不动
```

- `disassociate` 是**按 MAC 精确匹配**的：MAC 不存在会回 `Association doesn't exist.`，不会误删别人的（实测）。
- 老版本系统建的 legacy association 可能是 `mDeviceMacAddress=null`，这种用 MAC 删不掉，只能 `disassociate-all 0`（会把该用户所有关联清空）或在界面上撤。
- 建完回到 app 里 `btctl state` 就能看到 `"assoc": true`，整机 `connect()` 不再被 CDM 关联检查拒绝。
- app 里已经内置这条路：`ShizukuBridge.associateCompanionDevice()` 借 Shizuku 服务端 `newProcess`
  跑同一条命令（退出码 0 即成功），详情页「设备操作 · 关联 CDM」和长按面板「关联（CDM）」都先试它，
  没 Shizuku 授权或命令失败才退回系统选择器——因为选择器只列正在广播的设备，已连上的设备根本选不到。

### 坑：每秒重建列表会把长按吃掉（3.1.0+ 修掉）

之前「已保存设备」是 `scheduleWithFixedDelay(this::updateSavedDeviceList, 0, 1, TimeUnit.SECONDS)`，每秒 `clear()+addAll()+renderDeviceLists()` 一次。副作用不只是费电：

- `notifyDataSetChanged()` 每秒重建行 → 长按手势被 cancel，操作面板根本弹不出来（实测 `input swipe` 按住 1.5s 毫无反应）。
- 后台每秒 `getBondedDevices()` + 逐台 `getProfileConnectionState()`，ConcatAdapter 还会闪。

现在改成广播驱动 + 400ms 防抖：`savedDeviceStateReceiver` 订阅 `ACTION_STATE_CHANGED`（蓝牙开关）、`ACTION_BOND_STATE_CHANGED`、`ACTION_ACL_CONNECTED/DISCONNECTED`、`BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED`、`BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED`，收到就 `scheduleSavedDeviceRefresh()` 合并成一次重建；`onResume()` 也补一次（前台外可能错过广播），`onDestroy` 注销。

### 设备详情页（3.1.0+）

列表行现在只留「名字 / 地址 / ● 已连接」三行字。**整行点击不再进详情页**（避免误触），已保存设备行右侧多一个 `›` 箭头（`deviceDetailImageView`，周围设备/非常用设备不显示），点箭头才进 `DeviceDetailActivity`；长按仍是设备操作面板。
详情页版式按卡片分组，从上到下是：概览卡（名字 / MAC / 「取消配对」描边按钮 + 「连接·断开」主按钮，取消配队在左 / 最后刷新时间）→ 连接配置卡（A2DP / HFP / LE Audio 三行，名字 + 状态 + 开关；末尾一行 CDM 关联）→ 底部工具（复制快照 JSON）与结果行。

- 顶部是 MaterialToolbar（返回箭头 + 「设备详情」，`colorPrimary` 蓝底白字），返回键直接 `finish()`，不用再靠系统返回手势。
- 配色统一成蓝：主题 `colorPrimary` 由紫色 `purple_500/700` 改为 `@color/colorPrimary`（日间 #1565C0 / 夜间 #0D47A1），`colorSecondary`（开关、进度等强调色）由青绿改为蓝（#1E88E5 / #64B5F6），全 app 生效；破坏性操作的红色单独定义为 `action_destructive`（日间 #D32F2F / 夜间 #CF6679），「取消配对」按钮描边/文字与确认框确认键都用它，避免和主色混淆。
- 深色模式（`values-night`）单独校色：主色 #0D47A1、强调色 #64B5F6；页面底 `@color/background`（#121212）比卡片底 `surface_night`（#1E1E1E）暗一级，卡片边界能看出来；未连接文字提亮到 #9E9E9E，已连接 #81C784、过渡 #FFB74D；chips 换成深底亮字（#1B5E20/#A5D6A7、#4E2A00/#FFB74D、#303030/#BDBDBD）。`colorPrimaryDark` 原先还留着紫色 #4A148C（夜间状态栏发紫），已改成深蓝。
- 对话框：`MaterialComponents` 的 AlertDialog 按钮取 `colorPrimary`，夜间 #0D47A1 在深灰底上几乎看不见，所以单独定义了 `ThemeOverlay.BTControl.AlertDialog`（按钮用 `colorSecondary`、标题/正文显式给 `android:textColorPrimary`），并同时挂到 `materialAlertDialogTheme` 与 `alertDialogTheme` 上；红色确认键由 `tintDestructiveButton()` 在 show 之后改色。
- 头部：设备名、MAC，下面是「连接 / 断开」主按钮与「取消配对」；卡片最底一行「最后刷新 HH:mm:ss」，一眼看出这页是不是实时（配对状态不再单独占 chip，取消配对按钮的可用性就代表配对状态；CDM 状态由「连接配置 · CDM 关联」那行文字按钮的文案体现）。
- 头部下面是整机按钮：**只有一个按钮**，按当前状态自动显示「连接」或「断开」（有任一 profile 通了就算已连接）。它和三个开关是同一套动作的两层入口：点「连接」= 把还没连上的 profile 全部连上，点「断开」= 断开所有连着的；动作进行中显示「连接中…/断开中…」并禁用。
- 取消配对在整机操作卡里（红色文字、先弹确认框）；`关联 CDM` / `取消关联` 二选一显示在连接配置卡末尾的「CDM 关联」行：`关联 CDM` 优先走 Shizuku shell 按 MAC 建（`cmd companiondevice associate`，无需人工点选择器），失败或没授权时退回系统 `CompanionDeviceManager` 选择器（需 Android 12+）；`取消关联`、`取消配对` 都先弹确认框；`unpair` 成功后会从保存列表里消失。
- 「连接配置」三行（A2DP 媒体 / HFP 通话 / LE Audio），每行只有「名字 + 实时状态 + 开关」，行内不再放连接/断开按钮（3.1.0 精简）：状态字号带语义色（绿 `state_connected` / 橙 `state_transition` / 灰 `state_disconnected`），连接动作统一由上面的整机按钮按开关决定。
- **LE Audio 整行只在两端都支持时出现**：手机侧看 `BluetoothAdapter.isLeAudioSupported()`（Android 13+，返回 `SUCCESS` 才算，系统里 LE Audio 被关掉时这里就是 false）；对端（音响）没有公开 API，只能靠证据——连上过一次（`refresh()` 里写 `le_audio_peer` 偏好），或扫描广播里带 ASCS/BASS/PACS/VCS/MICS/CAS（`0x184E/0x184F/0x1850/0x1844/0x184D/0x1853`）任意一个，`utils/LeAudioSupport.java` 负责登记。两个条件缺一个就整行藏起来，也不用再让整机连接去试一条必然失败的路径。
- 每行最右的开关**就是连接状态**（照系统蓝牙设置页）：拨开即连该 profile、拨关即断；拨动后进入 12 秒等待确认（每 300ms 采样），到位显示「✅ X 已连接/已断开（状态已确认）」，超时或权限被拒就自己拨回原位并显示原因。checked 一律由真实连接状态回写（`applyProfileSwitchStates()`），用户拨动期间用 `suppressSwitchCallbacks` 防止回写和手势打架。正在跑的动作未结束时再拨开关会被拒并提示「正在处理上一批操作」。LE Audio 不支持时不是置灰，而是整行不显示。
- 状态变化由广播驱动（配对 / ACL / A2DP / HFP / LE Audio / 蓝牙开关，250ms 防抖），页面停在前台时断开也能立刻看到，不再只靠 `onResume` 采样。
- 动作下发后每 300ms 用 `BtctlReporter.profileSettled()` 轮询到终态，最多 12s：整机动作要**每个目标 profile 都到位**才显示「整机已连接/已断开（…均已确认）」，超时显示「12s 内没有任何 profile 状态到位」。只把「还没到位」的 profile 放进目标（已连上的不重复连、本来没连的不重复断），所以重复点不会刷出一堆假失败；部分 profile 下发就报错时显示「部分失败：<谁失败>；<其余>已到位」，把两边都说清，不再 ack 一到就报 ✅。
- 过渡态看门狗：只要有 profile 停在 CONNECTING/DISCONNECTING（典型是对端自己拉 HFP），每 500ms 再刷一次，最多 12s。广播只在状态切换的那一下响，读到的值有时还差一拍，没有这个看门狗页面会永久挂在「连接中…」。
- 只开 A2DP 开关去连时，HFP 也可能亮起来——这不是 app 发的指令：日志 `bta_ag_rfc_acp_open` 表明对端以 HF 身份主动开 RFCOMM 建 SLC，app 只调了 `BluetoothA2dp.connect()`。要 A2DP 单连得改 DUT 固件策略，或用系统蓝牙把 HFP 单独断开。
- 整机动作期间按钮与开关都禁用，防连点并发下发；正在处理的 profile 行内状态位显示「连接中…/断开中…」，等终态回来才按真实状态刷新。
- 蓝牙关闭时顶部橙色横幅（点一下拉起系统蓝牙开关），地址非法时横幅提示。动作已改成逐 profile 下发，不再走 `BluetoothDevice.connect()`，所以不再要求先建 CDM 关联（CDM 仍服务列表页的自动化整机 connect）。
- 页面只留一行小字说明（开关语义 + 权限说明合并成一句）；「取消关联 / 取消配对」的后果写在各自确认框里，不再在页面上重复堆三行提示，第一屏就能看完所有控件。
- 「连接 / 断开」和每行开关是同一套逻辑：逐 profile `connectProfile` / `disconnectProfile`（HFP / LE Audio 有 Shizuku 时走 shell 通道，否则退回普通 API 并把系统原话显示在结果行）；终态判定是「每个目标 profile 都到位」，单个 profile 的成败用「%1$s：%2$s」直接说这一行。
- 「复制该设备快照 JSON」把 `BtctlReporter.snapshot()` 的结果塞剪贴板，人调试时直接贴给 AI。
- 失败原因直接显示在页面底部（例如 `profile_not_permitted: HFP 连接 被拒(Neither user ... nor current process has android.permission.MODIFY_PHONE_STATE.)`），不用抓 logcat。
- 旋转 / 进程重建不再丢操作：busy 行、等终态的方向、底部提示都存进 `onSaveInstanceState`，重建后按原方向继续轮询（重新计 12s），不会出现「按钮已灰、状态永远停在连接中」。
- 蓝牙关闭时 `getName()` 通常拿不到名字，标题只显示一次 MAC，地址行自动隐藏，不再上下两行同一串地址。
- 长按行仍然是快捷面板（关联 / 取消关联 / 取消配对）。

坑：`theme` 是 NoActionBar 且内容不避让状态栏，详情页布局根节点要 `android:fitsSystemWindows="true"`，否则标题会顶到时间下面；`?android:attr/borderlessButtonStyle` 的 `Button` 在这套主题下渲染不出来（文字为空、点不着），换成 `Widget.MaterialComponents.Button.TextButton` 的 `MaterialButton` 才正常。

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
### 界面控件的无障碍标签（3.1.0+，给自动化点按钮用）

`screencap` + 坐标点击一改版就全废，所以详情页的每个可点控件都带了**稳定的 `content-desc`**
（读屏也靠它），`uiautomator dump` 能直接看到，`phone.py tap <标签>` 就能点：

| 控件 | 标签（content-desc） | 说明 |
|---|---|---|
| Toolbar 返回箭头 | `返回` | 没有文字，原来读屏只会读「按钮」 |
| 概览卡整机按钮 | `整机连接` / `整机断开` / `整机连接中` / `整机断开中` | 按钮**文字**随状态变（连接/断开），标签始终表达当前动作 |
| 概览卡取消配对 | 文本 `取消配对` | 已有唯一文本，直接用文本点 |
| A2DP / HFP / LE Audio 开关 | `A2DP 开关` / `HFP 开关` / `LE Audio 开关` | `Switch` 原本只有「开/关」没有名字；行的标签 TextView 还加了 `android:labelFor`，TalkBack 会连读 |
| Shizuku 行 | `Shizuku 授权` | 点它才出授权/安装引导 |
| CDM 行按钮 | `CDM 关联` / `CDM 取消关联` | 文本是「关联 CDM / 取消关联」，标签按「功能+动作」统一 |

注意整机按钮的标签**跟随状态**：未连时是「整机连接」、已连时是「整机断开」，所以 `tap 整机连接` 成功本身就代表「点之前确实没连」——标签即断言。`phone.py` 同时匹配按钮文字，`tap 断开` / `tap 连接` 也能点中。

同时状态都是普通文本（`已连接` / `未连接` / `连接中…` / `已授权` / `未运行`），结果行以 `✅` / `⚠️` 开头，可以直接 `phone.py ui --grep 连接中` 断言。

```bash
# 设备详情页里按标签点，不用记坐标
python mist/scripts/board_verify/phone.py tap "A2DP 开关"
python mist/scripts/board_verify/phone.py tap 整机断开
python mist/scripts/board_verify/phone.py tap "Shizuku 授权"
python mist/scripts/board_verify/phone.py ui --grep "开关|已连接|✅"
```


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
| `关联` | 是 | `assoc` | 走 CompanionDeviceManager 关联（3.1.0+），系统会弹「寻找设备」选择器，需要目标处于可发现/配对模式；关联成功后 `BluetoothDevice.connect()` 才被放行 |
| `开关` | 是 | `enabled` `profile` | 等价于把详情页该 profile 的开关拨到 `auto_enable` 的方向：内部翻译成 `连接` / `断链` 后走同一套下发（有 Shizuku 时 HFP / LE Audio 走 shell 通道），`action` 上报翻译后的动作 |

公共字段：`req`、`action`、`addr`、`ok`、`reason`、`elapsed_ms`、`ts`、`a11y`、`adapter`。

`ok=false` 时 `reason` 取值：`missing_device_address`（没给地址也找不到名字；
**点名了却解析不出来一定报这个，不会静默退化成全局状态**）、
`bluetooth_permission_denied`、`bluetooth_disabled`、`no_device_found`、`missing bluetooth permission`、
`unknown_profile`（`auto_profile` 写错）、`profile_not_permitted`（单 profile 调用被系统拒绝，
括号里是系统原话）、`profile_api_unavailable`（隐藏 API 反射不到，如 LE Audio）、
`profile_timeout`（12s 看门狗：代理没回调）、`cdm_unavailable`（系统没有 CDM 服务）、
`already_associated`（`ok=true`，本来就已经关联过）、`associate_failed: <系统原话>`
（常见 `user_rejected` = 在选择器里点了取消）、`invalid_device_address`，
以及动作自身的失败原因（如 `连接超时`、`扫描等待超时`）。

### 各动作会不会弹系统框（3.1.0+，写自动化脚本前先看这张表）

| 动作 | 系统 UI | 说明 |
|---|---|---|
| `state` / `扫描` | 无 | 纯读取，界面上只有列表在刷 |
| `连接` / `断链` / `开关` | 无 | `开关` 在入口就翻译成 `连接` / `断链`；被权限拒时只在结果里给系统原话（`profile_not_permitted`），不弹框 |
| `取消配对` | 无 | 直接 `removeBond()`，实测 515ms 收工、界面只留一条 toast。界面上长按列表行 / 详情页点「取消配对」才会弹确认框，那是给人用的路径 |
| `配对` / `重新配对` | **有**：系统配对框（配对码 / 「允许访问通讯录和通话记录」） | 靠无障碍服务点掉，`a11y=OFF` 基本必失败；`重新配对` 会先 `取消配对`（不弹框）再走这条 |
| `关联`（CDM，有 Shizuku） | 无 | `cmd companiondevice associate <userId> <pkg> <MAC>` 按 MAC 直连，不进选择器 |
| `关联`（CDM，无 Shizuku） | **有**：系统「选择要关联的设备」页 | 只列正在广播的设备；选项无法选中目标时会关联到别的设备（对端 LE 随机地址），app 会自动撤销并提示 |

### 踩过的坑（改这块之前先读）

- **`auto_name` 只在已配对列表里找**（3.1.0+）：没配对的设备用 `--name` 会直接
  `missing_device_address`，别指望它顺手扫出来；未配对的场景先用 `--addr`。
- **`-e` 传的都是字符串**：`-e scan_sec 15` 用 `getIntExtra` 取会静默拿到默认值，必须 `getStringExtra` + 解析。
- **已配对设备不出现在 `devices`**：UI 列表本来就不显示已配对项，要它们看 `state` 的 `bonded`。
- **广播接收器注册必须幂等**：`onResume` 注册、`onDestroy` 注销，重复调会让 `ACTION_FOUND` 回调翻倍；
  扫描开始前由 `startDiscovery()` 自己兜底注册，别只依赖 Activity 生命周期。
- **别在 `onPause` 里停扫描/注销接收器**：adb 触发时界面常被切后台，那样会「扫到一半变聋」。
- **`stopBluetoothDiscovery()` 会动 UI**，只能在主线程调；工作线程里调会 `CalledFromWrongThreadException`。
- **profile 级的 connect/disconnect 是「半特权 API」**：`BluetoothA2dp.connect/disconnect` 这台机器放行，
  `BluetoothHeadset.connect` 直接 `SecurityException`（要 `BLUETOOTH_PRIVILEGED`），别假设三个 profile 一样能用。
- **动作 ack ≠ 状态到位**：profile 级调用返回后状态广播还在路上，采样要轮询等，且「连上」等 `CONNECTED`、
  「断开」等「不是 CONNECTED」，方向反了会永远读旧值。
- **`BluetoothDevice.connect()/disconnect()` 要 CDM 关联**（3.1.0+ 才摸清）：普通应用调整机 connect 会抛
  `SecurityException: The application ... does not have a CDM association with the Bluetooth Device`，
  真正干活的是后面对每个 profile 的兜底调用；能用的三条路是 CDM 关联、系统签名 priv-app、shell 代理。
- **CDM 关联必须先在 manifest 声明** `<uses-feature android:name="android.software.companion_device_setup" android:required="false"/>`，
  否则 `associate()` 直接 `IllegalStateException: Must declare uses-feature android.software.companion_device_setup`。
- **`AssociationRequest.Builder#addDevice(BluetoothDevice)` 是 @SystemApi**，普通应用只能用
  `addDeviceFilter(new BluetoothDeviceFilter.Builder().build())`，代价是系统弹选择器让你点设备；
  选择器只列**可发现**的设备，已配对但不进配对模式的音箱不会出现（实测卡在「寻找设备」）。
- **CDM 关联只放开整机 `connect()/disconnect()`，不放开单 profile 的 HFP**（3.1.0+ 实测）：关联建成后
  `assoc=true`、`BluetoothDevice.connect()` 不再抛 `does not have a CDM association`，但
  `BluetoothHeadset.connect()` 仍然 `profile_not_permitted: ... MODIFY_PHONE_STATE`。想要主动连 HFP
  只能系统签名 priv-app / shell 代理；普通应用的实际效果是「连 A2DP 后用等对端拉起 HFP」。
- **CDM 的确认框 adb 点不动**（实测 Android 16）：`input tap` 在「要允许…吗？」这个系统框上无效，
  `uiautomator dump` 也抓不到它的节点；只有 `input keyevent 61`(TAB)+`66`(ENTER) 能确认，
  但它确认的是列表里的第一个设备，不能按名字挑。要指定设备只能人工点。
- **`BluetoothManager.getConnectedDevices(profile)` 在部分机型直接抛**
  `IllegalArgumentException: Profile not supported`（实测小米 Android 16），
  `BtctlReporter` 已退回 `adapter.getProfileConnectionState()`。

### Shizuku 代理：让 HFP 也能主动连（可选，3.1.0+）

普通应用调 `BluetoothHeadset.connect()` 会被 `MODIFY_PHONE_STATE` 挡住，CDM 关联也解不开。想主动连 HFP
只有一条免 root 的路：借 Shizuku 的服务端以 shell(uid 2000) 身份代发 binder 事务。

1. 手机装 Shizuku，用 App 里「查看指令」给的命令起服务端（`adb shell nohup <libshizuku.so> &`），
   确认 `ps -A | grep shizuku_server` 有以 `shell` 身份运行的进程。
2. `adb install -r BTControl_v3.1.0.apk`，先跑一次 `--via shizuku` 触发授权框，点「始终允许」。
   `btctl state` 里的 `shizuku` 字段会从 `shizuku_unauthorized` 变成 `ok`。
3. 连接：`phone.py btctl 连接 --name <设备名> --profile hfp --via shizuku`。

实测证据（Android 16，DM_Speaker）：`dumpsys bluetooth_manager` 的 HeadsetService 状态机里出现
`what=1(0x1) CONNECT` 这条**服务端发起**的记录（对端自己连回来时只有 `what=101 STACK_EVENT`），
随后 `EVENT_TYPE_AT_CIND`/`BIND` 完成，`hfp=CONNECTED`。

实现上的三个坑：
- Android 12+ 没有 `bluetooth_headset` 这个系统服务名（`service list` 里只有 `bluetooth_manager`），
  `SystemServiceHelper.getSystemService("bluetooth_headset")` 拿不到东西。
- `BluetoothHeadset` 代理里没有直接的 `IBinder`/`IInterface` 字段，IBluetoothHeadset 藏在 @hide 的
  connector 里，需要 `org.lsposed.hiddenapibypass:hiddenapibypass:4.3` 豁免后再反射取
  （`ShizukuBridge` 用 `getInterfaceDescriptor()` 匹配 `android.bluetooth.IBluetoothHeadset`）。
- `IBluetoothHeadset.connect(device, AttributionSource)` 的 `AttributionSource` 必须写 **shell** 的 uid
  （`Shizuku.getUid()`，兜底 2000），写应用自己的 uid 会被系统回
  `Calling uid: 2000 doesn't match source uid: <appUid>`。

Shizuku 没起/没授权时，`--via shizuku` 会明确回 `shizuku_not_running` / `shizuku_unauthorized`，
不会静默退回 native 路径。

**LE Audio 也走同一条通道**：`--profile leaudio --via shizuku`。普通应用反射不到
`BluetoothLeAudio.connect()`（@SystemApi，`NoSuchMethodException`），换成
`IBluetoothLeAudio.connect(device, AttributionSource)` 后就能以 shell 身份发出去。

- 代理返回 `false` 时上报 `shizuku_rejected: connect 返回 false（android.bluetooth.IBluetoothLeAudio）`
  —— 这是**系统受理但拒绝**，不是 Shizuku 没就绪。实测 DM_Speaker 就是这种情况：它是经典蓝牙音箱，
  不做 LE Audio，所以连/断都回 false。要验证 LE Audio 得换一台支持 LE Audio 的对端。
- 代理报成功但 3s 内状态没变成 CONNECTED 时，会改报
  `profile_not_connected: <profile> 等 CONNECTED 超时`，不让 `ok=true` 掩盖「什么都没发生」。
