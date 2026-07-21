package com.runie.support;

import com.runie.core.RunieClock;
import com.runie.state.RunieAccountState;
import com.runie.state.RunieStateStore;

/**
 * Settable clock with the SAME persisted monotonic-watermark guard as the
 * production {@code GuardedClock} ({@code System.currentTimeMillis()} replaced
 * by a settable base). Lets tests simulate a user rolling the system clock
 * back/forward and assert the watermark clamps it.
 */
public class GuardedFakeClock implements RunieClock
{
	private final RunieStateStore stateStore;
	private long systemMs;

	public GuardedFakeClock(RunieStateStore stateStore, long startMs)
	{
		this.stateStore = stateStore;
		this.systemMs = startMs;
	}

	/** Simulates setting the OS clock (backwards allowed — that's the point). */
	public void setSystemMs(long ms)
	{
		this.systemMs = ms;
	}

	public void advanceMs(long ms)
	{
		this.systemMs += ms;
	}

	@Override
	public long nowMs()
	{
		if (!stateStore.hasState())
		{
			return systemMs;
		}
		RunieAccountState state = stateStore.getState();
		long wm = state.clockWatermarkMs;
		long now = Math.max(systemMs, wm);
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
		return systemMs;
	}
}
