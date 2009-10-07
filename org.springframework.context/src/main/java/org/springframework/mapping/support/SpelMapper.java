/*
 * Copyright 2002-2009 the original author or authors.
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
package org.springframework.mapping.support;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConverterRegistry;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.convert.support.GenericConverter;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParserConfiguration;
import org.springframework.mapping.Mapper;
import org.springframework.mapping.MappingException;
import org.springframework.mapping.MappingFailure;

/**
 * A generic object mapper implementation based on the Spring Expression Language (SpEL).
 * @author Keith Donald 
 * @see #setAutoMappingEnabled(boolean)
 * @see #addMapping(String)
 * @see #addMapping(String, String)
 */
public class SpelMapper implements Mapper<Object, Object> {

	private static final Log logger = LogFactory.getLog(SpelMapper.class);

	private static final MappableTypeFactory mappableTypeFactory = new MappableTypeFactory();

	private static final SpelExpressionParser sourceExpressionParser = new SpelExpressionParser();

	private static final SpelExpressionParser targetExpressionParser = new SpelExpressionParser(
			SpelExpressionParserConfiguration.CreateObjectIfAttemptToReferenceNull
					| SpelExpressionParserConfiguration.GrowListsOnIndexBeyondSize);

	private final Set<SpelMapping> mappings = new LinkedHashSet<SpelMapping>();

	private boolean autoMappingEnabled = true;

	private MappingConversionService conversionService = new MappingConversionService();

	public void setAutoMappingEnabled(boolean autoMappingEnabled) {
		this.autoMappingEnabled = autoMappingEnabled;
	}

	public MappingConfiguration addMapping(String fieldExpression) {
		return addMapping(fieldExpression, fieldExpression);
	}

	public ConverterRegistry getConverterRegistry() {
		return conversionService;
	}

	public MappingConfiguration addMapping(String sourceFieldExpression, String targetFieldExpression) {
		Expression sourceExp;
		try {
			sourceExp = sourceExpressionParser.parseExpression(sourceFieldExpression);
		} catch (ParseException e) {
			throw new IllegalArgumentException("The mapping source '" + sourceFieldExpression
					+ "' is not a parseable value expression", e);
		}
		Expression targetExp;
		try {
			targetExp = targetExpressionParser.parseExpression(targetFieldExpression);
		} catch (ParseException e) {
			throw new IllegalArgumentException("The mapping target '" + targetFieldExpression
					+ "' is not a parseable property expression", e);
		}
		SpelMapping mapping = new SpelMapping(sourceExp, targetExp);
		this.mappings.add(mapping);
		return mapping;
	}

	public Object map(Object source, Object target) {
		try {
			MappingContextHolder.push(source);
			EvaluationContext sourceContext = getEvaluationContext(source);
			EvaluationContext targetContext = getEvaluationContext(target);
			List<MappingFailure> failures = new LinkedList<MappingFailure>();
			for (SpelMapping mapping : this.mappings) {
				doMap(mapping, sourceContext, targetContext, failures);
			}
			Set<SpelMapping> autoMappings = getAutoMappings(sourceContext, targetContext);
			for (SpelMapping mapping : autoMappings) {
				doMap(mapping, sourceContext, targetContext, failures);
			}
			if (!failures.isEmpty()) {
				throw new MappingException(failures);
			}
			return target;
		} finally {
			MappingContextHolder.pop();
		}
	}

	private EvaluationContext getEvaluationContext(Object object) {
		return mappableTypeFactory.getMappableType(object).getEvaluationContext(object, this.conversionService);
	}

	private void doMap(SpelMapping mapping, EvaluationContext sourceContext, EvaluationContext targetContext,
			List<MappingFailure> failures) {
		if (logger.isDebugEnabled()) {
			logger.debug(MappingContextHolder.getLevel() + mapping);
		}
		mapping.map(sourceContext, targetContext, failures);
	}

	private Set<SpelMapping> getAutoMappings(EvaluationContext sourceContext, EvaluationContext targetContext) {
		if (this.autoMappingEnabled) {
			Set<SpelMapping> autoMappings = new LinkedHashSet<SpelMapping>();
			Set<String> sourceFields = getMappableFields(sourceContext.getRootObject().getValue());
			for (String field : sourceFields) {
				if (!explicitlyMapped(field)) {
					Expression sourceExpression;
					Expression targetExpression;
					try {
						sourceExpression = sourceExpressionParser.parseExpression(field);
					} catch (ParseException e) {
						throw new IllegalArgumentException("The mapping source '" + field
								+ "' is not a parseable value expression", e);
					}
					try {
						targetExpression = targetExpressionParser.parseExpression(field);
					} catch (ParseException e) {
						throw new IllegalArgumentException("The mapping target '" + field
								+ "' is not a parseable value expression", e);
					}
					try {
						if (targetExpression.isWritable(targetContext)) {
							autoMappings.add(new SpelMapping(sourceExpression, targetExpression));
						}
					} catch (EvaluationException e) {

					}
				}
			}
			return autoMappings;
		} else {
			return Collections.emptySet();
		}
	}

	private Set<String> getMappableFields(Object object) {
		return mappableTypeFactory.getMappableType(object).getFields(object);
	}

	private boolean explicitlyMapped(String field) {
		for (SpelMapping mapping : this.mappings) {
			if (mapping.getSourceExpressionString().startsWith(field)) {
				return true;
			}
		}
		return false;
	}

	private static class MappingConversionService extends DefaultConversionService {

		@Override
		protected GenericConverter getDefaultConverter(TypeDescriptor sourceType, TypeDescriptor targetType) {
			return new MapperConverter(new SpelMapper());
		}

	}

	private static class MappableTypeFactory {

		private static final MapMappableType MAP_MAPPABLE_TYPE = new MapMappableType();

		private static final BeanMappableType BEAN_MAPPABLE_TYPE = new BeanMappableType();

		@SuppressWarnings("unchecked")
		public <T> MappableType<T> getMappableType(T object) {
			if (object instanceof Map<?, ?>) {
				return (MappableType<T>) MAP_MAPPABLE_TYPE;
			} else {
				return (MappableType<T>) BEAN_MAPPABLE_TYPE;
			}
		}
	}

}
