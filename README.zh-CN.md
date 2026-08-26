# SoftPOS — 离线非接触式原型

[English](README.md) · **简体中文**

[![CI](https://github.com/CrazyDudo/SoftPOS/actions/workflows/ci.yml/badge.svg)](https://github.com/CrazyDudo/SoftPOS/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

一套 Android SDK 与演示应用：通过 NFC 读取非接触式支付卡，立即对卡片数据做脱敏处理，并在本地记录
一笔购物车订单。

## 它不是什么

**它不收款。** 没有收单机构、没有授权、没有清算、没有结算，也不存在任何形式的网络调用。读卡在
READ RECORD 之后即停止：不生成应用密文（cryptogram），不做卡片认证，不做持卡人验证。

本项目未按 PCI MPoC、PCI DSS 或 EMVCo 认证标准构建或评估，其中任何内容都不应被当作"卡片为真"或
"交易已发生"的证据。请使用测试卡或你自己的卡。

被刻意略去的那些 EMV 功能——离线数据认证（SDA/DDA/CDA）、CVM 处理、终端风险管理、GENERATE AC
——连同规范出处，都列在
[`EmvReadFlow`](emv-core/src/main/kotlin/com/softpos/emv/flow/EmvReadFlow.kt) 的类文档中。

## 模块

| 模块 | 类型 | 内容 |
|---|---|---|
| `emv-core` | Kotlin/JVM，**不依赖 Android** | BER-TLV 编解码、EMV 标签注册表、AID 注册表、APDU 编码、DOL 构造器、完整读卡流程、交易状态机 |
| `softpos-sdk` | Android library | `IsoDep` 传输、读卡器模式绑定、Keystore 加密、Room 持久化、外设、导出、`SoftPos` 门面 |
| `demo` | Android 应用 | Compose MVVM：商品目录 → 购物车 → 刷卡 → 历史 |

`emv-core` 不依赖 Android 是有意为之。所有值得推敲的判断——一个标签如何解码、哪个候选应用胜出、
PDOL 响应里放什么、一笔交易何时允许改变状态——都住在这里，并由纯 JVM 单元测试覆盖。`softpos-sdk`
只提供基于 `IsoDep` 的 `ApduTransceiver` 和存储，不包含任何 EMV 解析逻辑。

## 构建与测试

```bash
# 单元测试。只要有 JDK 17 就能跑，不需要 Android SDK。
./gradlew :emv-core:test

# 单个测试类，或单个测试。
./gradlew :emv-core:test --tests '*BerTlvParserTest'
./gradlew :emv-core:test --tests '*EmvReadFlowTest.reads a visa card end to end'

# Android 模块，需要 Android SDK。
./gradlew :softpos-sdk:assembleDebug :demo:assembleDebug
./gradlew :demo:installDebug
```

**必须使用 JDK 17。** Android Gradle Plugin 拒绝在更低版本上运行；而 `settings.gradle.kts` 只要
能找到 SDK 就会把 Android 模块接进来，于是低版本 JDK 会让整个构建失败——包括 `:emv-core:test`，
尽管 core 本身跟 Android 毫无关系。

`settings.gradle.kts` 只在能找到 Android SDK 时才纳入 `:softpos-sdk` 和 `:demo`（依次查找
`ANDROID_HOME`、`ANDROID_SDK_ROOT`，以及 `local.properties` 中的 `sdk.dir`）。找不到时它只配置
`:emv-core` 并明确提示，这样 core 在一台什么都没装的 CI runner 上依然可测。用
`-PforceAndroidModules=true` 可强制启用 Android 模块。

Gradle wrapper（8.11.1）已入库，全新 clone 只需要一个 JDK 17。Android SDK 路径来自
`local.properties`，该文件不入库——Android Studio 首次打开时会写入，或者你自己设置 `ANDROID_HOME`。

### 持续集成

[`ci.yml`](.github/workflows/ci.yml) 在每次向 `main` 推送和提交 PR 时运行：

- **`core`** 在清空 `ANDROID_HOME` 与 `ANDROID_SDK_ROOT` 的前提下跑 `:emv-core:test`，Android 模块
  被完全跳过。这是刻意为之：它让上文"不需要 Android SDK"这句话是被验证过的，而不只是宣称。
- **`package`** 构建 demo APK 与 SDK AAR，并把两者作为 workflow artifact 上传。

两个 job 在执行入库的 Gradle wrapper JAR 之前，都会先用 Gradle 官方发布的校验和验证它。

[`release.yml`](.github/workflows/release.yml) 由 `v*` 形式的 tag 触发。它会重跑测试，用带版本号的
文件名构建同样那两个产物，并发布一个附带它们的 GitHub Release：

```bash
git tag v0.1.0 && git push origin v0.1.0
```

demo APK 是 **debug** 构建。该应用没有配置签名，release APK 出来会是未签名的、装不上；SDK 是
library，不需要签名，因此以 release variant 发布。

## 读卡流程

`EmvReadFlow` 实现了 EMV 4.4 Book 1 §12.3（应用选择）与 Book 3 §10.1–10.2，并遵循 EMV Contactless
Book B §3.3 的非接触式入口点行为：

1. **SELECT PPSE** —— `2PAY.SYS.DDF01`。解析 `6F → A5 → BF0C → 61*` 取出 AID、标签与优先级。
   若卡片没有 PPSE，则退回到逐个选择已注册 AID 的方式。
2. **构建候选列表** —— 与终端 AID 注册表取交集（仅 Visa 与 Mastercard 系列），按应用优先级指示符
   排序。
3. **SELECT 应用** —— 读取 FCI，取出 PDOL。
4. **GET PROCESSING OPTIONS** —— 用 `TerminalProfile` 填充 PDOL，从 Format 1（`80`）或
   Format 2（`77`）响应中解析 AIP 与 AFL。返回 `6985` 则丢弃该候选并尝试下一个。
5. **READ RECORD** —— 遍历 AFL，把所有内容累积进一个 `TlvDatabase`。
6. **提取** —— 从 `5A` 或 Track 2 取 PAN，从 `5F24` 或 Track 2 取有效期，另外得到卡组织、标签，
   并做一次 Luhn 校验。

每一步都在
[`EmvReadFlowTest`](emv-core/src/test/kotlin/com/softpos/emv/flow/EmvReadFlowTest.kt) 中针对模拟卡
做了验证，包括 PPSE 缺失时的回退、`6985` 时的候选穿透、应用被锁、记录缺失、Track 2 回退，以及传输
中断。

## 卡片数据如何处理

项目要求在"内存中短暂使用的原始数据"与"落盘的脱敏数据"之间划一条线。这条线由类型强制，而不是靠
约定：

- **`Pan` 用 `CharArray` 而非 `String` 保存数字**，这样 `close()` 才真的能把它覆写掉。对完整卡号
  的访问被限制在 `reveal { }` 作用域内。`toString()` 永远不会渲染出数字。
- **`RawCardData` 实现 `AutoCloseable`**，会擦除 PAN、磁道数据以及每一个敏感 TLV 值。它的
  `applicationLabel` 与 `cardholderName` 是 `String`，无法擦除——这个局限被写在类文档里，而不是
  被掩盖过去。
- **`CardVault.ingest()` 会消费传入的 `RawCardData`**，并且是获得 `CapturedCard` 的唯一途径。它
  下游的任何代码都够不到完整卡号。
- **`RedactedCard` 是 UI 和数据库唯一能看到的卡片类型。** 没有持卡人姓名、没有磁道数据、没有完整
  PAN——只用一个布尔值记录"曾存在姓名"。
- **APDU trace 默认脱敏。** 原始非接触式 trace 中含有 tag `57`，那就是一个完整 PAN。`ApduTrace`
  会重新解析每一条响应，扣留标签注册表标记为敏感的所有值；无法解析的响应则整条扣留。
- **未知的 primitive 标签默认视为敏感。** 对一个陌生的专有数据元过度脱敏，比泄漏它划算。
- **卡片指纹**（Keystore HMAC-SHA256 对 PAN 取值后截断）用于把同一张卡的多次到访归组。它是单向的，
  且由于密钥从不离开 Keystore，即便拿到一个候选 PAN 也无法在设备之外重算出该指纹。
- **`persistEncryptedPan` 默认关闭。** 打开它会把 AES-256-GCM 密文存在一个 Keystore 密钥之下——
  那是可还原的数据，风险性质完全不同。默认值实现的是项目要求的规则：只保留后四位，别的都不留。
- **`SoftPos.wipeAllCardData()`** 会删除 Keystore 密钥，从而让已存储的每一个指纹和密文块永久不可读。

## 交易状态机

```
  CREATED ──SUBMIT──▶ PENDING ──BEGIN_PROCESSING──▶ PROCESSING ──COMPLETE──▶ PROCESSED
     │                   │                              │
     │                   │                              └──FAIL──▶ FAILED
     │                   │                                           │
     │                   │                        SCHEDULE_RETRY ◀───┤
     │                   │                              │            └──ABANDON──▶ ABANDONED
     │                   │                              ▼
     │                   │                       RETRY_SCHEDULED ──BEGIN_PROCESSING──▶ PROCESSING
     └───────────────────┴──────────────CANCEL──────────┴──────────────▶ CANCELLED
```

`TransactionStateMachine` 是一个纯粹的表驱动函数。`TransactionRepository.applyEvent()` 是 `state`
列的唯一写入者：它先咨询状态机，成功后更新该行并向 `transaction_events` 追加一条审计记录。非法的
状态转移会以 `TransitionResult.Rejected` 返回，而不是把那一行数据写坏。

`PROCESSING` 被刻意设计为不可取消——写到一半的操作会先落到 `FAILED`，这样失败原因才会被记录下来。

在离线场景下，"处理中"意味着扣减库存并出具小票。没有任何东西需要重发，因此重试就是把本地的收尾
逻辑重跑一遍。

## 使用 SDK

```kotlin
val softPos = SoftPos.create(context, SoftPosConfig(merchantName = "My Store"))

softPos.cardReader.reads(activity) { cart.totalMinor }.collect { event ->
    when (event) {
        is CardReadEvent.Completed -> {
            // event.captured.card is a RedactedCard. The raw data is already wiped.
            val id = softPos.transactions.create(lines, "USD", event.captured)
            softPos.transactions.applyEvent(id, TransactionEvent.SUBMIT)
            softPos.transactions.process(id) { runCatching { softPos.catalog.reserve(lines) } }
        }
        is CardReadEvent.Failed -> showError(event.message)
        else -> Unit
    }
}
```

只要该 Flow 处于被收集状态，读卡器模式就保持开启；收集一停止，它就被关闭。

## 演示应用

三个页签：**Shop**（商品目录与购物车）、**Tap**（读卡器模式、掩码后的卡片结果、可展开的 APDU
trace）、**History**（状态标签、逐笔交易的审计轨迹、重试/放弃/取消、通过分享面板导出 CSV 与
JSON）。

商品目录页面刻意允许超量下单——正是它在不需要真实硬件故障的前提下，跑通了
`FAILED → RETRY_SCHEDULED → ABANDONED` 这条路径。

每个界面都带有"本应用不收款"的横幅提示，打印出的小票页脚固定为
`OFFLINE PROTOTYPE - NOT A PAYMENT RECEIPT`。

## 已知缺口

- 无离线数据认证。证书类数据元（`8F`、`90`、`9F46`、`93`、`9F4B`）会被解析进 TLV 数据库，但从不
  校验。CA 公钥表（`CapkRegistry`）是存在的，也会用已发布的校验和验证条目，但没有任何代码读取
  它——把它填满不会改变任何行为。
- 无持卡人验证。CVM 列表只被读取，不被评估。
- 多应用冲突处理仅有优先级排序。EMV Book 1 §12.4 要求在优先级指示符置起 b8 时由持卡人确认；
  `CardCandidate` 暴露了该标志位，但流程不会弹出提示。
- Kernel 2 与 Kernel 3 在 GPO 之后分歧很大。这里只实现了两者共有的前半段，Kernel 2 特有的数据元
  （`DF8117`、`9F1D`）填的是占位值。
- 外设部分只有接口加一个 logcat 打印器。蓝牙 SPP、USB 与扫码都标记为 TODO，而不是凭空猜实现。
- 终端配置的默认值（`9F33`、`9F40`、`9F1A`、`5F2A`、`9F09`）描述的是这个原型本身，并不是收单机构
  下发的终端参数。
- `emv-core` 是针对模拟卡验证的。本项目中没有任何部分在真实硬件上跑过。

## 许可

采用 [Apache License 2.0](LICENSE)。

EMV® 是 EMVCo, LLC 的注册商标。Visa、Mastercard、American Express、JCB、Discover 和 UnionPay 均为
各自所有者的商标。本项目与 EMVCo 及任何卡组织均无隶属关系，未获其认可或认证；文中出现这些标识仅
用于指明代码所引用的规范与应用标识符。
