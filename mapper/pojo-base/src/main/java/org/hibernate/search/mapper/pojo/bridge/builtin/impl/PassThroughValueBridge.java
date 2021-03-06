/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.mapper.pojo.bridge.builtin.impl;

import java.util.function.Function;

import org.hibernate.search.engine.backend.types.dsl.IndexFieldTypeFactory;
import org.hibernate.search.engine.environment.bean.BeanHolder;
import org.hibernate.search.mapper.pojo.bridge.ValueBridge;
import org.hibernate.search.mapper.pojo.bridge.binding.ValueBindingContext;
import org.hibernate.search.mapper.pojo.bridge.mapping.programmatic.ValueBinder;
import org.hibernate.search.mapper.pojo.bridge.runtime.ValueBridgeFromIndexedValueContext;
import org.hibernate.search.mapper.pojo.bridge.runtime.ValueBridgeToIndexedValueContext;
import org.hibernate.search.util.common.impl.Contracts;

/**
 * A pass-through value bridge, i.e. a bridge that passes the input value as-is to the underlying backend.
 * <p>
 * This bridge will not work for any type: only types supported by the backend
 * through {@link IndexFieldTypeFactory#as(Class)} will work.
 *
 * @param <F> The type of input values, as well as the type of the index field.
 */
public final class PassThroughValueBridge<F> implements ValueBridge<F, F> {

	private final Class<F> fieldType;
	private final Function<String, F> parsingFunction;

	private PassThroughValueBridge(Class<F> fieldType, Function<String, F> parsingFunction) {
		Contracts.assertNotNull( fieldType, "fieldType" );
		this.fieldType = fieldType;
		this.parsingFunction = parsingFunction;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "[" + fieldType.getName() + "]";
	}

	@Override
	public F toIndexedValue(F value, ValueBridgeToIndexedValueContext context) {
		return value;
	}

	@Override
	public F fromIndexedValue(F value, ValueBridgeFromIndexedValueContext context) {
		return value;
	}

	@Override
	public F parse(String value) {
		return parsingFunction.apply( value );
	}

	@Override
	public boolean isCompatibleWith(ValueBridge<?, ?> other) {
		if ( !getClass().equals( other.getClass() ) ) {
			return false;
		}
		PassThroughValueBridge<?> castedOther = (PassThroughValueBridge<?>) other;
		return fieldType.equals( castedOther.fieldType );
	}

	public static class Binder<F> implements ValueBinder {
		private final Class<F> rawValueType;
		private final ValueBridge<F, F> bridge;

		public Binder(Class<F> rawValueType, Function<String, F> parsingFunction) {
			this.rawValueType = rawValueType;
			this.bridge = new PassThroughValueBridge<>( rawValueType, parsingFunction );
		}

		@Override
		public void bind(ValueBindingContext<?> context) {
			context.<F, F>setBridge(
					rawValueType,
					BeanHolder.of( bridge ),
					context.getTypeFactory().as( rawValueType )
			);
		}
	}
}