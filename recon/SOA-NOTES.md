# SOA 逆向笔记（厂商实时车辆信号层）

> 目标：绕开厂商授权，从 root 进程直接拿车辆的**实时**信号。
> 结论：**可行**。SOA（Service-Oriented Architecture）的 HIDL 接口对任何进程开放。

---

## 1. 为什么不能用 CarProperty

`com.ca.car.proxy.hardware.property.CaCarPropertyManager`（厂商 HAL）虽然**零权限可读**，
但它返回的是**装饰性的静态镜像**：

- `EV_BATTERY_LEVEL` 恒为 `150000`（车机已显示 49%）
- `GEAR_SELECTION` 恒为 `4`（挂挡后不变）
- `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` 恒为 `0.0`（行驶中也是 0）
- 订阅（`registerServiceListener`）能收到事件，但**只有初始推送 + 雷达/CAN 帧噪声**，
  我们的目标信号**永不更新**

**结论：CarProperty 这条路对实时数据无效。**

---

## 2. 真正的实时层：SOA

车机上的数据链路：

```
CAN 总线
  → SOA（native: android.hardware.automotive.soabridge@1.0-service, sebridge_server）
       ↓ HIDL
  → android.hardware.automotive.soabridge@1.0::ISoaBridgeNative
       ↓ Java
  → com.incall.soabridge.*（厂商客户端，打包在 /system/app/SoaBridgeTransit/SoaBridgeTransit.apk）
       ↓
  → com.incall.apps.casdkmanager.*（CA SDK，厂商给三方用的那套 = 云平台的 vehicle_service.*）
       ↓
  → 厂商各个 App（能耗 / CaEvs / HMI …）
```

### 关键突破：HIDL 没有 per-package 权限校验

`SoaBridgeTransit` 是 `android.uid.system` + signature 权限，**绑不上**；
但底层 **HIDL 服务**对任何进程开放，SELinux 又是 Permissive。

**做法：把厂商的 HIDL Java 类挂到我们进程的 classpath 上，直接调 HIDL。**

```powershell
# 1) 从车机拉出厂商 APK，抽 dex
adb pull /system/app/SoaBridgeTransit/SoaBridgeTransit.apk
# 里面的 classes*.dex 就是 HIDL Java 类（android.hardware.automotive.soabridge.V1_0.*）

# 2) 推 dex 到车机
adb push soa1.dex ... soa12.dex /data/local/tmp/

# 3) 用合并 classpath 跑
adb shell "CLASSPATH=/data/local/tmp/ours.dex:/data/local/tmp/soa1.dex:...:/data/local/tmp/soa12.dex \
           app_process /system/bin <我们的类>"
```

最小可运行代码（反射版）：

```java
Class<?> iface = Class.forName("android.hardware.automotive.soabridge.V1_0.ISoaBridgeNative");
Object svc = iface.getMethod("getService").invoke(null);          // HIDL 服务
// 全量属性清单（实测 2102 个）
List<?> all = (List<?>) iface.getMethod("getAllSupportSoaProp").invoke(svc);
// 读值：用厂商的客户端
Class<?> clientCls = Class.forName(
    "com.incall.soabridge.impl.SoaBridgeServiceManagement.SoaBridgeNativeClient");
Object client = clientCls.getConstructor(iface).newInstance(svc);
Object prop   = clientCls.getMethod("getProperty", int.class).invoke(client, propId);
Object value  = prop.getClass().getMethod("getPropertyValue").invoke(prop);
```

**已验证**：`getService()` 返回 `...@Proxy`，`getAllSupportSoaProp()` 返回 **2102** 个属性，
`getProperty(id)` 能读到实时值（转向灯 `0x00415244` 会跟着闪烁 `[0,0]↔[0,1]`）。

---

## 3. 两套 id（重要）

| 来源 | 例子 | 能否被客户端读到 |
|---|---|---|
| logcat 里的 `propid:0x... name:Basc_*` | `0x418045 Basc_GearBasInfo.Ntf_GearBasInfo` | ❌ `getProperty` 返回 null（服务端内部信号）|
| 客户端可见集（2102 个） | `0x00415244`（转向灯）、`0x00413066`、`0x00419309` … | ✅ 可读、实时 |

所以**名字表不能直接套在客户端 id 上** —— 需要行为标定。

### 已靠行为确认

| id | 值 | 身份 |
|---|---|---|
| `0x00415244` | `[0,0] ↔ [0,1]` | **转向灯**（跟着闪烁）|

---

## 4. logcat 里的名字表（部分，供对照）

```
0x401276  Basc_VehRdy.Ntf_VehRdy
0x402164  Basc_BattHVSts.Ntf_BattHVSts
0x413030  Basc_IBSInfo.Ntf_IBSInfo              （IBS 智能电池传感器）
0x413196  Basc_HvActvReq.Ntf_HvBattCpInfo
0x4131e7  Basc_EmsOperStsInfo.Ntf_EmsOperStsInfo（EMS 发动机管理）
0x413018  Basc_AccPedalSts.Ntf_AD_AccPedalStsChagMsg
0x418045  Basc_GearBasInfo.Ntf_GearBasInfo
0x415245  Basc_ExtrLiInfoTips.Ntf_TurnLiInfo
0x419831  Basc_ExtrLiActCtrlInfo.Ntf_TurnLiInfo
0x419905  Basc_ExtrLiSwtReqInfo.Ntf_TurnLiSwtReqInfo
```

（完整表可用 `logcat -d | grep -o 'propid:0x[0-9a-f]* name:[A-Za-z0-9_.]*' | sort -u` 导出）

---

## 5. 下一步

**一次只做一个动作**，记录客户端可见集里哪些 id 变化，逐个确定身份：

1. 挂 D 挡（保持）→ 找出**挡位**
2. 打转向灯 → 已知 `0x00415244`
3. 开空调 → 找出**功率/电流**相关
4. 踩油门让增程器启动 → 找出**转速**

工具：`tools/` 下的 SOA 探针（另行整理）。
