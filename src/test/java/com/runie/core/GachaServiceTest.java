package com.runie.core;

import com.google.gson.Gson;
import com.runie.creatures.CreatureDefinition;
import com.runie.creatures.CreatureRegistry;
import com.runie.creatures.Rarity;
import com.runie.state.CreatureInstance;
import com.runie.state.RunieAccountState;
import com.runie.state.RunieStateStore;
import com.runie.state.TestStateStores;
import com.runie.support.FakeClock;
import com.runie.support.ScriptedRandom;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * GachaService: odds, pity, first-copy protection, duplicate stars + shiny
 * rolls, starters (architecture §9, §12.1; odds/pity mirror runie_sim.py
 * Section B; dupes follow the Egg-rework star rules).
 */
public class GachaServiceTest
{
	private static final long HASH = 777L;
	private static final long T0 = 1_784_721_600_000L; // 2026-07-17T00:00:00Z-ish

	private Path dir;
	private ScheduledExecutorService executor;
	private CreatureRegistry registry;
	private RunieStateStore store;
	private FakeClock clock;
	private EggService eggs;

	@Before
	public void setUp() throws IOException
	{
		dir = Files.createTempDirectory("runie-gacha-test");
		executor = Executors.newSingleThreadScheduledExecutor();
		registry = new CreatureRegistry(new Gson());
		registry.load();
		store = TestStateStores.create(new Gson(), executor, registry, dir);
		store.switchAccount(HASH);
		clock = new FakeClock(T0);
		eggs = new EggService(store, clock);
		eggs.rolloverDayIfNeeded();
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private GachaService gacha(RunieRandom rng)
	{
		return new GachaService(eggs, registry, store, clock, rng);
	}

	private RunieAccountState state()
	{
		return store.getState();
	}

	private void noStarters()
	{
		state().gacha.starterPullsRemaining = 0;
	}

	private void giveEggs(int n)
	{
		state().eggs.balance = n;
	}

	private CreatureInstance own(String creatureId)
	{
		CreatureInstance c = CreatureInstance.fresh(T0);
		state().creatures.owned.put(creatureId, c);
		return c;
	}

	private List<CreatureDefinition> pool(Rarity r)
	{
		return registry.byRarity(r);
	}

	// -- odds distribution (the sampler itself, no pity interference) -----

	@Test
	public void baseAndSoftOddsWithinThreeSigmaOver200k()
	{
		for (double[] dist : new double[][]{Economy.BASE_ODDS, Economy.GM_SOFT_ODDS})
		{
			int n = 200_000;
			RunieRandom rng = RunieRandom.seeded(42);
			int[] counts = new int[dist.length];
			for (int i = 0; i < n; i++)
			{
				counts[GachaService.sampleTier(dist, rng)]++;
			}
			for (int t = 0; t < dist.length; t++)
			{
				double expected = n * dist[t];
				double sigma = Math.sqrt(n * dist[t] * (1 - dist[t]));
				assertTrue("tier " + t + ": observed " + counts[t] + " expected " + expected,
					Math.abs(counts[t] - expected) <= 3 * sigma);
			}
		}
	}

	@Test
	public void conditionalSamplingMatchesRenormalizedBase()
	{
		// Elite-pity reroll: BASE[E,M,GM] / 0.05 — Master and GM must stay reachable
		int n = 200_000;
		RunieRandom rng = RunieRandom.seeded(7);
		int[] counts = new int[6];
		for (int i = 0; i < n; i++)
		{
			counts[GachaService.sampleConditional(Economy.BASE_ODDS, Rarity.ELITE.tierIndex(), rng)]++;
		}
		assertEquals(0, counts[0] + counts[1] + counts[2]); // never below the floor
		double sum = Economy.BASE_ODDS[3] + Economy.BASE_ODDS[4] + Economy.BASE_ODDS[5];
		for (int t = 3; t <= 5; t++)
		{
			double p = Economy.BASE_ODDS[t] / sum;
			double sigma = Math.sqrt(n * p * (1 - p));
			assertTrue("tier " + t, Math.abs(counts[t] - n * p) <= 3 * sigma);
		}
	}

	// -- cost & rejection --------------------------------------------------

	@Test
	public void hatchSpendsExactlyOneEgg()
	{
		noStarters();
		giveEggs(5);
		gacha(new ScriptedRandom()).pull();
		assertEquals(1, Economy.HATCH_COST_EGGS);
		assertEquals(4, eggs.getEggBalance());
	}

	@Test
	public void insufficientEggsRejectedWithNoStateChange()
	{
		noStarters();
		giveEggs(0);
		try
		{
			gacha(new ScriptedRandom()).pull();
			fail("expected IllegalStateException");
		}
		catch (IllegalStateException expected)
		{
			// rejected before any roll
		}
		assertEquals(0, eggs.getEggBalance());
		assertEquals(0, state().gacha.totalPulls);
		assertEquals(0, state().gacha.rarePity);
		assertTrue(state().creatures.owned.isEmpty());
	}

	// -- pity --------------------------------------------------------------

	@Test
	public void rarePityGuaranteesRarePlusOnTenthPull()
	{
		noStarters();
		giveEggs(20);
		GachaService g = gacha(new ScriptedRandom()); // all-zero rng: rigged Common stream
		for (int i = 1; i <= 9; i++)
		{
			assertEquals("pull " + i, Rarity.COMMON, g.pull().rarity);
		}
		assertEquals(9, state().gacha.rarePity);
		PullResult tenth = g.pull();
		assertTrue(tenth.rarity.tierIndex() >= Rarity.RARE.tierIndex());
		assertEquals(0, tenth.pity.rarePity); // reset on the hit
	}

	@Test
	public void elitePityGuaranteesElitePlusOnThirtiethPull()
	{
		noStarters();
		giveEggs(60);
		GachaService g = gacha(new ScriptedRandom());
		PullResult last = null;
		for (int i = 1; i <= 30; i++)
		{
			last = g.pull();
			if (i < 30)
			{
				assertTrue("pull " + i, last.rarity.tierIndex() < Rarity.ELITE.tierIndex());
			}
		}
		assertTrue(last.rarity.tierIndex() >= Rarity.ELITE.tierIndex());
		assertEquals(0, last.pity.elitePity);
	}

	@Test
	public void elitePityRerollCanStillLandGrandmaster()
	{
		noStarters();
		giveEggs(2);
		state().gacha.elitePity = Economy.ELITE_PITY_N - 1;
		// 0.5 → Common on the base roll; 0.95 over BASE[E,M,GM]/0.05 → 0.0475 → GM
		PullResult r = gacha(new ScriptedRandom().queueDoubles(0.5, 0.95)).pull();
		assertEquals(Rarity.GRANDMASTER, r.rarity);
	}

	@Test
	public void gmSoftPitySwitchesDistributionAt150()
	{
		noStarters();
		giveEggs(20);
		// r = 0.99: BASE cumulative puts it in Master; GM_SOFT cumulative puts it in GM
		state().gacha.gmPity = Economy.GM_SOFT_AT - 1;
		PullResult below = gacha(new ScriptedRandom().queueDoubles(0.99)).pull();
		assertEquals(Rarity.MASTER, below.rarity);

		state().gacha.gmPity = Economy.GM_SOFT_AT;
		PullResult at = gacha(new ScriptedRandom().queueDoubles(0.99)).pull();
		assertEquals(Rarity.GRANDMASTER, at.rarity);
	}

	@Test
	public void gmHardGuaranteeOnThreeHundredthPull()
	{
		noStarters();
		giveEggs(2);
		state().gacha.gmPity = Economy.GM_HARD_AT - 1; // this IS the 300th GM-less pull
		PullResult r = gacha(new ScriptedRandom().queueDoubles(0.0)).pull(); // rng irrelevant
		assertEquals(Rarity.GRANDMASTER, r.rarity);
		assertEquals(0, r.pity.gmPity);
	}

	@Test
	public void higherTierHitResetsLowerCountersOnly()
	{
		noStarters();
		giveEggs(4);
		// GM (hard pity) resets everything
		state().gacha.rarePity = 5;
		state().gacha.elitePity = 20;
		state().gacha.gmPity = Economy.GM_HARD_AT - 1;
		PullResult gm = gacha(new ScriptedRandom()).pull();
		assertEquals(Rarity.GRANDMASTER, gm.rarity);
		assertEquals(0, gm.pity.rarePity);
		assertEquals(0, gm.pity.elitePity);
		assertEquals(0, gm.pity.gmPity);

		// a Rare hit resets rare only; elite/gm keep counting
		state().gacha.rarePity = 5;
		state().gacha.elitePity = 20;
		state().gacha.gmPity = 100;
		PullResult rare = gacha(new ScriptedRandom().queueDoubles(0.90)).pull(); // 0.86 ≤ r < 0.95 → Rare
		assertEquals(Rarity.RARE, rare.rarity);
		assertEquals(0, rare.pity.rarePity);
		assertEquals(21, rare.pity.elitePity);
		assertEquals(101, rare.pity.gmPity);
	}

	@Test
	public void pityInvariantsHoldOverManyRandomPulls()
	{
		noStarters();
		giveEggs(5_000 * Economy.HATCH_COST_EGGS);
		GachaService g = gacha(RunieRandom.seeded(1234));
		for (int i = 0; i < 5_000; i++)
		{
			PullResult r = g.pull();
			// same invariants runie_sim.py encodes: no 10-window without Rare+,
			// no 30 without Elite+, no 300 without GM
			assertTrue(r.pity.rarePity < Economy.RARE_PITY_N);
			assertTrue(r.pity.elitePity < Economy.ELITE_PITY_N);
			assertTrue(r.pity.gmPity < Economy.GM_HARD_AT);
		}
		// dupes grant NO currency anymore: 5000 hatches spend all 5000 eggs
		assertEquals(0, eggs.getEggBalance());
		assertEquals(5_000, state().gacha.totalPulls);
	}

	// -- first-copy protection ----------------------------------------------

	@Test
	public void firstCopyProtectionRerollsDuplicateToUnownedSameTier()
	{
		noStarters();
		giveEggs(4);
		GachaService g = gacha(new ScriptedRandom()); // Common tier, always index 0
		PullResult first = g.pull();
		assertTrue(first.isNew);
		assertEquals(pool(Rarity.COMMON).get(0).getId(), first.creatureId);

		PullResult second = g.pull(); // index 0 again → owned → reroll to first unowned
		assertTrue(second.isNew);
		assertTrue(second.rerolled);
		assertEquals(pool(Rarity.COMMON).get(1).getId(), second.creatureId);
		assertNotEquals(first.creatureId, second.creatureId);
	}

	@Test
	public void noRerollAfterTwentiethLifetimePull()
	{
		noStarters();
		giveEggs(2);
		String c0 = pool(Rarity.COMMON).get(0).getId();
		own(c0);
		state().gacha.totalPulls = Economy.FIRST_COPY_PROTECTION_PULLS;
		PullResult r = gacha(new ScriptedRandom().queueDoubles(0.0, 0.5)).pull(); // 0.5: shiny roll misses
		assertFalse(r.isNew);
		assertFalse(r.rerolled);
		assertEquals(c0, r.creatureId);
		assertEquals(2, state().creatures.owned.get(c0).copiesPulled);
		assertEquals(1, state().creatures.owned.get(c0).stars); // dupe → +1 star
	}

	@Test
	public void duplicateStandsWhenTierFullyOwned()
	{
		noStarters();
		giveEggs(2);
		for (CreatureDefinition d : pool(Rarity.COMMON))
		{
			own(d.getId());
		}
		state().gacha.totalPulls = 6; // still inside the protection window
		PullResult r = gacha(new ScriptedRandom().queueDoubles(0.0, 0.5)).pull();
		assertFalse(r.isNew);
		assertFalse(r.rerolled);
		assertEquals(1, r.starsAfter);
	}

	// -- duplicates → stars (NO currency refund) -------------------------------

	@Test
	public void duplicateGrantsAStarAndNoCurrency()
	{
		noStarters();
		giveEggs(1);
		state().gacha.totalPulls = 25; // beyond protection: dups stand

		String c0 = pool(Rarity.COMMON).get(0).getId();
		CreatureInstance inst = own(c0);
		PullResult dup = gacha(new ScriptedRandom().queueDoubles(0.0, 0.5)).pull();
		assertFalse(dup.isNew);
		assertEquals(1, dup.starsAfter);
		assertEquals(1, inst.stars);
		assertEquals(2, inst.copiesPulled);
		// the old DUP_REFUND_CRT path is gone: the spent egg stays spent
		assertEquals(0, eggs.getEggBalance());
		assertEquals(0, store.getState().eggs.lifetimeEarned);
	}

	@Test
	public void starsCapAtTwentyFive()
	{
		noStarters();
		giveEggs(3);
		state().gacha.totalPulls = 25;
		String c0 = pool(Rarity.COMMON).get(0).getId();
		CreatureInstance inst = own(c0);
		inst.stars = 24;

		GachaService g = gacha(new ScriptedRandom().queueDoubles(0.0, 0.5, 0.0, 0.5, 0.0, 0.5));
		assertEquals(25, g.pull().starsAfter);        // 24 → 25
		assertEquals(25, g.pull().starsAfter);        // capped
		assertEquals(25, g.pull().starsAfter);        // still capped, dupes still consumed
		assertEquals(25, inst.stars);
		assertEquals(4, inst.copiesPulled);
	}

	// -- shiny (rework part 4) ---------------------------------------------

	/** One dupe of an owned Common with the given pre-dupe stars; returns the result. */
	private PullResult dupeWithShinyRoll(CreatureInstance inst, double shinyRoll)
	{
		giveEggs(eggs.getEggBalance() + 1);
		// draws: tier sample (0.0 → Common), creature nextInt (default 0), shiny roll
		return gacha(new ScriptedRandom().queueDoubles(0.0, shinyRoll)).pull();
	}

	@Test
	public void shinyRollUsesThePreDupeTierRate()
	{
		noStarters();
		state().gacha.totalPulls = 25;
		String c0 = pool(Rarity.COMMON).get(0).getId();
		CreatureInstance inst = own(c0);

		// Bronze (5 stars before dupe): 0.10% — just above misses
		inst.stars = 5;
		PullResult miss = dupeWithShinyRoll(inst, 0.00101);
		assertFalse(miss.becameShiny);
		assertFalse(inst.shiny);

		// Silver (6 stars before dupe): 0.20% — the same roll value now hits
		PullResult hit = dupeWithShinyRoll(inst, 0.00199);
		assertTrue(hit.becameShiny);
		assertTrue(hit.shiny);
		assertTrue(inst.shiny);
	}

	@Test
	public void shinyPostPinkRateIsOnePercent()
	{
		noStarters();
		state().gacha.totalPulls = 25;
		String c0 = pool(Rarity.COMMON).get(0).getId();
		CreatureInstance inst = own(c0);
		inst.stars = Economy.MAX_STARS; // already 5 Pink BEFORE the dupe

		PullResult miss = dupeWithShinyRoll(inst, 0.0101); // just above 1.0%
		assertFalse(miss.becameShiny);
		PullResult hit = dupeWithShinyRoll(inst, 0.0099);  // just below 1.0%
		assertTrue(hit.becameShiny);
		assertEquals(Economy.MAX_STARS, inst.stars);       // stars stay capped
	}

	@Test
	public void shinyRollingStopsOnceShiny()
	{
		noStarters();
		giveEggs(2);
		state().gacha.totalPulls = 25;
		String c0 = pool(Rarity.COMMON).get(0).getId();
		CreatureInstance inst = own(c0);
		inst.shiny = true;
		inst.stars = 3;

		// draws per pull: tier + creature only — a 0.0 second double would be a
		// guaranteed shiny hit if a roll still happened; becameShiny must stay false
		GachaService g = gacha(new ScriptedRandom().queueDoubles(0.0, 0.0, 0.0, 0.0));
		PullResult r1 = g.pull();
		PullResult r2 = g.pull();
		assertFalse(r1.becameShiny);
		assertFalse(r2.becameShiny);
		assertTrue(r1.shiny);                 // still reported shiny (pre-existing)
		assertEquals(5, inst.stars);          // dupes still advance stars to the cap
	}

	@Test
	public void shinyIsDeterministicUnderSeededRng()
	{
		// identical seeds ⇒ identical shiny outcomes, run twice from scratch
		boolean[] first = shinySequenceWithSeed(20260719L);
		boolean[] second = shinySequenceWithSeed(20260719L);
		for (int i = 0; i < first.length; i++)
		{
			assertEquals("pull " + i, first[i], second[i]);
		}
	}

	private boolean[] shinySequenceWithSeed(long seed)
	{
		// fresh state each run (incl. pity counters — determinism needs equal starts)
		state().creatures.owned.clear();
		state().creatures.active = null;
		state().gacha.totalPulls = 25;
		state().gacha.rarePity = 0;
		state().gacha.elitePity = 0;
		state().gacha.gmPity = 0;
		noStarters();
		String c0 = pool(Rarity.COMMON).get(0).getId();
		CreatureInstance inst = own(c0);
		inst.stars = Economy.MAX_STARS; // post-pink 1% — hits occur within a few hundred rolls
		giveEggs(400);
		GachaService g = gacha(RunieRandom.seeded(seed));
		boolean[] out = new boolean[400];
		for (int i = 0; i < out.length; i++)
		{
			PullResult r = g.pull();
			out[i] = r.creatureId.equals(c0) && r.becameShiny;
		}
		return out;
	}

	// -- starter pulls -------------------------------------------------------

	@Test
	public void threeStarterHatchesFreeThirdForcedUncommonPlusAllProtected()
	{
		assertEquals(Economy.STARTER_PULLS, state().gacha.starterPullsRemaining);
		assertEquals(0, eggs.getEggBalance());
		GachaService g = gacha(new ScriptedRandom()); // rigged all-Common stream

		PullResult p1 = g.pull(); // free despite 0 eggs
		assertTrue(p1.starterPull);
		assertTrue(p1.isNew);
		assertEquals(0, eggs.getEggBalance()); // credit 1, spend 1 — uniform ledger
		assertEquals(p1.creatureId, state().creatures.active); // first creature auto-activates

		PullResult p2 = g.pull();
		assertTrue(p2.isNew); // first-copy protected by construction

		PullResult p3 = g.pull();
		assertTrue(p3.starterPull);
		// rigged rng would give Common — starter #3 forces Uncommon+
		assertTrue(p3.rarity.tierIndex() >= Rarity.UNCOMMON.tierIndex());
		assertTrue(p3.isNew);
		assertEquals(0, p3.pity.starterPullsRemaining);

		try
		{
			g.pull(); // starters consumed, 0 eggs
			fail("expected IllegalStateException");
		}
		catch (IllegalStateException expected)
		{
			// hatch power must now be earned
		}
	}

	// -- pull log ------------------------------------------------------------

	@Test
	public void recentPullsLoggedAndTailCapped()
	{
		noStarters();
		giveEggs(60 * Economy.HATCH_COST_EGGS);
		GachaService g = gacha(RunieRandom.seeded(99));
		for (int i = 0; i < 60; i++)
		{
			g.pull();
		}
		List<RunieAccountState.PullRecord> tail = state().gacha.pullHistoryTail;
		assertEquals(GachaService.PULL_HISTORY_TAIL_MAX, tail.size());
		assertEquals(60, tail.get(tail.size() - 1).n); // newest kept, oldest dropped
		assertEquals(11, tail.get(0).n);
	}
}
