package com.runie.overlay;

import com.runie.support.TestRunieConfig;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Evolution burst VFX (rework part 2): one-shot, ms-driven, self-clipping to
 * its progress window, reduced-motion aware; the GOLDEN variant is the grander
 * L120 milestone burst.
 */
public class EvolutionBurstTest
{
	private static boolean drawsAnything(double progress, boolean golden, boolean reducedMotion)
	{
		AuraRenderer renderer = new AuraRenderer();
		TestRunieConfig config = new TestRunieConfig();
		config.reducedMotion = reducedMotion;
		BufferedImage canvas = new BufferedImage(160, 160, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		renderer.renderEvolutionBurst(g, 48, 48, 64, 64, progress, golden, config);
		g.dispose();
		for (int y = 0; y < canvas.getHeight(); y += 2)
		{
			for (int x = 0; x < canvas.getWidth(); x += 2)
			{
				if ((canvas.getRGB(x, y) >>> 24) != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	@Test
	public void burstRendersWithinItsWindowOnly()
	{
		assertTrue(drawsAnything(0.05, false, false));
		assertTrue(drawsAnything(0.6, false, false));
		assertFalse("past the window: nothing may draw", drawsAnything(1.0, false, false));
		assertFalse(drawsAnything(1.5, false, false));
		assertFalse(drawsAnything(-0.1, false, false));
	}

	@Test
	public void goldenVariantRendersToo()
	{
		assertTrue(drawsAnything(0.3, true, false));
		assertTrue("golden burst window is longer",
			AuraRenderer.GOLDEN_BURST_MS > AuraRenderer.EVOLUTION_BURST_MS);
	}

	@Test
	public void reducedMotionStillShowsAFadingGlow()
	{
		// reduced motion keeps a static fading glow but drops ring + particles;
		// something must still draw so the moment is not lost entirely
		assertTrue(drawsAnything(0.3, false, true));
		assertTrue(drawsAnything(0.3, true, true));
	}
}
