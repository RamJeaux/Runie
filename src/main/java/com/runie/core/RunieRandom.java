package com.runie.core;

import java.security.SecureRandom;
import java.util.SplittableRandom;

/**
 * Seedable RNG abstraction (architecture §2). GachaService (Epic 5) and any other
 * randomness consume this interface only, so tests can bind a fixed seed and
 * fixture-assert exact sequences.
 */
public interface RunieRandom
{
	double nextDouble();

	/** Uniform int in {@code [0, bound)}. */
	int nextInt(int bound);

	/** Production instance: SecureRandom-seeded SplittableRandom. */
	static RunieRandom secure()
	{
		return seeded(new SecureRandom().nextLong());
	}

	/** Deterministic instance for tests and reproducible fixtures. */
	static RunieRandom seeded(long seed)
	{
		SplittableRandom r = new SplittableRandom(seed);
		return new RunieRandom()
		{
			@Override
			public double nextDouble()
			{
				return r.nextDouble();
			}

			@Override
			public int nextInt(int bound)
			{
				return r.nextInt(bound);
			}
		};
	}
}
