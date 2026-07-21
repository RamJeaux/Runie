package com.runie.overlay;

import com.runie.RunieConfig;
import com.runie.config.AnimationIntensity;
import com.runie.core.AuraState;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.Stroke;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Reusable composited aura effects (architecture Appendix B decision: auras are
 * COMPOSITED over the base creature look, never baked per-creature). Any of the
 * 42 lines gets the same PRESTIGE ring / GOLDEN radiance the moment its
 * {@link AuraState} says so — no art dependency.
 *
 * <p>Render-thread rules (§13): the golden glow is pre-rendered ONCE (in the
 * constructor, at plugin startup) into a single canonical raster and blitted
 * scaled per frame; everything else is pure arithmetic + a handful of oval
 * draws. Zero decode, zero image allocation in {@code render()}.
 *
 * <p>All motion is ms-driven off the caller-supplied {@code nowMs}
 * ({@link com.runie.core.RunieClock#elapsedMs()}), and reduced-motion /
 * intensity gating (§6.4) is applied here so every caller composites auras
 * consistently: reduced motion = a static ring + static glow, no shimmer, no
 * orbiting sparkles.
 */
@Singleton
public class AuraRenderer
{
	/** Which visual effect an {@link AuraState} maps to. */
	public enum Effect
	{
		/** No aura layer. */
		NONE,
		/** Faint warm ring (prestiged, below the level-120 cap). */
		PRESTIGE_RING,
		/** Radiant golden glow + ring + sparkles (prestiged AND level 120). */
		GOLDEN_RADIANCE
	}

	/** Shimmer period for the golden glow pulse (ms-driven, §6.2). */
	static final long SHIMMER_PERIOD_MS = 2400;
	/** Orbit period for the golden sparkles (ms). */
	static final long ORBIT_PERIOD_MS = 5200;
	/** Canonical pre-rendered glow raster edge (blitted scaled). */
	static final int GLOW_RASTER_PX = 384;

	private static final Color GOLD = new Color(255, 214, 84);
	private static final Color GOLD_CORE = new Color(255, 232, 150);
	private static final Color PRESTIGE_RING_COLOR = new Color(235, 220, 160);
	private static final BasicStroke RING_STROKE = new BasicStroke(3f);
	private static final BasicStroke GOLD_RING_STROKE = new BasicStroke(3.5f);

	private final BufferedImage glowRaster;

	@Inject
	public AuraRenderer()
	{
		// one-time pre-render at construction (plugin startup, NOT render())
		this.glowRaster = renderGlowRaster(GLOW_RASTER_PX);
	}

	/** Pure aura-state → effect mapping (unit-tested; the overlay just obeys). */
	public static Effect effectFor(AuraState aura)
	{
		if (aura == null)
		{
			return Effect.NONE;
		}
		switch (aura)
		{
			case PRESTIGE:
				return Effect.PRESTIGE_RING;
			case GOLDEN:
				return Effect.GOLDEN_RADIANCE;
			case NONE:
			default:
				return Effect.NONE;
		}
	}

	/**
	 * Golden glow alpha for {@code nowMs} — constant under reduced motion
	 * (static glow, §6.4), otherwise a gentle sine pulse whose amplitude scales
	 * with {@link AnimationIntensity}.
	 */
	static float glowAlpha(long nowMs, boolean reducedMotion, AnimationIntensity intensity)
	{
		final float base = 0.55f;
		if (reducedMotion)
		{
			return base;
		}
		float amp;
		switch (intensity)
		{
			case FULL:
				amp = 0.25f;
				break;
			case SUBTLE:
				amp = 0.10f;
				break;
			case NORMAL:
			default:
				amp = 0.20f;
		}
		double phase = 2 * Math.PI * (nowMs % SHIMMER_PERIOD_MS) / (double) SHIMMER_PERIOD_MS;
		float a = base + amp * (float) Math.sin(phase);
		return Math.max(0f, Math.min(1f, a));
	}

	/** Ring alpha (shared by prestige + golden rings); static under reduced motion. */
	static float ringAlpha(long nowMs, boolean reducedMotion, AnimationIntensity intensity, float base)
	{
		if (reducedMotion)
		{
			return base;
		}
		float amp = intensity == AnimationIntensity.SUBTLE ? 0.06f : 0.14f;
		// quarter-period offset from the glow so ring and glow breathe out of step
		double phase = 2 * Math.PI * ((nowMs + SHIMMER_PERIOD_MS / 4) % SHIMMER_PERIOD_MS)
			/ (double) SHIMMER_PERIOD_MS;
		float a = base + amp * (float) Math.sin(phase);
		return Math.max(0f, Math.min(1f, a));
	}

	/**
	 * Orbiting sparkle count — 0 under reduced motion (static ring only, §6.4)
	 * and under SUBTLE intensity; 5 at NORMAL, 7 at FULL.
	 */
	static int sparkleCount(boolean reducedMotion, AnimationIntensity intensity)
	{
		if (reducedMotion || intensity == AnimationIntensity.SUBTLE)
		{
			return 0;
		}
		return intensity == AnimationIntensity.FULL ? 7 : 5;
	}

	/** The cached canonical glow raster (test seam: must be built once, reused). */
	BufferedImage getGlowRaster()
	{
		return glowRaster;
	}

	/**
	 * Layer drawn UNDER the sprite (the golden radiance halo). Coordinates are
	 * the UNSCALED sprite box so the aura stays stable while the sprite bobs /
	 * squashes.
	 */
	public void renderUnder(Graphics2D g, AuraState aura, int spriteX, int spriteY,
		int spriteW, int spriteH, long nowMs, RunieConfig config)
	{
		if (effectFor(aura) != Effect.GOLDEN_RADIANCE)
		{
			return;
		}
		boolean rm = config.reducedMotion();
		int d = (int) (Math.max(spriteW, spriteH) * 1.7);
		int gx = spriteX + (spriteW - d) / 2;
		int gy = spriteY + (spriteH - d) / 2 + spriteH / 10; // sit slightly low: grounded halo
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
			glowAlpha(nowMs, rm, config.animationIntensity())));
		g.drawImage(glowRaster, gx, gy, d, d, null); // scale-blit of the pre-rendered raster
		g.setComposite(old);
	}

	/**
	 * Layer drawn OVER the sprite: PRESTIGE = faint warm ring; GOLDEN = bright
	 * golden ring + orbiting sparkles (sparkles gated by reduced-motion /
	 * intensity). Pure arithmetic + oval draws — render-thread safe.
	 */
	public void renderOver(Graphics2D g, AuraState aura, int spriteX, int spriteY,
		int spriteW, int spriteH, long nowMs, RunieConfig config)
	{
		Effect effect = effectFor(aura);
		if (effect == Effect.NONE)
		{
			return;
		}
		boolean rm = config.reducedMotion();
		AnimationIntensity intensity = config.animationIntensity();
		Stroke oldStroke = g.getStroke();

		int d = Math.min(spriteW, spriteH) + (effect == Effect.GOLDEN_RADIANCE ? 12 : 8);
		int cx = spriteX + spriteW / 2;
		int cy = spriteY + spriteH / 2 + spriteH / 12;

		if (effect == Effect.PRESTIGE_RING)
		{
			float a = ringAlpha(nowMs, rm, intensity, 0.36f);
			g.setColor(withAlpha(PRESTIGE_RING_COLOR, a));
			g.setStroke(RING_STROKE);
			g.drawOval(cx - d / 2, cy - d / 2, d, d);
			g.setStroke(oldStroke);
			return;
		}

		// GOLDEN_RADIANCE ring
		float a = ringAlpha(nowMs, rm, intensity, 0.66f);
		g.setColor(withAlpha(GOLD, a));
		g.setStroke(GOLD_RING_STROKE);
		g.drawOval(cx - d / 2, cy - d / 2, d, d);
		g.setStroke(oldStroke);

		// orbiting sparkles on a flattened ellipse around the sprite
		int n = sparkleCount(rm, intensity);
		if (n > 0)
		{
			double orbit = 2 * Math.PI * (nowMs % ORBIT_PERIOD_MS) / (double) ORBIT_PERIOD_MS;
			double rx = d / 2.0 + 4;
			double ry = rx * 0.42;
			for (int k = 0; k < n; k++)
			{
				double ang = orbit + k * 2 * Math.PI / n;
				int px = cx + (int) Math.round(rx * Math.cos(ang));
				int py = cy + (int) Math.round(ry * Math.sin(ang));
				int s = (k % 2 == 0) ? 3 : 2;
				// sparkles behind the sprite's top half read as "passing behind";
				// keep it simple: draw all, tiny enough not to occlude
				g.setColor(withAlpha(GOLD_CORE, 0.55f + 0.35f * (float) Math.max(0, Math.sin(ang))));
				g.fillOval(px - s / 2, py - s / 2, s, s);
			}
		}
	}

	// ------------------------------------------------------------------
	// Evolution burst (rework part 2): one-shot flash + sparkle over the sprite
	// ------------------------------------------------------------------

	/** One-shot evolution burst length (ms-driven, §6.2). */
	public static final long EVOLUTION_BURST_MS = 1_000;
	/** Grander golden burst (p3 → L120 mastery milestone). */
	public static final long GOLDEN_BURST_MS = 1_400;

	private static final Color EVO_FLASH = new Color(210, 240, 255);
	private static final Color EVO_SPARK = new Color(255, 255, 255);

	/**
	 * One-shot evolution burst over the sprite box. {@code progress} is 0..1
	 * through the burst; callers clamp/stop past 1. Normal motion: an expanding
	 * flash ring + radial sparkles; the GOLDEN variant reuses the pre-rendered
	 * radiance raster for a grander bloom. Reduced motion (§6.4): a single
	 * static glow that fades out — no ring expansion, no sparkles. Pure
	 * arithmetic + blits; zero allocation.
	 */
	public void renderEvolutionBurst(Graphics2D g, int spriteX, int spriteY,
		int spriteW, int spriteH, double progress, boolean golden, RunieConfig config)
	{
		if (progress < 0 || progress >= 1)
		{
			return;
		}
		int cx = spriteX + spriteW / 2;
		int cy = spriteY + spriteH / 2;
		Color base = golden ? GOLD : EVO_FLASH;
		float fade = (float) (1.0 - progress);
		Composite oldComposite = g.getComposite();
		Stroke oldStroke = g.getStroke();

		// bloom: the cached radiance raster, scaled up through the burst
		int d = (int) (Math.max(spriteW, spriteH) * (golden ? 1.2 + 1.3 * progress : 0.9 + 0.9 * progress));
		float bloomAlpha = (golden ? 0.85f : 0.6f) * fade;
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, bloomAlpha)));
		g.drawImage(glowRaster, cx - d / 2, cy - d / 2, d, d, null);
		g.setComposite(oldComposite);

		if (config.reducedMotion())
		{
			return; // static fading glow only — no ring, no particles (§6.4)
		}

		// expanding flash ring
		int r = (int) (Math.min(spriteW, spriteH) * (0.35 + 0.85 * progress));
		g.setColor(withAlpha(base, 0.8f * fade));
		g.setStroke(golden ? GOLD_RING_STROKE : RING_STROKE);
		g.drawOval(cx - r, cy - r, 2 * r, 2 * r);
		g.setStroke(oldStroke);

		// radial sparkles flying outward (deterministic angles; golden = more)
		int n = golden ? 12 : 8;
		double reach = r + 6 + 10 * progress;
		for (int k = 0; k < n; k++)
		{
			double ang = k * 2 * Math.PI / n + (golden ? 0.26 : 0.4);
			int px = cx + (int) Math.round(reach * Math.cos(ang));
			int py = cy + (int) Math.round(reach * 0.8 * Math.sin(ang));
			int s = (k % 3 == 0) ? 4 : 3;
			g.setColor(withAlpha(k % 2 == 0 ? EVO_SPARK : base, 0.9f * fade));
			g.fillOval(px - s / 2, py - s / 2, s, s);
		}
	}

	// ------------------------------------------------------------------

	private static Color withAlpha(Color c, float alpha)
	{
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}

	/** One-time radial golden glow raster (soft core → transparent edge). */
	private static BufferedImage renderGlowRaster(int px)
	{
		BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		float r = px / 2f;
		RadialGradientPaint paint = new RadialGradientPaint(
			new Point2D.Float(r, r), r,
			new float[]{0f, 0.35f, 0.7f, 1f},
			new Color[]{
				new Color(255, 236, 160, 200),
				new Color(255, 214, 84, 140),
				new Color(255, 196, 40, 55),
				new Color(255, 196, 40, 0)
			},
			MultipleGradientPaint.CycleMethod.NO_CYCLE);
		g.setPaint(paint);
		g.fillOval(0, 0, px, px);
		g.dispose();
		return img;
	}
}
