# DESIGN_SYSTEM.md — People

Status: **Design specification for implementation**. This is an intentionally small design system for the Sliide KMP UX Innovator Challenge, not a general-purpose UI library.

## Design direction

**Minimal & Premium:** clear hierarchy, quiet surfaces, generous breathing room, neutral/slate foundation and a single blue accent. Aim for an expensive-feeling interface through restraint, smooth state changes and accessibility—not gradients, decorative illustrations, flashy effects or extra screens.

- App title: **Users**. English-only UI.
- **One main screen** (directory); Add User as a transient form; Delete as a confirmation dialog + Snackbar.
- **100% shared Compose Multiplatform UI** for Android and iOS in the `shared` module (`commonMain`).
- Match system Light/Dark preference automatically; **no theme switcher or Settings screen**.
- Use Android/iOS system font family; shared typography token sizes and weights (Roboto / SF by platform default, without bundled fonts).

## Semantic colors (initial fixed specification)

The palette is defined in shared `ColorScheme`; tokens are semantic and components must not hardcode platform-specific colors. Confirm text/button contrast in light and dark during UI QA; adjust values centrally if needed.

| Token | Light | Dark | Use |
|---|---|---|---|
| `background` | `#F8FAFC` | `#0F172A` | Page background |
| `surface` | `#FFFFFF` | `#1E293B` | Dialog, sheet, input surface |
| `surfaceVariant` | `#F1F5F9` | `#334155` | Shimmer, inset states, avatar surfaces |
| `primary` | `#2563EB` | `#93C5FD` | FAB, primary button, focus accent |
| `onPrimary` | `#FFFFFF` | `#0F172A` | Text on primary |
| `onBackground` | `#0F172A` | `#F8FAFC` | Main text |
| `onSurface` | `#0F172A` | `#F8FAFC` | Sheet/dialog/list text |
| `onSurfaceVariant` | `#475569` | `#CBD5E1` | Email, helper text, timestamps |
| `outline` | `#CBD5E1` | `#64748B` | Field borders |
| `outlineVariant` | `#E2E8F0` | `#334155` | Hairline row dividers |
| `error` | `#B91C1C` | `#FCA5A5` | Destructive and validation errors |
| `onError` | `#FFFFFF` | `#450A0A` | Text on error-filled surfaces |

- `primaryContainer`: Light `#DBEAFE`, Dark `#1E3A8A`; `onPrimaryContainer`: Light `#1D4ED8`, Dark `#DBEAFE` (for initial/avatar surfaces; verify combination in contrast QA).
- Use theme-aware ripple/focus feedback. Do not convey error/success by color alone.
- Avoid adding a separate design-token engine or dependencies beyond Compose Material 3.

## Typography (system fonts, shared metrics)

| Role | Size / line height | Weight | Intended use |
|---|---|---|---|
| Screen title | `24sp / 32sp` | Semibold | `Users` top app bar |
| Dialog/sheet title | `20sp / 28sp` | Semibold | `Add user`, `Delete user?` |
| User name | `16sp / 24sp` | Medium | Main list content |
| Body/input | `16sp / 24sp` | Regular | Form entries, dialog explanation |
| Supporting text | `14sp / 20sp` | Regular | Email, validation description |
| Timestamp/caption | `12sp / 16sp` | Regular | `Seen … ago` / `Added … ago` |
| Button | `14sp / 20sp` | Semibold | Form and dialog actions |

Use scalable `sp`, meaningful labels and native system font metrics. Allow rows and dialogs to grow with large accessibility text instead of clipping. Keep long emails readable (ellipsize only if necessary; inspect narrow widths).

## Spacing and shapes

- Spacing tokens in `dp`: `4`, `8`, `12`, `16`, `20`, `24`, `32` (use these before inventing others).
- Main screen horizontal inset: **20dp** on phones; up to **24dp** at expanded widths.
- Directory list: **comfortable density**; row minimum **76dp**, flexible with text scaling; avatar **44dp** diameter; content gap **12dp**; subtle divider aligned to list content.
- Standard rounded shapes: field/buttons **12dp**; bottom-sheet/dialog **24dp** top/corners; auxiliary rounded surfaces **16dp**. Avatar is circular.
- Standard minimum interactive target: **48x48dp**, including Delete affordances and controls.
- FAB size: Material 3 regular FAB default; anchored bottom-end with safe-area/inset padding.
- Prefer tonal surfaces and thin dividers to prominent shadowed cards; **no individual user cards** on phone.

## Responsive layout

Measure available **window/content width**, not platform or orientation string:

| Available width | Directory | Add User form |
|---|---|---|
| `< 600dp` | Single clean list, comfortable rows | Full-screen form |
| `>= 600dp` | Two-column clean-list grid, stable reading order | Centered dialog (cap width around 440-480dp) |

- A two-column layout means two columns of the **same clean list row design**, not a User Details / master-detail screen.
- Keep column gutters around **16–24dp**; ensure row content remains readable.
- Width changes must not reset data or trigger a network reload.
- Add form must scroll/adjust for the on-screen keyboard on both Android and iOS; submit actions stay reachable.

## Components and UX contracts

### Directory screen
- Minimal Material 3 top app bar with **Users** only; no counters, search, settings or navigation actions.
- List rows: initials avatar; **name**, **email**, and **relative timestamp**. No profile pictures or external image service.
- Remote user timestamp label: `Seen 5 minutes ago`; local-created: `Added 5 minutes ago` (**provisional data semantics**; verify GoRest response and reconsider only if necessary).
- Long press row to open Delete confirmation; normal tap has no additional details action.
- FAB (+) bottom end launches Add User form.
- Pull-to-refresh keeps current cached content visible. No artificial full-screen shimmer after data has already loaded.

### Add User
- Phone: full-screen form. Expanded: centered Dialog. Reuse one composable form content, rather than separate form logic.
- Fields: **Name**, **Email**, **Gender**. Gender uses Material 3 single-choice segmented buttons: **Male** / **Female**; initially unselected and required. Send `status=active` without exposing a Status control.
- Initially no error labels. Validate values in shared logic as a field becomes edited/loses focus; show required errors on blur or submit. Validate all on submit; sanitize surrounding whitespace. Do not use overly restrictive ASCII-only name rules or a single excessively clever email regex; server 422 is authoritative.
- Display field errors immediately below corresponding fields, with text and icon where appropriate; preserve entered values on network/server failure.
- Disable duplicate submit while creating and show progress in primary button.
- On confirmed **201**: close form, insert persisted user at top, scroll top if necessary, animate insertion, show a brief success message.
- Without a configured token: allow opening the form and local validation; block API submit and clearly explain setup is required (no fake success).

### Delete / Undo (implement last)
- Long press -> shared Material 3 centered AlertDialog. Include user's name, Cancel, destructive Delete.
- Approved durable flow: Confirm -> persist intent and hide row with animation -> **5-second** Undo Snackbar for the latest accepted transaction -> if undone, restore without HTTP request; otherwise issue DELETE -> on **204** finish removal. Several users may have independent pending deadlines; accepting a new user revokes the previous Undo without moving its deadline.
- The live app uses the durable multi-delete flow. The Room ledger, processor, shared UI, and platform foreground integration are active.
- **Tradeoff:** Original challenge wording places the 204 response before the visual removal/Undo; the approved delayed-commit flow differs. Call this out in README and validate the choice in final review.
- Durable recovery and retry decisions live in the app-owned processor, and the UI consumes its Room-backed state. Treat DELETE 204 or a DELETE 404 confirming absence as resolved; other failures must not imply confirmed removal.

### UI states

| State | Design response |
|---|---|
| First launch, empty cache, request in flight | 4-6 shimmer list-row placeholders, same geometry as final rows |
| Cache present and remote refresh running | Render cached list immediately; subtle refresh progress only |
| Cache empty and API reports empty users | Centered Material icon, `No users found` and short description |
| Cache empty, network failure | `No internet connection` / relevant error and **Retry** button |
| Cache present, refresh fails | Keep list; small contextual non-blocking message |
| Add invalid/422 | Inline field errors; remain on form |
| Add transport/server error | Inline/general form error; keep form values |
| Delete fails | Restore hidden row, contextual error Snackbar |

Keep empty-data distinct from network failure. Map auth/permission/rate limit errors to clear messages rather than reporting every failure as "No internet".

## Motion

- Normal UI motion: **150–250ms**; list insert/remove ~**220–280ms**. Prefer framework defaults when they give equivalent behavior.
- Shimmer only for initial uncached loading; no aggressive flashing; stable placeholder sizes to avoid layout shift.
- Use Compose animation primitives for insertion/removal and sheet transitions; avoid external animation frameworks.
- Respect accessibility/reduced motion as supported by shared UI APIs; functional clarity matters more than animation.
- Snackbar Undo window: **5 seconds** as a business behavior, not merely a visual animation duration.

## Accessibility and cross-platform QA

- Verify TalkBack (Android) and VoiceOver (iOS), labels for FAB, avatars (decorative), fields, segmented options, dialog, Undo action.
- Meet WCAG-style readable contrast target (normally 4.5:1 for normal text) and 48dp targets where possible.
- Keyboard focus order, IME action behavior, error announcement, safe-area insets, landscape two-column readability, and dynamic font size.
- UI smoke tests on Android and iOS **including light/dark, phone width and >=600dp window** are performed manually by the user (AI agents must NEVER run builds, tests, or simulators). Do not state visual parity until manually verified by the user.

## Explicit non-goals

User Details, Settings, theme picker, counts, search, filters, extra screens, remote images, decorative illustration assets, server-side user creation timestamps and offline create queue are out of scope. The approved durable Delete ledger is an exception to the former persistent-delete exclusion.
