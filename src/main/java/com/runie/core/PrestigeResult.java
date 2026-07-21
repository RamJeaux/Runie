package com.runie.core;

/**
 * Outcome of {@link ProgressionService#prestige(String)}. Prestige is
 * IRREVERSIBLE and single-tier by design — there is deliberately no API to
 * undo it or to prestige a second time.
 */
public enum PrestigeResult
{
	SUCCESS,
	/** No state loaded, or the document is read-only (newer schema). */
	NOT_READY,
	/** The creature id is not in the collection. */
	NOT_OWNED,
	/** Eligibility is level 99 — the creature is not there yet. */
	NOT_LEVEL_99,
	/** One prestige tier only: already prestiged, no second reset path. */
	ALREADY_PRESTIGED
}
