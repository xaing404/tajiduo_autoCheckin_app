# 塔吉多自动签到（Android）

一个原生 Android 自动签到应用：每天定时自动完成 **APP 签到 + 游戏角色签到 + 金币任务 + 云异环时长领取**，完成后发送系统通知并自动退出进程省电。

> 本仓库中的 APK **不包含任何账号凭据**，安装后需自行配置账号（见 [账号配置](#账号配置重要)）。

---

## 功能特性

| 功能 | 说明 |
|---|---|
| 📱 验证码登录 | 应用内手机号 + 短信验证码登录，装上就能用自己的账号，无需命令行 |
| ⏰ 每日定时 | `AlarmManager` 精确闹钟，时间可在 App 内自定义（默认 08:00），开机后自动重排 |
| 🎮 完整签到 | APP 签到 + 3 个游戏角色签到 + 金币任务（签到/浏览/点赞/分享）+ 云异环时长 |
| 🔔 结果通知 | 签到结果系统通知，支持 Server 酱 / 自定义 Webhook |
| 📧 邮件通知 | 签到完成后自动将结果（含签到时间、成功/失败/跳过）发送到 QQ 邮箱 |
| 🌙 夜间补签 | 每天 23:30 自动核对当天签到结果，未成功则重新触发补签，防止漏签 |
| 🔋 省电自退 | 定时任务完成后自我了结进程并释放 WakeLock，避免后台耗电 |
| 🔐 权限引导 | 通知权限 / 精确闹钟 / 电池优化白名单 / 厂商自启动，一键跳转 |
| 📊 内置 UI | 签到状态、获得奖励、运行历史、签到时间等设置 |
| 🔁 会话保活 | accessToken → refreshToken → laohuToken → 密码登录 多级自动重建并回写 |

## 截图

| 概览与权限引导 | 设置 | 验证码登录 |
|---|---|---|
| ![概览](docs/screenshot-overview.png) | ![设置](docs/screenshot-settings.png) | ![登录](docs/screenshot-login.png) |

---

## 安装

下载 [dist/tajiduo-attendance-v1.3.2.apk](dist/tajiduo-attendance-v1.3.2.apk) 后：

```bash
adb install -r dist/tajiduo-attendance-v1.3.2.apk
```

或把 APK 传到手机点击安装。要求 **Android 8.0+**（minSdk 26），targetSdk 34。

安装后打开 App：

1. 在「账号信息」卡片点击 **「登录 / 添加账号」**，用手机号 + 短信验证码登录（见下）
2. 按「权限引导」卡片逐项授权

---

## 账号配置

仓库内的 APK 是**脱敏版**，不含任何账号凭据，安装后需登录你自己的账号。

### 方式一：应用内验证码登录（推荐）

打开 App，在「账号信息」卡片点击 **「登录 / 添加账号」**：

1. 输入手机号 → 点击「获取验证码」（60 秒后可重发）
2. 填入收到的短信验证码 → 点击「登录」

登录会自动完成整条链路：

```
短信验证码登录（老虎平台）→ 换取塔吉多会话 → 写入应用私有目录
```

成功后账号信息立即显示在卡片中，**无需任何命令行操作**。凭据仅保存在应用私有目录，卸载 App 即清除。支持多账号：再次登录会用新账号追加，同一账号登录则覆盖旧记录。

### 方式二：adb 注入（批量 / 离线场景）

debug 版 APK 支持 `run-as`，可免 root 注入现成的凭据文件：

```bash
# 1. 先打开 App 一次（让私有目录完成初始化），然后准备好 accounts.json
# 2. 推送到设备
adb push accounts.json /data/local/tmp/accounts.json

# 3. 复制到应用私有目录
adb shell run-as com.tajiduo.attendance cp /data/local/tmp/accounts.json files/accounts.json

# 4.（可选）如果需要密码兜底登录，再注入解密密钥
adb push credential-key /data/local/tmp/credential-key
adb shell run-as com.tajiduo.attendance cp /data/local/tmp/credential-key files/credential-key

# 5. 重启 App 生效
adb shell am force-stop com.tajiduo.attendance
```

### 方式三：自行构建

把真实的 `accounts.json`、`credential-key` 放入 `app/src/main/assets/` 后重新构建（见 [构建](#构建)）。
> 注意：请勿把含真实凭据的版本提交或分发。

### accounts.json 格式

```json
[
  {
    "id": "main",
    "name": "主账号",
    "uid": "你的用户ID",
    "deviceId": "设备标识",
    "accessToken": "访问令牌",
    "refreshToken": "刷新令牌",
    "openudid": "设备标识（H5 请求用）",
    "vendorid": "设备标识（H5 请求用）",
    "laohuToken": "老虎平台令牌（用于重建会话）",
    "laohuUserId": "老虎平台用户ID",
    "phone": "手机号（可选）",
    "roleId": "主角色ID（展示用）",
    "roleName": "主角色名（展示用）",
    "encryptedPassword": {
      "v": 2,
      "alg": "AES-256-GCM",
      "kdf": "scrypt",
      "salt": "base64url(salt)",
      "iv": "base64url(iv)",
      "tag": "base64url(tag)",
      "data": "base64url(密文)"
    }
  }
]
```

字段说明：

- `accessToken` / `refreshToken` — 主会话令牌，运行中会自动刷新并回写
- `laohuToken` / `laohuUserId` — 主令牌失效时用于重建会话
- `encryptedPassword` — 可选，最后兜底（用手机号 + 密码登录）；解密需配套的 `credential-key`
- 其余 `uid` / `deviceId` / `openudid` / `vendorid` 参与请求签名，必须与账号匹配

> 凭据可通过原有登录流程获取（`scrypt(N=16384,r=8,p=1,len=32)` 派生密钥 + `AES-256-GCM` 加密密码，`credential-key` 为派生口令）。

---

## 邮件通知（QQ 邮箱）

签到完成后可自动把结果发送到 QQ 邮箱：每日一封，含**签到开始/结束时间**、**结果状态（成功 / 失败 / 跳过）**与完整账号明细。

配置步骤：

1. **开启 QQ 邮箱 SMTP 服务**：登录网页版 QQ 邮箱 → 设置 → 账户 → 开启「POP3/SMTP 服务」（需短信验证）→ 生成 **16 位授权码**（只展示一次，请及时保存）。
2. **在 App 内填写**：打开 App → 设置卡片 → 「邮件通知（QQ 邮箱）」：
   - 发件 QQ 邮箱：你的完整 QQ 邮箱地址（如 `xxxx@qq.com`）
   - SMTP 授权码：上一步生成的授权码（**不是** QQ 登录密码）
   - 收件人邮箱：留空则发送到发件邮箱
3. 点击「保存设置」，再点「立即签到」即可收到测试邮件。

说明：

- 邮件通过 `smtp.qq.com:465`（SSL 加密）发送；授权码只保存在应用私有存储中，不会写入源码或提交仓库，怀疑泄露时可在 QQ 邮箱中删除并重新生成。
- 未配置时自动跳过；发送失败不影响签到主流程，仅记录日志（TAG `TajiduoAttendance`）。
- 发送为同步执行、15 秒超时，位于「完成后自动退出进程」之前，不会被省电自退截断。

---

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` | 调用签到接口 |
| `POST_NOTIFICATIONS` | 发送签到结果通知（Android 13+ 需动态申请） |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | 精确到分的每日定时 |
| `RECEIVE_BOOT_COMPLETED` | 重启后重排闹钟 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导加入电池优化白名单 |
| `FOREGROUND_SERVICE(_DATA_SYNC)` / `WAKE_LOCK` | 签到期间前台服务与唤醒锁 |

**国内 ROM 必做**：在系统设置中为 App 开启「自启动」并加入「电池/后台运行白名单」，否则定时唤醒可能被系统拦截或延迟。

---

## 构建

环境要求：JDK 17、Android SDK（compileSdk 34）。

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 若项目路径含非 ASCII 字符（中文目录），AGP 会拦截构建，已在 `gradle.properties` 中设置 `android.overridePathCheck=true`。

## 技术栈

- **语言/UI**：Kotlin + XML Views + Material Components
- **构建**：Gradle 8.7 + AGP 8.5.2 + Kotlin 1.9.24，minSdk 26 / targetSdk 34
- **网络**：OkHttp 4.12.0｜**并发**：kotlinx-coroutines｜**邮件**：JavaMail for Android 1.6.7
- **加密**：BouncyCastle（scrypt）、AES-128-ECB（签名）、AES-256-GCM（密码）

## 项目结构

```
app/src/main/java/com/tajiduo/attendance/
├── MainActivity.kt            # 首页 UI
├── HistoryAdapter.kt          # 运行历史列表
├── data/                      # 账号/状态/设置存储
├── net/                       # 协议、签名、加密、接口封装
├── login/                     # 短信验证码登录
├── runner/                    # 签到执行器（核心流程）
├── schedule/                  # 闹钟调度、夜间补签检查与开机重排
├── service/                   # 前台服务
├── notify/                    # 系统通知、Webhook 与邮件
└── permission/                # 权限引导
```

## 更新记录

### v1.3.2

- **修复极端网络环境下邮件发送卡死导致丢失**：超级省电过夜 + 深度休眠唤醒后的首次网络连接中，JavaMail 内部超时曾失效，SMTP 调用阻塞导致进程挂起数小时、邮件丢失且无法自杀退出。现改为独立线程执行并设 45 秒硬性总时长上限，超时即放弃等待并继续后续流程；单次失败后自动重建连接重试一次。
- 进程启动时设置 `java.net.preferIPv4Stack=true`，规避不可达 IPv6 路径造成的连接长时间阻塞。
- 邮件发送结果新增持久化记录（`email:sign` / `email:check` + 日期 = `sent` / `failed: 原因`），便于出问题时快速排查。

### v1.3.1

- **夜间检查改为每次结果都发送通知与邮件**：补签检查（23:30 默认）发现全部账号当天已签到时，由原来的静默返回改为发送「塔吉多夜间检查完成」通知与邮件（主题「塔吉多签到 | 夜间检查·全部已签 | 时间」），仅报告检查结果，不执行签到、不改写状态与历史。
- 发现未签时的补签链路不变：重新触发完整签到，照常发送签到结果通知与邮件。
- 通知与邮件受设置中「通知开关 / 邮件配置」控制，未配置时不发送。

### v1.3.0

- **新增夜间补签检查**：每天 23:30（默认）自动核对当天每个账号的签到结果，只要有账号未签到成功，就重新触发一次完整签到（已签账号自动跳过），防止白天定时被 ROM 拦截导致的漏签。
- 检查闹钟与主签到闹钟**独立并存**，同样使用 `setAlarmClock()` 保证准点，开机 / 应用更新后自动重排；未配置账号或全部已签时静默返回，不产生通知与邮件。
- 触发补签时走完整链路：通知 / Webhook / 邮件照常发送，完成后同样省电自退。实测准点触发偏差为毫秒级（目标 17:09:00，实际 17:09:00.009）。
- 因签到按「账号 + 日期」去重，检查时间放在当天 23:30 可真正补上当天漏签；若放到 00:00 之后补的将是新的一天，无法挽回漏签的当天。

### v1.2.0

- **新增邮件通知（QQ 邮箱）**：签到完成后自动将结果发送到 QQ 邮箱，正文含签到开始/结束时间、结果状态（成功/失败/跳过）与完整账号明细；通过 `smtp.qq.com:465`（SSL）发送，授权码仅存应用私有存储（`commit()` 落盘）。
- 设置卡片新增「邮件通知（QQ 邮箱）」分组：发件邮箱 / SMTP 授权码 / 收件人（留空发给自己），发件与授权码必须成对填写才会生效。
- 发送时机位于杀进程之前（同步发送、15 秒超时），邮件失败仅记录日志，不影响签到主流程。
- 新增依赖 JavaMail for Android（`com.sun.mail:android-mail` / `android-activation` 1.6.7）。

### v1.1.1

- **更换应用图标**：使用《异环》角色壁纸作为图标，按 1:1 正方形构图以脸部为视觉中心裁切，保留发饰与爱心元素、裁掉文字 logo；生成全套自适应图标（adaptive icon）与各密度位图（mdpi~xxxhdpi），背景色取角色服装绯红 `#A22B3E`。

### v1.1.0

- **新增应用内验证码登录**：`MainActivity` 的「账号信息」卡片新增「登录 / 添加账号」按钮，手机号 + 短信验证码即可登录，无需 adb 注入凭据，任何人都能轻松上手。流程：`sendCaptcha` → `loginWithCaptcha` → `userCenterLogin` → 写入私有目录。
- 账号卡改为只展示已配置凭据的账号；未登录时给出引导文案，不再显示空模板。
- 支持多账号：新账号追加、同账号覆盖。

### v1.0.1

- **修复定时不准点**：改用 `setAlarmClock()`（系统唯一保证准点投递的接口，不受 Doze 与厂商电源策略窗口改写）。实测触发偏差由**分钟级降到毫秒级**（目标 16:30:00，实际 16:30:00.062）。
  副作用：状态栏会出现闹钟图标，点击可回到本应用。
- **修复定时运行后状态丢失**：`markSigned()` / `saveSummary()` / `lastRunDate` 由 `apply()`（异步刷盘）改为 `commit()`（同步落盘）。原实现中任务结束后会立即自杀进程，异步写入来不及刷盘就被丢弃，导致**去重键失效**、App 内结果展示陈旧。
- **修复 UI 数据源不一致**：结果卡改为优先读取 `runs.json` 最新记录（同步写入可靠），仅在历史缺失时回退 `last-summary`。
- **修复开机锁屏期不重排闹钟**：`BootReceiver` 增加 `android:directBootAware="true"`，`SettingsStore` 迁移到设备加密存储（direct boot），重启后未解锁也能读到签到时间。
- 新增关键路径日志（TAG: `TajiduoAttendance`），便于排障：闹钟排程/触发、服务启停、每账号结果、杀进程。

### v1.0.0

- 首个版本：定时签到、完整签到流程、结果通知、省电自退、权限引导、内置 UI。

## 已知限制

1. 使用 `setAlarmClock()` 会在**状态栏常驻闹钟图标**，这是为保证准点投递所做的取舍。
2. 国内 ROM 仍需手动开启「自启动」与「电池/后台运行白名单」，否则后台服务可能被拦截。

## 免责声明

本项目仅供个人学习与自动化实践使用。请自行评估账号与合规风险，**不要将包含凭据的 APK 分发给他人**，也不要将 `local-credentials/`、真实的 `assets/accounts.json`、`credential-key` 提交到任何仓库。