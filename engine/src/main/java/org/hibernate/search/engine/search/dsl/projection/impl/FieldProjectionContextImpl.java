/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.engine.search.dsl.projection.impl;

import org.hibernate.search.engine.search.SearchProjection;
import org.hibernate.search.engine.search.dsl.projection.FieldProjectionContext;
import org.hibernate.search.engine.search.projection.spi.FieldSearchProjectionBuilder;
import org.hibernate.search.engine.search.projection.spi.SearchProjectionFactory;


public class FieldProjectionContextImpl<T> implements FieldProjectionContext<T> {

	private FieldSearchProjectionBuilder<T> fieldProjectionBuilder;

	FieldProjectionContextImpl(SearchProjectionFactory factory, String absoluteFieldPath, Class<T> clazz) {
		this.fieldProjectionBuilder = factory.field( absoluteFieldPath, clazz );
	}

	@Override
	public SearchProjection<T> toProjection() {
		return fieldProjectionBuilder.build();
	}

}