# Q07 车机「安装校验」逆向与绕过（CAKJ）

> 目标：搞清 `INSTALL_FAILED_INVALID_APK: CAKJ failed to verify APK result` 到底卡在哪，以及怎么把 APK 装进去。
> 结论：校验在**框架层**，逻辑已完整还原；并找到了**无需改系统、已验证可用**的绕过方法。

---

## 1. 校验代码在哪

| 文件 | 类/方法 | 作用 |
|---|---|---|
| `/system/framework/services.jar` | `com.android.server.pm.PackageInstallerSession.validateApkInstallLocked()` | 安装前逐文件校验，失败即抛异常 |
| `/system/framework/ext.jar` | `com.vecentek.storeverity.APKVerityUtil` | 真正的校验实现（白名单 / 证书 / 签名） |
| `/system/framework/ext.jar` | `com.vecentek.storeverity.VerifyByEDC` `HexUtil` `FileUtils` `Base64Utils` | 签名工具 |

日志 tag：`Leosin-APK`（`Slog/Log.d`），另有 `PackageInstallerSession` 的 `CAKJ ...` 日志。

> 注意：`APKVerityUtil` **不在** `services.jar` 里（只在里面被引用），它在 **`ext.jar`**（boot classpath）。

---

## 2. 判定流程（从 smali 还原）

`PackageInstallerSession.validateApkInstallLocked()` 对每个暂存 APK 执行：

```java
verity = new APKVerityUtil();                 // 构造时写入 5 个「可信下载目录」
boolean isDebug = verity.isDebug();
int isInstallCheck = SystemProperties.getInt("vecentek.model", 0);

// ① 调试旁路（本机走不到）
if (isDebug && isInstallCheck == 1) {
    log("unverified");  goto 放行;                  // ← 直接跳过校验
}

// ② 白名单包名（本机走这里）
if (verity.isWhiteListApp(mPackageName)) {
    log("white app,pass");  goto 放行;
}

// ③ 签名校验（长安私钥签名，三方无法伪造）
if (verity.verity(mPackageName, apkPath)) {
    log("verifying APK is passed");  goto 放行;
}

throw PackageManagerException(-2, "CAKJ failed to verify APK result");
```

### ① isDebug()

```java
Build.TAGS.contains("test-keys") && checkForDangerousProps()
```
`checkForDangerousProps()` 就是扫 `getprop` 输出里有没有 `[ro.debuggable]: [1]`。

* 本机：`ro.debuggable = 1`（✅ 满足），但 `ro.build.tags = release-keys`（❌ 不满足）
  → `isDebug()` 恒为 **false**，此路在本机**默认走不通**。
  （`vecentek.model` 是普通属性，`setprop vecentek.model 1` 可以设，但 `isDebug` 为 false 时没用。）

### ② 白名单包名（写死在 ext.jar）

```java
whiteListPackages = {
  "de.dsa.ford.otaflash",          // 未安装 ← 可用
  "com.changan.acscenter",         // 未安装 ← 可用
  "com.incall.apps.intelligenthmi",// 已装（/system/app/IntelligentHMI）
  "com.incall.apps.softmanager",   // 已装（/system/app/IncallFota）← 注意包名额不副实
  "com.incall.submasterservice"    // 已装（/system/app/C3bSubmaster）
}
```

只做 `equals(包名)` 比较，**与签名、来源、installer 都无关**。
→ 只要 APK 的 `package=` 是上面任一个、且该包名**尚未安装**，安装直接放行。

### ③ 签名校验（vecentek）

可信目录（构造时写死 5 个）：

```
/data/data/com.changan.appmarket/files/download/<pkg>/          ← 官方应用市场
/data/user/0/com.incall.apps.sdalauncher/app_apks/<pkg>/
/data/user/0/com.changan.auto.devicemanagement/files/download/<pkg>/
/fota/subMaster/<pkg>/
/storage/emulated/0/Download/apps/<pkg>/
```

在目录里找 `<dir>/<pkg>`，命中后要求：

```
<dir>/signed           Base64 的签名
<dir>/appchangan.crt   证书（必须由内置 CHANGAN CA 根证书签发）
```

`saveVerity()` 逻辑：
1. `verifyCert(certPath)`：内置根证书（CN=CHANGAN Services CA，`MIIDZjCC...`）+ 传入证书做 issuer/subject 校验 + `verify(rootPubKey)`。
2. `getVerity()`：用证书公钥对 `signed` 做解密（`VerifyByEDC.decryptWithPublicKey`），结果里必须含 APK 的 **SHA-256 十六进制串**。

→ **必须长安私钥才能签出 `signed`**，三方无法伪造。（实测：把 apk 放进市场下载目录后报 `根证书校验失败`。）

---

## 3. 已验证可用的绕过：**用白名单包名安装**

不用 root、不改系统、不装驱动，只要 APK 的 `package` 取那 5 个中的**未安装**名字。

实测（2026-10-14，`192.168.137.167:5555`）：

```powershell
# 1) 改包名（示例用 apktool 在已有 APK 上改，实际项目直接改 applicationId 更好）
apktool d -f app-release.apk -o renamed
# 编辑 renamed\AndroidManifest.xml: package="de.dsa.ford.otaflash"
apktool b renamed -o renamed-test.apk

# 2) 用自己的 keystore 正常签名
apksigner sign --ks q07battery-release.jks --ks-key-alias q07battery `
  --ks-pass pass:q07battery --key-pass pass:q07battery renamed-test.apk

# 3) 正常安装
adb install -r renamed-test.apk
# => Performing Streamed Install / Success
```

对照实验（同一次抓包）：

| APK 包名 | 结果 | 日志 |
|---|---|---|
| `com.lushu.q07battery` | ❌ `CAKJ failed to verify APK result` | `Leosin: The apk pkg is not found` |
| `de.dsa.ford.otaflash` | ✅ `Success` | 走 `isWhiteListApp` 放行 |

安装完成后 `pm path` 正常、activity/service 全限定名照常工作（改包名不影响运行）。

**限制**：白名单只有 5 个名额、3 个已被系统应用占用 → 只剩 **2 个可用**（`de.dsa.ford.otaflash`、`com.changan.acscenter`）。
这是「装**有限几个**自己的 App」的办法，不是通用办法。

---

## 4. 通用办法（需改框架，未实测）

要装**任意包名**，必须让白名单/校验放行。最小改动是 `ext.jar`：

* 方案 A：把 `whiteListPackages` 里某个名字换成自己的包名；
* 方案 B：把 `APKVerityUtil.isWhiteListApp()` 直接 `return true`（一次搞定所有包名）。

投递方式（`ro.boot.veritymode=enforcing`，`/system` 只读，**不能直接改分区**）：

| 方式 | 影响 | 风险 |
|---|---|---|
| **overlayfs / bind mount** 覆盖 `/system/framework/ext.jar`，再重启 framework | 仅内存生效，重启即恢复，**不动分区、不破 verity** | 需在 init 的 mount namespace 里挂载；ART 若因 dex 变化无法用旧 oat 会回退解释执行 |
| `adb disable-verity` + `adb remount` 改分区 | 永久（可再 enable） | 破坏 verity/OTA 完整性，需解锁（本机 `vbmeta.device_state=unlocked`） |

改完都必须 **重启 `system_server`**（`adb shell stop; adb shell start` 或整机重启）才会加载新的 `ext.jar`。

---

## 5. 关键结论

1. 安装校验 = `PackageInstallerSession` + `ext.jar` 的 `APKVerityUtil`，**不是**可 `pm disable` 的 App，也**不是**网络校验（离线也拦）。
2. 判定三条路：`isDebug&&vecentek.model==1` / `白名单包名` / `长安签名`。
3. 本机 `isDebug=false`、签名无法伪造 → **只剩「白名单包名」可走**。
4. **已经把 APK 装进去了**：把包名改成 `de.dsa.ford.otaflash`（白名单第二位，未安装）即 `Success`。
5. 要装任意包名，只能改 `ext.jar`（详见第 4 节）。
