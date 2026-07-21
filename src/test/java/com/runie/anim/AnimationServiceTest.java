package com.runie.anim;

import com.google.gson.Gson;
import com.runie.assets.AnimationClip;
import com.runie.assets.ArtStyle;
import com.runie.assets.ArtStyleService;
import com.runie.assets.AssetLoader;
import com.runie.config.AnimationIntensity;
import com.runie.core.AuraState;
import com.runie.core.ProgressionService;
import com.runie.core.XpTable;
import com.runie.creatures.CreatureRegistry;
import com.runie.state.CreatureInstance;
import com.runie.state.RunieStateStore;
import com.runie.state.TestStateStores;
import com.runie.overlay.AuraRenderer;
import com.runie.support.FakeClock;
import com.runie.support.TestRunieConfig;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * AnimationService + AnimationClip: ms-driven frame indexing, ping-pong idle
 * playback of the pre-rendered frame sequences, reduced-motion frame_000 hold,
 * subtle-intensity fps halving, single-frame static fallback, and golden-aura
 * clip selection (Epic 6, §6 / §12.1 / Appendix B).
 */
public class AnimationServiceTest
{
	private static final long HASH = 606L;

	private Path dir;
	private ScheduledExecutorService executor;
	private CreatureRegistry registry;
	private RunieStateStore store;
	private ProgressionService progression;
	private FakeClock clock;
	private TestRunieConfig config;
	private AnimationService anim;

	@Before
	public void setUp() throws IOException
	{
		dir = Files.createTempDirectory("runie-anim-test");
		executor = Executors.newSingleThreadScheduledExecutor();
		registry = new CreatureRegistry(new Gson());
		registry.load();
		store = TestStateStores.create(new Gson(), executor, registry, dir);
		store.switchAccount(HASH);
		progression = new ProgressionService(store, registry, new com.runie.core.EggService(store, new FakeClock(1_000_000L)));
		clock = new FakeClock(1_000_000L);
		config = new TestRunieConfig();
		// direct executor: refresh() decodes synchronously in tests
		anim = new AnimationService(new AssetLoader(), unlockedStyles(), progression, config, clock,
			Runnable::run);
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	/** Kawaii pre-unlocked: these tests exercise animation, not the easter egg. */
	private ArtStyleService unlockedStyles()
	{
		return new ArtStyleService(config, () -> "true", v -> { });
	}

	private CreatureInstance ownActive(String id)
	{
		CreatureInstance c = CreatureInstance.fresh(0);
		store.getState().creatures.owned.put(id, c);
		store.getState().creatures.active = id;
		return c;
	}

	/** The frame ping-pong playback should pick at {@code now} — same math, one place. */
	private static BufferedImage expectedIdleFrame(AnimationService.CompanionClips clips, long now)
	{
		AnimationClip idle = clips.idle;
		long elapsed = now + IdleFramePlayer.phaseOffsetMs(clips.creatureId);
		return idle.frame(IdleFramePlayer.frameIndex(idle.frameCount(), elapsed, idle.getPerFrameMs()));
	}

	// ------------------------------------------------------------------
	// AnimationClip frame indexing — pure function of elapsed ms (§6.2)
	// ------------------------------------------------------------------

	@Test
	public void clipFrameIndexIsMsDriven()
	{
		BufferedImage f = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		AnimationClip clip = new AnimationClip(new BufferedImage[]{f, f, f}, 12, true);
		assertEquals(83, clip.getPerFrameMs());       // 1000/12
		assertEquals(0, clip.frameIndexAt(0));
		assertEquals(0, clip.frameIndexAt(82));
		assertEquals(1, clip.frameIndexAt(83));
		assertEquals(2, clip.frameIndexAt(167));
		assertEquals(0, clip.frameIndexAt(249));      // 249/83 = 3 → wraps to 0
		assertEquals(1, clip.frameIndexAt(83 * 4));   // loops forever
	}

	@Test
	public void nonLoopingClipClampsAndFinishes()
	{
		BufferedImage f = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		AnimationClip clip = new AnimationClip(new BufferedImage[]{f, f}, 10, false);
		assertEquals(1, clip.frameIndexAt(10_000));   // clamps to last frame
		assertTrue(clip.isFinished(200));             // 2 frames x 100ms
		assertTrue(!clip.isFinished(199));
	}

	@Test
	public void negativeElapsedClampsToFrameZero()
	{
		BufferedImage f = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		AnimationClip clip = new AnimationClip(new BufferedImage[]{f, f}, 12, true);
		assertEquals(0, clip.frameIndexAt(-500));
	}

	// ------------------------------------------------------------------
	// AnimationService — ping-pong idle playback of pre-rendered frames
	// ------------------------------------------------------------------

	@Test
	public void noActiveCreatureMeansNoFrame()
	{
		anim.refresh();
		assertNull(anim.currentCompanionFrame(clock.elapsedMs()));
	}

	@Test
	public void idleSequenceLoadsAllSixteenFrames()
	{
		ownActive("grubnak"); // real 16-frame pre-rendered idle
		anim.refresh();
		AnimationClip idle = anim.getActiveClips().idle;
		assertNotNull(idle);
		assertEquals(16, idle.frameCount());
		assertEquals(1000 / AssetLoader.IDLE_FPS, idle.getPerFrameMs());
	}

	@Test
	public void idleFramesPlayPingPongSeamlessly()
	{
		ownActive("grubnak");
		anim.refresh();
		AnimationClip idle = anim.getActiveClips().idle;
		int per = idle.getPerFrameMs();                // 83ms @ 12fps
		int n = idle.frameCount();                     // 16
		long cycle = (long) per * (2 * (n - 1));       // full ping-pong period
		// align t0 so (t0 + creature phase offset) lands exactly on step 0
		long t0 = 10 * cycle - IdleFramePlayer.phaseOffsetMs("grubnak");
		assertSame(idle.frame(0), anim.currentCompanionFrame(t0));
		assertSame(idle.frame(1), anim.currentCompanionFrame(t0 + per));
		assertSame(idle.frame(n - 1), anim.currentCompanionFrame(t0 + (n - 1L) * per)); // apex
		assertSame(idle.frame(n - 2), anim.currentCompanionFrame(t0 + (long) n * per)); // reverses — no wrap-jump
		assertSame(idle.frame(1), anim.currentCompanionFrame(t0 + (2L * n - 3) * per)); // …back down
		assertSame(idle.frame(0), anim.currentCompanionFrame(t0 + cycle));              // loop closes at 0
		assertSame(idle.frame(1), anim.currentCompanionFrame(t0 + cycle + per));        // and keeps going
	}

	@Test
	public void reducedMotionHoldsFrameZero()
	{
		ownActive("grubnak");
		config.reducedMotion = true;
		anim.refresh();
		AnimationClip idle = anim.getActiveClips().idle;
		assertSame(idle.firstFrame(), anim.currentCompanionFrame(0));
		assertSame(idle.firstFrame(), anim.currentCompanionFrame(83));
		assertSame(idle.firstFrame(), anim.currentCompanionFrame(987_654));
	}

	@Test
	public void singleFrameIdleRendersStatic()
	{
		// every bundled creature ships a full idle loop now, so prove the
		// static path with a synthetic one-frame clip and the pure index math
		BufferedImage only = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		AnimationClip idle = new AnimationClip(new BufferedImage[]{only}, AssetLoader.IDLE_FPS, true);
		assertEquals(1, idle.frameCount());
		for (long elapsed : new long[]{0, 83, 1_234_567})
		{
			int index = IdleFramePlayer.frameIndex(idle.frameCount(), elapsed, idle.getPerFrameMs());
			assertEquals(0, index);
			assertSame(only, idle.frame(index));
		}
	}

	@Test
	public void subtleIntensityHalvesEffectiveFps()
	{
		ownActive("grubnak");
		config.intensity = AnimationIntensity.SUBTLE;
		anim.refresh();
		AnimationClip idle = anim.getActiveClips().idle;
		int per = idle.getPerFrameMs();
		long cycle = (long) per * (2 * (idle.frameCount() - 1));
		// align so HALVED elapsed lands exactly on step 0 of a loop
		long t0 = 2 * (10 * cycle) - IdleFramePlayer.phaseOffsetMs("grubnak");
		BufferedImage f0 = anim.currentCompanionFrame(t0);
		assertSame(idle.frame(0), f0);
		// one NORMAL frame period later: still frame 0 (half-speed playback)
		assertSame(f0, anim.currentCompanionFrame(t0 + per));
		// two periods later: advanced exactly one frame
		assertSame(idle.frame(1), anim.currentCompanionFrame(t0 + 2L * per));
	}

	@Test
	public void frameIsStableAcrossRefreshesAtTheSameInstant()
	{
		ownActive("grubnak");
		anim.refresh();
		long now = 777_777;
		BufferedImage before = anim.currentCompanionFrame(now);
		anim.refresh(); // e.g. aura/stage re-check — must not restart the loop
		assertSame(before, anim.currentCompanionFrame(now));
	}

	// ------------------------------------------------------------------
	// Aura selection (golden at 120 — §12.1 aura rows, Appendix B)
	// ------------------------------------------------------------------

	@Test
	public void goldenAuraCompositesOverBaseIdle()
	{
		CreatureInstance c = ownActive("grubnak");
		c.prestigeCount = 1;
		c.xp = XpTable.xpForLevel(120); // prestiged AND level 120 → GOLDEN
		assertEquals(AuraState.GOLDEN, progression.auraOf("grubnak"));
		anim.refresh();
		AnimationService.CompanionClips clips = anim.getActiveClips();
		assertNotNull(clips);
		assertEquals(AuraState.GOLDEN, clips.aura);
		// Appendix B: the BASE idle frame renders; the golden radiance is
		// composited by AuraRenderer over ANY creature — never baked
		long now = clock.elapsedMs();
		assertSame(expectedIdleFrame(clips, now), anim.currentCompanionFrame(now));
		assertEquals(AuraRenderer.Effect.GOLDEN_RADIANCE, AuraRenderer.effectFor(clips.aura));
		// baked Grubnak golden variant stays resident as an OPTIONAL fallback only
		assertNotNull(clips.golden);
	}

	@Test
	public void goldenAuraEffectAppliesToPlaceholderArtCreatures()
	{
		// any of the 42 lines — no baked golden art required
		CreatureInstance c = ownActive("malgrave");
		c.prestigeCount = 1;
		c.xp = XpTable.xpForLevel(120);
		anim.refresh();
		AnimationService.CompanionClips clips = anim.getActiveClips();
		assertEquals(AuraState.GOLDEN, clips.aura);
		assertNull(clips.golden);        // no baked variant exists…
		assertNotNull(clips.idle);       // …but the base look renders (animated)
		long now = clock.elapsedMs();
		assertSame(expectedIdleFrame(clips, now), anim.currentCompanionFrame(now));
		assertEquals(AuraRenderer.Effect.GOLDEN_RADIANCE, AuraRenderer.effectFor(clips.aura));
	}

	@Test
	public void prestigeBelow120KeepsBaseLookNoGoldenClip()
	{
		CreatureInstance c = ownActive("grubnak");
		c.prestigeCount = 1;
		c.xp = XpTable.xpForLevel(80);
		assertEquals(AuraState.PRESTIGE, progression.auraOf("grubnak"));
		anim.refresh();
		AnimationService.CompanionClips clips = anim.getActiveClips();
		assertEquals(AuraState.PRESTIGE, clips.aura);
		assertNull(clips.golden);                  // golden variant only loads at GOLDEN
		assertEquals(3, clips.stage);              // prestiged keeps the stage-3 look
		assertEquals(16, clips.idle.frameCount()); // stage-3 idle loop plays as usual
	}

	@Test
	public void stageTracksEvolutionForClipSelection()
	{
		CreatureInstance c = ownActive("grubnak");
		c.xp = XpTable.xpForLevel(40); // stage 2 at 40
		anim.refresh();
		assertEquals(2, anim.getActiveClips().stage);
		assertEquals(16, anim.getActiveClips().idle.frameCount()); // stage-2 loop
	}

	// ------------------------------------------------------------------
	// Deployment persistence + immediate deploy (Bugs 1 & 2)
	// ------------------------------------------------------------------

	@Test
	public void persistedActiveCompanionRedeploysWhenStateLoads()
	{
		// session 1: deploy grubnak, persist
		ownActive("grubnak");
		store.markDirty();
		store.flush();

		// session 2 (client relaunch): fresh store/services over the same file.
		// The plugin registers a StateLoadListener that refreshes the animation
		// service — the login-time refresh alone would race the state load.
		RunieStateStore store2 = TestStateStores.create(new Gson(), executor, registry, dir);
		ProgressionService progression2 = new ProgressionService(store2, registry, new com.runie.core.EggService(store2, clock));
		AnimationService anim2 = new AnimationService(new AssetLoader(), unlockedStyles(), progression2,
			config, clock, Runnable::run);
		anim2.refresh(); // pre-load refresh (GameState.LOGGED_IN): state not bound yet
		assertNull(anim2.getActiveClips());

		store2.addLoadListener(hash -> anim2.refresh()); // plugin wiring
		store2.switchAccount(HASH);                      // first StatChanged binds the account

		assertNotNull("companion must re-deploy from persisted state", anim2.getActiveClips());
		assertEquals("grubnak", anim2.getActiveClips().creatureId);
		assertNotNull(anim2.currentCompanionFrame(clock.elapsedMs()));
	}

	@Test
	public void invalidPersistedActiveFallsBackToNoCompanion() throws IOException
	{
		// persisted active id that is no longer owned (on-disk drift / legacy
		// state): the document loads cleanly and NO companion deploys — no crash
		ownActive("grubnak");
		store.markDirty();
		store.flush();
		Path main = dir.resolve("state-" + HASH + ".json");
		String raw = new String(Files.readAllBytes(main), java.nio.charset.StandardCharsets.UTF_8);
		Files.write(main, raw.replace("\"active\":\"grubnak\"", "\"active\":\"ghost\"")
			.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		RunieStateStore store2 = TestStateStores.create(new Gson(), executor, registry, dir);
		ProgressionService progression2 = new ProgressionService(store2, registry, new com.runie.core.EggService(store2, clock));
		AnimationService anim2 = new AnimationService(new AssetLoader(), unlockedStyles(), progression2,
			config, clock, Runnable::run);
		store2.addLoadListener(hash -> anim2.refresh());
		store2.switchAccount(HASH); // must not throw, must not quarantine
		assertEquals(RunieStateStore.LoadStatus.LOADED_OK, store2.getLoadStatus());
		assertNull(anim2.getActiveClips());                                    // graceful: no companion
		assertNull(anim2.currentCompanionFrame(clock.elapsedMs()));
		assertTrue(store2.getState().creatures.owned.containsKey("grubnak"));  // roster intact
	}

	@Test
	public void setActiveObserverHookDeploysCompanionImmediately()
	{
		// plugin wiring: onActiveChanged → AnimationService.refresh() (Bug 2)
		progression.addListener(new ProgressionService.ProgressionListener()
		{
			@Override
			public void onActiveChanged(String activeCreatureId)
			{
				anim.refresh();
			}
		});
		store.getState().creatures.owned.put("grubnak", CreatureInstance.fresh(0)); // owned, NOT active
		anim.refresh();
		assertNull(anim.getActiveClips()); // nothing deployed yet

		assertTrue(progression.setActiveCreature("grubnak")); // the "Set active" click
		assertNotNull("clicking Set active must deploy immediately", anim.getActiveClips());
		assertEquals("grubnak", anim.getActiveClips().creatureId);

		assertTrue(progression.setActiveCreature(null));      // clearing undeploys immediately
		assertNull(anim.getActiveClips());
	}

	@Test
	public void styleChangeSwapsClipsOnRefresh()
	{
		ownActive("grubnak");
		anim.refresh();
		assertEquals(ArtStyle.KAWAII, anim.getActiveClips().style);
		config.artStyle = ArtStyle.PIXEL;
		anim.refresh();
		assertEquals(ArtStyle.PIXEL, anim.getActiveClips().style);
		assertEquals(16, anim.getActiveClips().idle.frameCount()); // pixel loop too
	}
}
