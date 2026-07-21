package com.runie.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Duplicate-star tier math (rework part 3) and shiny rate selection (part 4):
 * count → (tier color, stars-in-tier 1–5), caps at 25, and the per-tier shiny
 * ladder incl. the post-Pink 1.0% rate.
 */
public class StarTierTest
{
	@Test
	public void zeroStarsHasNoTier()
	{
		assertNull(StarTier.tierFor(0));
		assertNull(StarTier.tierFor(-3));
		assertEquals(0, StarTier.starsInTier(0));
	}

	@Test
	public void tierBoundariesAreFivesBronzeToPink()
	{
		assertEquals(StarTier.BRONZE, StarTier.tierFor(1));
		assertEquals(StarTier.BRONZE, StarTier.tierFor(5));
		assertEquals(StarTier.SILVER, StarTier.tierFor(6));
		assertEquals(StarTier.SILVER, StarTier.tierFor(10));
		assertEquals(StarTier.GOLD, StarTier.tierFor(11));
		assertEquals(StarTier.GOLD, StarTier.tierFor(15));
		assertEquals(StarTier.RED, StarTier.tierFor(16));
		assertEquals(StarTier.RED, StarTier.tierFor(20));
		assertEquals(StarTier.PINK, StarTier.tierFor(21));
		assertEquals(StarTier.PINK, StarTier.tierFor(25));
	}

	@Test
	public void starsInTierCycleOneToFive()
	{
		assertEquals(1, StarTier.starsInTier(1));
		assertEquals(5, StarTier.starsInTier(5));
		assertEquals(1, StarTier.starsInTier(6));
		assertEquals(3, StarTier.starsInTier(13));
		assertEquals(5, StarTier.starsInTier(25));
	}

	@Test
	public void beyondTheCapClampsToFullPink()
	{
		assertEquals(25, Economy.MAX_STARS);
		assertEquals(StarTier.PINK, StarTier.tierFor(26));
		assertEquals(StarTier.PINK, StarTier.tierFor(9_999));
		assertEquals(5, StarTier.starsInTier(26));
		assertEquals(5, StarTier.starsInTier(9_999));
	}

	@Test
	public void shinyRateFollowsTheTierBeforeTheDupe()
	{
		// 0 stars (first dupe) rolls at the Bronze rate
		assertEquals(0.0010, Economy.shinyRateForStars(0), 1e-12);
		assertEquals(0.0010, Economy.shinyRateForStars(1), 1e-12);
		assertEquals(0.0010, Economy.shinyRateForStars(5), 1e-12);
		assertEquals(0.0020, Economy.shinyRateForStars(6), 1e-12);
		assertEquals(0.0020, Economy.shinyRateForStars(10), 1e-12);
		assertEquals(0.0035, Economy.shinyRateForStars(11), 1e-12);
		assertEquals(0.0050, Economy.shinyRateForStars(16), 1e-12);
		assertEquals(0.0075, Economy.shinyRateForStars(21), 1e-12);
		assertEquals(0.0075, Economy.shinyRateForStars(24), 1e-12);
		// at 25 stars (already maxed BEFORE the dupe): post-Pink 1.0%
		assertEquals(0.0100, Economy.shinyRateForStars(25), 1e-12);
		assertEquals(0.0100, Economy.shinyRateForStars(40), 1e-12);
	}
}
