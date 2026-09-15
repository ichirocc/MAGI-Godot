# V6 Final Bridge Port Notes

This pass targets the last practical gap after p8: Web V6 logic that lived inside the React `App` component rather than in standalone worker functions.

## Newly ported Native files

- `V6FinalPort.kt`
  - `buildBusyDetail`
  - `confirmDespiteImpossibleWishes`
  - `handleSimple`
  - `handleCheck`
  - `handleOptimize`
  - `getAlgorithmLabel`
  - `optimizationPlan`
  - `checkResultWorse`
- `V6HotfixPasses.kt`
  - HF80 strategic oscillation replacement
  - HF67 inter-staff swap replacement
  - HF66 intra-staff redistribution replacement
  - HF70 anomaly detector replacement
  - post-chain `HF80 -> HF67 -> HF66 -> HF70`

## Native substitutions

The following Web concepts are now deliberately represented by Android-native equivalents rather than copied 1:1:

| Web concept | Native replacement |
|---|---|
| `window.confirm` in impossible-wish gate | `ImpossibleWishGate` result; ViewModel may block or allow |
| `setBusyDetail` / BusyOverlay detail object | `V6FinalPort.BusyDetail` |
| `handleSimple` | `V6FinalPort.handleSimple` |
| `handleCheck` | `V6FinalPort.handleCheck` |
| `handleOptimize` | `V6FinalPort.handleOptimize` |
| `getAlgorithmLabel` | `V6FinalPort.getAlgorithmLabel` |
| `runPostOptimization` closure | `V6HotfixPasses.runPostOptimization` |
| `window.HF80` | `V6HotfixPasses.applyHF80StrategicOscillation` |
| `window.HF67` | `V6HotfixPasses.applyHF67InterStaffSwap` |
| `window.HF66` | `V6HotfixPasses.applyHF66IntraStaffRedistribution` |
| `window.HF70` | `V6HotfixPasses.detectHF70Anomalies` |
| `archiveAndClearLogs` | ViewModel run boundary + returned `logs` list |
| `checkResultWorse` | `V6FinalPort.checkResultWorse` |

## Residual items

No business logic item is intentionally left as `needs manual review`. Browser runtime mechanisms are not direct ports because Android has no DOM, Worker Blob URL, Tailwind runtime, or `window.storage`.
