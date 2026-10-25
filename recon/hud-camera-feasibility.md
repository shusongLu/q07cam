# HUD 显示补盲摄像头画面 —— 可行性逆向（2026-10-18）

> 结论先行：**HUD 的画面确实由 Android 侧产生并推给 QNX 盒子**，
> 通路已定位到函数级（`CMapService::SendHUDData` → WebSocket 二进制帧）。
> 但载荷是导航私有协议（命令字 + 图像），**尚未解出完整帧格式**；
> 要落地还需要：解协议 → 抢/并行一条 WS 通道 → 实车验证。

---

## 1. 关键反转：HUD 不是「纯 QNX 渲染」

上一版结论（`hud-architecture.md`）说「HUD 由 QNX 渲染、Android 没有画面通道」，
**只对了一半**：Android 没有 HUD **显示**（没有 `DISPLAY_TYPE_HUD`），
但 Android 的导航进程一直在**往 HUD 盒子推画面数据**。

实车证据（`/proc/net/tcp`，2026-10-18）：

```
172.16.119.1:39138 → 172.16.119.2:10002  ESTABLISHED  uid=1000
172.16.119.1:39136 → 172.16.119.2:10002  ESTABLISHED  uid=1000   (IPv6-mapped)
```

发送方身份已确认：

| 项 | 值 |
|---|---|
| 包名 | `com.incall.apps.navi.service` |
| uid | **1000（`android.uid.system`）** ← 与 socket 的 uid 完全一致 |
| APK | `/system/priv-app/NaviService/NaviService.apk`（311 MB） |
| 真正的库 | APK 内 `lib/arm64-v8a/libMXNavi.so`（**15 MB**） |
| 本地副本 | `%TEMP%\opencode\mxn\mxn15.so` |

> 注意：`/system/app/MXNaviDemo/lib/arm64/libMXNavi.so` 是 **178 MB** 的另一个库，
> 不含 HUD 代码（那是 demo 的地图数据）。别再抓错。

---

## 2. HUD 发送链路（函数级，已还原）

### 2.1 字符串与 RTTI 定位

```
0xC3BEE7  ws://172.16.119.2:10002
0xC3BEF9  10002
0xC3BEFF  ##### m_WebSocket->dispatch %s
0xC3BF60  ZN12easywsclient9WebSocket8dispatchIZN11service_map11CMapService
          11SendHUDDataEPcRKmE3$_2EEvT_E9_Callback      ← lambda 的类型名
0xC3BEC3  WSCreatorProc RetryLeftTimes = %d
0xBA984D  HUDRenderMain                                  ← 渲染线程名
```

### 2.2 `CMapService::SendHUDData` = `fcn.0090abcc`

签名与还原一致：`SendHUDData(char* data, unsigned long const& len)`

```
0x90abf0  ldr  x0, [x19, 0x1d8]   ; this->m_WebSocket
0x90abf8  ldr  x8, [x0]           ; vtable
0x90abfc  ldr  w2, [x2]           ; len
0x90ac00  ldr  x8, [x8, 0x30]
0x90ac04  blr  x8                 ; WebSocket.sendBinary(data, len)
0x90ac08  ldr  x0, [x19, 0x1d8]
0x90ac0c  mov  w1, #0
0x90ac14  ldr  x8, [x8, 0x10]
0x90ac18  blr  x8                 ; 第二次虚调用（poll/send）
0x90ac20  adrp x8, 0xe9b000
0x90ac24  add  x8, x8, 0x7a8      ; lambda 的 vtable = 0xE9B7A8
0x90ac2c  stp  x8, x9, [sp]
0x90ac38  ldr  x8, [x8, 0x50]
0x90ac3c  blr  x8                 ; WebSocket.dispatch(lambda)
```

**结论：`SendHUDData` 不做任何再封装**，调用方给什么字节就发什么。
载荷格式完全由上层决定。

### 2.3 连接建立后先发一个 6 字节命令

WS 创建线程（`fcn.0090ab1c`，日志串 `WSCreatorProc RetryLeftTimes`）在连上后：

```
w8 = 0x7F ; w9 = 6
str  w8, [sp, 0x10]      ; 首字节 0x7F
strh wzr, [sp, 0x14]     ; 后两字节 0
SendHUDData(this, sp+0x10, 6)
→ 实际发出： 7F 00 00 00 00 00
```

→ **WS 上是「命令字节 + 参数」的协议**，不是纯裸帧。`0x7F` 是初始化/注册类命令。

### 2.4 另一条通道：UDP 10003（状态类）

`HudDataSender`（全局单例、懒加载，构造函数 `fcn.00902f58`）：

```
socket(AF_INET, SOCK_DGRAM, 0x11)
sockaddr: 02 00 27 13  → sin_port = 0x2713 = 10003
inet_pton(AF_INET, "172.16.119.2")
```

报文格式（上一轮已完全逆出）：

```
offset 0: uint16 seq
offset 2: uint8  totalChunks
offset 3: uint8  chunkIndex
offset 4: payload（≤4092）
单条总长上限约 1 MB
```

这是**状态/小消息**通道；大画面走 WS。

---

## 3. HUD 收到的是「图」

库里的渲染与图像能力（导入/符号级证据）：

```
rfw::CReadPixels::Set(x,y,w,h,fmt,type)        → glReadPixels      @0x224d3c
rfw::CNreEglSurface::Init                      → eglCreatePbufferSurface
rfw::NreSnapshot::GetSnapshot_png / _jpg / _bmp / _mem
tdefl_write_image_to_png_file_in_memory
UTILS_DecordJpeg / DecordPng / DecodePngBuffer
CMapServiceCallBackInterface::OnSnapshoted(char const*, unsigned long const&)
```

含义：

1. 导航会 **EGL 离屏渲染 → glReadPixels 抓帧**；
2. 引擎自带 **PNG / JPEG / BMP 编码器**；
3. 地图服务对外提供 **快照字节回调**（`OnSnapshoted`，`char* + len`）。

→ 所以 HUD 侧拿到的是**像素/编码图**，这为「把摄像头画面塞进去」提供了基础：
只要格式对，图像本身是可以替换的。

---

## 3.5 ✅ 协议已完整解出（Java 层源码级）

关键类（在 `com.incall.apps.navi.service` 的 `classes4.dex` 里）：

```
com.incall.apps.navi.service.nbl.sd.externalproxy.util.HUDUtil
  static final byte[] HUD_HEAD          = { 0x7F }
  static final byte[] HUD_CONTROL       = new byte[1]      // 运行时填入命令字
  static final byte[] HUD_EMPTY_LENGTH  = { 0,0,0,0 }
  static final byte[] HUD_EMPTY_CONTENT = { 0,0,0,0xFF }
  static void sendData(byte cmd, ScreenshotDataInfo info, WebSocketClient ws)
```

`sendData` 的字节码还原成 Java：

```java
byte[] png = (info != null) ? info.getPNGByteData() : null;   // ← PNG 编码后的图
HUD_CONTROL[0] = cmd;
int len = (png == null) ? 10 : 6 + png.length;
byte[] buf = new byte[len];
buf[0] = (byte)0x7F;                       // HUD_HEAD
buf[1] = cmd;                              // HUD_CONTROL
if (png == null) {
    System.arraycopy(HUD_EMPTY_LENGTH,  0, buf, 2, 4);   // 00 00 00 00
    System.arraycopy(HUD_EMPTY_CONTENT, 0, buf, 6, 4);   // 00 00 00 FF
} else {
    System.arraycopy(intToByte(png.length), 0, buf, 2, 4); // 大端 32 位长度 = 6 + png.length
    System.arraycopy(png, 0, buf, 6, png.length);
}
ws.send(buf);                              // WebSocket 二进制帧
```

`intToByte(int)` = 大端 4 字节。

### 报文格式（最终）

```
偏移 0   : 0x7F                   魔数
偏移 1   : 命令字
偏移 2-5 : uint32 大端 总长度（= 6 + PNG 长度；空包为 0）
偏移 6   : PNG 字节（空包时为 00 00 00 FF，总长 10）
```

### 命令字（来自 `MapBusiness$3` 等调用点）

| 命令 | 调用点 | 含义 | 负载 |
|---|---|---|---|
| `0x00` | `createPresentMap` | 初始化 / 建立显示 | 空 |
| `0x01` | `onFirstRender` | 首帧就绪 | 空 |
| `0x02` | `lambda$$1$MapBusiness$3`（每次截图） | **图像帧** | **PNG** |
| `0x02` | `onDestroyMap` | 清屏 | 空 |

调用点原文（`classes5.dex`，`MapBusiness$3.lambda$$1$MapBusiness$3`）：

```java
externalServiceInner.sendDataByWebSocket(
    "ws://172.16.119.2:10002",
    (byte) 2,                  // const/4 v2, #int 2
    screenshotDataInfo);
```

### 图片的生产方式

```
MapController.startCommonScreenshot / startScreenshotForBitmaps
  → AutoNavi GBL  MapDevice.setScreenshotRect + setScreenshotMode
  → IEGLScreenshotObserver 回调 Bitmap
  → ScreenshotDataInfo(PNG bytes)
  → HUDUtil.sendData(0x02, info, ws)
```

HUD 尺寸常量：`WHUDHelper.<clinit>` 里出现 **243 × 200**（与 Android 那块 243×200 虚拟显示一致）。

### 端到端链路（已验证的部分）

| 环节 | 状态 |
|---|---|
| WS 握手 `ws://172.16.119.2:10002` → `101 Switching Protocols`（Server: WebSocket++/0.8.2） | ✅ 实测 |
| 服务端不主动下发任何数据；也不回 ACK | ✅ 实测 |
| 按上述格式发送 `0x00/0x01/0x02+PNG`：连接不被断开、无报错 | ✅ 实测 |
| 画面是否真的被显示 | ⏳ **未验证** —— 见 §4 |

---

## 4. 还缺什么（这是目前真正的门槛）

| # | 未知项 | 影响 | 现状 |
|---|---|---|---|
| 1 | WS 帧的完整结构 | 决定能不能造出合法帧 | ✅ **已解**（见 §3.5） |
| 2 | 图像编码 | 决定发送端怎么编码 | ✅ **已解：PNG** |
| 3 | 盒子是否接受**第二个**发送方、如何仲裁 | 决定能否并行注入 | ❌ **关键未知**（见下） |
| 4 | 平均码率 | 决定「快照型」还是「视频型」 | ✅ 实测：**突发式**，突发时约 2.2 MB/s，空闲为 0 |

### 4.1 实车实验结果（2026-10-18）

按协议发送了多组探针，盒子**全部静默接受**，但 **HUD 画面无任何变化**：

| 探针 | 内容 | 结果 |
|---|---|---|
| A | 握手 + 6 字节 `7F 00 00 00 00 00` | 无反应 |
| B | `0x00` 空包 → `0x02` + 243×200 PNG | 无反应 |
| C | `0x01` 空包 → `0x02` + 243×200/480×240 PNG | 无反应 |
| D | `0x00` → `0x01` → `0x02` + **纯白 800×480 / 243×200** | 无反应 |
| E | 6 字节 init → `0x02` + 纯白 800×480 | 无反应 |
| F | **force-stop 导航后**发纯白图 | 无反应（且 HUD 左侧 SR 画面**完全没变**） |

### 4.2 关键观察

1. **HUD 左侧的「SR 画面」不是导航推的图** —— 停掉导航后它一点都不变，
   说明那块由 QNX/ADAS 侧渲染或缓存，与 WS 通道无关。
2. 用户确认：**只有导航进行中，HUD 左侧才会显示地图**。
   → WS 推的 PNG 是 **HUD 地图模式** 的画面，当前不在该模式，因此即使收到也不显示。
3. 盒子接受并存的多条连接（导航两条 + 我们的），**不会踢掉旧连接**。
4. 盒子对非法/未知内容**不报错、不断连接**，因此无法用回包判断是否被接受。

### 4.3 下一步（唯一有效验证）

**在导航进行中（HUD 左侧出现地图）时，发送一帧纯白图。**

- 若左侧变白 → 协议完全成立，注入可行；
- 若仍无变化 → 盒子按连接/会话做了归属绑定，需要另找「注册」方式。

### 4.4 ❌ 实车最终结论（2026-10-18，导航进行中）

在**导航进行中、HUD 左侧地图正在实时刷新**的状态下，做了完整的对照实验：

| 测试 | 内容 | 结果 |
|---|---|---|
| G | 8 种尺寸 × 8 种颜色（240×240 / 300×200 / 320×240 / 400×240 / 480×240 / 512×256 / 640×240 / 800×480），每种连发 6 帧 | **全程仍显示导航地图，无任何颜色出现** |
| H | force-stop `com.incall.apps.navi.service` + `com.incall.apps.navi` 后，发 `init6 + 纯白 800×480` | 无变化；且强制停止后仍有 **uid 1000 的连接**存活（端口 AE4C / AE3E），说明还有别的 system 应用占着该通道 |

**结论：**

1. **协议已 100% 解出**（§3.5），这一点是确定成果。
2. 盒子在 TCP/WebSocket 层**接受**我们的连接（101，持续 `ESTABLISHED`，不报错不断开），
   但**从不把我们发的内容渲染到 HUD**。
3. 无法通过 force-stop 导航拿到「唯一客户端」的干净状态 ——
   还有其它 uid=1000 进程持有到 `172.16.119.2:10002` 的连接。
4. 因此推断：**HUD 通道按「会话/进程归属」绑定**，
   不是任何能连上 10002 的客户端都能当画面源。

**可行性判定：** 在没有厂商配合或 **root 级注入导航进程**的前提下，
「第三方 App 把画面送到 HUD」**不可行**。继续投入的性价比很低。

**仍然有效的东西：**

- §3.5 的协议描述可直接用于将来任何需要"往 HUD 发图"的场合
  （若有 root 或厂商授权，可让导航进程代发）。
- 若将来拿到 root：可以 hook `HUDUtil.sendData` 或替换 `ScreenshotDataInfo` 的 PNG，
  这时协议知识立刻可用。

---

---

## 5. 下一步（按投入产出排序）

### 第 1 步：量一下真实码率（5 分钟，需车机亮屏）

```sh
adb shell "ss -tin | grep -A1 172.16.119.2:10002"
# 等 10 秒再采一次，比较 bytes_acked 增量
```

- 若增量 ≈ 0 → WS 是**事件驱动**（导航只在需要时推图）→ 「快照注入」完全可行。
- 若增量 ~10 MB/10s → 是**持续视频流** → 注入会与导航抢带宽/抢画面。

### 第 2 步：静态解协议（不需要车，可离线做）

- 反汇编 `case.0x90c714` 分发表，枚举命令字；
- 找 `OnSnapshoted` 的实现与它的调用者，看图像字节是否直接进 `SendHUDData`；
- 找 `SendHUDData` 的虚表槽（`CMapServiceInterface`），定位渲染侧准备 buffer 的位置，
  读出 header 字段（宽/高/格式/编码长度）。

### 第 3 步：探针（低风险，需车机 + 停车）

在 app 里加一个**调试用**的最小 WS 客户端：

1. 连 `ws://172.16.119.2:10002`；
2. 先发 `7F 00 00 00 00 00`；
3. 再发一帧**已知内容**的候选格式（纯色/棋盘格），逐种试；
4. 观察 HUD 是否出现。

⚠️ 风险控制：

- `172.16.119.2` 同时承载**仪表**，只做**合法 WebSocket 二进制帧**，不发畸形包；
- 必须在**停车**状态做；
- 一旦发现仪表异常立刻停止。

---

## 6. 现实评估

**能做到吗？** 原理上可以 —— HUD 的图像源就在 Android 侧，且是图像而非封闭指令流。

**代价与风险：**

- 需要复刻一个**系统级导航的私有协议**（无文档、随 OTA 变化）；
- 导航进程持续占用同一条 WS 通道，注入大概率会**与导航互相覆盖**；
  除非能找到「多客户端/多图层」的仲裁方式；
- HUD 与仪表共用一台 QNX 盒子，属于安全相关显示，调试必须停车；
- 车机 OTA 后协议/地址可能全变（本次就遇到了 `libMXNavi.so` 版本差异）。

**建议路线：** 先做第 1、2 步（成本低、信息量大），
用结论决定是否值得进入第 3 步的实车注入试验。

---

## 7. 工具与产物

| 项 | 位置 |
|---|---|
| r2 6.2.4 | `%TEMP%\opencode\r2\x\radare2-6.2.4-w64\bin\radare2.exe` |
| 15 MB 目标库 | `%TEMP%\opencode\mxn\mxn15.so` |
| 178 MB 干扰库（demo） | `%TEMP%\opencode\mxn\libMXNavi.so` |
| 车辆设置 APK（含 HUD/盲区字符串） | `%TEMP%\opencode\hud\vs.apk` |

r2 常用命令（本库 paddr == vaddr，可直接用文件偏移）：

```
r2 -e bin.relocs.apply=true -A -q -c 'axt @ 0xc3bee7; q' mxn15.so
r2 -e bin.relocs.apply=true -A -q -c 's 0x90ac20; pd 80; q' mxn15.so
```

---

## 8. 顺带确认的其它事实

- 车辆设置（`com.incall.apps.vehiclesetting`，154 MB）里有：
  `EVENT_SHOW_BLIND_RL_CAMERA` / `EVENT_SHOW_BLIND_RR_CAMERA`（左右后视盲区事件）、
  `HUD_MAP_MODE` / `HUD_AR_MODE` / `HUD_ADJUST_KEY` / `QNX_HUD_INFO_PREFIX` / `RES_HUD_MEMORY_SWITCH`。
  → 车机本身有「盲区影像」事件，但它们是**整车信号**，第三方无法伪造 CAN 事件；
    也没有证据表明 HUD 订阅了这两个事件。
- `navi.service` 导出了一个 `com.incall.apps.navi.service.NaviService`（3 个 filter），
  但未发现任何「发送 HUD 数据」的 AIDL —— HUD 推送是进程内 native 行为。
