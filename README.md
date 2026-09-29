# 日语大词典（Android）

一款离线优先的日中词典：首启下载约 86 MB 的压缩词库，解压后约占 650 MB；需要约 820 MB 可用空间完成安装。下载并通过 SHA-256 校验后，查词可离线使用。

功能：

- 约 21.7 万条日语词条与简体中文释义；支持日文、假名、词条前缀和中文释义检索。
- 搜索记录、收藏、本机保存。
- 使用 Android 系统日语语音播放读音。
- 含汉字、JLPT 等其他开放数据表，之后可继续扩展查阅入口。

当前不含按计划复习、拼写/听写测试、DeepSeek 或其他 AI 功能。

词库来源、逐表授权和修改说明见 [LICENSES.md](LICENSES.md)。构建使用 Android Gradle Plugin 8.9.2、compileSdk 35、JDK 17；zstd-jni 用于展开词库压缩文件。

## 构建 APK

### 云端一键构建（推荐）

工程已带 GitHub Actions 工作流 `.github/workflows/build-apk.yml`。操作步骤：

1. 下载源码 ZIP 并解压。
2. 在 GitHub 新建仓库，Visibility 选 **Private**；建议勾选初始化 README。
3. 安装并登录 [GitHub Desktop](https://desktop.github.com/)，在 **File > Clone repository** 中克隆刚创建的仓库。
4. 把解压出来的工程文件复制到克隆目录，覆盖 README；确认 `.github` 文件夹也复制进来。
5. 回到 GitHub Desktop，在 **Changes** 写提交说明并点 **Commit to main**，再点 **Push origin**。
6. 推送后 GitHub 会自动运行 **Build Japanese Dictionary APK**。也可打开仓库 **Actions**，选择该工作流并点 **Run workflow**。
7. 构建完成后点开运行记录，在 **Artifacts** 区域下载 `JapaneseDictionary-debug-apk`；解压后就是 `app-debug.apk`。

APK 构建产物仅保留 7 天，需要时重新运行工作流即可。源码仓库保持 Private，词库文件不会上传到仓库。

GitHub Free 私有仓库包含有限的 Actions 构建分钟与工件存储额度；个人账户额度以 GitHub 当前账单页面为准。工作流在 GitHub 的 Linux runner 上安装 JDK、Android 35 SDK 与 Gradle，不要求本机安装 Android Studio。首次构建需要联网下载构建依赖。

### 本机 Android Studio 构建

也可以用 Android Studio 打开本文件夹，安装 Android SDK Platform 35 与 JDK 17，然后执行 **Build > Build APK(s)**。生成文件位于 `app/build/outputs/apk/debug/app-debug.apk`。安装后首次启动时再下载完整词库。
