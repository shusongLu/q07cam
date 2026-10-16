# 车机整体性能检查 & 卡顿归因（2026-10-16）

设备：`192.168.137.167:5555`（se1000_car / Android 11 / 6 核）

## 结论速览

**车机卡顿 = CPU 争用引起的线程风暴**，元凶是**厂商预装的常驻系统应用**，
不是我们的 App（我们 CPU 10~12%、上下文切换约占 2.4%）。

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

### audio.service 异常

`dumpsys media.audio_flinger`：只有 **1 条活跃输出轨**，且 `framesWritten`
15 分钟才增加约 100 万帧（≈1000 帧/秒）。
**这点音量不可能需要 42,000 次/秒的上下文切换**（48kHz/240 帧缓冲的正常量级约 200 次/秒）
→ 高度怀疑厂商音频 HAL 存在**自旋/高频唤醒**，且很可能是被语音助手的常开录音驱动。

## ⚠️ 方法论纠正（重要）

- `/proc/<pid>/status` 的 `ctxt_switches` **只统计该进程的主线程**，
  用它求和在多线程系统上会低估一个数量级（曾得出 5,386/秒 vs 内核 88,300/秒）。
  **必须遍历 `/proc/<pid>/task/*/status` 求和。**
- 举例：我们 App 按错误口径得出 "15 次/秒（占 0.02%）"，按正确口径是 **2,194 次/秒（约 2.4%）**。

## 可行动项（按收益）

| # | 动作 | 预计收益 | 代价 |
|---|---|---|---|
| 1 | 车机设置里关掉**语音唤醒** | 可能消掉 audio.service 42k 切换的大头 | 失去语音唤醒 |
| 2 | 不用时清掉**导航**后台 | 40~44% CPU | 导航不可用 |
| 3 | 关掉舱内 **DMS** 监控 | 7~30% CPU | 失去疲劳监测 |
| 4 | 我们：双路预连 → 只预连一侧 | ~5% CPU | 另一侧打灯要重连 |
| 5 | 处理 logcollector（厂商日志上传）| ~7% CPU | 无 |
