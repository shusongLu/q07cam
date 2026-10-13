# 上车作战手册（长安启源 Q07 原厂车机）

> 目标：一趟车搞清"电池功率 / 发动机转速 / 多屏 / 转向灯信号"从哪来。
> 原则：**只读取证，不改系统，不写总线。驻车、通电状态下操作。**

---

## 0. 出发前检查（在屋里做完）

- [ ] 笔记本 + 充电器
- [ ] **USB 数据线**（无线 ADB 不通时的备用）
- [ ] 手机热点（车机与笔记本都要能连上同一网络；必要时用热点把两边都接进来）
- [ ] 环境自检（在屋里跑一遍，别到车上才发现问题）：

```powershell
cd C:\Users\lushu\projects\q07battery
.\gradlew.bat --offline :app:assembleDebug        # 应 BUILD SUCCESSFUL
& "$env:REA_ADB_PATH" version                     # adb 可用
```

- [ ] 确认离线全部就绪（见 `README.md` 的环境表）：
  JDK / Android SDK / Gradle / REA / Jadx / Apktool / adb 全在本地，**不需要网络**。

---

## 1. 连上车机（无线 ADB）

1. 车机：开发者选项 → 打开 **USB 调试 / 无线调试（ADB）**
2. 拿车机的 `IP:端口`（在无线调试页面）
3. Android 11+ 首次需要配对：

```powershell
adb pair <IP>:<配对端口>      # 输入车机上显示的 6 位配对码
adb connect <IP>:<连接端口>   # 注意：配对端口 ≠ 连接端口
adb devices -l
```

**连不上时排错**（和手机那套一样）：

```powershell
adb mdns services             # 分别看 _adb-tls-pairing 与 _adb-tls-connect 的真实端口
adb disconnect
adb kill-server; adb start-server
```

> 若车机没开无线调试但有 USB，就插线；注意可能需要车机侧授权弹窗。

---

## 2. 一键取证（核心步骤）

```powershell
cd C:\Users\lushu\projects\q07battery\recon
.\adbrecon.ps1 -Connect <IP>:<连接端口> -TurnSignal -PullApks
```

它会自动：
- 收集系统属性、服务清单、`dumpsys car_service`、**广播历史（关键）**、屏幕清单
- 抓日志快照
- 按提示**分段抓动作日志**（打左转灯 / 打右转灯…）
- 把候选系统 App 拉到 `apks/`
- 生成 `SUMMARY.md`

**再加几个动作会更有价值**（每次动作单独一个日志文件，便于对比）：

```powershell
.\adbrecon.ps1 -Serial <IP>:<端口> -Actions `
  "挂D挡","挂R挡","踩加速（功率上升）","松油门滑行","踩刹车（能量回收）","打开360全景影像","打开空调"
```

只想实时看日志时（不落盘）：

```powershell
.\livewatch.ps1 -Connect <IP>:<端口>
# 然后一边操作车机，一边看刷出来的行
```

---

## 3. 结果在哪、先看什么

产物目录：`recon\recon-out\<时间戳>\`

| 优先级 | 文件 | 看什么 |
|---|---|---|
| ★★★ | `SUMMARY.md` | 总览：是否 AAOS、屏幕清单、广播命中、候选包 |
| ★★★ | `17-broadcasts.txt` / `17b-broadcasts_filtered.txt` | **最近广播 + 已注册接收者** → 车辆信号是不是广播发的 |
| ★★★ | `11-car_service.txt` | 若 AAOS：车辆属性 ID 与当前值（功率/转速/转向灯可能直接可见） |
| ★★ | `24-candidate_receivers.txt` | 候选系统 App 监听了哪些 action（常能直接读出信号广播名） |
| ★★ | `5x-action-*.txt` | 每个动作前后的日志差异 → 哪个动作触发哪条日志 |
| ★★ | `10-services.txt` | 有没有 vehicle / hud / avm / camera 服务 |
| ★ | `13-display.txt` / `14-window_displays.txt` | 中控/副驾/HUD 是否独立 Display |
| ★ | `23-candidate_detail.txt` | 候选 App 的权限、签名、sharedUserId |

---

## 4. 判定树（决定项目往哪走）

```
11-car_service.txt 有内容？
├─ 是 → 车机是 AAOS
│     → 记下 power/rpm/turn 相关的属性 ID
│     → 回来用反射调 CarPropertyManager（避免编译期依赖）
└─ 否
      ↓
17-broadcasts / 24-candidate_receivers 里有车辆相关 action？
├─ 是 → 广播方案可行（最理想）：普通 App 就能收
│     → 记下 action 名 + extras 键名，回来实现 RealVehicleSignalSource
└─ 否
      ↓
10-services 里有车辆服务？
├─ 是 → 试着 bind（用 dumpsys package <pkg> 看权限是否 signature 级）
│     ├─ 非 signature → AIDL 方案可行
│     └─ signature → 普通 App 拿不到，功率/转速大概率做不了
└─ 都没有 → 功率/转速放弃（不 root 无解）
```

**副驾屏 / HUD / 侧摄像头同理**，都在 `13-display.txt`、`10-services.txt`、`24-candidate_receivers.txt` 里找线索。

---

## 5. 顺带一定要试：别的安装姿势

`adb install` 被禁，但**可能只禁了这一个命令**。这决定你以后迭代快不快：

```powershell
adb install -r app-debug.apk
adb shell pm install-create / install-write / install-commit
adb push app-debug.apk /data/local/tmp/ ; adb shell pm install /data/local/tmp/app-debug.apk
```

任何一条能通，都请记下来。

---

## 6. 安全与边界

- **只读**：不要向 CAN/系统写任何东西
- **驻车操作**：不要边开边抓
- 不要修改系统分区、不要删系统 App
- 车机改动可能影响保修/OTA —— 心里有数
- 抓到的日志可能含位置等信息，外发前脱敏

---

## 7. 收工前

- [ ] 把 `recon\recon-out\<时间戳>\` **整个目录**带回来（或拷到别处）
- [ ] 如果日志里有明显可疑的 action / 属性名，记在便签上
- [ ] 确认 `apks/` 里的候选 App 都拉全了（没拉全可用 `-PullApks -PullLimit 30` 再来一次）

带回来我接着：用 REA 逆向 `apks/`，定位信号源 → 实现 `RealVehicleSignalSource` → 上车验证。
