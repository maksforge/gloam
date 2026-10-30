# Closing the controls leaves a bubble, and its size is what bounds it

## Context

Putting the controls away over a running shade left nothing on screen to bring them back but the
notification, which is a pull and a tap away. The user asked for a chat-head-shaped answer: the controls
shrink to a small circle that can be dragged anywhere, tapped to reopen, and dropped on an ✕ to remove.

That is a **third touchable window above the shade**, and unlike the panel it has no idle timeout:
it is meant to stay.

## Decision

- **The bubble is a child of the shade's lifetime, exactly as the panel is** (ADR-0011 rule 2).
  `ShadeService` owns it; `onDestroy` takes it down. There is no bubble while nothing is dimming,
  so it needs no service of its own and no second notification.
- **Two ways of closing leave it, the rest do not.** A tap outside the panel and Back or a tap
  outside the compact controls are the user turning back to what they were reading. The ✕, the
  idle timeout, the cog and one host replacing the other close for good.
- **Its size is the bound, as the panel's width is.** A square window of explicit pixels,
  `BUBBLE_SIZE_DP`, capped at a quarter of the display's shorter side by `bubbleSidePx`.
- **At rest it is tucked: only `BUBBLE_PEEK_DP` of it is on screen**, the rest past the display's
  edge, which `FLAG_LAYOUT_NO_LIMITS` allows. So the bubble covers less of the app below than any
  window of ours but the ✕. The peek is wider than the ~21dp strip HyperOS keeps for the back
  gesture; taps and drags started inside that strip were both read on the phone and reach the
  bubble.
- **A release throws it into the nearer edge** (`tuckToEdge`): the finger's velocity is projected
  to choose the side and handed to a spring as its starting speed. Every drag position and every
  spring frame is clamped to `bubbleDragArea`, which keeps at least the peek on screen and the
  whole height inside the system bars; a rotation re-tucks it at once.
- **Docked, it changes shape**: a circle while held or flying, and as it arrives a "D" flat and
  square against the border, round on the inside. The morph is animated sizes with a bouncy spring,
  so each is clamped at zero — an overshoot to a negative corner radius threw inside Compose and,
  in the shade's own process, took the shade down (read on the phone 2026-10-11).
  The morph is also clamped above, and undocking is instant rather than sprung: a frame
  recording showed the flat edge bouncing inside the display, and a tab still flat while a finger
  carried it away, both as a gap at the border. Near the ✕ the bubble jumps
  onto it, so a drop is never a near miss. `BubbleBoundsTest` sweeps the clamp and the tuck.
- **`FLAG_NOT_FOCUSABLE`, without `FLAG_WATCH_OUTSIDE_TOUCH`.** A tap elsewhere is what produced
  the bubble; it cannot also be what removes it.
- **The ✕ is a window of its own and is untouchable**, added before the bubble so the bubble is
  dropped onto it rather than under it, and invisible until a drag starts.
- **One preference turns it off, `controls_bubble`, on by default.** The first time the bubble is
  thrown away a prompt asks whether to keep it; `controls_bubble_asked` makes that once per install.
  The prompt is a window above the shade, shaped like the panel — not focusable, closed by a tap
  outside and by `PANEL_IDLE_TIMEOUT_MS` — because an Activity would be under the shade.
- **The position is held in memory, not stored.** It is only meaningful on this display, and
  every DataStore key is backed up to the next phone.

## Alternatives

**A timeout, like the panel's.** It would bound the bubble in time as well as in space, and it
would make it a worse notification: the point is that it is there when the user reaches for it.
A 39dp sliver of a window resting on a side edge, and can drag elsewhere or throw away, is a smaller
cost than the panel's 30 seconds of a bar down one edge.

**A system Bubble (`Notification.BubbleMetadata`).** It is the platform's own floating head and
would cost no overlay window, but it opens an Activity, and every Activity is under the shade.

**Showing the bubble while dimming is off too.** That would keep a foreground service and its
notification alive only to hold the bubble. Asked and declined on 2026-10-11.

## Consequences

- **Four windows of ours can be up at once**: the shade, the ✕, the bubble and the prompt. Only
  the bubble and the prompt catch touches; the panel never coexists with the bubble.
- **The bubble has no timeout**, so a layout bug that drew it invisibly would leave a small dead
  patch until the shade comes down. Its size and the clamp are what keep that patch small and on
  screen.
- The ✕'s drop radius is the target's width, generous on purpose; nothing measured it yet.

Amendment, 2026-10-11. **The claim about HyperOS's edge strip above was read over the home screen
only, and it does not hold over apps.** On the 1.2.0 build from the internal track, with Chrome in
front, a tap 40px from the right edge went to `GestureStubRight` and never reached the bubble: the
strip (62px, ~21dp, on this phone) is a system window above every `TYPE_APPLICATION_OVERLAY`, and
the launcher simply does not raise it. A 39dp peek therefore left about 13dp of the docked tab
touchable in any app. `BUBBLE_PEEK_DP` is now 50dp, which leaves about 24dp clear of the strip; the
tab still docks flush to the border, it is only wider. The strip's width follows the phone's gesture
settings, so this is a margin for the default, not a guarantee.

Amendment, 2026-10-30. **A start from the Quick Settings tile leaves the bubble too.** The tile turns
the shade on with nothing of Gloam's on screen, so the user had to find the notification to reach the
controls. That is the same problem this ADR solved for closing the controls. Starting from the app
and from the schedule still leave no bubble: the app is already in front, and a scheduled start
happens while nobody is looking.
