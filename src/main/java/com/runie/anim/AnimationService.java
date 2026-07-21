package com.runie.anim;

import com.runie.RunieConfig;
import com.runie.assets.AnimationClip;
import com.runie.assets.ArtStyle;
import com.runie.assets.AssetLoader;
import com.runie.config.AnimationIntensity;
import com.runie.core.AuraState;
import com.runie.core.ProgressionService;
import com.runie.core.RunieClock;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Millisecond-driven companion animation (architecture §6). The current frame
 * is a pure function of {@link RunieClock#elapsedMs()} — repaint-driven, no
 * timer thread, correct at any client framerate.
 *
 * <p><b>Idle = pre-rendered frames, played ping-pong.</b> Each creature /
 * style / stage ships its idle as a real PNG sequence
 * ({@code idle/frame_000..frame_0NN}); {@link AssetLoader} discovers and
 * decodes the full sequence and this service plays it back and forth
 * (0,1,…,N-1,N-2,…,1) at {@link AssetLoader#IDLE_FPS} via
 * {@link IdleFramePlayer}. Variants that currently bundle only
 * {@code frame_000} render as a static sprite — when their loops drop into the
 * resource folder later they animate with no code change. A small
 * deterministic per-creature phase offset ({@link IdleFramePlayer#phaseOffsetMs})
 * keeps different companions from playing in perfect sync.
 *
 * <p>The earlier procedural mesh-deformation rig (band mesh, breathing warp,
 * behavior clips) was rejected and removed — no warps are applied to the
 * sprite; the art IS the animation.
 *
 * <p>Decode policy (§6.3): {@link #refresh()} snapshots the active creature /
 * stage / style / aura on the calling thread, then pre-decodes the clip set on
 * the injected executor and swaps it in via a volatile reference. The render
 * path only ever reads {@link CompanionClips} — zero decode, zero I/O.
 *
 * <p><b>Golden aura (Appendix B):</b> the L120 golden look is COMPOSITED over
 * the base idle by {@link com.runie.overlay.AuraRenderer} — never baked. A
 * bundled {@code aura_gold/} variant (Grubnak) is retained only as a fallback
 * frame source when no idle art exists at all.
 *
 * <p>Settings (§6.4): reduced motion pins {@code frame_000} (fully static);
 * SUBTLE intensity halves the effective idle fps.
 */
@Slf4j
@Singleton
public class AnimationService
{
	/** Immutable, atomically-swapped clip set for the active companion. */
	public static final class CompanionClips
	{
		public final String creatureId;
		public final int stage;
		public final ArtStyle style;
		public final AuraState aura;
		public final AnimationClip idle;
		/**
		 * Baked golden variant (legacy Grubnak art) — OPTIONAL FALLBACK only:
		 * used as the frame source when no idle art exists. The golden look
		 * itself is composited by AuraRenderer (Appendix B).
		 */
		public final AnimationClip golden;

		CompanionClips(String creatureId, int stage, ArtStyle style, AuraState aura,
			AnimationClip idle, AnimationClip golden)
		{
			this.creatureId = creatureId;
			this.stage = stage;
			this.style = style;
			this.aura = aura;
			this.idle = idle;
			this.golden = golden;
		}

		/** The base frame source: idle art, else the baked-golden fallback. */
		AnimationClip baseClip()
		{
			return idle != null ? idle : golden;
		}
	}

	private final AssetLoader assetLoader;
	private final com.runie.assets.ArtStyleService artStyleService;
	private final ProgressionService progressionService;
	private final RunieConfig config;
	private final RunieClock clock;
	private final Executor executor;

	private volatile CompanionClips active;

	@Inject
	public AnimationService(AssetLoader assetLoader, com.runie.assets.ArtStyleService artStyleService,
		ProgressionService progressionService, RunieConfig config, RunieClock clock,
		java.util.concurrent.ScheduledExecutorService executor)
	{
		this(assetLoader, artStyleService, progressionService, config, clock, (Executor) executor);
	}

	/** Test seam: a direct executor + fake clock make refresh() synchronous. */
	public AnimationService(AssetLoader assetLoader, com.runie.assets.ArtStyleService artStyleService,
		ProgressionService progressionService, RunieConfig config, RunieClock clock, Executor executor)
	{
		this.assetLoader = assetLoader;
		this.artStyleService = artStyleService;
		this.progressionService = progressionService;
		this.config = config;
		this.clock = clock;
		this.executor = executor;
	}

	/**
	 * Re-resolve the active companion's clip set (active creature / stage /
	 * style / aura changed). Cheap snapshot here; decode + swap on the executor.
	 */
	public void refresh()
	{
		Optional<String> activeId = progressionService.getActiveCreatureId();
		if (!activeId.isPresent())
		{
			active = null;
			return;
		}
		String id = activeId.get();
		int stage = Math.max(1, progressionService.currentStageOf(id));
		AuraState aura = progressionService.auraOf(id);
		ArtStyle style = artStyleService.effectiveStyle(); // locked Kawaii resolves to Pixel
		int targetPx = config.companionScale().getPixels();

		executor.execute(() ->
		{
			try
			{
				AnimationClip idle = assetLoader.getIdleClip(id, style, stage, targetPx);
				// baked golden variant: FALLBACK frame source only (Appendix B —
				// the golden look is composited by AuraRenderer over any creature)
				AnimationClip golden = aura == AuraState.GOLDEN
					? assetLoader.getGoldenClip(id, style, stage, targetPx) : null;
				if (idle == null && golden == null)
				{
					active = null;
					return;
				}
				active = new CompanionClips(id, stage, style, aura, idle, golden);
			}
			catch (Exception e)
			{
				log.warn("Runie: companion clip refresh failed", e);
			}
		});
	}

	/** Drop everything (shutdown / style-pack invalidation). */
	public void reset()
	{
		active = null;
	}

	public CompanionClips getActiveClips()
	{
		return active;
	}

	/**
	 * The companion frame for {@code nowElapsedMs} — pure arithmetic + array
	 * lookup (render-thread safe). Null when no companion art is resident.
	 *
	 * <ul>
	 *   <li>Reduced motion pins {@code frame_000} — fully static (§6.4).</li>
	 *   <li>Otherwise the idle sequence plays as a seamless ping-pong loop at
	 *       {@link AssetLoader#IDLE_FPS}; SUBTLE intensity halves the effective
	 *       fps by halving elapsed time.</li>
	 *   <li>Single-frame sequences are inherently static (index is always 0).</li>
	 *   <li>A deterministic per-creature phase offset de-syncs companions.</li>
	 * </ul>
	 */
	public BufferedImage currentCompanionFrame(long nowElapsedMs)
	{
		CompanionClips c = active;
		if (c == null)
		{
			return null;
		}
		AnimationClip base = c.baseClip();
		if (base == null)
		{
			return null;
		}
		if (config.reducedMotion())
		{
			return base.firstFrame(); // hold frame_000, zero motion (§6.4)
		}
		long elapsed = nowElapsedMs + IdleFramePlayer.phaseOffsetMs(c.creatureId);
		if (config.animationIntensity() == AnimationIntensity.SUBTLE)
		{
			elapsed = elapsed / 2; // half-speed playback = half the effective fps
		}
		return base.frame(IdleFramePlayer.frameIndex(base.frameCount(), elapsed, base.getPerFrameMs()));
	}
}
