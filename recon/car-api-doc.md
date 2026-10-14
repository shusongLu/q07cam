# 车机 API 文档摘录（长安 8155 车机 API）

来源：长安开发者平台文档中心 → 8155车机API文档 / 虚拟车模块
URL 形如：
`https://open.sda.changan.com.cn/docCenter?marketCode=car-api-8155&documentNodeId=<节点>&documentId=<文档>`

> 这是**真车（8155 车机）的 API**，不是虚拟车。
> 调用方式：`CaSdkManager.getInstance().xxx(...)` + `key = Vehicle.<模块>.<信号>.key()`
> 获取/监听走「获取和监听方法说明」文档。

模块目录：车控模块、消息盒子模块、虚拟车模块（tbox / 驾驶 / 辅助驾驶 / 悬架 / 空调 /
雨刮 / 充电盖 / 电子手刹 / **能量电池** / 车窗 / **车灯** / 车门 / 座椅 / **行驶** /
车身硬件 / 伴我回家 / 极客空间）、蓝牙电话 / 蓝牙设置 / 声音 / 系统设置 / 长安云 /
智慧id / 子屏 / 业务 / 行程提醒 / 语音 / 地图 / 网络鉴权 / 消息事件 …

---

## 行驶服务（`Vehicle.drive.*`）

| 信号 | key | 类型/取值 |
|---|---|---|
| **车速** | `Vehicle.drive.speedValue` | int `0~460`（km/h）|
| 车速有效 | `Vehicle.drive.speedValidStatus` | `"0"`有效 / `"1"`无效 |
| 车速丢通信故障 | `Vehicle.drive.lostFaultStatus` | `"0"`正常 / `"1"`故障 |
| **档位** | `Vehicle.drive.gearLevel` | `"1"`P `"2"`R `"3"`N `"4"`D `"5"`reserved；`-1..-4`=无效档；`"0"`Invalid |
| 档位有效 | `Vehicle.drive.gearValidStatus` | `0`Valid / `1`Invalid |
| **总电流** | `Vehicle.drive.totalCurrent` | 无效 `-255`；有效 `0~1000`，**单位 A** |
| 制动踏板 | `Vehicle.drive.brakePedalStatus` | `"0"`未踩 / `"1"`有效踩踏 / `"2"`无效踩踏 |
| 总里程 | `Vehicle.drive.totalMileage` | String，km，精度 0.1 |
| 总里程(燃油) | `Vehicle.drive.totalMileageFuel` | 同上 |
| 总里程(纯电) | `Vehicle.drive.totalMileageElectricity` | 同上 |
| 复位里程 | `Vehicle.drive.resetMileage` | 总里程 − 复位时里程 |
| 车辆限速 | `Vehicle.drive.carSpeedLimit` | `0`关；`1`80；`2`60；`3`70；`4`90；`5`100 |
| 限速状态 | `Vehicle.drive.carSpeedLimitStatus` | `0`限速中 / `1`无限速 |
| 超时节电提醒 | `Vehicle.drive.powerTiOutSaving` | `0`未激活 / `1`已激活 |
| OTA 工作分组 | `Vehicle.drive.otaWGroupStatus` | jsonString |
| 电源档位控制状态 | `Vehicle.drive.powerShiftOperCode` | int[]（请求ID/会话ID/车控域/来源/响应码/计数）|

设置类接口（需权限，不走获取/监听）：
`controlDrive(...)`：`SET_CAR_SPEED_LIMIT`、`RESET_MILEAGE`、
`CONTROL_DRIVE_GEAR_LEVEL`、`CONTROL_ALL_GROUP_POWER` 等。

---

## 从厂商代码里挖到的 key 名（`com.incall.apps.sdkservice` / `com.incall.apps.energy`）

```
vehicle.drive.gearLevel          ← 档位（SOA 属性名形态）
vehicle.drive.speedValue         ← 车速
Vehicle.drive.totalMileage
Vehicle.energyRecycle.remainingElectric     ← 电池余量
Vehicle.energyRecycle.outPutCurrent         ← 输出电流
Vehicle.energyRecycle.dcAcOutPutU           ← 交/直流输出电压
Vehicle.energyRecycle.batChargeStatus / batChargeMode
Vehicle.energyRecycle.energyUiOption / energyUiStyle / energyShowReduce
Vehicle.energyRecycle.fiftyKmAvgRemain / fiftyKmAvgUse / fiftyKmEnyConsume
Vehicle.energyRecycle.hundredKmAvgUse / tenKmAvgRemain / tenKmAvgUse
Vehicle.energyRecycle.nearlyThirtyEgy / nearlyYearEgy
Vehicle.energyRecycle.alterChargeLimit / restMileInfo / EnyDistributeInfo
Vehicle.light.liSnsrLoBeamReq
Vehicle.drive.tripSummary
Vehicle.energyRecycle.tripEnergyConsume
```

---

## 待补

- 能量电池服务文档后半部分（功率/输出电流/剩余里程）—— 快照被截断
- 「获取和监听方法说明」文档（`CaSdkManager` 的用法）

---

## 能量电池服务（`Vehicle.energyRecycle.*`）

| 信号 | key |
|---|---|
| 能量回收强度模式 | `energyRecycleMode`（0 无设置；1 标准；2 强；0x3-0x67=0-100%；0x68 弱）|
| 充电锁自动解锁 | `chargeConnectStatus` |
| 充电口解闭锁 | `vcuChrglockSts`（0 其他、1 解锁到位、2 锁止到位）|
| 充电口提示 | `chargePortSwitchTips` |
| 能量工况模式 | `energyCnSelect`（CLTC/WLTC/动态续航）|
| 高压激活反馈 | `batteryHvMode`（0 Initializing 1 Standby 2 PreCharge 3 HVActive 4 Powerdown 5 Failure 7 ReadytoSleep）|
| 能耗选项 | `energyUiOption` |
| 能量显示方式 | `energyUiStyle`（0 电量百分比 / 1 里程）|
| 预约充电 | `reChargeInfo` |
| **小电池(12V)信息** | **`ibsInfo`** → int[]：`[0]`U_Batt 电压(mV 0~32767)、`[3]`I_BATT 充电电流(分段精度)、`[10]`SOC(%) … |
| 输出电流 | `outPutCurrent`（代码里挖到）|
| 交/直流输出电压 | `dcAcOutPutU`（代码里挖到）|
| 电池余量 | `remainingElectric`（代码里挖到）|
| 剩余里程 | `restMileInfo`（代码里挖到）|
| 近 10/30/50/100km 能耗 | `tenKmAvgUse` / `nearlyThirtyEgy` / `fiftyKmAvgUse` / `hundredKmAvgUse` … |

> 注意：`ibsInfo` 是**12V 蓄电池**的智能传感器，不是动力电池。
> 动力电池的电流/电压要看 `Vehicle.drive.totalCurrent` / `Vehicle.energyRecycle.outPutCurrent` / `dcAcOutPutU`。

---

## 车灯服务（`Vehicle.light.*`）—— 每个接口都带「分析日志TAG」

**这个 TAG 就是 SDK key → SOA 内部信号名的映射表！**

| SDK key | 说明 | 日志 TAG（SOA 信号名）|
|---|---|---|
| `frontLightStatus` | 前灯（0 关/1 位置灯/2 位置+近光/3 自动）| `Cnr_HdLampCtrl.Ntf_HdLampRunMod` |
| `rearFogLightStatus` | 后雾灯 | `Cnr_ReFogCtrl.Ntf_ReFogRunSt` |
| `frontFogLampStatus` | 前雾灯 | `Basc_ExtrLiActCtrlInfo.Ntf_FogInfo` |
| **`highBeamStatus`** | 远光灯 | **`Basc_ExtrLiActCtrlInfo.Ntf_HiBeamInfo`** |
| `autoHighBeamStatus` | 自动远光 | `vehicle.light.autoHighBeamStatus` |
| `backLightLevel` | 背景光等级 | `Basc_InnrLiSet.Ntf_BackGroundLiLvl` |
| `btnLightLevel` | 按键背光 | `Enh_BackGroundLi.Ntf_LiInten` |
| `domeLightStatus` | 顶灯联动 | — |
| 各区域氛围灯服务状态 | 中控/hud/A柱/顶灯/脚灯/车门/储物箱… | — |

> **转向灯**：对应 `Basc_ExtrLiActCtrlInfo.Ntf_TurnLiInfo`（同一命名族），
> 与 logcat 实测 `0x419831 = Basc_ExtrLiActCtrlInfo.Ntf_TurnLiInfo` 完全一致。

---

## 打通两条线的关键

```
SDK key (文档)  →  SOA 信号名 (文档的"分析日志TAG" / logcat)  →  SOA id (logcat 实测)
Vehicle.drive.gearLevel   →  Basc_GearBasInfo.Ntf_GearBasInfo   →  0x418045
Vehicle.light.highBeamStatus → Basc_ExtrLiActCtrlInfo.Ntf_HiBeamInfo → 0x419831(族)
```

**但要记住**：logcat 里的这些 id 是**服务端内部 id，客户端 `getProperty` 读不到**（返回 null）。
客户端可见的是另一套 id（`0x40xxxx`/`0x41xxxx`/`0x7085xx`/`0xE0xxxx`，共 2102 个）。

**所以最终要靠"取值域 + 行为"把客户端 id 认出来**：
| 目标 | 取值特征（来自文档）|
|---|---|
| 档位 | `1~5`，且挂挡时变 |
| 车速 | `0~460` |
| 总电流 | `-255` 或 `0~1000` |

**下一步**：开车/挂挡时跑 SOA 全量监视器，按上面的取值特征对号入座。

---

## 与 SOA 的关系（要点）

- 车机上 SOA HIDL 服务 `android.hardware.automotive.soabridge@1.0::ISoaBridgeNative`
  对任何进程开放（SELinux Permissive），已验证可连、可读 **2102** 个属性
- 但 HAL 只认**数字 id**；`Vehicle.xxx` 这类**名字**由厂商 SDK（`CaSdkManager` /
  `casdkmanager`）映射到 id
- 文档里的「分析日志TAG」把 **SDK key → SOA 内部信号名** 打通了
- 剩下的最后一跳（SOA 信号名 → 客户端可见 id）用**行为 + 取值域**标定
