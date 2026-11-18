# Ballon — balloon climbing game (design + plan)

## Spec

An original Android arcade game. A hot air balloon climbs endlessly; the player drags a finger
to steer it left/right, collects bubbles for points, avoids spiked bars, and can grab a magnet
that pulls bubbles in. One hit ends the run. Best score is saved. Works offline.
The look must be original and colourful (sky gradient, clouds, coloured balloon) — NOT a
grey-on-white pixel-art look.

## Global Constraints

- Kotlin, native Android, no third-party runtime dependencies (framework `Activity`, `View`, `Canvas` only).
- Package `com.ballon.game`; game rules live in `com.ballon.game.core` and must not import `android.*`.
- minSdk 24, compileSdk 35. Portrait only.
- Build with `./gradlew assembleDebug`; unit tests with `./gradlew testDebugUnitTest`. Both must pass.
- World units: width is always 100f; height = 100f * (viewHeight / viewWidth). y grows downward.
- Do not touch the `.mp4` file or `README.md`.
- Commit on the current branch; do not push.

## Task 1: Game core (pure Kotlin, TDD)

Files: `app/src/main/java/com/ballon/game/core/*.kt`, tests in `app/src/test/java/com/ballon/game/core/`.

Public API (exact names; the UI task depends on them):

```kotlin
enum class Phase { READY, PLAYING, PAUSED, GAME_OVER }
enum class BarSize(val width: Float) { SHORT(14f), MEDIUM(26f), LONG(40f) }
class Bubble(var x: Float, var y: Float)            // centre; radius Game.BUBBLE_R = 2f
class SpikeBar(val x: Float, var y: Float, val size: BarSize) // x = left edge, y = top; height Game.BAR_H = 3f (incl. spikes)
class Magnet(var x: Float, var y: Float)            // centre; radius Game.MAGNET_R = 3f

class Game(val worldHeight: Float, private val random: kotlin.random.Random, bestScore: Int = 0) {
    val worldWidth: Float                 // 100f
    val phase: Phase                      // starts READY
    val score: Int
    val bestScore: Int
    val balloonX: Float                   // centre of envelope, starts 50f
    val balloonY: Float                   // fixed: worldHeight * 0.6f
    val balloonTilt: Float                // degrees; 0 while alive, 25f after a crash
    val bubbles: List<Bubble>
    val bars: List<SpikeBar>
    val magnets: List<Magnet>
    val magnetTimeLeft: Float             // seconds, 0 when inactive
    val scrollSpeed: Float                // units/second
    val distance: Float                   // total units scrolled this run
    fun start()                           // READY or GAME_OVER -> PLAYING, resets run (keeps bestScore)
    fun steerTo(x: Float)                 // sets target x (clamped); ignored unless PLAYING
    fun pause()                           // PLAYING -> PAUSED
    fun resume()                          // PAUSED -> PLAYING
    fun update(dt: Float)                 // advances only when PLAYING; dt clamped to 0.05f
}
```

Rules:

- Balloon shape for collisions: envelope circle radius `BALLOON_R = 4f` at (balloonX, balloonY) plus
  basket rect 3f wide × 3f tall centred horizontally, its top at balloonY + 5.5f.
- Steering: each update `balloonX += (target - balloonX) * min(1f, 12f * dt)`, clamped to [4f, 96f].
- Scrolling: everything moves down by `scrollSpeed * dt`. `scrollSpeed` = min(75f, 30f + 0.6f * secondsPlayed).
- In READY phase the world is populated (so the start screen shows a scene) but nothing moves.
- Spawning: content is generated in rows above the top edge as distance accumulates, one row every 14f
  units. A row is either a bubble formation (2–5 bubbles: line, diagonal, or pair — all inside [4f, 96f]),
  a spike bar (random size, fully inside the world), or bubbles plus a bar that do not overlap.
  No two bars may be closer than 18f vertically. Nothing spawns within 30f above the balloon at run start.
  A magnet spawns once every 700f units of distance at a random x in [10f, 90f], not overlapping a bar.
- Objects are removed once fully below `worldHeight`.
- Bubble touching the balloon (circle/circle or circle/rect): removed, score += 10.
- A bar whose top edge passes below the basket bottom without a hit: score += 1 (once per bar).
- Magnet touching the balloon: removed, `magnetTimeLeft = 6f`. While active, bubbles within 30f of the
  balloon centre move toward it at 80f units/s (in addition to scrolling). Timer counts down in update.
- Bar rect overlapping the balloon shape: phase = GAME_OVER, balloonTilt = 25f, bestScore = max(bestScore, score).
- Same `Random` seed ⇒ identical run (deterministic).

Tests must cover: phase transitions, steering + clamping, scroll speed ramp and cap, bubble scoring,
bar pass scoring, crash → GAME_OVER + best score, magnet attraction and expiry, spawn invariants
(simulate 120 s with several seeds, steering irrelevant — assert bar spacing ≥ 18f and all objects inside
horizontal bounds at spawn), determinism, dt clamp, restart resets run but keeps best.

## Task 2: Android UI — rendering, input, screens, saved best score, icon

Files: `app/src/main/java/com/ballon/game/` (`MainActivity.kt`, `GameView.kt`, `Renderer.kt`, others as needed),
`app/src/main/res/`, tests in `app/src/test/java/com/ballon/game/`.

- `GameView` (custom `View`): creates `Game` once its size is known, runs a frame loop with
  `Choreographer` (dt from frame times), calls `game.update(dt)` and invalidates. Stops when detached / activity paused
  (activity `onPause` must pause a PLAYING game).
- Input: while PLAYING, touch down/move anywhere calls `steerTo(touchX in world units)`; draw a soft
  translucent ring at the finger. Tap targets: Start button (READY), pause icon top-right (PLAYING),
  Resume (PAUSED), Replay and Quit (GAME_OVER; Quit finishes the activity).
- `Renderer` draws everything with `Canvas` in world units scaled to the view; no bitmaps required:
  - vertical sky gradient that shifts gradually (day blue → sunset → dusk → night with stars → loops) with `game.distance`;
  - 2 layers of soft parallax clouds scrolling slower than the world;
  - balloon: striped, warm-coloured envelope with basket and ropes, gentle sway, rotated by `balloonTilt`;
  - bubbles: translucent with rim and highlight; spike bars: solid beam with downward spikes in a colour
    that contrasts with every sky phase; magnet: red horseshoe with white tips; a glow ring around the
    balloon while `magnetTimeLeft > 0`;
  - HUD: score top-left, `BEST n` beside it, pause icon top-right; respects display cutout / status bar insets;
  - READY: title "BALLON", hint "Drag to steer", START button; PAUSED: dim + "PAUSED" + RESUME;
    GAME_OVER: dim + "GAME OVER" + score + "NEW BEST!" when beaten + REPLAY + QUIT.
- Best score persisted in `SharedPreferences` (`"ballon"`, key `"best"`), loaded into `Game(bestScore=…)`, saved on game over.
- Immersive: draw edge-to-edge, keep screen on while playing.
- Adaptive launcher icon (vector foreground of the balloon on a sky-blue background), set in the manifest.
- Verification: add Robolectric (`testImplementation("org.robolectric:robolectric:4.14.1")`) and a test using
  `@GraphicsMode(GraphicsMode.Mode.NATIVE)` that renders `Renderer` for READY, PLAYING (after ~5 s simulated with
  seed 1), PLAYING at night with magnet active, and GAME_OVER into 1080×2340 bitmaps saved as PNGs under
  `app/build/screenshots/`. Look at the PNGs yourself and fix visual defects (overlaps, clipped text,
  unreadable contrast) before reporting. Also test the best-score store round-trip.
