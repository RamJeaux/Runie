package com.runie.support;

import com.runie.core.RunieClock;

/** Settable clock for deterministic economy tests. */
public class FakeClock implements RunieClock
{
	private long nowMs;
	private long elapsedMs;

	public FakeClock(long startMs)
	{
		this.nowMs = startMs;
	}

	@Override
	public long nowMs()
	{
		return nowMs;
	}

	@Override
	public long elapsedMs()
	{
		return elapsedMs;
	}

	public void setNowMs(long nowMs)
	{
		this.nowMs = nowMs;
	}

	public void advanceMs(long ms)
	{
		nowMs += ms;
		elapsedMs += ms;
	}

	public void advanceDays(int days)
	{
		advanceMs(days * 86_400_000L);
	}
}
