package com.runie.core;

import com.google.gson.Gson;
import com.runie.assets.AssetLoader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Streams the creature art bundle into a local cache on first launch, so the
 * distributed plugin jar stays under the Plugin Hub's 10&nbsp;MB limit
 * (architecture: the ~28&nbsp;MB of animation frames live on the repo's
 * {@code assets} branch, NOT in the jar). Files are fetched once from
 * github.com — the RuneLite maintainers confirmed github.com networking needs
 * no user warning — validated as real PNGs, and written under
 * {@code RUNELITE_DIR/runie/assets/}. {@link AssetLoader} reads that cache first,
 * so once synced Runie is fully local; a missing/failed download simply degrades
 * to a silhouette and retries next launch. No telemetry, no personal data — the
 * only traffic is GETs for static art files.
 */
@Slf4j
@Singleton
public class RunieAssetService
{
	static final String INDEX_RESOURCE = "/com/runie/art_index.json";
	/** Refresh the UI every N freshly downloaded files so art appears progressively. */
	private static final int REFRESH_EVERY = 24;

	/** Test seam over the network so sync() is unit-testable without github.com. */
	public interface Fetcher
	{
		/** GET the URL, returning the raw bytes, or throw on any non-success. */
		byte[] get(String url) throws IOException;
	}

	private static final class Index
	{
		int version;
		String base;
		List<String> files;
	}

	private final AssetLoader assetLoader;
	private final ScheduledExecutorService executor;
	private final Fetcher fetcher;
	private final Gson gson;

	@Inject
	public RunieAssetService(AssetLoader assetLoader, ScheduledExecutorService executor,
		OkHttpClient http, Gson gson)
	{
		// Plugin Hub requires the client's shared, injected Gson (never a fresh instance).
		this(assetLoader, executor, new OkHttpFetcher(http), gson);
	}

	/** Test seam: supply a fake Fetcher + Gson (and typically a same-thread executor). */
	public RunieAssetService(AssetLoader assetLoader, ScheduledExecutorService executor,
		Fetcher fetcher, Gson gson)
	{
		this.assetLoader = assetLoader;
		this.executor = executor;
		this.fetcher = fetcher;
		this.gson = gson;
	}

	/**
	 * Point the loader at {@code RUNELITE_DIR/runie/assets} and kick off a
	 * background sync. Safe to call on the client thread — the actual download
	 * runs on the injected executor.
	 */
	public void start(Path runeliteDir, Runnable onProgress, Runnable onComplete)
	{
		Path cacheDir = runeliteDir.resolve("runie").resolve("assets");
		assetLoader.setAssetCacheDir(cacheDir);
		executor.execute(() -> syncNow(cacheDir, onProgress, onComplete));
	}

	/**
	 * Download any missing art into {@code cacheDir}. Idempotent: files already
	 * present are skipped, and once a full clean sync completes a version marker
	 * lets subsequent launches short-circuit. Returns the number newly fetched.
	 * Package-visible for tests.
	 */
	int syncNow(Path cacheDir, Runnable onProgress, Runnable onComplete)
	{
		int done = 0;
		try
		{
			Index idx = readIndex();
			if (idx == null || idx.files == null || idx.base == null)
			{
				log.warn("Runie: art index missing/invalid; skipping asset sync");
				return 0;
			}
			Files.createDirectories(cacheDir);
			Path versionMarker = cacheDir.resolve(".version");
			if (isSynced(versionMarker, idx.version))
			{
				if (onComplete != null)
				{
					onComplete.run();
				}
				return 0;
			}

			int failed = 0;
			int sinceRefresh = 0;
			for (String rel : idx.files)
			{
				Path target = cacheDir.resolve(rel);
				if (Files.isRegularFile(target))
				{
					continue; // resume-friendly: keep what we already have
				}
				try
				{
					byte[] bytes = fetcher.get(idx.base + rel);
					if (!isPng(bytes))
					{
						failed++;
						log.debug("Runie: not a PNG, skipping {}", rel);
						continue;
					}
					writeAtomic(target, bytes);
					done++;
					if (++sinceRefresh >= REFRESH_EVERY)
					{
						sinceRefresh = 0;
						assetLoader.invalidate();
						if (onProgress != null)
						{
							onProgress.run();
						}
					}
				}
				catch (Exception e)
				{
					failed++;
					log.debug("Runie: asset fetch failed for {}: {}", rel, e.toString());
				}
			}

			assetLoader.invalidate();
			if (failed == 0)
			{
				writeVersion(versionMarker, idx.version); // only mark done when EVERYTHING landed
			}
			else
			{
				log.info("Runie: asset sync incomplete ({} failed); will retry next launch", failed);
			}
			if (onComplete != null)
			{
				onComplete.run();
			}
		}
		catch (Exception e)
		{
			log.warn("Runie: asset sync error", e);
		}
		return done;
	}

	private Index readIndex()
	{
		try (InputStream in = RunieAssetService.class.getResourceAsStream(INDEX_RESOURCE))
		{
			if (in == null)
			{
				return null;
			}
			return gson.fromJson(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), Index.class);
		}
		catch (Exception e)
		{
			log.warn("Runie: failed to read art index", e);
			return null;
		}
	}

	private boolean isSynced(Path versionMarker, int version)
	{
		try
		{
			return Files.isRegularFile(versionMarker)
				&& new String(Files.readAllBytes(versionMarker), java.nio.charset.StandardCharsets.UTF_8)
				.trim().equals(Integer.toString(version));
		}
		catch (IOException e)
		{
			return false;
		}
	}

	private void writeVersion(Path versionMarker, int version) throws IOException
	{
		Files.write(versionMarker, Integer.toString(version).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static boolean isPng(byte[] b)
	{
		if (b == null || b.length < 8
			|| (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G')
		{
			return false;
		}
		try (InputStream in = new ByteArrayInputStream(b))
		{
			return ImageIO.read(in) != null; // fully decodes: rejects truncated/corrupt files
		}
		catch (IOException e)
		{
			return false;
		}
	}

	private static void writeAtomic(Path target, byte[] bytes) throws IOException
	{
		Files.createDirectories(target.getParent());
		Path tmp = target.resolveSibling(target.getFileName() + ".part");
		Files.write(tmp, bytes);
		try
		{
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException atomicUnsupported)
		{
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Production fetcher over RuneLite's shared OkHttpClient. */
	private static final class OkHttpFetcher implements Fetcher
	{
		private final OkHttpClient http;

		OkHttpFetcher(OkHttpClient http)
		{
			this.http = http;
		}

		@Override
		public byte[] get(String url) throws IOException
		{
			Request req = new Request.Builder().url(url).build();
			try (Response resp = http.newCall(req).execute())
			{
				if (!resp.isSuccessful() || resp.body() == null)
				{
					throw new IOException("HTTP " + resp.code() + " for " + url);
				}
				return resp.body().bytes();
			}
		}
	}
}
