# Chaysu

基于 [KernelSU](https://github.com/tiann/KernelSU) 的深度定制版，重点做了**内核符号层脱敏**与**全链路脱名**，并重做了界面。

> **重要**：这不是一个可以直接分发的通用安装包。内核模块（`kernelsu.ko`）在编译时就把**管理器包名**和**签名证书哈希**写死在里面了，因此每个使用者都**必须用自己的证书和包名重新编译一次**。下面的步骤会带你从零走完整个流程，大约 20–30 分钟。

---

## 目录

- [改了什么](#改了什么)
- [开始之前：弄清楚你的设备](#开始之前弄清楚你的设备)
- [第一步：准备构建环境](#第一步准备构建环境)
- [第二步：生成你的签名证书](#第二步生成你的签名证书)
- [第三步：从证书里算出两个关键参数](#第三步从证书里算出两个关键参数)
- [第四步：编译内核模块 kernelsu.ko](#第四步编译内核模块-kernelsuko)
- [第五步：编译 ksud 与 ksuinit](#第五步编译-ksud-与-ksuinit)
- [第六步：编译管理器 APK](#第六步编译管理器-apk)
- [第七步：安装并修补镜像](#第七步安装并修补镜像)
- [第八步：刷入并验证](#第八步刷入并验证)
- [更换内核版本 / 更换设备](#更换内核版本--更换设备)
- [常见问题](#常见问题)
- [隐蔽性说明与边界](#隐蔽性说明与边界)

---

## 改了什么

### 1. 内核符号层脱敏（核心）

新增 [`kernel/feature/kallsyms_hide.c`](kernel/feature/kallsyms_hide.c)，在模块初始化时定位内核的 `kallsyms_op` 与 `modules_op` 两个 `seq_operations` 结构，用 `ksu_patch_text` 替换它们的 `show` 函数指针：

- **已授权的 uid**（root / 管理器）：原样放行，功能完全不受影响
- **其他所有进程**：读取 `/proc/kallsyms` 和 `/proc/modules` 时，凡是符号名或模块名里带 `ksu` / `kernelsu` 的行都会被丢弃

效果（普通 App 视角实测）：

| 检测面 | 官方 KernelSU | Chaysu |
|---|---|---|
| `/proc/kallsyms` 含 ksu 的行 | 240+ 条 | **0 条** |
| `/proc/modules` 的 kernelsu 行 | 可见 | **0 行** |
| `/sys/module/*ksu*` | 可见 | **0 个** |

挂载点：[`kernel/Kbuild`](kernel/Kbuild)（加入构建）、[`kernel/core/init.c`](kernel/core/init.c)（初始化与退出）。

### 2. 管理器全链路脱名

把 `me.weishu.kernelsu` 整个改成了中性包名（示例中为 `com.chayu.ksu`）：

- Kotlin 包结构与目录（240 个文件）
- Manifest 里硬编码的类名（`me.weishu.kernelsu.ui.MainActivity`、`me.weishu.kernelsu.magica.AppZygotePreload` 等）
- JNI 的 21 个 `Java_me_weishu_kernelsu_*` 符号
- `namespace` 声明（决定 `R` 与 `BuildConfig` 的包名）

### 3. daemon 路径改名

`/data/adb/ksud` → `/data/adb/kud`。涉及 [`kernel/runtime/ksud.h`](kernel/runtime/ksud.h) 的 `KSUD_PATH` 与 [`userspace/ksud/src/defs.rs`](userspace/ksud/src/defs.rs) 的 `DAEMON_PATH`，**两处必须保持一致**。

### 4. 运行时加固

- `selinux_hide` 开启：净化 App 对 `/sys/fs/selinux` 的查询
- `shell_su_adb_only` 开启：限制 shell 的 root 来源
- `sulog` 关闭：不产生 su 日志
- `/data/adb/ksu/log` 权限收紧到 `0700`
- `/data/adb/ksu`、daemon 的 SELinux 标签统一为中性 `adb_data_file`

### 5. 界面

- 默认 Material 风格，移除 Miuix 切换入口
- 底栏改为液态玻璃悬浮样式，点击有放大动画
- 主页移除"支持开发"和"了解 KernelSU"卡片
- 关闭应用更新检查与 LKM 更新提示
- 新图标（深炭黑底 + 白色方块边框），自适应图标占比 0.68

---

## 开始之前：弄清楚你的设备

**这一步决定了后面能不能成功，不要跳过。**

你需要确认三件事：

### 1. 内核的 KMI 版本

KernelSU 的 KO 必须与内核匹配。手机上的识别方法：

```bash
su -c "uname -r"
# 例：6.6.118-android15-8-gc44b714366cc-abogki519650608-4k
```

从版本串里对上 KMI：

| 内核版本串里的特征 | KMI |
|---|---|
| `android12-5.10` 或 5.10.x | `android12-5.10` |
| 5.10.x（Android 13） | `android13-5.10` |
| 5.15.x（Android 13） | `android13-5.15` |
| 5.15.x（Android 14） | `android14-5.15` |
| 6.1.x | `android14-6.1` |
| 6.6.x | `android15-6.6` |
| 6.12.x | `android16-6.12` |
| 6.18.x | `android17-6.18` |

也可以用 KernelSU 的 ksud 直接问（已装官方版的情况下）：

```bash
su -c "/data/adb/ksud boot-info current-kmi"
```

### 2. 分区类型

```bash
su -c "/data/adb/ksud boot-info is-ab-device"     # true 表示 A/B 分区
su -c "/data/adb/ksud boot-info available-partitions"
su -c "/data/adb/ksud boot-info default-partition"   # 通常是 init_boot 或 boot
```

- **GKI 设备**（Android 12+，内核 5.10+）：KernelSU 一般装在 **`init_boot`** 分区
- **非 GKI / 老设备**：装在 **`boot`** 分区

### 3. 设备架构

```bash
getprop ro.product.cpu.abi
# 绝大多数是 arm64-v8a
```

如果不是 `arm64-v8a`，你需要额外编译对应架构的 ksud（见[更换设备](#更换内核版本--更换设备)）。

---

## 第一步：准备构建环境

以下以 **Ubuntu 22.04/24.04 x86_64** 为例。全程需要能访问 GitHub 与 Google 的下载源。

### 1.1 基础工具

```bash
sudo apt update
sudo apt install -y git curl unzip python3 openjdk-21-jdk keytool cpio
```

确认 Java 版本必须是 **21**：

```bash
java -version
# openjdk version "21.x.x" 才对
```

### 1.2 Android SDK

```bash
export ANDROID_HOME=$HOME/android-sdk
mkdir -p $ANDROID_HOME

cd /tmp
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-11076708_latest.zip -d $ANDROID_HOME
mkdir -p $ANDROID_HOME/cmdline-tools/latest
mv $ANDROID_HOME/cmdline-tools/bin $ANDROID_HOME/cmdline-tools/lib \
   $ANDROID_HOME/cmdline-tools/NOTICE.txt $ANDROID_HOME/cmdline-tools/source.properties \
   $ANDROID_HOME/cmdline-tools/latest/

export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "ndk;29.0.14206865"
```

> 版本号必须和 [`manager/build.gradle.kts`](manager/build.gradle.kts) 里的一致（compileSdk 37、buildTools 37.0.0、NDK 29.0.14206865）。版本对不上会在编译时报错。

把这些写进 `~/.bashrc` 免得每次重来：

```bash
cat >> ~/.bashrc <<'EOF'
export ANDROID_HOME=$HOME/android-sdk
export ANDROID_SDK_ROOT=$HOME/android-sdk
export ANDROID_NDK_HOME=$HOME/android-sdk/ndk/29.0.14206865
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
EOF
source ~/.bashrc
```

### 1.3 Rust（编 ksud 和 ksuinit 用）

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
source ~/.cargo/env
rustup target add aarch64-linux-android
```

### 1.4 Docker（编内核模块用）

```bash
sudo apt install -y docker.io
sudo usermod -aG docker $USER
newgrp docker
docker info    # 能输出信息就说明可用
```

### 1.5 克隆源码

```bash
git clone https://github.com/baizhou04/chaysu.git
cd chaysu
```

---

## 第二步：生成你的签名证书

**这个证书就是你的身份，请长期保存。** 以后每次升级都必须用同一个证书，否则内核不认，管理器会显示"未安装"。

```bash
mkdir -p ~/chaysu-keys
cd ~/chaysu-keys

keytool -genkeypair \
  -keystore key.jks \
  -alias chaysu \
  -keyalg RSA -keysize 2048 \
  -validity 10950 \
  -storepass 你的密码 \
  -keypass 你的密码 \
  -dname "CN=chaysu, OU=dev, O=chaysu, L=City, ST=State, C=CN"
```

参数说明：

- `-alias chaysu`：别名，后面编译要用到，记牢
- `-validity 10950`：有效期 30 年
- `-storepass`/`-keypass`：建议同一个密码
- `-dname`：随便填，不影响功能

> **别把 `key.jks` 提交到任何仓库。** 丢了就得重刷内核模块。

---

## 第三步：从证书里算出两个关键参数

内核模块需要知道两个值来校验管理器身份：**证书的字节长度**和**证书的 SHA-256**。

### 3.1 导出证书（DER 格式）

```bash
cd ~/chaysu-keys
keytool -exportcert -alias chaysu -keystore key.jks -storepass 你的密码 -file chaysu.der
```

### 3.2 算出 size（十六进制）

```bash
SIZE_DEC=$(stat -c%s chaysu.der)
SIZE_HEX=$(printf '0x%04x' $SIZE_DEC)
echo "KSU_EXPECTED_SIZE=$SIZE_HEX   (十进制 $SIZE_DEC)"
```

典型值是 `0x0353`（851 字节），也可能略有不同，以你算出来的为准。

### 3.3 算出 hash（小写、无冒号）

```bash
HASH=$(sha256sum chaysu.der | awk '{print $1}')
echo "KSU_EXPECTED_HASH=$HASH"
```

### 3.4 记下三个值

```
SIZE_HEX = 0x0353                       ← 用你自己的
HASH     = 693105e2...d2d6              ← 用你自己的
ALIAS    = chaysu
```

> 也可以用 APK 反过来验证：`apksigner verify --print-certs 你的.apk`，其中 `certificate SHA-256 digest` 应该和上面的 HASH 一致。

---

## 第四步：编译内核模块 kernelsu.ko

用 KernelSU 官方的 DDK 容器，按你的 KMI 编译。示例用 `android15-6.6`，**换成你自己的 KMI**。

### 4.1 拉取 DDK 镜像

```bash
# 官方源（国内可能很慢）
docker pull ghcr.io/ylarod/ddk-min:android15-6.6-20260828

# 国内推荐用南大镜像，速度快很多
docker pull ghcr.nju.edu.cn/ylarod/ddk-min:android15-6.6-20260828
```

镜像 tag 规则：`<KMI>-<DDK发布日>`。可以在这里查可用 tag：`https://github.com/ylarod/ddk-min/pkgs/container/ddk-min`

### 4.2 编译

把第一步的三个值填进去：

```bash
cd chaysu

docker run --rm --privileged \
  -v "$PWD":/work -w /work/kernel \
  ghcr.nju.edu.cn/ylarod/ddk-min:android15-6.6-20260828 \
  bash -c '
    CONFIG_KSU=m CC=clang make \
      KSU_PACKAGE_NAME=com.chayu.ksu \
      KSU_EXPECTED_SIZE=0x0353 \
      KSU_EXPECTED_HASH=693105e2b3a13b618837284bb4fd71f5dd3cbf6c4ac2df2997e459fd3aeffb6
  '
```

编译成功后产物在 `kernel/kernelsu.ko`。

### 4.3 验证 KO 里的参数对不对

```bash
strings kernel/kernelsu.ko | grep -E "package name|signature"
```

必须看到这三行，值是你自己填的：

```
-- KernelSU Manager package name: com.chayu.ksu
-- KernelSU Manager signature size: 0x0353
-- KernelSU Manager signature hash: 693105e2...
```

> `KSU_PACKAGE_NAME` 必须和你后面编 APK 时用的包名**完全一致**，否则管理器不被识别。

---

## 第五步：编译 ksud 与 ksuinit

ksud 是用户态 daemon，**它会把你刚编的 KO 内嵌进去**，所以修补镜像时用的就是你的 KO。

### 5.1 先把 KO 放进资源目录

```bash
cd chaysu

# 注意：文件名格式是 <KMI>_kernelsu.ko
cp kernel/kernelsu.ko userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko
```

### 5.2 编译 ksuinit

```bash
cd chaysu
L=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
CLANG=$L/bin/aarch64-linux-android26-clang
BUILTINS="$($CLANG --print-resource-dir)/lib/linux/libclang_rt.builtins-aarch64-android.a"

export CC_aarch64_linux_android=$CLANG
export AR_aarch64_linux_android=$L/bin/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$CLANG
export RUSTFLAGS="-C target-feature=+crt-static -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wno-unused-command-line-argument -C link-arg=$BUILTINS"

cargo build --package ksuinit --target=aarch64-linux-android --release

cp target/aarch64-linux-android/release/ksuinit userspace/ksud/bin/aarch64/ksuinit
```

### 5.3 编译 ksud

```bash
cd chaysu
export CC_aarch64_linux_android=$L/bin/aarch64-linux-android26-clang
export CXX_aarch64_linux_android=$L/bin/aarch64-linux-android26-clang++
export AR_aarch64_linux_android=$L/bin/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$L/bin/aarch64-linux-android26-clang
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--sysroot=$L/sysroot -I$L/sysroot/usr/include/aarch64-linux-android"
export KSU_PACKAGE_NAME=com.chayu.ksu

cargo build --target aarch64-linux-android --release --manifest-path ./userspace/ksud/Cargo.toml
```

产物：`target/aarch64-linux-android/release/ksud`（体积约 7MB，比官方 3.6MB 大，因为内嵌了 KO 和 ksuinit）。

### 5.4 放进 APK 的 jniLibs

```bash
mkdir -p manager/app/src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/release/ksud manager/app/src/main/jniLibs/arm64-v8a/libksud.so
```

> 文件名必须是 `libksud.so`，这是 Android 加载 native 库的约定。

---

## 第六步：编译管理器 APK

### 6.1 配置签名（用环境变量，不要写进文件）

```bash
export KEYSTORE_FILE=$HOME/chaysu-keys/key.jks
export KEYSTORE_PASSWORD=你的密码
export KEY_ALIAS=chaysu
export KEY_PASSWORD=你的密码
```

[`manager/app/build.gradle.kts`](manager/app/build.gradle.kts) 会自动读取这些环境变量。

### 6.2 编译

```bash
cd chaysu/manager

./gradlew assembleRelease \
  -PKSU_PACKAGE_NAME=com.chayu.ksu \
  -PKSU_NAME=Chaysu \
  --no-daemon
```

- `-PKSU_PACKAGE_NAME`：**必须和编 KO 时的完全一致**
- `-PKSU_NAME`：桌面上显示的应用名，可以随便改

产物：`manager/app/build/outputs/apk/release/Chaysu_*_release.apk`

### 6.3 验证 APK

```bash
APK=$(ls manager/app/build/outputs/apk/release/*.apk)

# 包名对不对
$ANDROID_HOME/build-tools/37.0.0/aapt2 dump packagename $APK

# 签名是不是你自己的证书
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs $APK

# 确认 libksud.so 在
unzip -l $APK | grep libksud
```

三条都要对得上：包名 = `com.chayu.ksu`，证书 SHA-256 = 你的 HASH，`libksud.so` 存在。

---

## 第七步：安装并修补镜像

### 7.1 安装 APK

```bash
adb install -r 你的.apk
```

或者拷到手机直接点安装。

### 7.2 在手机上修补

打开 **Chaysu**，主页会提示"未安装"，点击它 → 选择 **"安装"** → 选择修补方式：

- **推荐：修补 init_boot / boot 镜像文件**（需要你自备原厂镜像）
- 或者选择直接刷入（需要已 root，但首次安装时通常没有）

App 会用内嵌的 ksud 把你的 KO 打进镜像，产出 `kernelsu_patched_xxx.img`。

### 7.3 或者用命令行修补

如果你更喜欢命令行（ksud 在 APK 里，也可以单独取出用）：

```bash
su -c "/data/adb/kud boot-patch \
  -b /sdcard/init_boot.img \
  -m /path/to/kernelsu.ko \
  --partition init_boot \
  -o /sdcard/output"
```

参数：`-b` 原镜像、`-m` 你的 KO（不填就用内嵌的）、`--partition` 分区名、`-o` 输出目录。

---

## 第八步：刷入并验证

### 8.1 刷入前务必备份

```bash
# 先备份原分区
adb shell su -c "dd if=/dev/block/by-name/init_boot_a of=/sdcard/init_boot_backup.img"
adb pull /sdcard/init_boot_backup.img
```

**这一步不能省。** 刷错会开不了机。

### 8.2 刷入（二选一）

**方式 A：fastboot（推荐，最稳）**

```bash
adb reboot bootloader
fastboot flash init_boot kernelsu_patched_xxx.img
fastboot reboot
```

**方式 B：手机上直接用 dd**

```bash
adb push kernelsu_patched_xxx.img /sdcard/
adb shell su -c "dd if=/sdcard/kernelsu_patched_xxx.img of=/dev/block/by-name/init_boot_a bs=4096"
adb shell su -c sync
adb reboot
```

> A/B 设备的当前分区可能是 `_a` 也可能是 `_b`，用 `getprop ro.boot.slot_suffix` 确认。

### 8.3 验证是否成功

重启后打开 Chaysu，主页应该显示 **"工作中"** 和版本号。也可以命令行验证：

```bash
# 内核接口是否活着
su -c "/data/adb/kud debug info"
# 应输出 version / uapi_version / lkm: true

# 管理器是否被认作管理器（关键）
su -c "dmesg | grep -i 'Crowning manager'"
# 应看到：KernelSU: Crowning manager: uid=xxxxx
```

### 8.4 验证隐藏是否生效

```bash
# 普通身份看不到 ksu 符号
su shell -c "grep -c ksu /proc/kallsyms"     # 应为 0
su shell -c "grep -c ksu /proc/modules"      # 应为 0

# 但 root 自己仍然看得到（不影响功能）
su -c "grep -c ksu /proc/kallsyms"           # 应为 240 左右
```

---

## 更换内核版本 / 更换设备

### 换 KMI

如果你的设备不是 `android15-6.6`，改这几处：

1. 第四步：DDK 镜像 tag 换成你的 KMI，例如 `ghcr.nju.edu.cn/ylarod/ddk-min:android14-6.1-20260828`
2. 第五步：KO 文件名换成对应 KMI，例如 `cp kernel/kernelsu.ko userspace/ksud/bin/aarch64/android14-6.1_kernelsu.ko`
3. 其余步骤不变

KernelSU 支持的 KMI 完整列表见 [`.github/workflows/build-lkm.yml`](.github/workflows/build-lkm.yml)。

### 换架构（x86_64 / riscv64）

当前仓库里 `userspace/ksud/bin/` 只预置了 `aarch64` 目录。要用在 x86 设备上：

1. 第五步把 `aarch64-linux-android` 换成 `x86_64-linux-android`
2. KO 文件名放进 `userspace/ksud/bin/x86_64/`
3. jniLibs 目录改成 `manager/app/src/main/jniLibs/x86_64-v8a/libksud.so`

### 用 GitHub Actions 自动构建（最省事）

仓库自带 CI，改 [`.github/workflows/build-lkm.yml`](.github/workflows/build-lkm.yml) 的 `kmi` 列表，或者手动触发 `Build LKM` workflow，可以一次编出多个 KMI 的 KO。

要在 CI 里签名，需要在仓库 Settings → Secrets 里配置：

| Secret 名 | 内容 |
|---|---|
| `KEYSTORE` | keystore 文件 base64（`base64 -w0 key.jks`） |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 别名 |
| `KEY_PASSWORD` | key 密码 |

---

## 常见问题

### 装上了但显示"未安装"

三个原因，按顺序排查：

1. **包名不一致**：`KSU_PACKAGE_NAME` 编 KO 时和编 APK 时必须是同一个值
2. **签名不一致**：APK 必须用与 `KSU_EXPECTED_HASH` 对应的那个证书签名。用 `apksigner verify --print-certs` 核对
3. **KO 没生效**：`su -c "dmesg | grep KernelSU"`，看有没有 `package name xxx` 的输出，值对不对

### 刷完开不了机 / 卡开机动画

进 fastboot 刷回备份：

```bash
fastboot flash init_boot init_boot_backup.img
fastboot reboot
```

所以第八步的备份**必须做**。

### KO 加载失败：`invalid module format`

vermagic 不匹配。KO 的 vermagic 来自 DDK 参考内核，必须和你设备的实际内核一致。GKI 设备只要 KMI 相同通常能过；不一致时需要开 `CONFIG_MODULE_FORCE_LOAD` 或用 KernelSU 的 late-load 模式。

### 编译 APK 报 `Unresolved reference 'R'` 或 `'BuildConfig'`

`namespace` 没改。改 [`manager/app/build.gradle.kts`](manager/app/build.gradle.kts) 第 64 行的 `namespace`，必须与你的包名一致。

### gradle 下载卡住

Gradle 官方源在国内经常连不上。可以预先把发行包下好：

```bash
curl -L -o /tmp/gradle.zip https://mirrors.cloud.tencent.com/gradle/gradle-9.7.1-bin.zip
# 解压到 ~/.gradle/wrapper/dists/gradle-9.7.1-bin/<hash>/ 下
# <hash> 目录名可以用 ./gradlew --version 触发一次后看报错里的路径
```

### 模块安装报 "KernelSU version is too old"

日志形如：

```
- KernelSU version: 16 (kernel) + ... (ksud)
! KernelSU version is too old!
```

内核报文是 `16`，说明 KO 是在**没有 git 历史的目录里编译的**。KernelSU 的版本号来自 `git rev-list --count HEAD`，在 DDK 容器内没有 `.git`，于是 Kbuild 回退到兜底的 `KSU_VERSION=16`。

本仓库已修复：`kernel/Kbuild` 支持用 `KSU_VERSION` 环境变量显式覆盖，`userspace/ksud/build.rs` 与 `manager/build.gradle.kts` 也会读取同名变量。编译时统一指定即可：

```bash
export KSU_VERSION=32680
```

三处（内核 KO、ksud、管理器 APK）必须用**同一个值**，否则会出现版本不一致的告警。

验证方法：

```bash
strings kernel/kernelsu.ko | grep "welcome to KernelSU"
# 应显示你设定的版本号，而不是 16
```

### 模块安装报 "Unable to apply SELinux patches"

日志形如：

```
- Checking SELinux patches
! Unable to apply SELinux patches!
! Your kernel may not support SELinux patch fully
```

这**不是**内核不支持 SELinux 打补丁，而是 daemon 路径改名导致的兼容性问题。

原因：本仓库把 daemon 从 `/data/adb/ksud` 改名成了 `/data/adb/kud`，但第三方模块的安装脚本里普遍硬编码了旧路径。以 Zygisk Next 为例，它的 `customize.sh` 里有个 `check_sepolicy` 函数：

```sh
check_sepolicy() {
    /data/adb/ksud sepolicy check "$1"
    return $?
}
```

`/data/adb/ksud` 不存在 → 命令执行失败 → 返回非零 → 模块误判为“内核不支持”。

验证规则本身没问题：

```bash
/data/adb/kud sepolicy apply /path/to/sepolicy.rule
# 能打印出解析后的每条规则、返回 0，说明打补丁功能正常
```

本仓库已修复：`ksud install` 执行时会**自动创建兼容链接** `/data/adb/ksud -> /data/adb/kud`，卸载时一并清理（见 [`userspace/ksud/src/utils.rs`](userspace/ksud/src/utils.rs) 的 `link_daemon_legacy_name`）。

如果你用的是较早的版本，手动补一条链接即可：

```bash
su -c "ln -sf /data/adb/kud /data/adb/ksud"
```

> 隐蔽性影响：`/data/adb` 目录权限是 `0700`，普通应用无法进入，因此该链接不会暴露给检测方。

### 改了 daemon 路径后 root 没了

`KSUD_PATH`（内核侧）和 `DAEMON_PATH`（ksud 侧）必须同时改、且**都编进新镜像后再删旧文件**。顺序错了会导致内核找不到 daemon，`su` 直接 Permission denied。

正确顺序：

1. 改两处源码
2. 编新 KO + 新 ksud
3. 新 ksud 放到新路径（如 `/data/adb/kud`）
4. 新 KO 打进镜像并刷入
5. 重启验证 root 正常
6. **最后**再删旧文件

---

## 隐蔽性说明与边界

### 做到了什么

| 项目 | 状态 |
|---|---|
| `/proc/kallsyms` 的 ksu 符号 | 非授权进程不可见 |
| `/proc/modules` 的 kernelsu 行 | 非授权进程不可见 |
| `/sys/module/*ksu*` | 不存在 |
| `/system/bin/su` | 非授权进程不可见 |
| `/data/adb` | 权限 0700，不可读 |
| SELinux 查询 | `selinux_hide` 已开启 |
| 管理器包名 / 类名 | 已脱名 |
| daemon 文件名 | 已改为 `kud` |
| 日志目录 | 0700 |

### 无法覆盖的

如实说明，避免误解：

1. **已安装应用无法真正隐藏**。`pm list packages` 仍能看到管理器。Android 上任何 App 都能枚举其他 App。
2. **TEE 级完整性认证**。Play Integrity / SafetyNet 基于硬件签名，任何内核方案都无法绕过。bootloader 解锁状态也会被标记。
3. **动态行为检测**。已授权 App 主动调 `su` 时仍有短暂的进程与域切换痕迹。
4. **vermagic 绑定**。KO 与内核强绑定，跨内核版本必须重编。

### 安全建议

- **改掉默认密码**，不要把 keystore 密码和系统密码设成一样
- `key.jks` 离线保存，不要提交到任何仓库
- 从官方渠道获取原厂 boot/init_boot 镜像，确保备份可靠

---

## 致谢与许可

本项目基于 [KernelSU](https://github.com/tiann/KernelSU)（作者 weishu）修改，遵循 **GPL-3.0** 许可，与上游一致。

内核部分遵循 GPL-2.0。使用、修改、分发请遵守相应许可证。

感谢 KernelSU 社区与 DDK 镜像维护者。
