/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.engine.environment.bean;


import org.hibernate.search.engine.environment.bean.spi.BeanResolver;
import org.hibernate.search.util.SearchException;

/**
 * The main entry point for components looking to retrieve user-provided beans.
 * <p>
 * Depending on the integration, beans may be resolved using reflection (expecting a no-argument constructor),
 * or using a more advanced dependency injection context (CDI, Spring DI).
 * <p>
 * Regardless of the implementations, this interface is used to retrieve the beans,
 * referenced either by their name, by their type, or both.
 * <p>
 * This interface may be used by any Hibernate Search module,
 * but should only be implemented by the Hibernate Search engine itself;
 * if you are looking for implementing your own bean resolver,
 * you should implement {@link BeanResolver} instead.
 */
public interface BeanProvider {

	/**
	 * Retrieve a bean referenced by its type.
	 * @param <T> The expected return type.
	 * @param expectedClass The expected class. Must be non-null. The returned bean will implement this class.
	 * @param typeReference The type used as a reference to the bean to retrieve.
	 * @return The resolved bean.
	 * @throws SearchException if the reference is invalid (null) or the bean cannot be resolved.
	 */
	<T> T getBean(Class<T> expectedClass, Class<?> typeReference);

	/**
	 * Retrieve a bean referenced by its type.
	 * @param <T> The expected return type.
	 * @param expectedClass The expected class. Must be non-null. The returned bean will implement this class.
	 * @param nameReference The name used as a reference to the bean to retrieve. Must be non-null and non-empty.
	 * @return The resolved bean.
	 * @throws SearchException if the reference is invalid (null or empty) or the bean cannot be resolved.
	 */
	<T> T getBean(Class<T> expectedClass, String nameReference);

	/**
	 * Retrieve a bean referenced by its type, name, or both, depending on the content of the {@link BeanReference}.
	 * @param <T> The expected return type.
	 * @param expectedClass The expected class. Must be non-null. The returned bean will implement this class.
	 * @param reference The reference to the bean to retrieve. Must be non-null.
	 * @return The resolved bean.
	 * @throws SearchException if the reference is invalid (null or empty) or the bean cannot be resolved.
	 */
	<T> T getBean(Class<T> expectedClass, BeanReference reference);

}