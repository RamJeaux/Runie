package com.runie;

import com.runie.assets.ArtStyle;
import com.runie.config.AnimationIntensity;
import com.runie.config.CompanionPosition;
import com.runie.config.CompanionScale;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * Lightweight user settings ONLY (architecture §2). Progression/economy state
 * lives in the versioned JSON store, never here. Overlay free-drag positions are
 * persisted by OverlayManager; {@link #companionPosition()} only seeds the default.
 */
@ConfigGroup(RunieConfig.GROUP)
public interface RunieConfig extends Config
{
	String GROUP = "runie";

	@ConfigItem(
		keyName = "artStyle",
		name = "Art style",
		description = "Which art pack to use for your companions."
			+ " Psst — one style is a secret until you earn it in-game;"
			+ " picking it early snaps back to Pixel...",
		position = 0
	)
	default ArtStyle artStyle()
	{
		return ArtStyle.PIXEL;
	}

	@ConfigItem(
		keyName = "showCompanion",
		name = "Show companion",
		description = "Show your active companion on screen",
		position = 1
	)
	default boolean showCompanion()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNameplate",
		name = "Show name & level",
		description = "Show a small name/level chip under the companion",
		position = 2
	)
	default boolean showNameplate()
	{
		return true;
	}

	@ConfigItem(
		keyName = "companionPosition",
		name = "Default position",
		description = "Initial screen corner for the companion overlay (drag freely afterwards)",
		position = 3
	)
	default CompanionPosition companionPosition()
	{
		return CompanionPosition.BOTTOM_RIGHT;
	}

	@ConfigItem(
		keyName = "companionScale",
		name = "Companion size",
		description = "Render size of the companion",
		position = 4
	)
	default CompanionScale companionScale()
	{
		return CompanionScale.MEDIUM;
	}

	@ConfigItem(
		keyName = "animationIntensity",
		name = "Animation intensity",
		description = "How lively the companion is (signature/blink frequency)",
		position = 5
	)
	default AnimationIntensity animationIntensity()
	{
		return AnimationIntensity.NORMAL;
	}

	@ConfigItem(
		keyName = "reducedMotion",
		name = "Reduced motion",
		description = "Static companion, no blinking or signature animations, simple Hatch reveals",
		position = 6
	)
	default boolean reducedMotion()
	{
		return false;
	}

	@ConfigItem(
		keyName = "confirmDangerPulls",
		name = "Confirm Hatches in danger",
		description = "Ask before Hatching while in combat or in dangerous areas",
		position = 7
	)
	default boolean confirmDangerPulls()
	{
		return true;
	}

	@ConfigItem(
		keyName = "warnNoActiveCreature",
		name = "Warn when no active companion",
		description = "Show a warning when XP is being earned with no active companion",
		position = 8
	)
	default boolean warnNoActiveCreature()
	{
		return true;
	}
}
