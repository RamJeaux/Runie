package com.runie.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * The egg glyph used across the UI (Hatch button etc.), backed by the bundled
 * sprite at classpath resource {@code com/runie/ui/egg.png} (~79x96,
 * transparent). The source image is loaded ONCE and cached; per-size scaled
 * copies (smooth, aspect-preserving) are derived on demand. If the resource is
 * missing or unreadable the old programmatically-painted glyph is used instead
 * — the icon path never throws.
 */
@Slf4j
public final class EggIcon
{
	/** Cached full-size sprite; null when the bundled resource failed to load. */
	private static final BufferedImage SOURCE = loadSource();

	private EggIcon()
	{
	}

	/**
	 * Egg glyph scaled to the given height in px (width follows the sprite's
	 * aspect ratio). Falls back to the painted glyph when the bundled sprite is
	 * unavailable.
	 */
	public static BufferedImage scaled(int heightPx)
	{
		if (SOURCE == null)
		{
			return paintedFallback(heightPx);
		}
		int h = Math.max(1, heightPx);
		int w = Math.max(1, (int) Math.round(h * (double) SOURCE.getWidth() / SOURCE.getHeight()));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.drawImage(SOURCE, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	private static BufferedImage loadSource()
	{
		try (InputStream in = EggIcon.class.getResourceAsStream("egg.png"))
		{
			if (in == null)
			{
				log.warn("Runie: bundled egg sprite missing (com/runie/ui/egg.png); using painted glyph");
				return null;
			}
			BufferedImage img = ImageIO.read(in);
			if (img == null)
			{
				log.warn("Runie: bundled egg sprite unreadable; using painted glyph");
			}
			return img;
		}
		catch (Exception ex)
		{
			log.warn("Runie: failed to load bundled egg sprite; using painted glyph", ex);
			return null;
		}
	}

	/** Legacy tiny painted egg (pre-sprite fallback — keeps the UI crash-free). */
	private static BufferedImage paintedFallback(int sz)
	{
		sz = Math.max(6, sz);
		BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(247, 238, 214));
		g.fillOval(2, 1, sz - 4, sz - 2);
		g.setColor(new Color(226, 210, 168));
		g.drawOval(2, 1, sz - 5, sz - 3);
		g.setColor(new Color(255, 252, 240));
		g.fillOval(5, 3, 3, 4); // highlight
		g.dispose();
		return img;
	}
}
