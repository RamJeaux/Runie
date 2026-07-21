package com.runie.core;

import com.google.gson.Gson;
import com.runie.state.RunieStateStore;
import com.runie.state.TestStateStores;
import com.runie.support.FakeClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The #1-risk tests (architecture §12.1 row 1): the XP-snapshot-safe pipeline
 * must never turn a login burst, reconnect burst, account switch, negative
 * delta, or implausible spike into a grant.
 */
public class XpDeltaServiceTest
{
	private static final long HASH_A = 1111L;
	private static final long HASH_B = 2222L;

	private Path dir;
	private ScheduledExecutorService executor;
	private RunieStateStore store;
	private XpDeltaService xp;
	private EggService eggs;
	private FakeClock clock;
	private long accountHash = AccountContext.NO_ACCOUNT;

	private final List<int[]> received = new ArrayList<>(); // [skillOrdinal, delta, newXp]

	@Before
	public void setUp() throws IOException
	{
		dir = Files.createTempDirectory("runie-xp-test");
		executor = Executors.newSingleThreadScheduledExecutor();
		store = TestStateStores.create(new Gson(), executor, null, dir);
		clock = new FakeClock(1_784_678_400_000L); // 2026-07-17T00:00:00Z-ish
		xp = new XpDeltaService(() -> accountHash, store);
		eggs = new EggService(store, clock);
		xp.addListener(eggs);
		xp.addListener((skill, delta, newXp) -> received.add(new int[]{skill.ordinal(), delta, newXp}));
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	// -- helpers --------------------------------------------------------

	private void login(long hash)
	{
		accountHash = hash;
		xp.handleGameStateChanged(GameState.LOGGING_IN);
		xp.handleGameStateChanged(GameState.LOGGED_IN);
	}

	private void loginBurst(int xpPerSkill)
	{
		for (Skill s : Skill.values())
		{
			xp.handleStatChanged(new StatChanged(s, xpPerSkill, 99, 99));
		}
	}

	private void warmUp()
	{
		xp.handleGameTick();
		xp.handleGameTick();
	}

	private void stat(Skill s, int newXp)
	{
		xp.handleStatChanged(new StatChanged(s, newXp, 1, 1));
	}

	// -- the big one ----------------------------------------------------

	@Test
	public void freshLoginOfMaxedAccountGrantsNothing()
	{
		login(HASH_A);
		loginBurst(13_034_431);   // a maxed account's per-skill XP streams in
		warmUp();

		assertTrue(received.isEmpty());
		assertEquals(0, eggs.getEggBalance());
		assertEquals(0L, eggs.getBankedXp());

		// after warm-up, genuine XP flows normally
		stat(Skill.ATTACK, 13_034_431 + 60_000);
		assertEquals(1, received.size());
		assertEquals(60_000, received.get(0)[1]);
		assertEquals(1, eggs.getEggBalance()); // 60k xp = first 50k egg + 10k banked
		assertEquals(10_000L, eggs.getBankedXp());
	}

	@Test
	public void perSkillSentinel_firstEventIsSnapshotOnly()
	{
		login(HASH_A);
		stat(Skill.MINING, 500_000);   // first sighting of Mining: snapshot only (also binds the account + restarts grace)
		warmUp();
		assertTrue(received.isEmpty());
		stat(Skill.MINING, 500_100);   // now a trusted delta
		assertEquals(1, received.size());
		assertEquals(100, received.get(0)[1]);
	}

	@Test
	public void graceWindowDropsDeltas()
	{
		login(HASH_A);
		stat(Skill.FISHING, 1_000);    // snapshot (sentinel)
		stat(Skill.FISHING, 1_500);    // in grace: snapshot-only, delta dropped
		xp.handleGameTick();
		stat(Skill.FISHING, 1_800);    // still in grace (1 tick left)
		assertTrue(received.isEmpty());
		assertFalse(xp.isWarmedUp());

		xp.handleGameTick();
		assertTrue(xp.isWarmedUp());
		stat(Skill.FISHING, 2_000);    // grace over: trusted delta from latest snapshot
		assertEquals(1, received.size());
		assertEquals(200, received.get(0)[1]);
	}

	@Test
	public void hopReArmsSentinels()
	{
		login(HASH_A);
		stat(Skill.ATTACK, 10_000);    // binds account; grace restarts (normative §3.2)
		warmUp();
		stat(Skill.ATTACK, 10_050);
		assertEquals(1, received.size());

		xp.handleGameStateChanged(GameState.HOPPING);
		// re-delivered XP burst after the hop must be snapshot-only
		stat(Skill.ATTACK, 10_050);
		stat(Skill.ATTACK, 10_050);
		assertEquals(1, received.size());

		xp.handleGameStateChanged(GameState.LOGGED_IN);
		warmUp();
		stat(Skill.ATTACK, 10_150);
		assertEquals(2, received.size());
		assertEquals(100, received.get(1)[1]);
	}

	@Test
	public void connectionLostAndLoginScreenReArm()
	{
		for (GameState gs : new GameState[]{GameState.CONNECTION_LOST, GameState.LOGIN_SCREEN})
		{
			received.clear();
			login(HASH_A);
			warmUp();
			stat(Skill.HERBLORE, 5_000);
			xp.handleGameStateChanged(gs);
			xp.handleGameStateChanged(GameState.LOGGED_IN);
			warmUp();
			stat(Skill.HERBLORE, 9_000);  // would be +4000 if snapshots survived — must be snapshot-only
			assertTrue("no grant after " + gs, received.isEmpty());
		}
	}

	@Test
	public void negativeDeltaNeverGrantsAndReSnapshots()
	{
		login(HASH_A);
		stat(Skill.PRAYER, 50_000);
		warmUp();
		stat(Skill.PRAYER, 40_000);    // negative: re-snapshot, no grant, no clawback
		assertTrue(received.isEmpty());
		assertEquals(0, eggs.getEggBalance());

		stat(Skill.PRAYER, 40_500);    // trusted from the re-snapshot
		assertEquals(1, received.size());
		assertEquals(500, received.get(0)[1]);
	}

	@Test
	public void zeroDeltaGrantsNothing()
	{
		login(HASH_A);
		stat(Skill.COOKING, 1_000);
		warmUp();
		stat(Skill.COOKING, 1_000);
		assertTrue(received.isEmpty());
	}

	@Test
	public void implausibleSpikeReSnapshotsAndGrantsNothing()
	{
		login(HASH_A);
		stat(Skill.SLAYER, 100_000);
		warmUp();
		stat(Skill.SLAYER, 100_000 + XpDeltaService.MAX_PLAUSIBLE_EVENT_XP + 1); // > 5M: dropped
		assertTrue(received.isEmpty());
		assertEquals(0, eggs.getEggBalance());

		stat(Skill.SLAYER, 100_000 + XpDeltaService.MAX_PLAUSIBLE_EVENT_XP + 101);
		assertEquals(1, received.size());
		assertEquals(100, received.get(0)[1]);
	}

	@Test
	public void plausibleLargeGrantStillFlows()
	{
		login(HASH_A);
		stat(Skill.RUNECRAFT, 0);
		warmUp();
		stat(Skill.RUNECRAFT, XpDeltaService.MAX_PLAUSIBLE_EVENT_XP); // exactly at ceiling: allowed
		assertEquals(1, received.size());
		assertEquals(XpDeltaService.MAX_PLAUSIBLE_EVENT_XP, received.get(0)[1]);
	}

	@Test
	public void accountHashMinusOneIsIgnoredEntirely()
	{
		accountHash = AccountContext.NO_ACCOUNT;
		xp.handleGameStateChanged(GameState.LOGGING_IN);
		xp.handleGameStateChanged(GameState.LOGGED_IN);
		warmUp();
		stat(Skill.ATTACK, 1_000);
		stat(Skill.ATTACK, 2_000);
		assertTrue(received.isEmpty());
		assertFalse(store.hasState()); // no account was ever bound
	}

	// -- prompt in-session accrual (timing/sync bug regression tests) -----

	@Test
	public void rapidSameTickDeltasCreditEggsImmediately()
	{
		login(HASH_A);
		stat(Skill.WOODCUTTING, 100_000); // first sighting: snapshot only
		warmUp();

		// a rapid burst of real deltas within the SAME tick — no lull, no extra
		// ticks, no debounce: each event must credit as it arrives
		int xp = 100_000;
		for (int i = 1; i <= 5; i++)
		{
			xp += 25_000;
			stat(Skill.WOODCUTTING, xp);
			assertEquals("delta " + i + " must be delivered immediately", i, received.size());
			assertEquals(25_000, received.get(i - 1)[1]);
			// banked/credited state reflects THIS event already (50k per egg today)
			assertEquals((25_000L * i) / 50_000, eggs.getEggBalance());
			assertEquals((25_000L * i) % 50_000, eggs.getBankedXp());
		}
		assertEquals(2, eggs.getEggBalance());   // 125k = 2 eggs + 25k banked
		assertEquals(25_000L, eggs.getBankedXp());
	}

	@Test
	public void midSessionLoggedInAfterRegionLoadDoesNotSwallowOngoingGains()
	{
		login(HASH_A);
		stat(Skill.AGILITY, 200_000);
		warmUp();
		stat(Skill.AGILITY, 200_100);
		assertEquals(1, received.size());

		// Region crossing / teleport: RuneLite fires LOGGED_IN again after
		// LOADING with NO login-ish state in between. The settle window must not
		// re-arm — the very next real delta (same tick!) still credits.
		xp.handleGameStateChanged(GameState.LOGGED_IN);
		assertTrue("region-load LOGGED_IN must not restart grace", xp.isWarmedUp());
		stat(Skill.AGILITY, 200_250);
		assertEquals(2, received.size());
		assertEquals(150, received.get(1)[1]);

		// but a REAL reconnect (reset state first) still re-arms everything
		xp.handleGameStateChanged(GameState.HOPPING);
		xp.handleGameStateChanged(GameState.LOGGED_IN);
		stat(Skill.AGILITY, 200_400); // first post-hop reading: snapshot only
		warmUp();
		stat(Skill.AGILITY, 200_400);
		assertEquals("post-hop first reading must grant nothing", 2, received.size());
	}

	@Test
	public void accountSwitchSwapsStoreBeforeAttributionAndDropsSnapshots()
	{
		login(HASH_A);
		loginBurst(1_000_000);
		warmUp();
		stat(Skill.ATTACK, 1_050_000); // A earns 50k → the first egg of the day
		assertEquals(1, eggs.getEggBalance());
		assertEquals(HASH_A, store.getAccountHash());

		// switch account under us mid-session (no GameStateChanged seen)
		accountHash = HASH_B;
		stat(Skill.ATTACK, 90_000_000); // B's XP appears: MUST be snapshot-only, on B's file
		assertEquals(HASH_B, store.getAccountHash());
		assertEquals(0, eggs.getEggBalance());          // B starts clean
		assertEquals(1, received.size());               // only A's legitimate delta ever fired

		// B needs a fresh grace window too
		warmUp();
		stat(Skill.ATTACK, 90_000_500);
		assertEquals(2, received.size());
		assertEquals(500, received.get(1)[1]);
	}
}
