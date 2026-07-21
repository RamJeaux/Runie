package com.runie.assets;

import java.awt.image.BufferedImage;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * AssetLoader: §5.1 path convention, frame-count discovery by probing,
 * style fallback chain, pre-scaling, caching (Epic 6, §12.1).
 */
public class AssetLoaderTest
{
	private AssetLoader loader;

	@Before
	public void setUp() throws Exception
	{
		// Real art streams into a cache at runtime (not bundled in-jar), so point
		// the loader at a synthetic seeded cache; fallbackling stays on the classpath
		// to exercise the legacy frame_000 + style-fallback paths.
		com.runie.creatures.CreatureRegistry registry = new com.runie.creatures.CreatureRegistry(new com.google.gson.Gson());
		registry.load();
		loader = new AssetLoader();
		loader.setAssetCacheDir(com.runie.support.TestArt.roster(registry));
	}

	@Test
	public void framePathFollowsSection5Convention()
	{
		assertEquals("/com/runie/creatures/grubnak/art/kawaii/stage2/idle/frame_000.png",
			AssetLoader.framePath("grubnak", ArtStyle.KAWAII, 2, "idle", 0));
		assertEquals("/com/runie/creatures/grubnak/art/pixel/stage3/aura_gold/frame_011.png",
			AssetLoader.framePath("grubnak", ArtStyle.PIXEL, 3, "aura_gold", 11));
	}

	@Test
	public void idleDirWithFullSequenceDiscoversAllFrames()
	{
		// grubnak ships a real pre-rendered 16-frame idle loop (frame_000..015)
		AnimationClip clip = loader.getIdleClip("grubnak", ArtStyle.KAWAII, 1, 128);
		assertNotNull(clip);
		assertEquals(16, clip.frameCount());
		assertEquals(1000 / AssetLoader.IDLE_FPS, clip.getPerFrameMs());
		assertTrue(clip.isLoop());
	}

	@Test
	public void idleDirWithOnlyFrameZeroDiscoversOneFrame()
	{
		// every bundled creature ships a full loop now, so use the synthetic
		// test-resource creature (exactly one frame_000.png on the classpath)
		// to prove probing stops at the first miss: one frame ⇒ clip of size 1
		AnimationClip clip = loader.getIdleClip("fallbackling", ArtStyle.KAWAII, 1, 128);
		assertNotNull(clip);
		assertEquals(1, clip.frameCount());
		assertSame(clip.firstFrame(), clip.frameAt(1_234_567));
	}

	@Test
	public void goldenVariantExistsForStage3InBothStyles()
	{
		assertNotNull(loader.getGoldenClip("grubnak", ArtStyle.KAWAII, 3, 128));
		assertNotNull(loader.getGoldenClip("grubnak", ArtStyle.PIXEL, 3, 128));
	}

	@Test
	public void goldenVariantAbsentForStage1()
	{
		assertNull(loader.getGoldenClip("grubnak", ArtStyle.KAWAII, 1, 128));
	}

	@Test
	public void placeholderCreaturesResolveInBothStyles()
	{
		assertNotNull(loader.getIdleClip("malgrave", ArtStyle.KAWAII, 3, 64));
		assertNotNull(loader.getIdleClip("cindermaw", ArtStyle.PIXEL, 2, 64));
	}

	@Test
	public void unknownCreatureReturnsNullNotThrow()
	{
		assertNull(loader.getIdleClip("no_such_creature", ArtStyle.KAWAII, 1, 128));
		assertNull(loader.getThumbnail("no_such_creature", ArtStyle.PIXEL, 1, 64));
	}

	@Test
	public void missingStyleFallsBackToKawaii()
	{
		// test-resource creature bundled with KAWAII art only
		AnimationClip viaPixel = loader.getClip("fallbackling", ArtStyle.PIXEL, 1, "idle", 12, true, 64);
		assertNotNull("pixel request must fall back to kawaii", viaPixel);
	}

	@Test
	public void framesArePreScaledToTarget()
	{
		AnimationClip clip = loader.getIdleClip("grubnak", ArtStyle.KAWAII, 1, 96);
		assertNotNull(clip);
		int max = Math.max(clip.getWidth(), clip.getHeight());
		assertTrue("expected max edge <= 96, got " + max, max <= 96);
	}

	@Test
	public void clipsAreCachedPerKey()
	{
		AnimationClip a = loader.getIdleClip("grubnak", ArtStyle.KAWAII, 1, 128);
		AnimationClip b = loader.getIdleClip("grubnak", ArtStyle.KAWAII, 1, 128);
		assertSame(a, b);
	}

	@Test
	public void thumbnailIsFirstIdleFrame()
	{
		BufferedImage thumb = loader.getThumbnail("grubnak", ArtStyle.KAWAII, 1, 64);
		assertNotNull(thumb);
		assertTrue(Math.max(thumb.getWidth(), thumb.getHeight()) <= 64);
	}
}
