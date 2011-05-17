/*
 * Copyright 2010-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cache.interceptor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;

/**
 * Abstract implementation of {@link CacheOperation} that caches
 * attributes for methods and implements a fallback policy: 1. specific target
 * method; 2. target class; 3. declaring method; 4. declaring class/interface.
 *
 * <p>Defaults to using the target class's caching attribute if none is
 * associated with the target method. Any caching attribute associated with
 * the target method completely overrides a class caching attribute.
 * If none found on the target class, the interface that the invoked method
 * has been called through (in case of a JDK proxy) will be checked.
 *
 * <p>This implementation caches attributes by method after they are first used.
 * If it is ever desirable to allow dynamic changing of cacheable attributes
 * (which is very unlikely), caching could be made configurable.

 * @author Costin Leau
 * @see org.springframework.transaction.interceptor.AbstractFallbackTransactionAttributeSource
 */
public abstract class AbstractFallbackCacheOperationSource implements CacheOperationSource {

	/**
	 * Canonical value held in cache to indicate no caching attribute was
	 * found for this method and we don't need to look again.
	 */
	private final static CacheOperation NULL_CACHING_ATTRIBUTE = new CacheUpdateOperation();

	/**
	 * Logger available to subclasses.
	 * <p>As this base class is not marked Serializable, the logger will be recreated
	 * after serialization - provided that the concrete subclass is Serializable.
	 */
	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * Cache of CacheOperationDefinitions, keyed by DefaultCacheKey (Method + target Class).
	 * <p>As this base class is not marked Serializable, the cache will be recreated
	 * after serialization - provided that the concrete subclass is Serializable.
	 */
	final Map<Object, CacheOperation> attributeCache = new ConcurrentHashMap<Object, CacheOperation>();

	/**
	 * Determine the caching attribute for this method invocation.
	 * <p>Defaults to the class's caching attribute if no method attribute is found.
	 * @param method the method for the current invocation (never <code>null</code>)
	 * @param targetClass the target class for this invocation (may be <code>null</code>)
	 * @return {@link CacheOperation} for this method, or <code>null</code> if the method
	 * is not cacheable
	 */
	public CacheOperation getCacheOperation(Method method, Class<?> targetClass) {
		// First, see if we have a cached value.
		Object cacheKey = getCacheKey(method, targetClass);
		CacheOperation cached = this.attributeCache.get(cacheKey);
		if (cached != null) {
			if (cached == NULL_CACHING_ATTRIBUTE) {
				return null;
			}
			// Value will either be canonical value indicating there is no caching attribute,
			// or an actual caching attribute.
			return cached;
		}
		else {
			// We need to work it out.
			CacheOperation cacheDef = computeCacheOperationDefinition(method, targetClass);
			// Put it in the cache.
			if (cacheDef == null) {
				this.attributeCache.put(cacheKey, NULL_CACHING_ATTRIBUTE);
			}
			else {
				if (logger.isDebugEnabled()) {
					logger.debug("Adding cacheable method '" + method.getName() + "' with attribute: " + cacheDef);
				}
				this.attributeCache.put(cacheKey, cacheDef);
			}
			return cacheDef;
		}
	}

	/**
	 * Determine a cache key for the given method and target class.
	 * <p>Must not produce same key for overloaded methods.
	 * Must produce same key for different instances of the same method.
	 * @param method the method (never <code>null</code>)
	 * @param targetClass the target class (may be <code>null</code>)
	 * @return the cache key (never <code>null</code>)
	 */
	protected Object getCacheKey(Method method, Class<?> targetClass) {
		return new DefaultCacheKey(method, targetClass);
	}

	/**
	 * Same signature as {@link #getTransactionAttribute}, but doesn't cache the result.
	 * {@link #getTransactionAttribute} is effectively a caching decorator for this method.
	 * @see #getTransactionAttribute
	 */
	private CacheOperation computeCacheOperationDefinition(Method method, Class<?> targetClass) {
		// Don't allow no-public methods as required.
		if (allowPublicMethodsOnly() && !Modifier.isPublic(method.getModifiers())) {
			return null;
		}

		// The method may be on an interface, but we need attributes from the target class.
		// If the target class is null, the method will be unchanged.
		Method specificMethod = ClassUtils.getMostSpecificMethod(method, targetClass);
		// If we are dealing with method with generic parameters, find the original method.
		specificMethod = BridgeMethodResolver.findBridgedMethod(specificMethod);

		// First try is the method in the target class.
		CacheOperation opDef = findCacheOperation(specificMethod);
		if (opDef != null) {
			return opDef;
		}

		// Second try is the caching operation on the target class.
		opDef = findCacheOperation(specificMethod.getDeclaringClass());
		if (opDef != null) {
			return opDef;
		}

		if (specificMethod != method) {
			// Fall back is to look at the original method.
			opDef = findCacheOperation(method);
			if (opDef != null) {
				return opDef;
			}
			// Last fall back is the class of the original method.
			return findCacheOperation(method.getDeclaringClass());
		}
		return null;
	}

	/**
	 * Subclasses need to implement this to return the caching attribute
	 * for the given method, if any.
	 * @param method the method to retrieve the attribute for
	 * @return all caching attribute associated with this method
	 * (or <code>null</code> if none)
	 */
	protected abstract CacheOperation findCacheOperation(Method method);

	/**
	 * Subclasses need to implement this to return the caching attribute
	 * for the given class, if any.
	 * @param clazz the class to retrieve the attribute for
	 * @return all caching attribute associated with this class
	 * (or <code>null</code> if none)
	 */
	protected abstract CacheOperation findCacheOperation(Class<?> clazz);

	/**
	 * Should only public methods be allowed to have caching semantics?
	 * <p>The default implementation returns <code>false</code>.
	 */
	protected boolean allowPublicMethodsOnly() {
		return false;
	}

	/**
	 * Default cache key for the CacheOperationDefinition cache.
	 */
	private static class DefaultCacheKey {

		private final Method method;

		private final Class<?> targetClass;

		public DefaultCacheKey(Method method, Class<?> targetClass) {
			this.method = method;
			this.targetClass = targetClass;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof DefaultCacheKey)) {
				return false;
			}
			DefaultCacheKey otherKey = (DefaultCacheKey) other;
			return (this.method.equals(otherKey.method) && ObjectUtils.nullSafeEquals(this.targetClass,
					otherKey.targetClass));
		}

		@Override
		public int hashCode() {
			return this.method.hashCode() * 29 + (this.targetClass != null ? this.targetClass.hashCode() : 0);
		}
	}
}