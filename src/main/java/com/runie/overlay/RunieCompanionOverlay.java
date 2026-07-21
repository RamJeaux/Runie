package com.runie.overlay;

import com.runie.RunieConfig;
import com.runie.anim.AnimationService;
import com.runie.core.AuraState;
import com.runie.core.EggService;
import com.runie.core.ProgressionService;
import com.runie.core.RunieClock;
import com.runie.creatures.CreatureDefinition;
import com.runie.creatures.CreatureRegistry;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The on-screen companion (architecture §7.1).
 *
 * <p><b>Click-through safe by construction</b>: this overlay installs NO mouse
 * listeners and adds no interactive menu entries — game-world clicks under the
 * sprite pass through untouched. RuneLite's built-in alt-drag (overlay edit
 * mode) is the only interaction, and OverlayManager persists the position.
 *
 * <p>Render path is arithmetic + {@code drawImage} only (§13): the idle frames
 * are pre-decoded by {@link AnimationService} and played back as a ping-pong
 * loop (the art IS the animation — no procedural warps); fonts/strokes are
 * cached; the egg toast polls two ints. The shiny shimmer and the one-shot
 * evolution burst are composited layers (ShinyRenderer / AuraRenderer) — no
 * per-creature art, zero steady-state allocation.
 */
@Singleton
public class RunieCompanionOverlay extends Overlay
{
	private static final long TOAST_DURATION_MS = 4000;
	private static final Color CHIP_BG = new Color(0, 0, 0, 140);
	private static final Color TOAST_BG = new Color(20, 60, 20, 190);
	private static final Color TOAST_FG = new Color(170, 255, 170);
	private static final Font CHIP_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);

	private final AnimationService animationService;
	private final AuraRenderer auraRenderer;
	private final ShinyRenderer shinyRenderer;
	private final ProgressionService progressionService;
	private final CreatureRegistry registry;
	private final EggService eggService;
	private final RunieConfig config;
	private final RunieClock clock;

	// egg toast state (poll-based: no listener seam needed on EggService)
	private int lastEggs = -1;
	private int toastAmount;
	private long toastUntilMs;

	// one-shot evolution burst (rework part 2): ms-anchored, self-expiring
	private volatile long burstStartMs = Long.MIN_VALUE;
	private volatile boolean burstGolden;

	@Inject
	public RunieCompanionOverlay(AnimationService animationService, AuraRenderer auraRenderer,
		ShinyRenderer shinyRenderer, ProgressionService progressionService, CreatureRegistry registry,
		EggService eggService, RunieConfig config, RunieClock clock)
	{
		this.animationService = animationService;
		this.auraRenderer = auraRenderer;
		this.shinyRenderer = shinyRenderer;
		this.progressionService = progressionService;
		this.registry = registry;
		this.eggService = eggService;
		this.config = config;
		this.clock = clock;
		setPosition(OverlayPosition.BOTTOM_RIGHT); // default; user drags freely
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_LOW);
		setMovable(true);
		setResettable(true);
	}

	/**
	 * Trigger the one-shot evolution burst (stage crossings; {@code golden} for
	 * the p3→L120 mastery milestone). Safe from any thread — the render path
	 * reads the volatile anchor.
	 */
	public void playEvolutionBurst(boolean golden)
	{
		burstGolden = golden;
		burstStartMs = clock.elapsedMs();
	}

	/** True while an evolution burst is still playing (test seam). */
	public boolean isBurstActive()
	{
		return burstStartMs != Long.MIN_VALUE;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showCompanion())
		{
			return null;
		}
		long now = clock.elapsedMs();
		BufferedImage frame = animationService.currentCompanionFrame(now);
		AnimationService.CompanionClips clips = animationService.getActiveClips();
		if (frame == null || clips == null)
		{
			return null;
		}

		int chipH = config.showNameplate() ? 16 : 0;
		int toastH = 20;
		int fw = frame.getWidth();
		int fh = frame.getHeight();
		// reserve margin around the sprite so the composited aura layers
		// (golden glow extends ~0.35× beyond the sprite box) never clip
		int auraPadX = (int) Math.ceil(Math.max(fw, fh) * 0.35) + 4;
		int headroom = (int) Math.ceil(fh * 0.30);
		int w = Math.max(fw + 2 * auraPadX, 90);
		int h = toastH + headroom + fh + chipH;
		int baselineY = toastH + headroom + fh; // the ground line the feet stand on
		int spriteX = (w - fw) / 2;
		int spriteY = baselineY - fh;

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// glow layer UNDER the sprite (GOLDEN mastery radiance; no-op otherwise)
		auraRenderer.renderUnder(g, clips.aura, spriteX, spriteY, fw, fh, now, config);

		// the pre-rendered idle frame — ping-pong playback picked the frame
		g.drawImage(frame, spriteX, spriteY, null);

		// shiny shimmer (rework part 4): runtime hue-shift + glints, no new art
		boolean shiny = progressionService.getCreature(clips.creatureId)
			.map(c -> c.shiny).orElse(false);
		if (shiny)
		{
			shinyRenderer.renderShimmer(g, frame, spriteX, spriteY, now, config);
		}

		// ring/sparkle layer OVER the sprite (PRESTIGE ring / GOLDEN sparkles)
		auraRenderer.renderOver(g, clips.aura, spriteX, spriteY, fw, fh, now, config);

		// one-shot evolution burst (self-expires after its ms window)
		if (burstStartMs != Long.MIN_VALUE)
		{
			long total = burstGolden ? AuraRenderer.GOLDEN_BURST_MS : AuraRenderer.EVOLUTION_BURST_MS;
			double progress = (now - burstStartMs) / (double) total;
			if (progress >= 1.0)
			{
				burstStartMs = Long.MIN_VALUE;
			}
			else
			{
				auraRenderer.renderEvolutionBurst(g, spriteX, spriteY, fw, fh, progress, burstGolden, config);
			}
		}

		// nameplate chip (§7.1)
		if (config.showNameplate())
		{
			CreatureDefinition def = registry.byId(clips.creatureId);
			String name = def != null ? def.getName() : clips.creatureId;
			if (shiny)
			{
				name = "\u2726 " + name; // ✦ shiny tag
			}
			int level = progressionService.levelOf(clips.creatureId);
			String label = name + "  Lv " + level;
			g.setFont(CHIP_FONT);
			int cy = baselineY + 2;
			FontMetrics fm = g.getFontMetrics();
			int tw = fm.stringWidth(label);
			int cx = (w - tw) / 2;
			g.setColor(CHIP_BG);
			g.fillRoundRect(cx - 5, cy, tw + 10, 14, 8, 8);
			g.setColor(clips.aura == AuraState.GOLDEN ? new Color(255, 214, 84) : Color.WHITE);
			g.drawString(label, cx, cy + 11);
		}

		// egg-earned toast: poll the balance; a rise queues a 4s chip
		int eggBalance = eggService.getEggBalance();
		if (lastEggs >= 0 && eggBalance > lastEggs)
		{
			toastAmount = eggBalance - lastEggs;
			toastUntilMs = now + TOAST_DURATION_MS;
		}
		lastEggs = eggBalance;
		if (now < toastUntilMs && toastAmount > 0)
		{
			String toast = "+" + toastAmount + " Egg" + (toastAmount > 1 ? "s" : "");
			g.setFont(CHIP_FONT);
			FontMetrics fm = g.getFontMetrics();
			int tw = fm.stringWidth(toast);
			int tx = (w - tw) / 2;
			Composite old = g.getComposite();
			float fade = Math.min(1f, (toastUntilMs - now) / 600f);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));
			g.setColor(TOAST_BG);
			g.fillRoundRect(tx - 6, 0, tw + 12, 16, 8, 8);
			g.setColor(TOAST_FG);
			g.drawString(toast, tx, 12);
			g.setComposite(old);
		}

		return new Dimension(w, h); // bounds = alt-drag handle size
	}
}
