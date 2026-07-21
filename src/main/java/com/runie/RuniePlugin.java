package com.runie;

import com.google.inject.Binder;
import com.google.inject.Provides;
import com.runie.anim.AnimationService;
import com.runie.assets.ArtStyleService;
import com.runie.assets.AssetLoader;
import com.runie.core.AccountContext;
import com.runie.core.ClientAccountContext;
import com.runie.core.GachaService;
import com.runie.core.GuardedClock;
import com.runie.core.ProgressionService;
import com.runie.core.RunieClock;
import com.runie.core.RunieRandom;
import com.runie.core.EggService;
import com.runie.core.XpDeltaService;
import com.runie.creatures.CreatureRegistry;
import com.runie.overlay.RunieCompanionOverlay;
import com.runie.overlay.RuniePullOverlay;
import com.runie.state.RunieStateStore;
import com.runie.ui.RuniePanel;
import com.runie.ui.UiContext;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.Notifier;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Runie — cozy, local-first creature-collector companion.
 *
 * <p>Thin conductor (architecture §2): lifecycle + event routing only, owns no
 * business logic. Epics 3–5 wired the XP-snapshot-safe delta pipeline, token
 * economy, Summon/progression/conversion services and the state store; Epics
 * 6–7 attach the companion + pull overlays, the animation engine, and the side
 * panel here.
 *
 * <p>Every event handler is guarded: an escaped exception would disable the
 * whole plugin (§14), and losing one event must only ever under-grant.
 */
@Slf4j
@PluginDescriptor(
	name = "Runie",
	description = "A cozy, local-first creature-collector companion. Earn Hatches by playing, no advantage, no network.",
	tags = {"companion", "pets", "collection", "cosmetic", "fun"}
)
public class RuniePlugin extends Plugin
{
	/** Recent-hitsplat window for the §7.3 danger check. */
	private static final long COMBAT_RECENT_MS = 10_000;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private RunieConfig config;

	@Inject
	private XpDeltaService xpDeltaService;

	@Inject
	private EggService eggService;

	@Inject
	private ProgressionService progressionService;

	@Inject
	private GachaService gachaService;

	@Inject
	private RunieStateStore stateStore;

	@Inject
	private CreatureRegistry creatureRegistry;

	@Inject
	private AssetLoader assetLoader;

	@Inject
	private ArtStyleService artStyleService;

	@Inject
	private AnimationService animationService;

	@Inject
	private Notifier notifier;

	@Inject
	private RunieCompanionOverlay companionOverlay;

	@Inject
	private RuniePullOverlay pullOverlay;

	@Inject
	private RunieClock clock;

	private RuniePanel panel;
	private NavigationButton navButton;
	private XpDeltaService.XpDeltaListener xpPanelSyncListener;
	private GachaService.PullListener panelPullListener;
	private ProgressionService.ProgressionListener progressionListener;
	private RunieStateStore.StateLoadListener stateLoadListener;

	private volatile long lastCombatMs = Long.MIN_VALUE;
	/** Danger flag, refreshed on the client thread each tick (§7.3). */
	private volatile boolean dangerNow;

	@Override
	public void configure(Binder binder)
	{
		// Plugin-local bindings (RuneLite's provided Gson/Guice — no new deps).
		binder.bind(RunieClock.class).to(GuardedClock.class);
		binder.bind(AccountContext.class).to(ClientAccountContext.class);
		binder.bind(RunieRandom.class).toInstance(RunieRandom.secure());
	}

	@Provides
	RunieConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RunieConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		// Order per architecture: registry → state/pipeline → overlays → panel.
		creatureRegistry.load();

		// Locked-Kawaii safety: a stored Kawaii selection while the easter egg is
		// still locked (legacy state) snaps back to Pixel before any art loads.
		artStyleService.enforceKawaiiLock();

		// XP pipeline fan-out: every validated player-XP delta feeds the egg
		// economy AND mirrors 1:1 onto the active creature (Epic 4).
		xpDeltaService.addListener(eggService);
		xpDeltaService.addListener(progressionService);

		// Epic 6: overlays + animation. The companion overlay's default corner
		// is seeded from config; free drag/persistence stays with OverlayManager.
		companionOverlay.setPosition(mapPosition());
		pullOverlay.setDangerSupplier(() -> dangerNow);
		overlayManager.add(companionOverlay);
		overlayManager.add(pullOverlay);
		gachaService.addListener(pullOverlay);

		// Epic 7: side panel. Commands marshal onto the client thread; UI
		// refresh marshals onto the EDT (§2 rule).
		UiContext uiContext = new UiContext(eggService, gachaService,
			progressionService, creatureRegistry,
			assetLoader, artStyleService, stateStore, config, pullOverlay,
			clientThread::invokeLater, () -> dangerNow);
		panel = new RuniePanel(uiContext);

		BufferedImage icon = assetLoader.getThumbnail("grubnak", artStyleService.effectiveStyle(), 1, 24);
		navButton = NavigationButton.builder()
			.tooltip("Runie")
			.icon(icon != null ? icon : new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB))
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Same-delta panel sync (bug fix: "+1 Egg float now, panel minutes
		// later"). EggService and ProgressionService were registered FIRST, so
		// by the time this listener fires the delta's egg credit and
		// level/stage/evolution checks are already applied — the panel re-reads
		// the exact post-delta state the companion toast's balance poll sees.
		// Without this, egg balance / banked-XP / Hatch availability only
		// refreshed on rare events (level-ups, hatches, region loads) and lagged
		// live accrual by minutes. refreshLater() coalesces bursts and marshals
		// onto the EDT, so this stays cheap and thread-correct per delta.
		xpPanelSyncListener = (skill, delta, newTotalXp) -> panel.refreshLater();
		xpDeltaService.addListener(xpPanelSyncListener);

		// listeners: engine events → animation refresh + panel repaint
		panelPullListener = result ->
		{
			animationService.refresh(); // first pull may auto-activate a companion
			panel.refreshLater();
		};
		gachaService.addListener(panelPullListener);
		progressionListener = new ProgressionService.ProgressionListener()
		{
			@Override
			public void onLevelUp(String creatureId, int oldLevel, int newLevel)
			{
				// grander GOLDEN burst at the p3→L120 mastery milestone (rework part 2)
				if (newLevel >= ProgressionService.PRESTIGE_LEVEL_CAP
					&& progressionService.getActiveCreatureId().map(creatureId::equals).orElse(false))
				{
					companionOverlay.playEvolutionBurst(true);
				}
				panel.refreshLater();
			}

			@Override
			public void onStageChange(String creatureId, int oldStage, int newStage)
			{
				animationService.refresh(); // evolution: new stage art
				// one-shot evolution burst over the companion (rework part 2)
				if (progressionService.getActiveCreatureId().map(creatureId::equals).orElse(false))
				{
					companionOverlay.playEvolutionBurst(false);
				}
				panel.refreshLater();
			}

			@Override
			public void onPrestige(String creatureId)
			{
				animationService.refresh(); // aura / stage look changed
				panel.refreshLater();
			}

			@Override
			public void onActiveChanged(String activeCreatureId)
			{
				animationService.refresh(); // deploy/undeploy shows IMMEDIATELY
				panel.refreshLater();
			}
		};
		progressionService.addListener(progressionListener);

		// Restore hook: the state file loads on the first attributed StatChanged
		// AFTER LOGGED_IN, so a login-time refresh alone races it and loses. When
		// the account state lands, re-deploy the persisted active companion.
		stateLoadListener = accountHash ->
		{
			animationService.refresh();
			if (panel != null)
			{
				panel.refreshLater();
			}
		};
		stateStore.addLoadListener(stateLoadListener);

		animationService.refresh();
		log.debug("Runie started: {} creature lines registered", creatureRegistry.size());
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(companionOverlay);
		overlayManager.remove(pullOverlay);
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (xpPanelSyncListener != null)
		{
			xpDeltaService.removeListener(xpPanelSyncListener);
			xpPanelSyncListener = null;
		}
		if (panelPullListener != null)
		{
			gachaService.removeListener(panelPullListener);
			panelPullListener = null;
		}
		gachaService.removeListener(pullOverlay);
		if (progressionListener != null)
		{
			progressionService.removeListener(progressionListener);
			progressionListener = null;
		}
		if (stateLoadListener != null)
		{
			stateStore.removeLoadListener(stateLoadListener);
			stateLoadListener = null;
		}
		panel = null;
		animationService.reset();
		assetLoader.invalidate();
		xpDeltaService.reset();
		stateStore.shutdownFlush();
		stateStore.reset();
		log.debug("Runie stopped: state flushed");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		try
		{
			GameState gs = event.getGameState();
			xpDeltaService.handleGameStateChanged(gs);
			if (gs == GameState.LOGGED_IN)
			{
				animationService.refresh(); // account switch may change companion
				if (panel != null)
				{
					panel.refreshLater();
				}
			}
			if (gs == GameState.LOGIN_SCREEN || gs == GameState.HOPPING)
			{
				stateStore.flushAsync(); // flush point (§4.4); I/O stays off the client thread
			}
		}
		catch (Exception e)
		{
			log.error("Runie: error handling GameStateChanged", e);
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		try
		{
			xpDeltaService.handleStatChanged(event);
		}
		catch (Exception e)
		{
			log.error("Runie: error handling StatChanged", e);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		try
		{
			xpDeltaService.handleGameTick();
			eggService.rolloverDayIfNeeded(); // cheap UTC day-boundary guard (§13)
			dangerNow = computeDanger();        // client-thread read, cached for EDT/overlay
		}
		catch (Exception e)
		{
			log.error("Runie: error handling GameTick", e);
		}
	}

	/**
	 * Kawaii easter egg (§epic): a public-chat "uwu" unlocks the hidden art
	 * style. Detection + persistence live in {@link ArtStyleService}; this
	 * handler only fires the one-time celebration and refreshes art (the player
	 * may already have Kawaii selected, silently resolving to Pixel until now).
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		try
		{
			if (!artStyleService.handleChatMessage(event))
			{
				return;
			}
			// one-time delight: game chat line + RuneLite notification
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", ArtStyleService.UNLOCK_MESSAGE, null);
			notifier.notify(ArtStyleService.UNLOCK_MESSAGE);
			animationService.refresh(); // effective style may flip Pixel → Kawaii right now
			if (panel != null)
			{
				panel.refreshLater();
			}
		}
		catch (Exception e)
		{
			log.error("Runie: error handling ChatMessage", e);
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		try
		{
			if (event.getActor() == client.getLocalPlayer())
			{
				lastCombatMs = clock.nowMs(); // §7.3 recent-combat window
			}
		}
		catch (Exception e)
		{
			log.error("Runie: error handling HitsplatApplied", e);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		try
		{
			if (!RunieConfig.GROUP.equals(event.getGroup()))
			{
				return;
			}
			if ("artStyle".equals(event.getKey()))
			{
				assetLoader.invalidate(); // style-pack swap: drop decoded frames (§5.2)
				if (artStyleService.enforceKawaiiLock())
				{
					// Kawaii is locked: the selection snapped back to Pixel in the
					// settings UI (the option is unavailable until earned); drop a
					// subtle in-game hint (client thread required for chat)
					clientThread.invokeLater(() ->
					{
						if (client.getGameState() == GameState.LOGGED_IN)
						{
							client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
								ArtStyleService.LOCKED_HINT, null);
						}
					});
				}
			}
			animationService.refresh();
			if (panel != null)
			{
				panel.refreshLater();
			}
		}
		catch (Exception e)
		{
			log.error("Runie: error handling ConfigChanged", e);
		}
	}

	/** §7.3 danger heuristic — MUST run on the client thread. */
	private boolean computeDanger()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		if (client.getVarbitValue(Varbits.IN_WILDERNESS) == 1)
		{
			return true;
		}
		if (withinCombatWindow(clock.nowMs(), lastCombatMs))
		{
			return true;
		}
		Player lp = client.getLocalPlayer();
		return lp != null && lp.getInteracting() != null;
	}

	/**
	 * Recent-combat window check, overflow-safe against the {@code Long.MIN_VALUE}
	 * "never hit" sentinel.
	 *
	 * <p><b>Bug fix (hatch reveal only showing after logout):</b> the old check
	 * was {@code nowMs - lastCombatMs < COMBAT_RECENT_MS}. With the sentinel
	 * {@code lastCombatMs == Long.MIN_VALUE}, the subtraction overflows to a
	 * NEGATIVE value, so the check was TRUE from plugin start until the first
	 * hitsplat ever landed on the player. That kept {@code dangerNow} stuck true,
	 * and RuniePullOverlay's §7.3 in-danger deferral re-anchored the reveal every
	 * frame — the hatch cinematic never started while logged in and could only
	 * slip out around the logout transition, when {@code computeDanger()} finally
	 * reported false (gameState no longer LOGGED_IN). Guarding the sentinel makes
	 * the reveal play immediately on hatch. Pure function; no client access.
	 */
	static boolean withinCombatWindow(long nowMs, long lastCombatMs)
	{
		return lastCombatMs != Long.MIN_VALUE && nowMs - lastCombatMs < COMBAT_RECENT_MS;
	}

	private OverlayPosition mapPosition()
	{
		switch (config.companionPosition())
		{
			case BOTTOM_LEFT:
				return OverlayPosition.BOTTOM_LEFT;
			case TOP_RIGHT:
				return OverlayPosition.TOP_RIGHT;
			case TOP_LEFT:
				return OverlayPosition.TOP_LEFT;
			case BOTTOM_RIGHT:
			default:
				return OverlayPosition.BOTTOM_RIGHT;
		}
	}
}
