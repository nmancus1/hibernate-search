/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.engine.cfg.impl;

import java.util.Optional;

import org.hibernate.search.engine.cfg.ConfigurationPropertySource;

public class FallbackConfigurationPropertySource implements ConfigurationPropertySource {
	private final ConfigurationPropertySource main;
	private final ConfigurationPropertySource fallback;

	public FallbackConfigurationPropertySource(ConfigurationPropertySource main, ConfigurationPropertySource fallback) {
		this.main = main;
		this.fallback = fallback;
	}

	@Override
	public Optional<?> get(String key) {
		Optional<?> value = main.get( key );
		if ( !value.isPresent() ) {
			return fallback.get( key );
		}
		else {
			return value;
		}
	}

}