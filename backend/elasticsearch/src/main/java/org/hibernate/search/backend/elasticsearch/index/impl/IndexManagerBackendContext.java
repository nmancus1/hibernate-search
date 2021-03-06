/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.elasticsearch.index.impl;

import org.hibernate.search.backend.elasticsearch.schema.management.impl.ElasticsearchIndexLifecycleExecutionOptions;
import org.hibernate.search.backend.elasticsearch.index.layout.IndexLayoutStrategy;
import org.hibernate.search.backend.elasticsearch.document.model.impl.ElasticsearchIndexModel;
import org.hibernate.search.backend.elasticsearch.document.model.lowlevel.impl.LowLevelIndexMetadataBuilder;
import org.hibernate.search.backend.elasticsearch.schema.management.impl.ElasticsearchIndexSchemaManager;
import org.hibernate.search.backend.elasticsearch.link.impl.ElasticsearchLink;
import org.hibernate.search.backend.elasticsearch.lowlevel.index.impl.IndexMetadata;
import org.hibernate.search.backend.elasticsearch.mapping.impl.TypeNameMapping;
import org.hibernate.search.backend.elasticsearch.multitenancy.impl.MultiTenancyStrategy;
import org.hibernate.search.backend.elasticsearch.orchestration.impl.ElasticsearchWorkOrchestrator;
import org.hibernate.search.backend.elasticsearch.orchestration.impl.ElasticsearchWorkOrchestratorImplementor;
import org.hibernate.search.backend.elasticsearch.orchestration.impl.ElasticsearchWorkOrchestratorProvider;
import org.hibernate.search.backend.elasticsearch.scope.model.impl.ElasticsearchScopeModel;
import org.hibernate.search.backend.elasticsearch.search.impl.ElasticsearchSearchContext;
import org.hibernate.search.backend.elasticsearch.search.projection.impl.ElasticsearchSearchProjection;
import org.hibernate.search.backend.elasticsearch.search.projection.impl.SearchProjectionBackendContext;
import org.hibernate.search.backend.elasticsearch.search.query.impl.ElasticsearchSearchQueryBuilder;
import org.hibernate.search.backend.elasticsearch.search.query.impl.SearchBackendContext;
import org.hibernate.search.backend.elasticsearch.work.execution.impl.ElasticsearchIndexIndexer;
import org.hibernate.search.backend.elasticsearch.work.execution.impl.ElasticsearchIndexIndexingPlan;
import org.hibernate.search.backend.elasticsearch.work.execution.impl.ElasticsearchIndexWorkspace;
import org.hibernate.search.backend.elasticsearch.work.execution.impl.WorkExecutionBackendContext;
import org.hibernate.search.backend.elasticsearch.work.execution.impl.WorkExecutionIndexManagerContext;
import org.hibernate.search.engine.backend.common.spi.EntityReferenceFactory;
import org.hibernate.search.engine.backend.mapping.spi.BackendMappingContext;
import org.hibernate.search.engine.backend.session.spi.BackendSessionContext;
import org.hibernate.search.engine.backend.session.spi.DetachedBackendSessionContext;
import org.hibernate.search.engine.backend.work.execution.DocumentRefreshStrategy;
import org.hibernate.search.engine.backend.work.execution.spi.IndexIndexer;
import org.hibernate.search.engine.backend.work.execution.spi.IndexIndexingPlan;
import org.hibernate.search.engine.backend.work.execution.spi.IndexWorkspace;
import org.hibernate.search.engine.search.loading.context.spi.LoadingContextBuilder;
import org.hibernate.search.util.common.reporting.EventContext;

import com.google.gson.Gson;

public class IndexManagerBackendContext implements SearchBackendContext, WorkExecutionBackendContext {

	private final EventContext eventContext;
	private final ElasticsearchLink link;
	private final Gson userFacingGson;
	private final MultiTenancyStrategy multiTenancyStrategy;
	private final IndexLayoutStrategy indexLayoutStrategy;
	private final ElasticsearchWorkOrchestratorProvider orchestratorProvider;
	private final ElasticsearchWorkOrchestrator queryOrchestrator;

	private final SearchProjectionBackendContext searchProjectionBackendContext;

	public IndexManagerBackendContext(EventContext eventContext, ElasticsearchLink link, Gson userFacingGson,
			MultiTenancyStrategy multiTenancyStrategy,
			IndexLayoutStrategy indexLayoutStrategy,
			TypeNameMapping typeNameMapping,
			ElasticsearchWorkOrchestratorProvider orchestratorProvider,
			ElasticsearchWorkOrchestrator queryOrchestrator) {
		this.eventContext = eventContext;
		this.link = link;
		this.userFacingGson = userFacingGson;
		this.multiTenancyStrategy = multiTenancyStrategy;
		this.indexLayoutStrategy = indexLayoutStrategy;
		this.orchestratorProvider = orchestratorProvider;
		this.queryOrchestrator = queryOrchestrator;

		this.searchProjectionBackendContext = new SearchProjectionBackendContext(
				typeNameMapping.getTypeNameExtractionHelper(),
				multiTenancyStrategy.getIdProjectionExtractionHelper()
		);
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "[" + eventContext + "]";
	}

	@Override
	public <R> IndexIndexingPlan<R> createIndexingPlan(
			ElasticsearchWorkOrchestrator orchestrator,
			WorkExecutionIndexManagerContext indexManagerContext,
			BackendSessionContext sessionContext, EntityReferenceFactory<R> entityReferenceFactory,
			DocumentRefreshStrategy refreshStrategy) {
		multiTenancyStrategy.checkTenantId( sessionContext.getTenantIdentifier(), eventContext );

		return new ElasticsearchIndexIndexingPlan<>(
				link.getWorkBuilderFactory(), orchestrator,
				indexManagerContext,
				sessionContext,
				entityReferenceFactory,
				refreshStrategy
		);
	}

	@Override
	public IndexIndexer createIndexer(
			ElasticsearchWorkOrchestrator orchestrator,
			WorkExecutionIndexManagerContext indexManagerContext,
			BackendSessionContext sessionContext) {
		multiTenancyStrategy.checkTenantId( sessionContext.getTenantIdentifier(), eventContext );

		return new ElasticsearchIndexIndexer( link.getWorkBuilderFactory(), orchestrator,
				indexManagerContext, sessionContext );
	}

	@Override
	public IndexWorkspace createWorkspace(ElasticsearchWorkOrchestrator orchestrator,
			WorkExecutionIndexManagerContext indexManagerContext,
			DetachedBackendSessionContext sessionContext) {
		multiTenancyStrategy.checkTenantId( sessionContext.getTenantIdentifier(), eventContext );

		return new ElasticsearchIndexWorkspace(
				link.getWorkBuilderFactory(), multiTenancyStrategy, orchestrator, indexManagerContext,
				sessionContext
		);
	}

	@Override
	public SearchProjectionBackendContext getSearchProjectionBackendContext() {
		return searchProjectionBackendContext;
	}

	@Override
	public ElasticsearchSearchContext createSearchContext(BackendMappingContext mappingContext,
			ElasticsearchScopeModel scopeModel) {
		return new ElasticsearchSearchContext(
				mappingContext,
				userFacingGson, link.getSearchSyntax(),
				multiTenancyStrategy,
				scopeModel
		);
	}

	@Override
	public <H> ElasticsearchSearchQueryBuilder<H> createSearchQueryBuilder(
			ElasticsearchSearchContext searchContext,
			BackendSessionContext sessionContext,
			LoadingContextBuilder<?, ?, ?> loadingContextBuilder,
			ElasticsearchSearchProjection<?, H> rootProjection) {
		multiTenancyStrategy.checkTenantId( sessionContext.getTenantIdentifier(), eventContext );
		return new ElasticsearchSearchQueryBuilder<>(
				link.getWorkBuilderFactory(), link.getSearchResultExtractorFactory(),
				queryOrchestrator,
				searchContext, sessionContext, loadingContextBuilder, rootProjection
		);
	}

	EventContext getEventContext() {
		return eventContext;
	}

	ElasticsearchIndexSchemaManager createSchemaManager(ElasticsearchIndexModel model,
			ElasticsearchIndexLifecycleExecutionOptions lifecycleExecutionOptions) {
		LowLevelIndexMetadataBuilder builder = new LowLevelIndexMetadataBuilder(
				link.getIndexMetadataSyntax(),
				model.getNames()
		);
		model.contributeLowLevelMetadata( builder );
		IndexMetadata expectedMetadata = builder.build();
		return new ElasticsearchIndexSchemaManager(
				link.getWorkBuilderFactory(), orchestratorProvider.getRootParallelOrchestrator(),
				indexLayoutStrategy, model.getNames(), expectedMetadata,
				lifecycleExecutionOptions
		);
	}

	ElasticsearchWorkOrchestratorImplementor createSerialOrchestrator(String indexName) {
		return orchestratorProvider.createSerialOrchestrator(
				"Elasticsearch serial work orchestrator for index " + indexName
		);
	}

	ElasticsearchWorkOrchestratorImplementor createParallelOrchestrator(String indexName) {
		return orchestratorProvider.createParallelOrchestrator(
				"Elasticsearch parallel work orchestrator for index " + indexName
		);
	}

	String toElasticsearchId(String tenantId, String id) {
		return multiTenancyStrategy.toElasticsearchId( tenantId, id );
	}
}
