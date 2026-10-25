# People

People is a small Android and iOS user directory built for the Sliide KMP UX Innovator Challenge. Its screen, form, state, validation, and time labels are shared Kotlin and Compose Multiplatform code. The app reads GoRest's public users and supports authenticated creation and deletion.

## Features

- Fetches the **actual last page** of GoRest `/public/v2/users`: it reads `X-Pagination-Pages` from page 1, requests that page, and retries once if the page count changes.
- Shows name, email, initials, and a relative time label; keeps cached users visible during refresh and when offline.
- Uses an uncached loading shimmer, empty state, contextual errors, Retry, and pull to refresh.
- Opens a full-screen Add user form on phones and a centered dialog at wider widths. Shared name, email, and required gender validation runs as fields are edited or left. A confirmed HTTP 201 saves the user locally and places the row at the top.
- Opens a confirmation dialog on a row's long press. Each accepted Delete is saved in Room and hides its row. Each user has an independent five-second deadline; only the latest accepted Delete offers Undo. After its deadline, the app sends DELETE. HTTP 204 or a DELETE 404 confirming the user is already absent removes the row from the local cache. Permanent failure restores the row and shows an explanation; an unconfirmed terminal outcome is reported separately.
- Uses one list column below 600 dp and two columns at wider content widths, with shared light and dark themes.

## Platforms and stack

`androidApp` is the Android host (minimum API 26). `iosApp` is the SwiftUI host for the shared Compose view (minimum iOS 16; configured device and simulator targets are arm64). All product UI and logic live in `shared/src/commonMain`; platform source sets provide the HTTP engine and Room database builder.

Versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml):

| Component | Version |
| --- | --- |
| Kotlin and Compose compiler plugin | 2.4.20 |
| Compose Multiplatform | 1.12.1 |
| Compose Material 3 | 1.12.0-alpha03 |
| Android Gradle Plugin / Gradle wrapper | 9.1.1 / 9.5.1 |
| Ktor (OkHttp on Android, Darwin on iOS) | 3.5.2 |
| Room / bundled SQLite | 2.8.5 / 2.6.2 |
| Koin | 4.2.2 |
| kotlinx.serialization / coroutines | 1.11.0 / 1.11.0 |
| KSP | 2.3.12 |

## Architecture

The `shared` module separates `data`, `domain`, `presentation`, `designsystem`, and `di`. Ktor checks response status and maps transport and HTTP errors. The repository fetches a last-page snapshot and updates Room transactionally, preserving locally created users and deduplicating by server ID. Room's `Flow` is the reactive source of visible rows. A shared `UsersViewModel` exposes immutable UI state through `StateFlow`; Compose renders that state. Koin wires the repository, client, database, Delete processor, and ViewModel.

Room also stores one current Delete transaction per user. Acceptance, latest-only Undo eligibility, request claims, retries, and terminal results are persisted. The app-owned processor handles independent deadlines and up to four concurrent requests. It resumes pending work when the app enters the foreground; after a lost or uncertain response, it checks the user directly before retrying. Transient failures use bounded backoff. A confirmed DELETE 204 or 404 and cache removal are one Room transaction. The live list masks pending and terminal tombstoned rows across refreshes. Delete failure snackbars are live events, so an old ledger error is not shown again on restart. Recovery runs while the app process can run; it is not a scheduled background service.

GoRest does not provide a reliable creation timestamp in the consumed user payload. Remote rows display **“Seen … ago”** from persisted local `firstSeenAt`; users created in this app display **“Added … ago”** from local `addedAt`. Shared Kotlin formats both labels. They do not claim to be server creation times.

```text
androidApp/                 Android entry point
iosApp/                     iOS entry point and Xcode project
shared/src/commonMain/      Shared data, domain, presentation, design system, DI
shared/src/androidMain/     Android HTTP engine and Room builder
shared/src/iosMain/         iOS HTTP engine and Room builder
shared/src/commonTest/      Shared logic tests
docs/                       Design and implementation notes
```

## Setup and run

Use a macOS development environment with Android Studio, Android SDK 37, a JDK 21 toolchain, and Xcode for iOS. Open the project root in Android Studio and let Gradle sync. The Gradle wrapper and Xcode project are included; dependencies require network access on a fresh clone.

- **Android:** select the `androidApp` run configuration in Android Studio, or build with `./gradlew :androidApp:assembleDebug`, then launch on an API 26 or newer device/emulator.
- **iOS:** open `iosApp/iosApp.xcodeproj` in Xcode, select an arm64 iOS simulator or device, configure signing for your environment, and run the `iosApp` scheme. Xcode invokes the shared framework's Gradle integration.

### Optional GoRest token

Public reads work without a token. To enable writes, copy [`gorest.local.properties.example`](gorest.local.properties.example) to `gorest.local.properties` in the project root and set `GOREST_TOKEN` in that local file. Rebuild the app after changing it. The shared build task reads that optional local file (and `local.properties`) and generates `GoRestConfig.kt` under `shared/build/`. The `.gitignore` has entries for both local files and build outputs. Without a token, the Add form remains inspectable and validates input, while write requests show a setup explanation.

Use a disposable token for a demo. A token embedded in a mobile build can be extracted; this approach is **not production-grade secret storage**. Never commit local config, generated sources, build artifacts, or token-bearing logs.

### Unit tests

Run the shared Android host suite with `./gradlew :shared:testAndroidHostTest`. The iOS test task is `./gradlew :shared:iosSimulatorArm64Test` on a configured Mac. The latest reported `:shared:testAndroidHostTest` run executed **177 tests: 177 passed, 0 failed, 0 skipped**. The latest native iOS unit tests have not been verified.

The developer reports completed Android and iOS manual QA, including Delete/Undo on the durable multi-delete implementation, and successful builds on both platforms. The documentation and cleanup changes in this pass have not been manually retested on devices. No fresh public-clone build, latest native iOS unit test result, or complete two-platform accessibility and layout check has been recorded here.

## UX and accessibility

The interface uses a single directory screen, a full-screen phone form or wide dialog for Add, a delete confirmation, clear inline errors, readable spacing, labelled controls, and a screen-reader label for each row. It follows the system light/dark setting and uses keyboard and safe-area padding. Final TalkBack, VoiceOver, large-text, and wide-layout checks remain part of the manual release checklist.

## Limitations and tradeoffs

- **Undo ordering:** the challenge describes HTTP 204 before Undo. Here DELETE is delayed for five seconds so Undo can prevent server deletion; GoRest has no restore endpoint. The intent and deadline survive process death in Room. A later foreground launch resumes expired work. This is a deliberate deviation from the challenge's literal 204-first order.
- **Uncertain Delete outcomes:** a DELETE 404 is treated as confirmed absence and removes the cached row. Network failures and server errors are retried after checking server state. A 404 or access restriction during verification of an uncertain request may still leave the outcome unconfirmed; the row remains hidden locally and the app explains the uncertainty. It does not claim a 204 in that case.
- **Permissions:** a GoRest token may not be allowed to delete public seed users. Verify deletion with a user created using the same token. Unauthorized and forbidden responses are shown as errors.
- **Offline writes:** cached reads remain available offline. Add needs a connection. Accepted Delete intents remain in the Room ledger for foreground retry/recovery; there is no general offline write queue or offline Add queue.
- **Time labels:** `Seen` and `Added` use local timestamps. They update when UI state changes, without a periodic clock ticker.
- **Target coverage:** the configured iOS targets are arm64 device and Apple Silicon simulator; Intel simulator support is not configured. Material 3 is pinned to an alpha release.

## AI-assisted development

The developer set the product scope, architecture, UX tradeoffs, and acceptance criteria. Codex assisted with implementation and documentation; Gemini assisted with review and unit testing. The developer reported the 177-test Android host result above, successful Android and iOS builds, and completed manual QA on both platforms. The project is published in a public GitHub repository. The latest native iOS unit tests, a fresh-clone build, and full accessibility checks remain unverified. No unrecorded device, CI, or coverage result is claimed here.
