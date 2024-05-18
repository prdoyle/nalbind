package org.elasticsearch.example.module1;

import org.elasticsearch.example.module1.api.Module1Service;
import org.elasticsearch.nalbind.api.InjectableSingleton;

public class Module1ServiceImpl implements Module1Service, InjectableSingleton {
	@Override
	public String greeting() {
		return "Hello from " + getClass().getSimpleName();
	}
}
