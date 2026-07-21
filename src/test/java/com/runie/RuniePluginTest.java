package com.runie;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Dev-client launcher (not a unit test): run main() from the IDE with
 * {@code -ea -Xmx2g} to load Runie in a development RuneLite client.
 * Test XP paths on a throwaway account first (architecture §15).
 */
public class RuniePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RuniePlugin.class);
		RuneLite.main(args);
	}
}
