# 构建说明

## 环境

本项目使用 Gradle 9.3.1、Android Gradle Plugin 9.1.1、Kotlin 2.3.21，已在 JDK 21 和 Android SDK Platform 37 环境构建。推荐用 Android Studio 打开仓库根目录，安装对应 SDK；或设置 `JAVA_HOME` 和 `ANDROID_HOME`。

若手动创建 `local.properties` 指定 `sdk.dir`，该文件只供本机使用，禁止提交。

## 调试包

```powershell
# Windows
.\gradlew.bat :app:assembleDebug
```

```sh
# macOS / Linux
chmod +x gradlew
./gradlew :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。应用 ID 为 `com.mingyue.app.debug`，可与正式版并存；两者不共享临时数据或旧版文件。

## 加密核心测试

```powershell
.\gradlew.bat cryptoChecks
```

在其他系统使用 `./gradlew cryptoChecks`。测试只生成合成数据，不读取个人文件。检查内容包括默认噪点和照片封面 PNG、图片原始字节还原、认证失败、边界、改密及 80 MiB 流式输入，测试 JVM 堆上限为 64 MiB。

## 发布包

未配置签名时：

```powershell
.\gradlew.bat :app:assembleRelease
```

输出未签名 APK，不能直接安装。发布前请使用**自己的**签名密钥；原作者的密钥不会公开。下列环境变量必须同时设置：

| 环境变量 | 用途 |
| --- | --- |
| `MINGYUE_SIGNING_STORE` | 你自己的 keystore 文件路径 |
| `MINGYUE_SIGNING_PASSWORD` | 密钥库与签名密钥的密码 |
| `MINGYUE_SIGNING_ALIAS` | 密钥别名 |

密钥库与签名密钥使用不同密码时，请自行调整签名配置。通过终端、系统或 CI 的秘密变量设置密码，不要写进代码或命令截图。

配置后执行 `:app:assembleRelease`，或安装 Python 3.10+ 后运行 `python build_release.py`。脚本将 APK 复制到被 Git 忽略的 `dist/`，未配置签名时会明确标记 `-unsigned`。

自己签名的 APK 与作者发布的 APK 签名不同，不能覆盖安装。继续维护同一个包时，要保管好自己的签名密钥。

## 依赖与网络

首次构建需要下载 Gradle、Google Maven / Maven Central 依赖和对应 SDK；这属于开发环境联网。安装后的应用本身不申请联网权限。

Gradle 分发包固定 SHA-256 校验值。CI 会运行核心测试并构建调试包，CI 产物使用调试签名，不替代作者签名的 Releases 安装包。
