package com.runie.core;

import com.google.gson.Gson;
import com.runie.state.RunieStateStore;
import com.runie.state.TestStateStores;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Monotonic watermark guard: system-clock rollback can never move Runie time backwards (§11). */
public class GuardedClockTest
{
	private ScheduledExecutorService executor;
	private RunieStateStore store;
	private GuardedClock clock;

	@Before
	public void setUp() throws IOException
	{
		executor = Executors.newSingleThreadScheduledExecutor();
		store = TestStateStores.create(new Gson(), executor, null,
			Files.createTempDirectory("runie-clock-test"));
		store.switchAccount(1L);
		clock = new GuardedClock(store);
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	@Test
	public void normalOperationAdvancesWatermark()
	{
		long before = System.currentTimeMillis();
		long now = clock.nowMs();
		assertTrue(now >= before);
		assertTrue(store.getState().clockWatermarkMs >= before);
	}

	@Test
	public void rolledBackSystemClockIsClampedToWatermark()
	{
		// simulate a state persisted "in the future" (i.e. the user rolled the OS clock back)
		long futureWatermark = System.currentTimeMillis() + 6 * 3600_000L;
		store.getState().clockWatermarkMs = futureWatermark;

		assertEquals(futureWatermark, clock.nowMs()); // never earlier than the watermark
		assertEquals(futureWatermark, store.getState().clockWatermarkMs); // not regressed
	}

	@Test
	public void worksWithoutStateLoaded() throws IOException
	{
		RunieStateStore empty = TestStateStores.create(new Gson(), executor, null,
			Files.createTempDirectory("runie-clock-test2"));
		GuardedClock c = new GuardedClock(empty);
		assertTrue(c.nowMs() > 0);
	}
}
