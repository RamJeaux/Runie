package com.runie;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Bug 5 root cause: {@code nowMs - lastCombatMs < WINDOW} overflowed when
 * {@code lastCombatMs} still held the {@code Long.MIN_VALUE} "never hit"
 * sentinel, evaluating TRUE — the danger flag stayed stuck on from plugin
 * start, so the hatch reveal was deferred every frame while logged in and only
 * slipped out around logout. The window check must treat the sentinel as
 * "not in combat".
 */
public class RuniePluginDangerTest
{
	private static final long NOW = 1_784_721_600_000L;

	@Test
	public void sentinelNeverHitMeansNotInCombat_noOverflow()
	{
		// the old expression: NOW - Long.MIN_VALUE overflows negative → was "true"
		assertTrue("precondition: the naive check really does overflow",
			NOW - Long.MIN_VALUE < 10_000);
		assertFalse(RuniePlugin.withinCombatWindow(NOW, Long.MIN_VALUE));
	}

	@Test
	public void recentHitIsInsideTheWindow()
	{
		assertTrue(RuniePlugin.withinCombatWindow(NOW, NOW - 1));
		assertTrue(RuniePlugin.withinCombatWindow(NOW, NOW - 9_999));
	}

	@Test
	public void oldHitIsOutsideTheWindow()
	{
		assertFalse(RuniePlugin.withinCombatWindow(NOW, NOW - 10_000));
		assertFalse(RuniePlugin.withinCombatWindow(NOW, NOW - 86_400_000L));
	}
}
