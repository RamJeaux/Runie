package com.runie.core;

/**
 * Injectable time source (architecture §2, §11).
 *
 * <ul>
 *   <li>{@link #nowMs()} — wall clock for economy decisions (day rollover, forge
 *       window). The production implementation ({@link GuardedClock}) is
 *       monotonic-guarded: it never returns a value earlier than the persisted
 *       watermark, so rolling the system clock back cannot replay token days or
 *       un-expire forge capacity.</li>
 *   <li>{@link #elapsedMs()} — {@code System.nanoTime()}-derived, for animation
 *       only; unrelated to wall time.</li>
 * </ul>
 */
public interface RunieClock
{
	long nowMs();

	long elapsedMs();
}
