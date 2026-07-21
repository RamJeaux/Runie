package com.runie.core;

import com.google.gson.Gson;
import com.runie.creatures.CreatureDefinition;
import com.runie.creatures.CreatureRegistry;
import com.runie.state.CreatureInstance;
import com.runie.state.RunieAccountState;
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
import net.runelite.api.Skill;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ProgressionService: XP mirror, OSRS-table leveling, evolution thresholds,
 * prestige (irreversible, single tier, golden aura at 120) — Epic 4, §12.1.
 */
public class ProgressionServiceTest
{
	private static final long HASH = 333L;

	private Path dir;
	private ScheduledExecutorService executor;
	private CreatureRegistry registry;
	private RunieStateStore store;
	private EggService eggs;
	private ProgressionService progression;

	@Before
	public void setUp() throws IOException
	{
		dir = Files.createTempDirectory("runie-progression-test");
		executor = Executors.newSingleThreadScheduledExecutor();
		registry = new CreatureRegistry(new Gson());
		registry.load();
		store = TestStateStores.create(new Gson(), executor, registry, dir);
		store.switchAccount(HASH);
		eggs = new EggService(store, new FakeClock(1_784_721_600_000L));
		progression = new ProgressionService(store, registry, eggs);
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private RunieAccountState state()
	{
		return store.getState();
	}

	private CreatureInstance own(String id)
	{
		CreatureInstance c = CreatureInstance.fresh(0);
		state().creatures.owned.put(id, c);
		return c;
	}

	private CreatureInstance ownActive(String id)
	{
		CreatureInstance c = own(id);
		state().creatures.active = id;
		return c;
	}

	private void gain(int delta)
	{
		progression.onXpGained(Skill.WOODCUTTING, delta, 0);
	}

	// -- XP mirror ---------------------------------------------------------

	@Test
	public void xpMirrorsOneToOneToActiveCreature()
	{
		CreatureInstance c = ownActive("grubnak");
		gain(1_000);
		gain(234);
		assertEquals(1_234, c.xp);
		assertEquals(1_234, c.lifetimeXp);
	}

	@Test
	public void noActiveCreatureIsANoOp()
	{
		CreatureInstance c = own("grubnak"); // owned but NOT active
		gain(5_000);
		assertEquals(0, c.xp); // XP feeds no one — no accrual, no banking
		assertEquals(0, c.lifetimeXp);
	}

	@Test
	public void xpFeedsOnlyTheActiveCreature()
	{
		CreatureInstance a = ownActive("grubnak");
		CreatureInstance b = own("skitterscour");
		gain(100);
		assertTrue(progression.setActiveCreature("skitterscour"));
		gain(50);
		assertEquals(100, a.xp);
		assertEquals(50, b.xp);
	}

	// -- active-companion selection ------------------------------------------

	@Test
	public void setActiveCreatureRequiresOwnership()
	{
		own("grubnak");
		assertFalse(progression.setActiveCreature("malgrave")); // in registry, not owned
		assertFalse(progression.setActiveCreature("not-a-creature"));
		assertTrue(progression.setActiveCreature("grubnak"));
		assertEquals("grubnak", progression.getActiveCreatureId().get());
		assertTrue(progression.setActiveCreature(null)); // clear selection
		assertFalse(progression.getActiveCreatureId().isPresent());
		gain(100); // and mirroring stops
		assertEquals(0, state().creatures.owned.get("grubnak").xp);
	}

	@Test
	public void setActiveNotifiesListenersImmediately()
	{
		// Bug 2: making a creature active must signal observers right away —
		// the plugin wires this to AnimationService.refresh(), so the companion
		// deploys without waiting for an unrelated config change.
		own("grubnak");
		own("skitterscour");
		List<String> changes = new ArrayList<>();
		progression.addListener(new ProgressionService.ProgressionListener()
		{
			@Override
			public void onActiveChanged(String activeCreatureId)
			{
				changes.add(activeCreatureId);
			}
		});

		assertTrue(progression.setActiveCreature("grubnak"));
		assertEquals(1, changes.size());
		assertEquals("grubnak", changes.get(0));

		assertFalse(progression.setActiveCreature("malgrave")); // not owned: rejected, NO signal
		assertEquals(1, changes.size());

		assertTrue(progression.setActiveCreature("skitterscour"));
		assertTrue(progression.setActiveCreature(null)); // clearing also signals (undeploy)
		assertEquals(3, changes.size());
		assertEquals("skitterscour", changes.get(1));
		assertEquals(null, changes.get(2));
	}

	// -- leveling ---------------------------------------------------------------

	@Test
	public void levelFollowsOsrsTableWithCap99()
	{
		CreatureInstance c = ownActive("grubnak");
		assertEquals(1, progression.levelOf("grubnak"));
		gain(83);
		assertEquals(2, progression.levelOf("grubnak")); // L2 = 83 XP
		c.xp = XpTable.xpForLevel(99) - 1;
		assertEquals(98, progression.levelOf("grubnak"));
		gain(1);
		assertEquals(99, progression.levelOf("grubnak"));
		gain(50_000_000); // beyond 13,034,431 — un-prestiged cap binds
		assertEquals(99, progression.levelOf("grubnak"));
	}

	@Test
	public void levelUpEventFires()
	{
		ownActive("grubnak");
		List<int[]> ups = new ArrayList<>();
		progression.addListener(new ProgressionService.ProgressionListener()
		{
			@Override
			public void onLevelUp(String creatureId, int oldLevel, int newLevel)
			{
				assertEquals("grubnak", creatureId);
				ups.add(new int[]{oldLevel, newLevel});
			}
		});
		gain(82); // still level 1
		assertTrue(ups.isEmpty());
		gain(1);  // 83 → level 2
		assertEquals(1, ups.size());
		assertEquals(1, ups.get(0)[0]);
		assertEquals(2, ups.get(0)[1]);
	}

	// -- evolution ---------------------------------------------------------------

	@Test
	public void evolutionThresholdsFollowRoster()
	{
		// grubnak stages unlock at L1 / L40 / L75; ratterbone's stage 3 at L78
		CreatureDefinition grubnak = registry.byId("grubnak");
		assertEquals(1, progression.evolutionStageFor(grubnak, 39, false));
		assertEquals(2, progression.evolutionStageFor(grubnak, 40, false));
		assertEquals(2, progression.evolutionStageFor(grubnak, 74, false));
		assertEquals(3, progression.evolutionStageFor(grubnak, 75, false));

		CreatureDefinition ratterbone = registry.byId("ratterbone");
		assertEquals(2, progression.evolutionStageFor(ratterbone, 77, false));
		assertEquals(3, progression.evolutionStageFor(ratterbone, 78, false));
	}

	@Test
	public void stageChangeSignalFiresOnEvolution()
	{
		CreatureInstance c = ownActive("grubnak");
		List<int[]> evolutions = new ArrayList<>();
		progression.addListener(new ProgressionService.ProgressionListener()
		{
			@Override
			public void onStageChange(String creatureId, int oldStage, int newStage)
			{
				assertEquals("grubnak", creatureId);
				evolutions.add(new int[]{oldStage, newStage});
			}
		});

		c.xp = XpTable.xpForLevel(40) - 1;      // level 39, stage 1
		assertEquals(1, progression.currentStageOf("grubnak"));
		gain(1);                                 // → level 40: stage 1 → 2
		assertEquals(2, progression.currentStageOf("grubnak"));

		c.xp = XpTable.xpForLevel(75) - 1;      // level 74, stage 2
		gain(1);                                 // → level 75: stage 2 → 3
		assertEquals(3, progression.currentStageOf("grubnak"));

		assertEquals(2, evolutions.size());
		assertEquals(2, evolutions.get(0)[1]);
		assertEquals(3, evolutions.get(1)[1]);
	}

	// -- evolution egg reward (rework part 2) ----------------------------------

	@Test
	public void evolutionGrantsOneEggPerCrossing()
	{
		CreatureInstance c = ownActive("grubnak"); // stages at L1 / L40 / L75
		assertEquals(0, eggs.getEggBalance());

		c.xp = XpTable.xpForLevel(40) - 1;
		gain(1);                                   // stage 1 → 2
		assertEquals(1, eggs.getEggBalance());
		assertEquals(2, c.evolutionRewardedStage);

		c.xp = XpTable.xpForLevel(75) - 1;
		gain(1);                                   // stage 2 → 3
		assertEquals(2, eggs.getEggBalance());
		assertEquals(3, c.evolutionRewardedStage);
	}

	@Test
	public void evolutionCrossingTwoStagesInOneDeltaGrantsTwoEggs()
	{
		CreatureInstance c = ownActive("grubnak");
		gain((int) XpTable.xpForLevel(80));        // level 1 → 80: stage 1 → 3 in one delta
		assertEquals(3, progression.currentStageOf("grubnak"));
		assertEquals(2, eggs.getEggBalance());
		assertEquals(3, c.evolutionRewardedStage);
	}

	@Test
	public void evolutionRewardIsGrantedExactlyOncePerCrossing()
	{
		CreatureInstance c = ownActive("grubnak");
		c.xp = XpTable.xpForLevel(40) - 1;
		gain(1);                                   // stage 1 → 2: +1 egg
		assertEquals(1, eggs.getEggBalance());

		// simulate a re-check / replayed crossing: drop XP below the threshold
		// and cross again — the persisted watermark must block a second grant
		c.xp = XpTable.xpForLevel(40) - 1;
		gain(1);
		assertEquals(1, eggs.getEggBalance());     // still exactly one
		assertEquals(2, c.evolutionRewardedStage);
	}

	@Test
	public void evolutionRewardWatermarkSurvivesReload() throws IOException
	{
		CreatureInstance c = ownActive("grubnak");
		c.xp = XpTable.xpForLevel(40) - 1;
		gain(1);                                   // stage 1 → 2: +1 egg
		assertEquals(1, eggs.getEggBalance());
		store.flush();

		// relaunch: fresh store + services over the same file
		RunieStateStore store2 = TestStateStores.create(new Gson(), executor, registry, dir);
		store2.switchAccount(HASH);
		EggService eggs2 = new EggService(store2, new FakeClock(1_784_721_600_000L));
		ProgressionService progression2 = new ProgressionService(store2, registry, eggs2);
		CreatureInstance c2 = store2.getState().creatures.owned.get("grubnak");
		assertEquals(2, c2.evolutionRewardedStage); // watermark persisted
		assertEquals(1, store2.getState().eggs.balance);

		// replay the same crossing after reload — no double grant
		c2.xp = XpTable.xpForLevel(40) - 1;
		progression2.onXpGained(Skill.WOODCUTTING, 1, 0);
		assertEquals(1, eggs2.getEggBalance());

		// the NEXT crossing still grants normally
		c2.xp = XpTable.xpForLevel(75) - 1;
		progression2.onXpGained(Skill.WOODCUTTING, 1, 0);
		assertEquals(2, eggs2.getEggBalance());
	}

	@Test
	public void legacyCreatureMidStageGetsNoRetroactiveGrant()
	{
		// legacy documents pre-date evolutionRewardedStage (0): a creature already
		// at stage 2 crossing to 3 must earn ONE egg, not a retroactive two
		CreatureInstance c = ownActive("grubnak");
		c.xp = XpTable.xpForLevel(60);             // stage 2 already
		c.evolutionRewardedStage = 0;              // as loaded from an old save
		gain((int) (XpTable.xpForLevel(75) - XpTable.xpForLevel(60))); // cross 2 → 3
		assertEquals(1, eggs.getEggBalance());
		assertEquals(3, c.evolutionRewardedStage);
	}

	// -- golden L120 milestone reward (owner request) ---------------------------

	@Test
	public void reachingLevel120GrantsFiveEggsExactlyOnce()
	{
		CreatureInstance c = ownActive("grubnak");
		c.xp = XpTable.xpForLevel(99);
		assertEquals(PrestigeResult.SUCCESS, progression.prestige("grubnak"));
		assertEquals(0, eggs.getEggBalance());
		assertFalse(c.goldenRewardGranted);

		c.xp = XpTable.xpForLevel(120) - 1;        // level 119
		gain(1);                                   // → level 120: golden milestone
		assertEquals(120, progression.levelOf("grubnak"));
		assertEquals(Economy.GOLDEN_MILESTONE_REWARD_EGGS, eggs.getEggBalance()); // +5
		assertTrue(c.goldenRewardGranted);

		// simulate a re-check / replayed crossing: drop XP below the cap and
		// cross again — the persisted grant-once flag must block a second grant
		c.xp = XpTable.xpForLevel(120) - 1;
		gain(1);
		assertEquals(Economy.GOLDEN_MILESTONE_REWARD_EGGS, eggs.getEggBalance()); // still exactly 5
	}

	@Test
	public void goldenMilestoneGrantSurvivesReload() throws IOException
	{
		CreatureInstance c = ownActive("grubnak");
		c.xp = XpTable.xpForLevel(99);
		assertEquals(PrestigeResult.SUCCESS, progression.prestige("grubnak"));
		c.xp = XpTable.xpForLevel(120) - 1;
		gain(1);                                   // milestone: +5 eggs
		assertEquals(5, eggs.getEggBalance());
		store.flush();

		// relaunch: fresh store + services over the same file
		RunieStateStore store2 = TestStateStores.create(new Gson(), executor, registry, dir);
		store2.switchAccount(HASH);
		EggService eggs2 = new EggService(store2, new FakeClock(1_784_721_600_000L));
		ProgressionService progression2 = new ProgressionService(store2, registry, eggs2);
		CreatureInstance c2 = store2.getState().creatures.owned.get("grubnak");
		assertTrue(c2.goldenRewardGranted);        // watermark persisted

		// replay the crossing after reload — no double grant
		c2.xp = XpTable.xpForLevel(120) - 1;
		progression2.onXpGained(Skill.WOODCUTTING, 1, 0);
		assertEquals(5, eggs2.getEggBalance());
	}

	@Test
	public void legacyCreatureAlreadyPast120GetsNoRetroactiveGrant()
	{
		// legacy documents pre-date goldenRewardGranted (false): a creature that
		// mastered L120 before the rework must NOT be granted retroactively —
		// its level is capped at 120 and can never cross the milestone again
		CreatureInstance c = ownActive("grubnak");
		c.prestigeCount = 1;
		c.xp = XpTable.xpForLevel(120) + 5_000_000; // already past 120 in the old save
		c.goldenRewardGranted = false;              // as loaded from a legacy document
		assertEquals(120, progression.levelOf("grubnak"));

		gain(1_000_000);                            // more XP at the cap — no crossing
		assertEquals(0, eggs.getEggBalance());      // no retroactive grant
		assertFalse(c.goldenRewardGranted);
	}

	@Test
	public void stageCrossingsStillGrantOneEggEachAlongsideTheMilestone()
	{
		// the two evolution crossings keep their +1 each; L120 adds +5 on top
		CreatureInstance c = ownActive("grubnak"); // stages at L1 / L40 / L75
		gain((int) XpTable.xpForLevel(99));        // crosses both stages → +2 eggs
		assertEquals(2, eggs.getEggBalance());
		assertEquals(PrestigeResult.SUCCESS, progression.prestige("grubnak"));

		c.xp = XpTable.xpForLevel(120) - 1;
		gain(1);                                   // golden milestone → +5 more
		assertEquals(7, eggs.getEggBalance());
	}

	// -- prestige -------------------------------------------------------------

	@Test
	public void prestigeRequiresLevel99()
	{
		CreatureInstance c = ownActive("grubnak");
		c.xp = XpTable.xpForLevel(99) - 1; // level 98
		assertEquals(PrestigeResult.NOT_LEVEL_99, progression.prestige("grubnak"));
		assertEquals(XpTable.xpForLevel(99) - 1, c.xp); // untouched
		assertEquals(0, c.prestigeCount);
		assertEquals(PrestigeResult.NOT_OWNED, progression.prestige("malgrave"));
	}

	@Test
	public void prestigeResetsXpRaisesCapKeepsLookAndLifetimeXp()
	{
		CreatureInstance c = ownActive("grubnak");
		c.xp = XpTable.xpForLevel(99);
		c.lifetimeXp = XpTable.xpForLevel(99);
		List<String> prestiged = new ArrayList<>();
		progression.addListener(new ProgressionService.ProgressionListener()
		{
			@Override
			public void onPrestige(String creatureId)
			{
				prestiged.add(creatureId);
			}
		});

		assertEquals(PrestigeResult.SUCCESS, progression.prestige("grubnak"));
		assertEquals(0, c.xp);                                   // reset to level 1
		assertEquals(1, progression.levelOf("grubnak"));
		assertEquals(1, c.prestigeCount);
		assertEquals(XpTable.xpForLevel(99), c.lifetimeXp);      // preserved
		assertEquals(3, progression.currentStageOf("grubnak"));  // keeps final evolved look
		assertEquals(AuraState.PRESTIGE, progression.auraOf("grubnak"));
		assertEquals(1, prestiged.size());

		gain((int) XpTable.xpForLevel(100));                     // cap is now 120
		assertEquals(100, progression.levelOf("grubnak"));
	}

	@Test
	public void prestigeIsIrreversibleAndSingleTier()
	{
		CreatureInstance c = ownActive("grubnak");
		c.xp = XpTable.xpForLevel(99);
		assertEquals(PrestigeResult.SUCCESS, progression.prestige("grubnak"));

		// back at L99 (and even L120): no second reset path, no un-prestige API
		c.xp = XpTable.xpForLevel(120);
		assertEquals(PrestigeResult.ALREADY_PRESTIGED, progression.prestige("grubnak"));
		assertEquals(XpTable.xpForLevel(120), c.xp); // nothing reset
		assertEquals(1, c.prestigeCount);
	}

	@Test
	public void goldenAuraAtLevel120()
	{
		CreatureInstance c = ownActive("grubnak");
		assertEquals(AuraState.NONE, progression.auraOf("grubnak")); // un-prestiged: no aura
		c.xp = XpTable.xpForLevel(99);
		assertEquals(PrestigeResult.SUCCESS, progression.prestige("grubnak"));

		c.xp = XpTable.xpForLevel(120) - 1;                      // level 119
		assertEquals(AuraState.PRESTIGE, progression.auraOf("grubnak"));
		gain(1);                                                 // → level 120: fully mastered
		assertEquals(120, progression.levelOf("grubnak"));
		assertEquals(AuraState.GOLDEN, progression.auraOf("grubnak"));

		gain(100_000_000);                                       // prestiged cap binds at 120
		assertEquals(120, progression.levelOf("grubnak"));
		assertEquals(AuraState.GOLDEN, progression.auraOf("grubnak"));
	}

	// -- collection accessors ------------------------------------------------

	@Test
	public void collectionAccessorsReflectOwnership()
	{
		assertEquals(0, progression.ownedCount());
		assertFalse(progression.isOwned("grubnak"));
		assertEquals(0, progression.levelOf("grubnak"));
		assertEquals(0, progression.currentStageOf("grubnak"));
		assertEquals(AuraState.NONE, progression.auraOf("grubnak"));

		own("grubnak");
		own("cindermaw");
		assertEquals(2, progression.ownedCount());
		assertTrue(progression.isOwned("cindermaw"));
		assertTrue(progression.ownedCreatureIds().contains("grubnak"));
		assertTrue(progression.getCreature("grubnak").isPresent());
		assertFalse(progression.getCreature("malgrave").isPresent());
	}
}
