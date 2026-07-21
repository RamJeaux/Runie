package com.runie.state;

import com.google.gson.Gson;
import com.runie.creatures.CreatureRegistry;
import com.runie.state.migrations.StateMigration;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/** Test access to RunieStateStore's package-private test-seam constructor. */
public final class TestStateStores
{
	private TestStateStores()
	{
	}

	public static RunieStateStore create(Gson gson, ScheduledExecutorService executor,
		CreatureRegistry registry, Path dir)
	{
		return new RunieStateStore(gson, executor, registry, dir,
			Collections.emptyList(), RunieAccountState.CURRENT_SCHEMA_VERSION);
	}

	public static RunieStateStore create(Gson gson, ScheduledExecutorService executor,
		CreatureRegistry registry, Path dir, List<StateMigration> migrations, int currentSchemaVersion)
	{
		return new RunieStateStore(gson, executor, registry, dir, migrations, currentSchemaVersion);
	}
}
