package com.runie.support;

import com.runie.assets.ArtStyle;
import com.runie.creatures.CreatureDefinition;
import com.runie.creatures.CreatureRegistry;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Test-only art seeding. Since the real animation frames no longer ship in the
 * jar (they stream into RUNELITE_DIR/runie/assets via {@link
 * com.runie.core.RunieAssetService}), tests that need resolvable art point an
 * {@link com.runie.assets.AssetLoader} at a cache directory populated here with
 * synthetic sprite sheets. The whole roster is seeded once per JVM and shared.
 */
public final class TestArt
{
	public static final int IDLE_FRAMES = 16;

	private static Path shared;

	private TestArt()
	{
	}

	/**
	 * A cache dir seeded with a 16-frame idle sheet for every roster line × style
	 * × stage, plus a baked golden (aura_gold) fallback for grubnak stage 3 in
	 * both styles. Seeded once and reused. Point AssetLoader at it via
	 * {@code loader.setAssetCacheDir(TestArt.roster(registry))}.
	 */
	public static synchronized Path roster(CreatureRegistry registry)
	{
		if (shared != null)
		{
			return shared;
		}
		try
		{
			Path cache = Files.createTempDirectory("runie-art-cache");
			for (CreatureDefinition def : registry.all())
			{
				for (ArtStyle style : ArtStyle.values())
				{
					for (int stage = 1; stage <= 3; stage++)
					{
						writeSheet(cache, def.getId(), style, stage, "idle", IDLE_FRAMES);
					}
				}
			}
			// baked golden variant exists ONLY for grubnak stage 3 (optional fallback)
			writeSheet(cache, "grubnak", ArtStyle.KAWAII, 3, "aura_gold", IDLE_FRAMES);
			writeSheet(cache, "grubnak", ArtStyle.PIXEL, 3, "aura_gold", IDLE_FRAMES);
			shared = cache;
			return cache;
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/** Write a synthetic horizontal sprite sheet of {@code n} equal cells. */
	public static void writeSheet(Path cache, String id, ArtStyle style, int stage, String anim, int n)
		throws IOException
	{
		int cellW = 16;
		int cellH = 24;
		BufferedImage sheet = new BufferedImage(cellW * n, cellH, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = sheet.createGraphics();
		for (int i = 0; i < n; i++)
		{
			// distinct-ish opaque block per cell so frames are non-empty
			g.setColor(new Color(60 + (i * 9) % 180, 120, 200, 255));
			g.fillRect(i * cellW + 2, 3, cellW - 4, cellH - 6);
		}
		g.dispose();
		Path target = cache.resolve(String.format(
			"com/runie/creatures/%s/art/%s/stage%d/%s/%s.%d.png",
			id, style.getFolderToken(), stage, anim, anim, n));
		Files.createDirectories(target.getParent());
		ImageIO.write(sheet, "png", target.toFile());
	}
}
