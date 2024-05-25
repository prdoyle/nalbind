package org.elasticsearch.nalbind.test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.elasticsearch.nalbind.injector.Later;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.openjdk.jmh.annotations.Mode.Throughput;

@Warmup(time = 2000, timeUnit = MILLISECONDS, iterations = 3)
@Measurement(time = 2000, timeUnit = MILLISECONDS, iterations = 2)
@Fork(20)
@BenchmarkMode(Throughput)
public class LaterBench {
	static final int ITERS = 3;

	static final class VolatileLater<T> implements Later<T> {
		volatile T value = null;
		final AtomicBoolean alreadySet = new AtomicBoolean(false);

		@Override
		public T value() {
			T result = value;
			if (result == null) {
				throw new IllegalStateException("Not yet set");
			} else {
				return result;
			}
		}

		@Override
		public void setValue(T newValue) {
			if (alreadySet.compareAndSet(false, true)) {
				value = requireNonNull(newValue);
			} else {
				throw new IllegalStateException("Already set");
			}
		}
	}

	static final class AtomicLater<T> implements Later<T> {
		final AtomicReference<T> ref = new AtomicReference<>(null);

		@Override
		public T value() {
			T result = ref.get();
			if (result == null) {
				throw new IllegalStateException("Not yet set");
			} else {
				return result;
			}
		}

		@Override
		public void setValue(T newValue) {
			if (!ref.compareAndSet(null, newValue)) {
				throw new IllegalStateException("Already set");
			}
		}
	}

	/**
	 * Not a valid implementation. Just for comparison.
	 */
	static final class FinalLater<T> implements Later<T> {
		final T value;

		FinalLater(T value) {
			this.value = value;
		}

		@Override
		public T value() {
			return value;
		}

		@Override
		public void setValue(T newValue) {
		}
	}

	@State(Scope.Benchmark)
	public static class BenchmarkState {
		final String finalField;
		String regularField;
		volatile String volatileField;
		final Later<String> volatileLater;
		final Later<String> atomicLater;
		final Later<String> indyLater;
		final Later<String> finalLater;

		public BenchmarkState() {
			this.finalField = "Hello";
			this.regularField = "Hello";
			this.volatileField = "Hello";
			this.volatileLater = new VolatileLater<>();
			volatileLater.setValue("Hello");
			this.atomicLater = new AtomicLater<>();
			atomicLater.setValue("Hello");
			this.indyLater = Later.later(String.class);
			indyLater.setValue("Hello");
			this.finalLater = new FinalLater<>("Hello");
		}
	}

	@Benchmark
	public void volatileLater(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.volatileLater.value());
		}
	}

	@Benchmark
	public void atomicLater(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.atomicLater.value());
		}
	}

	@Benchmark
	public void indyLater(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.indyLater.value());
		}
	}

	@Benchmark
	public void x_finalLater(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.finalLater.value());
		}
	}

	@Benchmark
	public void x_baseline(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state);
		}
	}

//	@Benchmark
	public void x_finalField(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.finalField);
		}
	}

//	@Benchmark
	public void x_regularField(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.regularField);
		}
	}

//	@Benchmark
	public void x_volatileField(BenchmarkState state, Blackhole blackhole) {
		for (int i = 0; i < ITERS; i++) {
			blackhole.consume(state.volatileField);
		}
	}

}
