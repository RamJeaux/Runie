package com.runie.overlay;

import com.runie.config.AnimationIntensity;
import com.runie.core.AuraState;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.runie.support.TestRunieConfig;

/**
 * AuraRenderer: aura-state → effect selection, ms-driven shimmer,
 * reduced-motion / intensity gating, and the pre-rendered glow raster
 * (Appendix B: auras are composited, never baked).
 */
public class AuraRendererTest
{
	private AuraRenderer renderer;
	private TestRunieConfig config;

	@Before
	public void setUp()
	{
		renderer = new AuraRenderer();
		config = new TestRunieConfig();
	}

	// ------------------------------------------------------------------
	// Aura state → effect selection
	// ------------------------------------------------------------------

	@Test
	public void effectSelectionMapsAuraStates()
	{
		assertEquals(AuraRenderer.Effect.NONE, AuraRenderer.effectFor(AuraState.NONE));
		assertEquals(AuraRenderer.Effect.PRESTIGE_RING, AuraRenderer.effectFor(AuraState.PRESTIGE));
		assertEquals(AuraRenderer.Effect.GOLDEN_RADIANCE, AuraRenderer.effectFor(AuraState.GOLDEN));
		assertEquals(AuraRenderer.Effect.NONE, AuraRenderer.effectFor(null));
	}

	// ------------------------------------------------------------------
	// ms-driven shimmer, reduced-motion / intensity gating (§6.2 / §6.4)
	// ------------------------------------------------------------------

	@Test
	public void glowAlphaIsStaticUnderReducedMotion()
	{
		float a0 = AuraRenderer.glowAlpha(0, true, AnimationIntensity.NORMAL);
		float a1 = AuraRenderer.glowAlpha(AuraRenderer.SHIMMER_PERIOD_MS / 4, true, AnimationIntensity.NORMAL);
		float a2 = AuraRenderer.glowAlpha(999_999, true, AnimationIntensity.FULL);
		assertEquals(a0, a1, 0f); // constant: no shimmer at all
		assertEquals(a0, a2, 0f);
	}

	@Test
	public void glowAlphaPulsesMsDriven()
	{
		long quarter = AuraRenderer.SHIMMER_PERIOD_MS / 4; // sine peak
		float base = AuraRenderer.glowAlpha(0, false, AnimationIntensity.NORMAL);
		float peak = AuraRenderer.glowAlpha(quarter, false, AnimationIntensity.NORMAL);
		assertEquals(0.55f, base, 0.001f);
		assertEquals(0.75f, peak, 0.001f);   // base + NORMAL amplitude 0.20
		// full period wraps back to base — pure function of elapsed ms
		assertEquals(base, AuraRenderer.glowAlpha(AuraRenderer.SHIMMER_PERIOD_MS, false,
			AnimationIntensity.NORMAL), 0.001f);
	}

	@Test
	public void shimmerAmplitudeScalesWithIntensity()
	{
		long quarter = AuraRenderer.SHIMMER_PERIOD_MS / 4;
		float subtle = AuraRenderer.glowAlpha(quarter, false, AnimationIntensity.SUBTLE);
		float normal = AuraRenderer.glowAlpha(quarter, false, AnimationIntensity.NORMAL);
		float full = AuraRenderer.glowAlpha(quarter, false, AnimationIntensity.FULL);
		assertTrue(subtle < normal && normal < full);
	}

	@Test
	public void sparklesGatedByReducedMotionAndIntensity()
	{
		assertEquals(0, AuraRenderer.sparkleCount(true, AnimationIntensity.NORMAL));
		assertEquals(0, AuraRenderer.sparkleCount(true, AnimationIntensity.FULL));
		assertEquals(0, AuraRenderer.sparkleCount(false, AnimationIntensity.SUBTLE));
		assertEquals(5, AuraRenderer.sparkleCount(false, AnimationIntensity.NORMAL));
		assertEquals(7, AuraRenderer.sparkleCount(false, AnimationIntensity.FULL));
	}

	// ------------------------------------------------------------------
	// Pre-rendered glow raster (§13: no image allocation in render())
	// ------------------------------------------------------------------

	@Test
	public void glowRasterIsPreRenderedOnceAndReused()
	{
		BufferedImage glow = renderer.getGlowRaster();
		assertNotNull(glow);
		assertEquals(AuraRenderer.GLOW_RASTER_PX, glow.getWidth());
		assertSame(glow, renderer.getGlowRaster()); // cached, never rebuilt
		// golden core is actually golden and opaque-ish; edge fades out
		int center = glow.getRGB(glow.getWidth() / 2, glow.getHeight() / 2);
		assertTrue("core must be visible", ((center >>> 24) & 0xFF) > 150);
		int corner = glow.getRGB(1, 1);
		assertEquals("corner must be transparent", 0, (corner >>> 24) & 0xFF);
	}

	// ------------------------------------------------------------------
	// Composited render paths (headless smoke: effect draws iff selected)
	// ------------------------------------------------------------------

	private int paintedPixels(AuraState aura, boolean reducedMotion)
	{
		config.reducedMotion = reducedMotion;
		BufferedImage canvas = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		renderer.renderUnder(g, aura, 60, 60, 80, 80, 1234, config);
		renderer.renderOver(g, aura, 60, 60, 80, 80, 1234, config);
		g.dispose();
		int painted = 0;
		for (int y = 0; y < canvas.getHeight(); y++)
		{
			for (int x = 0; x < canvas.getWidth(); x++)
			{
				if (((canvas.getRGB(x, y) >>> 24) & 0xFF) != 0)
				{
					painted++;
				}
			}
		}
		return painted;
	}

	@Test
	public void noneDrawsNothingGoldenDrawsRadiance()
	{
		assertEquals(0, paintedPixels(AuraState.NONE, false));
		int prestige = paintedPixels(AuraState.PRESTIGE, false);
		int golden = paintedPixels(AuraState.GOLDEN, false);
		assertTrue("prestige ring paints", prestige > 0);
		assertTrue("golden radiance paints far more than the prestige ring", golden > prestige * 3);
	}

	@Test
	public void reducedMotionStillShowsAStaticAura()
	{
		// §6.4: aura remains visible as a STATIC ring/glow under reduced motion
		assertTrue(paintedPixels(AuraState.GOLDEN, true) > 0);
		assertTrue(paintedPixels(AuraState.PRESTIGE, true) > 0);
	}
}
