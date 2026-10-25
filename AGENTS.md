# AGENTS.md — People (Sliide KMP UX Innovator Challenge)

> Applies to the entire repository. Read `docs/IMPLEMENTATION_PLAN.md` and `docs/DESIGN_SYSTEM.md` before implementing a feature. The source of truth for challenge requirements is the supplied two-page Sliide KMP "UX Innovator" Challenge PDF. This file is engineering guidance, not a substitute for the challenge.

## Mission and scope

Build a polished **Android + iOS** user-directory app using **Kotlin Multiplatform and 100% shared Compose Multiplatform UI**, in the scope of a one-day challenge. Quality matters more than adding features.

**Required:**
- Get users from the **last page** of GoRest `/users`; show name, email, and a relative timestamp calculated in shared Kotlin code.
- Shimmer loading, graceful errors (including no internet), and offline cached read access.
- FAB -> Add User form with real-time name/email validation. Show new user at the top immediately after successful HTTP 201.
- Long press -> delete confirmation; animated removal and actionable Undo; verify HTTP 204 on successful server delete.
- Portrait single list; landscape/tablet adaptive two-column layout.
- Clean separation of `data`, `domain`, `presentation`; Ktor; Room KMP; Koin; shared ViewModel/logic tests.
- Public GitHub repo and accurate README detailing architecture and AI use.

**Approved small UX choices:** clean list and initials avatar, system theme-aware Light/Dark color scheme, pull-to-refresh, adaptive add form, inline validation, contextual errors, empty state. These are not additional product screens.

**Do not add:** User Details, Settings, login/onboarding, search, filters, sorting, editing, paginated browsing, analytics, extra navigation, backend service, offline Add queue, or unrequested third-party services. The approved durable multi-delete architecture is the sole exception to the former persistent Delete restriction.

## Non-negotiable engineering rules

1. **Cross-platform parity:** Everything user-visible must work on Android and iOS. All UI, user flows, validation, relative-time formatting, business rules, and state live in `commonMain` inside `shared`. Restrict platform code to entry points (`androidApp` and `iosApp`), platform Ktor engine, Room builder/path, and necessary build integration.
2. **KMP module structure:** `shared` is the single shared KMP Gradle module, with packages `data/`, `domain/`, `presentation/`, `designsystem/`, `di/` inside `commonMain`. Host applications are `androidApp` (Android host application) and `iosApp` (iOS host application). Do not create additional Gradle feature modules without a proven need.
3. **MVVM:** One shared `UsersViewModel`, immutable `UsersUiState`, `StateFlow`. Prefer simple explicit operations and testable injected collaborators; keep Composables presentation-only.
4. **Data flow:** Room is the list's reactive local source of truth. Repository fetches the remote last-page snapshot, updates Room transactionally, preserves locally created records as specified, and deduplicates by server ID. Network failures must not wipe cache. The approved deletion ledger is also Room-backed; its processor and UI integration are separate phases.
5. **Networking:** Ktor shared HTTP client config and error mapping; OkHttp on Android / Darwin on iOS; kotlinx.serialization. Never assume successful status without checking it. Handle HTTP 201 (create), 204 (delete), 401/403, 404, 422, 429, 5xx and connectivity errors as appropriate.
6. **Database:** Room KMP with `BundledSQLiteDriver` and platform-specific database builders. No Android-only Room API in common code. Application builds and test execution must be authorized and verified manually by the user.
7. **Dependency injection:** Koin with small, explicit modules. Prefer constructor injection and fakes in tests.
8. **Secrets:** GoRest token from a Git-ignored local build configuration shared by both targets. Do not commit, print, test-snapshot, or log the token. Missing token must not prevent read-only app launch; form and validation remain inspectable, but write requests are blocked with a clear explanation. A client-embedded build token is **not** production-grade secret storage; state this in README.
9. **Visual consistency:** Follow `docs/DESIGN_SYSTEM.md`. Reuse colors, spacing, typography, components, and motion tokens. No new screen or bespoke style without a requirement.
10. **Scope discipline:** Do not solve unrelated edge cases via frameworks, queues, generic abstractions, or new infrastructure. Prefer the smallest solution that satisfies the task.
11. **Execution rules:** No agent may run shell commands, Gradle, compilation, builds, tests, Xcode, simulators, or emulators without separate explicit user authorization for that exact execution.

## Important ambiguities — do not silently hide them

- **Timestamp:** GoRest user payload is not known to provide creation time. Initial approved provisional rule: remote users show **"Seen … ago"** calculated from persisted `firstSeenAt`; locally created users show **"Added … ago"** from local creation time. Validate against real API data during implementation. Never fabricate the server's creation timestamp.
- **Delete ownership:** Provisional plan: attempt authorized DELETE for selected users and explain permission errors. If seed records are not deletable, test with own created records and consider limiting deletion affordance only after observing actual API responses.
- **Undo order:** The challenge text describes showing Undo **after** server 204, but GoRest exposes no restoration endpoint. The approved sequence remains **confirm -> hide -> 5-second Undo window -> DELETE if not undone -> finalize on 204**. The newly approved durable multi-delete architecture persists each accepted intent in Room, preserves independent deadlines, and permits recovery after process death. Document the deviation from literal 204-first ordering transparently. Phase 1 adds only the database foundation; the existing single-delete flow remains live until processor and UI integration.

## Iteration protocol for Codex

For each stage in `docs/IMPLEMENTATION_PLAN.md`:
1. Read acceptance criteria and the relevant design spec first.
2. Make a small, targeted change only for the current stage; do not start later stages.
3. Write or update code/tests for behavior changed. Cover success, failure, empty, and important race conditions.
4. **No unauthorized execution:** Do not run shell commands, Gradle, compilation, builds, tests, Xcode or simulators without separate explicit user authorization for that exact execution. Prompt the user for any manual app or test verification needed.
5. Review the diff for architecture leakage, accidental secrets, unrelated changes, and untested assumptions.
6. Report changed files, user-performed verification results, outstanding risks and the next planned stage.

## Acceptance bar

A stage is complete only when its behavior is implemented, the user has manually verified that tests pass and both platforms function correctly, and no out-of-scope screen or feature was added. Prepare the final README **after** implementation to reflect actual decisions, AI assistance, verification results, and tradeoffs.
