package com.runie.creatures;

import com.runie.core.Economy;

/**
 * Creature rarity tiers. Odds / refund values are sourced from {@link Economy}
 * (single source of truth for all economy numbers) — this enum only indexes them.
 */
public enum Rarity
{
	COMMON("Common"),
	UNCOMMON("Uncommon"),
	RARE("Rare"),
	ELITE("Elite"),
	MASTER("Master"),
	GRANDMASTER("Grandmaster");

	private final String displayName;

	Rarity(String displayName)
	{
		this.displayName = displayName;
	}

	/** Tier index into the Economy odds/shard arrays (C=0 .. GM=5). */
	public int tierIndex()
	{
		return ordinal();
	}

	public double baseOdds()
	{
		return Economy.BASE_ODDS[ordinal()];
	}

	public String getDisplayName()
	{
		return displayName;
	}
}
