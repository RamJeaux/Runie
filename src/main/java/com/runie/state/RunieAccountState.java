package com.runie.state;

import com.runie.core.Economy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single versioned JSON document persisted per OSRS account
 * (architecture §4.2, schema v2). Progression/economy state ONLY — user
 * settings live in RunieConfig/ConfigManager.
 *
 * <p>Schema v2 (Egg rework): the v1 {@code tokens} section (Runie Tokens /
 * CrT, banded accrual, banked allowance) is replaced by the {@code eggs}
 * section. The v1→v2 migration converts a legacy balance at
 * {@link Economy#LEGACY_TOKENS_PER_EGG} tokens per egg (floor). Removed v1
 * keys (crt bands, banked allowance, milli counters) are simply dropped by the
 * migration; any other unknown keys are ignored by Gson — old documents always
 * remain loadable, never quarantined for being old.
 */
public class RunieAccountState
{
	public static final int CURRENT_SCHEMA_VERSION = 2;

	public int schemaVersion = CURRENT_SCHEMA_VERSION;
	/** RuneLite's stable per-account hash, as a string — the sync-ready key. */
	public String accountHash;
	public long createdAtMs;
	/** Monotonic-guard watermark (architecture §11). */
	public long clockWatermarkMs;

	public Eggs eggs = new Eggs();
	public Gacha gacha = new Gacha();
	public Creatures creatures = new Creatures();
	public Achievements achievements = new Achievements();

	/** Egg currency state (schema v2 — replaces the v1 {@code tokens} section). */
	public static class Eggs
	{
		/** Spendable egg balance. */
		public int balance;
		/** XP banked toward the NEXT egg at today's ladder position. */
		public long xpBanked;
		/** Eggs earned so far today (ladder position k; resets at day rollover). */
		public int earnedToday;
		/** UTC day (yyyy-MM-dd) this counter belongs to; null until first touch. */
		public String currentDayUtc;
		public long lifetimeEarned;
	}

	public static class Gacha
	{
		public int totalPulls;
		public int rarePity;
		public int elitePity;
		public int gmPity;
		public int starterPullsRemaining = Economy.STARTER_PULLS;
		public List<PullRecord> pullHistoryTail = new ArrayList<>();
	}

	/** Recent-pull record (UI list only; counters are the real pity state). */
	public static class PullRecord
	{
		public int n;
		public String creatureId;
		public String rarity;
		public boolean dup;
		public long tsMs;
	}

	public static class Creatures
	{
		/** Active creature id, or null (XP feeds no one — panel warns in Epic 7). */
		public String active;
		public Map<String, CreatureInstance> owned = new LinkedHashMap<>();
	}

	/**
	 * Achievement idempotency (kept for forward use). NOTE on legacy documents:
	 * removed old-era keys are dropped by migration or ignored by Gson on load —
	 * old state files remain loadable.
	 */
	public static class Achievements
	{
		/** Idempotency set: stable key per granting event — never double-grant. */
		public Set<String> grantedKeys = new LinkedHashSet<>();
	}

	public static RunieAccountState fresh(long accountHash, long nowMs)
	{
		RunieAccountState s = new RunieAccountState();
		s.accountHash = Long.toString(accountHash);
		s.createdAtMs = nowMs;
		s.clockWatermarkMs = nowMs;
		return s;
	}
}
