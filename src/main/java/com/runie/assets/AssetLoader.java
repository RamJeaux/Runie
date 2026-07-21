package com.runie.assets;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves creature x stage x style x animation to cached, pre-decoded
 * {@link AnimationClip}s (architecture §5.1). Primary art is a single packed
 * sprite sheet ({@code <anim>.<n>.png}, n equal-width cells) sliced at load;
 * a legacy {@code frame_000..frame_N} sequence (probed until the first miss)
 * remains a supported fallback for single-frame art and test fixtures.
 *
 * <p><b>Never call from {@code render()}</b> — decode happens when
 * {@link com.runie.anim.AnimationService} refreshes (off the client thread) or
 * when the Swing panel builds thumbnails (EDT, once, then cached). The overlay
 * render path only ever touches already-decoded frames.
 *
 * <p>Fallback chain (§5.2): requested style → {@link ArtStyle#KAWAII} → null
 * (callers render a silhouette / skip). A missing frame set can never NPE the
 * render loop; it logs once per key.
 */
@Slf4j
@Singleton
public class AssetLoader
{
	static final String CREATURES_ROOT = "/com/runie/creatures/";
	static final int MAX_PROBE_FRAMES = 64;

	/**
	 * Standard idle framerate (architecture §6.1: idle 10–15fps). The single
	 * knob for companion idle playback speed — ping-pong playback derives its
	 * per-frame duration from this.
	 */
	public static final int IDLE_FPS = 12;

	private final Map<String, AnimationClip> clipCache = new ConcurrentHashMap<>();
	private final Map<String, BufferedImage> thumbCache = new ConcurrentHashMap<>();

	/**
	 * Root of the downloaded-art cache (RUNELITE_DIR/runie/assets), populated by
	 * {@link com.runie.core.RunieAssetService} on first launch. When set, it is
	 * the PRIMARY art source; the bundled classpath is only a fallback (kept tiny
	 * so the jar stays under the Plugin Hub 10 MB limit — the full animation art
	 * lives outside the jar and streams into this cache).
	 */
	private volatile Path assetCacheDir;

	/** Point the loader at the downloaded-art cache root (see RunieAssetService). */
	public void setAssetCacheDir(Path dir)
	{
		this.assetCacheDir = dir;
		invalidate(); // any clips decoded before the cache was ready must be re-resolved
	}

	/**
	 * Resource path for one frame — the single place the §5.1 convention lives.
	 * {@code /com/runie/creatures/<id>/art/<style>/stage<N>/<anim>/frame_###.png}
	 */
	static String framePath(String creatureId, ArtStyle style, int stage, String animation, int frame)
	{
		return String.format("%s%s/art/%s/stage%d/%s/frame_%03d.png",
			CREATURES_ROOT, creatureId, style.getFolderToken(), stage, animation, frame);
	}

	/**
	 * Resource path for a packed sprite <b>sheet</b>: a single horizontal strip of
	 * {@code frames} equal-width cells at
	 * {@code /com/runie/creatures/<id>/art/<style>/stage<N>/<anim>/<anim>.<frames>.png}.
	 * Sheets are the primary art form (one file per loop); the legacy
	 * {@code frame_###.png} sequence remains a supported fallback.
	 */
	static String sheetPath(String creatureId, ArtStyle style, int stage, String animation, int frames)
	{
		return String.format("%s%s/art/%s/stage%d/%s/%s.%d.png",
			CREATURES_ROOT, creatureId, style.getFolderToken(), stage, animation, animation, frames);
	}

	/**
	 * Load (or return cached) a clip, pre-scaled so its largest edge is
	 * {@code targetPx}. Applies the style fallback chain; returns null when no
	 * art exists in any style (caller silhouettes).
	 */
	public AnimationClip getClip(String creatureId, ArtStyle style, int stage, String animation,
		int fps, boolean loop, int targetPx)
	{
		String key = creatureId + '|' + style.getFolderToken() + '|' + stage + '|' + animation
			+ '|' + fps + '|' + targetPx;
		AnimationClip cached = clipCache.get(key);
		if (cached != null)
		{
			return cached;
		}
		AnimationClip clip = decodeClip(creatureId, style, stage, animation, fps, loop, targetPx);
		if (clip == null && style != ArtStyle.KAWAII)
		{
			clip = decodeClip(creatureId, ArtStyle.KAWAII, stage, animation, fps, loop, targetPx); // fallback
		}
		if (clip != null)
		{
			clipCache.put(key, clip);
		}
		else
		{
			log.debug("Runie: no art for {} (style={}, stage={}, anim={})", creatureId, style, stage, animation);
		}
		return clip;
	}

	/** Idle clip at the standard idle fps. */
	public AnimationClip getIdleClip(String creatureId, ArtStyle style, int stage, int targetPx)
	{
		return getClip(creatureId, style, stage, "idle", IDLE_FPS, true, targetPx);
	}

	/**
	 * Baked golden L120 variant (bundled under {@code aura_gold}) — retained as
	 * an OPTIONAL FALLBACK frame source only. The golden look itself is
	 * composited over the base idle by {@code AuraRenderer} (Appendix B), so it
	 * applies to any creature without per-creature baked art.
	 */
	public AnimationClip getGoldenClip(String creatureId, ArtStyle style, int stage, int targetPx)
	{
		return getClip(creatureId, style, stage, "aura_gold", IDLE_FPS, true, targetPx);
	}

	/**
	 * Collection-grid thumbnail: stage-appropriate idle frame 0, scaled to
	 * {@code px}, cached (§6.3). Null when the creature has no art at all.
	 */
	public BufferedImage getThumbnail(String creatureId, ArtStyle style, int stage, int px)
	{
		String key = creatureId + '|' + style.getFolderToken() + '|' + stage + '|' + px;
		BufferedImage cached = thumbCache.get(key);
		if (cached != null)
		{
			return cached;
		}
		AnimationClip clip = getIdleClip(creatureId, style, stage, px);
		if (clip == null)
		{
			return null;
		}
		BufferedImage thumb = clip.firstFrame();
		thumbCache.put(key, thumb);
		return thumb;
	}

	/** Drop all decoded frames (style-pack swap / shutdown). */
	public void invalidate()
	{
		clipCache.clear();
		thumbCache.clear();
	}

	// ------------------------------------------------------------------

	private AnimationClip decodeClip(String creatureId, ArtStyle style, int stage, String animation,
		int fps, boolean loop, int targetPx)
	{
		// Primary: a single packed sprite sheet <anim>.<n>.png (n equal-width cells).
		for (int n = 1; n <= MAX_PROBE_FRAMES; n++)
		{
			BufferedImage sheet = decode(sheetPath(creatureId, style, stage, animation, n));
			if (sheet != null)
			{
				AnimationClip clip = sliceSheet(sheet, n, fps, loop, targetPx);
				if (clip != null)
				{
					return clip;
				}
			}
		}
		// Fallback: legacy per-frame sequence frame_000..N, probed until first miss (§5.1).
		List<BufferedImage> frames = new ArrayList<>();
		for (int i = 0; i < MAX_PROBE_FRAMES; i++)
		{
			BufferedImage img = decode(framePath(creatureId, style, stage, animation, i));
			if (img == null)
			{
				break;
			}
			frames.add(scaleToFit(img, targetPx));
		}
		if (frames.isEmpty())
		{
			return null;
		}
		return new AnimationClip(frames.toArray(new BufferedImage[0]), fps, loop);
	}

	/** Slice a horizontal strip of {@code n} equal-width cells into pre-scaled frames. */
	private static AnimationClip sliceSheet(BufferedImage sheet, int n, int fps, boolean loop, int targetPx)
	{
		int fw = sheet.getWidth() / n;
		int fh = sheet.getHeight();
		if (fw < 1 || fh < 1)
		{
			return null;
		}
		BufferedImage[] frames = new BufferedImage[n];
		for (int i = 0; i < n; i++)
		{
			// detach each cell from the shared sheet raster into its own ARGB image
			BufferedImage cell = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = cell.createGraphics();
			g.drawImage(sheet.getSubimage(i * fw, 0, fw, fh), 0, 0, null);
			g.dispose();
			frames[i] = scaleToFit(cell, targetPx);
		}
		return new AnimationClip(frames, fps, loop);
	}

	private BufferedImage decode(String resourcePath)
	{
		// 1) downloaded-art cache (primary source once RunieAssetService has synced)
		Path dir = assetCacheDir;
		if (dir != null)
		{
			String rel = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
			Path f = dir.resolve(rel);
			if (Files.isRegularFile(f))
			{
				try (InputStream in = Files.newInputStream(f))
				{
					BufferedImage img = ImageIO.read(in);
					if (img != null)
					{
						return img;
					}
				}
				catch (IOException e)
				{
					log.warn("Runie: failed to read cached {}", f, e);
				}
			}
		}
		// 2) bundled classpath (kept minimal: test fixtures / any small bundled fallback)
		try (InputStream in = AssetLoader.class.getResourceAsStream(resourcePath))
		{
			if (in == null)
			{
				return null;
			}
			return ImageIO.read(in);
		}
		catch (IOException e)
		{
			log.warn("Runie: failed to decode {}", resourcePath, e);
			return null;
		}
	}

	/** Pre-scale at decode time so render() is a plain blit (§6.3, §13). */
	static BufferedImage scaleToFit(BufferedImage src, int targetPx)
	{
		int max = Math.max(src.getWidth(), src.getHeight());
		if (max <= targetPx)
		{
			return toArgb(src);
		}
		double s = targetPx / (double) max;
		int w = Math.max(1, (int) Math.round(src.getWidth() * s));
		int h = Math.max(1, (int) Math.round(src.getHeight() * s));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	private static BufferedImage toArgb(BufferedImage src)
	{
		if (src.getType() == BufferedImage.TYPE_INT_ARGB)
		{
			return src;
		}
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return out;
	}
}
