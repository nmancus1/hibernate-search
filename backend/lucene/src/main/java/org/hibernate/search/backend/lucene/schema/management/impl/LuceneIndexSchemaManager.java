/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.lucene.schema.management.impl;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

import org.hibernate.search.backend.lucene.orchestration.impl.LuceneWriteWorkOrchestrator;
import org.hibernate.search.backend.lucene.work.impl.LuceneSchemaManagementWork;
import org.hibernate.search.backend.lucene.work.impl.LuceneWorkFactory;
import org.hibernate.search.engine.backend.schema.management.spi.IndexSchemaManager;
import org.hibernate.search.engine.reporting.spi.ContextualFailureCollector;

public class LuceneIndexSchemaManager implements IndexSchemaManager {

	private final LuceneWorkFactory luceneWorkFactory;
	private final SchemaManagementIndexManagerContext indexManagerContext;

	public LuceneIndexSchemaManager(LuceneWorkFactory luceneWorkFactory,
			SchemaManagementIndexManagerContext indexManagerContext) {
		this.luceneWorkFactory = luceneWorkFactory;
		this.indexManagerContext = indexManagerContext;
	}

	@Override
	public CompletableFuture<?> createIfMissing() {
		return doSubmit( luceneWorkFactory.createIndexIfMissing() );
	}

	@Override
	public CompletableFuture<?> createOrValidate(ContextualFailureCollector failureCollector) {
		// We don't perform any validation whatsoever.
		return createIfMissing();
	}

	@Override
	public CompletableFuture<?> createOrUpdate() {
		// We don't perform any update whatsoever.
		return createIfMissing();
	}

	@Override
	public CompletableFuture<?> dropIfExisting() {
		return doSubmit( luceneWorkFactory.dropIndexIfExisting() );
	}

	@Override
	public CompletableFuture<?> dropAndCreate() {
		return doSubmit( luceneWorkFactory.dropIndexIfExisting() )
				.thenCompose( ignored -> doSubmit( luceneWorkFactory.createIndexIfMissing() ) );
	}

	@Override
	public CompletableFuture<?> validate(ContextualFailureCollector failureCollector) {
		// We only check that the index exists, and we throw an exception if it doesn't.
		return doSubmit( luceneWorkFactory.validateIndexExists() );
	}

	private CompletableFuture<?> doSubmit(LuceneSchemaManagementWork<?> work) {
		Collection<LuceneWriteWorkOrchestrator> orchestrators = indexManagerContext.getAllWriteOrchestrators();
		CompletableFuture<?>[] futures = new CompletableFuture[orchestrators.size()];
		int i = 0;
		for ( LuceneWriteWorkOrchestrator orchestrator : orchestrators ) {
			futures[i] = orchestrator.submit( work );
			++i;
		}
		return CompletableFuture.allOf( futures );
	}
}
