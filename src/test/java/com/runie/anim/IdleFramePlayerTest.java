package com.runie.anim;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * IdleFramePlayer: pure ping-pong playback math for the pre-rendered idle
 * sequences — index correctness and continuity over time, single-frame static
 * fallback, and the deterministic per-creature phase offset.
 */
public class IdleFramePlayerTest
{
	private static final int PER = 1000 / 12; // 83ms — the IDLE_FPS frame period

	@Test
	public void singleFrameIsAlwaysIndexZero()
	{
		assertEquals(0, IdleFramePlayer.frameIndex(1, 0, PER));
		assertEquals(0, IdleFramePlayer.frameIndex(1, 999_999_999L, PER));
		assertEquals(0, IdleFramePlayer.frameIndex(0, 12_345, PER)); // degenerate: still safe
	}

	@Test
	public void twoFramesAlternate()
	{
		// period is 2 steps: 0,1,0,1,…
		int[] expected = {0, 1, 0, 1, 0, 1};
		for (int step = 0; step < expected.length; step++)
		{
			assertEquals(expected[step], IdleFramePlayer.frameIndex(2, (long) step * PER, PER));
		}
	}

	@Test
	public void pingPongSequenceIsExactOverFullCycles()
	{
		// N=4 → 0,1,2,3,2,1 then repeats — the wrap goes THROUGH 0, never jumps
		int[] expected = {0, 1, 2, 3, 2, 1, 0, 1, 2, 3, 2, 1, 0};
		for (int step = 0; step < expected.length; step++)
		{
			assertEquals("step " + step, expected[step],
				IdleFramePlayer.frameIndex(4, (long) step * PER, PER));
		}
	}

	@Test
	public void indexHoldsSteadyWithinAFramePeriod()
	{
		// sub-frame times inside one step all map to the same index
		assertEquals(1, IdleFramePlayer.frameIndex(16, PER, PER));
		assertEquals(1, IdleFramePlayer.frameIndex(16, PER + 1, PER));
		assertEquals(1, IdleFramePlayer.frameIndex(16, 2L * PER - 1, PER));
		assertEquals(2, IdleFramePlayer.frameIndex(16, 2L * PER, PER));
	}

	@Test
	public void playbackIsContinuousNoIndexJumps()
	{
		// sample every ms across several full loops: adjacent samples never
		// differ by more than one frame — seamless at both turnarounds
		int n = 16;
		long cycleMs = (long) PER * (2 * (n - 1));
		int prev = IdleFramePlayer.frameIndex(n, 0, PER);
		for (long t = 1; t <= 3 * cycleMs; t++)
		{
			int idx = IdleFramePlayer.frameIndex(n, t, PER);
			assertTrue("jump at t=" + t + ": " + prev + " -> " + idx, Math.abs(idx - prev) <= 1);
			assertTrue(idx >= 0 && idx < n);
			prev = idx;
		}
	}

	@Test
	public void bothEndFramesAreHeldExactlyOneStep()
	{
		// N=3, period 4: 0,1,2,1 — no doubled frame at either end
		int n = 3;
		int[] expected = {0, 1, 2, 1, 0, 1, 2, 1};
		for (int step = 0; step < expected.length; step++)
		{
			assertEquals(expected[step], IdleFramePlayer.frameIndex(n, (long) step * PER, PER));
		}
	}

	@Test
	public void negativeElapsedClampsToZero()
	{
		assertEquals(0, IdleFramePlayer.frameIndex(16, -1, PER));
		assertEquals(0, IdleFramePlayer.frameIndex(16, Long.MIN_VALUE + 1, PER));
	}

	@Test
	public void phaseOffsetIsDeterministicPerCreature()
	{
		assertEquals(IdleFramePlayer.phaseOffsetMs("grubnak"), IdleFramePlayer.phaseOffsetMs("grubnak"));
		assertEquals(IdleFramePlayer.phaseOffsetMs("cindermaw"), IdleFramePlayer.phaseOffsetMs("cindermaw"));
		// different creatures land on different phases (de-synced companions)
		assertNotEquals(IdleFramePlayer.phaseOffsetMs("grubnak"), IdleFramePlayer.phaseOffsetMs("cindermaw"));
		assertEquals(0, IdleFramePlayer.phaseOffsetMs(null));
	}

	@Test
	public void phaseOffsetStaysInsideItsWindow()
	{
		String[] ids = {"grubnak", "cindermaw", "malgrave", "skitterscour", "cluckabee", "vornathax"};
		for (String id : ids)
		{
			long off = IdleFramePlayer.phaseOffsetMs(id);
			assertTrue(id, off >= 0 && off < IdleFramePlayer.PHASE_WINDOW_MS);
		}
	}
}
