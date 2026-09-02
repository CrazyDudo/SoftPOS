# SoftPOS

[English](README.md) · **简体中文**

[![CI](https://github.com/CrazyDudo/SoftPOS/actions/workflows/ci.yml/badge.svg)](https://github.com/CrazyDudo/SoftPOS/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/CrazyDudo/SoftPOS?label=release)](https://github.com/CrazyDudo/SoftPOS/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-3DDC84?logo=android&logoColor=white)

**一个纯 Kotlin 的 EMV 非接触读卡内核：不只读卡，还验卡。**
每一步都标注规范章节，用模拟卡和运行时生成的临时 PKI 做单元测试，并且从设计上保证完整卡号
永远不会变成 `String`、不落盘、不进日志。

把卡贴到 Android 手机背面。SoftPOS 先做 Entry Point 预处理，选择应用，与芯片按 EMV 对话，
**校验卡片的证书链和签名（SDA 与 fast DDA）**，判断卡片要求哪种持卡人验证，最后交给你一条脱敏记录。

> **它不收款。** 没有收单机构、没有授权、没有密文、没有结算、没有任何网络调用。未按 PCI MPoC、
> PCI DSS 或 EMVCo 评估。请使用测试卡或你自己的卡。

## 为什么又造一个 NFC 读卡器？

- **大多数开源读卡器止步于「这是卡号」。** SoftPOS 接着问：这张卡是真的吗？用卡组织的 CA 公钥
  恢复发卡行证书，用发卡行公钥恢复 IC 卡证书，再校验卡片对本笔交易不可预知数的签名——严格按
  EMV 4.4 Book 2 和 Contactless Book C-3 逐步实现，章节号写在代码里。
- **每一个 EMV 判断都是纯 JVM 单元测试。** `emv-core` 不依赖 Android。读卡流程、证书格式和限额
  逻辑都对着模拟卡跑，其中证书部分对着测试运行时生成的 RSA PKI 验证，让代码接受规范的检验，
  而不是自己检验自己。
- **卡片数据被当回事处理。** 可擦除缓冲区、脱敏的 APDU trace、带密钥的指纹，以及让完整卡号
  在 vault 之后不可触达的类型设计。哪些东西擦不掉，设计说明里也写明了。

## 已实现

| 领域 | 内容 | 出处 |
|---|---|---|
| 入口点 | 读卡器的交易 / CVM / 底限三档限额驱动 TTQ；超限金额在碰卡前就被拒绝 | Contactless Book B §3.1 |
| 应用选择 | PPSE、候选列表、优先级排序、AID 列表回退、部分匹配选择 | Book 1 §12.3 |
| 处理 | 从终端配置填 PDOL、GPO Format 1/2、遍历 AFL、`6Cxx` / `61xx` 处理 | Book 3 §10.1-10.2 |
| **认证** | **SDA 与 fast DDA**：CA → 发卡行 → IC 卡的公钥恢复、SSAD / SDAD 验签、fDDA 的 CTQ 绑定、TVR 位 | Book 2 §5-6, Book C-3 |
| CVM 判定 | CVM List 求值（Kernel 2）、Card Transaction Qualifiers（Kernel 3）——只判定并报告，不执行 | Book 3 §10.5 |
| 数据卫生 | `CharArray` 存 PAN，`reveal` 交出真视图，`withBytes` 在 `finally` 清零，未知标签默认脱敏 | - |
| 存储 | Room、表驱动的交易状态机与审计轨迹、带公式中和的 CSV / JSON 导出 | - |
| 设备 | Keystore AES-256-GCM 与 HMAC-SHA256、StrongBox / TEE 识别、本地完整性信号 | - |

刻意不做：GENERATE AC（因此没有 CDA，也没有任何批准 / 拒绝）、终端风险管理、PIN 输入。原因写在
[`EmvReadFlow`](emv-core/src/main/kotlin/com/softpos/emv/flow/EmvReadFlow.kt) 的类文档里。

## 三十秒上手

```kotlin
val softPos = SoftPos.create(
    context,
    SoftPosConfig(
        // Optional. With CA public keys loaded, SDA and fast DDA are verified on every read.
        capkRegistry = myCapkRegistry,
        terminalProfile = TerminalProfile(
            readerLimits = ReaderLimits(cvmRequiredLimitMinor = 5_000, contactlessTransactionLimitMinor = 25_000),
        ),
    ),
)

softPos.cardReader.reads(activity) { cart.totalMinor }.collect { event ->
    if (event is CardReadEvent.Completed) {
        val card = event.captured.card   // RedactedCard: the raw data has already been wiped
        card.maskedPan                   // ************0010
        card.authentication              // DDA_AUTHENTICATED, SDA_AUTHENTICATED, NOT_PERFORMED, FAILED
        card.cvm                         // NO_CVM_REQUIRED, ONLINE_PIN_REQUIRED, ...
    }
}
```

只要 Flow 处于收集状态，读卡器模式就保持开启。项目不附带任何 CA 公钥——用 `CapkTextParser` 从
卡组织公告加载，`CapkRegistry.verify` 会先用公告里的校验和逐条核对。

## 构建

```bash
./gradlew :emv-core:test                                   # JDK 17，不需要 Android SDK
./gradlew :softpos-sdk:assembleRelease :demo:assembleDebug # 需要 Android SDK
```

`settings.gradle.kts` 只在找到 SDK 时才接入 Android 模块，因此 core 在一台裸机 runner 上也能构建。
CI 会刻意隐藏 SDK 来跑 core——上面那句话就是这样被验证的——然后跑 SDK 测试，再打包 APK 与 AAR。
推一个 `v*` 标签即发布 Release。

## 结构

```
┌────────────────────────── emv-core  (Kotlin/JVM，不依赖 Android) ──────────────────────┐
│  tlv        BER-TLV 编解码、标签注册表、DOL 构造                                          │
│  apdu       命令 / 响应编码、状态字                                                       │
│  terminal   TerminalProfile、ReaderLimits、Entry Point 预处理                            │
│  flow       EmvReadFlow：PPSE → SELECT → GPO → READ RECORD → ODA → CVM 判定              │
│  oda        RSA 恢复、发卡行 / IC 卡证书、SSAD / SDAD、CAPK 注册表                        │
│  cvm        CVM List、CTQ、CardholderVerification                                        │
│  model      Pan（CharArray）、Track2Data、RawCardData → RedactedCard                     │
│  txn        交易状态机                                                                    │
└────────────────────────────────────────────────────────────────────────────────────────┘
                                          ▲ ApduTransceiver
┌────────────────────────── softpos-sdk  (Android library) ──────────────────────────────┐
│  IsoDep 传输 · 读卡器模式绑定 · CardVault · Keystore 加密 · DeviceIntegrity              │
│  Room 持久化 · TransactionRepository · CSV / JSON 导出 · SoftPos 门面                    │
└────────────────────────────────────────────────────────────────────────────────────────┘
                                          ▲
┌────────────────────────── demo  (Compose) ─────────────────────────────────────────────┐
│  Shop → Tap → History                                                                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

## 卡片数据如何处理

- **PAN 存在 `CharArray` 里**，`close()` 才能真正覆写。`reveal { }` 交出的是该缓冲区的视图而非
  副本；`withBytes { }` 给加密调用方一个在 `finally` 里清零的 `ByteArray`。tag `5A` 和 tag `57`
  都解码进可擦除缓冲区，从不经过 `String`。
- **`CardVault.ingest()` 消费原始数据**，是获得 `CapturedCard` 的唯一途径。过了这条线，任何代码都
  够不到完整卡号。UI 和数据库看到的只有 `RedactedCard`。
- **APDU trace 默认脱敏**，未知的 primitive 标签在被证明无害之前一律视为敏感。解析不了的响应
  整条扣留。
- **带密钥的指纹**（Keystore HMAC-SHA256，截断）用来归组同一张卡的多次到访。它不可逆，且密钥
  从不离开 Keystore，因此无法在设备之外重算。`persistEncryptedPan` 默认关闭，也应该保持关闭。
- **卡片提供的文本在解码时剥掉控制字符**，以 `=`、`+`、`-`、`@` 开头的 CSV 字段在进入表格软件
  之前被中和。
- **擦不掉的东西写在明处**：`applicationLabel` 和 `cardholderName` 是 `String`，会活到 GC 为止。
  这个局限标注在类上，不藏。

## 交易

```
CREATED ─SUBMIT─▶ PENDING ─BEGIN_PROCESSING─▶ PROCESSING ─COMPLETE─▶ PROCESSED
   │                 │                            └─FAIL─▶ FAILED ─SCHEDULE_RETRY─▶ RETRY_SCHEDULED ─┐
   │                 │                                       └─ABANDON─▶ ABANDONED                    │
   └─────────────────┴──────────── CANCEL ────────────▶ CANCELLED           (BEGIN_PROCESSING) ◀─────┘
```

`TransactionStateMachine` 是一张纯粹的表。`TransactionRepository.applyEvent()` 是 `state` 列的唯一
写入者，每次被接受的状态转移都追加一条审计记录；非法转移以 `Rejected` 返回，而不是写坏数据行。
离线场景下，「处理」意味着扣减库存并打印页脚为 `OFFLINE PROTOTYPE - NOT A PAYMENT RECEIPT` 的小票。

## 演示应用

三个页签：**Shop**（商品目录与购物车）、**Tap**（读卡器模式、脱敏结果、认证与 CVM 结论、可展开的
脱敏 APDU trace）、**History**（状态标签、审计轨迹、重试 / 放弃 / 取消、CSV 与 JSON 导出）。库存
刻意允许超量下单——正是它在没有硬件故障的情况下跑通了重试与放弃路径。

## 已知缺口

- CDA 需要 GENERATE AC，因此只提供 CDA 的卡会被报告为未认证。
- Kernel 2 与 Kernel 3 在 GPO 之后分歧很大；这里只有共有前缀加 fDDA 与 CTQ。Kernel 2 的
  `DF8117`、`9F1D` 填的是占位值。
- API 31 以下平台无法区分 StrongBox 与 TEE，`keySecurityLevel()` 会如实说明。
- `DeviceIntegrity` 报告的是本地信号。它不是 attestation，被攻破的设备可以撒谎。
- 只针对模拟卡与测试 PKI 验证过。本项目尚未接触过任何物理硬件。

## 路线图

对着 Mastercard 测试卡实现 Kernel 2 特有部分 · Amex、JCB、Discover、UnionPay 条目的实卡验证 ·
demo 里按卡组织公告格式加载 CAPK · 终端风险管理（Book 3 §10.6）· 完整性报告接入 Play Integrity。

如果它替你省下了一周读 EMV Book 3 的时间，点个 star 能让下一个人更快找到它。
欢迎 issue 和 pull request——测试会很快告诉你改动站不站得住。

## 许可

[Apache License 2.0](LICENSE)。

EMV® 是 EMVCo, LLC 的注册商标。Visa、Mastercard、American Express、JCB、Discover 和 UnionPay 均为
各自所有者的商标。本项目与 EMVCo 及任何卡组织均无隶属关系，未获其认可或认证；文中出现这些标识仅
用于指明代码所引用的规范与应用标识符。
