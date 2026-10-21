# Decoder pipeline: per-event control flow

What runs, in order, from the JNI entry points down to `MooMergeBuffer::update` and the result
listing. The final Kotlin engine API mirrors this: `onTouchEvent(pid, action, x, y, tMs)` and
`step(tMs)`, with the decode happening synchronously inside the event that lifts the last
pointer.

Source: call traces of the oracle (`re/vectors/merge_ext.py trace`, 2026-10-20, v2 snapshot):
a single-thumb `the` swipe, a two-thumb `hello` (60 ms overlap) and `the` with autospace on.
Nesting comes from the stack pointer at each traced entry. Only exported `Emkey::`, NINLib and
KeyboardPage functions are traced, so an untraced frame between two traced ones (such as
`Roen::Trigger::fire`) disappears from the tree. Function addresses are Ghidra addresses.
Drawing and sound calls are left out.

## 0. Threading and timing (from re/README.md, "Timing")

- The GL thread drains the touch queue each frame. It makes one `NINLib.onTouchEvent` per
  queued event, then one `NINLib.step()`.
- MOVEs arrive at most once per pointer per frame, all stamped with the same
  `System.currentTimeMillis()`.
- Engine time is seconds as `double` (`RoenGL::globaltimestamp`, set by `syncTiming` and each
  call). Touch times inside Mootouch are seconds too.

## 1. `NINLib.onTouchEvent(pid, action, x, y, pressure, size, tMs)`

```
Java_com_jormy_nin_NINLib_onTouchEvent
  KeyboardPage::handleTouchEvent(TouchInfo)      (seen on DOWN; on later events the trace shows
    Moopad::handleTouchEvent(TouchInfo)           Moopad::handleTouchEvent directly, a tail call)
      UI filters, each of which can swallow the touch:
        Interactute::maybeHandleTouchFromMoopad (tutorial), EmojiCrown, the bottom bar,
        SpaceCompleter holder, maybeLaunchMenu, MooBoardSwitchHandler
      Moopad::findKey(x, y) -> MooBoard::findKey -> findKeyEx -> Mookey::findTest
      Moopad::handleMooTouch(TouchInfo)          <- the decoder path starts here
```

On UP, `Moopad::handleTouchEvent` runs a second time at the JNI level after the first
returns. It does only key lookups and has no decoder effect.

### 1a. DOWN (action 0)

```
Moopad::handleMooTouch
  AutospaceModule::newPressShouldAutomerge(t, pos, n, &pos)    @00ee6470
    Mookey::containsWithExtraIgnoringOHM x2 (MooBoard+0xfc4 extra 3, MooBoard+0xfac extra 1:
        a press on either special key never auto-merges)
    MooBoard::findKey(pos); a key with +0xc9c <= 0 -> 0
    AutospaceModule::automergeAllowanceCheck(t, pos, n, tap, ...)  @00ee60c8
        -> 1 = word boundary: the open buffers are force-merged (committed) before this touch
  Moopad::obtainTouch(pid, pos) -> new Mootouch (Moopad::syncMooTouchForces)
  Mootouch::record(pos, false)        first sample (subdivides long segments, see re/README.md)
  SaveableTouch::record               (decoder state: markReleased matches its records to set
                                       MooTouchPoint+0x17c and calcDepartureVelocity reads them;
                                       see pipeline/SaveableTouch.kt for every writer)
  Moopad::imbueStartingArea(Mootouch&)   (start key, row and edge flags)
  Mootouch::syncForceKeyEligibility, takeForceTouchUpdate
```

### 1b. MOVE (action 1)

```
Moopad::handleMooTouch
  Moopad::obtainTouch(pid, pos)       (the existing Mootouch)
  Mootouch::takeForceTouchUpdate
  Mootouch::record(pos, false)        once per ~2.5 to 3.3 board units of travel, all at the event time
  SaveableTouch::record               (one per Mootouch::record when +0xb5; interpolated force and time)
  Moopad::maybeRetireSlidingMootouch(Mootouch&)   (slide gestures: cursor, delete, ...; also per step)
```

### 1c. UP (action 2)

```
Moopad::handleMooTouch
  Moopad::performMooTouchRelease(TouchInfo)
    Moopad::obtainTouch
    Mootouch::record(pos, false)
    Mootouch::markReleased()                                   <- touch analysis
      finalizeSlownessDetection
      Moopad::genTraversal(MooTouchPoint&) -> addTraversal(MooTouchPoint&, Mookey&) per crossed key
      calcDepartureVelocity
      insertArtificialVisitationPointsAndGetRidOfUnimportantPoints
      syncDampeningState
      Moopad::genTraversal per surviving keypoint
    SaveableTouch::record                                      (after markReleased, when +0xb5)
    Moopad::sitTouch(Mootouch&, bool, TouchInfo const*)        @008f6508
      Mootouch::getStartpos, isSimpleTap, getUnitDisplacement (+ space and shortcut tap checks)
      AutospaceModule::newTouchShouldAutomerge(mt, t, pos, n)  @00ee6588
        Mootouch::isSimpleTap; automergeAllowanceCheck
        == 1 -> Moopad::forceMergeAllBuffers (commit the open word), turnOnTempAutospace,
                addSpacebarRelease (unless USF 0x144)
      Moopad::registerSittingTouch(Mootouch&, bool)
        Mootouch::checkFingerCollisionDetachmentPointsFromAnotherTouch (vs every sitting touch)
        Mootouch::syncVisitationPoint
        Moopad::resyncSittingTouchesStartBasedSortation
        (the touch is copied into the sitting list, std::list<Mootouch> at Moopad+0x294c4)
      Moopad::onSitTouchRegister, PeckModule::onSitTouchRegister (peck/literal-tap mode)
      Mootouch::syncBottomSpacingness, markActualCurrentKey
      Moopad::expireTwoFingChaser
      if (!bool arg && no pointer still down):                 <- the decode
        Moopad::updateMergeBuffers(reason)                     @0067a698
    Moopad::addReleasePoints
```

A lift while another pointer is still down runs `sitTouch` without `updateMergeBuffers`. In
the two-thumb trace, the two inner lifts sit their touches, and only the final lift decodes.

### 1d. `Moopad::updateMergeBuffers` @0067a698 (the decode)

```
(lo, hi) = min / max of Mootouch+0xd8 (merge index) over the sitting list (Moopad+0x294c4)
for k in lo..hi:                                     (one buffer for a normal word)
    buf = Moopad::obtainMergeBuffer(k)               (std::map<int, MooMergeBuffer> at Moopad+0x29d3c)
    MooMergeBuffer::update(buf, hi, first = (k == lo), true, moopad)
drop buffers whose key is outside [lo, hi]
first buffer: MooMergeBuffer::markSweepMaster(count - 1); every other: markDeadAfter(-1)
Moopad::syncPredictedText(reason)                    <- result listing, see 1f
```

### 1e. `MooMergeBuffer::update(int last, bool first, bool full, Moopad*)` @0089f4cc

The full pseudocode is in the `re/vectors/gen_merge_update.py` docstring. In outline:

```
a static "last update time" double := RoenGL::globaltimestamp when |now - it| > 0.01 s (0x3f847ae1_40000000)
if dead (+0x3c) or (!first and (finished (+0x3d) or Moopad+0x766)) -> return
    (with !first and Moopad+0x766 set, it also marks the buffer dead)
target = (full && !first) ? last : last - 1
pad results up to target: one empty PathResult + ACCollectage per missing touch index
loop (while lastTouch (+0x38) < last, or == last when the Context has "...", a debug re-run):
    PathSearch::reset(PS = Moopad+0x29dc0, startTouch (+0x34))     -> resetEx: gatherTouches,
                                                                     coverage, trie roots ...
    finished = (startTouch == lastTouch + 1 && !Moopad+0x2a110); if !first && finished: break
    lastTouch++
    Moopad+0x2a2bc = 3; PathSearch::syncBucketStepAllowance(f)
    PathSearch::performSteps(f2iz(E*150 + (1-E)*45)), E = Moopad+0x12ac   (the argument is dead;
                                                  split searches pass E*80 + (1-E)*45)
    PathSearch::listResults(7); top = PathSearch::topResult()
    push ACCollectage(moopad); if Xnl Moopad+0x2b45c: PathSearch::gatherAutocomplete(ac, top)
    split re-searches, each = reset* (pre half) + performSteps + topResult, reset* (post half) +
    performSteps + topResult, then PathResult::mergeFromSpaceSplitters(top, pre, post, w, idx):
      1. near-space tap: needs !Xnl Moopad+0x800c, Xnl Moopad+0xb44, top score < 7.4,
         !Moopad+0x2a11a and Moopad+0x2affc < 3; idx = PathSearch::mootouchIndexOfNearSpaceTapper
         (-1 = none); resetForPrePostcut; w = 0.55
      2. timed autospace: needs Xnl Moopad+0x7fd8 == 1 and the USF/Xnl gates;
         AutospaceModule::syncAutospaceStatsPostSearch -> AutospaceSplitoffStats::shouldSplit;
         idx = stats.splitTouch, w = stats.score; resetForAutospaceSplitSearch;
         an early split (PathResult flag) also calls AutospaceModule::onDetectEarlySplit
      3. possessive 's: top score < 7.4, no earlier split, USF 0xb1 off, Moopad+0x2affc < 3;
         idx = PathSearch::mootouchIndexOfApostropheEnder; w = 0.55
    insert (-score, resultIndex) into the multimap (+0x1c); push the PathResult (+0x0);
    maxScore (+0x30) = max(maxScore, score)
until dead
```

With the boot defaults (autospace off, Moopad+0x7fd8 = 0), split 2 never runs. Splits 1 and 3
are checked on every update; in the default traces both index lookups return -1, so no split
search runs.

### 1f. `Moopad::syncPredictedText` (result listing; Moopad ui-glue, not ported here)

```
MooMergeBuffer::getSweepChoice(&i, &t, &wa, &alts, &results, &ac, &isLast)   @0089eda4
    i = sweepMaster, walking back while results[i].score (+0x2c) <= 0.5 or
        <= 0.2 * results[i-1].score -> MooMergeBuffer::returnSweepChoice(i, ...) copies them out
MooMergeBuffer::getMostRecentAutoCompleteBuffer(ac)
ACCollectage isEmpty / hasLeftCandidates / hasRightCandidates; loadEdgeternsFromAC
MooMergeBuffer::getExplicitResult -> mootouchlist_getExplicitResult (literal tapped letters)
Moopad::filterInvalids, spliceAutoComplete, spliceWildGuesses, spliceCustomScoring,
filterRepeatingResults, performNeoMarkov (context rerank), spliceCapsToExplicit,
spliceWeirdCaps, maybeTransform, spliceSmartQuotes, syncBackspaceRetypePenalties,
setCurrentWA, spliceThiccAutocompleteSlots
propagateLiquidToClient  -> the "markliquid" text op (what the user sees as the typed word)
```

The decoder's ranked candidates are `PathSearch` results +0x638 as left by the last
`listResults` (see `driver.Engine.results`). `syncPredictedText` is ranking, casing and UI on
top of those.

## 2. `NINLib.step()` (once per frame, after the frame's events)

```
Java_com_jormy_nin_NINLib_step
  KeyboardPage::updateState, updateMoopad
    Moopad::update()
      Moopad::updateCurrentStyleKeyboard (UI)
      Roen::Trigger::fire (Moopad+0x390, the per-frame trigger): with autospace on this calls
        AutospaceModule::updateAutospaceTimedMerge()   @00ee66c8 (gated by slide mode and USF
        flags; when elapsed = now - lastUp - 0.14 > absoluteAutospaceTimeLimit it finishes the
        word via Moopad::maybeFinishWord("autospace_timed"))
      for each down Mootouch: Moopad::maybeRetireSlidingMootouch(Mootouch&)
      for each down Mootouch:
        Mootouch::update()      record(current pos, flag 1) + velocity / slowdown detection
        SaveableTouch::recordUpdate (inlined): a frame record (flag +0x14 = 1, the last
                                record's pos, +0x98, frame factor and dt)
      Moopad::syncMooTouchForces, wpmcounter_update
```

With the defaults (autospace off), `step` never decodes and never commits. In the
autospace-on trace, `updateAutospaceTimedMerge` ran on every frame after the lift without
committing within the 0.9 s settle window. Its gating is ported (merge/AutospaceModule.kt,
`updateAutospaceTimedMerge`):

- The dynMult ChasingFloat (AM+0x330) recovers toward 1.0 every frame (frac 0.01,
  dt = RoenGL::globallastdtimefactor, speed limit 0.004).
- Nothing else happens while a pointer is down (Moopad+0x29440), with no sitting touches, in a
  menu or suspension state, or outside slide mode.
- Peck typing vetoes the timed commit and sets the peck lock: by default this happens once the
  word has 4 or more sitting touches (settings_autospace_pause_minpecks), and also in a few UI
  states.
- Otherwise, once `now - lastUp - 0.14 > touchStayTime`, it calls
  `Moopad::maybeFinishWord` and `Moopad::turnOnTempAutospace`. That is the autospace commit.

## 3. What the Kotlin engine has to reproduce, per call

| JNI call | Decoder work | Merge-area functions |
|---|---|---|
| onTouchEvent DOWN | new Mootouch, first record | newPressShouldAutomerge, automergeAllowanceCheck (word boundary before the touch) |
| onTouchEvent MOVE | Mootouch::record (subdivided) | none |
| onTouchEvent UP, others still down | markReleased, sitTouch, registerSittingTouch | newTouchShouldAutomerge, automergeAllowanceCheck |
| onTouchEvent UP, last pointer | the above, then updateMergeBuffers | MooMergeBuffer::update (search, splits), markSweepMaster / markDeadAfter, then getSweepChoice / returnSweepChoice / getMostRecentAutoCompleteBuffer / getExplicitResult in syncPredictedText |
| step | Mootouch::update per down touch | updateAutospaceTimedMerge (autospace on only) |

Proposed Kotlin API, mirroring the above:

```kotlin
class Engine {
    fun onTouchEvent(pid: Int, action: Int, x: Float, y: Float, tMs: Long)  // decode happens inside the last UP
    fun step(tMs: Long)
    val candidates: List<Candidate>   // PathSearch results after the last MooMergeBuffer::update
    val liquidText: String?           // what syncPredictedText would propagate
}
```

## 4. Moopad offsets used along this path

| Offset | Type | Meaning |
|---|---|---|
| 0x766 | u8 | "stop merging" flag: update bails out (and marks dead) for non-first buffers |
| 0x7c0 | Xnl | read by update before listResults (debug print switch) |
| 0x7f9c | i32 | settings_autospace_timed_millsecs (250) |
| 0x7fd8 | Xnl | timed autospace active (gates automergeAllowanceCheck and split 2) |
| 0x800c, 0xb44 | Xnl | near-space split gates (0x800c off and 0xb44 on) |
| 0x8074 | AutospaceModule | embedded; its +0x2fc = Moopad+0x8370 is the AutospaceSplitoffStats |
| 0x12ac | f32 | step-count blend E (1.0); performSteps ignores the result |
| 0x137c + n*0x34 | Xnl | USF flag n (`hasUSF1`) |
| 0x1f1ec | MooBoard* | board |
| 0x294c4 | std::list<Mootouch> | sitting touches of the open word (node + 8 = Mootouch) |
| 0x29aa4 | Xnl | slide mode (`slidemodeActive`) |
| 0x29d3c | std::map<int, MooMergeBuffer> | merge buffers by first touch index |
| 0x29dc0 | PathSearch | the one search object |
| 0x2a110 | u8 | when 0, a buffer whose search reached its start is marked finished |
| 0x2a11a | u8 | disables the near-space split |
| 0x2a2bc | i32 | set to 3 before every search (bucket mode) |
| 0x2affc | i32 | split budget gate (< 3 allows splits 1 and 3) |
| 0x2b45c | Xnl | autocomplete gathering on |
| 0x2b530 | f64 | time of the last lift (s) |
| 0x2b538 | Point2D | position of the last lift |
