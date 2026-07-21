package com.runie.state.migrations;

import com.google.gson.JsonObject;

/**
 * One schema-migration step, applied to the raw JSON tree before POJO binding
 * (architecture §4.3). Migrations run in order until the document reaches
 * {@code RunieAccountState.CURRENT_SCHEMA_VERSION}; each step is responsible for
 * bumping the {@code schemaVersion} field it produces.
 */
public interface StateMigration
{
	/** This step applies to documents at exactly this version (→ fromVersion + 1). */
	int fromVersion();

	/** Mutate {@code root} in place, including setting {@code schemaVersion} to fromVersion + 1. */
	void apply(JsonObject root);
}
