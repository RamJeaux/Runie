package com.runie.core;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;

/** Production {@link AccountContext}: delegates to the injected RuneLite client. */
@Singleton
public class ClientAccountContext implements AccountContext
{
	private final Client client;

	@Inject
	public ClientAccountContext(Client client)
	{
		this.client = client;
	}

	@Override
	public long accountHash()
	{
		return client.getAccountHash();
	}
}
