package com.runie.overlay;

import com.google.gson.Gson;
import com.runie.assets.ArtStyleService;
import com.runie.assets.AssetLoader;
import com.runie.core.EggService;
import com.runie.core.ProgressionService;
import com.runie.core.PullResult;
import com.runie.creatures.CreatureRegistry;
import com.runie.creatures.Rarity;
import com.runie.state.RunieStateStore;
import com.runie.state.TestStateStores;
import com.runie.support.FakeClock;
import com.runie.support.TestRunieConfig;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Bug 5 regression: the hatch reveal must present IMMEDIATELY when onPull
 * fires (it used to stay hidden until logout because a stuck danger flag —
 * the Long.MIN_VALUE combat-window overflow in RuniePlugin — re-anchored the
 * presentation every frame). Also pins the intended §7.3 semantics: a genuine
 * danger defers only the CINEMATIC, never the committed result.
 */
public class RuniePullOverlayTest
{
	private ScheduledExecutorService executor;
	private FakeClock clock;
	private TestRunieConfig config;
	private RuniePullOverlay overlay;
	private RunieStateStore store;

	@Before
	public void setUp() throws IOException
	{
		Path dir = Files.createTempDirectory("runie-pulloverlay-test");
		executor = Executors.newSingleThreadScheduledExecutor();
		Gson gson = new Gson();
		CreatureRegistry registry = new CreatureRegistry(gson);
		registry.load();
		store = TestStateStores.create(gson, executor, registry, dir);
		store.switchAccount(11L);
		clock = new FakeClock(1_784_721_600_000L);
		EggService eggs = new EggService(store, clock);
		ProgressionService progression = new ProgressionService(store, registry, eggs);
		AssetLoader assets = new AssetLoader();
		ArtStyleService styles = new ArtStyleService(config = new TestRunieConfig(), () -> "true", v -> { });
		// direct executor: presentation prep is synchronous, like the mockup seam
		overlay = new RuniePullOverlay(registry, progression, assets, styles, config, clock, Runnable::run);
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private PullResult result()
	{
		return new PullResult("grubnak", Rarity.COMMON, true, false, 0, false, false, false,
			new PullResult.PitySnapshot(0, 0, 0, 1, 0));
	}

	private Dimension renderOnce()
	{
		BufferedImage img = new BufferedImage(300, 240, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		Dimension d = overlay.render(g);
		g.dispose();
		return d;
	}

	@Test
	public void revealPresentsImmediatelyOnHatch()
	{
		assertFalse(overlay.isPresenting());
		assertNull(renderOnce()); // nothing to show before a hatch

		overlay.onPull(result()); // the hatch — direct executor: prep is done now

		assertTrue("presentation must exist right after onPull", overlay.isPresenting());
		assertNotNull("first frame after the hatch must render the cinematic", renderOnce());
	}

	@Test
	public void revealPlaysThroughAndDismissesOnItsOwnTimeline()
	{
		overlay.onPull(result());
		assertNotNull(renderOnce());                 // anchors the presentation clock
		clock.advanceMs(RuniePullOverlay.HATCH_MS + 100);
		assertNotNull(renderOnce());                 // reveal card phase
		clock.advanceMs(RuniePullOverlay.REVEAL_MS); // past the total
		assertNull(renderOnce());                    // dismissed
		assertFalse(overlay.isPresenting());
	}

	@Test
	public void genuineDangerDefersTheCinematicUntilClear()
	{
		AtomicBoolean danger = new AtomicBoolean(true);
		overlay.setDangerSupplier(danger::get);
		overlay.onPull(result());

		assertNull("cinematic must wait while in danger", renderOnce());
		clock.advanceMs(10_000);
		assertNull(renderOnce());                    // still deferred, still pending
		assertTrue(overlay.isPresenting());          // result is committed; only presentation waits

		danger.set(false);
		assertNotNull("cinematic starts as soon as danger clears", renderOnce());
	}

	@Test
	public void reducedMotionStillPresentsImmediately()
	{
		config.reducedMotion = true;
		overlay.onPull(result());
		assertNotNull(renderOnce());
	}
}
