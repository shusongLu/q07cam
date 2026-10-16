# 车机整体性能检查 & 卡顿归因（2026-10-16）

设备：`192.168.137.167:5555`（se1000_car / Android 11 / 6 核）

## 结论速览

**车机卡顿 = CPU 争用**，元凶是**厂商预装的常驻系统应用**，
不是我们的 App（我们 CPU 11.6%、上下文切换约占 1.3%）。

⚠️ 注意区分两个排行（**切换数第一 ≠ CPU 第一**）：

| 口径 | 第一名 | 第二~五名 |
|---|---|---|
| **CPU（决定卡顿）** | navi 44.2% | sdkservice 36.2% / speechassistant 34.2% / soabridge 33.0% / dms 18.3% |
| **上下文切换数** | audio.service 26,900/秒（29.5%）| soabridge 4,027 / sdkservice 1,407 / **我们 1,289** / surfaceflinger 1,136 |

audio.service 切换数最多，但**只烧 11.9% 单核**（一次切换约 3 µs）；
真正吃掉 CPU 的是导航/SDK/语音助手。两者都要看，但**定"卡顿元凶"应以 CPU 为准**。

| 指标 | 实测 | 正常参考 | 判定 |
|---|---|---|---|
| CPU 使用 | 370 / 600（62%）| — | 偏高 |
| Load average | 11 ~ 25 | < 6 | ✗ 超额 2~4 倍 |
| **PSI cpu some** | **avg10 28.6% / avg60 31.9% / avg300 35.3%** | < 5% | ✗✗ **平均 30% 时间在等 CPU** |
| PSI io / memory | **0 / 0** | — | ✓ 排除 IO、内存 |
| 上下文切换 | **87k~147k 次/秒** | 5k~20k | ✗✗ 线程风暴 |
| CPU 频率 | cpu0 2.3GHz / cpu4 1.8GHz（满频）| — | ✓ 无降频 |
| 进程 / 线程 | 428 / ~4150 | — | 多 |

**PSI 是"卡"的直接量化**：`some avg300 = 35%` 意味着**长期有约 1/3 的时间，任务在排队等 CPU**。

## CPU 占用排行（% = 单核百分比）

| 进程 | 第一次 | 第二次 | 说明 |
|---|---|---|---|
| com.incall.apps.navi.service | 44% | 40% | 导航服务 |
| android.hardware.automotive.soabridge@1.0-service | 31% | 33% | **所有车辆信号的桥（最稳定）** |
| com.incall.apps.speechassistant | 80% | 30% | 语音助手（波动大）|
| com.incall.apps.sdkservice | 34% | 20% | SDK 服务（含 3 个进程）|
| com.desaysv.ivi.dms | 6.8% | 30% | 舱内监控 AI（波动大）|
| com.incall.apps.sdalauncher(:vpa) | 18% | 20% | 桌面 |
| vendor.hardware.g2d / surfaceflinger / audio | 14% / 6.8% / 14% | 13% / — / 10% | 图形与音频 |
| caevsserver / evs.appservice | 17% / 8.5% | 10% / 10% | EVS 相机 |
| **de.dsa.ford.otaflash（我们）** | **11%** | **10%** | ✓ 第 6~8 位 |

### 第三次（口径已修正，10 秒窗口，进程级 stat）

```
整机忙时 = 62.6% of 6 cores = 3.76 核          ← 与第一/二轮的 62% 吻合

  44.2%  com.incall.apps.navi.service
  36.2%  com.incall.apps.sdkservice
  34.2%  com.incall.apps.speechassistant
  33.0%  android.hardware.automotive.soabridge@1.0-service
  18.3%  com.desaysv.ivi.dms
  16.6%  com.incall.apps.sdalauncher
  11.9%  android.hardware.audio.service
  11.6%  de.dsa.ford.otaflash            ← 我们
   2.6%  com.incall.app.sdamediaservice
   1.8%  com.incall.apps.sdkservice:service2
  -------
  210.4% 合计（单核）= 整机 3.76 核忙时的 56%
```

## 上下文切换归因（20 秒窗口，含全部线程）

```
内核总计            146,917 次/秒
按进程求和           90,628 次/秒（覆盖 62%）

   42,187 /秒   android.hardware.audio.service   ← ★★★ 占 47%，头号来源
    6,952 /秒   soabridge@1.0-service
    5,138 /秒   com.incall.apps.navi.service
    2,799 /秒   smaupdateagent
    2,354 /秒   com.incall.apps.sdkservice
    2,289 /秒   com.desaysv.ivi.dms
    2,244 /秒   logd
    2,194 /秒   de.dsa.ford.otaflash              ← 我们，第 8 位（约 2.4%）
    1,990 /秒   caevsserver
    1,953 /秒   surfaceflinger
    1,840 /秒   sdalauncher:vpa
    ...
```

> 说明：上表来自"遍历 428 个进程"的口径。遍历本身耗时十几秒，每个进程的增量
> 实际跨越了「20 秒 + 遍历耗时」，所以**数字偏高**。下面是精确计时（设备端 `sleep 10`，
> 单进程全部线程）复核后的**权威值**。

### 精确复核（10 秒窗口，设备端计时）

```
内核总计（/proc/stat ctxt）        88,300 次/秒
被测 7 个进程合计                  35,316 次/秒（占 40%）

  26,005 /秒   android.hardware.audio.service   ← ★★★ 头号，占内核总量约 30%
   3,952 /秒   soabridge@1.0-service
   1,326 /秒   com.incall.apps.sdkservice
   1,159 /秒   de.dsa.ford.otaflash              ← 我们（第 4 位，约 1.3%）
   1,136 /秒   surfaceflinger
   1,092 /秒   com.desaysv.ivi.dms
     646 /秒   com.incall.apps.speechassistant
```

### audio.service 的两个常驻空转线程（已定位到线程级）

```
android.hardware.audio.service (pid 404) ── 26,893 次/秒 = 内核总量 91,302 的 29.5%
  ├─ audio_hal_cap   (tid 31410)   13,284 次/秒   采集
  └─ audio_hal_ref   (tid 31411)   13,271 次/秒   参考信号（AEC 回采）
     合计 ≈ 26,500 次/秒，CPU 仅 3.9% + 3.9% ≈ 8% 单核
```

- 对应的采集会话 `AudioIn_18E`（16kHz / 双声道 / 512 帧缓冲）
  → 正常应约 **31 次填充/秒**，实际却折算成 **26,500 次切换/秒**，**差 850 倍**。
- 这两个线程是**懒创建后永不退出的常驻工作线程**：`starttime` 显示约 1 小时前创建，
  之后语音助手重启并开了**新**采集会话（`AudioIn_1AE`），**没有新建** cap/ref 线程。
- 它们**与"当前是否在录音"脱钩**：两条采集会话都已 `Standby: yes` 时，仍在 24,500 次/秒。
- 本质是厂商音频 HAL（`/vendor/lib64/hw/audio.primary.se1000.so`）的**高频轮询缺陷**
  （不是忙等烧 CPU，是每 ~3 µs 一次的唤醒/睡眠循环）。

### ❌ 实测证伪："关掉语音唤醒能解决"

| 步骤 | `audio_hal_cap`+`ref` | 内核总切换 |
|---|---|---|
| 关闭前（语音助手运行中）| 22,787 次/秒 | 82,422 次/秒 |
| `pm disable-user com.incall.apps.speechassistant` 后 | **24,548 次/秒** | 85,785 次/秒 |

**语音助手被禁用、进程被杀、所有采集会话进入 standby，HAL 线程照旧空转。**
→ "关语音唤醒"这条捷径**不成立**；该空转是 HAL 的固有行为。
（已 `pm enable` 还原，包状态回到 healthy。）

### 其他录音持有者（均已排除为主因）

| pid | 进程 | 备注 |
|---|---|---|
| 1807 | `com.incall.apps.sdkservice:service2` | 有 AudioRecord 线程，但仅 1.8% CPU / 157 切换/秒 |
| 3471 | `com.changan.evs.appservice` | RecordMonitor（EVS 录像）|
| 567 | `/system/bin/canomicservice`（root）| 线程 `NomicRecordThre` |
| 1087 | `system_server` | SessionRecordTh |
| 1539 | `com.incall.app.sdamediaservice` | 持有常开输出轨（44.1kHz/MEDIA），仅 2.6% CPU |

### 能否修掉？—— 不能

- `adb root` 被禁（`ro.secure=1`、production build）→ 无法 `setprop ctl.restart vendor.audio-hal`。
- HAL 位于 `/vendor`，非 root 不可改。
- 唯一理论途径是**重启音频 HAL**（销毁这两个线程），但需要 root 或厂商 OTA。

## ⚠️ 方法论纠正（重要）

- `/proc/<pid>/status` 的 `ctxt_switches` **只统计该进程的主线程**，
  用它求和在多线程系统上会低估一个数量级（曾得出 5,386/秒 vs 内核 88,300/秒）。
  **必须遍历 `/proc/<pid>/task/*/status` 求和。**
- 举例：我们 App 按错误口径得出 "15 次/秒（占 0.02%）"，按正确口径是 **约 1,159 次/秒（约 1.3%）**。
- 遍历口径会**系统性高估**（增量跨越了遍历耗时），定稿数字一律以设备端精确计时为准。

### CPU 口径纠正（第二次踩坑）

- 测进程 CPU **必须用进程级 `/proc/<pid>/stat`**（字段 14+15），
  **不能**遍历 `/proc/<pid>/task/*/stat` 求和。
- 原因：进程级 stat 是**线程组累计时间，包含已退出线程**，而 `task/` 只列活线程。
- 例：`audio.service` 进程级 = 219,933 ticks，但 24 个活线程求和只有 33,152（**差 6.6 倍**）。
- 错误口径会得出"各进程合计 685%"这种**超过整机 600%** 的荒谬结果；
  正确口径下被测 10 个进程合计 210.4%（单核），整机忙时 3.76 核（62.6%），逻辑自洽。
- HZ 已确认 = **100**（`getconf CLK_TCK`）。
- 真实整机忙时可用 `/proc/stat` 的 `(total - idle - iowait)` 增量比算出，**不需要 HZ**。

## 可行动项（按收益）

| # | 动作 | 预计收益 | 代价 | 状态 |
|---|---|---|---|---|
| ~~1~~ | ~~关掉**语音唤醒**~~ | ~~消掉 audio.service 26k 切换~~ | — | ❌ **实测无效，已证伪** |
| 1 | 清掉**导航**后台 | 44% CPU（最大头）| 导航不可用 | 未测 |
| 2 | 关掉 **sdkservice / 语音助手** | 34~36% CPU | 语音/SDK 功能失效 | 未测 |
| 3 | 关掉舱内 **DMS** 监控 | 18% CPU | 失去疲劳监测 | 未测 |
| 4 | 我们：双路预连 → 只预连一侧 | ~5% CPU | 另一侧打灯要重连 | 可选 |

> 结论：**能真正降 CPU 的都是厂商预装应用，关掉就失去对应功能。**
> audio.service 那 26.9k 切换/秒修复不了（要 root 或 OTA），
> 且它只占 11.9% 单核 —— 即使修掉也解决不了卡顿。
> 我们的 App（11.6%）在 CPU 排行第 7~8 位，不是原因。

---

## 车机恢复出厂设置后的基线（2026-10-17）

用户重置了车机（我们的 App 随之被删除）。开机 ~27 分钟后复测。

### 数值

| 指标 | 重置前 | 重置后 |
|---|---|---|
| Load average | 11 ~ 25 | **8.19 / 8.04 / 6.90** |
| **PSI cpu some** | avg300 = **35.3%** | avg300 = **15.1%**（avg10 13.8）|
| PSI io / memory | 0 / 0 | 0 / 0 |
| 内核总上下文切换 | 88,300 /秒 | **104,105 /秒** |
| `audio_hal_cap`+`ref` | 26,005 /秒 | **31,893 /秒**（占内核 30.6%）|
| 活跃录音会话 | 有 | 有（`AudioIn_5E` Standby: no）|

### 🔑 重要推论：切换数高 ≠ 卡

重置后**上下文切换反而更高**（104k vs 88k），但 **PSI 压力几乎减半**（15% vs 35%）。
→ **切换风暴不是"卡"的成因，CPU 需求才是。** 之前把 audio.service 的 26k 切换
当成头号嫌疑是过度归因，应回到 CPU 口径（谁是 CPU 消耗大户）。

### audio.service 是"内核态"消耗

`dumpsys cpuinfo`：`10% android.hardware.audio.service: **1.3% user + 9.4% kernel**`
→ 印证了之前的判断：这不是在"算"，而是在**高频系统调用/唤醒**上烧时间
（与"一次切换约 3 µs"的估算一致）。

### 复查修正："懒创建后泄漏"说得太满

重置这一轮里 `audio_hal_cap`/`audio_hal_ref` 的 tid 是 **744/745**（很低），
说明它们在**开机早期**就已存在，不是"开机一小时后才创建"。
更准确的说法：**它们在第一次采集会话建立时创建，之后不再退出**（本轮语音助手很快就开了麦）。
"空转 ~30k/秒、与当前是否在录音脱钩、非 root 修不掉"这几条结论不变。

### 重置后的 CPU 排行（dumpsys cpuinfo，5 分钟窗口）

```
29%  com.incall.apps.speechassistant        27%  com.incall.apps.navi.service
24%  soabridge@1.0-service                  11%  com.incall.apps.sdkservice
11%  sdalauncher:vpa                        10%  android.hardware.audio.service（内核态为主）
9.3% sdalauncher                             9.2% system_server
7.6% surfaceflinger                          7.3% com.desaysv.ivi.dms
7.2% com.changan.evs.appservice              6.9% graphics.composer@2.1-service
6.1% systemui                                5.1% adasService
4.5% logd                                    4.2% logcollector
3.5% data-collect-center                     3.1% camera.provider@2.4-service
2.8% com.incall.SoaBridgeTransit             2.4% sdamediaservice / bluetooth
```

**同一批厂商应用，一个不少** —— 重置并不会让车机变快，只是把"我们 App"这一项清零了。
