# 充放电功率/电流 标定记录（2026-10-17，实车快充现场）

> 结论先行：**车机显示的充电电流不在 SOA 总线上**，第三方读不到。
> 我们只能拿到**电压**和**方向**，功率不再猜。详见 §5。

## 1. 现场对照数据（同刻采样）

| 时刻 | 车机 电压 | 车机 电流 | 车机 功率 | BCU[0]/10 | PDU[8]/10 | PDU[9] | PDU[10] | PDU[15] |
|---|---|---|---|---|---|---|---|---|
| 01:36:10 | 387.5 V | 45.8 A | 17.7 kW | **387.5** ✅ | 387.1 | 220 | 660 | 80 |
| 01:38:24 | 387.3 V | 39.7 A | 15.4 kW | **387.2** ✅ | 386.7 | 220 | 660 | 80 |

- **电压**：`BCU(0x00419307)[0] ÷ 10` 三次采样**全部精确相等** → 板上钉钉 ✅
- 电流 45.8 → 39.7 A（降 13%），而 `PDU[9]/[10]/[15]` **一个都没动** → 都不是电流 ✗

## 2. 车机自己的功率公式（反编译确认）

`com.incall.apps.module_energy.ui.battery.BatteryFragment.chargePower()`：

```java
double d2 = (Double.parseDouble(chargeValtageValue) * Double.parseDouble(chargeAddElecValue)) / 1000.0d;
tvPowerValue.setThrottleTextNotHandel(String.format("%.1f", d2));
```

**功率 = 电压 × 电流 ÷ 1000** —— 和我们原来的公式**完全一样** ✓
（验算：387.3 × 39.7 ÷ 1000 = 15.38 ≈ 车机显示 15.4 ✓）

## 3. 数值来源：不是 SOA（证据链）

### 3.1 能量 App 走的是 CA SDK，不是 SoaBridge

`com.incall.apps.module_energy.repository.BatteryRepository`
→ `com.incall.apps.casdkmanager.CaSdkManager` + `ICADataCallback` / `ICAEventListener`

### 3.2 实时值是向 QNX 仪表要的

```java
private final void getQNXHMIData() {
    req.addProperty(HU_HMI_REQUEST, 2);
    CaSdkManager.getInstance().callService("event_module", "meter_service",
        CtEventServiceCpt.MeterService.Method.SEND_MSG_TO_METER,
        {TYPE: 1, "key": gson.toJson(req)});
}
```

而 `chargeValtageValue` / `chargeAddElecValue` 这两个名字**只存在于能量 App 里**，
`IncallSdkService.apk` 里**完全没有** → 它们是**仪表侧的 key**，不是 SDK 信号。

### 3.3 那些看起来像"电流/电压"的 SDK 属性都是别的东西

`EnergyRecycleModel.getValueBySoa()` 里：

| SDK 属性 | 映射到 | 实测值 | 判定 |
|---|---|---|---|
| `Vehicle.energyRecycle.outPutCurrent` | `getPduOperPrmInfo()[9]` | 恒 220 | ❌ **V2L 对外放电**，非充电 |
| `Vehicle.energyRecycle.dcAcOutPutU` | `getPduOperPrmInfo()[10]` | 恒 660 | ❌ 同上 |
| `CONTROL_CHARGE_CURRENT/VOLTAGE/POWER` | — | — | ❌ 带 `CONTROL_` 前缀 = **设置项**（限流/目标），非实时测量 |

### 3.4 SOA 侧根本没有这个量

`SoaBridgeTransit.apk` → `com.incall.soabridge.impl.SoaBridgeServiceManagement.SoaBridgeNativeClient`：
全部方法只有 `getProperty(int)` / `subscribeProperty(int)` / `setProperty(...)`
—— **只能按 id 读，没有按名字的接口**；其 `SoaBridge*ClientManager` 里的
充电类取值器（`getChrgConnectSts` / `getAcChrgILimSet` / `getWirelsChrgnSts` …）**全是状态/设置，没有一个充电电流**。

### 3.5 sdkservice 侧

- `CONTROL_CHARGE_CURRENT/VOLTAGE/POWER` + `get/setChargeCurrent|Voltage|Power`
  + Room 表 `charge(..., chargeCurrent, chargeVoltage, chargePower, ...)`
- `SoaChargeModel` 只有控制类方法（充电口使能、限制设置）—— 无实时读数
- 即：**实时 V/I 不是 SDK 自己算出来的**

## 4. 附带确认

- **充放电方向**：`VCU状态(0x00419310)[1]==2` → 充电中 ✅
  （原注释写 `[0]==2` 属差一位；`[0]` 实测恒为 1。旧判据保留做兜底）
- **`BCU[9]` = 累计充电电量**：增量约 0.5~0.6 Wh/单位，斜率换算 15~24 kW，与车机功率量级吻合
  → 可做**非实时**交叉校验，但分辨率太粗（几分钟跳一次），做不了实时读数
- `PDU[0]`（原被当电流用）实测 340~381 且随负载缓慢变化 → 是**温度(0.1℃)**，不是电流

## 5. 结论与可行路径

**实时充电电流拿不到。** 它既不在 SOA，也不在 CA SDK 的信号空间，而在
**QNX 仪表（meter）** 后面，且只能经 CA SDK 的 `event_module/meter_service` 访问。

| 路径 | 可行性 |
|---|---|
| SOA 直读 | ❌ 该量不存在 |
| CA SDK | ❌ `authority.json` 白名单不含我们的包 |
| **QNX 仪表通道** | ✅ **唯一可能**：仪表在 `172.16.119.2:10002`（WebSocket，握手成功过），`getQNXHMIData` 正是"HU 向仪表要 HMI 数据" |
| 派生值 | `BCU[9]` 斜率 → 真实但 1~2 分钟延迟 |

## 6. 我们 App 当前的处理（正式版）

- 电压 → **BCU `[0]` ÷ 10**（与车机精确一致）
- 方向 → 已修正
- **功率/电流 → 显示 `—` / `n/a`**，不再输出一个错的数
  （`OverlayService.powerKnown` 恒 false；找到真实来源后改这一处即可）

## 7. 复现要点

- 对照必须**同刻**：车机画面（`screencap`）+ `logcat` 里的原始帧时间戳对齐；
  隔 2 分钟比较会因为快充降流而误判（我第一次就是这么看错的）
- `uiautomator dump` 对能量 App **无效** —— 它的 UI 是 **Kanzi** 渲染（`libkanzi.so`），不是 Android View
