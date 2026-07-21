package com.runie.overlay;

import com.runie.RunieConfig;
import com.runie.assets.AnimationClip;
import com.runie.assets.AssetLoader;
import com.runie.core.GachaService;
import com.runie.core.ProgressionService;
import com.runie.core.PullResult;
import com.runie.core.RunieClock;
import com.runie.creatures.CreatureDefinition;
import com.runie.creatures.CreatureRegistry;
import com.runie.creatures.Rarity;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Hatch → reveal cinematic (architecture §7.2). Registered always; renders only
 * while a presentation is active. Display-only: no listeners, cannot eat
 * clicks; dismissed by timeout or {@link #skip()} from the panel.
 *
 * <p>Phases (normal motion): a real hatching egg — the same bundled egg sprite
 * used on the Hatch button wobbles, cracks, and bursts open (0–1.5s) → creature
 * reveal card with name, rarity, NEW!/duplicate star gain + shiny tag (1.5–4.8s).
 * Reduced motion: a simple 0.8s fade-in card shown for 2.5s.
 *
 * <p><b>In-danger deferral (§7.3)</b>: while the danger supplier reports true,
 * the presentation clock is re-anchored each frame — the pull RESULT is already
 * committed and saved by GachaService; only the presentation waits, so the
 * cinematic never obscures combat.
 */
@Slf4j
@Singleton
public class RuniePullOverlay extends Overlay implements GachaService.PullListener
{
	static final long HATCH_MS = 1500;
	static final long REVEAL_MS = 3300;
	static final long REDUCED_FADE_MS = 800;
	static final long REDUCED_HOLD_MS = 2500;
	private static final int CARD_W = 280;
	private static final int CARD_H = 210;
	private static final int EGG_H = 120; // rendered height of the hatching egg sprite
	private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 16);
	private static final Font BODY_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Font BADGE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);

	/** Rarity glow colors, C..GM. */
	private static final Color[] RARITY_COLORS = {
		new Color(157, 157, 157), new Color(30, 200, 60), new Color(0, 112, 221),
		new Color(163, 53, 238), new Color(255, 128, 0), new Color(230, 204, 128),
	};

	public static Color rarityColor(Rarity rarity)
	{
		return RARITY_COLORS[rarity.tierIndex()];
	}

	private static final class Presentation
	{
		final PullResult result;
		final BufferedImage art; // pre-decoded before presentation starts
		long startMs;
		boolean started;
		boolean skipped;

		Presentation(PullResult result, BufferedImage art)
		{
			this.result = result;
			this.art = art;
		}
	}

	private final CreatureRegistry registry;
	private final ProgressionService progressionService;
	private final AssetLoader assetLoader;
	private final com.runie.assets.ArtStyleService artStyleService;
	private final RunieConfig config;
	private final RunieClock clock;
	private final Executor executor;

	/** Danger sensor, injected by the plugin (reads client state on demand). */
	private volatile BooleanSupplier dangerSupplier = () -> false;

	private volatile Presentation presentation;
	/** Cached scaled egg sprite for the hatch cinematic (same source as the Hatch button). */
	private BufferedImage eggSprite;

	@Inject
	public RuniePullOverlay(CreatureRegistry registry, ProgressionService progressionService,
		AssetLoader assetLoader, com.runie.assets.ArtStyleService artStyleService, RunieConfig config,
		RunieClock clock, java.util.concurrent.ScheduledExecutorService executor)
	{
		this(registry, progressionService, assetLoader, artStyleService, config, clock, (Executor) executor);
	}

	/** Test / mockup seam: direct executor makes presentation prep synchronous. */
	public RuniePullOverlay(CreatureRegistry registry, ProgressionService progressionService,
		AssetLoader assetLoader, com.runie.assets.ArtStyleService artStyleService, RunieConfig config,
		RunieClock clock, Executor executor)
	{
		this.registry = registry;
		this.progressionService = progressionService;
		this.assetLoader = assetLoader;
		this.artStyleService = artStyleService;
		this.config = config;
		this.clock = clock;
		this.executor = executor;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_HIGH);
	}

	public void setDangerSupplier(BooleanSupplier dangerSupplier)
	{
		this.dangerSupplier = dangerSupplier != null ? dangerSupplier : () -> false;
	}

	/** True while a reveal is being presented (panel shows its Skip button). */
	public boolean isPresenting()
	{
		return presentation != null;
	}

	/** Skip the cinematic: jump straight to (or dismiss) the reveal card. */
	public void skip()
	{
		Presentation p = presentation;
		if (p == null)
		{
			return;
		}
		if (!p.skipped && !config.reducedMotion())
		{
			p.skipped = true;
			p.startMs = clock.elapsedMs() - HATCH_MS; // jump past the hatch into the reveal phase
		}
		else
		{
			presentation = null; // second skip (or reduced motion): dismiss
		}
	}

	/** GachaService callback (client thread) — decode reveal art off-thread. */
	@Override
	public void onPull(PullResult result)
	{
		executor.execute(() ->
		{
			try
			{
				int stage = Math.max(1, progressionService.currentStageOf(result.creatureId));
				AnimationClip clip = assetLoader.getIdleClip(result.creatureId, artStyleService.effectiveStyle(), stage, 128);
				presentation = new Presentation(result, clip != null ? clip.firstFrame() : null);
			}
			catch (Exception e)
			{
				log.warn("Runie: pull reveal prep failed", e);
			}
		});
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Presentation p = presentation;
		if (p == null)
		{
			return null;
		}
		long now = clock.elapsedMs();
		if (dangerSupplier.getAsBoolean())
		{
			// defer/suppress presentation while in danger (§7.3) — the pull
			// result is already committed & saved; only the cinematic waits
			p.startMs = now;
			p.started = true;
			return null;
		}
		if (!p.started)
		{
			p.startMs = now;
			p.started = true;
		}
		long t = now - p.startMs;
		boolean reduced = config.reducedMotion();
		long total = reduced ? REDUCED_FADE_MS + REDUCED_HOLD_MS : HATCH_MS + REVEAL_MS;
		if (t >= total)
		{
			presentation = null;
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		Color rc = rarityColor(p.result.rarity);

		if (!reduced && t < HATCH_MS)
		{
			renderHatch(g, t, rc);
		}
		else
		{
			long rt = reduced ? t : t - HATCH_MS;
			float alpha = reduced
				? Math.min(1f, rt / (float) REDUCED_FADE_MS)
				: Math.min(1f, rt / 250f);
			renderRevealCard(g, p, rc, alpha);
		}
		return new Dimension(CARD_W, CARD_H);
	}

	// ------------------------------------------------------------------

	/**
	 * The hatching-egg cinematic: the same bundled egg sprite used on the Hatch
	 * button pops in, rocks with growing intensity while cracks spread across its
	 * shell, then bursts open in a rarity-colored flash of flying shell shards —
	 * handing off to the creature reveal card. Purely presentational.
	 */
	private void renderHatch(Graphics2D g, long t, Color rc)
	{
		g.setColor(new Color(12, 12, 16, 200));
		g.fillRoundRect(0, 0, CARD_W, CARD_H, 16, 16);

		BufferedImage egg = eggSprite();
		int ew = egg.getWidth();
		int eh = egg.getHeight();
		int cx = CARD_W / 2;
		int baseY = 150; // the egg rests its base here; art column matches the reveal card
		double p = t / (double) HATCH_MS;
		boolean burst = p >= 0.72;

		// soft, pulsing rarity glow behind the egg
		float pulse = (float) (0.5 + 0.5 * Math.sin(t / 120.0));
		g.setColor(new Color(rc.getRed(), rc.getGreen(), rc.getBlue(), (int) (36 + 40 * pulse)));
		g.fillOval(cx - 72, baseY - eh - 6, 144, 144);

		if (!burst)
		{
			double scale = p < 0.12 ? 0.7 + 0.3 * (p / 0.12) : 1.0; // pop-in
			double wobAmp = Math.toRadians(3 + 15 * clamp01((p - 0.10) / 0.6)); // rocking grows
			double angle = Math.sin(t / 70.0) * wobAmp;
			int sw = (int) (ew * scale);
			int sh = (int) (eh * scale);
			int ox = cx - sw / 2;
			int oy = baseY - sh;

			Graphics2D gg = (Graphics2D) g.create();
			gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			gg.rotate(angle, cx, baseY); // pivot on the base so it rocks like a real egg
			gg.drawImage(egg, ox, oy, sw, sh, null);
			drawCracks(gg, ox, oy, sw, sh, p);
			gg.dispose();

			drawHatchLabel(g, "Hatching...");
		}
		else
		{
			double bp = clamp01((p - 0.72) / 0.28);
			int fr = (int) (24 + 130 * bp);
			int fcx = cx;
			int fcy = baseY - eh / 2;
			g.setColor(new Color(255, 255, 255, (int) (150 * (1 - bp))));
			g.fillOval(fcx - fr / 2, fcy - fr / 2, fr, fr);
			g.setColor(new Color(rc.getRed(), rc.getGreen(), rc.getBlue(), (int) (210 * (1 - bp))));
			g.setStroke(new BasicStroke(3f));
			g.drawOval(fcx - fr / 2, fcy - fr / 2, fr, fr);

			// shell splits: top cap lifts and tips away, base settles; both fade
			float eggAlpha = (float) Math.max(0, 1 - bp * 1.15);
			int topH = (int) (eh * 0.46);
			BufferedImage top = egg.getSubimage(0, 0, ew, topH);
			BufferedImage bot = egg.getSubimage(0, topH, ew, eh - topH);
			drawPiece(g, top, cx, baseY - eh - (int) (bp * 44), Math.toRadians(-20 * bp), eggAlpha);
			drawPiece(g, bot, cx, baseY - eh + topH + (int) (bp * 10), Math.toRadians(9 * bp), eggAlpha);

			drawShards(g, cx, fcy, bp);
			drawHatchLabel(g, "Hatched!");
		}
	}

	/** Progressive shell cracks in egg-local space (drawn inside the egg's rotated context). */
	private void drawCracks(Graphics2D g, int ox, int oy, int w, int h, double p)
	{
		g.setStroke(new BasicStroke(2f));
		// each crack: normalized zig-zag points + the progress at which it starts
		drawCrack(g, ox, oy, w, h, p, 0.30,
			new double[]{0.50, 0.44, 0.55, 0.46, 0.40}, new double[]{0.16, 0.28, 0.38, 0.50, 0.60});
		drawCrack(g, ox, oy, w, h, p, 0.48,
			new double[]{0.50, 0.58, 0.52, 0.66}, new double[]{0.20, 0.33, 0.46, 0.56});
		drawCrack(g, ox, oy, w, h, p, 0.60,
			new double[]{0.30, 0.42, 0.50, 0.60, 0.72}, new double[]{0.46, 0.42, 0.48, 0.43, 0.47});
	}

	private void drawCrack(Graphics2D g, int ox, int oy, int w, int h, double p, double start,
		double[] nx, double[] ny)
	{
		if (p < start)
		{
			return;
		}
		double reveal = clamp01((p - start) / 0.14); // grows to full length
		int segs = nx.length - 1;
		double shown = reveal * segs;
		int prevX = ox + (int) (nx[0] * w);
		int prevY = oy + (int) (ny[0] * h);
		for (int i = 1; i < nx.length; i++)
		{
			double frac = Math.min(1.0, shown - (i - 1));
			if (frac <= 0)
			{
				break;
			}
			int tx = ox + (int) ((nx[i - 1] + (nx[i] - nx[i - 1]) * frac) * w);
			int ty = oy + (int) ((ny[i - 1] + (ny[i] - ny[i - 1]) * frac) * h);
			g.setColor(new Color(60, 44, 30, 230)); // dark fissure
			g.drawLine(prevX, prevY, tx, ty);
			g.setColor(new Color(255, 250, 235, 150)); // thin highlight
			g.drawLine(prevX, prevY - 1, tx, ty - 1);
			prevX = tx;
			prevY = ty;
		}
	}

	/** Draw a shell piece centered horizontally at cx with its top at topY, rotated about its own centre. */
	private void drawPiece(Graphics2D g, BufferedImage img, int cx, int topY, double angle, float alpha)
	{
		if (alpha <= 0f)
		{
			return;
		}
		Graphics2D gg = (Graphics2D) g.create();
		gg.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, Math.min(1f, alpha)));
		gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		int w = img.getWidth();
		int h = img.getHeight();
		gg.rotate(angle, cx, topY + h / 2.0);
		gg.drawImage(img, cx - w / 2, topY, null);
		gg.dispose();
	}

	private void drawShards(Graphics2D g, int cx, int cy, double bp)
	{
		int alpha = (int) (220 * (1 - bp));
		if (alpha <= 0)
		{
			return;
		}
		int n = 7;
		int dist = (int) (bp * 96);
		g.setColor(new Color(238, 226, 196, alpha));
		for (int i = 0; i < n; i++)
		{
			double ang = i * (2 * Math.PI / n) + 0.35;
			int sx = cx + (int) (Math.cos(ang) * dist);
			int sy = cy + (int) (Math.sin(ang) * dist) - (int) (bp * 12); // slight upward bias
			int s = Math.max(2, 6 - (int) (3 * bp));
			g.fillPolygon(new int[]{sx, sx + s, sx - s / 2}, new int[]{sy - s, sy + s, sy + s}, 3);
		}
	}

	private void drawHatchLabel(Graphics2D g, String s)
	{
		g.setFont(BODY_FONT);
		g.setColor(new Color(230, 230, 230, 220));
		FontMetrics fm = g.getFontMetrics();
		g.drawString(s, (CARD_W - fm.stringWidth(s)) / 2, CARD_H - 14);
	}

	private static double clamp01(double v)
	{
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}

	/** Lazily scaled copy of the shared Hatch-button egg sprite, reused for the hatch cinematic. */
	private BufferedImage eggSprite()
	{
		BufferedImage e = eggSprite;
		if (e == null)
		{
			e = com.runie.ui.EggIcon.scaled(EGG_H);
			eggSprite = e;
		}
		return e;
	}

	private void renderRevealCard(Graphics2D g, Presentation p, Color rc, float alpha)
	{
		int a = (int) (alpha * 255);
		g.setColor(new Color(12, 12, 16, Math.min(210, a)));
		g.fillRoundRect(0, 0, CARD_W, CARD_H, 16, 16);
		g.setColor(new Color(rc.getRed(), rc.getGreen(), rc.getBlue(), a));
		g.setStroke(new BasicStroke(3f));
		g.drawRoundRect(1, 1, CARD_W - 3, CARD_H - 3, 16, 16);

		CreatureDefinition def = registry.byId(p.result.creatureId);
		String name = def != null ? def.getName() : p.result.creatureId;

		// creature art, centered; rarity glow disc behind it
		int cx = CARD_W / 2;
		g.setColor(new Color(rc.getRed(), rc.getGreen(), rc.getBlue(), (int) (alpha * 60)));
		g.fillOval(cx - 58, 26, 116, 116);
		if (p.art != null)
		{
			int aw = p.art.getWidth();
			int ah = p.art.getHeight();
			java.awt.Composite old = g.getComposite();
			g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha));
			g.drawImage(p.art, cx - aw / 2, 84 - ah / 2 + 10, null);
			g.setComposite(old);
		}

		g.setFont(TITLE_FONT);
		g.setColor(new Color(255, 255, 255, a));
		FontMetrics fm = g.getFontMetrics();
		g.drawString(name, cx - fm.stringWidth(name) / 2, 158);

		g.setFont(BADGE_FONT);
		fm = g.getFontMetrics();
		String rarity = p.result.rarity.getDisplayName().toUpperCase();
		g.setColor(new Color(rc.getRed(), rc.getGreen(), rc.getBlue(), a));
		g.drawString(rarity, cx - fm.stringWidth(rarity) / 2, 175);

		String badge;
		Color badgeColor;
		if (p.result.isNew)
		{
			badge = "NEW!";
			badgeColor = new Color(120, 255, 120, a);
		}
		else
		{
			com.runie.core.StarTier tier = com.runie.core.StarTier.tierFor(p.result.starsAfter);
			badge = "Duplicate  +1 \u2605  (" + p.result.starsAfter
				+ (tier != null ? " - " + tier.getDisplayName() : "") + ")";
			Color tc = tier != null ? tier.getColor() : new Color(200, 200, 255);
			badgeColor = new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), a);
		}
		g.setColor(badgeColor);
		g.drawString(badge, cx - fm.stringWidth(badge) / 2, 190);
		if (p.result.becameShiny)
		{
			String shiny = "\u2726 SHINY! \u2726";
			g.setColor(new Color(160, 235, 255, a));
			g.drawString(shiny, cx - fm.stringWidth(shiny) / 2, 204);
		}
	}
}
