# 京东黑五抢购台（Android，路径 B）

对着「每周5有特价 / 黑色星期五」这套活动做的**定时抢购工具**：周四 20:00 预约+提前抢，周五 10:00 / 16:00 / 20:00 / 22:00 抢购。到点自动把鸡蛋 / 纸巾 / 牛奶 / 可乐推进到「订单已提交、待支付」，剩下付款由人来做。

原生 Android 工程，Kotlin + WebView，**不引第三方 SDK**，页面内自动化，不碰 `h5st` / `sign`。

---

## 一、它到底怎么抢（先看这段，决定你要不要用）

| 环节 | 做法 |
|---|---|
| 签名 | **不自己构造下单请求**。所有点击都发生在 `m.jd.com` 页面里，`h5st` / `sign` 由京东自己的 JS 生成，天然一致 |
| 准时 | 三源 UDP NTP 取中位数算 `offset`，再用 `SystemClock` 级别的本地时钟等点；开火误差目标 ≤ 80ms |
| 不冷启动 | 闹钟在 **T-5 分钟**就拉起界面并加载好 4 个页面，T0 只做点击（WebView 冷启动 2–4 秒，临场开必输） |
| 抗杀后台 | `setAlarmClock`（Doze 免疫）+ 前台服务（specialUse）+ **全屏 Intent** + WakeLock 四层兜底 |
| 抢不到 | 命中「已抢完 / 无货」立刻停该 SKU，换下一个，不空转 |
| 支付 | **停在收银台**，震动 + 全屏通知喊人去付。自动支付会直接撞风控，不做 |

**能力边界（技术事实，不是免责声明）**：能不能抢到由库存量、账号权重、网络 RTT、风控策略共同决定。工具做的是「不漏点、不手抖、不因加载慢而错过前 3 秒」，不是「保证有货」。

---

## 二、目录

```
jd-blackfriday-sniper/
├─ config/app-config.json          ★ 唯一配置源（时间表 + SKU + 节奏），Gradle preBuild 自动复制进 assets
├─ android/                        Android Studio 工程根
│  └─ app/src/main/
│     ├─ assets/autopilot.js       ★ 页面内状态机（核心，Node 可直接跑测试）
│     ├─ java/com/lcs/jdsniper/
│     │  ├─ core/  SaleCalendar · TimeSync · AlarmScheduler · SniperService
│     │  │           WebViewPool · JsBridge · RunLog · Notifier · 两个 Receiver
│     │  ├─ data/  AppConfig / ConfigStore
│     │  ├─ ui/    MainActivity · SniperActivity · LoginActivity · LogActivity
│     │  └─ util/  Perms · Skus
│     └─ res/      布局 / 主题 / 图标
├─ sim/                            Node 仿真：假 DOM + 假时钟，直接加载 autopilot.js
├─ scripts/check.ps1               静态自检（XML/JSON/JS/资源引用/包名/桥契约 + 跑测试）
└─ docs/                           使用说明 / SKU 获取 / 风控与签名策略
```

---

## 三、构建

本机没有 JDK / Android SDK 也能拿到 APK，两条路选一条。

**A. GitHub Actions（推荐，零环境）**

推到 GitHub，`.github/workflows/android.yml` 会跑 `node --test` + `gradle assembleDebug`，在 Actions 的 Artifacts 里下载 `jd-sniper-debug-apk`。

> 第一次推仓库的话看这份逐步教程：[docs/GitHub构建APK教程.md](docs/GitHub构建APK教程.md) —— 含命令行 / GitHub Desktop / 纯网页三条路线，以及「仓库根目录结构」这个必踩的坑。
>
> 实测记录：托管 runner 自带 Android SDK，**不要**加 `android-actions/setup-android`（它默认安装的 `tools` 包已被 Google 下架，会让 `sdkmanager` 以 exit 1 结束）。

**B. 本地 Android Studio**

```bash
# JDK 17 + Android SDK 34
cd android
gradle assembleDebug          # 或直接 Android Studio 打开 android/ 目录
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

**C. 提交前自检（不需要 JDK）**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check.ps1
```

会校验：XML/JSON/JS 语法、Manifest 权限与组件、Kotlin 包名与目录、`R.id/R.string/R.layout/R.drawable` 全部引用、JS 桥契约、配置契约，并跑 28 条仿真测试。

---

## 四、首次配置（8 步）

1. **装 APK**，打开「京东黑五抢购台」。
2. **四项权限全绿**：精确闹钟、忽略电池优化、后台弹出界面/悬浮窗、通知（含全屏）。缺一项，后台就未必唤得起。
3. **登录京东**：点「登录京东账号」，在 WebView 里扫码/账号登录，返回时检测到 `pt_key` 即成功（Cookie 与抢购台共用）。
4. **填 SKU**：把商品链接粘进「SKU 或商品链接」输入框，失焦自动解析成纯数字 SKU 并补 `m.jd.com` 地址。默认 4 条是占位（`SKU_EGG` / `SKU_PAPER` / `SKU_MILK` / `SKU_COLA`），**必须换成真 SKU**。
5. **勾选要抢的**（建议最多 4 个，第 5 个起内存吃不消），点「保存配置」。保存时会自动排下一次闹钟并回显「下次 2026-11-12T20:00」。
6. **点「立即校时」**，看到「偏差 x ms」即可；偏差 > 800ms 说明网络差，换个网再校。
7. **点「预热测试」**进抢购台，确认：4 个页面能加载、日志出现 `ready`、顶部时钟在走、`offset` 与刚才一致。看一遍「诊断当前页」的输出，确认能抓到「预约 / 立即抢购」按钮。
8. **到点前 5 分钟别手动杀 App**。界面会被自动拉起并保持亮屏。

---

## 五、时间表（改 `config/app-config.json`）

| 事件 id | 周几 | 时间 | 动作 |
|---|---|---|---|
| `thu_reserve` | 周四 | 20:00:00 | `RESERVE_AND_GRAB`：先补预约再抢 |
| `fri_1000` | 周五 | 10:00:00 | 抢购 |
| `fri_1600` | 周五 | 16:00:00 | 抢购 |
| `fri_2000` | 周五 | 20:00:00 | 抢购 |
| `fri_2200` | 周五 | 22:00:00 | 抢购 |

- 时区**固定** `Asia/Shanghai`，不看系统时区（出国/改时区也不会错点）。
- `preloadLeadSec: 300` = T-5 分钟预热；`leadMs` = 开火提前量（周四 400ms，其余 250ms）。
- `retryWindowSec: 90` = 开火后最多尝试 90 秒。
- `staggerPerItemMs: 60` = 4 个页面按 0/60/120/180ms 错开，避免同时压同一条链路。

---

## 六、抢购台现场

- 顶部：`.SSS` 精度的服务端时间、剩余秒数、`offset`。
- 中间：每个 SKU 一个 WebView，标题后缀实时显示「已就绪 / 已预约 / 已点抢购 / 进结算 / 订单已提交 / 已抢完」。
- 底部：滚动日志 + 三个按钮 —— **手动开火**（补抢、过完验证后续抢）、**停止**、**诊断当前页**。
- 命中「订单已提交」：界面转红、强震动、全屏通知，**立刻去京东付款**（未支付订单会被取消）。

---

## 七、排查表

| 现象 | 大概率原因 | 处理 |
|---|---|---|
| 到点完全没反应 | 精确闹钟没开 / 被 ROM 杀了 / 电池优化没关 | 四项权限重开；小米华为 OPPO 另外把「自启动」「后台弹出」打开 |
| 界面起来了但一直是「等待布防」 | 已过窗口（`now > at + 90s`） | 手动开火 |
| 页面白屏 | 网络断 / WebView 被系统禁用 | 换网；系统设置里恢复 Android System WebView |
| 一直「找不到按钮」 | 京东改版，按钮文案变了 | 点「诊断当前页」看 `buttons` 输出，把新文案加进 `autopilot.js` 的 `TXT` 词典后重新构建 |
| 提示「需要人工过验证」 | 命中滑块/安全验证 | 在页面里手动过验证，再点「手动开火」 |
| 提示「登录态失效」 | `pt_key` 过期 | 回主界面重新登录 |
| 提交了但没付上款 | 人工环节 | 15–30 分钟内付款，超时订单自动取消 |

日志：主界面「查看日志」→「导出 CSV」，字段 `time, iso, type, sku, stage, attempt, detail`。

---

## 八、设计取舍（为什么这么做）

1. **DOM 自动化 > 接口重放**：`h5st` 每次请求都变，逆向它等于每周维护一套算法；页面内点击把签名问题彻底绕开，代价是依赖 DOM 结构，所以用「文本匹配 + 选择器兜底 + 诊断工具」三重保险。
2. **半自动 > 全自动**：验证码/滑块一出现就暂停等人，比硬刚风控活得久。
3. **停在收银台**：自动支付必然触发风控（且需要支付密码），人工付款 10 秒的事，不值得为它牺牲账号。
4. **单账号、单设备、低频**：不做多账号轮询、不做接口刷单。工具的目标是「我自己那只手更快一点」。

---

## 九、许可

个人自用。京东商品页结构、活动规则随时可能变，`TXT` 词典与 `config/app-config.json` 就是为改版准备的。
