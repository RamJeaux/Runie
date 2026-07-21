package com.runie.core;

/**
 * Narrow seam over {@code Client.getAccountHash()} so XpDeltaService is unit-testable
 * without a RuneLite client (architecture §0.5). Production binding:
 * {@link ClientAccountContext}.
 */
public interface AccountContext
{
	long NO_ACCOUNT = -1L;

	/** RuneLite's stable per-account hash, or {@link #NO_ACCOUNT} when unavailable. */
	long accountHash();
}
