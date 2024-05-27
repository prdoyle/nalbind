package org.elasticsearch.nalbind.injector;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.invoke.MutableCallSite.syncAll;
import static java.lang.reflect.Modifier.isStatic;
import static java.util.Objects.requireNonNull;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_8;
import static org.objectweb.asm.Type.getDescriptor;
import static org.objectweb.asm.Type.getInternalName;
import static org.objectweb.asm.Type.getMethodDescriptor;
import static org.objectweb.asm.Type.getReturnType;
import static org.objectweb.asm.Type.getType;


public class ProxyFactory {
	private static final Map<String, MutableCallSite> callSites = new ConcurrentHashMap<>();
	private static final AtomicInteger numCallSites = new AtomicInteger(0);

	public record ProxyInfo<T> (
		Class<T> interfaceType,
		T proxyObject,
		Consumer<T> setter
	){}

	/**
	 * The proxies we generate are optimized for run-time performance over generation efficiency.
	 * One result of this is that every proxy object requires generating and loading its on class,
	 * so they are expensive to create.
	 * The caller of this method should make an effort to reuse the resulting objects as much as possible.
	 */
	public static <T> ProxyInfo<T> generateFor(Class<T> interfaceType) {
		if (!interfaceType.isInterface()) {
			throw new IllegalArgumentException("Only interfaces can be proxied; cannot proxy " + interfaceType);
		}

		int callSiteNum = numCallSites.incrementAndGet();
		String methodName = "callSite+ " + callSiteNum;
		MutableCallSite callSite = newCallSite(MethodType.methodType(interfaceType));
		callSites.put(methodName, callSite);

		ClassWriter cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
		cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, "NALBIND_PROXY", null, getInternalName(Object.class), new String[]{getInternalName(interfaceType)});

		generateConstructor(cw);
		HashSet<Class<?>> interfacesAlreadySeen = new HashSet<>();
		generateDelegatingMethods(interfaceType, interfacesAlreadySeen, methodName, cw);

		cw.visitEnd();

		T proxy = interfaceType.cast(instantiate(loadProxyClass(cw)));
		AtomicBoolean alreadySet = new AtomicBoolean(false);
		return new ProxyInfo<>(
			interfaceType,
			proxy,
			(T newValue) -> {
				if (alreadySet.getAndSet(true)) {
					throw new IllegalStateException("Already set!");
				} else {
					callSite.setTarget(constant(interfaceType, newValue));
					syncAll(new MutableCallSite[]{callSite});
				}
			}
		);
	}

	private static <T> void generateDelegatingMethods(Class<T> interfaceType, HashSet<Class<?>> alreadySeen, String methodName, ClassWriter cw) {
		if (alreadySeen.add(interfaceType)) {
			LOGGER.trace("generateDelegatingMethods for {}", interfaceType);
		} else {
			return;
		}

		for (Class<?> s: interfaceType.getInterfaces()) {
			generateDelegatingMethods(s, alreadySeen, methodName, cw);
		}

		for (Method m: interfaceType.getDeclaredMethods()) {
			generateDelegatingMethod(m, interfaceType, methodName, cw);
		}
	}

	private static <T> void generateDelegatingMethod(Method m, Class<T> targetType, String targetMethodName, ClassWriter cw) {
		LOGGER.trace("generateDelegatingMethod {}", m);

		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, m.getName(), Type.getMethodDescriptor(m), null, null);
		mv.visitCode();

		// Push delegation target object
		getTarget(targetType, mv, targetMethodName);

		// Push args
		int localSlot = 1;
		for (Class<?> pt: m.getParameterTypes()) {
			mv.visitVarInsn(getType(pt).getOpcode(ILOAD), localSlot);
			localSlot += Type.getType(pt).getSize();
		}

		// Invoke and return result
		invoke(m, mv);
		mv.visitInsn(getReturnType(m).getOpcode(IRETURN));

		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	public static void invoke(Method method, MethodVisitor mv) {
		Class<?> type = method.getDeclaringClass();
		String typeName = Type.getInternalName(type);
		String methodName = method.getName();
		String signature = getMethodDescriptor(method);
		if (isStatic(method.getModifiers())) {
			// Static methods have no "this" argument
			mv.visitMethodInsn(INVOKESTATIC, typeName, methodName, signature, false);
		} else if (type.isInterface()) {
			mv.visitMethodInsn(INVOKEINTERFACE, typeName, methodName, signature, true);
		} else {
			mv.visitMethodInsn(INVOKEVIRTUAL, typeName, methodName, signature, false);
		}
	}

	private static Constructor<?> loadProxyClass(ClassWriter cw) {
		return new CustomClassLoader(ProxyFactory.class.getClassLoader())
			.loadThemBytes("NALBIND_PROXY", cw.toByteArray())
			.getConstructors()[0];
	}

	private static Object instantiate(Constructor<?> ctor) {
		try {
			return ctor.newInstance();
		} catch (InstantiationException | IllegalAccessException | VerifyError | InvocationTargetException e) {
			throw new AssertionError("Should be able to instantiate the generated class", e);
		}
	}

	private static <T> void generateValueMethod(Class<T> interfaceType, ClassWriter cw, String methodName) {
    /* Note that this descriptor returns Object even though we know a more specific
	   return type, and Java allows covariant return types. Turns out that is a
	   language feature that isn't available at the bytecode level. Hopefully
	   this method gets inlined and the jit can see through it to avoid a downcast.
	 */
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "value", "()" + getDescriptor(Object.class), null, null);
		mv.visitCode();

		getTarget(interfaceType, mv, methodName);

		// Return the result
		mv.visitInsn(ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static <T> void getTarget(Class<T> interfaceType, MethodVisitor mv, String methodName) {
		Handle bootstrapMethodHandle = new Handle(
			Opcodes.H_INVOKESTATIC,

			getInternalName(ProxyFactory.class),
			"bootstrap",
			MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class).toMethodDescriptorString(),
			false
		);
		mv.visitInvokeDynamicInsn(methodName, "()" + getDescriptor(interfaceType), bootstrapMethodHandle);
	}

	private static void generateConstructor(ClassWriter cw) {
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitMethodInsn(INVOKESPECIAL, getInternalName(Object.class), "<init>", "()V", false);
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static MutableCallSite newCallSite(MethodType type) {
		try {
			return new MutableCallSite(lookup()
				.findStatic(ProxyFactory.class, "notYetSet", methodType(void.class))
				.asType(type));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new AssertionError("Method should be accessible", e);
		}
	}

	public static void notYetSet() {
		throw new IllegalStateException("Cannot invoke method on object that is not fully constructed. Use the @Now annotation on your method's parameter to indicate that you need to call a method on it");
	}

	@SuppressWarnings("unused")
	public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type) {
		return requireNonNull(callSites.remove(name), ()->"CallSite not found: \"" + name + "\"");
	}

	private static final class CustomClassLoader extends ClassLoader {
		CustomClassLoader(ClassLoader parentClassLoader) {
			super(parentClassLoader);
		}

		public Class<?> loadThemBytes(String dottyName, byte[] b) {
			return defineClass(dottyName, b, 0, b.length);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(ProxyFactory.class);
}
