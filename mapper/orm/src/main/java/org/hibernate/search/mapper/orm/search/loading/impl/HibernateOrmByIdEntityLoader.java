/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.mapper.orm.search.loading.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.hibernate.AssertionFailure;
import org.hibernate.MultiIdentifierLoadAccess;
import org.hibernate.Session;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.metamodel.spi.MetamodelImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.search.mapper.orm.common.EntityReference;
import org.hibernate.search.mapper.orm.common.impl.HibernateOrmUtils;
import org.hibernate.search.mapper.orm.search.loading.EntityLoadingCacheLookupStrategy;

public class HibernateOrmByIdEntityLoader<E> implements HibernateOrmComposableEntityLoader<E> {

	public static EntityLoaderFactory factory(SessionFactoryImplementor sessionFactory,
			EntityPersister entityType) {
		return new Factory( toRootEntityType( sessionFactory, entityType ) );
	}

	private final Session session;
	private final EntityPersister targetEntityType;
	private final EntityLoadingCacheLookupStrategyImplementor<?> cacheLookupStrategyImplementor;
	private final MutableEntityLoadingOptions loadingOptions;

	private HibernateOrmByIdEntityLoader(
			EntityPersister targetEntityType,
			Session session,
			EntityLoadingCacheLookupStrategyImplementor<E> cacheLookupStrategyImplementor,
			MutableEntityLoadingOptions loadingOptions) {
		this.targetEntityType = targetEntityType;
		this.session = session;
		this.cacheLookupStrategyImplementor = cacheLookupStrategyImplementor;
		this.loadingOptions = loadingOptions;
	}

	@Override
	public List<E> loadBlocking(List<EntityReference> references) {
		if ( cacheLookupStrategyImplementor == null ) {
			// Optimization: if we don't need to look up the cache, we don't need a map to store intermediary results.
			return loadEntities( references );
		}
		else {
			return HibernateOrmComposableEntityLoader.super.loadBlocking( references );
		}
	}

	@Override
	public void loadBlocking(List<EntityReference> references, Map<? super EntityReference, ? super E> entitiesByReference) {
		List<EntityReference> missingFromCacheReferences = loadBlockingFromCache( references, entitiesByReference );
		if ( missingFromCacheReferences.isEmpty() ) {
			return;
		}

		List<? extends E> loadedEntities = loadEntities( missingFromCacheReferences );
		Iterator<EntityReference> referencesIterator = missingFromCacheReferences.iterator();
		Iterator<? extends E> loadedEntityIterator = loadedEntities.iterator();
		while ( referencesIterator.hasNext() ) {
			EntityReference reference = referencesIterator.next();
			E loadedEntity = loadedEntityIterator.next();
			if ( loadedEntity != null ) {
				entitiesByReference.put( reference, loadedEntity );
			}
		}
	}

	/**
	 * @param references The references to entities to load from the cache.
	 * @param entitiesByReference The map where loaded entities should be put.
	 * @return The references that could not be loaded from the cache.
	 */
	// The cast is safe because we check that every loaded element is an instance of the type from the entity reference.
	@SuppressWarnings("unchecked")
	private List<EntityReference> loadBlockingFromCache(List<EntityReference> references,
			Map<? super EntityReference,? super E> entitiesByReference) {
		if ( cacheLookupStrategyImplementor == null ) {
			return references;
		}

		List<EntityReference> missingFromCacheReferences = new ArrayList<>( references.size() );

		for ( EntityReference reference : references ) {
			Object entityId = reference.getId();
			Object loadedEntity = cacheLookupStrategyImplementor.lookup( entityId );
			if ( loadedEntity == null ) {
				missingFromCacheReferences.add( reference );
			}
			else if ( hasExpectedType( reference, loadedEntity ) ) {
				entitiesByReference.put( reference, (E) loadedEntity );
			}
			else {
				// The index is out of sync and the referenced entity does not exist anymore.
				// Assume the entity we were attempting to load was deleted, and mark it as such.
				entitiesByReference.put( reference, null );
			}
		}

		return missingFromCacheReferences;
	}

	// The cast is safe because we check that every loaded element is an instance of the type from the entity reference.
	@SuppressWarnings("unchecked")
	private List<E> loadEntities(List<EntityReference> references) {
		List<Serializable> ids = new ArrayList<>( references.size() );
		for ( EntityReference reference : references ) {
			ids.add( (Serializable) reference.getId() );
		}

		List<?> loadedEntities = getMultiAccess().multiLoad( ids );

		for ( int i = 0; i < references.size(); i++ ) {
			EntityReference reference = references.get( i );
			Object loadedEntity = loadedEntities.get( i );
			if ( !hasExpectedType( reference, loadedEntity ) ) {
				// The index is out of sync and the referenced entity does not exist anymore.
				// Assume the entity we were attempting to load was deleted and mark it as such.
				loadedEntities.set( i, null );
			}
		}

		return (List<E>) loadedEntities;
	}

	private MultiIdentifierLoadAccess<?> getMultiAccess() {
		MultiIdentifierLoadAccess<?> multiAccess = session.byMultipleIds( targetEntityType.getEntityName() );

		multiAccess.withBatchSize( loadingOptions.getFetchSize() );

		return multiAccess;
	}

	/*
	 * Under some circumstances, the multi-access or the cache lookups may return entities that extend E,
	 * but not the type expected by users.
	 *
	 * For example, let's consider entity types A, B, C, D, with B, C, and D extending A
	 * Let's imagine an instance of type B and with id 4 is deleted from the database
	 * and replaced with an instance of type D and id 4.
	 * If a search on entity types B and C is performed before the index is refreshed,
	 * we might be requested to load entity B with id 4,
	 * and since we're working with the common supertype A,
	 * loading will succeed but will yield an entity of type D with id 4.
	 *
	 * Now, the entity will still be an instance of A, but... the user doesn't care about A:
	 * the user asked for a search on entities B and C.
	 * Returning D might be a problem, especially if the user intends to call methods defined on an interface I,
	 * implemented by B and C, but not D.
	 * This will be a problem since that entity does not implement I.
	 *
	 * The easiest way to avoid this problem is to just check the type of every loaded entity,
	 * to be sure it's the same type that was originally requested.
	 * Then we will be safe, because callers are expected to only pass entity references
	 * to types that were originally targeted by the search,
	 * and these types are known to implement any interface that the user could possibly rely on.
	 */
	private static boolean hasExpectedType(EntityReference reference, Object loadedEntity) {
		return reference.getType().isInstance( loadedEntity );
	}

	private static EntityPersister toRootEntityType(
			SessionFactoryImplementor sessionFactory, EntityPersister entityType) {
		/*
		 * We need to rely on Hibernate ORM's SPIs: this is complex stuff.
		 * For example there may be class hierarchies such as A > B > C
		 * where A and C are entity types and B is a mapped superclass.
		 * So we need to exclude non-entity types, and for that we need the Hibernate ORM metamodel.
		 */
		MetamodelImplementor metamodel = sessionFactory.getMetamodel();
		String rootEntityName = metamodel.entityPersister( entityType.getEntityName() ).getRootEntityName();
		return metamodel.entityPersister( rootEntityName ).getEntityPersister();
	}

	private static class Factory implements EntityLoaderFactory {

		private final EntityPersister rootEntityType;

		private Factory(EntityPersister rootEntityType) {
			this.rootEntityType = rootEntityType;
		}

		@Override
		public boolean equals(Object obj) {
			if ( obj == null || !( getClass().equals( obj.getClass() ) ) ) {
				return false;
			}
			Factory other = (Factory) obj;
			// If the root entity type is different,
			// the factories work in separate ID spaces and should be used separately.
			return rootEntityType.equals( other.rootEntityType );
		}

		@Override
		public int hashCode() {
			return rootEntityType.hashCode();
		}

		@Override
		public <E> HibernateOrmComposableEntityLoader<E> create(
				HibernateOrmLoadingIndexedTypeContext targetEntityTypeContext,
				SessionImplementor session,
				EntityLoadingCacheLookupStrategy cacheLookupStrategy, MutableEntityLoadingOptions loadingOptions) {
			/*
			 * This cast is safe: the loader will only return instances of E.
			 * See hasExpectedType() and its callers for more information,
			 * in particular runtime checks handling edge cases.
			 */
			@SuppressWarnings("unchecked")
			HibernateOrmComposableEntityLoader<E> result = (HibernateOrmComposableEntityLoader<E>) doCreate(
					targetEntityTypeContext.getEntityPersister(), session, cacheLookupStrategy, loadingOptions
			);
			return result;
		}

		@Override
		public <E> HibernateOrmComposableEntityLoader<? extends E> create(
				List<HibernateOrmLoadingIndexedTypeContext> targetEntityTypeContexts,
				SessionImplementor session, EntityLoadingCacheLookupStrategy cacheLookupStrategy,
				MutableEntityLoadingOptions loadingOptions) {
			EntityPersister commonSuperType = toMostSpecificCommonEntitySuperType( session, targetEntityTypeContexts );

			/*
			 * Theoretically, this cast is unsafe,
			 * since the loader could return entities of any type T extending "commonSuperClass",
			 * which is either E (good: T = E)
			 * or a common supertype of some child types of E
			 * (not good: T might be an interface that E doesn't implement but its children do).
			 *
			 * However, we perform some runtime checks that make this cast safe.
			 *
			 * See hasExpectedType() and its callers for more information.
			 */
			@SuppressWarnings("unchecked")
			HibernateOrmComposableEntityLoader<E> result = (HibernateOrmComposableEntityLoader<E>) doCreate(
					commonSuperType, session, cacheLookupStrategy, loadingOptions
			);

			return result;
		}

		private HibernateOrmComposableEntityLoader<?> doCreate(EntityPersister targetEntityType,
				SessionImplementor session,
				EntityLoadingCacheLookupStrategy cacheLookupStrategy, MutableEntityLoadingOptions loadingOptions) {
			if ( !rootEntityType.getMappedClass().isAssignableFrom( targetEntityType.getMappedClass() ) ) {
				throw new AssertionFailure(
						"Some types among the targeted entity types are not subclasses of the expected root entity type."
								+ " There is a bug in Hibernate Search, please report it."
								+ " Expected root entity name: " + rootEntityType.getEntityName()
								+ " Targeted entity name: " + targetEntityType.getEntityName()
				);
			}

			EntityLoadingCacheLookupStrategyImplementor<?> cacheLookupStrategyImplementor;

			/*
			 * Ideally, in order to comply with the cache lookup strategy,
			 * we would use multiAccess setters such as
			 * with(CacheMode) and enableSessionCheck(boolean),
			 * and let Hibernate ORM do it for us.
			 *
			 * However, with(CacheMode) has a side-effect: it can also affect how entities are put into the cache.
			 * Since the cache lookup strategy has nothing to do with that,
			 * we go the safer route and wrap the loader with other loaders that
			 * will perform PC and 2LC checking prior to using the multiAccess.
			 */
			switch ( cacheLookupStrategy ) {
				case SKIP:
					cacheLookupStrategyImplementor = null;
					break;
				case PERSISTENCE_CONTEXT:
					cacheLookupStrategyImplementor =
							PersistenceContextLookupStrategy.create( targetEntityType, session );
					break;
				case PERSISTENCE_CONTEXT_THEN_SECOND_LEVEL_CACHE:
					cacheLookupStrategyImplementor =
							PersistenceContextThenSecondLevelCacheLookupStrategy.create( targetEntityType, session );
					break;
				default:
					throw new AssertionFailure( "Unexpected cache lookup strategy: " + cacheLookupStrategy );
			}

			return new HibernateOrmByIdEntityLoader<>(
					targetEntityType, session, cacheLookupStrategyImplementor, loadingOptions
			);
		}

		private static EntityPersister toMostSpecificCommonEntitySuperType(SessionImplementor session,
				Iterable<? extends HibernateOrmLoadingIndexedTypeContext> targetEntityTypeContexts) {
			MetamodelImplementor metamodel = session.getSessionFactory().getMetamodel();
			EntityPersister result = null;
			for ( HibernateOrmLoadingIndexedTypeContext targetTypeContext : targetEntityTypeContexts ) {
				EntityPersister type = targetTypeContext.getEntityPersister();
				if ( result == null ) {
					result = type;
				}
				else {
					result = toMostSpecificCommonEntitySuperType( metamodel, result, type );
				}
			}
			return result;
		}

		private static EntityPersister toMostSpecificCommonEntitySuperType(MetamodelImplementor metamodel,
				EntityPersister type1, EntityPersister type2) {
			/*
			 * We need to rely on Hibernate ORM's SPIs: this is complex stuff.
			 * For example there may be class hierarchies such as A > B > C
			 * where A and C are entity types and B is a mapped superclass.
			 * So even if we know the two types have a common superclass,
			 * we need to skip non-entity superclasses, and for that we need the Hibernate ORM metamodel.
			 */
			EntityPersister superTypeCandidate = type1;
			while ( superTypeCandidate != null && !HibernateOrmUtils.isSuperTypeOf( superTypeCandidate, type2 ) ) {
				String superSuperTypeEntityName = superTypeCandidate.getEntityMetamodel().getSuperclass();
				superTypeCandidate = superSuperTypeEntityName == null ? null
						: metamodel.entityPersister( superSuperTypeEntityName ).getEntityPersister();
			}
			if ( superTypeCandidate == null ) {
				throw new AssertionFailure(
						"Cannot find a common entity supertype for " + type1.getEntityName()
								+ " and " + type2.getEntityName() + "."
								+ " There is a bug in Hibernate Search, please report it."
				);
			}
			return superTypeCandidate;
		}
	}
}
