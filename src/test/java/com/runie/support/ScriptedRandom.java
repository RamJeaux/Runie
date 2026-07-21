package com.runie.support;

import com.runie.core.RunieRandom;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Deterministic RNG test double with separate queues for {@code nextDouble}
 * (tier sampling) and {@code nextInt} (creature selection). When a queue is
 * empty, defaults are returned (0.0 / 0), which sample the FIRST option — a
 * rigged always-Common / always-first-creature stream.
 */
public class ScriptedRandom implements RunieRandom
{
	private final Deque<Double> doubles = new ArrayDeque<>();
	private final Deque<Integer> ints = new ArrayDeque<>();

	public ScriptedRandom queueDoubles(double... values)
	{
		for (double v : values)
		{
			doubles.add(v);
		}
		return this;
	}

	public ScriptedRandom queueInts(int... values)
	{
		for (int v : values)
		{
			ints.add(v);
		}
		return this;
	}

	@Override
	public double nextDouble()
	{
		Double v = doubles.poll();
		return v == null ? 0.0 : v;
	}

	@Override
	public int nextInt(int bound)
	{
		Integer v = ints.poll();
		return v == null ? 0 : Math.min(v, bound - 1);
	}
}
