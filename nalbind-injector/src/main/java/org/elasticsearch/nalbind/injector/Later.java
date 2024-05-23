package org.elasticsearch.nalbind.injector;

public interface Later<T> {
	T value();
	void setValue(T newValue);

	static <T> Later<T> later(Class<T> type) {
		return LaterFactory.generateFor(type);
	}
}
