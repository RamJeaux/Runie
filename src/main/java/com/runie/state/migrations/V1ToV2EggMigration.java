package com.runie.state.migrations;

import com.google.gson.JsonObject;
import com.runie.core.Economy;

/**
 * Schema v1 → v2: the Egg rework (gameplay rework part 1).
 *
 * <p>Replaces the v1 {@code tokens} section (Runie Tokens / CrT) with the v2
 * {@code eggs} section:
 * <ul>
 *   <li>{@code eggs.balance = floor(tokens.crt / 5)} — remainder discarded by
 *       design ({@link Economy#LEGACY_TOKENS_PER_EGG}).</li>
 *   <li>{@code eggs.lifetimeEarned = floor(tokens.lifetimeCrtEarned / 5)}.</li>
 *   <li>The day-key ({@code currentDayUtc}) carries over so the daily ladder
 *       anchors to the same day mechanism the old cap used.</li>
 *   <li>Today's ladder restarts clean ({@code earnedToday = 0},
 *       {@code xpBanked = 0}) — old band progress does not map onto the new
 *       tier ladder, and under-granting a partial egg is the safe direction.</li>
 *   <li>Removed v1 keys (dailyXpCounted, crtEarnedTodayMilli,
 *       bankedAllowanceMilli, crt, lifetimeCrtEarned) are dropped with the
 *       whole {@code tokens} section.</li>
 * </ul>
 *
 * <p>Defensive: a document with a missing/absent tokens section still migrates
 * (fresh eggs section) — migration must NEVER be the reason a document is
 * quarantined.
 */
public final class V1ToV2EggMigration implements StateMigration
{
	@Override
	public int fromVersion()
	{
		return 1;
	}

	@Override
	public void apply(JsonObject root)
	{
		JsonObject eggs = new JsonObject();
		long balance = 0;
		long lifetime = 0;
		String dayKey = null;

		if (root.has("tokens") && root.get("tokens").isJsonObject())
		{
			JsonObject tokens = root.getAsJsonObject("tokens");
			balance = readLong(tokens, "crt") / Economy.LEGACY_TOKENS_PER_EGG;
			lifetime = readLong(tokens, "lifetimeCrtEarned") / Economy.LEGACY_TOKENS_PER_EGG;
			if (tokens.has("currentDayUtc") && tokens.get("currentDayUtc").isJsonPrimitive())
			{
				dayKey = tokens.get("currentDayUtc").getAsString();
			}
		}

		eggs.addProperty("balance", Math.max(0, balance));
		eggs.addProperty("xpBanked", 0);
		eggs.addProperty("earnedToday", 0);
		eggs.addProperty("lifetimeEarned", Math.max(0, lifetime));
		if (dayKey != null)
		{
			eggs.addProperty("currentDayUtc", dayKey);
		}

		root.remove("tokens");
		root.add("eggs", eggs);
		root.addProperty("schemaVersion", 2);
	}

	private static long readLong(JsonObject obj, String key)
	{
		try
		{
			return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsLong() : 0L;
		}
		catch (Exception e)
		{
			return 0L;
		}
	}
}
