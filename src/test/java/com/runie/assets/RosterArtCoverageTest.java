package com.runie.assets;

import com.google.gson.Gson;
import com.runie.creatures.CreatureDefinition;
import com.runie.creatures.CreatureRegistry;
import java.awt.image.BufferedImage;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Full-roster rendering guarantee: every one of the 42 lines resolves art by
 * the §5.1 convention alone (no per-creature special cases) — real frames
 * where they've been dropped in (Grubnak), bundled placeholder tiles
 * everywhere else — and a missing frame set degrades to the fallback chain,
 * never an exception.
 */
public class RosterArtCoverageTest
{
	private CreatureRegistry registry;
	private AssetLoader loader;

	@Before
	public void setUp() throws Exception
	{
		registry = new CreatureRegistry(new Gson());
		registry.load();
		loader = new AssetLoader();
	}

	@Test
	public void all42ResolveIdleArtInBothStylesAtEveryStage()
	{
		for (CreatureDefinition def : registry.all())
		{
			for (ArtStyle style : ArtStyle.values())
			{
				for (int stage = 1; stage <= 3; stage++)
				{
					AnimationClip clip = loader.getIdleClip(def.getId(), style, stage, 64);
					assertNotNull(def.getId() + '/' + style + "/stage" + stage
						+ ": no idle art (real or placeholder)", clip);
					BufferedImage thumb = loader.getThumbnail(def.getId(), style, stage, 52);
					assertNotNull(def.getId() + ": no grid thumbnail", thumb);
				}
			}
		}
	}

	@Test
	public void missingAnimationFallsBackWithoutThrowing()
	{
		// no roster creature bundles a "walk" clip yet: loader must return
		// null (callers keep the last good frame / silhouette), never throw
		for (CreatureDefinition def : registry.all())
		{
			assertNull(loader.getClip(def.getId(), ArtStyle.KAWAII, 1, "walk", 12, true, 64));
		}
	}

	@Test
	public void missingStyleServesThePlaceholderViaKawaiiFallback()
	{
		// fallbackling (test resource) has ONLY kawaii art: a pixel request
		// must still render — the §5.2 fallback chain is what guarantees a
		// placeholder-or-better for any style the user picks
		assertNotNull(loader.getIdleClip("fallbackling", ArtStyle.PIXEL, 1, 64));
	}
}
