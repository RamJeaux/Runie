package com.runie.assets;

import com.runie.support.TestRunieConfig;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Kawaii easter-egg epic: unlock detection ("uwu" in public chat, exact trimmed
 * case-insensitive match), machine-global flag persistence, effective-style
 * enforcement (locked Kawaii resolves to Pixel everywhere), and idempotency.
 */
public class ArtStyleServiceTest
{
	/** Stands in for the hidden machine-global ConfigManager key. */
	private final Map<String, String> configStore = new HashMap<>();
	private TestRunieConfig config;
	private ArtStyleService service;

	@Before
	public void setUp()
	{
		configStore.clear();
		config = new TestRunieConfig();
		service = newService();
	}

	/** New service instance over the SAME persisted store (fresh session). */
	private ArtStyleService newService()
	{
		return new ArtStyleService(config,
			() -> configStore.get("kawaiiUnlocked"),
			v -> configStore.put("kawaiiUnlocked", v));
	}

	private static ChatMessage chat(ChatMessageType type, String message)
	{
		return new ChatMessage(null, type, "Zezima", message, null, 0);
	}

	private boolean say(String message)
	{
		return service.handleChatMessage(chat(ChatMessageType.PUBLICCHAT, message));
	}

	// ------------------------------------------------------------------
	// Unlock detection
	// ------------------------------------------------------------------

	@Test
	public void uwuInPublicChatUnlocks()
	{
		assertFalse(service.isKawaiiUnlocked());
		assertTrue(say("uwu"));
		assertTrue(service.isKawaiiUnlocked());
		assertEquals("true", configStore.get("kawaiiUnlocked"));
	}

	@Test
	public void detectionIsCaseInsensitive()
	{
		for (String magic : new String[]{"UWU", "UwU", "uWu"})
		{
			setUp(); // fresh service + store per variant
			assertTrue(magic, say(magic));
			assertTrue(magic, service.isKawaiiUnlocked());
		}
	}

	@Test
	public void surroundingWhitespaceIsTrimmed()
	{
		assertTrue(say(" uwu "));
		assertTrue(service.isKawaiiUnlocked());
	}

	@Test
	public void nearMissesDoNotUnlock()
	{
		for (String miss : new String[]{"uwuu", "buwu", "owo", "uwu!", "u w u", "uwu uwu", "say uwu", ""})
		{
			assertFalse(miss, say(miss));
		}
		assertFalse(service.isKawaiiUnlocked());
		assertFalse(configStore.containsKey("kawaiiUnlocked"));
	}

	@Test
	public void nullMessageIsIgnored()
	{
		assertFalse(say(null));
		assertFalse(service.handleChatMessage(null));
		assertFalse(service.isKawaiiUnlocked());
	}

	@Test
	public void nonPublicChatTypesAreIgnored()
	{
		ChatMessageType[] ignored = {
			ChatMessageType.GAMEMESSAGE, ChatMessageType.PRIVATECHAT, ChatMessageType.PRIVATECHATOUT,
			ChatMessageType.FRIENDSCHAT, ChatMessageType.CLAN_CHAT, ChatMessageType.SPAM,
			ChatMessageType.NPC_EXAMINE, ChatMessageType.CONSOLE,
		};
		for (ChatMessageType type : ignored)
		{
			assertFalse(type.name(), service.handleChatMessage(chat(type, "uwu")));
		}
		assertFalse(service.isKawaiiUnlocked());
	}

	@Test
	public void modPublicChatCountsAsPlayerChat()
	{
		assertTrue(service.handleChatMessage(chat(ChatMessageType.MODCHAT, "uwu")));
		assertTrue(service.isKawaiiUnlocked());
	}

	// ------------------------------------------------------------------
	// Idempotency + persistence
	// ------------------------------------------------------------------

	@Test
	public void reTriggerAfterUnlockIsNoOp()
	{
		assertTrue(say("uwu"));
		assertFalse(say("uwu"));   // no second celebration
		assertFalse(say("UWU"));
		assertTrue(service.isKawaiiUnlocked());
		assertEquals("true", configStore.get("kawaiiUnlocked"));
	}

	@Test
	public void unlockPersistsAcrossSessions()
	{
		assertTrue(say("uwu"));
		// fresh service over the same persisted store = client restart /
		// account switch (flag is machine-global, not per-account JSON)
		ArtStyleService next = newService();
		assertTrue(next.isKawaiiUnlocked());
		assertFalse(next.handleChatMessage(chat(ChatMessageType.PUBLICCHAT, "uwu"))); // still idempotent
	}

	@Test
	public void lockedByDefaultOnFreshStore()
	{
		assertFalse(service.isKawaiiUnlocked());
		assertFalse(Boolean.parseBoolean(configStore.get("kawaiiUnlocked")));
	}

	// ------------------------------------------------------------------
	// Effective-style enforcement
	// ------------------------------------------------------------------

	@Test
	public void kawaiiSelectedWhileLockedResolvesToPixel()
	{
		config.artStyle = ArtStyle.KAWAII;
		assertEquals(ArtStyle.PIXEL, service.effectiveStyle());
	}

	@Test
	public void pixelSelectionIsAlwaysAvailable()
	{
		config.artStyle = ArtStyle.PIXEL;
		assertEquals(ArtStyle.PIXEL, service.effectiveStyle());
		say("uwu");
		assertEquals(ArtStyle.PIXEL, service.effectiveStyle()); // unlock never forces a switch
	}

	@Test
	public void kawaiiWorksFullyOnceUnlocked()
	{
		config.artStyle = ArtStyle.KAWAII;
		assertEquals(ArtStyle.PIXEL, service.effectiveStyle());
		assertTrue(say("uwu"));
		assertEquals(ArtStyle.KAWAII, service.effectiveStyle()); // flips immediately, same session
	}

	@Test
	public void unlockPersistedFromPriorSessionEnablesKawaii()
	{
		configStore.put("kawaiiUnlocked", "true");
		config.artStyle = ArtStyle.KAWAII;
		assertEquals(ArtStyle.KAWAII, newService().effectiveStyle());
	}

	// ------------------------------------------------------------------
	// Selection gating — Kawaii is UNAVAILABLE until unlocked (Bug 3)
	// ------------------------------------------------------------------

	/** Service wired with a live artStyle writer, like production ConfigManager. */
	private ArtStyleService newGatingService()
	{
		return new ArtStyleService(config,
			() -> configStore.get("kawaiiUnlocked"),
			v -> configStore.put("kawaiiUnlocked", v),
			styleName -> config.artStyle = ArtStyle.valueOf(styleName));
	}

	@Test
	public void pickingKawaiiWhileLockedSnapsBackToPixel()
	{
		ArtStyleService gated = newGatingService();
		config.artStyle = ArtStyle.KAWAII;        // player picks the locked option
		assertTrue(gated.enforceKawaiiLock());    // plugin reacts to the config change
		assertEquals(ArtStyle.PIXEL, config.artStyle());
		assertEquals(ArtStyle.PIXEL, gated.effectiveStyle());
	}

	@Test
	public void legacyStoredKawaiiWhileLockedFallsBackToPixelAtLoad()
	{
		// old install persisted Kawaii before the lock existed; startUp() enforces
		config.artStyle = ArtStyle.KAWAII;
		ArtStyleService fresh = newGatingService();
		assertFalse(fresh.isKawaiiUnlocked());
		assertTrue(fresh.enforceKawaiiLock());
		assertEquals(ArtStyle.PIXEL, config.artStyle());
	}

	@Test
	public void enforceKeepsKawaiiOnceUnlocked()
	{
		ArtStyleService gated = newGatingService();
		config.artStyle = ArtStyle.KAWAII;
		assertTrue(gated.handleChatMessage(chat(ChatMessageType.PUBLICCHAT, "uwu")));
		assertFalse(gated.enforceKawaiiLock());   // unlocked: Kawaii is selectable
		assertEquals(ArtStyle.KAWAII, config.artStyle());
		assertEquals(ArtStyle.KAWAII, gated.effectiveStyle());
	}

	@Test
	public void enforceIsANoOpForPixelSelection()
	{
		ArtStyleService gated = newGatingService();
		config.artStyle = ArtStyle.PIXEL;
		assertFalse(gated.enforceKawaiiLock());
		assertEquals(ArtStyle.PIXEL, config.artStyle());
	}

	@Test
	public void unlockThenPickKawaiiStaysAcrossSessions()
	{
		ArtStyleService gated = newGatingService();
		assertTrue(gated.handleChatMessage(chat(ChatMessageType.PUBLICCHAT, "uwu")));
		config.artStyle = ArtStyle.KAWAII;
		// fresh session over the same persisted flag: still selectable
		assertFalse(newGatingService().enforceKawaiiLock());
		assertEquals(ArtStyle.KAWAII, config.artStyle());
	}
}
