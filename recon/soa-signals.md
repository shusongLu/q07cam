# SOA 信号表与读取方法（实测）

## 结论速览

| 能力 | 状态 |
|---|---|
| 直连 SOA HIDL（`ISoaBridgeNative`） | ✅ root 进程可用，2102 个属性 |
| 厂商 SDK key 命名（`Vehicle.drive.gearLevel.key()`）| ✅ 能解析出 `vehicle.drive.gearLevel` |
| `CaSdkManager.getVehicleInfoSync(keys)` | ⚠️ 能调用，但 **`init()` 失败**（要 bindService）|
| `SoaBridgePropIds` 名字↔id 表（161 项）| ✅ 可反射读出 |
| 直读 `ID_GET_*` 系列 | ✅ 17 项有值 |
| **挡位 / 功率 / 转速** | ❌ 仍未拿到（见下）|

---

## 1. 为什么 `CaSdkManager` 用不了

```
CaSdkManager.getInstance().init(ctx, true)
  → SecurityException: Unable to find app for caller ... when binding service
    Intent { act=com.incall.app.start.CaSdkService pkg=com.incall.apps.sdkservice }
```

`init()` 要**绑定 `com.incall.apps.sdkservice`**，而 `app_process` 不是"已注册的 App"，
AMS 拒绝 bindService。**这是唯一卡点** —— 若我们的代码跑在真正的 App 里（渠道装壳），
`CaSdkManager` 就能直接用，那样**按 key 取值**是最干净的方案。

**但 key 本身是可解析的**（不需要 init）：
```java
String key = Vehicle.drive.gearLevel.key();   // = "vehicle.drive.gearLevel"
```

---

## 2. 可用的读取通道（已在 root 进程验证）

```java
// 1) 拿 SOA HIDL 服务
Class<?> iface = Class.forName("android.hardware.automotive.soabridge.V1_0.ISoaBridgeNative");
Object svc = iface.getMethod("getService").invoke(null);
// 2) 厂商客户端（把它所在 APK 的 dex 挂到 classpath 即可）
Object client = Class.forName(
    "com.incall.soabridge.impl.SoaBridgeServiceManagement.SoaBridgeNativeClient")
        .getConstructor(iface).newInstance(svc);
// 3) 读值
Object prop  = client.getClass().getMethod("getProperty", int.class).invoke(client, id);
Object value = prop.getClass().getMethod("getPropertyValue").invoke(prop);
```

**运行方式**（合并 classpath，dex 从厂商 APK 里抽出来）：
```
CLASSPATH=<我们的dex>:/data/local/tmp/sdk1.dex..sdk5.dex:/data/local/tmp/soa1.dex..soa12.dex \
  app_process /system/bin <我们的类>
```
- `sdk*.dex` ← `/system/app/SoaBridgeTransit/SoaBridgeTransit.apk`（HIDL Java 类 + SOA 客户端）
- `soa*.dex` ← `com.incall.apps.sdkservice` 的 APK（SDK：`CaSdkManager` / `Vehicle` / `SoaBridgePropIds`）

---

## 3. `SoaBridgePropIds`（名字↔id，161 项）里可直读的 17 项

```
0x00402021  ID_GET_CHARGE_PORT_FAULT_STATUS        = 0
0x00408015  ID_GET_EDC_TAR_GEAR                    = 0
0x00402027  ID_GET_EPB_LOST_COM_INFO               = 0
0x00412026  ID_GET_EPB_STATUS                      = [1, 0, 1, 0, 0, 0]   ← 电子手刹
0x00405101  ID_GET_FRONT_LIGHT_STATE               = 0
0x00404003  ID_GET_HIGH_SPD_MIRROR_AUTO_FOLD_STATE = 1
0x00408007  ID_GET_LOST_VEH_SPD                    = 0
0x00404006  ID_GET_MIRROR_AUTO_FOLD_STATE          = 1
0x00404018  ID_GET_MIRROR_HEAT                     = 0
0x00404009  ID_GET_MIRROR_RVS_AUX_STATE            = 1
0x00408004  ID_GET_POWER_SHIFT_INFO                = 2                    ← 电源挡位
0x00405104  ID_GET_REAR_FOG_LIGHT_STATE            = 0
0x0040228C  ID_GET_SUNROOF_POSITION_STATE          = 1
0x00418006  ID_GET_VEH_SPD                         = [0, 0, 0, 0]         ← 车速（驻车）
0x00402006  ID_GET_WIPER_INTERVAL_TIME             = 5
0x00412034  ID_GET_WIPER_RAIN_LIGHT_SENSOR_STATE   = [0, 0, 0, 0, 0]
0x00402009  ID_GET_WIPER_SENSITIVE_LEVEL           = 3
```

**规律**：`ID_GET_xxx` 可直读；`ID_xxx_CHANGE` / `ID_xxx_EVENT` 是订阅用（直读返回 null）。

---

## 4. 仍未解决的（挡位 / 功率 / 转速）

`SoaBridgePropIds` 里没有这三个；它们由 **SDK 服务**（`com.incall.apps.sdkservice`）
按 `vehicle.drive.gearLevel` 之类 key 转发给 SOA。可读的 `getProperty` 客户端集里，
驻车状态下的变化项都是 CAN 心跳/雷达噪声，**没有 1~5 的挡位、没有 0~1000 的电流**。

**下一步（关键）**：**开着车**跑 SOA 全量监视器。
- 车速 `0~460`、总电流 `-255`/`0~1000`、挡位 `1~5` —— 只有**行驶中**才会变
- 一旦某项按这些取值范围变化，就能把它钉死
- 文档给的取值范围就是判据（见 `car-api-doc.md`）

监视器：读全部 2102 个属性、按内容比对、每秒一轮、打印变化项。
