package org.elasticsearch.nalbind.injector.spec;

/**
 * Indicates that a type should be injected by supplying a value of some subtype instead.
 */
public record AliasSpec(
	Class<?> requestedType,
	Class<?> subtype
) implements UnambiguousSpec { }
