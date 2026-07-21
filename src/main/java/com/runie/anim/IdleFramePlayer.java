package com.runie.anim;

/**
 * Pure ping-pong playback math for pre-rendered idle frame sequences
 * (architecture §6). The idle art is a real animation now — each creature /
 * style / stage ships {@code idle/frame_000..frame_0NN.png} — so the companion
 * simply plays those frames back and forth:
 *
 * <pre>0, 1, …, N-1, N-2, …, 1, 0, 1, …</pre>
 *
 * <p>Everything here is a pure function of elapsed MILLISECONDS — never client
 * ticks — so playback speed is independent of client framerate (§6.2), and the
 * render thread does arithmetic only. The frame rate is
 * {@link com.runie.assets.AssetLoader#IDLE_FPS} (via each clip's
 * {@code perFrameMs}); change that one constant to retime every idle.
 *
 * <p>Single-frame sequences (most of the roster until their loops are
 * generated) resolve to index 0 — a static sprite. When multi-frame art drops
 * into a creature's {@code idle/} folder later, it starts animating with no
 * code change.
 */
public final class IdleFramePlayer
{
	/**
	 * Window for the per-creature phase offset (ms). Offsets are spread across
	 * this window so different companions never play in lockstep; it comfortably
	 * exceeds any idle loop period (16 frames @ 12fps ping-pong ≈ 2.5s).
	 */
	static final int PHASE_WINDOW_MS = 10_000;

	private IdleFramePlayer()
	{
	}

	/**
	 * Ping-pong frame index for {@code elapsedMs} into a {@code frameCount}
	 * sequence at {@code perFrameMs} per frame.
	 *
	 * <p>The loop period is {@code 2 * (frameCount - 1)} steps, so the two end
	 * frames are held exactly one step each — no double-frame stutter at the
	 * turnarounds and no wrap-jump from the last frame back to the first.
	 * {@code frameCount <= 1} is always index 0 (static sprite).
	 */
	public static int frameIndex(int frameCount, long elapsedMs, int perFrameMs)
	{
		if (frameCount <= 1)
		{
			return 0;
		}
		if (elapsedMs < 0)
		{
			elapsedMs = 0;
		}
		long step = elapsedMs / perFrameMs;
		int period = 2 * (frameCount - 1);
		int p = (int) (step % period);
		return p < frameCount ? p : period - p;
	}

	/**
	 * Deterministic per-creature phase offset (ms), seeded from the creature id
	 * hash. Added to elapsed time before indexing so companions of different
	 * creatures aren't perfectly synced; the same creature always gets the same
	 * offset (stable across sessions and refreshes).
	 */
	public static long phaseOffsetMs(String creatureId)
	{
		if (creatureId == null)
		{
			return 0;
		}
		return Math.floorMod(creatureId.hashCode(), PHASE_WINDOW_MS);
	}
}
