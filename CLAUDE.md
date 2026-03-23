# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Element X Android is a Matrix messaging client built with Kotlin, Jetpack Compose, and the Matrix Rust SDK. It is a single-activity, full-Compose application using an MVI-inspired architecture (similar to Circuit).

## Build Commands

```bash
# Build debug APK (Google Play flavor)
./gradlew :app:assembleGplayDebug

# Build F-Droid flavor
./gradlew :app:assembleFDroidDebug

# Run all quality checks (detekt, ktlint, lint, konsist)
./gradlew runQualityChecks

# Individual quality checks
./gradlew detekt
./gradlew ktlintCheck --continue
./gradlew ktlintFormat              # Auto-fix formatting
./gradlew lint

# Run all unit tests
./gradlew test

# Run tests for a single module
./gradlew :features:home:impl:testDebugUnitTest

# Run a single test class
./gradlew :features:home:impl:testDebugUnitTest --tests "io.element.android.features.home.impl.HomePresenterTest"

# Konsist architecture tests
./gradlew :tests:konsist:testDebugUnitTest

# Code coverage
./gradlew :app:koverHtmlReport
./gradlew :app:koverVerify

# Screenshot testing
./gradlew recordPaparazzi           # Record Compose snapshots
./gradlew recordRoborazzi           # Record Roborazzi screenshots

# Pre-PR check (full quality + tests)
./tools/quality/check.sh

# Update markdown table of contents
./gradlew generateDocsToc
```

## Architecture

### MVI Pattern (Presenter → State → View)

The core pattern, inspired by [Circuit](https://slackhq.github.io/circuit/):

- **`Presenter<State>`**: A `fun interface` with a single `@Composable fun present(): State` method. Uses Compose runtime for reactive state (not ViewModel).
- **State**: Immutable data class containing UI state and an `eventSink: (Event) -> Unit` lambda for handling user actions.
- **Event**: Sealed interface representing user interactions.
- **View**: Pure `@Composable` functions that receive state and call `eventSink`.
- **Node**: Appyx nodes connect Presenter and View, manage DI scopes and navigation.

### Module Structure

Three main module categories under a multi-module Gradle project:

- **`features/`** (~46 modules): Each feature has `api/` and `impl/` submodules. The `api` module defines an `EntryPoint` interface; `impl` contains Presenter, State, Events, View, and FlowNode. Features do not depend on other features directly — navigation is wired through the `app` and `appnav` modules.
- **`libraries/`** (~50 modules): Shared code. Key ones: `architecture` (Presenter, AsyncData, BaseFlowNode), `matrix/api` and `matrix/impl` (Rust SDK wrappers), `designsystem` (theme/components), `di` (scope definitions).
- **`services/`** (~5 modules): Cross-cutting services (analytics, error handling, navigation state).

Modules are auto-discovered in `settings.gradle.kts` — no manual registration needed when adding new feature/library/service modules.

### Key Frameworks

- **Navigation**: [Appyx](https://bumble-tech.github.io/appyx/) with `BackStack<NavTarget>` and sealed `NavTarget` interfaces
- **DI**: [Metro](https://zacsweers.github.io/metro/latest/) (annotation-based). Scopes: `AppScope` → `SessionScope` → `RoomScope`. API modules cannot use DI.
- **Matrix SDK**: Rust SDK via UniFFI Kotlin bindings. `MatrixClient` interface in `libraries/matrix/api`, `RustMatrixClient` implementation in `libraries/matrix/impl`.
- **Async operations**: `AsyncData<T>` sealed interface (`Uninitialized | Loading | Success | Failure`) used throughout for modeling async state.

### Build Variants

- **Flavors**: `gplay` (Google Play with Firebase push), `fdroid` (F-Droid with UnifiedPush)
- **Build types**: `debug`, `release`, `nightly`
- **Enterprise builds**: Detected by presence of `enterprise/README.md`, changes minSdk to 33

## Code Conventions

- **Kotlin only** — no Java classes.
- **Max line length**: 160 characters.
- **Naming**: Presenters must end with `Presenter`, states with `State`, events with `Event`/`Events`, views with `View`, flow nodes with `FlowNode`, entry points with `EntryPoint`. These names are used for code coverage rules.
- **Prefer `sealed interface`** over `sealed class`.
- **No class mocking** (no mockk) — use Fake implementations of interfaces. Mocking is only acceptable for Android framework classes (e.g., `Bitmap`).
- **Logging**: Use `Timber.tag(loggerTag.value).d("message")` — never log private user data.
- **Compose previews**: Every `@Composable` should have an internal preview function annotated with `@PreviewsDayNight`, named with `Preview` suffix, using `ElementPreview` as root.

```kotlin
@PreviewsDayNight
@Composable
internal fun MyComponentPreview() = ElementPreview {
    MyComponent()
}
```

## CRITICAL: Implementation Quality Checklist

**Before declaring any feature or fix "done", you MUST complete this checklist. No exceptions. Skipping this has repeatedly caused embarrassing bugs that required multiple fix rounds.**

### 1. Walk through EVERY user-visible surface
A feature is not just the code — it's what the user sees. Before finishing, mentally walk through the ENTIRE user journey step by step:
- What does the user see on screen? (in-app UI)
- What does the notification show? (title, artwork, buttons, metadata)
- What happens when they tap every control?
- What happens when they background/foreground the app?
- If data changes server-side or via service, does EVERY UI surface update? (not just the one you were focused on)

### 2. If a feature spans multiple surfaces, check ALL of them
When code touches a service AND a notification AND an in-app UI, all three must be updated together. Never implement one surface and forget the others. Examples:
- Skip next/previous must update: notification metadata, notification artwork, in-app filename, in-app controls, deep link intent
- Any metadata change must propagate to: service state, notification, in-app player state

### 3. Verify assumptions — never guess with a comment
Do NOT write `// SDK returns newest-first` without verifying it. If you're unsure about ordering, threading requirements, API behavior, or IPC boundaries — look it up in the actual code or docs first. A wrong assumption with a confident comment is worse than no comment at all.

### 4. Think about what's MISSING, not just what compiles
A build passing means nothing to the user. Ask yourself:
- Are there buttons/controls the user would expect but I didn't add?
- Does the UI show stale data after a state change?
- Is artwork/metadata preserved across transitions?
- Are there threading requirements I'm ignoring? (e.g., ExoPlayer requires main thread)

### 5. Android-specific gotchas to always check
- **ExoPlayer/Media3**: All player operations MUST happen on the main thread. Use `Dispatchers.Main` for any scope that touches the player.
- **MediaMetadata.extras**: Does NOT cross the IPC boundary to MediaController. Only `title`, `artist`, `artworkData` are available on the controller side. Never rely on extras for UI state on the controller side.
- **ForwardingPlayer**: Only ADD commands to available commands when your feature enables them. Never REMOVE default commands — it breaks existing behavior (like seek).
- **Async initialization**: If data loads asynchronously, make sure the UI updates AFTER loading completes, not just at the moment you start loading.

## Testing

Three testing frameworks:

1. **Presenter tests**: Molecule + Turbine for unit testing presenters with state flow assertions. Located in `features/{name}/impl/src/test/`.
2. **Screenshot tests**: Paparazzi + Showkase for pixel-perfect UI testing. Adding `@Preview` composables automatically creates screenshot tests.
3. **E2E tests**: Maestro for functional testing. Located in `.maestro/`.

## Strings / Translations

Strings are managed via [Localazy](https://localazy.com/p/element) and shared with Element X iOS. Do not modify string resources directly — add temporary XML files and note it in the PR for the reviewer to integrate into Localazy.
