package com.runie.state.migrations;

import java.util.Collections;
import java.util.List;

/**
 * Registered production migrations, ascending by {@link StateMigration#fromVersion()}.
 * v1→v2: the Egg rework ({@link V1ToV2EggMigration}) — legacy Runie-Token
 * balances convert to eggs at 5:1 (floor). The machinery itself is additionally
 * exercised by unit tests (RunieStateStoreTest) with synthetic migrations
 * (architecture §4.3).
 */
public final class StateMigrations
{
	public static final List<StateMigration> PRODUCTION =
		Collections.singletonList(new V1ToV2EggMigration());

	private StateMigrations()
	{
	}
}
