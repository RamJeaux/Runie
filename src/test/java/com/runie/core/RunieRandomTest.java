package com.runie.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Seedable RNG: identical seeds → identical sequences (gacha fixtures depend on this). */
public class RunieRandomTest
{
	@Test
	public void sameSeedSameSequence()
	{
		RunieRandom a = RunieRandom.seeded(20260717L);
		RunieRandom b = RunieRandom.seeded(20260717L);
		for (int i = 0; i < 1000; i++)
		{
			assertEquals(a.nextDouble(), b.nextDouble(), 0.0);
			assertEquals(a.nextInt(17), b.nextInt(17));
		}
	}

	@Test
	public void boundsRespected()
	{
		RunieRandom r = RunieRandom.seeded(42L);
		for (int i = 0; i < 10_000; i++)
		{
			double d = r.nextDouble();
			assertTrue(d >= 0.0 && d < 1.0);
			int n = r.nextInt(6);
			assertTrue(n >= 0 && n < 6);
		}
	}

	@Test
	public void differentSeedsDiverge()
	{
		RunieRandom a = RunieRandom.seeded(1L);
		RunieRandom b = RunieRandom.seeded(2L);
		boolean diverged = false;
		for (int i = 0; i < 100 && !diverged; i++)
		{
			diverged = a.nextInt(1_000_000) != b.nextInt(1_000_000);
		}
		assertTrue(diverged);
	}
}
