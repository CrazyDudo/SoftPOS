# SoftPOS — offline contactless prototype

**English** · [简体中文](README.zh-CN.md)

An Android SDK and demo app that read a contactless payment card over NFC, reduce the card data
immediately, and record a basket locally.

## What this is not

**It takes no payments.** There is no acquirer, no authorisation, no clearing, no settlement, and no
network call of any kind. The card read stops after READ RECORD: no cryptogram is generated, no card
is authenticated, and no cardholder is verified.

It is not built or assessed against PCI MPoC, PCI DSS or EMVCo certification, and nothing in it
should be read as evidence that a card is genuine or that a transaction occurred. Use test cards or
your own card.

The specific EMV functions left out — offline data authentication (SDA/DDA/CDA), CVM processing,
terminal risk management, GENERATE AC — are listed with references in the class documentation on
[`EmvReadFlow`](emv-core/src/main/kotlin/com/softpos/emv/flow/EmvReadFlow.kt).

## Modules

| Module | Type | Contents |
|---|---|---|
| `emv-core` | Kotlin/JVM, **no Android** | BER-TLV codec, EMV tag registry, AID registry, APDU coding, DOL builder, the full card-read flow, the transaction state machine |
| `softpos-sdk` | Android library | `IsoDep` transport, reader-mode binding, Keystore crypto, Room persistence, peripherals, export, the `SoftPos` facade |
| `demo` | Android app | Compose MVVM: catalog → basket → tap → history |

`emv-core` has no Android dependency on purpose. Every interesting decision — how a tag is decoded,
which candidate wins, what the PDOL response contains, when a transaction may move state — lives
there and is covered by plain JVM unit tests. `softpos-sdk` supplies the `IsoDep`-backed
`ApduTransceiver` and the storage; it contains no EMV parsing.

## Build and test

```bash
# Unit tests. Runs anywhere with a JDK 17 — no Android SDK needed.
./gradlew :emv-core:test

# One test class, or one test.
./gradlew :emv-core:test --tests '*BerTlvParserTest'
./gradlew :emv-core:test --tests '*EmvReadFlowTest.reads a visa card end to end'

# The Android modules. Requires an Android SDK.
./gradlew :softpos-sdk:assembleDebug :demo:assembleDebug
./gradlew :demo:installDebug
```

**JDK 17 is required.** The Android Gradle Plugin refuses to run on anything older, and because
`settings.gradle.kts` wires in the Android modules whenever it can find an SDK, an older JDK fails
the whole build — `:emv-core:test` included, even though the core itself needs no Android anything.

`settings.gradle.kts` only includes `:softpos-sdk` and `:demo` when it can find an Android SDK
(via `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`). Without one it
configures `:emv-core` alone and says so, which keeps the core testable on a bare CI runner. Force
the Android modules on with `-PforceAndroidModules=true`.

The Gradle wrapper (8.11.1) is checked in, so a fresh clone needs only a JDK 17. The Android SDK
path comes from `local.properties`, which is not checked in — Android Studio writes it on first
open, or set `ANDROID_HOME`.

## The read flow

`EmvReadFlow` implements EMV 4.4 Book 1 §12.3 (application selection) and Book 3 §10.1–10.2, with
the contactless entry-point behaviour of EMV Contactless Book B §3.3:

1. **SELECT PPSE** — `2PAY.SYS.DDF01`. Parse `6F → A5 → BF0C → 61*` for AIDs, labels and priorities.
   If the card has no PPSE, fall back to selecting each registered AID in turn.
2. **Build the candidate list** — intersect with the terminal AID registry (Visa and Mastercard
   families only), sort by Application Priority Indicator.
3. **SELECT the application** — read the FCI, extract the PDOL.
4. **GET PROCESSING OPTIONS** — fill the PDOL from `TerminalProfile`, parse AIP and AFL from either
   a Format 1 (`80`) or Format 2 (`77`) response. A `6985` drops that candidate and tries the next.
5. **READ RECORD** — walk the AFL, accumulate everything into a `TlvDatabase`.
6. **Extract** — PAN from `5A` or from Track 2, expiry from `5F24` or Track 2, plus scheme, label
   and a Luhn check.

Every step is exercised against a simulated card in
[`EmvReadFlowTest`](emv-core/src/test/kotlin/com/softpos/emv/flow/EmvReadFlowTest.kt), including the
PPSE-absent fallback, candidate fall-through on `6985`, a blocked application, missing records, the
Track 2 fallback and transport loss.

## How card data is handled

The brief draws a line between raw data used transiently in memory and reduced data written to
disk. That line is enforced by types, not by convention:

- **`Pan` holds digits in a `CharArray`**, not a `String`, so `close()` can actually overwrite them.
  Access to the full number is scoped through `reveal { }`. `toString()` never renders digits.
- **`RawCardData` is `AutoCloseable`** and wipes the PAN, the track data and every sensitive TLV
  value. Its `applicationLabel` and `cardholderName` are `String`s and cannot be wiped — that
  limitation is documented on the class rather than papered over.
- **`CardVault.ingest()` consumes the `RawCardData` it is given** and is the only way to obtain a
  `CapturedCard`. Nothing downstream of it can reach the full number.
- **`RedactedCard` is the only card type the UI and the database see.** No cardholder name, no track
  data, no full PAN — a boolean records that a name was present.
- **APDU traces are redacted by default.** A raw contactless trace contains tag `57`, which is a full
  PAN. `ApduTrace` re-parses each response and withholds every value the tag registry marks
  sensitive; a response it cannot parse is withheld entirely.
- **Unknown primitive tags default to sensitive.** Over-redacting an unfamiliar proprietary element
  is cheaper than leaking one.
- **A card fingerprint** (Keystore HMAC-SHA256 over the PAN, truncated) groups repeat visits by the
  same card. It is one-way, and because the key never leaves the Keystore it cannot be recomputed
  off-device from a candidate PAN.
- **`persistEncryptedPan` defaults to off.** Turning it on stores an AES-256-GCM blob under a
  Keystore key — recoverable data, and a different risk posture. The default implements the brief's
  rule: keep the last four digits and nothing else.
- **`SoftPos.wipeAllCardData()`** deletes the Keystore keys, which makes every stored fingerprint
  and blob permanently unreadable.

## Transaction state machine

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

`TransactionStateMachine` is a pure table-driven function. `TransactionRepository.applyEvent()` is
the only writer of the `state` column: it consults the machine, and on success updates the row and
appends an audit record to `transaction_events`. An illegal transition comes back as
`TransitionResult.Rejected` rather than corrupting the row.

`PROCESSING` is deliberately not cancellable — a half-finished write reaches `FAILED` first so the
reason is recorded.

Offline, "processing" means reserving stock and producing a receipt. There is nothing to re-send, so
a retry re-runs local finalisation.

## Using the SDK

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

Reader mode is active for as long as the flow is collected and is disabled when collection stops.

## Demo app

Three tabs: **Shop** (catalog and basket), **Tap** (reader mode, masked card result, expandable APDU
trace), **History** (state chips, per-transaction audit trail, retry/abandon/cancel, CSV and JSON
export via the share sheet).

Stock is intentionally allowed to be over-ordered from the catalog screen — that is what exercises
the `FAILED → RETRY_SCHEDULED → ABANDONED` path without needing a hardware failure.

Every screen carries a banner stating that no payment is taken, and printed receipts are footed
`OFFLINE PROTOTYPE - NOT A PAYMENT RECEIPT`.

## Known gaps

- No offline data authentication. Certificate elements (`8F`, `90`, `9F46`, `93`, `9F4B`) are parsed
  into the TLV database and never verified. A CA public key table (`CapkRegistry`) exists and
  validates entries against their published checksums, but nothing reads it — populating it changes
  no behaviour.
- No cardholder verification. The CVM list is read, not evaluated.
- Multi-application conflict resolution is priority ordering only. EMV Book 1 §12.4 requires
  cardholder confirmation when the priority indicator sets b8; `CardCandidate` exposes the flag but
  the flow does not prompt.
- Kernel 2 and Kernel 3 diverge sharply after GPO. Only the shared prefix is implemented, and the
  Kernel 2 specific elements (`DF8117`, `9F1D`) carry placeholder values.
- Peripherals are interfaces plus a logcat printer. Bluetooth SPP, USB and scanning are marked TODO
  rather than guessed at.
- The terminal profile defaults (`9F33`, `9F40`, `9F1A`, `5F2A`, `9F09`) describe this prototype.
  They are not an acquirer-issued profile.
- `emv-core` is verified against a simulated card. Nothing here has been run against physical
  hardware.

## Licence

Licensed under the [Apache License 2.0](LICENSE).

EMV® is a registered trademark of EMVCo, LLC. Visa, Mastercard, American Express, JCB, Discover and
UnionPay are trademarks of their respective owners. This project is not affiliated with, endorsed
by, or certified by EMVCo or any card scheme; the marks appear only to identify the specifications
and application identifiers that the code refers to.
