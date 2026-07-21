package com.runie.assets;

/**
 * Art style packs. N-ary by construction (architecture §5.3): adding a new pack
 * (e.g. {@code KAWAII_PIXEL("kawaii_pixel", "Kawaii-Pixel")}) requires only a new
 * enum constant plus the matching {@code art/<folderToken>/...} resource trees —
 * asset paths are derived from {@link #getFolderToken()}, the config dropdown
 * auto-lists new values, and state stores no style info (presentation-only).
 */
public enum ArtStyle
{
	KAWAII("kawaii", "Kawaii"),
	PIXEL("pixel", "Pixel");

	private final String folderToken;
	private final String displayName;

	ArtStyle(String folderToken, String displayName)
	{
		this.folderToken = folderToken;
		this.displayName = displayName;
	}

	/** Lowercase resource-folder token used in asset paths (architecture §5.1). */
	public String getFolderToken()
	{
		return folderToken;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
