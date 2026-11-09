# 上传 GitHub 生成 APK（完整教程）

目标：把工程推到 GitHub，让它自动编译出 `app-debug.apk`，手机下载安装。全程不需要本地装 JDK / Android Studio。

---

## 0. 先记住唯一的硬要求

**仓库根目录下必须直接看到 `.github/`、`android/`、`config/`、`sim/` 这几个文件夹并排。**

GitHub 只认仓库根下的 `.github/workflows/*.yml`。如果你把整个 `jd-blackfriday-sniper` 文件夹拖进仓库，路径会变成 `jd-blackfriday-sniper/.github/...`，Actions 不会出现 —— 这是最常见的失败原因。

判断方法：打开仓库首页，文件列表第一层应该是 `android`、`config`、`docs`、`scripts`、`sim`、`README.md`，**不是** `jd-blackfriday-sniper`。

---

## 路线 A：命令行（本机已装 git 2.53，最快，5 分钟）

### A1. 解压

把 `jd-blackfriday-sniper.zip` 解压到任意目录，例如：

```
C:\Users\25329\Documents\jd-blackfriday-sniper
```

### A2. 网页建空仓库

1. 打开 https://github.com/new
2. Repository name 填 `jd-sniper`
3. 选 **Public**（公开仓库的 Actions 分钟数无限；私有仓库每月 2000 分钟也够用，但公开最省事）
4. **不要勾** Add a README file / .gitignore / license（仓库必须是空的）
5. 点 Create repository

### A3. 本地初始化并推送

```powershell
cd C:\Users\25329\Documents\jd-blackfriday-sniper

git init -b main
git add .
git commit -m "jd black friday sniper: android autopilot"
git remote add origin https://github.com/你的用户名/jd-sniper.git
git push -u origin main
```

首次 push 会弹浏览器登录 GitHub（Git Credential Manager），点授权即可。

若提示 `remote origin already exists`：

```powershell
git remote set-url origin https://github.com/你的用户名/jd-sniper.git
git push -u origin main
```

### A4. 确认目录结构

推送完刷新仓库页面，第一层应该是：

```
.github/  android/  config/  docs/  scripts/  sim/  .gitignore  README.md
```

对不上就回去看第 0 节。

---

## 路线 B：GitHub Desktop（不想碰命令行）

1. 装 GitHub Desktop：https://desktop.github.com/
2. 登录 GitHub 账号。
3. 菜单 **File → Add local repository**，选解压后的 `jd-blackfriday-sniper` 文件夹。
4. 提示 "not a Git repository" 时点 **create a repository**。
   - Name：`jd-sniper`
   - Local path：**父目录**（例如 `C:\Users\25329\Documents`）
   - 不要勾 Initialize with README
5. 左下角填 Summary（随便写），点 **Commit to main**。
6. 右上角 **Publish repository**，**取消勾选 Keep this code private**，点 Publish。

这样仓库根就是 `jd-blackfriday-sniper` 目录本身，结构天然正确。

---

## 路线 C：纯网页上传（不装任何东西）

1. https://github.com/new 建空仓库（同 A2）。
2. 进仓库，点 **Add file → Upload files**。
3. 在 Windows 资源管理器里打开 `jd-blackfriday-sniper` 文件夹，**进入文件夹内部**，全选这 8 项：
   ```
   .github  android  config  docs  scripts  sim  .gitignore  README.md
   ```
   一起拖进网页上传区。**不要拖外层的 `jd-blackfriday-sniper` 文件夹本身。**
4. 等文件列表加载完（约 57 个文件），底部 Commit changes 填一句说明，点 **Commit changes**。

> 网页上传对 `.` 开头的文件夹是支持的，但部分浏览器在拖拽多文件夹时顺序会乱。传完一定回首页确认第一层结构（第 0 节）。结构不对就删库重建，别在网页里挪文件。

---

## 2. 触发构建

推送/提交后自动开始。也可以手动：

**Actions 标签 → 左侧 `android` → 右侧 Run workflow → Run workflow**

首次进 Actions 页如果看到黄色横幅「Workflows aren't being run on this repository」，点 **I understand my workflows, go ahead and enable them**。

一次运行包含两个 job：

| job | 内容 | 正常耗时 |
|---|---|---|
| 逻辑仿真测试 | `node --test`，28 条排期与状态机用例 | 20 秒 |
| 编译 debug APK | JDK 17 + Android SDK + Gradle 8.7 → `assembleDebug` | 3–6 分钟（首次要下依赖，慢一些） |

两个都是绿勾才算成功。

---

## 3. 下载 APK

1. Actions → `android` → 点最新那次运行（绿勾）。
2. 页面**最底部** Artifacts 区域 → 点 **jd-sniper-debug-apk**。
3. 得到一个 zip，解压后里面是 `app-debug.apk`（约 3–6 MB）。

Artifact 保留 30 天（workflow 里 `retention-days: 30`），过期就重新跑一次。

---

## 4. 装到手机

任选一种传输方式：微信「文件传输助手」、USB 数据线、网盘、QQ。

手机端：

1. 设置 → 安全 → 允许「安装未知来源应用」（各 ROM 名称不同，小米叫「未知来源应用安装」）。
2. 点 APK 安装。若提示「应用未安装 / 签名冲突」，先卸载旧版本再装。
3. 打开后按 README 的「首次配置 8 步」走：四项权限 → 登录京东 → 填真 SKU → 校时 → 预热测试。

---

## 5. 修改后重新出包

改任何文件（比如把真实 SKU 写进 `config/app-config.json`，或给 `autopilot.js` 加按钮词）：

```powershell
cd C:\Users\25329\Documents\jd-blackfriday-sniper
git add .
git commit -m "update sku"
git push
```

推上去自动重新构建，几十秒后 Actions 里就有新 Artifact。

**想直接改配置不打代码**：APK 装好后在主界面填 SKU 保存即可，`config/app-config.json` 只是出厂默认值，改它需要重新构建。

---

## 6. 排错表

| 现象 | 原因 | 处理 |
|---|---|---|
| Actions 标签里没有 `android` 这个 workflow | `.github/workflows/android.yml` 不在仓库根 | 回第 0 节，重建仓库结构 |
| 有 workflow 但一直不跑 | push 到 `master` 之外的命名分支 / Actions 被禁用 | 确认分支是 `main`；Settings → Actions → General → Allow all actions |
| `sim-test` 红叉 | node 测试失败（改坏了 `autopilot.js`） | 点开日志看断言，本地先跑 `node --test sim/schedule.test.mjs sim/autopilot.test.mjs` |
| `SDK location not found` | runner 上 SDK 路径异常 | ubuntu-latest 自带 Android SDK；看 `SDK 环境` 那步有没有打印出 `ANDROID_HOME` |
| `Failed to find package 'tools'` | 用了 `android-actions/setup-android` | 该 action 默认装已被 Google 下架的 `tools` 包，本 workflow 已移除它（实测踩过） |
| `Failed to install ... licenses` | license 未接受 | 托管 runner 镜像已预接受；自建 runner 先跑 `yes \| sdkmanager --licenses` |
| `Unsupported class file major version` | 用了 JDK 21+ | workflow 里 `java-version: '17'` 别改 |
| `gradle: command not found` | setup-gradle 步骤被注释/失败 | 恢复 `gradle/actions/setup-gradle@v3` |
| `Artifact not found` / `if-no-files-found` 报错 | APK 实际没产出，构建早失败了 | 看 `assembleDebug` 那一步的日志，别只看最后一步 |
| `Permission denied` 推不上去 | 账号没权限 / token 过期 | 用 GitHub Desktop 重新登录，或换 HTTPS + PAT |
| 网页上传后文件是散的 | 拖了外层文件夹 | 删库重建，按路线 C 第 3 步拖内部内容 |
| 私有仓库 Actions 停了 | 免费额度用尽 | 仓库 Settings → General → 改 Public，或等下月额度 |

---

## 7. 验收清单

- [ ] 仓库首页第一层就是 `android/`、`config/`、`.github/`
- [ ] Actions 里 `android` 有两次绿勾（sim-test + build-apk）
- [ ] Artifacts 能下到 `jd-sniper-debug-apk.zip`，解压出 `app-debug.apk`
- [ ] 手机能装上，打开后主界面显示「下一次：2026-11-12T20:00」
- [ ] 点「预热测试」，日志出现 `[sku] ready`

全过之后，第一天正式用之前记得把 4 个占位 SKU 换成真实 SKU。
