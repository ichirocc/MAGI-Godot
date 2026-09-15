# MAGI V6 Web → Native Port Inventory

Source: `magi_v6_web.html` / P11 uploaded package.

This inventory tracks Web V6 symbols and their Android Native mapping. It is used as the checklist for smart sync: do not overwrite working Native code blindly; port in layers, validate each layer, and keep browser-only mechanisms represented by Android-native equivalents.

## Current strategy

1. Keep the current Android app buildable.
2. Sync documentation and verification notes first.
3. Port model/engine code before large UI rewrites.
4. Add UI features in read-only mode before enabling edits.
5. Add tests around every scoring/optimizer port.

## Core mapping summary

| Web symbol / feature | Native mapping / status |
|---|---|
| `makeInitialState` | `StateParser` + bundled sample JSON assets |
| `_csvEsc` / CSV export | Native CSV helpers and schedule sharing/export path |
| `parseScheduleCsv` | P11 `ScheduleCsvBridge.parse` candidate; current app has simple CSV share only |
| `reducer` / `historyReducer` | Native ViewModel reducers still pending in current GitHub app |
| `resolveConstraints` | Current `ResolvedProblem` / `ResolvedConstraintSet` |
| `buildImpossibleWishSummary` | P11 `V6WebCompat` candidate, not yet in current GitHub app |
| `buildShiftCountDiagnosticStructured` | P11 `V6WebCompat` candidate, not yet in current GitHub app |
| `detectImpossibleWishes` | P11 `V6SanityPort` candidate, not yet in current GitHub app |
| `buildSanityCheck` | P11 `V6SanityPort` + `V6PortAnalyzer` candidate |
| `runSimpleSchedule` | P11 `GreedyMirrorScheduler` candidate |
| `runOptimization` | Current `WebSmartOptimizer`; P11 `V6NativeOptimizer` candidate |
| `runViolationCheck` | Current `ScoreAnalyzer`/`ResolvedEvaluator`; P11 `UnifiedViolationChecker` candidate |
| `_magiStableDcb` | P11 stable scoring path candidate |
| `scoreVecStable` / `betterVec` / `firstDiffTier` | P11 `V6WebCompat` candidate |
| `MagiRNG` / xorshift | P11 `V6WebCompat` candidate |
| `MagiPatternDB` | P11 `V6WebCompat` candidate |
| `zobristHash_v5` | P11 deterministic hash candidate |
| Workbook builders `buildWs1..buildWs7` | P11 `V6WebCompat` candidate |
| Worker execution | Android coroutine/direct execution; Web Worker/Blob URL not ported directly |
| `getV5Flags` | Settings/flag UI pending |
| `runRSIPlusParallel` | P11 candidate; not yet in current GitHub app |
| `runALNSParallel` | P11 candidate; not yet in current GitHub app |
| `HomeView` / `OverviewDashboard` | Current simplified Compose UI; P11 richer UI candidate |
| `MobileScheduleView` / `ScheduleGrid` | Current simplified grid; P11 richer UI candidate |
| `WatchConstraintView` / `ConstraintsSection` | Current constraint summary; detailed edit UI pending |
| `ColorSettingsView` | Pending |
| `LogsView` | Pending |

## P11 App-handler bridge additions

| Web symbol | P11 Native mapping candidate |
|---|---|
| `buildBusyDetail` | `V6FinalPort.buildBusyDetail` |
| `confirmDespiteImpossibleWishes` | `V6FinalPort.confirmDespiteImpossibleWishes` |
| `handleSimple` | `V6FinalPort.handleSimple` |
| `handleCheck` | `V6FinalPort.handleCheck` |
| `handleOptimize` | `V6FinalPort.handleOptimize` |
| `getAlgorithmLabel` | `V6FinalPort.getAlgorithmLabel` |
| `checkResultWorse` | `V6FinalPort.checkResultWorse` |
| `runPostOptimization` | `V6HotfixPasses.runPostOptimization` |
| `window.HF80` | `V6HotfixPasses.applyHF80StrategicOscillation` |
| `window.HF67` | `V6HotfixPasses.applyHF67InterStaffSwap` |
| `window.HF66` | `V6HotfixPasses.applyHF66IntraStaffRedistribution` |
| `window.HF70` | `V6HotfixPasses.detectHF70Anomalies` |

## P11 source files to compare before porting

### Engine/model candidates

- `engine/C3Run.kt`
- `engine/Problem.kt`
- `engine/Evaluator.kt`
- `engine/DeltaEvaluator.kt`
- `engine/MirrorCore.kt`
- `engine/GreedyMirrorScheduler.kt`
- `engine/LightMirrorOptimizer.kt`
- `engine/SaOptimizer.kt`
- `engine/V6NativeOptimizer.kt`
- `engine/V6FinalPort.kt`
- `engine/V6HotfixPasses.kt`
- `engine/V6WebCompat.kt`
- `engine/V6SanityPort.kt`
- `engine/V6PortAnalyzer.kt`
- `engine/Ws1Ops.kt`
- `engine/ScheduleCsvBridge.kt`
- `model/MagiState.kt`
- `model/StateParser.kt`

### UI candidates

- `ui/MagiViewModel.kt`
- `ui/ConstraintEditor.kt`
- `ui/Ws1Editor.kt`
- `ui/WishEditor.kt`
- `ui/StaffRangeEditor.kt`
- `ui/NeedDayEditor.kt`
- `ui/ShiftColorEditor.kt`
- `ui/V6RemainingScreens.kt`

### Test candidates

- `V6WebCompatTest.kt`
- `MirrorEngineTest.kt`
- `V6PortAnalyzerTest.kt`
- `V6FinalBridgePortTest.kt`
- `DeltaEvaluatorTest.kt`
- `V6SanityPortTest.kt`
- `V6NativeOptimizerChoiceTest.kt`

## Browser-only items

These should not be copied directly:

- DOM operations
- Web Worker Blob URL creation
- Tailwind runtime CSS
- `window.storage`
- browser file APIs

Use Android-native replacements: Compose state, ViewModel, ContentResolver, Storage Access Framework, and coroutine/direct execution.

## Next smart-sync order

1. Add/verify test infrastructure.
2. Port `C3Run.kt` and align C3 run-mode scoring.
3. Compare current `ResolvedEvaluator` / `DeltaEvaluator` with P11 `Evaluator` / `DeltaEvaluator` before replacing anything.
4. Add P11 CSV import/export as separate bridge, not by replacing current share flow.
5. Add read-only constraint detail previews before editable constraint UI.
6. Add editable C2 → C3 → C1 → C41/C42 → groupShiftApt in that order.
