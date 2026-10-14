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

- 能量电池服务文档（功率/电流/SOC 的完整 key 与取值）
- 车灯服务文档（转弯灯 key）
- 「获取和监听方法说明」文档（`CaSdkManager` 的用法）

## 与 SOA 的关系（重要）

- 车机上 SOA HIDL 服务 `android.hardware.automotive.soabridge@1.0::ISoaBridgeNative`
  对任何进程开放（SELinux Permissive），已验证可连、可读 2102 个属性
- 但 HAL 只认**数字 id**；上面的 `Vehicle.xxx` **名字**由厂商 SDK 映射到 id
- 下一步：用"名字对应的已知取值域"（如档位 1~5、车速 0~460、电流 -255~1000）
  对 SOA 里变化的 id 做**行为标定**，把 id 认出来
