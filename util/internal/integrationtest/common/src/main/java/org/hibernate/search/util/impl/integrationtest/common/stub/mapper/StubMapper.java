/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.util.impl.integrationtest.common.stub.mapper;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.hibernate.search.engine.backend.index.spi.IndexManager;
import org.hibernate.search.engine.mapper.mapping.building.spi.IndexManagerBuildingState;
import org.hibernate.search.engine.mapper.mapping.building.spi.Mapper;
import org.hibernate.search.engine.mapper.mapping.building.spi.TypeMetadataContributorProvider;
import org.hibernate.search.engine.mapper.model.spi.MappableTypeModel;

class StubMapper implements Mapper<StubMapping> {

	private final boolean multiTenancyEnabled;

	private final TypeMetadataContributorProvider<StubTypeMetadataContributor> contributorProvider;

	private final Map<StubTypeModel, IndexManagerBuildingState<?>> indexManagerBuildingStates = new HashMap<>();

	StubMapper(TypeMetadataContributorProvider<StubTypeMetadataContributor> contributorProvider, boolean multiTenancyEnabled) {
		this.contributorProvider = contributorProvider;
		this.multiTenancyEnabled = multiTenancyEnabled;
	}

	@Override
	public boolean isMultiTenancyEnabled() {
		return multiTenancyEnabled;
	}

	@Override
	public void addIndexed(MappableTypeModel typeModel, IndexManagerBuildingState<?> indexManagerBuildingState) {
		indexManagerBuildingStates.put( (StubTypeModel) typeModel, indexManagerBuildingState );
		contributorProvider.forEach( typeModel, c -> c.contribute( indexManagerBuildingState ) );
	}

	@Override
	public StubMapping build() {
		Map<String, String> normalizedIndexNamesByTypeIdentifier = indexManagerBuildingStates.entrySet().stream()
				.collect( Collectors.toMap( e -> e.getKey().asString(), e -> e.getValue().getIndexName() ) );
		Map<String, IndexManager<?>> indexManagersByTypeIdentifier = indexManagerBuildingStates.entrySet().stream()
				.collect( Collectors.toMap( e -> e.getKey().asString(), e -> e.getValue().build() ) );
		return new StubMapping( normalizedIndexNamesByTypeIdentifier, indexManagersByTypeIdentifier );
	}
}