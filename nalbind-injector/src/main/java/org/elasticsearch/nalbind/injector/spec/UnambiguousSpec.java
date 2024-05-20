package org.elasticsearch.nalbind.injector.spec;

sealed public interface UnambiguousSpec extends InjectionSpec
	permits ConstructorSpec, AliasSpec
{ }
