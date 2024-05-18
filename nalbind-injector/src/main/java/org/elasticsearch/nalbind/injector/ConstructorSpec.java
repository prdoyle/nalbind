package org.elasticsearch.nalbind.injector;

import java.lang.reflect.Constructor;

/**
 * Indicates that a type should be instantiated by calling its constructor.
 */
record ConstructorSpec(
	Constructor<?> constructor
) implements UnambiguousSpec {
	@Override
	public Class<?> requestedType() {
		return constructor.getDeclaringClass();
	}
}
