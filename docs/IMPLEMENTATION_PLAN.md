# IMPLEMENTATION_PLAN.md — People / Sliide KMP UX Innovator

Status: **Durable multi-delete is active in the Android and iOS application.** The user reports successful Android and iOS builds and completed manual QA, including Delete/Undo. The latest reported `:shared:testAndroidHostTest` run executed **177 tests: 177 passed, 0 failed, 0 skipped**. The latest native iOS unit tests have not been verified.

## 1. Task traceability

Source of requirements: original two-page PDF **"The Sliide KMP 'UX Innovator' Challenge"**. Keep distinctions between *requested*, *approved implementation decisions*, and *optional polish*.

| Source requirement | Implementation | Verification |
|---|---|---|
| KMP Android & iOS; shared Compose UI | Single `shared` KMP module with native entry points (`androidApp`, `iosApp`) | Manual user build and smoke-test on both targets |
| GoRest `/users` **last page** | Read pagination metadata, then fetch actual last page; not a hardcoded page number | Manual user execution of mock header/last-page tests; live API check |
| Show Name, Email, Relative Timestamp from shared logic | One shared list row/formatter with persisted first-seen/created timestamp | Manual user execution of shared timestamp tests; no invented server creation date |
| Shimmer + graceful No Internet errors | Cache-first UI state machine; shimmer only when no cache | Manual user execution of loading/cache/error/empty state tests |
| FAB, polished Add User form, real-time Name/Email validation | Shared full-screen phone form, wide dialog, and validators; Gender required by API | Manual user execution of Android/iOS form smoke tests and shared validator tests |
| 201 -> new user immediately at top | Insert into Room and emit reactive list; animated insertion | Manual user execution of Repository/ViewModel 201 test |
| Long press + confirmation; 204 and animated delete + Undo | Deferred HTTP DELETE after 5-second Undo window | Manual user execution of Undo and 204 tests; explicit deviation/risk documented |
| Portrait single, landscape/tablet two-column | Width-based 600dp breakpoint | Manual user phone/expanded UI smoke checks |
| Clean architecture; Ktor; Room KMP; Koin | Data/domain/presentation separation; DI and local cache | Code review, manual user build and repository test runs |
| Shared unit tests | Validation, ViewModel, time, repository, create/delete | Latest Android host run: 177 executed, 177 passed, 0 failed, 0 skipped; latest native iOS unit tests unverified |
| UX polish, AI orchestration, KMP mastery, speed/quality | Shared Material 3 theme, motion and documentation, deliberate small increments | Manual user UX checklist, review diffs |
| Public GitHub + README | Publish repo; write README after verified implementation | README updated; public repository publication remains pending |

## 2. Frozen scope and architectural decisions

- **Product:** "People" — one Users screen, Add User form, Delete dialog/Snackbar. No User Details, Settings, profile editing, search, filters, bottom nav or onboarding.
- **UI:** Compose Multiplatform in `commonMain` of `shared`, Material 3, system light/dark, static shared neutral/blue tokens, no font assets. Clean list, ~76dp min rows, 44dp initials avatars. Width `<600dp` one column; `>=600dp` two-column clean grid. Phone Add User form is full-screen; wide/tablet form is a centered dialog. See `DESIGN_SYSTEM.md`.
- **Architecture:** `shared` as single KMP Gradle module with packages `data`, `domain`, `presentation`, `designsystem`, `di` inside `commonMain`; host applications are `androidApp` (Android) and `iosApp` (iOS). Clean Architecture boundaries, MVVM; one shared `UsersViewModel`, immutable `UsersUiState`, `StateFlow`.
- **Library choices:** Ktor (Android OkHttp, iOS Darwin), kotlinx.serialization, Room KMP + BundledSQLiteDriver, Koin, Kotlin coroutines/Flow, kotlin.time. Platform-specific pieces should be the minimum required. Min Android **API 26**, iOS **16.0**.
- **Data:** Room is the reactive local source of truth. Remote last-page snapshot replaces older remote-snapshot rows transactionally; locally created rows stay pinned to the top and deduplicate by server ID. Each refresh also verifies whether tracked locally created users still exist server-side; only conclusive 'not found' removes those rows. Network failures retain cache. Guard against a pending deletion reappearing during refresh.
- **Refresh:** On app launch and user-initiated pull-to-refresh only. No interval polling, foreground-triggered refresh or connectivity observer.
- **Offline:** Cached **read-only** users available. Add needs network at operation time. The approved durable Delete ledger may hold accepted intents for later retry/recovery; it must not report an HTTP 204 before receiving one. No general write queue or offline Add queue.
- **Token:** Optional for public read mode, configured in one **Git-ignored local build config** consumed by both targets; no Settings/login. Without a token Add form and validation still work but Create blocks with explanation. No secrets in repository, logs or README. A token in a client binary is inherently extractable; never advertise this demo technique as robust production secret storage.
- **Add fields:** Name, Email, Gender (`male`/`female`) segmented selection; `status=active` automatically. Inline validation after interaction, user-friendly mapping of HTTP 422, 201 success inserts at list top and closes modal.
- **Delete:** The active durable multi-delete flow persists one current transaction per user, allows independent five-second deadlines, revokes the previous Undo only when a new acceptance commits, and keeps terminal tombstones to mask stale snapshots. Its processor handles foreground recovery, retry, and network reconciliation.
- **Tests:** Shared unit tests plus manual Android emulator/iOS simulator smoke tests. Automated E2E/UI framework is not required. **Execution rule:** No agent may run shell commands, Gradle, compilation, builds, tests, Xcode, simulators, or emulators without separate explicit user authorization for that exact execution.

## 3. API assumptions to validate (blocking checks, not new features)

1. **Base URL and responses:** Use GoRest v2 `/public/v2/users`, list returns an array, and pagination headers include `X-Pagination-Pages` (and optionally current page/total). Verify real response including how `page` and authentication affect the result. Fetch page 1 to learn total pages, then request that last page. If pages change mid-fetch, safely retry/reload; don't silently choose a guessed last page.
2. **Create contract:** Request `name,email,gender,status`; ensure `201` includes server user ID. Verify missing/duplicate inputs produce field-aware `422` errors.
3. **Delete access:** Provisional decision: allow attempted DELETE with configured token and gracefully handle unauthorized/forbidden (401/403/404 depending on API). Seed/public users may not belong to the token. Test Delete with a **newly created test user**, not only seeded users. If deleting others consistently fails due to ownership, reconsider affordance during implementation rather than misrepresenting success.
4. **Timestamp:** No confirmed `created_at` in the standard user response. **Provisional**: `firstSeenAt` persisted for remote data (`Seen … ago`); `addedAt` persisted when successfully created (`Added … ago`). Both formatted in shared code; refresh must preserve firstSeenAt. Re-evaluate after inspecting live payload.
5. **Token-free public mode:** Confirm GET without a token works; if server behavior differs, reflect actual restrictions.
6. **Created-user reconciliation:** API may have token-scoped data and/or daily reseeding. Verify GET-by-id ownership and a conclusive `404`; avoid deleting local data on transient failure, 401 or 403.
7. **Critical challenge mismatch:** PDF literally asks for `204` -> disappearance + Undo restoring **local** state. Chosen behavior is delayed remote deletion so Undo truly prevents server deletion. This improves consistency but **does not follow the literal 204-first ordering**. Explicitly describe and justify this tradeoff in final README; reconsider if a literal evaluator requires 204-first behavior.

## 4. Compatibility preflight (before feature development)

- Create a version matrix for **Kotlin, Compose Multiplatform, Compose compiler plugin, AGP, Gradle, KSP, Room, SQLite driver, Ktor, Koin and Android/iOS SDK/Xcode**. Pick compatible **stable** releases and pin them; don't blindly copy latest snippets from mismatched docs.
- Official references for implementer: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html ; https://developer.android.com/kotlin/multiplatform/room ; https://ktor.io/docs/client-engines.html ; https://insert-koin.io/docs/quickstart/cmp/ ; https://gorest.co.in/docs/javascript .
- Confirm Android API 26 and iOS 16 support for the **combined** dependency set. Public documentation supports the chosen technologies individually; the real combined build is verified manually by the user.
- Verify both simulator targets as early as possible; take care with Room/KSP iOS codegen and iOS platform database builder.
- Generate the token source/config without committing its raw value; document how a reviewer can supply their own. Verify builds succeed without token and read-only features still work.
- Record manual test/build verification results from the user as implementation proceeds; no test or build should be marked passed before user execution.
- **Execution rule:** No agent may run shell commands, Gradle, compilation, builds, tests, Xcode, simulators, or emulators without separate explicit user authorization for that exact execution.

## 5. Staged implementation — gates, tests, completion

### Stage 0 — Bootstrap and compatibility
**Work:** Configure shared module structure (`shared` with `androidApp` and `iosApp` host apps), pinned dependency catalog, empty token config fallback, minimal DI initialization.

**Done when:** Android and iOS apps launch to a placeholder `Users` screen, API 26/iOS16 minimums are set, dependencies resolve, token stays out of Git. Both target builds manually verified by the user.

### Stage 1 — Shared Design System
**Work:** Implement `MaterialTheme`, fixed light/dark color schemes, typography, spacing/shapes, shared UserRow, empty/error/loading skeletons; minimal top bar and FAB; 600dp two-column layout.

**Done when:** Same shared components render on Android and iOS, light/dark and narrow/wide layouts manually reviewed by the user, no extra screens created.

### Stage 2 — API, persistence and DI
**Work:** GoRest DTOs and Ktor service, pagination-header handling, Room KMP database/DAOs, repository with snapshot reconciliation and owned-user overlay, error mapping, Koin wiring, no-token read mode.

**Tests:** Last-page selection, missing/changed pagination, transactional snapshot replacement, preserved local-created users, duplicate IDs, cache after HTTP/network failures, conclusive vs inconclusive server checks.

**Done when:** Both platforms compile with Room/Ktor/Koin and read offline cache, actual API response observed, mock/integration repository tests pass when manually executed by the user.

### Stage 3 — Smart User Feed
**Work:** Shared `UsersViewModel`, `UsersUiState`, reactive row display, relative timestamp formatting, shimmer, error/empty states, app-launch fetch, pull-to-refresh.

**Tests:** No-cache shimmer -> data; cache-first; refresh error; offline/no-cache; format at time boundaries; preserve firstSeenAt; adaptive layout behavior.

**Done when:** List contains last-page users with Name/Email/relative time; cache shows immediately on restart; both platform smoke tests pass as manually verified by the user.

### Stage 4 — Add User
**Work:** Shared Name/Email/Gender form, full-screen phone presentation and wide dialog, validation after interaction, 422 mapping, token-missing explanation, busy/submit state, 201 success insert at top and auto-close/animate.

**Tests:** Unicode name handling, whitespace, invalid email, blur and submit rules, missing gender, server 422, missing token, duplicate submit, network error preserving inputs, 201 persistence/overlay ordering.

**Done when:** Create works using own GoRest token on both platforms (manually verified by the user), new user immediately appears at top and survives refresh/restart, form remains usable without token.

### Stage 5 — UX/adaptive/accessibility QA
**Work:** Shimmer pacing, list insertion animation, Android/iOS keyboard and insets, VoiceOver/TalkBack labeling, dynamic type, 600dp threshold and two-column readability, light/dark polish.

**Done when:** Required UI states look consistent, controls accessible, no clipped content or out-of-scope extras. Treat as a manual QA pass by the user, not a feature expansion.

### Stage 6 — Original Delete & Undo flow (implemented before the durable revision)
**Work:** Shared long-press confirmation, one pending deletion, animated temporary hide, 5-second Undo, actual HTTP DELETE after window, 204 finalization, failure restoration and Snackbar. This is historical behavior, replaced by the approved durable multi-delete flow.

**Tests:** Cancel, Undo before deadline (no HTTP request), timeout then one DELETE, HTTP 204, 401/403, 404 as confirmed by API, transport/server error, refresh overlap, rapid second long press, disposal/cancellation. If the process terminates **before** the DELETE request is sent, the in-memory pending action ends without deleting; a new launch shows the cached/server row again. If termination occurs **after** sending, the next normal remote sync reconciles the server state. No lifecycle persistence.

**Done when:** Works against a token-owned test user on Android and iOS (manually tested by the user); Undo restores row and prevents HTTP request; status-dependent failures do not falsely report success. README flags order deviation.

### Stage 7 — Final verification and delivery
**Work:** User re-runs shared unit tests, Android and iOS build+smoke flows, inspect diffs/secrets, public GitHub, brief truthful `README.md` with setup/token, architectural choices, AI collaboration and review, known API/Undo tradeoffs, and actual test results. Optionally capture screenshots if easy; not required by PDF.

**Done when:** Public repo accessible, instructions reproducible, no token leaks, user-verified states clearly differentiated from unverified ones.

### Approved durable multi-delete follow-on

- **Phase 1 — Room v2 foundation:** Added `delete_transactions`, an additive v1-to-v2 migration, transactional acceptance/Undo and conditional claim/completion DAO operations, and a Room visible-user query.
- **Phase 2 — App-owned processor:** A shared Koin singleton owns one foreground scheduler with individual persisted deadlines and up to four concurrent requests. Room compare-and-set claims guard DELETE and verification; DELETE 204 or 404 and cached-row removal share a Room transaction. Timeout, lost response, and server uncertainty use direct GET before resend. Retry schedules persist bounded exponential backoff, transaction-specific jitter and valid Retry-After. A fresh process resumes expired intents and verifies orphaned InFlight/Verifying rows. Recovery is best effort while foregrounded; a terminated app does not execute background work.
- **Phase 3A — Application lifecycle foundation:** `AppLifecycleManager` in `commonMain` and native bridges (`PeopleApplication` with `ActivityLifecycleCallbacks` on Android, `scenePhase` observation in `iOSApp.swift` on iOS) coordinate foreground recovery and ordered shutdown (`processor.shutdownAndJoin()` before `database.close()`).
- **Phase 3B — Shared UI & processor activation:** The live app uses the Room visible-user query, resumes the processor on foreground, routes deletions through persisted transactions, and supports independent deadlines with latest-only Undo. The former single-delete path has been removed from the live flow.
- **Verification gate:** The user reports successful Android and iOS builds and completed manual QA, including Delete/Undo. The latest reported `:shared:testAndroidHostTest` run executed 177 tests: 177 passed, 0 failed, 0 skipped. The latest native iOS unit tests and full migration/recovery matrix remain unverified.

## 6. Manual platform smoke checklist

Check each on **both** Android emulator and iOS simulator (performed manually by the user):
- Start with token absent: public user list loads; Add form opens, validation works, Create explains missing token.
- Start with cache absent and network unavailable: designed error + Retry; with cache: list remains usable.
- Start with valid token: fetch genuine last page; verify API response headers and relative timestamp semantics.
- Add user: name/email/gender validation, create 201, form/dialog closes, new row appears at top, survives refresh and app restart.
- Long press/delete: Cancel; Undo before deadline; expire Undo -> DELETE 204; unauthorized failure restores row without losing cached content.
- Refresh after remote data changes: replace stale snapshot, keep tracked created users without duplication.
- System light/dark, phone portrait and wide/landscape >=600dp, IME visibility, dynamic text and accessibility labels.

## 7. AI orchestration and change management

- `AGENTS.md` is the repository agent contract, `DESIGN_SYSTEM.md` the UI specification, and this file the task and acceptance checklist.
- For each stage, ask Codex to plan only that stage, implement targeted changes, inspect its diff, and report proposed modifications. No agent may run shell commands, Gradle, compilation, builds, tests, Xcode or simulators without separate explicit user authorization for that exact execution.
- Never update the checklist to "done" without user test evidence, and never fabricate working cross-platform status.
- Keep one-day scope strict; if a requirement exposes an unavoidable contradiction, record the reason and the smallest acceptable compromise before new code.

## 8. Deferred, provisional and rejected items

| Item | Status | Why |
|---|---|---|
| Delete server ownership on public/seed users | **Provisional — validate live** | API permissions may constrain deletion |
| `Seen` / `Added` relative timestamp semantics | **Provisional — validate live** | No known server creation timestamp |
| 204-first wording vs deferred Delete | **Known deliberate deviation** | True Undo without server restore endpoint |
| Durable multi-delete ledger and resumption | **Active; user reports Android/iOS builds and completed manual QA including Delete/Undo** | Explicit product decision supersedes the former single-delete restriction; latest Android host run passed 177/177 with 0 failed and 0 skipped; latest native iOS unit tests unverified |
| Offline Add queue | **Rejected** | Not required |
| User Details, Settings, login, search, extra screens | **Rejected** | Not requested |
| Exact dependency versions / real build status | **Versions pinned; user reports Android/iOS builds** | No fresh public-clone build recorded |
| README | **Updated for delivery** | Describes actual code and distinguishes current Android host tests and reported manual QA from unverified native iOS unit tests |
