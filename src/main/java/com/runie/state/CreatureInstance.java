package com.runie.state;

import com.runie.core.Economy;

/**
 * Owned-creature state, nested inside {@link RunieAccountState} (architecture §4.2).
 * {@code xp} is current-prestige XP (reset on prestige); {@code lifetimeXp} is
 * preserved across prestige per spec.
 *
 * <p>Rework fields (all default-safe for legacy documents): {@code stars} —
 * duplicate-star count (0..{@link Economy#MAX_STARS}); {@code shiny} — runtime
 * shiny flag (render effect, no per-creature art); {@code evolutionRewardedStage}
 * — the highest evolution stage whose +1-egg reward has already been granted
 * (idempotency across reloads/re-checks).
 */
public class CreatureInstance
{
	public long acquiredAtMs;
	public long xp;
	public long lifetimeXp;
	public int prestigeCount;
	public int copiesPulled;
	/** Duplicate stars (rework part 3): +1 per dupe hatch, capped at 25. */
	public int stars;
	/** Runtime shiny flag (rework part 4): once true, never rolled again. */
	public boolean shiny;
	/** Highest evolution stage already rewarded with +1 egg (grant-once guard). */
	public int evolutionRewardedStage;
	/**
	 * True once the golden L120 mastery milestone's +5-egg reward has been
	 * granted (grant-once guard; defaults false for legacy documents). Set only
	 * on an actual upward L120 crossing — a legacy creature already at 120 is
	 * never retroactively granted (its level can no longer cross the cap).
	 */
	public boolean goldenRewardGranted;

	public static CreatureInstance fresh(long nowMs)
	{
		CreatureInstance c = new CreatureInstance();
		c.acquiredAtMs = nowMs;
		c.xp = 0;
		c.lifetimeXp = 0;
		c.prestigeCount = 0;
		c.copiesPulled = 1;
		c.stars = 0;
		c.shiny = false;
		c.evolutionRewardedStage = 0;
		c.goldenRewardGranted = false;
		return c;
	}

	public boolean isPrestiged()
	{
		return prestigeCount > 0;
	}
}
