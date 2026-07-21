package com.runie.assets;

import com.runie.RunieConfig;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;

/**
 * THE single place the art-style rules live (easter-egg epic).
 *
 * <p>{@link ArtStyle#PIXEL} is the always-available default. {@link ArtStyle#KAWAII}
 * is a hidden easter-egg unlock: it stays LOCKED until the player says
 * {@code "uwu"} in public chat (case-insensitive, exact trimmed match — "uwuu"
 * does not count). Every art consumer (AnimationService, RuniePullOverlay, the
 * panel, the nav icon) resolves its style through {@link #effectiveStyle()}, so
 * selecting Kawaii while locked silently renders Pixel and nothing else in the
 * art pipeline needs to know about locks.
 *
 * <p><b>Persistence:</b> the unlock is a hidden, machine-global
 * {@link ConfigManager} key ({@code runie.kawaiiUnlocked} — no {@code @ConfigItem},
 * so it never appears in the config panel). It deliberately does NOT live in the
 * per-account JSON state: once earned, Kawaii stays unlocked across sessions and
 * account switches on this machine.
 *
 * <p><b>Threading:</b> {@link #handleChatMessage(ChatMessage)} runs on the client
 * thread (event handler) and is a couple of string compares in the common case;
 * the one-time ConfigManager write happens only at the unlock moment. The cached
 * flag is volatile so render/EDT readers see the unlock immediately.
 */
@Slf4j
@Singleton
public class ArtStyleService
{
	/** Hidden ConfigManager key (group {@link RunieConfig#GROUP}) — no @ConfigItem, never shown. */
	static final String UNLOCK_KEY = "kawaiiUnlocked";

	/** The visible art-style @ConfigItem key ({@link RunieConfig#artStyle()}). */
	static final String ART_STYLE_KEY = "artStyle";

	/** The magic word. Exact trimmed match, case-insensitive (§spec: no "uwuu" false positives). */
	static final String MAGIC_WORD = "uwu";

	/** One-time unlock celebration (game chat + RuneLite notification). */
	public static final String UNLOCK_MESSAGE =
		"(◕‿◕) Kawaii style unlocked — pick it in Runie ▸ Art style!";

	/** Subtle nudge shown if Kawaii is selected while still locked. */
	public static final String LOCKED_HINT =
		"Runie: that art style is still a secret... they say the cutest word in chat reveals it.";

	private final RunieConfig config;
	private final Supplier<String> unlockReader;
	private final Consumer<String> unlockWriter;
	/** Writes the visible artStyle config selection (enum name) — lock enforcement. */
	private final Consumer<String> styleWriter;

	/** Lazily-loaded cache of the persisted flag; volatile for render/EDT readers. */
	private volatile Boolean unlockedCache;

	@Inject
	public ArtStyleService(RunieConfig config, ConfigManager configManager)
	{
		this(config,
			() -> configManager.getConfiguration(RunieConfig.GROUP, UNLOCK_KEY),
			value -> configManager.setConfiguration(RunieConfig.GROUP, UNLOCK_KEY, value),
			styleName -> configManager.setConfiguration(RunieConfig.GROUP, ART_STYLE_KEY, styleName));
	}

	/** Test seam: the persisted flag behind plain reader/writer functions (no style writeback). */
	public ArtStyleService(RunieConfig config, Supplier<String> unlockReader, Consumer<String> unlockWriter)
	{
		this(config, unlockReader, unlockWriter, styleName -> { });
	}

	/** Test seam incl. the artStyle-selection writer used by {@link #enforceKawaiiLock()}. */
	public ArtStyleService(RunieConfig config, Supplier<String> unlockReader, Consumer<String> unlockWriter,
		Consumer<String> styleWriter)
	{
		this.config = config;
		this.unlockReader = unlockReader;
		this.unlockWriter = unlockWriter;
		this.styleWriter = styleWriter;
	}

	/** True once the Kawaii easter egg has been earned (persisted, machine-global). */
	public boolean isKawaiiUnlocked()
	{
		Boolean cached = unlockedCache;
		if (cached == null)
		{
			cached = Boolean.parseBoolean(unlockReader.get());
			unlockedCache = cached;
		}
		return cached;
	}

	/**
	 * The style every art consumer must render: the config selection, EXCEPT
	 * Kawaii-while-locked resolves to the always-available Pixel default.
	 */
	public ArtStyle effectiveStyle()
	{
		ArtStyle selected = config.artStyle();
		if (selected == ArtStyle.KAWAII && !isKawaiiUnlocked())
		{
			return ArtStyle.PIXEL;
		}
		return selected;
	}

	/**
	 * Kawaii is UNAVAILABLE until the easter egg is earned: if the stored
	 * artStyle selection is Kawaii while still locked (a locked pick in the
	 * settings dropdown, or a legacy selection from before the lock existed),
	 * snap the config selection back to the always-available Pixel default. The
	 * plugin calls this at startup (a locked player never keeps a Kawaii
	 * selection) and on every artStyle config change (picking Kawaii while
	 * locked immediately reverts in the settings UI). Once unlocked this is a
	 * permanent no-op.
	 *
	 * @return true when a locked Kawaii selection was reverted to Pixel (the
	 * plugin shows the {@link #LOCKED_HINT})
	 */
	public boolean enforceKawaiiLock()
	{
		if (config.artStyle() != ArtStyle.KAWAII || isKawaiiUnlocked())
		{
			return false;
		}
		styleWriter.accept(ArtStyle.PIXEL.name());
		log.debug("Runie: kawaii art style is locked — selection reverted to Pixel");
		return true;
	}

	/**
	 * Easter-egg detector, fed every {@link ChatMessage} by the plugin.
	 * Unlocks on a public/player chat line whose trimmed text is exactly
	 * {@code "uwu"} (case-insensitive).
	 *
	 * @return true only when THIS message caused a brand-new unlock (the plugin
	 * fires the one-time celebration); false for non-matches and for re-triggers
	 * once already unlocked (idempotent).
	 */
	public boolean handleChatMessage(ChatMessage event)
	{
		if (event == null || !isPlayerChat(event.getType()))
		{
			return false;
		}
		String text = event.getMessage();
		if (text == null || !text.trim().equalsIgnoreCase(MAGIC_WORD))
		{
			return false;
		}
		if (isKawaiiUnlocked())
		{
			return false; // idempotent: typing it again is a no-op
		}
		unlockWriter.accept("true");
		unlockedCache = true;
		log.debug("Runie: kawaii art style unlocked");
		return true;
	}

	/** Public/player chat lines only — system, private, friends/clan chat never trigger. */
	private static boolean isPlayerChat(ChatMessageType type)
	{
		return type == ChatMessageType.PUBLICCHAT || type == ChatMessageType.MODCHAT;
	}
}
