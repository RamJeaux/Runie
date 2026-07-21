package com.runie.core;

import com.runie.assets.AnimationClip;
import com.runie.assets.ArtStyle;
import com.runie.assets.AssetLoader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * RunieAssetService: on first launch it streams the art bundle (listed in the
 * bundled art_index.json) from github.com into RUNELITE_DIR/runie/assets, only
 * writes files that validate as real PNGs, skips what it already has, and marks
 * the sync complete only when nothing failed. AssetLoader then resolves art from
 * that cache.
 */
public class RunieAssetServiceTest
{
	private ScheduledExecutorService executor;
	private AssetLoader loader;

	@Before
	public void setUp()
	{
		executor = Executors.newSingleThreadScheduledExecutor();
		loader = new AssetLoader();
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	/** A real, sliceable PNG (wide enough for a 16-cell idle sheet). */
	private static byte[] png()
	{
		try
		{
			BufferedImage img = new BufferedImage(320, 32, BufferedImage.TYPE_INT_ARGB);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(img, "png", out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Test
	public void downloadsCachesAndIsIdempotent() throws IOException
	{
		byte[] png = png();
		RunieAssetService svc = new RunieAssetService(loader, executor, url -> png);
		Path cache = Files.createTempDirectory("runie-dl-test");

		int downloaded = svc.syncNow(cache, null, null);
		assertTrue("should fetch the whole indexed roster", downloaded > 400);

		// a known idle sheet + a frame_000 landed and are valid images
		Path sheet = cache.resolve("com/runie/creatures/grubnak/art/kawaii/stage1/idle/idle.16.png");
		assertTrue(Files.isRegularFile(sheet));
		assertNotNull(ImageIO.read(sheet.toFile()));
		// completion marker written since nothing failed
		assertTrue(Files.isRegularFile(cache.resolve(".version")));

		// second run: everything cached → nothing re-downloaded
		assertEquals(0, svc.syncNow(cache, null, null));
	}

	@Test
	public void rejectsCorruptDownloadsAndRetriesNextLaunch() throws IOException
	{
		RunieAssetService svc = new RunieAssetService(loader, executor, url -> "definitely not a png".getBytes());
		Path cache = Files.createTempDirectory("runie-dl-bad");

		int downloaded = svc.syncNow(cache, null, null);
		assertEquals("corrupt payloads must never be written", 0, downloaded);
		assertFalse("no completion marker while downloads fail", Files.isRegularFile(cache.resolve(".version")));
	}

	@Test
	public void assetLoaderResolvesDownloadedArtFromCache() throws IOException
	{
		byte[] png = png();
		RunieAssetService svc = new RunieAssetService(loader, executor, url -> png);
		Path cache = Files.createTempDirectory("runie-dl-load");
		svc.syncNow(cache, null, null);

		loader.setAssetCacheDir(cache);
		AnimationClip idle = loader.getIdleClip("grubnak", ArtStyle.KAWAII, 1, 128);
		assertNotNull("downloaded idle sheet must resolve", idle);
		assertEquals(16, idle.frameCount());
	}
}
