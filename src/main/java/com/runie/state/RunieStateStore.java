package com.runie.state;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.runie.core.Economy;
import com.runie.creatures.CreatureRegistry;
import com.runie.state.migrations.StateMigration;
import com.runie.state.migrations.StateMigrations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Versioned JSON state store (architecture §4).
 *
 * <p>One document per OSRS account under {@code RUNELITE_DIR/runie/}:
 * {@code state-<accountHash>.json} (+ {@code .bak} previous-good rotation and a
 * transient {@code .tmp} atomic-write staging file).
 *
 * <p>Guarantees:
 * <ul>
 *   <li><b>Atomic writes</b> — serialize to {@code .tmp}, rotate main → {@code .bak},
 *       then {@code ATOMIC_MOVE} tmp → main. A crash can lose at most the last
 *       ≤15s of deltas, never corrupt the file.</li>
 *   <li><b>Never silently reset</b> — a file that fails to parse or validate is
 *       quarantined as {@code state-<hash>.json.corrupt-<ts>}, then {@code .bak}
 *       is tried; only if both fail does a fresh state start, with
 *       {@link LoadStatus#FRESH_AFTER_CORRUPT} exposed for a UI warning.</li>
 *   <li><b>Migrations</b> — raw-JSON migration steps run before POJO binding.
 *       A schemaVersion NEWER than this build supports loads read-only and
 *       disables all writes (never destructively rewrite a newer schema).</li>
 *   <li><b>Debounced saves</b> — save 2s after the last mutation with a 15s
 *       max-latency backstop; disk I/O always on the injected executor, never
 *       the client thread.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class RunieStateStore
{
	public enum LoadStatus
	{
		/** No file existed; brand-new account state. */
		FRESH_NEW,
		LOADED_OK,
		/** Loaded after applying one or more schema migrations. */
		MIGRATED,
		/** Main file bad; previous-good {@code .bak} restored. */
		RECOVERED_FROM_BAK,
		/** Main and {@code .bak} both bad/absent; corrupt file quarantined, fresh state started. UI must warn. */
		FRESH_AFTER_CORRUPT,
		/** Document written by a newer plugin version; loaded read-only, writes disabled. */
		READ_ONLY_NEWER_SCHEMA
	}

	static final long SAVE_DEBOUNCE_MS = 2_000;
	static final long SAVE_BACKSTOP_MS = 15_000;

	/**
	 * Fired after {@link #switchAccount(long)} binds and loads an account's
	 * state document. This is THE restore hook: consumers that mirror persisted
	 * state (e.g. the companion overlay re-deploying the persisted active
	 * creature) subscribe here instead of racing the login event, because the
	 * state file only loads on the first attributed StatChanged after login —
	 * strictly AFTER GameState.LOGGED_IN fires.
	 */
	public interface StateLoadListener
	{
		void onStateLoaded(long accountHash);
	}

	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final CreatureRegistry registry; // nullable in tests
	private final Path dir;
	private final List<StateMigration> migrations;
	private final int currentSchemaVersion;

	private final List<StateLoadListener> loadListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

	private final Object lock = new Object();
	private RunieAccountState state;
	private long accountHash = -1;
	private LoadStatus loadStatus;
	private boolean writesDisabled;

	private boolean dirty;
	private long firstDirtyAtMs;
	private long lastDirtyAtMs;
	private ScheduledFuture<?> pendingSave;

	@Inject
	public RunieStateStore(Gson gson, ScheduledExecutorService executor, CreatureRegistry registry)
	{
		this(gson, executor, registry, RuneLite.RUNELITE_DIR.toPath().resolve("runie"),
			StateMigrations.PRODUCTION, RunieAccountState.CURRENT_SCHEMA_VERSION);
	}

	/** Test seam: explicit directory, migrations, and schema-version target. */
	RunieStateStore(Gson gson, ScheduledExecutorService executor, CreatureRegistry registry,
		Path dir, List<StateMigration> migrations, int currentSchemaVersion)
	{
		this.gson = gson;
		this.executor = executor;
		this.registry = registry;
		this.dir = dir;
		this.migrations = migrations;
		this.currentSchemaVersion = currentSchemaVersion;
	}

	// ------------------------------------------------------------------
	// Account switching / loading
	// ------------------------------------------------------------------

	/**
	 * Bind the store to an account, loading (or creating) its state file.
	 * Called by XpDeltaService BEFORE any delta attribution (§3.2). Switching
	 * away flushes the previous account's state asynchronously — the two
	 * accounts use distinct files, so there is no bleed (§16.2).
	 */
	public void switchAccount(long hash)
	{
		synchronized (lock)
		{
			if (hash == accountHash && state != null)
			{
				return;
			}
			if (state != null && dirty && !writesDisabled)
			{
				// Flush the OLD account's state off-thread; the captured object is
				// never mutated again after this switch.
				final RunieAccountState old = state;
				final long oldHash = accountHash;
				cancelPendingSave();
				dirty = false;
				executor.execute(() -> writeQuietly(old, oldHash));
			}
			accountHash = hash;
			writesDisabled = false;
			dirty = false;
			firstDirtyAtMs = 0;
			state = load(hash);
		}
		// Notify outside the lock: listeners read back through the store's
		// public accessors (which re-take the lock) and may fan out further.
		for (StateLoadListener l : loadListeners)
		{
			try
			{
				l.onStateLoaded(hash);
			}
			catch (Exception e)
			{
				log.error("Runie: state-load listener failed", e);
			}
		}
	}

	/** Subscribe to account-state loads (restore hook — see {@link StateLoadListener}). */
	public void addLoadListener(StateLoadListener l)
	{
		loadListeners.add(l);
	}

	public void removeLoadListener(StateLoadListener l)
	{
		loadListeners.remove(l);
	}

	/** Current account state. Throws if no account has been bound yet. */
	public RunieAccountState getState()
	{
		synchronized (lock)
		{
			if (state == null)
			{
				throw new IllegalStateException("no account state loaded (switchAccount not called)");
			}
			return state;
		}
	}

	public boolean hasState()
	{
		synchronized (lock)
		{
			return state != null;
		}
	}

	public long getAccountHash()
	{
		synchronized (lock)
		{
			return accountHash;
		}
	}

	public LoadStatus getLoadStatus()
	{
		synchronized (lock)
		{
			return loadStatus;
		}
	}

	public boolean isWritesDisabled()
	{
		synchronized (lock)
		{
			return writesDisabled;
		}
	}

	// ------------------------------------------------------------------
	// Dirty tracking / debounced save
	// ------------------------------------------------------------------

	/** Mark meaningful state change; debounced save 2s after the last mark, 15s backstop. */
	public void markDirty()
	{
		synchronized (lock)
		{
			if (state == null || writesDisabled)
			{
				return;
			}
			long now = System.currentTimeMillis();
			dirty = true;
			lastDirtyAtMs = now;
			if (firstDirtyAtMs == 0)
			{
				firstDirtyAtMs = now;
			}
			if (pendingSave == null || pendingSave.isDone())
			{
				pendingSave = executor.schedule(this::debouncedSave, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
			}
		}
	}

	/** Low-priority dirty (e.g. clock-watermark advance): piggybacks the normal debounce. */
	public void markDirtyLow()
	{
		synchronized (lock)
		{
			if (state == null || writesDisabled)
			{
				return;
			}
			dirty = true;
			if (firstDirtyAtMs == 0)
			{
				firstDirtyAtMs = System.currentTimeMillis();
				lastDirtyAtMs = firstDirtyAtMs;
			}
			if (pendingSave == null || pendingSave.isDone())
			{
				pendingSave = executor.schedule(this::debouncedSave, SAVE_BACKSTOP_MS, TimeUnit.MILLISECONDS);
			}
		}
	}

	private void debouncedSave()
	{
		long delay;
		synchronized (lock)
		{
			if (!dirty || state == null || writesDisabled)
			{
				return;
			}
			long now = System.currentTimeMillis();
			boolean quietLongEnough = now - lastDirtyAtMs >= SAVE_DEBOUNCE_MS;
			boolean backstopHit = now - firstDirtyAtMs >= SAVE_BACKSTOP_MS;
			if (quietLongEnough || backstopHit)
			{
				doSaveLocked();
				return;
			}
			delay = Math.min(SAVE_DEBOUNCE_MS - (now - lastDirtyAtMs), SAVE_BACKSTOP_MS - (now - firstDirtyAtMs));
			pendingSave = executor.schedule(this::debouncedSave, Math.max(delay, 50), TimeUnit.MILLISECONDS);
		}
	}

	/** Queue an immediate flush on the executor (event-driven flush points, §4.4). */
	public void flushAsync()
	{
		executor.execute(this::flush);
	}

	/** Synchronous flush — shutDown() and tests. */
	public void flush()
	{
		synchronized (lock)
		{
			if (state == null || writesDisabled || !dirty)
			{
				return;
			}
			doSaveLocked();
		}
	}

	private void cancelPendingSave()
	{
		if (pendingSave != null)
		{
			pendingSave.cancel(false);
			pendingSave = null;
		}
	}

	/** Must hold {@code lock}. */
	private void doSaveLocked()
	{
		cancelPendingSave();
		dirty = false;
		firstDirtyAtMs = 0;
		writeQuietly(state, accountHash);
	}

	// ------------------------------------------------------------------
	// Disk I/O
	// ------------------------------------------------------------------

	Path mainFile(long hash)
	{
		return dir.resolve("state-" + hash + ".json");
	}

	Path bakFile(long hash)
	{
		return dir.resolve("state-" + hash + ".json.bak");
	}

	private void writeQuietly(RunieAccountState toWrite, long hash)
	{
		try
		{
			atomicWrite(toWrite, hash);
		}
		catch (IOException e)
		{
			log.error("Runie: failed to save state for account {}", hash, e);
		}
	}

	private void atomicWrite(RunieAccountState toWrite, long hash) throws IOException
	{
		String json;
		synchronized (toWrite)
		{
			json = gson.toJson(toWrite);
		}
		Files.createDirectories(dir);
		Path main = mainFile(hash);
		Path bak = bakFile(hash);
		Path tmp = dir.resolve("state-" + hash + ".json.tmp");
		Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
		if (Files.exists(main))
		{
			Files.move(main, bak, StandardCopyOption.REPLACE_EXISTING);
		}
		try
		{
			Files.move(tmp, main, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			Files.move(tmp, main, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	// ------------------------------------------------------------------
	// Load / migrate / validate / quarantine
	// ------------------------------------------------------------------

	private RunieAccountState load(long hash)
	{
		Path main = mainFile(hash);
		Path bak = bakFile(hash);

		if (!Files.exists(main))
		{
			if (Files.exists(bak))
			{
				// Crash between bak-rotation and tmp-move: bak is the last good doc.
				RunieAccountState fromBak = tryLoadFile(bak, hash);
				if (fromBak != null)
				{
					loadStatus = LoadStatus.RECOVERED_FROM_BAK;
					dirty = true;
					firstDirtyAtMs = System.currentTimeMillis();
					lastDirtyAtMs = firstDirtyAtMs;
					pendingSave = executor.schedule(this::debouncedSave, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
					return fromBak;
				}
				quarantine(bak);
			}
			loadStatus = LoadStatus.FRESH_NEW;
			return RunieAccountState.fresh(hash, System.currentTimeMillis());
		}

		LoadResult result = tryLoadFileDetailed(main, hash);
		if (result.readOnlyNewerSchema)
		{
			loadStatus = LoadStatus.READ_ONLY_NEWER_SCHEMA;
			writesDisabled = true;
			log.warn("Runie: state file for {} has schemaVersion {} (this build supports {}). "
				+ "Loaded read-only; update the plugin.", hash, result.fileVersion, currentSchemaVersion);
			return result.state;
		}
		if (result.state != null)
		{
			loadStatus = result.migrated ? LoadStatus.MIGRATED : LoadStatus.LOADED_OK;
			if (result.migrated)
			{
				markDirtyAfterLoad();
			}
			return result.state;
		}

		// Main file is bad: quarantine it (never silently reset) and try .bak.
		log.warn("Runie: state file {} failed to load ({}); quarantining and trying .bak", main, result.error);
		quarantine(main);
		if (Files.exists(bak))
		{
			RunieAccountState fromBak = tryLoadFile(bak, hash);
			if (fromBak != null)
			{
				loadStatus = LoadStatus.RECOVERED_FROM_BAK;
				markDirtyAfterLoad();
				return fromBak;
			}
			quarantine(bak);
		}
		log.error("Runie: no recoverable state for account {}; starting fresh. The corrupt file was preserved.", hash);
		loadStatus = LoadStatus.FRESH_AFTER_CORRUPT;
		return RunieAccountState.fresh(hash, System.currentTimeMillis());
	}

	/** Must hold {@code lock}; schedules a rewrite of a just-recovered/migrated doc. */
	private void markDirtyAfterLoad()
	{
		dirty = true;
		firstDirtyAtMs = System.currentTimeMillis();
		lastDirtyAtMs = firstDirtyAtMs;
		pendingSave = executor.schedule(this::debouncedSave, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
	}

	private static final class LoadResult
	{
		RunieAccountState state;
		boolean migrated;
		boolean readOnlyNewerSchema;
		int fileVersion;
		String error;
	}

	private RunieAccountState tryLoadFile(Path file, long hash)
	{
		LoadResult r = tryLoadFileDetailed(file, hash);
		return r.readOnlyNewerSchema ? null : r.state;
	}

	private LoadResult tryLoadFileDetailed(Path file, long hash)
	{
		LoadResult result = new LoadResult();
		try
		{
			String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			// instance API: RuneLite bundles an older Gson without JsonParser.parseString
			JsonElement parsed = new JsonParser().parse(raw);
			if (!parsed.isJsonObject())
			{
				result.error = "root is not a JSON object";
				return result;
			}
			JsonObject root = parsed.getAsJsonObject();
			if (!root.has("schemaVersion") || !root.get("schemaVersion").isJsonPrimitive())
			{
				result.error = "missing schemaVersion";
				return result;
			}
			int version = root.get("schemaVersion").getAsInt();
			result.fileVersion = version;

			if (version > currentSchemaVersion)
			{
				// Newer than this build: bind leniently, read-only, never rewrite.
				result.state = gson.fromJson(root, RunieAccountState.class);
				result.readOnlyNewerSchema = true;
				return result;
			}

			while (version < currentSchemaVersion)
			{
				StateMigration step = findMigration(version);
				if (step == null)
				{
					result.error = "no migration registered from version " + version;
					return result;
				}
				step.apply(root);
				int after = root.get("schemaVersion").getAsInt();
				if (after != version + 1)
				{
					result.error = "migration from " + version + " produced version " + after;
					return result;
				}
				version = after;
				result.migrated = true;
			}

			RunieAccountState loaded = gson.fromJson(root, RunieAccountState.class);
			String invariantError = validateInvariants(loaded, hash);
			if (invariantError != null)
			{
				result.error = "invariant violation: " + invariantError;
				return result;
			}
			normalize(loaded);
			result.state = loaded;
			return result;
		}
		catch (Exception e)
		{
			result.error = e.toString();
			return result;
		}
	}

	private StateMigration findMigration(int fromVersion)
	{
		for (StateMigration m : migrations)
		{
			if (m.fromVersion() == fromVersion)
			{
				return m;
			}
		}
		return null;
	}

	/**
	 * Load-time invariant validation (§4.3). Returns an error description, or
	 * null when the document is healthy. Fail-closed: a violating document is
	 * treated as corrupt (quarantine + .bak fallback), never "fixed up".
	 */
	String validateInvariants(RunieAccountState s, long expectedHash)
	{
		if (s == null)
		{
			return "null document";
		}
		if (s.eggs == null || s.gacha == null || s.creatures == null
			|| s.achievements == null)
		{
			return "missing section";
		}
		if (s.accountHash == null || !s.accountHash.equals(Long.toString(expectedHash)))
		{
			return "accountHash mismatch: file=" + s.accountHash + " expected=" + expectedHash;
		}
		if (s.eggs.balance < 0)
		{
			return "negative egg balance";
		}
		if (s.eggs.xpBanked < 0)
		{
			return "negative banked egg XP";
		}
		if (s.eggs.earnedToday < 0)
		{
			return "negative eggs earned today";
		}
		if (s.eggs.lifetimeEarned < 0)
		{
			return "negative lifetime counters";
		}
		if (s.gacha.rarePity < 0 || s.gacha.rarePity > Economy.GM_HARD_AT
			|| s.gacha.elitePity < 0 || s.gacha.elitePity > Economy.GM_HARD_AT
			|| s.gacha.gmPity < 0 || s.gacha.gmPity > Economy.GM_HARD_AT)
		{
			return "pity counter out of range";
		}
		if (s.gacha.totalPulls < 0
			|| s.gacha.starterPullsRemaining < 0 || s.gacha.starterPullsRemaining > Economy.STARTER_PULLS)
		{
			return "summon counters out of range";
		}
		if (s.creatures.owned != null)
		{
			for (Map.Entry<String, CreatureInstance> e : s.creatures.owned.entrySet())
			{
				CreatureInstance c = e.getValue();
				if (c == null || c.xp < 0 || c.lifetimeXp < 0 || c.prestigeCount < 0 || c.copiesPulled < 0
					|| c.stars < 0 || c.evolutionRewardedStage < 0)
				{
					return "invalid creature instance: " + e.getKey();
				}
				if (registry != null && registry.isLoaded() && !registry.contains(e.getKey()))
				{
					return "owned creature not in registry: " + e.getKey();
				}
			}
			// NOTE: an active id that is not owned is NOT an invariant violation —
			// it degrades gracefully (normalize() clears it: no companion, no crash)
			// instead of quarantining an otherwise-healthy document.
		}
		if (s.clockWatermarkMs < 0)
		{
			return "negative clock watermark";
		}
		return null;
	}

	/** Harmless null-collection normalization after binding (explicit nulls in JSON). */
	private void normalize(RunieAccountState s)
	{
		if (s.gacha.pullHistoryTail == null)
		{
			s.gacha.pullHistoryTail = new java.util.ArrayList<>();
		}
		if (s.creatures.owned == null)
		{
			s.creatures.owned = new java.util.LinkedHashMap<>();
		}
		if (s.achievements.grantedKeys == null)
		{
			s.achievements.grantedKeys = new java.util.LinkedHashSet<>();
		}
		// Star counts are capped at MAX_STARS by the engine; clamp any drifted
		// value on load rather than rejecting an otherwise-healthy document.
		for (CreatureInstance c : s.creatures.owned.values())
		{
			if (c != null && c.stars > Economy.MAX_STARS)
			{
				c.stars = Economy.MAX_STARS;
			}
		}
		// Persisted active creature that is no longer owned/valid (e.g. roster or
		// legacy-state drift): fall back gracefully to "no companion" rather than
		// crashing or rejecting the document.
		if (s.creatures.active != null && !s.creatures.owned.containsKey(s.creatures.active))
		{
			log.warn("Runie: persisted active creature '{}' is not owned; clearing selection", s.creatures.active);
			s.creatures.active = null;
		}
	}

	private void quarantine(Path file)
	{
		try
		{
			Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
			Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
			log.warn("Runie: quarantined unreadable state file to {}", target);
		}
		catch (IOException e)
		{
			log.error("Runie: failed to quarantine {}", file, e);
		}
	}

	// ------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------

	/** Plugin shutDown(): flush outstanding changes synchronously. */
	public void shutdownFlush()
	{
		synchronized (lock)
		{
			cancelPendingSave();
			if (state != null && dirty && !writesDisabled)
			{
				doSaveLocked();
			}
		}
	}

	/** Unbind on plugin shutDown so a re-enable starts clean. */
	public void reset()
	{
		synchronized (lock)
		{
			cancelPendingSave();
			state = null;
			accountHash = -1;
			loadStatus = null;
			writesDisabled = false;
			dirty = false;
			firstDirtyAtMs = 0;
		}
	}
}
