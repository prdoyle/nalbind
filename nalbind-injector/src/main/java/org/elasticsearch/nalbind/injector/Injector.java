package org.elasticsearch.nalbind.injector;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.elasticsearch.nalbind.api.Inject;
import org.elasticsearch.nalbind.api.InjectableSingleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.newSetFromMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class Injector {
	private final Map<Class<?>, Object> instances = new HashMap<>();

	private Injector(){}

	public static Injector withFullLayerScan(ModuleLayer layer) {
		Injector result = new Injector();
		result.scan(layer);
		return result;
	}

	public <T> T getInstance(Class<T> type) {
		return type.cast(requireNonNull(instances.get(type)));
	}

	private void scan(ModuleLayer layer) {
		var specsByClass = discoveredInjectableClasses(layer);
		var plan = instantiationPlan(specsByClass);
		executePlan(plan);
	}

	/**
	 * The classes this will locate are:
	 *
	 * <ul><li>
	 *     All {@link InjectableSingleton} types provided ny all modules in the given <code>layer</code>
	 * </li><li>
	 *     All types of parameters passed to the constructor used to instantiate any discovered class.
	 * </li></ul>
	 *
	 * Note, in particular, that <em>subtypes are not discovered automatically</em>.
	 * For example, if your constructor takes a parameter whose type is an interface,
	 * the implementations of that interface will not be discovered unless they are also
	 * {@link InjectableSingleton}s or are otherwise discoverable as constructor parameters.
	 *
	 * <p>
	 * So it is possible to inject classes that don't inherit {@link InjectableSingleton},
	 * but only if those classes are explicitly named in an injectable constructor somewhere.
	 */
	private static Map<Class<?>, InjectionSpec> discoveredInjectableClasses(ModuleLayer layer) {
		Set<Class<?>> discoveredClasses = new HashSet<>();
		for (var m: layer.modules()) {
			for (var p: m.getDescriptor().provides()) {
				if (InjectableSingleton.class.getName().equals(p.service())) {
					p.providers().forEach(name -> {
						try {
							discoveredClasses.add(m.getClassLoader().loadClass(name));
						} catch (ClassNotFoundException e) {
							throw new IllegalStateException("Unexpected error scanning module layer", e);
						}
					});
				}
			}
		}
		Set<Class<?>> allDiscoveredClasses = Set.copyOf(discoveredClasses);
		LOGGER.debug("Discovered classes: {}", allDiscoveredClasses);

		// By using a LinkedHashMap, we preserve the order that the classes
		// were encountered (a postorder) which also happens to be the
		// right order to instantiate the objects.
		Map<Class<?>, InjectionSpec> specsByClass = new LinkedHashMap<>();
		for (var c: allDiscoveredClasses) {
			computeSpec(c, discoveredClasses, specsByClass);
		}
		if (LOGGER.isTraceEnabled()) {
			LOGGER.trace("Specs: {}",
				specsByClass.values().stream()
					.map(Object::toString)
					.collect(joining("\n\t", "\n\t", "")));
		}
		return specsByClass;
	}

	/**
	 * @return the {@link UnambiguousSpec} objects listed in execution order.
	 */
	private static Collection<UnambiguousSpec> instantiationPlan(Map<Class<?>, InjectionSpec> specsByClass) {
		// TODO: Cycle detection and reporting. Use SCCs
		LOGGER.trace("Constructing instantiation plan");
		List<UnambiguousSpec> plan = new ArrayList<>();
		Set<InjectionSpec> alreadyPlanned = newSetFromMap(new IdentityHashMap<>());
		specsByClass.keySet().forEach((c) ->
			updateInstantiationPlan(plan, c, specsByClass, alreadyPlanned)
		);
		LOGGER.trace("Instantiation plan: {}", plan);
		return plan;
	}

	private static void updateInstantiationPlan(List<UnambiguousSpec> plan, Class<?> requestedClass, Map<Class<?>, InjectionSpec> specsByClass, Set<InjectionSpec> alreadyPlanned) {
		InjectionSpec spec = specsByClass.get(requestedClass);
		if (alreadyPlanned.add(spec)) {
			switch (spec) {
				case null ->
					throw new IllegalStateException("Cannot instantiate " + requestedClass);
				case ConstructorSpec c -> {
					if (c.constructor().getParameterCount() != 0) {
						LOGGER.trace("Recursing into parameters of {}", c);
						for (var pt: c.constructor().getParameterTypes()) {
							updateInstantiationPlan(plan, pt, specsByClass, alreadyPlanned);
						}
					}
					LOGGER.trace("Plan to call constructor {}", c);
					plan.add(c);
				}
				case AliasSpec a -> {
					LOGGER.trace("Recursing into subtype for {}", a);
					updateInstantiationPlan(plan, a.subtype(), specsByClass, alreadyPlanned);
					LOGGER.trace("Plan to alias {}", a);
					plan.add(a);
				}
				case AmbiguousSpec a ->
					LOGGER.trace("Skipping {}", a);
			}
		}
	}

	private void executePlan(Collection<UnambiguousSpec> plan) {
		plan.forEach(spec -> {
			switch (spec) {
				case ConstructorSpec c -> {
					LOGGER.debug("Instantiating {}", c.requestedType().getSimpleName());
					instances.put(c.requestedType(), instantiate(c.constructor()));
				}
				case AliasSpec(var requestedType, var subtype) -> {
					LOGGER.debug("Aliasing {} to {}", requestedType.getSimpleName(), subtype.getSimpleName());
					instances.put(requestedType, getInstance(subtype));
				}
			}
		});
	}

	private Object instantiate(Constructor<?> constructor) {
		Object[] args = Stream.of(constructor.getParameterTypes())
			.map(this::getInstance)
			.toArray();
		try {
			return constructor.newInstance(args);
		} catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
			throw new IllegalStateException("Unable to call constructor: " + constructor, e);
		}
	}

	/**
	 * @param checklist will have <code>c</code> removed from it
	 * @param specsByClass will be left in topological order
	 */
	private static void computeSpec(Class<?> c, Set<Class<?>> checklist, Map<Class<?>, InjectionSpec> specsByClass) {
		InjectionSpec existingResult = specsByClass.get(c);
		if (existingResult != null) {
			LOGGER.trace("Spec for {} already exists", c);
			return;
		}

		if (checklist.remove(c)) {
			Constructor<?> constructor = getSuitableConstructorIfAny(c);
			if (constructor == null) {
				LOGGER.debug("No suitable constructor: {}", c);
			} else {
				LOGGER.trace("Recurse into parameters for: {}", constructor);
				for (var pt: constructor.getParameterTypes()) {
					computeSpec(pt, checklist, specsByClass);
				}

				registerSpec(new ConstructorSpec(constructor), specsByClass);
				aliasSuperinterfaces(c, c, specsByClass);
				for (Class<?> superclass = c.getSuperclass(); superclass != Object.class; superclass = superclass.getSuperclass()) {
					registerSpec(new AliasSpec(superclass, c), specsByClass);
					aliasSuperinterfaces(superclass, c, specsByClass);
				}
			}
		}
	}

	private static void aliasSuperinterfaces(Class<?> classToScan, Class<?> classToAlias, Map<Class<?>, InjectionSpec> specsByClass) {
		for (var i: classToScan.getInterfaces()) {
			registerSpec(new AliasSpec(i, classToAlias), specsByClass);
			aliasSuperinterfaces(i, classToAlias, specsByClass);
		}
	}

	private static void registerSpec(InjectionSpec spec, Map<Class<?>, InjectionSpec> specsByClass) {
		Class<?> requestedType = spec.requestedType();
		var existing = specsByClass.put(requestedType, spec);
		if (existing != null && !existing.equals(spec)) {
			AmbiguousSpec ambiguousSpec = new AmbiguousSpec(requestedType, spec, existing);
			LOGGER.trace("Ambiguity discovered: {}", ambiguousSpec);
			specsByClass.put(requestedType, ambiguousSpec);
		} else {
			LOGGER.trace("Register spec: {}", spec);
		}
	}

	private static Constructor<?> getSuitableConstructorIfAny(Class<?> type) {
		Constructor<?>[] constructors = type.getDeclaredConstructors();
		if (constructors.length == 1) {
			return constructors[0];
		} else {
			var injectConstructors = Stream.of(constructors)
				.filter(c -> c.isAnnotationPresent(Inject.class))
				.toList();
			if (injectConstructors.size() == 1) {
				return injectConstructors.getFirst();
			} else {
				return null;
			}
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(Injector.class);
}
