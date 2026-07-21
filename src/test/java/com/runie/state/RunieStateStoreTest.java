package com.runie.state;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.runie.state.migrations.StateMigration;
import com.runie.state.migrations.StateMigrations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * State persistence: atomic write + .bak rotation, round-trips, schema
 * migrations, corrupt-file quarantine (never silently reset), newer-schema
 * read-only mode, invariant validation (architecture §4, §12.1).
 */
public class RunieStateStoreTest
{
	private static final long HASH = 987654321L;

	private Path dir;
	private ScheduledExecutorService executor;
	private Gson gson;

	@Before
	public void setUp() throws IOException
	{
		dir = Files.createTempDirectory("runie-store-test");
		executor = Executors.newSingleThreadScheduledExecutor();
		gson = new Gson();
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private RunieStateStore newStore()
	{
		return TestStateStores.create(gson, executor, null, dir);
	}

	private Path mainFile()
	{
		return dir.resolve("state-" + HASH + ".json");
	}

	private Path bakFile()
	{
		return dir.resolve("state-" + HASH + ".json.bak");
	}

	private List<Path> corruptFiles() throws IOException
	{
		List<Path> out = new ArrayList<>();
		try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.corrupt-*"))
		{
			s.forEach(out::add);
		}
		return out;
	}

	// -- basics ------------------------------------------------------------

	@Test
	public void freshAccountStartsNew()
	{
		RunieStateStore store = newStore();
		store.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.FRESH_NEW, store.getLoadStatus());
		RunieAccountState s = store.getState();
		assertEquals(Long.toString(HASH), s.accountHash);
		assertEquals(RunieAccountState.CURRENT_SCHEMA_VERSION, s.schemaVersion);
		assertEquals(3, s.gacha.starterPullsRemaining);
		assertEquals(0, s.eggs.balance);
	}

	@Test
	public void saveLoadRoundTripPreservesEverything()
	{
		RunieStateStore store = newStore();
		store.switchAccount(HASH);
		RunieAccountState s = store.getState();
		s.eggs.balance = 14;
		s.eggs.xpBanked = 273_450;
		s.eggs.earnedToday = 7;
		s.eggs.lifetimeEarned = 120;
		s.eggs.currentDayUtc = "2026-07-17";
		s.gacha.totalPulls = 37;
		s.gacha.rarePity = 4;
		s.gacha.elitePity = 22;
		s.gacha.gmPity = 37;
		s.gacha.starterPullsRemaining = 0;
		CreatureInstance grubnak = CreatureInstance.fresh(1_784_678_500_000L);
		grubnak.xp = 1_210_421;
		grubnak.lifetimeXp = 14_244_852;
		grubnak.prestigeCount = 1;
		grubnak.stars = 13;
		grubnak.shiny = true;
		grubnak.evolutionRewardedStage = 3;
		s.creatures.owned.put("grubnak", grubnak);
		s.creatures.active = "grubnak";
		s.achievements.grantedKeys.add("quest:dragon_slayer_i");
		s.clockWatermarkMs = 1_784_703_600_000L;
		store.markDirty();
		store.flush();
		assertTrue(Files.exists(mainFile()));

		RunieStateStore store2 = newStore();
		store2.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.LOADED_OK, store2.getLoadStatus());
		RunieAccountState r = store2.getState();
		assertEquals(14, r.eggs.balance);
		assertEquals(273_450, r.eggs.xpBanked);
		assertEquals(7, r.eggs.earnedToday);
		assertEquals(120, r.eggs.lifetimeEarned);
		assertEquals("2026-07-17", r.eggs.currentDayUtc);
		assertEquals(37, r.gacha.gmPity);
		assertEquals("grubnak", r.creatures.active);
		assertEquals(1_210_421, r.creatures.owned.get("grubnak").xp);
		assertEquals(14_244_852, r.creatures.owned.get("grubnak").lifetimeXp);
		assertEquals(1, r.creatures.owned.get("grubnak").prestigeCount);
		assertEquals(13, r.creatures.owned.get("grubnak").stars);
		assertTrue(r.creatures.owned.get("grubnak").shiny);
		assertEquals(3, r.creatures.owned.get("grubnak").evolutionRewardedStage);
		assertTrue(r.achievements.grantedKeys.contains("quest:dragon_slayer_i"));
		assertEquals(1_784_703_600_000L, r.clockWatermarkMs);
	}

	@Test
	public void unknownLegacyKeysAreIgnoredOnLoad() throws IOException
	{
		// old-era keys (Baubles, forge, cosmetics) may still linger in hand-edited
		// or ancient files — Gson must ignore unknown keys and load cleanly
		RunieStateStore store = newStore();
		store.switchAccount(HASH);
		store.getState().eggs.balance = 21;
		store.markDirty();
		store.flush();

		String raw = new String(Files.readAllBytes(mainFile()), StandardCharsets.UTF_8);
		raw = raw.replace("\"balance\":21",
			"\"balance\":21,\"ct\":385,\"ctClueEasyToday\":50,\"lifetimeCtEarned\":9000");
		raw = raw.replace("\"gacha\":",
			"\"forge\":{\"windowEvents\":[{\"tsMs\":1784500000000,\"crt\":10}]},"
				+ "\"cosmetics\":{\"owned\":[\"party_hat\"]},\"gacha\":");
		Files.write(mainFile(), raw.getBytes(StandardCharsets.UTF_8));

		RunieStateStore store2 = newStore();
		store2.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.LOADED_OK, store2.getLoadStatus());
		assertEquals(21, store2.getState().eggs.balance);
		assertFalse(store2.isWritesDisabled());
	}

	// -- legacy token → egg migration (rework part 1) --------------------------

	private RunieStateStore productionMigrationStore()
	{
		return TestStateStores.create(gson, executor, null, dir,
			StateMigrations.PRODUCTION, RunieAccountState.CURRENT_SCHEMA_VERSION);
	}

	private void writeLegacyV1Document(int crt, long lifetimeCrt) throws IOException
	{
		String v1 = "{\"schemaVersion\":1,\"accountHash\":\"" + HASH + "\","
			+ "\"createdAtMs\":1784600000000,\"clockWatermarkMs\":1784600000000,"
			+ "\"tokens\":{\"crt\":" + crt + ",\"dailyXpCounted\":273450,"
			+ "\"crtEarnedTodayMilli\":12730,\"bankedAllowanceMilli\":41000,"
			+ "\"currentDayUtc\":\"2026-07-10\",\"lifetimeCrtEarned\":" + lifetimeCrt + "},"
			+ "\"gacha\":{\"totalPulls\":37,\"rarePity\":4,\"elitePity\":22,\"gmPity\":37,"
			+ "\"starterPullsRemaining\":0,\"pullHistoryTail\":[]},"
			+ "\"creatures\":{\"active\":\"grubnak\",\"owned\":{\"grubnak\":"
			+ "{\"acquiredAtMs\":1,\"xp\":1000,\"lifetimeXp\":1000,\"prestigeCount\":0,\"copiesPulled\":4}}},"
			+ "\"achievements\":{\"grantedKeys\":[]}}";
		Files.createDirectories(dir);
		Files.write(mainFile(), v1.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void legacyTokenBalanceMigratesToEggsAtFiveToOneFloor() throws IOException
	{
		writeLegacyV1Document(13, 27); // 13 CrT → 2 eggs (remainder discarded)

		RunieStateStore store = productionMigrationStore();
		store.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.MIGRATED, store.getLoadStatus());
		RunieAccountState s = store.getState();
		assertEquals(RunieAccountState.CURRENT_SCHEMA_VERSION, s.schemaVersion);
		assertEquals(2, s.eggs.balance);              // floor(13 / 5)
		assertEquals(5, s.eggs.lifetimeEarned);       // floor(27 / 5)
		assertEquals(0, s.eggs.earnedToday);          // ladder restarts clean
		assertEquals(0, s.eggs.xpBanked);
		assertEquals("2026-07-10", s.eggs.currentDayUtc); // day-key carried over
		// everything else survives untouched; new creature fields default safely
		assertEquals(37, s.gacha.totalPulls);
		assertEquals("grubnak", s.creatures.active);
		assertEquals(4, s.creatures.owned.get("grubnak").copiesPulled);
		assertEquals(0, s.creatures.owned.get("grubnak").stars);
		assertFalse(s.creatures.owned.get("grubnak").shiny);
		assertEquals(0, s.creatures.owned.get("grubnak").evolutionRewardedStage);
		assertFalse(store.isWritesDisabled());        // never quarantined, never read-only
		assertEquals(0, corruptFilesQuietly());

		// migrated document round-trips at v2
		store.flush();
		RunieStateStore again = productionMigrationStore();
		again.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.LOADED_OK, again.getLoadStatus());
		assertEquals(2, again.getState().eggs.balance);
	}

	@Test
	public void legacyDocumentWithoutTokensSectionStillMigrates() throws IOException
	{
		String v1 = "{\"schemaVersion\":1,\"accountHash\":\"" + HASH + "\","
			+ "\"createdAtMs\":1,\"clockWatermarkMs\":1,"
			+ "\"gacha\":{},\"creatures\":{},\"achievements\":{}}";
		Files.createDirectories(dir);
		Files.write(mainFile(), v1.getBytes(StandardCharsets.UTF_8));

		RunieStateStore store = productionMigrationStore();
		store.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.MIGRATED, store.getLoadStatus());
		assertEquals(0, store.getState().eggs.balance);
		assertFalse(store.isWritesDisabled());
	}

	private int corruptFilesQuietly()
	{
		try
		{
			return corruptFiles().size();
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Test
	public void secondSaveRotatesBak() throws IOException
	{
		RunieStateStore store = newStore();
		store.switchAccount(HASH);
		store.getState().eggs.balance = 1;
		store.markDirty();
		store.flush();
		assertFalse(Files.exists(bakFile()));

		store.getState().eggs.balance = 2;
		store.markDirty();
		store.flush();
		assertTrue(Files.exists(bakFile()));
		String bak = new String(Files.readAllBytes(bakFile()), StandardCharsets.UTF_8);
		assertTrue(bak.contains("\"balance\":1"));
		String main = new String(Files.readAllBytes(mainFile()), StandardCharsets.UTF_8);
		assertTrue(main.contains("\"balance\":2"));
	}

	// -- corruption --------------------------------------------------------

	@Test
	public void corruptMainFallsBackToBakAndQuarantines() throws IOException
	{
		RunieStateStore store = newStore();
		store.switchAccount(HASH);
		store.getState().eggs.balance = 7;
		store.markDirty();
		store.flush();
		store.getState().eggs.balance = 9;
		store.markDirty();
		store.flush();                       // bak now holds balance=7, main balance=9

		Files.write(mainFile(), "{not valid json!!".getBytes(StandardCharsets.UTF_8));

		RunieStateStore store2 = newStore();
		store2.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.RECOVERED_FROM_BAK, store2.getLoadStatus());
		assertEquals(7, store2.getState().eggs.balance);
		assertEquals(1, corruptFiles().size()); // the bad main was preserved, not deleted
	}

	@Test
	public void corruptWithNoBakStartsFreshButQuarantines() throws IOException
	{
		Files.createDirectories(dir);
		Files.write(mainFile(), "garbage".getBytes(StandardCharsets.UTF_8));

		RunieStateStore store = newStore();
		store.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.FRESH_AFTER_CORRUPT, store.getLoadStatus());
		assertEquals(0, store.getState().eggs.balance);
		assertEquals(1, corruptFiles().size());
		assertFalse(Files.exists(mainFile())); // moved aside, not overwritten in place
	}

	@Test
	public void invariantViolationIsTreatedAsCorrupt() throws IOException
	{
		RunieStateStore store = newStore();
		store.switchAccount(HASH);
		store.markDirty();
		store.flush();

		// hand-poison the file: negative balance must never load
		String raw = new String(Files.readAllBytes(mainFile()), StandardCharsets.UTF_8);
		Files.write(mainFile(), raw.replace("\"balance\":0", "\"balance\":-5").getBytes(StandardCharsets.UTF_8));

		RunieStateStore store2 = newStore();
		store2.switchAccount(HASH);
		assertTrue(store2.getLoadStatus() == RunieStateStore.LoadStatus.FRESH_AFTER_CORRUPT
			|| store2.getLoadStatus() == RunieStateStore.LoadStatus.RECOVERED_FROM_BAK);
		assertTrue(store2.getState().eggs.balance >= 0);
		assertEquals(1, corruptFiles().size());
	}

	@Test
	public void accountHashMismatchInsideFileIsRejected() throws IOException
	{
		RunieStateStore store = newStore();
		store.switchAccount(HASH);
		store.markDirty();
		store.flush();
		String raw = new String(Files.readAllBytes(mainFile()), StandardCharsets.UTF_8);
		Files.write(mainFile(),
			raw.replace(Long.toString(HASH), "1234500000").getBytes(StandardCharsets.UTF_8));

		RunieStateStore store2 = newStore();
		store2.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.FRESH_AFTER_CORRUPT, store2.getLoadStatus());
	}

	// -- schema versioning ---------------------------------------------------

	@Test
	public void newerSchemaLoadsReadOnlyAndNeverRewrites() throws IOException
	{
		RunieStateStore store = newStore();
		store.switchAccount(HASH);
		store.getState().eggs.balance = 5;
		store.markDirty();
		store.flush();

		String raw = new String(Files.readAllBytes(mainFile()), StandardCharsets.UTF_8);
		Files.write(mainFile(),
			raw.replace("\"schemaVersion\":" + RunieAccountState.CURRENT_SCHEMA_VERSION,
				"\"schemaVersion\":99").getBytes(StandardCharsets.UTF_8));
		byte[] onDisk = Files.readAllBytes(mainFile());

		RunieStateStore store2 = newStore();
		store2.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.READ_ONLY_NEWER_SCHEMA, store2.getLoadStatus());
		assertTrue(store2.isWritesDisabled());
		assertEquals(5, store2.getState().eggs.balance); // lenient read still works

		// mutation attempts must not touch the newer file
		store2.getState().eggs.balance = 99999;
		store2.markDirty();
		store2.flush();
		store2.shutdownFlush();
		assertTrue(java.util.Arrays.equals(onDisk, Files.readAllBytes(mainFile())));
	}

	@Test
	public void migrationMachineryUpgradesOldDocuments() throws IOException
	{
		// write a current (v2) document with the standard store
		RunieStateStore v2Store = newStore();
		v2Store.switchAccount(HASH);
		v2Store.getState().eggs.balance = 11;
		v2Store.markDirty();
		v2Store.flush();

		// a synthetic v2→v3 migration that proves raw-JSON access works
		StateMigration v2toV3 = new StateMigration()
		{
			@Override
			public int fromVersion()
			{
				return 2;
			}

			@Override
			public void apply(JsonObject root)
			{
				JsonObject eggs = root.getAsJsonObject("eggs");
				eggs.addProperty("balance", eggs.get("balance").getAsInt() + 1000); // synthetic transform
				root.addProperty("schemaVersion", 3);
			}
		};

		RunieStateStore v3Store = TestStateStores.create(gson, executor, null, dir,
			Collections.singletonList(v2toV3), 3);
		v3Store.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.MIGRATED, v3Store.getLoadStatus());
		assertEquals(1011, v3Store.getState().eggs.balance);  // migration ran
		assertEquals(3, v3Store.getState().schemaVersion);

		// migrated doc round-trips at the new version
		v3Store.flush();
		RunieStateStore v3Again = TestStateStores.create(gson, executor, null, dir,
			Collections.singletonList(v2toV3), 3);
		v3Again.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.LOADED_OK, v3Again.getLoadStatus());
		assertEquals(1011, v3Again.getState().eggs.balance);
	}

	@Test
	public void missingMigrationPathIsCorruptNotSilentReset() throws IOException
	{
		RunieStateStore v2Store = newStore();
		v2Store.switchAccount(HASH);
		v2Store.getState().eggs.balance = 3;
		v2Store.markDirty();
		v2Store.flush();

		// current version 4 but no registered migrations: must NOT silently zero the doc
		RunieStateStore broken = TestStateStores.create(gson, executor, null, dir,
			Collections.emptyList(), 4);
		broken.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.FRESH_AFTER_CORRUPT, broken.getLoadStatus());
		assertEquals(1, corruptFiles().size()); // old doc preserved for manual recovery
	}

	// -- active companion persistence (Bug 1) ---------------------------------

	@Test
	public void activeCompanionRoundTripsThroughTheStore()
	{
		RunieStateStore store = newStore();
		store.switchAccount(HASH);
		RunieAccountState s = store.getState();
		s.creatures.owned.put("grubnak", CreatureInstance.fresh(1000));
		s.creatures.owned.put("skitterscour", CreatureInstance.fresh(2000));
		s.creatures.active = "skitterscour";
		store.markDirty();
		store.flush();

		// fresh store over the same file = client relaunch
		RunieStateStore store2 = newStore();
		store2.switchAccount(HASH);
		assertEquals(RunieStateStore.LoadStatus.LOADED_OK, store2.getLoadStatus());
		assertEquals("skitterscour", store2.getState().creatures.active);
	}

	@Test
	public void persistedActiveNoLongerOwnedFallsBackGracefully() throws IOException
	{
		RunieStateStore store = newStore();
		store.switchAccount(HASH);
		store.getState().creatures.owned.put("grubnak", CreatureInstance.fresh(1000));
		store.getState().creatures.active = "grubnak";
		store.markDirty();
		store.flush();

		// hand-drift the file: active points at a creature that is not owned
		String raw = new String(Files.readAllBytes(mainFile()), StandardCharsets.UTF_8);
		Files.write(mainFile(),
			raw.replace("\"active\":\"grubnak\"", "\"active\":\"ghost\"").getBytes(StandardCharsets.UTF_8));

		RunieStateStore store2 = newStore();
		store2.switchAccount(HASH);
		// NOT treated as corrupt: document loads, selection degrades to "none"
		assertEquals(RunieStateStore.LoadStatus.LOADED_OK, store2.getLoadStatus());
		assertNull(store2.getState().creatures.active);
		assertTrue(store2.getState().creatures.owned.containsKey("grubnak"));
		assertEquals(0, corruptFiles().size());
	}

	@Test
	public void loadListenerFiresOnceWhenAccountStateLoads()
	{
		RunieStateStore store = newStore();
		List<Long> seen = new ArrayList<>();
		store.addLoadListener(seen::add);
		store.switchAccount(HASH);
		assertEquals(Collections.singletonList(HASH), seen);

		store.switchAccount(HASH); // same account, already bound: no reload, no re-fire
		assertEquals(1, seen.size());

		store.removeLoadListener(seen::add); // removing an unregistered lambda is harmless
		store.switchAccount(555L);
		assertEquals(2, seen.size());
		assertEquals(Long.valueOf(555L), seen.get(1));
	}

	// -- account switching ---------------------------------------------------

	@Test
	public void switchingAccountsKeepsFilesSeparate() throws Exception
	{
		long hashB = 555L;
		RunieStateStore store = newStore();
		store.switchAccount(HASH);
		store.getState().eggs.balance = 42;
		store.markDirty();

		store.switchAccount(hashB);          // flushes A asynchronously
		executor.submit(() -> null).get();   // drain the executor
		assertEquals(RunieStateStore.LoadStatus.FRESH_NEW, store.getLoadStatus());
		assertEquals(0, store.getState().eggs.balance);
		assertTrue(Files.exists(mainFile())); // A's file was written on switch

		store.switchAccount(HASH);           // back to A: restored
		assertEquals(42, store.getState().eggs.balance);
		assertNotNull(store.getState().accountHash);
		assertEquals(Long.toString(HASH), store.getState().accountHash);
	}
}
