# SoftPOS

**English** · [简体中文](README.zh-CN.md)

[![CI](https://github.com/CrazyDudo/SoftPOS/actions/workflows/ci.yml/badge.svg)](https://github.com/CrazyDudo/SoftPOS/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/CrazyDudo/SoftPOS?label=release)](https://github.com/CrazyDudo/SoftPOS/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-3DDC84?logo=android&logoColor=white)

**An EMV contactless reader core in pure Kotlin that verifies the card, not just reads it.**
Spec-cited, unit-tested against a simulated card and a throwaway PKI, and built so the full card
number never becomes a `String`, never reaches disk, and never appears in a log.

Tap a card on an Android phone. SoftPOS runs Entry Point pre-processing, selects the application,
talks EMV to the chip, **checks the card's certificate chain and signature (SDA and fast DDA)**,
works out which cardholder verification the card is asking for, and hands you a masked record.

> **It takes no payments.** No acquirer, no authorisation, no cryptogram, no settlement, no network
> call of any kind. It is not assessed against PCI MPoC, PCI DSS or EMVCo. Use test cards or your own.

## Why another NFC card reader?

- **Most open-source readers stop at "here is the PAN".** SoftPOS goes on to ask whether the card is
  genuine: the issuer certificate is recovered with the scheme's CA key, the ICC certificate with the
  issuer key, and the card's signature over this transaction's Unpredictable Number is checked -
  EMV 4.4 Book 2 and Contactless Book C-3, step by step, with the section numbers in the code.
- **Every EMV decision is a plain JVM unit test.** `emv-core` has no Android dependency. The read
  flow, the certificate formats and the limit logic run against a simulated card, including an RSA
  PKI generated at test time so the certificate code is checked against the specification rather
  than against itself.
- **Card data is handled like it matters.** Wipeable buffers, a redacted APDU trace, keyed
  fingerprints, and types that make the full number unreachable past the vault. The design notes
  say what cannot be wiped, too.

## What is implemented

| Area | What | Reference |
|---|---|---|
| Entry point | Reader transaction / CVM / floor limits set the TTQ; an over-limit amount is refused before the card is polled | Contactless Book B §3.1 |
| Selection | PPSE, candidate list, priority order, AID-list fallback, partial selection | Book 1 §12.3 |
| Processing | PDOL from a terminal profile, GPO Format 1 and 2, AFL walk, `6Cxx` / `61xx` handling | Book 3 §10.1-10.2 |
| **Authentication** | **SDA and fast DDA**: CA → issuer → ICC key recovery, SSAD / SDAD verification, fDDA CTQ binding, TVR bits | Book 2 §5-6, Book C-3 |
| CVM decision | CVM List evaluation (Kernel 2), Card Transaction Qualifiers (Kernel 3) - decided and reported, never performed | Book 3 §10.5 |
| Data hygiene | `CharArray` PAN with a real view on `reveal`, `withBytes` zeroed in `finally`, unknown tags redacted by default | - |
| Storage | Room, a table-driven transaction state machine with an audit trail, CSV / JSON export with formula neutralisation | - |
| Device | Keystore AES-256-GCM and HMAC-SHA256, StrongBox / TEE detection, local integrity signals | - |

Left out on purpose: GENERATE AC (and therefore CDA and any approve / decline), terminal risk
management, PIN entry. The class documentation on
[`EmvReadFlow`](emv-core/src/main/kotlin/com/softpos/emv/flow/EmvReadFlow.kt) says why.

## Thirty seconds

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

Reader mode stays on for as long as the flow is collected. No CA keys ship with the project - load
them from your scheme's bulletin with `CapkTextParser`, and `CapkRegistry.verify` checks each one
against its published checksum first.

## Build

```bash
./gradlew :emv-core:test                                   # JDK 17, no Android SDK needed
./gradlew :softpos-sdk:assembleRelease :demo:assembleDebug # needs an Android SDK
```

`settings.gradle.kts` wires the Android modules in only when it finds an SDK, so the core builds
on a bare runner. CI runs the core with the SDK deliberately hidden - that is what keeps the claim
above honest - then the SDK tests, then packages the APK and AAR. A `v*` tag publishes a release.

## How it fits together

```
┌────────────────────────── emv-core  (Kotlin/JVM, no Android) ──────────────────────────┐
│  tlv        BER-TLV codec, tag registry, DOL builder                                   │
│  apdu       command / response coding, status words                                    │
│  terminal   TerminalProfile, ReaderLimits, Entry Point pre-processing                  │
│  flow       EmvReadFlow: PPSE → SELECT → GPO → READ RECORD → ODA → CVM decision        │
│  oda        RSA recovery, issuer / ICC certificates, SSAD / SDAD, CAPK registry        │
│  cvm        CVM List, CTQ, CardholderVerification                                      │
│  model      Pan (CharArray), Track2Data, RawCardData → RedactedCard                    │
│  txn        transaction state machine                                                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
                                          ▲ ApduTransceiver
┌────────────────────────── softpos-sdk  (Android library) ──────────────────────────────┐
│  IsoDep transport · reader-mode binding · CardVault · Keystore crypto · DeviceIntegrity │
│  Room persistence · TransactionRepository · CSV / JSON export · SoftPos facade          │
└────────────────────────────────────────────────────────────────────────────────────────┘
                                          ▲
┌────────────────────────── demo  (Compose) ─────────────────────────────────────────────┐
│  Shop → Tap → History                                                                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

## How card data is handled

- **The PAN lives in a `CharArray`** so `close()` can overwrite it. `reveal { }` hands out a view
  over that buffer, not a copy; `withBytes { }` gives cryptographic consumers a `ByteArray` zeroed in
  a `finally`. Tag `5A` and tag `57` are decoded into wipeable buffers, never through a `String`.
- **`CardVault.ingest()` consumes the raw data** and is the only way to get a `CapturedCard`. Past
  that line nothing can reach the full number. `RedactedCard` is what the UI and the database see.
- **APDU traces are redacted by default**, and an unknown primitive tag is treated as sensitive
  until proven otherwise. A response the parser cannot read is withheld entirely.
- **A keyed fingerprint** (Keystore HMAC-SHA256, truncated) groups repeat visits by the same card.
  It cannot be reversed, and because the key never leaves the Keystore it cannot be recomputed
  off-device. `persistEncryptedPan` defaults to off and should stay off.
- **Card-supplied text is stripped of control characters** on decode, and a CSV field beginning
  `=`, `+`, `-` or `@` is neutralised before it can reach a spreadsheet.
- **What cannot be wiped is written down**: `applicationLabel` and `cardholderName` are `String`s
  and live until garbage collection. The limitation is on the class, not hidden.

## Transactions

```
CREATED ─SUBMIT─▶ PENDING ─BEGIN_PROCESSING─▶ PROCESSING ─COMPLETE─▶ PROCESSED
   │                 │                            └─FAIL─▶ FAILED ─SCHEDULE_RETRY─▶ RETRY_SCHEDULED ─┐
   │                 │                                       └─ABANDON─▶ ABANDONED                    │
   └─────────────────┴──────────── CANCEL ────────────▶ CANCELLED           (BEGIN_PROCESSING) ◀─────┘
```

`TransactionStateMachine` is a pure table. `TransactionRepository.applyEvent()` is the only writer
of the `state` column and appends an audit row on every accepted transition; an illegal one comes
back as `Rejected` instead of corrupting the row. Offline, "processing" means reserving stock and
printing a receipt footed `OFFLINE PROTOTYPE - NOT A PAYMENT RECEIPT`.

## Demo app

Three tabs: **Shop** (catalog and basket), **Tap** (reader mode, masked result, authentication and
CVM outcome, expandable redacted APDU trace), **History** (state chips, audit trail, retry /
abandon / cancel, CSV and JSON export). Stock can be over-ordered on purpose - that is what
exercises the retry and abandon path without a hardware failure.

## Known gaps

- CDA needs GENERATE AC, so a card offering only CDA is reported as not authenticated.
- Kernel 2 and Kernel 3 diverge after GPO; only the shared prefix plus fDDA and the CTQ are here.
  Kernel 2 elements `DF8117` and `9F1D` carry placeholder values.
- Below API 31 the platform cannot tell StrongBox from the TEE, so `keySecurityLevel()` says so.
- `DeviceIntegrity` reports local signals. It is not attestation and a compromised device can lie.
- Verified against a simulated card and a test PKI. Nothing here has met physical hardware.

## Roadmap

Kernel 2 specifics against a Mastercard test card · verified entries for Amex, JCB, Discover and
UnionPay · CAPK loading from the scheme bulletin format in the demo · terminal risk management
(Book 3 §10.6) · a Play Integrity hook for the integrity report.

If this saved you a week of reading EMV Book 3, a star helps the next person find it.
Issues and pull requests are welcome - the tests will tell you fast whether a change holds.

## Licence

[Apache License 2.0](LICENSE).

EMV® is a registered trademark of EMVCo, LLC. Visa, Mastercard, American Express, JCB, Discover and
UnionPay are trademarks of their respective owners. This project is not affiliated with, endorsed
by, or certified by EMVCo or any card scheme; the marks appear only to identify the specifications
and application identifiers the code refers to.
