# 领克 HUD 小地图 / 车机助手 —— 别人的实现方式（REA 分析）

分析对象（用户桌面提供）：

| 文件 | 包名 | 版本 | 体积 |
|---|---|---|---|
| `5. 小地图服务-1.0.6-HUD小地图必装.apk` | `cn.navitool.service` | 1.0.6 | 88 MB |
| `3. 领克OSN车机助手v2.1.5-By.Cheter.Chao.apk` | `cn.navitool` | 2.1.5 | 7 MB |

> 一句话：**他们用 Frida 把一份原生「运行时」注入到 HUD 的 Android 进程里，在那个进程内画小地图。**

---

## 1. 小地图服务（`cn.navitool.service`）—— 真正的技术核心

这个 APK **极干净**（49 个类、8 个资源、无任何权限），就是个「运行时供给器」：

```xml
<application android:extractNativeLibs="false">
  <service android:name="cn.navitool.service.hud.HudMapService"    exported="true">
      <intent-filter><action android:name="cn.navitool.hud.service.BIND"/></intent-filter>
  </service>
  <service android:name="cn.navitool.service.hud.HudMapServiceV2"  exported="true"
           android:process=":hud_runtime_v2">
      <intent-filter><action android:name="cn.navitool.hud.service.V2.BIND"/></intent-filter>
  </service>
</application>
```

### 1.1 APK 内容（体积分布说明一切）

```
lib/arm64-v8a/libnavitool_hud_runtime.so    65 MB   ← Frida 宿主（AArch64 .so）
assets/runtime/arm64-v8a/hud-runtime-arm64  53 MB   ← 加密的运行时载荷
classes.dex                                147 KB   ← 协议/校验/解密
```

### 1.2 对外 AIDL

```
IHudMapService (v1)
  openRuntime(): ParcelFileDescriptor        ← 把运行时通过管道交给调用方
  attachGeelyMap(int, String, IHudRuntimeCallback)
  setMiniMapActive(boolean)
  setTrafficLightVisibility(boolean, boolean)
  stopSession() / getRuntimeSha256() / getRuntimeVersion() / getProtocolVersion()

IHudMapServiceV2
  createSession(int, ParcelFileDescriptor, String, String, IHudRuntimeCallbackV2)
  updateSessionConfig(String, String) / setSessionActive(String, boolean)
  openPayload(...) / releasePayload(...) / openDiagnostics(...)
  getContractInfoJson() / getSessionSnapshotJson(String)
```

### 1.3 载荷是「加密 + 签名 + 版本序号」下发的

`ArtifactManifest` 字段：`ciphertextSha256` / `plaintextSha256` / `signature` / `signingKeyId` /
`releaseSequence` / `nonce` / `wrappedDek`（AES 密钥封装）+ `buildAad()`（附加认证数据）。
`ArtifactTransferProcessor.decryptToDirectBuffer(...)` 解到 **DirectByteBuffer** 再交给 native。
`ReleaseSequenceGuard` 防回滚。→ 是成套的**受控下发**体系，不是随便塞个 so。

### 1.4 native 到底干了什么（字符串证据）

`libnavitool_hud_runtime.so`（AArch64 DYN）：

```
ptrace 17 | process_vm_writev 2 | /proc/<pid>/mem | /proc/self/maps 6
dlopen 32 | dlsym 22 | soinfo 4 | linker 18
inject 195 | hook 42 | EGL 2 | GLES 2
frida 17973 | gum 2031 | Interceptor 40 | Stalker 70
frida-agent / frida-gadget / frida_agent_main
BoundedMessageQueue::isTopLevelFridaError        ← 明确是 Frida
```

→ **ptrace + process_vm_writev 注入目标进程**，载入 **Frida agent**（Gum 引擎），
在目标进程里 **hook + GLES 渲染**。

### 1.5 注入目标是谁、怎么校验

```
HudTargetProcessInspector: readProc(int) / inspect(int) / isNotRejected(int)
                           → 读 /proc/<pid>/cmdline 拿进程身份
HudTargetProcessPolicy:    isAllowedTarget(int,int,String,int,int,String)
HudServiceIdentity:        enforceTrustedCaller(int) + 读签名证书摘要
                           → 只服务「同签名」的调用方（即那个车机助手）
```

`/proc/` 在本包里只出现 1 次（Java 侧重，都是 native 干的）。
**目标 pid 由调用方（车机助手）给出**，服务侧只做白名单/身份校验。

### 1.6 消息协议

```
HudNativeBridge: attach / attachDirect / detach / postConfig / postRuntimeMode
                 postTrafficLightVisibility / drainMessages / drainRealtimeMessages
                 writePayloadToFd / releasePayload

消息 JSON：
  {"type":"hud-mini-map-config","payload":…}
  {"type":"hud-runtime-mode","payload":{"mode":"…"}}
  {"type":"hud-traffic-light-visibility","payload":{"navigationEnabled":…}}
```

---

## 2. 车机助手（`cn.navitool`）—— 前端 + 注入发起方

**被加固**：`android:name="ZUGRcnWStPi.ProxyApplication"`，真实 dex 在
`assets/OoooooOooo`（magic `NDPT`，1.2 MB）、`assets/d_shell_data_001`，
解密器 `assets/vwwwwwvwww/arm64/libd16dec2268234f39.so`（797 KB）。所以静态只能看清单/资源。

清单里的关键点：

```
权限：ACCESS_SUPERUSER(→ root)、READ_LOGS、WRITE_SECURE_SETTINGS、SYSTEM_ALERT_WINDOW、
      REQUEST_INSTALL_PACKAGES、MANAGE_EXTERNAL_STORAGE、KILL_BACKGROUND_PROCESSES、
      CAR_INFO / CAR_ENERGY / CAR_EXTERIOR_ENVIRONMENT / CONTROL_CAR_CLIMATE
组件：KeepAliveAccessibilityService（无障碍保活）
      MediaNotificationListener（通知监听）
      VehicleCoreService / FloatingBallService（前台服务 + 悬浮球）
      DesktopCardReceiver（桌面卡片/媒体 widget：geely.mediawidget、ecarx 广播）
      SimulatedGearReceiver / SimulatedHudStateReceiver（模拟档位/HUD 状态，便于调试）
queries：com.geely.map、cn.navitool.service、com.autonavi.amapauto、
         com.geely.online.service、com.geely.mediawidget、com.flyme.auto.music
```

`res/values/strings.xml` 说明它的定位就是**HUD 定制工具**：

```
HUD模式 / 布局管理 / 当前布局 / 清空小地图缓存 / 小地图预处理
HUD 显示: 巡航车道数、巡航红绿灯、车道/歌词居中、双行歌词、档位数字(D1-D8)、雪地、日夜模式
自动化: 进入导航时切换 / P档时切换 / 电话接入时切换 / 倒车时切换 HUD 显示模式
布局导入导出(本地 XML / 方案库)、车型不匹配提示（“不同车型 HUD 尺寸/分辨率支持可能不同”）
免责声明: “本功能仅为实现 HUD 自定义项目…存在部分车型不能通过该工具修改等未知情况”
```

---

## 3. 他们为什么能成功（关键前提）

```
车机助手(root) ──bindService──> 小地图服务 ──openRuntime()──> 加密载荷
      │                                （解密 + 校验签名/序号）
      └──原生运行时(libnavitool_hud_runtime.so) ──ptrace/process_vm_writev──>
             注入【HUD 的 Android 渲染进程】──Frida hook + GLES──> HUD 上出现小地图
```

**成立的前提是：领克 OS N 的 HUD 画面由一个 Android 进程渲染**，所以「注入那个进程」就等于「在 HUD 上作画」。
他们连车机型号的 HUD 尺寸/分辨率差异都要提示，也侧面说明这依赖具体车型的 Android HUD 实现。

---

## 4. 对 Q07 的意义（我们这边）

| 对比项 | 领克 OS N | 长安 Q07（我们实测） |
|---|---|---|
| HUD 是否 Android 屏 | ✅（所以能注入渲染进程） | ❌ Android 侧没有 `DISPLAY_TYPE_HUD`，也**没有 HUD 图层** |
| Android 有无 HUD 渲染进程 | ✅ 有 | ❌ **没有**（HUD/仪表由 QNX 侧渲染） |
| 跨 VM 通道 | — | 只有 `event_module/meter_service` 的 **JSON 状态**消息（type 0x11 = HUD 调节） |

**结论：这套 Frida 注入的思路，在 Q07 上没有注入目标。**
它成立的条件（「有一块 Android 进程在画 HUD」）在我们的车机上不成立。

### 可借鉴 / 不可借鉴

- ✅ **可借鉴**：整套工程做法（载荷加密+签名+防回滚、`/proc` 身份校验、调用方签名校验、
  消息用 JSON type 分发、用 Frida 做 GUI 叠层）。如果哪天我们的车机出现一个「Android 侧 HUD 渲染进程」，
  这份 playbook 直接能用。
- ❌ **不可借鉴**：注入本身 —— Q07 的 HUD 没有 Android 进程可注入。
- ⚠️ 注意：他们 app 里明确写了「部分车型不能通过该工具修改」，说明**它也不是通用方案，是逐车型适配**。

---

## 5. 顺带记录：分析产物位置

- 解密后的载荷/宿主：`C:\Users\lushu\AppData\Local\Temp\opencode\lynk\`
  （`libnavitool_hud_runtime.so`、`hud-runtime-arm64`、`classes.dex`、`assist-res/`）
