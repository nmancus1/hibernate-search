/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.integrationtest.backend.tck.search.query;

import static org.easymock.EasyMock.expect;
import static org.hibernate.search.util.impl.integrationtest.common.EasyMockUtils.projectionMatcher;
import static org.hibernate.search.util.impl.integrationtest.common.NormalizationUtils.reference;
import static org.hibernate.search.util.impl.integrationtest.common.assertion.SearchResultAssert.assertThat;
import static org.hibernate.search.util.impl.integrationtest.mapper.stub.StubMapperUtils.referenceProvider;
import static org.junit.Assert.assertEquals;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import org.hibernate.search.engine.backend.document.DocumentElement;
import org.hibernate.search.engine.backend.document.IndexFieldReference;
import org.hibernate.search.engine.backend.document.IndexObjectFieldReference;
import org.hibernate.search.engine.backend.document.model.dsl.IndexSchemaElement;
import org.hibernate.search.engine.backend.document.model.dsl.IndexSchemaObjectField;
import org.hibernate.search.engine.backend.document.model.dsl.ObjectFieldStorage;
import org.hibernate.search.engine.backend.types.Projectable;
import org.hibernate.search.engine.backend.work.execution.spi.IndexIndexingPlan;
import org.hibernate.search.engine.backend.common.DocumentReference;
import org.hibernate.search.engine.search.loading.context.spi.LoadingContext;
import org.hibernate.search.engine.search.loading.spi.DefaultProjectionHitMapper;
import org.hibernate.search.engine.search.loading.spi.EntityLoader;
import org.hibernate.search.engine.backend.common.spi.DocumentReferenceConverter;
import org.hibernate.search.engine.search.query.SearchQuery;
import org.hibernate.search.engine.spatial.GeoPoint;
import org.hibernate.search.integrationtest.backend.tck.testsupport.stub.StubDocumentReferenceConverter;
import org.hibernate.search.integrationtest.backend.tck.testsupport.stub.StubEntityLoader;
import org.hibernate.search.integrationtest.backend.tck.testsupport.stub.StubHitTransformer;
import org.hibernate.search.integrationtest.backend.tck.testsupport.stub.StubLoadedObject;
import org.hibernate.search.integrationtest.backend.tck.testsupport.stub.StubTransformedHit;
import org.hibernate.search.integrationtest.backend.tck.testsupport.stub.StubTransformedReference;
import org.hibernate.search.integrationtest.backend.tck.testsupport.configuration.DefaultAnalysisDefinitions;
import org.hibernate.search.integrationtest.backend.tck.testsupport.util.rule.SearchSetupHelper;
import org.hibernate.search.integrationtest.backend.tck.testsupport.stub.MapperEasyMockUtils;
import org.hibernate.search.util.impl.integrationtest.mapper.stub.GenericStubMappingScope;
import org.hibernate.search.util.impl.integrationtest.mapper.stub.StubMappingIndexManager;
import org.hibernate.search.util.impl.integrationtest.mapper.stub.StubMappingScope;
import org.hibernate.search.util.impl.test.annotation.TestForIssue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import org.easymock.EasyMockSupport;

public class SearchQueryResultLoadingOrTransformingIT extends EasyMockSupport {

	private static final String INDEX_NAME = "IndexName";

	private static final String MAIN_ID = "main";
	private static final String EMPTY_ID = "empty";

	private static final Integer INTEGER_VALUE = 42;
	private static final String STRING_VALUE = "string";
	private static final String STRING_ANALYZED_VALUE = "analyzed string";
	private static final LocalDate LOCAL_DATE_VALUE = LocalDate.of( 2018, 2, 1 );
	private static final GeoPoint GEO_POINT_VALUE = GeoPoint.of( 42.0, -42.0 );
	private static final Integer NESTED_OBJECT_INTEGER_VALUE = 142;
	private static final String NESTED_OBJECT_STRING_VALUE = "nested object string";
	private static final Integer FLATTENED_OBJECT_INTEGER_VALUE = 242;
	private static final String FLATTENED_OBJECT_STRING_VALUE = "flattened object string";

	@Rule
	public SearchSetupHelper setupHelper = new SearchSetupHelper();

	@Rule
	public ExpectedException thrown = ExpectedException.none();

	private IndexMapping indexMapping;
	private StubMappingIndexManager indexManager;

	@Before
	public void setup() {
		setupHelper.start()
				.withIndex(
						INDEX_NAME,
						ctx -> this.indexMapping = new IndexMapping( ctx.getSchemaElement() ),
						indexManager -> this.indexManager = indexManager
				)
				.setup();

		initData();
	}

	@Test
	@TestForIssue(jiraKey = "HSEARCH-3578")
	public void defaultResultType() {
		DocumentReference mainReference = reference( INDEX_NAME, MAIN_ID );
		DocumentReference emptyReference = reference( INDEX_NAME, EMPTY_ID );
		StubTransformedReference mainTransformedReference = new StubTransformedReference( mainReference );
		StubTransformedReference emptyTransformedReference = new StubTransformedReference( emptyReference );
		StubLoadedObject mainLoadedObject = new StubLoadedObject( mainReference );
		StubLoadedObject emptyLoadedObject = new StubLoadedObject( emptyReference );

		LoadingContext<StubTransformedReference, StubLoadedObject> loadingContextMock =
				createMock( LoadingContext.class );
		DocumentReferenceConverter<StubTransformedReference> documentReferenceConverterMock =
				createMock( StubDocumentReferenceConverter.class );
		EntityLoader<StubTransformedReference, StubLoadedObject> objectLoaderMock =
				createMock( StubEntityLoader.class );

		resetAll();
		// No calls expected on the mocks
		replayAll();
		GenericStubMappingScope<StubTransformedReference, StubLoadedObject> scope =
				indexManager.createGenericScope();
		SearchQuery<StubLoadedObject> objectsQuery = scope.query( loadingContextMock )
				.where( f -> f.matchAll() )
				.toQuery();
		verifyAll();

		resetAll();
		/*
		 * This will check in particular that the backend gets the projection hit mapper from the loading context,
		 * which must happen every time we execute the query,
		 * so that the mapper can run state checks (session is still open, ...).
		 */
		MapperEasyMockUtils.expectHitMapping(
				loadingContextMock, documentReferenceConverterMock, objectLoaderMock,
				c -> c
						.load( mainReference, mainTransformedReference, mainLoadedObject )
						.load( emptyReference, emptyTransformedReference, emptyLoadedObject )
		);
		replayAll();
		assertThat( objectsQuery ).hasHitsExactOrder( mainLoadedObject, emptyLoadedObject );
		verifyAll();
	}

	@Test
	public void selectEntityReference_noReferenceTransformer() {
		StubMappingScope scope = indexManager.createScope();

		SearchQuery<DocumentReference> query = scope.query()
				.selectEntityReference()
				.where( f -> f.matchAll() )
				.toQuery();
		assertThat( query )
				.hasDocRefHitsAnyOrder( INDEX_NAME, MAIN_ID, EMPTY_ID );
	}

	@Test
	public void selectEntity_noEntityLoading() {
		StubMappingScope scope = indexManager.createScope();

		SearchQuery<DocumentReference> query = scope.query()
				.selectEntity()
				.where( f -> f.matchAll() )
				.toQuery();
		assertThat( query )
				.hasDocRefHitsAnyOrder( INDEX_NAME, MAIN_ID, EMPTY_ID );
	}

	@Test
	public void selectEntityReference_referenceTransformer() {
		DocumentReference mainReference = reference( INDEX_NAME, MAIN_ID );
		DocumentReference emptyReference = reference( INDEX_NAME, EMPTY_ID );
		StubTransformedReference mainTransformedReference = new StubTransformedReference( mainReference );
		StubTransformedReference emptyTransformedReference = new StubTransformedReference( emptyReference );

		LoadingContext<StubTransformedReference, StubLoadedObject> loadingContextMock =
				createMock( LoadingContext.class );
		DocumentReferenceConverter<StubTransformedReference> documentReferenceConverterMock =
				createMock( StubDocumentReferenceConverter.class );
		EntityLoader<StubTransformedReference, StubLoadedObject> objectLoaderMock =
				createMock( StubEntityLoader.class );

		resetAll();
		// No calls expected on the mocks
		replayAll();
		GenericStubMappingScope<StubTransformedReference, StubLoadedObject> scope =
				indexManager.createGenericScope();
		SearchQuery<StubTransformedReference> referencesQuery = scope.query( loadingContextMock )
				.selectEntityReference()
				.where( f -> f.matchAll() )
				.toQuery();
		verifyAll();

		resetAll();
		/*
		 * This will check in particular that the backend gets the projection hit mapper from the loading context,
		 * which must happen every time we execute the query,
		 * so that the mapper can run state checks (session is still open, ...).
		 */
		MapperEasyMockUtils.expectHitMapping(
				loadingContextMock, documentReferenceConverterMock, objectLoaderMock,
				c -> c
						.entityReference( mainReference, mainTransformedReference )
						.entityReference( emptyReference, emptyTransformedReference )
		);
		replayAll();
		assertThat( referencesQuery ).hasHitsAnyOrder( mainTransformedReference, emptyTransformedReference );
		verifyAll();
	}

	@Test
	public void selectEntity_referencesTransformer_entityLoading() {
		DocumentReference mainReference = reference( INDEX_NAME, MAIN_ID );
		DocumentReference emptyReference = reference( INDEX_NAME, EMPTY_ID );
		StubTransformedReference mainTransformedReference = new StubTransformedReference( mainReference );
		StubTransformedReference emptyTransformedReference = new StubTransformedReference( emptyReference );
		StubLoadedObject mainLoadedObject = new StubLoadedObject( mainReference );
		StubLoadedObject emptyLoadedObject = new StubLoadedObject( emptyReference );

		LoadingContext<StubTransformedReference, StubLoadedObject> loadingContextMock =
				createMock( LoadingContext.class );
		DocumentReferenceConverter<StubTransformedReference> documentReferenceConverterMock =
				createMock( StubDocumentReferenceConverter.class );
		EntityLoader<StubTransformedReference, StubLoadedObject> objectLoaderMock =
				createMock( StubEntityLoader.class );

		resetAll();
		// No calls expected on the mocks
		replayAll();
		GenericStubMappingScope<StubTransformedReference, StubLoadedObject> scope =
				indexManager.createGenericScope();
		SearchQuery<StubLoadedObject> objectsQuery = scope.query( loadingContextMock )
				.selectEntity()
				.where( f -> f.matchAll() )
				.toQuery();
		verifyAll();

		resetAll();
		/*
		 * This will check in particular that the backend gets the projection hit mapper from the loading context,
		 * which must happen every time we execute the query,
		 * so that the mapper can run state checks (session is still open, ...).
		 */
		MapperEasyMockUtils.expectHitMapping(
				loadingContextMock, documentReferenceConverterMock, objectLoaderMock,
				c -> c
						.load( mainReference, mainTransformedReference, mainLoadedObject )
						.load( emptyReference, emptyTransformedReference, emptyLoadedObject )
		);
		replayAll();
		assertThat( objectsQuery ).hasHitsExactOrder( mainLoadedObject, emptyLoadedObject );
		verifyAll();
	}

	@Test
	public void select_referencesTransformer_entityLoading() {
		DocumentReference mainReference = reference( INDEX_NAME, MAIN_ID );
		DocumentReference emptyReference = reference( INDEX_NAME, EMPTY_ID );
		StubTransformedReference mainTransformedReference = new StubTransformedReference( mainReference );
		StubTransformedReference emptyTransformedReference = new StubTransformedReference( emptyReference );
		StubLoadedObject mainLoadedObject = new StubLoadedObject( mainReference );
		StubLoadedObject emptyLoadedObject = new StubLoadedObject( emptyReference );

		LoadingContext<StubTransformedReference, StubLoadedObject> loadingContextMock =
				createMock( LoadingContext.class );
		DocumentReferenceConverter<StubTransformedReference> documentReferenceConverterMock =
				createMock( StubDocumentReferenceConverter.class );
		EntityLoader<StubTransformedReference, StubLoadedObject> objectLoaderMock =
				createMock( StubEntityLoader.class );

		resetAll();
		// No calls expected on the mocks
		replayAll();
		GenericStubMappingScope<StubTransformedReference, StubLoadedObject> scope =
				indexManager.createGenericScope();
		SearchQuery<List<?>> projectionsQuery = scope.query( loadingContextMock )
				.select( f ->
						f.composite(
								f.field( "string", String.class ),
								f.documentReference(),
								f.entityReference(),
								f.entity()
						)
				)
				.where( f -> f.matchAll() )
				.toQuery();
		verifyAll();

		resetAll();
		/*
		 * This will check in particular that the backend gets the projection hit mapper from the loading context,
		 * which must happen every time we execute the query,
		 * so that the mapper can run state checks (session is still open, ...).
		 */
		MapperEasyMockUtils.expectHitMapping(
				loadingContextMock, documentReferenceConverterMock, objectLoaderMock,
				/*
				 * Expect each reference to be transformed because of the entity reference projection,
				 * but also loaded because of the entity projection.
				 */
				c -> c
						.entityReference( mainReference, mainTransformedReference )
						.load( mainReference, mainTransformedReference, mainLoadedObject )
						.entityReference( emptyReference, emptyTransformedReference )
						.load( emptyReference, emptyTransformedReference, emptyLoadedObject )
		);
		replayAll();
		assertThat( projectionsQuery ).hasListHitsAnyOrder( b -> {
			b.list( STRING_VALUE, mainReference, mainTransformedReference, mainLoadedObject );
			b.list( null, emptyReference, emptyTransformedReference, emptyLoadedObject );
		} );
		verifyAll();
	}

	@Test
	public void select_hitTransformer() {
		DocumentReference mainReference = reference( INDEX_NAME, MAIN_ID );
		DocumentReference emptyReference = reference( INDEX_NAME, EMPTY_ID );
		StubTransformedHit mainTransformedHit = new StubTransformedHit( mainReference );
		StubTransformedHit emptyTransformedHit = new StubTransformedHit( emptyReference );

		Function<List<?>, StubTransformedHit> hitTransformerMock = createMock( StubHitTransformer.class );

		resetAll();
		// No calls expected on the mocks
		replayAll();
		StubMappingScope scope = indexManager.createScope();
		SearchQuery<StubTransformedHit> query = scope.query()
				.select( f ->
						f.composite(
								hitTransformerMock,
								f.field( "string", String.class ).toProjection(),
								f.field( "string_analyzed", String.class ).toProjection(),
								f.field( "integer", Integer.class ).toProjection(),
								f.field( "localDate", LocalDate.class ).toProjection(),
								f.field( "geoPoint", GeoPoint.class ).toProjection(),
								f.documentReference().toProjection(),
								f.entityReference().toProjection(),
								f.entity().toProjection()
						)
				)
				.where( f -> f.matchAll() )
				.toQuery();
		verifyAll();

		resetAll();
		expect( hitTransformerMock.apply( projectionMatcher(
				STRING_VALUE, STRING_ANALYZED_VALUE, INTEGER_VALUE, LOCAL_DATE_VALUE, GEO_POINT_VALUE,
				mainReference, mainReference, mainReference
		) ) )
				.andReturn( mainTransformedHit );
		expect( hitTransformerMock.apply( projectionMatcher(
				null, null, null, null, null,
				emptyReference, emptyReference, emptyReference
		) ) )
				.andReturn( emptyTransformedHit );
		replayAll();
		assertThat( query ).hasHitsAnyOrder( mainTransformedHit, emptyTransformedHit );
		verifyAll();
	}

	@Test
	public void select_hitTransformer_referencesTransformer_entityLoading() {
		DocumentReference mainReference = reference( INDEX_NAME, MAIN_ID );
		DocumentReference emptyReference = reference( INDEX_NAME, EMPTY_ID );
		StubTransformedHit mainTransformedHit = new StubTransformedHit( mainReference );
		StubTransformedHit emptyTransformedHit = new StubTransformedHit( emptyReference );
		StubTransformedReference mainTransformedReference = new StubTransformedReference( mainReference );
		StubTransformedReference emptyTransformedReference = new StubTransformedReference( emptyReference );
		StubLoadedObject mainLoadedObject = new StubLoadedObject( mainReference );
		StubLoadedObject emptyLoadedObject = new StubLoadedObject( emptyReference );

		LoadingContext<StubTransformedReference, StubLoadedObject> loadingContextMock =
				createMock( LoadingContext.class );
		DocumentReferenceConverter<StubTransformedReference> documentReferenceConverterMock =
				createMock( StubDocumentReferenceConverter.class );
		EntityLoader<StubTransformedReference, StubLoadedObject> objectLoaderMock =
				createMock( StubEntityLoader.class );
		Function<List<?>, StubTransformedHit> hitTransformerMock = createMock( StubHitTransformer.class );

		resetAll();
		// No calls expected on the mocks
		replayAll();
		GenericStubMappingScope<StubTransformedReference, StubLoadedObject> scope =
				indexManager.createGenericScope();
		SearchQuery<StubTransformedHit> query = scope.query( loadingContextMock )
				.select( f ->
						f.composite(
								hitTransformerMock,
								f.field( "string", String.class ).toProjection(),
								f.documentReference().toProjection(),
								f.entityReference().toProjection(),
								f.entity().toProjection()
						)
				)
				.where( f -> f.matchAll() )
				.toQuery();
		verifyAll();

		resetAll();
		/*
		 * This will check in particular that the backend gets the projection hit mapper from the loading context,
		 * which must happen every time we execute the query,
		 * so that the mapper can run state checks (session is still open, ...).
		 */
		MapperEasyMockUtils.expectHitMapping(
				loadingContextMock, documentReferenceConverterMock, objectLoaderMock,
				/*
				 * Expect each reference to be transformed because of the entity reference projection,
				 * but also loaded because of the entity projection.
				 */
				c -> c
						.entityReference( mainReference, mainTransformedReference )
						.load( mainReference, mainTransformedReference, mainLoadedObject )
						.entityReference( emptyReference, emptyTransformedReference )
						.load( emptyReference, emptyTransformedReference, emptyLoadedObject )
		);
		expect( hitTransformerMock.apply( projectionMatcher(
				STRING_VALUE, mainReference, mainTransformedReference, mainLoadedObject
		) ) )
				.andReturn( mainTransformedHit );
		expect( hitTransformerMock.apply( projectionMatcher(
				null, emptyReference, emptyTransformedReference, emptyLoadedObject
		) ) )
				.andReturn( emptyTransformedHit );
		replayAll();
		assertThat( query ).hasHitsAnyOrder( mainTransformedHit, emptyTransformedHit );
		verifyAll();
	}

	@Test
	public void asEntityReference_noReferenceTransformer() {
		StubMappingScope scope = indexManager.createScope();

		SearchQuery<DocumentReference> query = scope.query()
				.asEntityReference()
				.where( f -> f.matchAll() )
				.toQuery();
		assertThat( query )
				.hasDocRefHitsAnyOrder( INDEX_NAME, MAIN_ID, EMPTY_ID );
	}

	@Test
	public void asEntity_noEntityLoading() {
		StubMappingScope scope = indexManager.createScope();

		SearchQuery<DocumentReference> query = scope.query()
				.asEntity()
				.where( f -> f.matchAll() )
				.toQuery();
		assertThat( query )
				.hasDocRefHitsAnyOrder( INDEX_NAME, MAIN_ID, EMPTY_ID );
	}

	@Test
	public void asProjection_hitTransformer() {
		DocumentReference mainReference = reference( INDEX_NAME, MAIN_ID );
		DocumentReference emptyReference = reference( INDEX_NAME, EMPTY_ID );
		StubTransformedHit mainTransformedHit = new StubTransformedHit( mainReference );
		StubTransformedHit emptyTransformedHit = new StubTransformedHit( emptyReference );

		Function<List<?>, StubTransformedHit> hitTransformerMock = createMock( StubHitTransformer.class );

		resetAll();
		// No calls expected on the mocks
		replayAll();
		StubMappingScope scope = indexManager.createScope();
		SearchQuery<StubTransformedHit> query = scope.query()
				.asProjection( f ->
						f.composite(
								hitTransformerMock,
								f.field( "string", String.class ).toProjection(),
								f.field( "string_analyzed", String.class ).toProjection(),
								f.field( "integer", Integer.class ).toProjection(),
								f.field( "localDate", LocalDate.class ).toProjection(),
								f.field( "geoPoint", GeoPoint.class ).toProjection(),
								f.documentReference().toProjection(),
								f.entityReference().toProjection(),
								f.entity().toProjection()
						)
				)
				.where( f -> f.matchAll() )
				.toQuery();
		verifyAll();

		resetAll();
		expect( hitTransformerMock.apply( projectionMatcher(
				STRING_VALUE, STRING_ANALYZED_VALUE, INTEGER_VALUE, LOCAL_DATE_VALUE, GEO_POINT_VALUE,
				mainReference, mainReference, mainReference
		) ) )
				.andReturn( mainTransformedHit );
		expect( hitTransformerMock.apply( projectionMatcher(
				null, null, null, null, null,
				emptyReference, emptyReference, emptyReference
		) ) )
				.andReturn( emptyTransformedHit );
		replayAll();
		assertThat( query ).hasHitsAnyOrder( mainTransformedHit, emptyTransformedHit );
		verifyAll();
	}

	@Test
	public void countQuery() {
		StubMappingScope scope = indexManager.createScope();

		SearchQuery<DocumentReference> query = scope.query()
				.where( f -> f.matchAll() )
				.toQuery();

		assertEquals( 2L, query.fetchTotalHitCount() );

		query = scope.query()
				.where( f -> f.match().field( "string" ).matching( STRING_VALUE ) )
				.toQuery();

		assertEquals( 1L, query.fetchTotalHitCount() );

		query = scope.query()
				.where( f -> f.matchAll() )
				.toQuery();

		// Using an offset/limit should not affect later counts
		query.fetch( 1, 1 );

		assertEquals( 2L, query.fetchTotalHitCount() );

		query.fetch( 0, 1 );

		assertEquals( 2L, query.fetchTotalHitCount() );
	}

	@Test
	public void countQueryWithProjection() {
		StubMappingScope scope = indexManager.createScope();

		SearchQuery<String> query = scope.query()
				.select( f -> f.field( "string", String.class ) )
				.where( f -> f.matchAll() )
				.toQuery();

		assertEquals( 2L, query.fetchTotalHitCount() );

		query = scope.query()
				.select( f -> f.field( "string", String.class ) )
				.where( f -> f.match().field( "string" ).matching( STRING_VALUE ) )
				.toQuery();

		assertEquals( 1L, query.fetchTotalHitCount() );
	}

	@Test
	public void callGetProjectionHitMapperEveryTime() {
		LoadingContext<DocumentReference, DocumentReference> loadingContextMock =
				createMock( LoadingContext.class );

		resetAll();
		// No calls expected on the mocks
		replayAll();
		GenericStubMappingScope<DocumentReference, DocumentReference> scope = indexManager.createGenericScope();
		SearchQuery<DocumentReference> query = scope.query( loadingContextMock )
				.selectEntity()
				.where( f -> f.matchAll() )
				.toQuery();
		verifyAll();

		/*
		 * We expect getProjectionHitMapper to be called *every time* a load is performed,
		 * so that the mapper can check its state (session is open in ORM, for example).
		 */

		resetAll();
		expect( loadingContextMock.getProjectionHitMapper() )
				.andReturn( new DefaultProjectionHitMapper<>( reference -> reference, EntityLoader.identity() ) );
		replayAll();
		query.fetchAll();
		verifyAll();

		// Second query execution to make sure the backend doesn't try to cache the projection hit mapper...
		resetAll();
		expect( loadingContextMock.getProjectionHitMapper() )
				.andReturn( new DefaultProjectionHitMapper<>( reference -> reference, EntityLoader.identity() ) );
		replayAll();
		query.fetchAll();
		verifyAll();
	}

	@Test
	@TestForIssue(jiraKey = "HSEARCH-3349")
	public void failedEntityLoading_skipHit() {
		DocumentReference mainDocumentReference = reference( INDEX_NAME, MAIN_ID );
		DocumentReference emptyDocumentReference = reference( INDEX_NAME, EMPTY_ID );
		StubTransformedReference mainEntityReference = new StubTransformedReference( mainDocumentReference );
		StubTransformedReference emptyEntityReference = new StubTransformedReference( emptyDocumentReference );
		StubLoadedObject emptyLoadedObject = new StubLoadedObject( emptyDocumentReference );

		LoadingContext<StubTransformedReference, StubLoadedObject> loadingContextMock =
				createMock( LoadingContext.class );
		DocumentReferenceConverter<StubTransformedReference> documentReferenceConverterMock =
				createMock( StubDocumentReferenceConverter.class );
		EntityLoader<StubTransformedReference, StubLoadedObject> objectLoaderMock =
				createMock( StubEntityLoader.class );

		resetAll();
		// No calls expected on the mocks
		replayAll();
		GenericStubMappingScope<StubTransformedReference, StubLoadedObject> scope =
				indexManager.createGenericScope();
		SearchQuery<StubLoadedObject> objectsQuery = scope.query( loadingContextMock )
				.where( f -> f.matchAll() )
				.toQuery();
		verifyAll();

		resetAll();
		MapperEasyMockUtils.expectHitMapping(
				loadingContextMock, documentReferenceConverterMock, objectLoaderMock,
				c -> c
						// Return "null" when loading, meaning the entity failed to load
						.load( mainDocumentReference, mainEntityReference, null )
						.load( emptyDocumentReference, emptyEntityReference, emptyLoadedObject )
		);
		replayAll();
		// Expect the main document to be excluded from hits, since it could not be loaded.
		assertThat( objectsQuery ).hasHitsAnyOrder( emptyLoadedObject );
		verifyAll();
	}

	private void initData() {
		IndexIndexingPlan<?> plan = indexManager.createIndexingPlan();
		plan.add( referenceProvider( MAIN_ID ), document -> {
			document.addValue( indexMapping.string, STRING_VALUE );
			document.addValue( indexMapping.string_analyzed, STRING_ANALYZED_VALUE );
			document.addValue( indexMapping.integer, INTEGER_VALUE );
			document.addValue( indexMapping.localDate, LOCAL_DATE_VALUE );
			document.addValue( indexMapping.geoPoint, GEO_POINT_VALUE );

			// Note: this object must be single-valued for these tests
			DocumentElement flattenedObject = document.addObject( indexMapping.flattenedObject.self );
			flattenedObject.addValue( indexMapping.flattenedObject.string, FLATTENED_OBJECT_STRING_VALUE );
			flattenedObject.addValue( indexMapping.flattenedObject.integer, FLATTENED_OBJECT_INTEGER_VALUE );

			// Note: this object must be single-valued for these tests
			DocumentElement nestedObject = document.addObject( indexMapping.nestedObject.self );
			nestedObject.addValue( indexMapping.nestedObject.string, NESTED_OBJECT_STRING_VALUE );
			nestedObject.addValue( indexMapping.nestedObject.integer, NESTED_OBJECT_INTEGER_VALUE );
		} );

		plan.add( referenceProvider( EMPTY_ID ), document -> { } );

		plan.execute().join();

		// Check that all documents are searchable
		StubMappingScope scope = indexManager.createScope();
		SearchQuery<DocumentReference> query = scope.query()
				.where( f -> f.matchAll() )
				.toQuery();
		assertThat( query )
				.hasDocRefHitsAnyOrder( INDEX_NAME, MAIN_ID, EMPTY_ID );
	}

	private static class IndexMapping {
		final IndexFieldReference<String> string;
		final IndexFieldReference<String> string_analyzed;
		final IndexFieldReference<Integer> integer;
		final IndexFieldReference<LocalDate> localDate;
		final IndexFieldReference<GeoPoint> geoPoint;
		final ObjectMapping flattenedObject;
		final ObjectMapping nestedObject;

		IndexMapping(IndexSchemaElement root) {
			string = root.field(
					"string",
					f -> f.asString().projectable( Projectable.YES )
			)
					.toReference();
			string_analyzed = root.field(
					"string_analyzed",
					f -> f.asString()
							.projectable( Projectable.YES )
							.analyzer( DefaultAnalysisDefinitions.ANALYZER_STANDARD_ENGLISH.name )
			)
					.toReference();
			integer = root.field(
					"integer",
					f -> f.asInteger().projectable( Projectable.YES )
			)
					.toReference();
			localDate = root.field(
					"localDate",
					f -> f.asLocalDate().projectable( Projectable.YES )
			)
					.toReference();
			geoPoint = root.field(
					"geoPoint",
					f -> f.asGeoPoint().projectable( Projectable.YES )
			)
					.toReference();
			IndexSchemaObjectField flattenedObjectField =
					root.objectField( "flattenedObject", ObjectFieldStorage.FLATTENED );
			flattenedObject = new ObjectMapping( flattenedObjectField );
			IndexSchemaObjectField nestedObjectField =
					root.objectField( "nestedObject", ObjectFieldStorage.NESTED );
			nestedObject = new ObjectMapping( nestedObjectField );
		}
	}

	private static class ObjectMapping {
		final IndexObjectFieldReference self;
		final IndexFieldReference<Integer> integer;
		final IndexFieldReference<String> string;

		ObjectMapping(IndexSchemaObjectField objectField) {
			self = objectField.toReference();
			string = objectField.field(
					"string",
					f -> f.asString().projectable( Projectable.YES )
			)
					.toReference();
			integer = objectField.field(
					"integer",
					f -> f.asInteger().projectable( Projectable.YES )
			)
					.toReference();
		}
	}

}
