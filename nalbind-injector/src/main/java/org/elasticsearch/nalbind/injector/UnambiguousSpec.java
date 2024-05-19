package org.elasticsearch.nalbind.injector;

sealed interface UnambiguousSpec extends InjectionSpec
	permits ConstructorSpec, AliasSpec
{ }
