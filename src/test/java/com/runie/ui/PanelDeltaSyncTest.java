package com.runie.ui;

import com.google.gson.Gson;
import com.runie.assets.ArtStyleService;
import com.runie.assets.AssetLoader;
import com.runie.core.AccountContext;
import com.runie.core.EggService;
import com.runie.core.GachaService;
import com.runie.core.ProgressionService;
import com.runie.core.RunieRandom;
import com.runie.core.XpDeltaService;
import com.runie.creatures.CreatureRegistry;
import com.runie.overlay.RuniePullOverlay;
import com.runie.state.CreatureInstance;
import com.runie.state.RunieStateStore;
import com.runie.state.TestStateStores;
import com.runie.support.FakeClock;
import com.runie.support.TestRunieConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.SwingUtilities;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Timing/sync regression tests for the live-play bug: "+1 Egg floats over the
 * companion, but the panel egg balance / banked XP only caught up minutes
 * later." The toast polls the live {@link EggService} balance every frame; the
 * panel used to refresh only on rare events (level-ups, hatches, region
 * loads). The fix wires a third XpDelta listener — registered AFTER EggService
 * and ProgressionService, exactly like {@code RuniePlugin.startUp()} — that
 * refreshes the panel from the SAME validated delta, so toast, credited
 * balance, creature XP mirror, and panel all derive from one event.
 */
public class PanelDeltaSyncTest
{
	private static final long HASH = 7777L;

	private Path dir;
	private ScheduledExecutorService executor;
	private RunieStateStore store;
	private XpDeltaService xp;
	private EggService eggs;
	private ProgressionService progression;
	private RuniePanel panel;
	private long accountHash = AccountContext.NO_ACCOUNT;

	/** Balance sampled inside the panel-sync listener — the toast's poll value. */
	private final List<Integer> toastPollSamples = new ArrayList<>();
	/** Creature XP sampled inside the panel-sync listener — the mirror's value. */
	private final List<Long> creatureXpSamples = new ArrayList<>();
	private int refreshLaterCalls;

	@Before
	public void setUp() throws IOException
	{
		System.setProperty("java.awt.headless", "true");
		dir = Files.createTempDirectory("runie-panel-sync-test");
		executor = Executors.newSingleThreadScheduledExecutor();
		Gson gson = new Gson();
		CreatureRegistry registry = new CreatureRegistry(gson);
		registry.load();
		store = TestStateStores.create(gson, executor, registry, dir);
		FakeClock clock = new FakeClock(1_784_678_400_000L);
		eggs = new EggService(store, clock);
		progression = new ProgressionService(store, registry, eggs);
		GachaService gacha = new GachaService(eggs, registry, store, clock, RunieRandom.seeded(20260719L));
		AssetLoader assets = new AssetLoader();
		TestRunieConfig config = new TestRunieConfig();
		ArtStyleService styles = new ArtStyleService(config, () -> "true", v -> { });
		RuniePullOverlay pullOverlay = new RuniePullOverlay(registry, progression, assets, styles,
			config, clock, Runnable::run);
		UiContext ctx = new UiContext(eggs, gacha, progression, registry, assets, styles,
			store, config, pullOverlay, Runnable::run, () -> false);
		panel = new RuniePanel(ctx);

		// EXACT RuniePlugin.startUp() wiring order: economy + progression first,
		// then the same-delta panel sync. The sync listener therefore observes
		// the post-delta state — assert that by sampling what the toast polls.
		xp = new XpDeltaService(() -> accountHash, store);
		xp.addListener(eggs);
		xp.addListener(progression);
		xp.addListener((skill, delta, newTotalXp) ->
		{
			toastPollSamples.add(eggs.getEggBalance());
			creatureXpSamples.add(progression.getActiveCreature().map(c -> c.xp).orElse(-1L));
			refreshLaterCalls++;
			panel.refreshLater();
		});
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	// -- helpers ----------------------------------------------------------

	private void login()
	{
		accountHash = HASH;
		xp.handleGameStateChanged(GameState.LOGGING_IN);
		xp.handleGameStateChanged(GameState.LOGGED_IN);
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

	private void flushEdt()
	{
		try
		{
			SwingUtilities.invokeAndWait(() -> { });
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	private void deployCreature(String id)
	{
		CreatureInstance c = CreatureInstance.fresh(0L);
		store.getState().creatures.owned.put(id, c);
		assertTrue(progression.setActiveCreature(id));
	}

	// -- tests ------------------------------------------------------------

	@Test
	public void deltaUpdatesToastBalanceCreatureXpAndPanelFromTheSameEvent()
	{
		login();
		stat(Skill.FLETCHING, 300_000);       // first sighting: snapshot only, loads state
		warmUp();
		deployCreature("grubnak");

		// +60k: one 50k accrual egg + 10k banked, mirrored 1:1 onto grubnak —
		// whose level jump past the ~L40 stage-2 threshold ALSO grants the +1
		// evolution-reward egg on this very delta (balance 2 total)
		stat(Skill.FLETCHING, 360_000);

		// the sync listener (= the toast's poll) saw the credited balance,
		// including the evolution reward, and the mirrored creature XP inside
		// the SAME event dispatch — nothing is deferred to a later tick/timer
		assertEquals(1, toastPollSamples.size());
		assertEquals(2, (int) toastPollSamples.get(0));
		assertEquals(60_000L, (long) creatureXpSamples.get(0));
		assertEquals(1, refreshLaterCalls);

		// and the panel, refreshed by that same delta, shows the same numbers
		flushEdt();
		assertEquals("2 Eggs", panel.headerEggsText());
		assertTrue("panel banked-XP line must show the post-delta bank: "
			+ panel.headerTodayText(),
			panel.headerTodayText().contains(String.format("%,d / %,d XP", 10_000L, 50_000L)));
	}

	@Test
	public void rapidBurstCoalescesButPanelEndsOnFinalValue()
	{
		login();
		stat(Skill.MINING, 0);
		warmUp();

		// 4 deltas inside one tick — every event credits immediately (ladder:
		// eggs 1-3 cost 50k each, the 4th costs 100k, so the last 50k banks)
		for (int i = 1; i <= 4; i++)
		{
			stat(Skill.MINING, i * 50_000);
			assertEquals("credit must land with delta " + i,
				Math.min(i, 3), (int) toastPollSamples.get(i - 1));
		}
		assertEquals(50_000L, eggs.getBankedXp());
		// ...and after one EDT hop the panel shows the final, fully-credited state
		flushEdt();
		flushEdt(); // second hop: a re-queue scheduled mid-refresh completes here
		assertEquals("3 Eggs", panel.headerEggsText());
	}

	@Test
	public void loginBurstGrantsNothingAndPanelShowsZero()
	{
		login();
		// a maxed account's lifetime XP streams in on login
		for (Skill s : Skill.values())
		{
			stat(s, 13_034_431);
		}
		warmUp();
		flushEdt();

		assertTrue("login burst must never reach the panel-sync listener", toastPollSamples.isEmpty());
		assertEquals(0, eggs.getEggBalance());
		assertEquals("0 Eggs", panel.headerEggsText());
	}

	@Test
	public void reRefreshingThePanelNeverDoubleCounts()
	{
		login();
		stat(Skill.SMITHING, 100_000);
		warmUp();
		stat(Skill.SMITHING, 150_000);        // exactly one 50k egg

		flushEdt();
		assertEquals("1 Egg", panel.headerEggsText());

		// refresh is a pure read: repeating it (EDT-queued or direct) must not
		// change the balance or the display
		panel.refreshLater();
		panel.refreshLater();
		flushEdt();
		SwingUtilities.invokeLater(panel::refresh);
		flushEdt();
		assertEquals(1, eggs.getEggBalance());
		assertEquals(0L, eggs.getBankedXp());
		assertEquals("1 Egg", panel.headerEggsText());
	}
}
