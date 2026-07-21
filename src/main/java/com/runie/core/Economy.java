package com.runie.core;

/**
 * ALL economy numbers live here — nowhere else (architecture §16.8).
 *
 * <p>Single currency: <b>Eggs</b>, earned from XP through daily-reset tier
 * costs (the k-th egg earned in a UTC day costs {@link #eggCostXp}); there is
 * NO daily cap — past the 8th egg every further egg that day costs the flat
 * steady rate. Hatching a creature costs {@link #HATCH_COST_EGGS}.
 *
 * <p>Duplicates no longer refund currency: each duplicate hatch adds one Star
 * ({@link StarTier} — Bronze→Silver→Gold→Red→Pink, five stars per tier, capped
 * at {@link #MAX_STARS}) and rolls a Shiny chance scaled by the creature's star
 * tier BEFORE the duplicate ({@link #shinyRateForStars}).
 *
 * <p>Legacy: the pre-rework currency was Runie Tokens (CrT); conceptually
 * {@link #LEGACY_TOKENS_PER_EGG} old tokens = 1 egg (the v1→v2 state migration
 * converts balances with floor division). Any tuning change must re-run the
 * unit-test suite.
 */
public final class Economy
{
	private Economy()
	{
	}

	// ------------------------------------------------------------------
	// Eggs: daily-reset tier accrual — NO daily cap
	// ------------------------------------------------------------------
	/**
	 * XP cost of the k-th egg earned today (k is 1-based): eggs 1–3 cost 50k XP
	 * each, 4–5 cost 100k, 6–8 cost 250k. THE single tunable ladder — edit here
	 * only; {@link #EGG_STEADY_COST_XP} covers every k beyond this array.
	 */
	public static final long[] EGG_TIER_COSTS_XP = {
		50_000L, 50_000L, 50_000L,      // eggs 1–3
		100_000L, 100_000L,             // eggs 4–5
		250_000L, 250_000L, 250_000L,   // eggs 6–8
	};
	/** XP cost of every egg from the 9th onward, forever that day (no cap). */
	public static final long EGG_STEADY_COST_XP = 500_000L;

	/** Hatching (summoning) a creature costs one egg. */
	public static final int HATCH_COST_EGGS = 1;

	/** Legacy Runie-Token conversion: 5 old tokens = 1 egg (floor, v1→v2). */
	public static final int LEGACY_TOKENS_PER_EGG = 5;

	/**
	 * XP cost of the k-th egg earned today. {@code k} is 1-based; any
	 * non-positive k is treated as 1 (defensive).
	 */
	public static long eggCostXp(int k)
	{
		if (k <= 0)
		{
			k = 1;
		}
		return k <= EGG_TIER_COSTS_XP.length ? EGG_TIER_COSTS_XP[k - 1] : EGG_STEADY_COST_XP;
	}

	// ------------------------------------------------------------------
	// Duplicate Stars & Shiny
	// ------------------------------------------------------------------
	/** Stars per color tier: Bronze 1–5, Silver 6–10, Gold 11–15, Red 16–20, Pink 21–25. */
	public static final int STARS_PER_TIER = 5;
	/** Star progression cap (5 Pink). Dupes beyond this only feed shiny rolls. */
	public static final int MAX_STARS = 25;

	/**
	 * Shiny chance per duplicate, indexed by the creature's star tier BEFORE the
	 * duplicate: Bronze .10%, Silver .20%, Gold .35%, Red .50%, Pink .75%.
	 */
	public static final double[] SHINY_RATE_BY_TIER = {0.0010, 0.0020, 0.0035, 0.0050, 0.0075};
	/** Shiny chance once already at {@link #MAX_STARS} (post-Pink): 1.00%. */
	public static final double SHINY_RATE_POST_PINK = 0.0100;

	/**
	 * Shiny roll rate for a duplicate given the creature's star count BEFORE
	 * this duplicate. 0 stars rolls at the Bronze rate; {@link #MAX_STARS}+
	 * rolls at the post-Pink rate.
	 */
	public static double shinyRateForStars(int starsBeforeDupe)
	{
		if (starsBeforeDupe >= MAX_STARS)
		{
			return SHINY_RATE_POST_PINK;
		}
		StarTier tier = StarTier.tierFor(Math.max(1, starsBeforeDupe));
		return SHINY_RATE_BY_TIER[tier.ordinal()];
	}

	// ------------------------------------------------------------------
	// Evolution reward
	// ------------------------------------------------------------------
	/** Eggs granted per evolution crossing (p1→p2, p2→p3), once per crossing ever. */
	public static final int EVOLUTION_REWARD_EGGS = 1;

	/** Eggs granted when a creature reaches the golden L120 mastery milestone (once per creature ever). */
	public static final int GOLDEN_MILESTONE_REWARD_EGGS = 5;

	// ------------------------------------------------------------------
	// Summon odds (constants only here — GachaService implements) — sim §B
	// ------------------------------------------------------------------
	/** Base tier odds: C, U, R, E, M, GM. */
	public static final double[] BASE_ODDS = {0.630, 0.230, 0.090, 0.035, 0.011, 0.004};
	/** GM soft-pity odds: GM 0.4% → 1.2%, difference taken from Common only. */
	public static final double[] GM_SOFT_ODDS = {0.622, 0.230, 0.090, 0.035, 0.011, 0.012};
	public static final int RARE_PITY_N = 10;
	public static final int ELITE_PITY_N = 30;
	public static final int GM_SOFT_AT = 150;
	public static final int GM_HARD_AT = 300;
	public static final int FIRST_COPY_PROTECTION_PULLS = 3;
	public static final int STARTER_PULLS = 3;
}
