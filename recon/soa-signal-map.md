# SOA 信号字典（逆向厂商适配层得到）

> 结论：**不用装 App、不用改 /system、不用开车**，就能从任意 root 进程按名字读实时信号。
> 方法 = 反编译厂商自己的适配层 `com.incall.soabridgeadapter.*`，抄它的 id。

---

## 1. 厂商的软件是怎么做的

链路上有三层，我们**直接跳到最下面一层**：

```
应用 (能量 App / EVS)
   └─ CaSdkManager.getVehicleInfoSync(["vehicle.drive.gearLevel", ...])     ← 要 bindService，app_process 用不了
        └─ com.incall.apps.sdkservice（SDK 服务，AIDL）
             └─ com.incall.soabridgeadapter.***ClientEmbedded                  ← ★ 真正干活的地方，只有 getProperty(id)
                  └─ SoaBridgeClientManager.getSoaBridgeIntProperty(id)
                       └─ ISoaBridgeNative (HIDL) ── SOA ── CAN
```

`*ClientEmbedded` 里的每个 getter 都是同一个套路：

```java
public int[] getVehSpd() {
    return this.mSoaBridgeClientManager.getSoaBridgeIntArrayProperty(4292614);  // 0x00418006
}
```

所以我们**只要把 id 抄下来**，用自己的 `app_process` + `ISoaBridgeNative` 就能读。

---

## 2. 读取方法（已验证）

```bash
# dex 来源：/system/app/SoaBridgeTransit/SoaBridgeTransit.apk 和 com.incall.apps.sdkservice 的 APK
CLASSPATH=<我们的dex>:/data/local/tmp/sdk1..5.dex:/data/local/tmp/soa1..12.dex \
  app_process /system/bin <我们的类>
```

```java
Class<?> iface = Class.forName("android.hardware.automotive.soabridge.V1_0.ISoaBridgeNative");
Object svc = iface.getMethod("getService").invoke(null);
Object client = Class.forName("com.incall.soabridge.impl.SoaBridgeServiceManagement.SoaBridgeNativeClient")
        .getConstructor(iface).newInstance(svc);
Object prop  = client.getClass().getMethod("getProperty", int.class).invoke(client, id);
Object value = prop.getClass().getMethod("getPropertyValue").invoke(prop);   // 多为 Integer[]
```

- `ID_GET_*`（可直读）与 `*_CHANGE/_EVENT`（仅订阅）是两套 id。
- HIDL 还有一个 **通知 id 空间**：logcat 会直接打印 `NTF propid:0x00413000 name:Basc_BcuOperInfo.Ntf_BcuOperStsInfo`，可用来对名字。

---

## 3. 车辆信息 `SoaBridgeVehicleInfoClientManager`

来源类：`com.incall.soabridgeadapter.car.vehicleinfo.SoaBridgeVehicleInfoClientEmbedded`

| id | 方法 | 含义 |
|---|---|---|
| `0x00418006` | `getVehSpd` | **车速** `int[]` |
| `0x00418037` | `getGearPosnInfo` | **挡位** `[0]=挡位, [1]=无效标志` |
| `0x00408004` | `getPowerShiftInfo` | 电源挡位（0=OFF 1=ACC 2=ON 3=START）|
| `0x00408015` | `getEdcTarGear` | 目标挡位 |
| `0x00409918` | `getDrvrGearDmd` | 驾驶员挡位请求 |
| `0x00418046` | `getGearErrInfo` | 挡位故障 |
| `0x00418085` | `getBrkPedlStsInfo` | 制动踏板 |
| `0x00418088` | `getSteerAglInfo` | 方向盘转角（-52 → -5.2°）|
| `0x00501000` | `getTotalMilg` | **总里程** |
| `0x00511001` | `getReevTotalMilg` | 增程/纯电里程 |
| `0x00408007` | `getLostVehSpd` | 车速丢失 |
| `0x00419883` | `getEspWhlSpd` | 轮速 |
| `0x00401086` | `getVehSpdLmtSts` | 限速状态 |
| `0x00413031` | `getVehSpdLmtCnl` | 限速取消 |
| `0x00419812` | `getTireValInfo` 等 | 胎压 |
| `0x00419814` | `getTireWarnInfo` 等 | 胎压报警 |
| `0x00419788` | `getPtLampTextDispInfo` | 仪表文字显示（见 §6）|
| `0x00412055` | `getResiMilgInfo` | **剩余里程** |
| `0x0041317B` | `getEngThmlInfo` | **发动机热管理** —— `[8]` = **实际转速 rpm**，`[4]` = 目标转速（实测见 §9）|

其他（节选）：`getAVHFucSts=0x418019`、`getEspLatAccel/getEspLongAccel/getEspYawRate=0x419883`、
`getSasSteerAgVld=0x419881`、`getHvSysStsRes=0x418209`、`getChassisTipInfo=0x413215`、
`getSteeringFltSts=0x413216`、`getCalcPressValue=0x413224`、`getCrashOutSts=0x418090`、
`getBasc_*Online=0x409xxx`（在线标志位，不是数值）。

## 4. 能量 `SoaBridgeEnergyClientManager`

来源类：`com.incall.soabridgeadapter.energy.SoaBridgeEnergyClientEmbedded`

| id | 方法 | 含义 |
|---|---|---|
| `0x00412050` | `getVehEgyCnseInfo` | **整车能耗信息** |
| `0x00413212` | `getVehEgyPercInfo` | **电量百分比** |
| `0x00413124` | `getVehFuelCnseInfo` | 燃油消耗信息 |
| `0x00412055` | `getResiMilgInfo` | **剩余里程** |
| `0x00413197` | `getHvBattCpInfo` | 高压电池信息 |
| `0x00413100` | `getBattStatus` | 电池状态 |
| `0x00402163` | `getBattHVSts` | 高压电池状态 |
| `0x00413070` | `getReevOperStsInfo` | **增程器运行状态** |
| `0x00413072` | `getReevOperPrmInfo` | **增程器运行参数** |
| `0x00411087` | `getEgyMagtModSts` | 能量管理状态 |
| `0x0041317A` | `getEgyRecyclMod` | 能量回收模式 |
| `0x00402062` | `getEnergyRecycleMode` | 能量回收模式 |
| `0x00412162` | `getEgyCnseSelect` | 能耗显示选择 |
| `0x00413228` | `getChrgPilePwrSplyFuncInfo` | 充电桩供电信息 |
| `0x00413029` | `getIBSInfo`（battery 类）| 12V 小电池 |

## 5. 动力 `SoaBridgePowertrainClientManager`

来源类：`com.incall.soabridgeadapter.car.powertrain.SoaBridgePowertrainClientEmbedded`

| id | 方法 | 含义 |
|---|---|---|
| `0x00419307` | `getBcuOperPrmInfo` | **BCU 电池控制单元 运行参数** |
| `0x00419306` | `getBcuOperStsInfo` | BCU 运行状态 |
| `0x00419309` | `getPduOperPrmInfo` | **PDU 高压配电单元 运行参数** |
| `0x00419308` | `getPduOperStsInfo` | PDU 运行状态 |
| `0x00419311` | `getVcuOperPrmInfo` | VCU 整车控制器 运行参数 |
| `0x00419310` | `getVcuOperStsInfo` | VCU 运行状态 |
| `0x00413066` | `getFmipuOperInfo` / `getFrntMotTq` | **前电机 / 逆变器** |
| `0x00419909` | `getLoUPortSt` | 低压端口 |

## 6. ★ 厂商自己写的"字段解码"（最重要）

在 **`com.changan.evs.appservice`** 里找到 `com.incall.apps.common.manager.SoaBridgeManager`，
它就是厂商**解读这些数组**的地方 —— 直接抄它的下标即可：

```java
// 挡位： gearPosnInfo[0] 是挡位，[1] 是无效标志
public int getGear_Get() {
    int[] p = vehicleInfoClientManager.getGearPosnInfo();   // 0x00418037
    return p[1] == 0 ? p[0] : 1;                            // 1=P
}
// 常量：GEAR_P=1, GEAR_R=2, GEAR_N=3, GEAR_D=4（另有 GEAR_INVALID / GEAR_RESERVED）

// 电量 SOC（厂商代码里方法名叫 getBatteryPower 其实起歪了，
// 调用它的是 updateBcuSocState，值就是仪表上的电量百分比）
public int getBatteryPower_Get() {
    int[] p = vehicleInfoClientManager.getPtLampTextDispInfo();  // 0x00419788
    return Math.max(p[25], 0);                                   // ← [25] = SOC 电量%
}

// 高压电压
public int getChargeHighV_Get() {
    int[] p = powertrainClientManager.getBcuOperPrmInfo();       // 0x00419307
    return p[12];                                                // ← [12] = 高压电压(V)
}
```

**实测验证**：
- `BcuOperPrmInfo[12] = 374` → 374 V ✓
- `PtLampTextDispInfo[25] = 47` ↔ 车机状态栏同时显示 **47%** ✓ （SOC 板上钉钉）

---

## 7. 实测值（2026-10-14，P 挡驻车）

```
0x00418037  getGearPosnInfo      = [1, 0]              ← 挡位 P，无效标志 0
0x00418006  getVehSpd            = [0, 0, 0, 0]        ← 车速 0
0x00408004  getPowerShiftInfo    = 2                   ← 电源 ON
0x00418085  getBrkPedlStsInfo    = [0, 1, 0, 0]
0x00418088  getSteerAglInfo      = [-52, 0, 0, 0]      ← -5.2°
0x00501000  getTotalMilg         = 102447
0x00511001  getReevTotalMilg     = [32589, 69858]
0x00412055  getResiMilgInfo      = [274, ...]          ← 剩余里程 274 km
0x00419788  getPtLampTextDispInfo= ...[25]=48...       ← 电池功率 48
0x00419307  getBcuOperPrmInfo    = [3741, 10012, 475, 142, 151, 8191, 149, 11, 0,
                                    1485, 88, 112, 374, 400, 72, 70, 70, ...]
                                                          ↑[12]=374 高压电压 V ✓
0x00419309  getPduOperPrmInfo    = [390, 0,0,0,0,0,0,0, 3737, 220, 660, 5000, 0, 3737,
                                    595, 83, 73, 73, ..., 269, 145, ...]
                                                          ↑[8][13]=373.7V(母線电压)
0x00413072  getReevOperPrmInfo   = [2369, 205, 69, 11, 0, 20, 274, 0, ...]
0x00413212  getVehEgyPercInfo    = [0, 77, 66, 78, 33, 8, 10, 11, 0,0,0,0, 67, 15, 24, 11, 0, 0]
0x00412050  getVehEgyCnseInfo    = [10335, 0, 4213, 607, 583, 3000, 3000, 3000, 626, 637, 631, ...]
0x00411087  getEgyMagtModSts     = [20, 20, 50, 1, 1, 0, ...]
```

### 用"空调"当可控负载做的标定（不用开车）

| 信号 | 空调关 | 空调中 | 空调最大 |
|---|---|---|---|
| `0x00419309[8]/[13]`（母线电压）| 3738 | 3737 | **3733**（带载下降）|
| `0x00419309[0]` | 226 | 378 | **690**（随负载线性上升）|
| `0x00419309[22]` | 156 | 260 | **476**（与 [0] 同比例 1.45）|
| `0x00419307[9]` | 1707 | 1542 | 3459 |
| `0x00408609` | 0 | 85 | 0 |
| `0x00416017`（空调状态位）| [1,1,1,1,2,0,3,…] | … | [1,1,1,1,2,0,4,…] |

→ **`0x00419309` = PDU 高压配电运行参数**，其中 `[8]/[13]` 是母线电压、
`[0]`/`[22]` 是随负载变化的电流/功率量。**实时功率就来自这里。**

---

## 8. 还没定的两件事

1. **`0x00419309[0]` 的量纲**（×0.1 A？×0.01 kW？）——
   需要一次"已知负载"参照（快充桩功率 / 或行驶后在车机能耗里对账）。
2. **`0x00413212`（电量百分比）里哪个下标是 SOC** ——
   数组有 18 个元素且驻车时不动，需在电量变化时对照。
3. **转速**：`0x00413072` 增程器运行参数里应有，但增程器未启动时全是常数，
   需增程器启动时取样。

---

## 9. 【已修正】发动机（增程器）转速 = `[8]`，不是 `[7]`

**`0x0041317B`（`getEngThmlInfo` 发动机热管理）**

| 下标 | 含义 | 依据 |
|---|---|---|
| **`[8]`** | ✅ **实际转速 rpm** | 熄火 0；驻车充电 慢/中/快 = **990 / 1090 / 1350** |
| **`[4]`** | ✅ **目标转速 rpm** | 熄火瞬间清零；随档位变化 954 / 1360 / 1400 |
| `[7]` | ❌ 不是转速 | 熄火时仍读 4220，全程几乎不动（±4%） |
| `[0]` | 水温（×0.1℃） | 熄火后升温 |
| `[10]` | 负载/状态 | 熄火 30 / 运行 100 |
| `[3]` | 状态位 | 熄火 0 / 运行 2 |

### 标定方法：切「驻车充电」档位（慢→中→快→慢，每档 5 秒，300ms 采样）

| 档位 | `[8]` | `[4]` | `[7]` | PDU[0] |
|---|---|---|---|---|
| 慢 | **990** | 950 | 4220 | 515 |
| 中 | **1090** | 1360 | 4260 | 578 |
| 快 | **1350** | 1400 | 4390 | 550 |
| 慢（切回）| **990** | 956 | 4390 | 538 |

`[8]` 三档清晰递增 ✅；`[4]` 中/快几乎相同 ❌；`[7]` 只动 4% ❌。

### 熄火过渡（另一组数据，同样是证据）

```
[4]  956 → 346 → 0                                  （0.5 秒，"目标值被清零"）
[8]  990 → 890 → 770 → 640 → 520 → 390 → 320 → 0    （4.5 秒，转子惰转惯性）
```

→ `[8]` 是**实际**转速（有惯性），`[4]` 是**目标**转速。

### 踩过的坑（别再犯）

1. **`[7]` 曾被误判为转速** —— 它在熄火时仍读 4240，被用户实测否掉。
2. **`BCU(0x00419307)[9]` 曾被误判为"发电功率"** —— 实测是**常数** 21194，
   与负载无关（真正随负载动的是 `PDU(0x00419309)[0]` / `[22]`）。
3. **`REEV` 系列（`0x00413070` / `0x00413072`）在本车全 0 或完全不变** ——
   该平台虽支持 `CD701_REEV`，但本车型未启用这条通道。
4. **厂商适配器里根本没有转速 getter** —— 枚举
   `SoaBridgeVehicleInfoClientEmbedded`（103 个 getter）与
   `SoaBridgeEnergyClientEmbedded`（40 个 getter），
   没有 `getEngSpd` / `getReevSpd` / `getGenSpd`。
   **转速只能用"行为标定"从数组里挖。**
5. `SoaPropConfig` 的 `configString` 全为空，**无法按名字反查 id**。

### 已在 carapp 中实现

- 悬浮窗新增一行：`转速 %d rpm（目标 %d） · 增程器运行/停止`
- App「实时数据」页新增：发动机转速 / 目标转速
