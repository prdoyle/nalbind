package org.elasticsearch.nalbind.injector;

public sealed interface UnambiguousSpec extends InjectionSpec
	permits ConstructorSpec, AliasSpec
{ }
