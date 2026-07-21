package com.runie.support;

import com.runie.RunieConfig;
import com.runie.assets.ArtStyle;
import com.runie.config.AnimationIntensity;
import com.runie.config.CompanionScale;

/** Mutable RunieConfig for animation/overlay tests and headless mockups. */
public class TestRunieConfig implements RunieConfig
{
	public ArtStyle artStyle = ArtStyle.KAWAII;
	public boolean reducedMotion = false;
	public AnimationIntensity intensity = AnimationIntensity.NORMAL;
	public CompanionScale scale = CompanionScale.MEDIUM;
	public boolean showCompanion = true;
	public boolean showNameplate = true;
	public boolean confirmDangerPulls = true;

	@Override
	public ArtStyle artStyle()
	{
		return artStyle;
	}

	@Override
	public boolean reducedMotion()
	{
		return reducedMotion;
	}

	@Override
	public AnimationIntensity animationIntensity()
	{
		return intensity;
	}

	@Override
	public CompanionScale companionScale()
	{
		return scale;
	}

	@Override
	public boolean showCompanion()
	{
		return showCompanion;
	}

	@Override
	public boolean showNameplate()
	{
		return showNameplate;
	}

	@Override
	public boolean confirmDangerPulls()
	{
		return confirmDangerPulls;
	}
}
