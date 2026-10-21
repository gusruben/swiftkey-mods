# pipeline: NintypeEngine facade, status and port plan

The oracle's call tree is in `../merge/PIPELINE.md`. `NintypeEngine` mirrors it:
`onTouchEvent(pid, action, x, y, pressure, size, ms)` and `step(clockUs)`.
`PipelineE2ETest` (engine/src/test/.../pipeline) replays the oracle corpus (`re/out/e2e/*.json`,
`re/vectors/gen_pipeline_e2e.py`) through the facade in stages.

## Stages

| Stage | Checks | Status (2026-10-20) |
|---|---|---|
| clock, touchinfo, records, mootouch, saveable | the input side (clocks, TouchInfo, Mootouch::record calls, Mootouch bytes, SaveableTouch records) | 31/31 each |
| sitting | the sitting list at each updateMergeBuffers (with search's host, +0xec included) | 31/31 |
| visit | syncVisitationPoint on each sat touch | 31/31 |
| mergecalls | MooMergeBuffer::update calls (start, lastTouch, last, first, full) | 31/31 |
| syncpred | syncPredictedText step by step (each recordPredStepsDebug: label, alternatives, chosen WordAlternative) and every markliquid, against re/out/e2e_syncpred | 30/31; kinetica-landscape waits on search's mergeFromSpaceSplitters two-word alternative ("La Z") |
| commit | the word-commit call log (reinforcementsOnMergingAll / updateStateAfterMergingAll and the learning calls, with arguments) and the solidify text, against re/out/e2e_commit | 3/3 (the commit gestures) |
| candidates | the driver's candidate list (word, score bits, prior bits) | 31/31 |
| text | the last markliquid text | 31/31 |
| backspace | re/out/e2e_backspace (gen_pipeline_backspace.py): per backspace the markliquid texts, sitting count, re-decode candidates, backspace-history size and the Moopad fields the path writes; at the end the candidates, text and alternatives (retype penalties) | see below |

Engine construction for the integration: `NintypeEngine(model, boot, strict = false,
sharedLangState = ...)`; search's `nintype.engine.search.PipelineMergeHost` is picked up by
reflection. `boot.searchBoot` / `boot.trieDir` locate the search boot dump and the trie dump.

## Facts the facade relies on

- **Touch transform.** TouchInfo x, y = `f2iz(px) / 2` (C int division), then
  `pos - screenKeyboardOrigin` (0, 152) as floats. dx/dy are the change since the pointer's
  previous event; they are 0 on DOWN and UP.
- **Clocks.** See EngineClock. App start is (1500000000 s, 0 µs). dt is 1/60 when the previous
  frame time is negative.
- **Record clock.**
  - Events: handleMooTouch and performMooTouchRelease set the clock to (t, factor, dt).
  - Frames: Moopad::update sets it to (gts, factor, dt).
  - MOVE subdivision: n = max(1, ceil(|dpos| / 4)) records when the touch is active (+0xb5).
    The record time is interpolated between the touch's +0x1c8 (when +0x1d0 is set) and the
    event time; factor and dt are divided by n.
- **Live touches.** Moopad+0x2942c is a map keyed by pid. obtainTouch constructs Mootouch()
  (constant template, unique id from Emkey::mootouch_counter) and gives the new touch order
  index +0xbc = live count - 1, bumping the others. sitTouch copies the touch into the sitting
  list (+0x294c4) and erases it from the live map.

## syncPredictedText (pipeline/SyncPredictedText.kt)

Ported step for step from the decompile and verified by the syncpred stage: the prologue (sitting
count gate, state reset), MooMergeBuffer::getSweepChoice through the host's `pathResult`,
getMostRecentAutoCompleteBuffer and the AC Xnls, loadEdgeternsFromAC (shown-button Xnls; the
buttons are UI), the literal ("explicit") alternative with its score curves (statics at
0x1b93b58 / 0x1b93b64 / 0x1b93b70, dumped from the oracle), literal reasons 0..8, quote literals,
filterInvalids, spliceAutoComplete (gate), spliceWildGuesses (gate), spliceCustomScoring, the
std::sort, filterRepeatingResults, performNeoMarkov (trie's NeoMarkov with the built-in chain
Markov + CoreccionStore), spliceCapsToExplicit, spliceWeirdCaps (WeirdCaseManager),
maybeTransform, spliceSmartQuotes + reallySpliceSmartQuotes, caps-lock upper-casing, the
backspace-retype gate, setCurrentWA, the tail Xnls, RecentWATransformed, the history multimap,
propagateLiquidToClient and syncCorrectionBox. Every splice has a switch in `PredConfig`
(engine.predConfig), defaults = Nintype.

Pending inside it (no effect on the corpus, reported through engine.pending when reached):
spliceAutoComplete / spliceThiccAutocompleteSlots bodies (need the ACCollectage contents that
search's gatherAutocomplete will build), wild guesses (WildGuesser), the text transforms behind
their settings (SMS saving, TransformerEnv), inductToChainKeeper (ChainEnv, trie area),
historySyncProcedure, pushSpaceIfTempAutospaceIsOn.

## Backspace (pipeline/Backspace.kt, NintypeEngine.backspace())

Moopad::performBackspace's in-word branch (the backspace key's tap while touches sit): the last
chain (Mootouch+0xd8, the touches of one all-up period: a tap, a swipe, one two-thumb burst)
leaves the sitting list, its merge buffer goes, the rest backtrack (onTouchBacktrack) and
updateMergeBuffers re-decodes. Before that saveCurrentPredictionToBackspaceHistory pushes the
current WordAlternative to the destroyed-word deque (LangState.destroyed, max 6) unless the
last search is under 0.3 s old, and Moopad+0x2affc counts the backspace. Emptying the word turns
caps on again when the word was capitalised (syncCapsOnBufferEmptyingBackspace) and emits
markliquid "". syncBackspaceRetypePenalties is ported too (curve and
matchesCurrentContextAsRecentlyDestructed from the oracle and the disassembly). With a pointer
down or no word, backspace() changes nothing (handled = false); the non-buffer path (text
deletion in the field) is the host's. Pending: getCurrentPreviousWordFromProxy for a non-empty
field (approximated), the retype reinforcements on commit (maybeReinforceOnStandardMerge pends
once LangState.destroyed is non-empty), Moopad+0x32cfc (single history, non-buffer path).

## The word commit (updateMergeBuffers' merge-all path)

Verified by the commit stage: mergeAll -> reinforcementsOnMergingAll (canReinforceFromThis,
maybeReinforceOnStandardMerge gate, the history entry and its WordAlternatives,
WeirdCaseManager.onChooseCasing, BadExplicitManager.onUsage, maybeStartUpvoting /
maybeReinforceExplicitCorrectionAfterSelection gates, the replaceable-history deque push into
LangState.replaceable / .history, onHistoryChange) -> progressCharaMarkov(' ') -> the upload
text ("word ") -> solidify (pred.textBeforeCursor grows) -> updateStateAfterMergingAll
(WordLearningModule.onMergeAfterMergingAll, AutoCompLearn.onStandardUsage on the raw or lowered
word, buffer and sitting cleanup, commit time, syncPredictedText -> syncCorrectionBox).
Pending: Interactute::onMerge, LangEnv::langflow_onWordUsage, progressCharaMarkov /
syncPathSearchCharaMarkovBuffer / clearCharaMarkov, recordRecentlyUsedWord, the retype
reinforcements (need backspace histories), maybeReinforceOnChoiceChoosing (a chosen
alternative), the temp-autospace and Shing::Context upload variants, auto-caps after the commit,
onJustAfterMergingAll.

Generators: re/vectors/gen_pipeline_syncpred.py (re/out/e2e_syncpred), re/vectors/gen_pipeline_commit.py
(re/out/e2e_commit), both over the e2e corpus gestures.

## Decisions with ablation or probe evidence (2026-10-20)

- Moopad::addReleasePoints (release-point drawing, random palette colour): stubbed, 31/31
  identical. Moopad::onSitTouchSpacebar: stubbed, the 3 commit gestures identical (text ops too).
- PeckModule::onSitTouchRegister, addReleasePoints, onSitTouchSpacebar: marked ui in
  inventory/overrides.json by the coordinator.
- PeckModule::onSitTouchRegister: stubbed in the oracle, all 31 decodes (candidates, text, text
  ops) identical. Only its unique-id consumption is kept (the records stage sees ids).
- maybeRetireSlidingMootouch: returns false on every corpus call and writes only
  Moopad+0x29958..0x29a07, which nothing reads.
- SpaceCompleterHolderTouchHandler claim / pass filter in sitTouch: UI (SCOPE.md ablation).
- syncBottomSpacingness / markActualCurrentKey in sitTouch act on the live object after the
  sitting copy is made, so they do not reach decoder state.

Ported and verified so far: the clocks, TouchInfo, obtainTouch / Mootouch(), the DOWN / MOVE / UP
paths with SaveableTouch (all five writers), imbueStartingArea, the force calls (touch's ports),
sitTouch (earliest-live scan, tap-release routing: tapRelease / canPerformAuxSimpleTapRelease,
the space-commit path up to forceMergeAllBuffers), registerSittingTouch (finger-collision
checks, syncVisitationPoint, resyncSittingTouchesStartBasedSortation), updateMergeBuffers'
orchestration (group range, erase, sweep marking with Moopad+0x766, dead-buffer cleanup, group
counter reset, merge-all structural reset), clearSittingTouchIslands, forceMergeAllBuffers,
turnOnTempAutospace, turnOffCaps and the updateAutospaceTimedMerge wiring (only with Xnl 0x7fd8).

AutospaceModule: the merge ports take it as its own image, so NintypeEngine keeps a working copy
that `withAutospace { am -> }` refreshes from `pad` before each call and writes back after it.
