/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.elasticsearch.orchestration.impl;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.hibernate.search.backend.elasticsearch.link.impl.ElasticsearchLink;
import org.hibernate.search.engine.environment.thread.spi.ThreadPoolProvider;
import org.hibernate.search.engine.reporting.FailureHandler;

/**
 * Provides access to various orchestrators.
 *
 * <h2>Orchestrator types</h2>
 *
 * Each orchestrator has its own characteristics and is suitable for different use cases.
 * We distinguish in particular between parallel orchestrators and serial orchestrators.
 *
 * <h3 id="parallel-orchestrators">Parallel orchestrators</h3>
 *
 * Parallel orchestrators execute worksets in no particular order.
 * <p>
 * They are suitable when the client takes the responsibility
 * of submitting works as needed to implement ordering: if work #2 must be executed after work #1,
 * the client will submit #1 and #2 in that order in the same workset,
 * or will take care to wait until #1 is done before he submits #2 in a different workset.
 * <p>
 * With a parallel orchestrator:
 * <ul>
 *     <li>Works submitted in the same workset will be executed in the given order.
 *     <li>Relative execution order between worksets is undefined.
 *     <li>Two works from the same workset may be sent together in a single bulk request,
 *     but only if all the works between them are bulked too.
 *     <li>Two works from different worksets may be sent together in a single bulk request.
 * </ul>
 * <p>
 * Parallel orchestrators from a single {@link ElasticsearchWorkOrchestratorProvider} (i.e. from a single backend)
 * rely on the same resources (same queue and consumer thread).
 *
 * <h3 id="serial-orchestrators">Serial orchestrators</h3>
 *
 * Serial orchestrators execute worksets in the order they were submitted.
 * <p>
 * They are best used as index-scoped orchestrators, when many worksets are submitted from different threads:
 * they allow to easily implement a reasonably safe (though imperfect) concurrency control by expecting
 * the most recent workset to hold the most recent data to be indexed.
 * <p>
 * With serial orchestrator:
 * <ul>
 *     <li>Works submitted in the same workset will be executed in the given order.
 *     <li>Worksets will be executed in the order they were submitted.
 *     <li>Two works from the same workset may be sent together in a single bulk request,
 *     but only if all the works between them are bulked too.
 *     <li>Two works from different worksets may be sent together in a single bulk request.
 * </ul>
 * <p>
 * Serial orchestrators from a single {@link ElasticsearchWorkOrchestratorProvider} (i.e. from a single backend)
 * rely on the separate resources (each has a dedicated queue and consumer thread).
 * <p>
 * Note that while serial orchestrators preserve ordering as best they can,
 * they lead to a lesser throughput and can only guarantee ordering within a single JVM.
 * When multiple JVMs with multiple instances of Hibernate Search target the same index
 */
public class ElasticsearchWorkOrchestratorProvider {

	private static final int SERIAL_MIN_BULK_SIZE = 2;
	/*
	 * For parallel orchestrators, we use a minimum bulk size of 1,
	 * and thus allow bulks with only one work.
	 * The reason is, for parallel orchestrators, we generally only submit single-work worksets,
	 * which means the decision on whether to bulk the work or not will always happen
	 * immediately after each work, when we only have one work to bulk.
	 * Thus if we set the minimum to a value higher than 1, we would always
	 * decide not to start a bulk (because there would always be only one
	 * work to bulk), which would result in terrible performance.
	 */
	private static final int PARALLEL_MIN_BULK_SIZE = 1;
	private static final int MAX_BULK_SIZE = 250;

	/*
	 * Setting the following constants involves a bit of guesswork.
	 * Basically we want the number to be large enough for the orchestrator
	 * to create bulks of the maximum size defined above most of the time,
	 * but we also want to keep the number as low as possible to avoid
	 * consuming too much memory with pending worksets.
	 * Here we set the number for parallel orchestrators higher than the number
	 * for serial orchestrators, because parallel orchestrators will generally only handle
	 * single-work worksets, and also because the parallel orchestrators rely on a single
	 * consumer thread shared between all index managers.
	 */
	private static final int SERIAL_MAX_WORKSETS_PER_BATCH = 10 * MAX_BULK_SIZE;
	private static final int PARALLEL_MAX_WORKSETS_PER_BATCH = 20 * MAX_BULK_SIZE;

	private final ElasticsearchLink link;
	private final ThreadPoolProvider threadPoolProvider;
	private final FailureHandler failureHandler;

	private final ElasticsearchBatchingWorkOrchestrator rootParallelOrchestrator;

	public ElasticsearchWorkOrchestratorProvider(String rootParallelOrchestratorName,
			ElasticsearchLink link,
			ThreadPoolProvider threadPoolProvider,
			FailureHandler failureHandler) {
		this.link = link;
		this.threadPoolProvider = threadPoolProvider;
		this.failureHandler = failureHandler;

		/*
		 * The following orchestrator doesn't require a strict execution ordering
		 * (because it's mainly used by the mass indexer, which already takes care of
		 * ordering works properly and waiting for pending works when necessary).
		 * Thus we use a parallel orchestrator to maximize throughput.
		 */
		this.rootParallelOrchestrator = createBatchingSharedOrchestrator(
				rootParallelOrchestratorName,
				createParallelWorkProcessor(),
				PARALLEL_MAX_WORKSETS_PER_BATCH,
				false // Do not care about ordering when queuing worksets
		);
	}

	public void start() {
		rootParallelOrchestrator.start();
	}

	public CompletableFuture<?> preStop() {
		return rootParallelOrchestrator.preStop();
	}

	public void stop() {
		rootParallelOrchestrator.stop();
	}

	/**
	 * @return The root parallel orchestrator. Useful to execute operations after an index manager was closed,
	 * such as index dropping.
	 */
	public ElasticsearchBatchingWorkOrchestrator getRootParallelOrchestrator() {
		return rootParallelOrchestrator;
	}

	/**
	 * @param name The name of the orchestrator to create.
	 * @return A <a href="#serial-orchestrators">serial orchestrator</a>.
	 */
	public ElasticsearchWorkOrchestratorImplementor createSerialOrchestrator(String name) {
		ElasticsearchWorkProcessor processor = createSerialWorkProcessor();

		return createBatchingSharedOrchestrator(
				name,
				processor,
				SERIAL_MAX_WORKSETS_PER_BATCH,
				true /* enqueue worksets in the exact order they were submitted */
		);
	}

	/**
	 * @param name The name of the orchestrator to create.
	 * @return A <a href="#parallel-orchestrators">parallel orchestrator</a>.
	 */
	public ElasticsearchWorkOrchestratorImplementor createParallelOrchestrator(String name) {
		return rootParallelOrchestrator.createChild( name );
	}

	private ElasticsearchBatchingWorkOrchestrator createBatchingSharedOrchestrator(
			String name, ElasticsearchWorkProcessor processor,
			int maxWorksetsPerBatch, boolean fair) {
		return new ElasticsearchBatchingWorkOrchestrator(
				name, processor, threadPoolProvider,
				maxWorksetsPerBatch, fair,
				failureHandler
		);
	}

	private ElasticsearchWorkProcessor createSerialWorkProcessor() {
		ElasticsearchWorkSequenceBuilder sequenceBuilder = createSequenceBuilder( this::createRefreshingWorkExecutionContext );
		ElasticsearchWorkBulker bulker = createBulker( sequenceBuilder, SERIAL_MIN_BULK_SIZE );
		return new ElasticsearchSerialWorkProcessor( sequenceBuilder, bulker );
	}

	private ElasticsearchWorkProcessor createParallelWorkProcessor() {
		ElasticsearchWorkSequenceBuilder sequenceBuilder = createSequenceBuilder( this::createRefreshingWorkExecutionContext );
		ElasticsearchWorkBulker bulker = createBulker( sequenceBuilder, PARALLEL_MIN_BULK_SIZE );
		return new ElasticsearchParallelWorkProcessor( sequenceBuilder, bulker );
	}

	private ElasticsearchWorkSequenceBuilder createSequenceBuilder(Supplier<ElasticsearchRefreshableWorkExecutionContext> contextSupplier) {
		return new ElasticsearchDefaultWorkSequenceBuilder(
				contextSupplier
		);
	}

	private ElasticsearchWorkBulker createBulker(ElasticsearchWorkSequenceBuilder sequenceBuilder, int minBulkSize) {
		return new ElasticsearchDefaultWorkBulker(
				sequenceBuilder,
				(worksToBulk, refreshStrategy) ->
						link.getWorkBuilderFactory().bulk( worksToBulk ).refresh( refreshStrategy ).build(),
				minBulkSize, MAX_BULK_SIZE
				);
	}

	private ElasticsearchRefreshableWorkExecutionContext createRefreshingWorkExecutionContext() {
		return new ElasticsearchDefaultWorkExecutionContext(
				link.getClient(), link.getGsonProvider(), link.getWorkBuilderFactory(), failureHandler
		);
	}

}
