# 长安官方 SDK（CaSdkManager）授权机制 —— 逆向到底

> 2026-10-16。结论：机制完全还原，但**无法绕过** —— 最后一道墙是厂商云端下发的白名单。

## 1. 直连是通的，卡在服务端授权

自己 bind `com.incall.apps.sdkservice`（`/system/app/IncallSdkService/IncapSdkService`）+
用官方 AAR 的 AIDL 桩 `ICaSdkService.callServiceSync(module, service, method, params, extra)`
直连调用，**完全绕开了客户端 APIManager 的接口拦截**，但服务端返回：

```json
{"code":-1001, "codeMsg":"app未授权, 请联系SDASdkService添加白名单", "module":"SDASDKSERVICE"}
```

日志证据：能成功调用的只有厂商自家应用（`com.incall.primateservice`、`com.incall.SoaBridgeTransit`）。

## 2. 服务端授权逻辑（dexdump 还原）

在 `classes4.dex` → `com.incall.apps.sdkservice.binder.CaSdkBinder`：

```java
if (!AuthorityManager.getInstance().checkAuthority(module, service, method)) {
    notifyFailed(cb, CtCommon$SdkResponse.NOT_AUTHORITY.toString());  // -1001
    return;
}
ModuleFactory.getModule(module)...
```

`com.incall.apps.sdkservice.authority.AuthorityManager`：

```java
private static final boolean IS_CONFIG = true;
public boolean checkAuthority(module, service, method) {
    if ("app_module".equals(module)) return true;      // ★ 唯一的无条件放行
    return mAuthorityModel.checkAuthority(service, method);
}
```

`AuthorityModel`：

```java
private static final String AUTHORITY_FILE = "authority.json";
Map mPackChannelEnMap;          // 包名 → channel
Map mPackChannelEnModulesMap;   // 包名+channel → 允许的模块
Map mPackPackEnMap;
```

**=> 白名单 = 「包名 + channel → 允许的模块列表」，由云端下发并缓存在 `authority.json`。**

## 3. 为什么绕不过

| 门 | 情况 |
|---|---|
| `module == "app_module"` | 无条件放行 ✓ —— 这也解释了为什么 SDK 的 `app_module/config_service/get_config_info` 能成功。但 `app_module` 下**只有 config_service**，拿不到车辆数据 ✗ |
| `authority.json` | 在 sdkservice **私有目录**（车机全盘搜不到，`/data/data/...` Permission denied，`adb root` 已失效）→ **写不进去** ✗ |
| 文件来源 | `IS_CONFIG=true` = 从配置读，而配置是**云端下发**的 ✗ → 回到"开发者平台要开通 API 列表" |

## 4. 结论

官方 SDK 这条路**只能由厂商打开**（要么平台开通 API 列表，要么把包名写进车端白名单），
第三方从外部无解。

而且没有损失：官方 SDK 的 `vehicle_service.drive` 只有车速/挡位/里程/轮胎，
**没有发动机转速** —— 我们的 HIDL 直读方案信号更全。

代码保留：`carapp/libs/CaSdkManager-0.5.52_developer_ca.aar`、`CaSdkTest`、`CaSdkDirect`
（均默认关闭，将来若拿到白名单，把 `ENABLED` 改 true 即可）。
