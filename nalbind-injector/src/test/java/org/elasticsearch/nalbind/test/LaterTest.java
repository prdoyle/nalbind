package org.elasticsearch.nalbind.test;

import org.elasticsearch.nalbind.injector.Later;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LaterTest {
	Later<String> later;

	@BeforeEach
	void setupLater() {
		later = Later.later(String.class);
	}

	@Test
	void getBeforeSet_throws() {
		assertThrows(IllegalStateException.class, () -> later.value());
	}

	@Test
	void getAfterSet_returnsCorrectValue() {
		later.setValue("testValue");
		assertEquals("testValue", later.value());
	}

	@Test
	void setAfterSet_throws() {
		later.setValue("firstValue");
		assertThrows(IllegalStateException.class, () -> {
			later.setValue("secondValue");
		});
	}
}
