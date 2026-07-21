package com.runie.core;

/** Where an Egg credit came from (outside the XP tier accrual). */
public enum EggSource
{
	/** Starter welcome grant (3 free hatches, §9.3). */
	STARTER,
	/** Evolution reward: +1 egg per stage crossing, once per crossing ever. */
	EVOLUTION,
	/** Golden L120 mastery milestone: +5 eggs, once per creature ever. */
	GOLDEN_MILESTONE
}
