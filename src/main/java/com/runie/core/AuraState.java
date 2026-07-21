package com.runie.core;

/**
 * Prestige aura presentation state (architecture §5.1: auras are composited
 * overlay clips — {@code aura_prestige} / {@code aura_gold}). Derived, never
 * persisted: {@code prestigeCount} + level fully determine it, so it can never
 * desync from the creature's real progression.
 */
public enum AuraState
{
	/** Not prestiged: no aura layer. */
	NONE,
	/** Prestiged, below the level-120 cap: faint prestige aura. */
	PRESTIGE,
	/** Prestiged AND level 120 (fully mastered): golden aura. */
	GOLDEN
}
