package com.runie.core;

import com.runie.state.RunieAccountState;
import com.runie.state.RunieStateStore;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Production {@link RunieClock}: wall clock guarded by a persisted monotonic
 * watermark (architecture §11). {@link #nowMs()} never returns a value earlier
 * than anything already recorded, so rolling the system clock back can neither
 * replay token days nor un-expire forge capacity. Setting the clock forward
 * burns capacity early, but the advanced watermark then prevents reclaiming it
 * — both failure modes are anti-exploit-safe.
 */
@Singleton
public class GuardedClock implements RunieClock
{
	private final RunieStateStore stateStore;

	@Inject
	public GuardedClock(RunieStateStore stateStore)
	{
		this.stateStore = stateStore;
	}

	@Override
	public long nowMs()
	{
		long sys = System.currentTimeMillis();
		if (!stateStore.hasState())
		{
			return sys;
		}
		RunieAccountState state = stateStore.getState();
		long wm = state.clockWatermarkMs;
		long now = Math.max(sys, wm);
		if (now > wm)
		{
			state.clockWatermarkMs = now;
			stateStore.markDirtyLow();
		}
		return now;
	}

	@Override
	public long elapsedMs()
	{
		return System.nanoTime() / 1_000_000;
	}
}
