# Q07 车机 HUD/多屏架构与「能否把画面送到 HUD」

> 结论先行：**HUD 由 QNX 侧渲染，Android 只能发「状态类」消息，没有画面通道。**

---

## 1. 屏幕拓扑（实测）

```
DRM 连接器：card0-DSI-1、card0-DSI-2、card1-DP-1   ← 3 块物理屏
           card2-Virtual-1                          ← 虚拟口（非物理屏）

Android displayId 0 = port 0  2560×1600   中控
Android displayId 1 = port 2  1920×1080   副驾屏   ← 实测可渲染（用户确认）
Android displayId 2 = port 3   800×480    driver zone，可渲染但 HUD 无反应
Android displayId 10= port10   243×200    虚拟口（card2-Virtual-1），可渲染
```

`screencap -d` 用的是**物理口编号**，不是 `dumpsys display` 的逻辑 id（踩过）。

### CarService 占用区（`dumpsys car_service`）

```
port0 → displayType=MAIN(1) zone0(driver)
port1 → displayType=MAIN(1) zone0(driver)
port2 → displayType=MAIN(1) zone1(passenger)
port3 → displayType=MAIN(1) zone0(driver)
```

**没有任何口注册成 `DISPLAY_TYPE_HUD(3)`**（常量在 `android.car.jar` 和厂商 `CarOccupantZoneManager` 里都有，但车机没用）。

SurfaceFlinger 逐屏图层：中控有 4 层；副驾只有状态栏/导航栏；800×480 与 243×200 **0 层**。
→ 车机自己的仪表/HUD 画面**不经过 Android**。

---

## 2. Android ↔ QNX 通道（逆向结论）

架构：SiEngine SE1000 单 SoC 跑 **Android + QNX**（厂商常量里叫
`SMA_EDC_A_Android` / `SMA_EDC_A_QNX` / `SMA_EDC_B_QNX` / `SMA_C2_MAIN` / `SMA_C2_SLVAE`）。

### 2.1 发送（Android → QNX）

```java
// com.incall.commonutil.util.MapParaUtils
transmitToQnx(int type, String jsonKey, int ack)
  → CommunicationHelper.getInstance().callServiceSync(
        "event_module", "meter_service", "send_msg_to_meter" | "send_ack_msg_to_meter", map)

map = { "type": <int>, "key": <JSON 字符串> }

// HUD 专用封装
sendHUDMsgToQnx(int adjustStatus, int value)
  → transmitToQnxByAck(0x11, {"adjuststatus":…, "value":…})
```

即：**HUD 只有一种消息类型 `0x11`，内容是「调节状态 + 值」**（方向盘调 HUD 用）。

已见到的 `meter_service` 消息类型（type）：`3 / 4 / 8 / 0xb / 0xd / 0x11(HUD) / 0x17 / 0x19 / 0x1d / 0x2f / 0xff` —— 全是**数值型状态**，没有图像/视频。

### 2.2 接收（QNX → Android）

QNX 事件主题（`CtMapKey`）：

```
QNX_TOPIC_PREFIX_SCREEN        = "qnx_0x8004"   ← 副驾屏 电源/可达 状态
QNX_CONFIG_INFO_PREFIX         = "qnx_0x800e"
QNX_HUD_INFO_PREFIX            = "qnx_0x8010"   ← HUD 信息（配置/状态）
QNX_TOPIC_PREFIX_MOT           = "qnx_0x8014"
QNX_PSG_BLUETOOTH_STATE_EVENT  = "qnx_0x8016"
```

### 2.3 SDK 里 HUD 的**全部**接口（`CtBusinessForwardCpt`）

```
HUDAdjustService     : get_hud_adjust_mode / set_hud_adjust_mode / event_hud_adjust_mode_change
AutoBrightnessService: GET_HUD_AUTO_BRIGHTNESS / SET_HUD_AUTO_BRIGHTNESS
SceneService         : set_hud_switch
（SOA/属性侧）        : VEHICLESET_HUD_ENABLE / _HEIGHT / _POSITIONSET / _LIGHTSET /
                       _LANGUAGE / _DISPLAY、HUD_MAP_MODE、HUD_AR_MODE、
                       ENH_HUDAMBLILINE_*（HUD 氛围灯）
```

**全部是开关/亮度/高度/角度/模式/语言/氛围灯，没有任何「推图像/视频」的接口。**

### 2.4 相关服务（都不是画面通道）

- `com.incall.samescreenservice`（同屏，浮动窗）
- `com.incall.apps.projectionscreenserver`（**手机投屏**：USB 设备 → 副驾屏，`IProjectionScreen`/`ProjectionScreenManager`）
- `com.rightware.kanzi.cluster` / 进程 `com.ca.kanzi`（仪表相关，提供 `ClusterSmall/BigWidgetProvider` app widget，但当前不渲染任何屏）
- `EvsService`：`control_evs_side_assist_switch` / `get_evs_side_assist_switch` / `SET_SIDE_ASSIST_CLOSE`
  （侧后辅助开关——车机自己的 AVM，出在中控）

---

## 3. 结论

| 问题 | 答案 |
|---|---|
| HUD 是 Android 屏吗？ | **不是**。Android 侧没有 `DISPLAY_TYPE_HUD`，画面里也没有 HUD 图层。 |
| 能往 HUD 画 Android 界面吗？ | **不能**（displayId 1/2/10 都试过，HUD 无变化）。 |
| 有「推画面到 HUD」的厂商接口吗？ | **没有**。只有状态类消息（type `0x11` = HUD 调节）。 |
| 唯一的跨 VM 通道 | `event_module / meter_service / send_msg_to_meter`，载荷是 `{type, key(json)}`，面向**数值状态**，不是像素。 |

要往 HUD 出画面，只能由 **QNX 侧**实现（它得有这份能力 + 有人告诉它放什么）。
Android 侧无解。

---

## 4. 可行的替代

| 方案 | 说明 |
|---|---|
| **副驾屏 (1920×1080)** | 标准 Android Display，已验证可渲染 → 侧摄像头可以直接搬过去 |
| 中控悬浮窗 | 已在做（转向灯联动） |
| 向 `meter_service` 盲试 type | ⚠️ **不建议**：那是仪表/HUD 服务，未知消息可能干扰仪表；且载荷是 JSON key/value，本就不是为图像设计的 |

> 如果一定要 HUD：只能走**长安/亿咖通侧的协议**（SDA 开发者平台 / 厂商支持），Android 这边没有入口。
