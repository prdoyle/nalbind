package org.elasticsearch.nalbind.test;

import org.elasticsearch.nalbind.injector.ProxyFactory;
import org.elasticsearch.nalbind.injector.ProxyFactory.ProxyInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ProxyTest {
	ProxyInfo<TestInterface> proxy;

	@BeforeEach
	void createProxy() {
		proxy = ProxyFactory.generateFor(TestInterface.class);
	}

	@Test
	void useAfterSet_works() {
		proxy.setter().accept(new TestImplementation());
		assertEquals("Received testArg", proxy.proxyObject().testMethod("testArg"));
	}

	@Test
	void useBeforeSet_throws() {
		assertThrows(IllegalStateException.class, () -> proxy.proxyObject().testMethod("testArg"));
	}

	@Test
	void setAfterSet_throws() {
		proxy.setter().accept(new TestImplementation());
		assertThrows(IllegalStateException.class, () -> proxy.setter().accept(new TestImplementation()));
	}

	public interface TestInterface {
		String testMethod(String arg);
	}

	public class TestImplementation implements TestInterface {
		@Override
		public String testMethod(String arg) {
			return "Received " + arg;
		}
	}
}
