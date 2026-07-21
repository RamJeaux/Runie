package com.runie.overlay;

import com.runie.support.TestRunieConfig;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Shiny render effect (rework part 4): a runtime shimmer over the decoded
 * frames — no per-creature shiny art. Tints are cached per frame; the static
 * thumbnail variant stays confined to the sprite's alpha.
 */
public class ShinyRendererTest
{
	private static BufferedImage sprite()
	{
		BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(90, 160, 90));
		g.fillOval(4, 4, 24, 24); // opaque blob, transparent corners
		g.dispose();
		return img;
	}

	@Test
	public void shimmerDrawsAndCachesTintsPerFrame()
	{
		ShinyRenderer r = new ShinyRenderer();
		TestRunieConfig config = new TestRunieConfig();
		BufferedImage frame = sprite();
		BufferedImage canvas = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		g.drawImage(frame, 8, 8, null);
		int before = canvas.getRGB(20, 20);
		r.renderShimmer(g, frame, 8, 8, 0, config);
		assertEquals(1, r.cachedFrameCount());
		// many render calls at different times reuse the same cached tints
		for (long t = 100; t < 4_000; t += 100)
		{
			r.renderShimmer(g, frame, 8, 8, t, config);
		}
		g.dispose();
		assertEquals(1, r.cachedFrameCount());
		assertTrue("shimmer must visibly alter the sprite", canvas.getRGB(20, 20) != before);
	}

	@Test
	public void reducedMotionShimmerIsFullyStatic()
	{
		// two independent renders at very different times must be pixel-identical
		// under reduced motion (fixed hue, no sparkle motion — §6.4)
		BufferedImage a = renderReducedAt(0);
		BufferedImage b = renderReducedAt(123_456);
		for (int y = 0; y < a.getHeight(); y++)
		{
			for (int x = 0; x < a.getWidth(); x++)
			{
				assertEquals("pixel " + x + "," + y, a.getRGB(x, y), b.getRGB(x, y));
			}
		}
	}

	private static BufferedImage renderReducedAt(long nowMs)
	{
		ShinyRenderer r = new ShinyRenderer();
		TestRunieConfig config = new TestRunieConfig();
		config.reducedMotion = true;
		BufferedImage frame = sprite();
		BufferedImage canvas = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		g.drawImage(frame, 8, 8, null);
		r.renderShimmer(g, frame, 8, 8, nowMs, config);
		g.dispose();
		return canvas;
	}

	@Test
	public void staticShinyThumbnailStaysInsideTheSpriteAlpha()
	{
		BufferedImage src = sprite();
		BufferedImage out = ShinyRenderer.applyStaticShiny(src);
		assertNotNull(out);
		assertEquals(src.getWidth(), out.getWidth());
		// transparent corner stays transparent (wash is SrcIn-clipped)
		assertEquals(0, out.getRGB(0, 0) >>> 24);
		// opaque center stays opaque and changed color (the iridescent wash)
		assertEquals(255, out.getRGB(16, 16) >>> 24);
		assertTrue(out.getRGB(16, 16) != src.getRGB(16, 16));
		// source untouched
		assertEquals(0, src.getRGB(0, 0) >>> 24);
	}
}
