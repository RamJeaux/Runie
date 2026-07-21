package com.runie.overlay;

import com.runie.RunieConfig;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Runtime Shiny effect (rework part 4) — NO per-creature shiny art files.
 *
 * <p>The effect is a composited iridescent hue-shift over the creature's
 * already-decoded frames plus a light sparkle overlay:
 * <ul>
 *   <li>Per source frame, {@link #HUE_STEPS} tinted silhouettes are built ONCE
 *       (the frame's alpha, filled with a rotating spectrum hue via SrcIn) and
 *       cached in a small identity-keyed LRU. Rendering then only blits the
 *       silhouette for the current time step at low alpha over the sprite —
 *       zero per-frame decode/allocation in steady state (§13).</li>
 *   <li>Reduced motion (§6.4): a single fixed-hue tint, no sparkle motion.</li>
 * </ul>
 *
 * <p>{@link #applyStaticShiny} produces a one-off tinted copy for Swing
 * thumbnails (collection tile / detail card) — a static diagonal iridescent
 * wash inside the sprite's alpha. Callers cache the result.
 */
@Singleton
public class ShinyRenderer
{
	/** Hue steps in the animated shimmer cycle. */
	static final int HUE_STEPS = 8;
	/** Ms per hue step (full cycle = HUE_STEPS * this). */
	static final long HUE_STEP_MS = 260;
	/** Overlay strength of the hue tint over the sprite. */
	static final float TINT_ALPHA = 0.32f;
	/** Sparkle orbit period (ms). */
	static final long SPARKLE_PERIOD_MS = 3_400;
	/** Cache cap: frames from at most a few clips stay resident. */
	static final int CACHE_MAX_FRAMES = 64;

	/** Identity-keyed LRU of per-frame tinted silhouettes. */
	private final Map<BufferedImage, BufferedImage[]> tintCache =
		new LinkedHashMap<BufferedImage, BufferedImage[]>(32, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<BufferedImage, BufferedImage[]> eldest)
			{
				return size() > CACHE_MAX_FRAMES;
			}
		};

	@Inject
	public ShinyRenderer()
	{
	}

	/**
	 * Composite the animated shiny shimmer over an already-drawn sprite frame.
	 * Call AFTER {@code g.drawImage(frame, x, y, null)}.
	 */
	public synchronized void renderShimmer(Graphics2D g, BufferedImage frame, int x, int y,
		long nowMs, RunieConfig config)
	{
		if (frame == null)
		{
			return;
		}
		boolean rm = config.reducedMotion();
		BufferedImage[] tints = tintCache.computeIfAbsent(frame, ShinyRenderer::buildTints);
		int idx = rm ? 0 : (int) ((nowMs / HUE_STEP_MS) % HUE_STEPS);
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, rm ? 0.22f : TINT_ALPHA));
		g.drawImage(tints[idx], x, y, null);
		g.setComposite(old);

		if (rm)
		{
			return; // static tint only — no sparkle motion (§6.4)
		}
		// two small orbiting glints over the sprite's upper half
		int w = frame.getWidth();
		int h = frame.getHeight();
		double phase = 2 * Math.PI * (nowMs % SPARKLE_PERIOD_MS) / (double) SPARKLE_PERIOD_MS;
		for (int k = 0; k < 2; k++)
		{
			double ang = phase + k * Math.PI;
			int px = x + w / 2 + (int) Math.round(w * 0.32 * Math.cos(ang));
			int py = y + h / 3 + (int) Math.round(h * 0.18 * Math.sin(2 * ang));
			float a = 0.45f + 0.4f * (float) Math.max(0, Math.sin(ang + k));
			g.setColor(new Color(1f, 1f, 1f, Math.min(1f, a)));
			g.fillOval(px - 1, py - 1, 3, 3);
			g.drawLine(px - 3, py, px + 3, py);
			g.drawLine(px, py - 3, px, py + 3);
		}
	}

	/**
	 * Static shiny variant for Swing thumbnails: a NEW image with a diagonal
	 * iridescent wash confined to the sprite's alpha. Callers cache the result.
	 */
	public static BufferedImage applyStaticShiny(BufferedImage src)
	{
		if (src == null)
		{
			return null;
		}
		int w = src.getWidth();
		int h = src.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(src, 0, 0, null);
		// iridescent wash clipped to the sprite alpha
		BufferedImage wash = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D wg = wash.createGraphics();
		wg.drawImage(src, 0, 0, null);
		wg.setComposite(AlphaComposite.SrcIn.derive(0.30f));
		wg.setPaint(new GradientPaint(0, 0, new Color(120, 220, 255), w, h, new Color(255, 140, 235)));
		wg.fillRect(0, 0, w, h);
		wg.dispose();
		g.drawImage(wash, 0, 0, null);
		g.dispose();
		return out;
	}

	/** Test/inspection seam. */
	synchronized int cachedFrameCount()
	{
		return tintCache.size();
	}

	/** Silhouette of {@code frame}'s alpha, filled with each spectrum hue. */
	private static BufferedImage[] buildTints(BufferedImage frame)
	{
		BufferedImage[] out = new BufferedImage[HUE_STEPS];
		for (int i = 0; i < HUE_STEPS; i++)
		{
			BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.drawImage(frame, 0, 0, null);
			g.setComposite(AlphaComposite.SrcIn);
			g.setColor(Color.getHSBColor(i / (float) HUE_STEPS, 0.55f, 1f));
			g.fillRect(0, 0, img.getWidth(), img.getHeight());
			g.dispose();
			out[i] = img;
		}
		return out;
	}
}
