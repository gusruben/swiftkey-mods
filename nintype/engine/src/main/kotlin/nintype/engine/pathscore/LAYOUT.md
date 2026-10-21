# pathscore struct layouts

Offsets the pathscore ports read or write, with what each field does. Names are inferred from
use. The offsets are what the vectors check. Status as of 2026-10-20.

## PredPath (sizeof 0x208)

| Off | Type | Meaning |
|---|---|---|
| 0x0c | i32 | Poolable refcount: a Refcounter copy does ++, clear() does --, and at 0 the path is destroyed (ctor sets 0) |
| 0x10 | PredPathDataPart | starts here (ctor) |
| 0x14 | i32 | parent id (the root path: its own id) |
| 0x18 | i32 | id (PathSearch+0x53c++ at creation). `isChildOf(o)` is `o.0x18 == this.0x14`, i.e. this is o's child |
| 0x1c | Refcounter\<PendingWord\> | pending-word finisher (never set on a fresh install) |
| 0x20, 0x24 | TWRet | finisher word {TrieWord id, TrieWord*}; live when +0x24 != 0 |
| 0x2a | i8 | pathsize (letters so far) |
| 0x2b | u8 | chars fed to the cursor: advance() always does +1, advanceSkipping / advanceApostrophied +1 per successful char. Subtracted in calculateNotLongEnoughMultpen |
| 0x2c | i8 | skipped-char count (advanceSkipping; multForCharSkip input; penalty reason 2) |
| 0x2d | i8 | same-point revisits over the last 4 steps (syncPointRevisitOn) |
| 0x2e | i8 | same-key (point+0x308) revisits over the last 4 steps |
| 0x2f | u16 | flags, see the bit list below |
| 0x34 | i8 | variant: apostrophe insertions (advanceApostrophied +1). Heuristic factor: 0 gives 1, 1 gives 0.96, 2 gives 0.8, else 0.6. Penalty reason 0 when >= 1 |
| 0x38 | f32 | soft index consumed so far (calculateNotLongEnoughMultpen) |
| 0x40..0x58 | f64 x4 | times (ctor: PS+0x168; 0x58 = PS+0x168 - 2?) |
| 0x68 / 0x6c | f32 | additive gains / penalties (research doc A/B) |
| 0x70 | f32 | running minimum score (updateHeuristicsSubscore) |
| 0x74 | f32 | score: the heuristic H (determineScoreForHeuristics), later the final score. Used by `operator<` and maybePrune |
| 0x78 | f32 | heuristic multiplier |
| 0x7c / 0x80 | i32 / f32 | count / sum. The mean multiplies the score |
| 0x84 / 0x88 | i32 / f32 | count / sum. The mean multiplies the score |
| 0x8c | i32 | visit count: +1 per markVisited, +2 per visited neighbour |
| 0x90 | f32 | visit weight: +1 per visit, +2*relief per visited neighbour |
| 0x94 | i32 | neighbour-visit count |
| 0x98 | f32 | novisit accumulator (updateVisitation multiplies it) |
| 0x9c | f32 | novisit mult = 0x98^e (syncBalancedNovisitMult) |
| 0xac | f32 | smooth-visitation mult (syncSmoothVisitation) |
| 0xb0 | f32 | backtravel mult (penalty reason 5 below 0.85) |
| 0xbc | f32 | not-long-enough multpen (syncNotLongEnoughMultpen) |
| 0xcc | f32 | future-coverage mult (syncFutureCoverageHeuristics sets 1) |
| 0xd4 | f32 | point-revisit mult (syncPointRevisitMult) |
| 0xd8 | WeightedFloats | classic slideVS accumulator |
| 0xe0 | f32 | classic slideVS mult = mean(0xd8)^3.8 |
| 0xe4 | WeightedFloats | neo slideVS accumulator |
| 0xec | f32 | neo slideVS mult = mean(0xe4)^3.6 |
| 0xf0 | Flag64 | visited set, indexed by visitation index |
| 0xf8 | MooTouchPoint* | last visited point (justVisited) |
| 0xfc | f32 | word prior (README; determineFinalScore) |
| 0x108 | vector\<PredHistory\> | steps, 0x28 bytes each (below) |
| 0x114, 0x118 | i32 | must be <= 3 / <= 5 for canAdvanceEx to gather skip-to chars |
| 0x140 | i32 | novisit count (updateVisitation; penalty reason 3 at >= 3) |
| 0x144 | f32 | skip-score mult = multForCharSkip(...)^1.7 |
| 0x148 | PathSearch* | owning search |
| 0x14c..0x174 | CombinedTriePath | trie cursor (trie area) |
| 0x174 | i8 | last finger (0 = none). lastfing/lastotherfing use it |
| 0x178 | FingerPathData | finger 1 (`fing(1)`, `otherfing(2)`) |
| 0x1c0 | FingerPathData | finger 2 |

Flag bits of the u16 at 0x2f:

| Bit | Meaning |
|---|---|
| 0x4 | cleared by determineScoreForHeuristics, set by determineFinalScore |
| 0x8 | doubled letter on one point |
| 0x10 | penalty reason 1 |
| 0x20 | apostrophe open, expecting "'" (cleared by syncApostate) |
| 0x40 | apostrophe ending: getWord appends "'", getWordLength +1 |
| 0x80 | with 0x40, cannot finish yet |
| 0x100 | possessive "'s": getWord appends "'s", getWordLength +2, canProgress false, canFinish true |
| 0x60 with PS+0x160 != 0 | canProgress true without the cursor |

## FingerPathData (0x48 bytes, at PredPath+0x178 / +0x1c0)

| Off | Type | Meaning |
|---|---|---|
| 0x10 | u8 | flags (ctor clears bits 0-2). Bit 1 picks a determineSlideDiffs branch |
| 0x11 | u8 | finger id (1 / 2) |
| 0x13 | u8 | touch index (ctor 0xff). calcSlideDiff compares the two fingers' values |
| 0x15 | u8 | last char (doubled-letter vowel test) |
| 0x20 | Point2D | last position (calcSlideDiff P0/P1) |
| 0x28 | i8 | visitation cursor: the next visitation index this finger may consume |
| 0x30, 0x38 | f64 | last start / end times (ctor: PS+0x168) |
| 0x40 | MooTouchPoint* | last point |

## PredHistory (step, 0x28 bytes, PredPath+0x108 elements)

| Off | Type | Meaning |
|---|---|---|
| 0x0c | i32 | char |
| 0x14 | u8 | touch index |
| 0x15 | u8 | finger |
| 0x18 | MooTouchPoint* | the point the letter maps to |
| 0x1e | u16 | flags. Bit 0 excludes the step from syncLastTwoForSlideVS. Bit 3 = important, bit 4 = pseudo-important (markVisited sets bit 4) |
| 0x20 | Point2D | key centre |

## SlideVSArg (0x7c bytes)

| Off | Type | Meaning |
|---|---|---|
| 0x00, 0x01 | u8 | char of the earlier letter, char of the new letter |
| 0x04 | Point2D | slide origin (earlier letter's point position) |
| 0x0c | Point2D | origin + diffA |
| 0x14 | Point2D | origin + diffB |
| 0x1c | Point2D | diffA: observed travel |
| 0x24 | Point2D | diffB: alternative travel (determineSlideDiffs) |
| 0x2c | Point2D | key A (earlier) |
| 0x34 | Point2D | key B (new) |
| 0x3c | Point2D | key diff = B - A |
| 0x44 | u8 | steps back (historic neo) |
| 0x48 | f64 | time (historic neo; ctor 0) |
| 0x50 | u8 | neo flag |
| 0x54 | f32 | weight (ctor 1; neo scales it by {0.4->0.1, 1->0.7, 1.5->1}(unitDist(diffA))) |
| 0x58 | f32 | dU = \|diffA\| / unit |
| 0x5c | f32 | best cosine |
| 0x60 | f32 | theta (deg) |
| 0x64 | f32 | tolerance (deg) |
| 0x68 | f32 | effective theta |
| 0x6c | f32 | length score |
| 0x70 | f32 | cos^2.3 |
| 0x74 | f32 | signed cos(theta_eff)^2.3 |
| 0x78, 0x79 | u8 | same finger, same touch (historic neo) |

## RecentSlideInfo (>= 0x28 bytes)

| Off | Type | Meaning |
|---|---|---|
| 0x00 | u8 | has two |
| 0x04 | Point2D | last diff |
| 0x0c | Point2D | last position |
| 0x14 | Point2D | previous diff |
| 0x1c | Point2D | previous position |
| 0x24 | f32 | cos(previous diff, last diff), only when both are > 5 px |

## SlideVSTarget

| Off | Type | Meaning |
|---|---|---|
| 0x00 | MooTouchPoint* | target point |
| 0x04 | f32 | weight |

## Small helpers

- WeightedFloats (8 bytes): +0 sumW, +4 sumWV.
  - `add(v, w)` adds w to sumW and v*w to sumWV.
  - `mean()` is sumWV/sumW, or 1 when sumW < 0.001.
  - `mean(a, b)` takes the weight first.
- Flag64 (8 bytes): a u64 bitset. Bit tests follow ARM register-shift semantics: negative or >= 64 indices test nothing, and >= 64 also asserts.
- FunnelStat (12 bytes): +0 total, +4 succ, +8 fail1. `succ()` and `fail1()` clamp their counter to >= 0 and return it by reference.
- WordAlternative:
  - +0x30 flags (bit 4 copies PS+0x330);
  - +0x3c penalty reason, written only when the priority beats +0x3d (never updated itself);
  - +0x40 f32.
- StepScoreStats: +0x20 skip-branch count. The rest is in search/StepScoreStats.kt.

## BranchmentData (canAdvanceEx)

| Off | Type | Meaning |
|---|---|---|
| 0x08 | i32 | char |
| 0x20..0x22 | u8 | flags in |
| 0x23 | u8 | out: cursor can take the char |
| 0x24 | u8 | out: apostrophe then char |
| 0x25 | u8 | out: closes an apostrophe / takes the possessive 's |
| 0x28 | vector\<char\> | skip-to chars |

## Fields of other objects read here

PathSearch:

| Off | Type | Meaning |
|---|---|---|
| 0x110 | Moopad* | |
| 0x678 / 0x67c / 0x680 | i32 | canAdvanceEx calls / skip checks / skip branches |
| 0xe74 | std::map\<CombinedTriePath, CanSkipToCache\> | skip-checker caches (search/SkipChecker.kt) |
| 0xea8 | vector\<StepScoreStats\> | step stats (search/StepScoreStats.kt) |
| 0x144 | f32 | keypoint count (research doc n) |
| 0x160 | i32 | |
| 0x168 | f64 | gesture t0 |
| 0x178 | f64 | last time (updateVisitationComplete uses +3 s) |
| 0x1bc | vector\<MooTouchPoint*\> | visitation points, indexed by point+0x308 |
| 0x294 | CurveMapper | time to soft index |
| 0x330 | u8 | disables the same-key revisit factor; copied to WA flags |
| 0x344 | f32 | WPM |
| 0x35a | u8 | literal-tap mode |
| 0x364 | f32 | novisit exponent base |

MooTouchPoint (beyond emkey/MooTouchPoint.kt):

| Off | Type | Meaning |
|---|---|---|
| 0x0c | MooTouchPoint* | visitation representative, used when 0x34d = 0 |
| 0x14 / 0x18 | MooTouchPoint* | next / previous visitation neighbour |
| 0xb8, 0xc0 | f64 | times compared by getFuturePlanarCoveragePoints |
| 0xd0 | u8 | |
| 0x138 | f64 | start-ish time |
| 0x148 | f64 | tEnd |
| 0x16c | f32 | |
| 0x22c | f32 | PPSCK curve input (detached points) |
| 0x304 / 0x308 | i32 | visitation index (0x308 when 0x34d is set) |
| 0x354 | f32 | novisit penalty |
| 0x35c | f32 | relief at start points |
| 0x360 | f32 | relief toward next |
| 0x364 | f32 | relief toward previous |
| 0x368 / 0x36c | f32 | neighbour reliefs for markVisited |
| 0x370 | f32 | visit strength |
| 0x225 bit 2 (flag 0x400) | | not penalisable in syncSmoothVisitation |
| 0x225 bit 3 (flag 0x800) | | start point |

Moopad:

| Off | Type | Meaning |
|---|---|---|
| 0x1f4a8 | f32 | key unit |
| 0x7fa4 | Xnl | double-letter setting |
