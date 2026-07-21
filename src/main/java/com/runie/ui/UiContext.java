package com.runie.ui;

import com.runie.RunieConfig;
import com.runie.assets.ArtStyleService;
import com.runie.assets.AssetLoader;
import com.runie.core.GachaService;
import com.runie.core.ProgressionService;
import com.runie.core.EggService;
import com.runie.creatures.CreatureRegistry;
import com.runie.overlay.RuniePullOverlay;
import com.runie.state.RunieStateStore;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Shared wiring for the side-panel components (architecture §8): read-only
 * service views + the command runner that marshals mutations onto the client
 * thread ({@code clientThread::invokeLater} in production, direct in tests).
 */
public final class UiContext
{
	public final EggService eggService;
	public final GachaService gachaService;
	public final ProgressionService progressionService;
	public final CreatureRegistry registry;
	public final AssetLoader assetLoader;
	/** Central effective-style / kawaii-unlock rule (easter egg) — panels render through this. */
	public final ArtStyleService artStyleService;
	public final RunieStateStore stateStore;
	public final RunieConfig config;
	public final RuniePullOverlay pullOverlay;
	/** Runs a state-mutating command on the client thread. */
	public final Consumer<Runnable> commandRunner;
	/** Danger sensor for the §7.3 pull confirmation. */
	public final BooleanSupplier dangerSupplier;

	public UiContext(EggService eggService, GachaService gachaService,
		ProgressionService progressionService,
		CreatureRegistry registry, AssetLoader assetLoader,
		ArtStyleService artStyleService, RunieStateStore stateStore, RunieConfig config,
		RuniePullOverlay pullOverlay, Consumer<Runnable> commandRunner, BooleanSupplier dangerSupplier)
	{
		this.eggService = eggService;
		this.gachaService = gachaService;
		this.progressionService = progressionService;
		this.registry = registry;
		this.assetLoader = assetLoader;
		this.artStyleService = artStyleService;
		this.stateStore = stateStore;
		this.config = config;
		this.pullOverlay = pullOverlay;
		this.commandRunner = commandRunner != null ? commandRunner : Runnable::run;
		this.dangerSupplier = dangerSupplier != null ? dangerSupplier : () -> false;
	}
}
