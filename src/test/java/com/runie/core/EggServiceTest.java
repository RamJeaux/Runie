package com.runie.core;

import com.google.gson.Gson;
import com.runie.state.RunieStateStore;
import com.runie.state.TestStateStores;
import com.runie.support.FakeClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Skill;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Egg accrual (rework part 1): daily-reset tier costs, NO daily cap, raw 1:1
 * banked-XP carry across day rollover (only the daily egg ordinal resets),
 * spend/credit rules. Eggs are the single currency.
 */
public class EggServiceTest
{
	private static final long HASH = 4242L;
	// 2026-07-17T12:00:00Z
	private static final long NOON_UTC = 1_784_721_600_000L + 43_200_000L - 86_400_000L;

	private Path dir;
	private ScheduledExecutorService executor;
	private RunieStateStore store;
	private FakeClock clock;
	private EggService eggs;

	@Before
	public void setUp() throws IOException
	{
		dir = Files.createTempDirectory("runie-egg-test");
		executor = Executors.newSingleThreadScheduledExecutor();
		store = TestStateStores.create(new Gson(), executor, null, dir);
		store.switchAccount(HASH);
		clock = new FakeClock(NOON_UTC);
		eggs = new EggService(store, clock);
		eggs.rolloverDayIfNeeded(); // anchor the day
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private void gain(int xp)
	{
		eggs.onXpGained(Skill.ATTACK, xp, 0);
	}

	// -- the tier ladder ---------------------------------------------------

	@Test
	public void tierCostLadderMatchesSpec()
	{
		// k = 1,2,3 → 50k; 4,5 → 100k; 6,7,8 → 250k; ≥9 → 500k forever
		assertEquals(50_000L, Economy.eggCostXp(1));
		assertEquals(50_000L, Economy.eggCostXp(2));
		assertEquals(50_000L, Economy.eggCostXp(3));
		assertEquals(100_000L, Economy.eggCostXp(4));
		assertEquals(100_000L, Economy.eggCostXp(5));
		assertEquals(250_000L, Economy.eggCostXp(6));
		assertEquals(250_000L, Economy.eggCostXp(7));
		assertEquals(250_000L, Economy.eggCostXp(8));
		assertEquals(500_000L, Economy.eggCostXp(9));
		assertEquals(500_000L, Economy.eggCostXp(10));
		assertEquals(500_000L, Economy.eggCostXp(1_000)); // stays 500k — no cap tier
		assertEquals(50_000L, Economy.eggCostXp(0));      // defensive clamp
	}

	@Test
	public void accrualWalksTheLadderExactly()
	{
		gain(49_999);
		assertEquals(0, eggs.getEggBalance());
		assertEquals(49_999L, eggs.getBankedXp());
		gain(1);                        // 50,000 → egg 1
		assertEquals(1, eggs.getEggBalance());
		assertEquals(0L, eggs.getBankedXp());
		assertEquals(50_000L, eggs.getNextEggCostXp()); // egg 2 still 50k

		gain(100_000);                  // eggs 2 + 3
		assertEquals(3, eggs.getEggBalance());
		assertEquals(100_000L, eggs.getNextEggCostXp()); // egg 4 costs 100k

		gain(100_000);                  // egg 4
		gain(100_000);                  // egg 5
		assertEquals(5, eggs.getEggBalance());
		assertEquals(250_000L, eggs.getNextEggCostXp()); // egg 6 costs 250k

		gain(750_000);                  // eggs 6,7,8
		assertEquals(8, eggs.getEggBalance());
		assertEquals(500_000L, eggs.getNextEggCostXp()); // egg 9 costs 500k
	}

	@Test
	public void singleBigDeltaCrossesMultipleTiers()
	{
		// 50k*3 + 100k*2 + 250k*3 = 1.1M for eggs 1–8, +500k → egg 9
		gain(1_600_000);
		assertEquals(9, eggs.getEggBalance());
		assertEquals(9, eggs.getEggsEarnedToday());
		assertEquals(0L, eggs.getBankedXp());
	}

	@Test
	public void noDailyCap_ninthAndBeyondStayAt500k()
	{
		gain(1_100_000);                // eggs 1–8
		assertEquals(8, eggs.getEggBalance());
		for (int k = 9; k <= 30; k++)   // 22 more eggs, all at the flat 500k rate
		{
			gain(500_000);
			assertEquals(k, eggs.getEggBalance());
			assertEquals(500_000L, eggs.getNextEggCostXp());
		}
		assertEquals(30, eggs.getEggsEarnedToday()); // way past the old 25 cap
	}

	@Test
	public void fractionalProgressAccumulatesAcrossDeltas()
	{
		gain(20_000);
		gain(20_000);
		assertEquals(0, eggs.getEggBalance());
		assertEquals(40_000L, eggs.getBankedXp());
		gain(10_000);
		assertEquals(1, eggs.getEggBalance());
		assertEquals(0L, eggs.getBankedXp());
	}

	// -- daily reset ---------------------------------------------------------

	@Test
	public void dailyResetRestartsTheLadder()
	{
		gain(1_600_000);                // 9 eggs today; next would cost 500k
		assertEquals(500_000L, eggs.getNextEggCostXp());

		clock.advanceDays(1);
		eggs.rolloverDayIfNeeded();

		assertEquals(0, eggs.getEggsEarnedToday());
		assertEquals(50_000L, eggs.getNextEggCostXp()); // ladder restarts at 50k
		assertEquals(9, eggs.getEggBalance());          // balance untouched

		gain(50_000);                                   // first egg of the new day
		assertEquals(10, eggs.getEggBalance());
	}

	@Test
	public void rolloverCarriesBankedXpRawOneToOne()
	{
		gain(1_100_000 + 250_000);      // 8 eggs + half of the 500k ninth egg
		assertEquals(8, eggs.getEggBalance());
		assertEquals(250_000L, eggs.getBankedXp());

		clock.advanceDays(1);
		eggs.rolloverDayIfNeeded();

		// banked XP is a continuous running counter: carried RAW, untouched;
		// only the daily egg ordinal (tier position) resets
		assertEquals(250_000L, eggs.getBankedXp());
		assertEquals(0, eggs.getEggsEarnedToday());
		assertEquals(50_000L, eggs.getNextEggCostXp()); // ladder restarted at day-1 rate

		// the intended morning windfall: the raw carry settles against the
		// cheaper restarted ladder on the next gain (50k+50k+50k+100k = 250k)
		gain(1);
		assertEquals(12, eggs.getEggBalance());
		assertEquals(4, eggs.getEggsEarnedToday());
		assertEquals(1L, eggs.getBankedXp());
	}

	@Test
	public void multiDayOfflineRolloverJustReanchors()
	{
		gain(75_000);                   // 1 egg + 25k banked toward egg 2
		clock.advanceDays(4);
		eggs.rolloverDayIfNeeded();
		assertEquals(1, eggs.getEggBalance());
		assertEquals(0, eggs.getEggsEarnedToday());
		assertEquals(25_000L, eggs.getBankedXp()); // raw carry — untouched
	}

	@Test
	public void dayNeverRegresses()
	{
		gain(50_000);
		String day = store.getState().eggs.currentDayUtc;
		clock.advanceMs(-2 * 86_400_000L);  // clock rolled back (GuardedClock prevents in prod; defend anyway)
		eggs.rolloverDayIfNeeded();
		assertEquals(day, store.getState().eggs.currentDayUtc);
		assertEquals(1, eggs.getEggBalance());
	}

	// -- initial snapshot / safety --------------------------------------------

	@Test
	public void noDeltasMeansNoEggs_initialSnapshotGrantsNothing()
	{
		assertEquals(0, eggs.getEggBalance());
		assertEquals(0L, eggs.getBankedXp());
		assertEquals(0, eggs.getEggsEarnedToday());
	}

	@Test
	public void nonPositiveDeltaIsRejectedDefensively()
	{
		eggs.onXpGained(Skill.ATTACK, 0, 0);
		eggs.onXpGained(Skill.ATTACK, -5_000, 0);
		assertEquals(0, eggs.getEggBalance());
		assertEquals(0L, eggs.getBankedXp());
	}

	@Test
	public void xpAccruesEggsButTouchesNoCreatureWhenNoneActive()
	{
		assertTrue(store.getState().creatures.owned.isEmpty());
		assertEquals(null, store.getState().creatures.active);
		gain(100_000);
		assertEquals(2, eggs.getEggBalance());
		assertTrue(store.getState().creatures.owned.isEmpty());
	}

	// -- spending / crediting ----------------------------------------------

	@Test
	public void spendEggsRequiresSufficientBalance()
	{
		gain(150_000); // 3 eggs
		assertFalse(eggs.spendEggs(4, "hatch"));
		assertEquals(3, eggs.getEggBalance());
		assertTrue(eggs.spendEggs(3, "hatch"));
		assertEquals(0, eggs.getEggBalance());
	}

	@Test
	public void creditEggsIsOutsideTheAccrualLadder()
	{
		gain(50_000); // 1 accrued egg, ladder at k=1
		eggs.creditEggs(2, EggSource.EVOLUTION);
		assertEquals(3, eggs.getEggBalance());
		assertEquals(1, eggs.getEggsEarnedToday()); // ladder position untouched
		assertEquals(50_000L, eggs.getNextEggCostXp());
	}

	// -- persistence ------------------------------------------------------------

	@Test
	public void bankedProgressSurvivesSaveLoad() throws IOException
	{
		gain(1_100_000 + 123_456);      // 8 eggs + 123,456 banked toward the 500k ninth
		store.flush();

		RunieStateStore reloaded = TestStateStores.create(new Gson(), executor, null, dir);
		reloaded.switchAccount(HASH);
		EggService eggs2 = new EggService(reloaded, clock);
		assertEquals(8, eggs2.getEggBalance());
		assertEquals(8, eggs2.getEggsEarnedToday());
		assertEquals(123_456L, eggs2.getBankedXp());
		assertEquals(500_000L, eggs2.getNextEggCostXp());

		eggs2.onXpGained(Skill.ATTACK, 500_000 - 123_456, 0);
		assertEquals(9, eggs2.getEggBalance());
	}
}
