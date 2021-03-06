/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.integrationtest.backend.elasticsearch.schema.management;

import static org.hibernate.search.util.impl.integrationtest.backend.elasticsearch.ElasticsearchIndexMetadataTestUtils.defaultPrimaryName;
import static org.hibernate.search.util.impl.integrationtest.backend.elasticsearch.ElasticsearchIndexMetadataTestUtils.defaultReadAlias;
import static org.hibernate.search.util.impl.integrationtest.backend.elasticsearch.ElasticsearchIndexMetadataTestUtils.defaultWriteAlias;

import java.util.EnumSet;

import org.hibernate.search.backend.elasticsearch.analysis.ElasticsearchAnalysisConfigurationContext;
import org.hibernate.search.backend.elasticsearch.analysis.ElasticsearchAnalysisConfigurer;
import org.hibernate.search.backend.elasticsearch.cfg.ElasticsearchBackendSettings;
import org.hibernate.search.integrationtest.backend.tck.testsupport.util.rule.SearchSetupHelper;
import org.hibernate.search.util.common.SearchException;
import org.hibernate.search.util.common.impl.Futures;
import org.hibernate.search.util.impl.integrationtest.backend.elasticsearch.rule.TestElasticsearchClient;
import org.hibernate.search.util.impl.integrationtest.mapper.stub.StubMappingIndexManager;
import org.hibernate.search.util.impl.integrationtest.mapper.stub.StubMappingSchemaManagementStrategy;
import org.hibernate.search.util.impl.test.SubTest;
import org.hibernate.search.util.impl.test.annotation.TestForIssue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests related to aliases when inspecting existing indexes,
 * for all alias-inspecting schema management operations.
 */
@RunWith(Parameterized.class)
@TestForIssue(jiraKey = "HSEARCH-3791")
public class ElasticsearchIndexSchemaManagerInspectionAliasesIT {

	private static final String INDEX_NAME = "IndexName";

	@Parameters(name = "With operation {0}")
	public static EnumSet<ElasticsearchIndexSchemaManagerOperation> strategies() {
		return ElasticsearchIndexSchemaManagerOperation.aliasInspecting();
	}

	@Rule
	public SearchSetupHelper setupHelper = new SearchSetupHelper();

	@Rule
	public TestElasticsearchClient elasticsearchClient = new TestElasticsearchClient();

	private final ElasticsearchIndexSchemaManagerOperation operation;

	private StubMappingIndexManager indexManager;

	public ElasticsearchIndexSchemaManagerInspectionAliasesIT(ElasticsearchIndexSchemaManagerOperation operation) {
		this.operation = operation;
	}

	@Test
	public void writeAliasTargetsMultipleIndexes() {
		elasticsearchClient.index( INDEX_NAME )
				.deleteAndCreate();
		// The write alias for index 1 also targets a second index
		elasticsearchClient.index( "otherIndex" )
				.deleteAndCreate()
				.aliases().put( defaultWriteAlias( INDEX_NAME ).original );

		SubTest.expectException(
				this::setupAndInspectIndex
		)
				.assertThrown()
				.isInstanceOf( SearchException.class )
				.hasMessageContaining(
						"Index aliases [" + defaultWriteAlias( INDEX_NAME ) + ", " + defaultReadAlias( INDEX_NAME )
								+ "] are assigned to a single Hibernate Search index, "
								+ " but they are already defined in Elasticsearch and point to multiple distinct indexes: "
								+ "[" + defaultPrimaryName( INDEX_NAME ) + ", "
								+ defaultPrimaryName( "otherIndex" ) + "]"
				);
	}

	@Test
	public void readAliasTargetsMultipleIndexes() {
		elasticsearchClient.index( INDEX_NAME )
				.deleteAndCreate();
		// The read alias for index 1 also targets a second index
		elasticsearchClient.index( "otherIndex" )
				.deleteAndCreate()
				.aliases().put( defaultReadAlias( INDEX_NAME ).original );

		SubTest.expectException(
				this::setupAndInspectIndex
		)
				.assertThrown()
				.isInstanceOf( SearchException.class )
				.hasMessageContaining(
							"Index aliases [" + defaultWriteAlias( INDEX_NAME ) + ", " + defaultReadAlias( INDEX_NAME )
									+ "] are assigned to a single Hibernate Search index, "
									+ " but they are already defined in Elasticsearch and point to multiple distinct indexes: "
									+ "[" + defaultPrimaryName( INDEX_NAME ) + ", "
									+ defaultPrimaryName( "otherIndex" ) + "]"
				);
	}

	private void setupAndInspectIndex() {
		setupHelper.start()
				.withSchemaManagement( StubMappingSchemaManagementStrategy.DROP_ON_SHUTDOWN_ONLY )
				.withBackendProperty(
						// Don't contribute any analysis definitions, migration of those is tested in another test class
						ElasticsearchBackendSettings.ANALYSIS_CONFIGURER,
						(ElasticsearchAnalysisConfigurer) (ElasticsearchAnalysisConfigurationContext context) -> {
							// No-op
						}
				)
				.withIndex( INDEX_NAME, ctx -> { }, indexManager -> this.indexManager = indexManager )
				.setup();

		Futures.unwrappedExceptionJoin( operation.apply( indexManager.getSchemaManager() ) );
	}

}
