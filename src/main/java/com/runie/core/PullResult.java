package com.runie.core;

import com.runie.creatures.Rarity;

/**
 * Rich result of a single Hatch (architecture §9.2). Immutable; consumed
 * by the hatch-reveal overlay (Epic 6) and the panel hatch tab (Epic 7).
 */
public final class PullResult
{
	/** Pity counters AFTER the pull — the panel's pity meters read this. */
	public static final class PitySnapshot
	{
		public final int rarePity;
		public final int elitePity;
		public final int gmPity;
		public final int totalPulls;
		public final int starterPullsRemaining;

		public PitySnapshot(int rarePity, int elitePity, int gmPity, int totalPulls, int starterPullsRemaining)
		{
			this.rarePity = rarePity;
			this.elitePity = elitePity;
			this.gmPity = gmPity;
			this.totalPulls = totalPulls;
			this.starterPullsRemaining = starterPullsRemaining;
		}
	}

	public final String creatureId;
	public final Rarity rarity;
	/** True = first copy (creature added to collection); false = duplicate. */
	public final boolean isNew;
	/** True when first-copy protection rerolled a duplicate to this creature. */
	public final boolean rerolled;
	/** Star count AFTER this hatch (0 for a first copy; capped at 25). */
	public final int starsAfter;
	/** True when THIS duplicate's shiny roll succeeded (the shiny moment). */
	public final boolean becameShiny;
	/** True when the creature is shiny after this hatch (new or pre-existing). */
	public final boolean shiny;
	/** True when this pull consumed a starter (welcome) hatch. */
	public final boolean starterPull;
	public final PitySnapshot pity;

	public PullResult(String creatureId, Rarity rarity, boolean isNew, boolean rerolled,
		int starsAfter, boolean becameShiny, boolean shiny, boolean starterPull, PitySnapshot pity)
	{
		this.creatureId = creatureId;
		this.rarity = rarity;
		this.isNew = isNew;
		this.rerolled = rerolled;
		this.starsAfter = starsAfter;
		this.becameShiny = becameShiny;
		this.shiny = shiny;
		this.starterPull = starterPull;
		this.pity = pity;
	}
}
