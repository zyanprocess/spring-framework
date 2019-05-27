/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.reactive.result.view.freemarker;

import java.io.IOException;
import java.util.HashMap;
import java.util.Properties;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.junit.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;
import org.springframework.ui.freemarker.SpringTemplateLoader;

import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Juergen Hoeller
 * @author Issam El-atif
 */
public class FreeMarkerConfigurerTests {

	@Test
	public void freeMarkerConfigurerDefaultEncoding() throws IOException, TemplateException {
		FreeMarkerConfigurer configurer = new FreeMarkerConfigurer();
		configurer.afterPropertiesSet();
		Configuration cfg = configurer.getConfiguration();
		assertEquals("UTF-8", cfg.getDefaultEncoding());
	}

	@Test
	public void freeMarkerConfigurerWithConfigLocation() {
		FreeMarkerConfigurer configurer = new FreeMarkerConfigurer();
		configurer.setConfigLocation(new FileSystemResource("myprops.properties"));
		Properties props = new Properties();
		props.setProperty("myprop", "/mydir");
		configurer.setFreemarkerSettings(props);
		assertThatIOException().isThrownBy(
				configurer::afterPropertiesSet);
	}

	@Test
	public void freeMarkerConfigurerWithResourceLoaderPath() throws Exception {
		FreeMarkerConfigurer configurer = new FreeMarkerConfigurer();
		configurer.setTemplateLoaderPath("file:/mydir");
		configurer.afterPropertiesSet();
		Configuration cfg = configurer.getConfiguration();
		assertTrue(cfg.getTemplateLoader() instanceof SpringTemplateLoader);
	}

	@Test
	@SuppressWarnings("rawtypes")
	public void freeMarkerConfigurerWithNonFileResourceLoaderPath() throws Exception {
		FreeMarkerConfigurer configurer = new FreeMarkerConfigurer();
		configurer.setTemplateLoaderPath("file:/mydir");
		Properties settings = new Properties();
		settings.setProperty("localized_lookup", "false");
		configurer.setFreemarkerSettings(settings);
		configurer.setResourceLoader(new ResourceLoader() {
			@Override
			public Resource getResource(String location) {
				if (!("file:/mydir".equals(location) || "file:/mydir/test".equals(location))) {
					throw new IllegalArgumentException(location);
				}
				return new ByteArrayResource("test".getBytes(), "test");
			}
			@Override
			public ClassLoader getClassLoader() {
				return getClass().getClassLoader();
			}
		});
		configurer.afterPropertiesSet();
		assertThat(configurer.getConfiguration(), instanceOf(Configuration.class));
		Configuration fc = configurer.getConfiguration();
		Template ft = fc.getTemplate("test");
		assertEquals("test", FreeMarkerTemplateUtils.processTemplateIntoString(ft, new HashMap()));
	}

	@Test  // SPR-12448
	public void freeMarkerConfigurationAsBean() {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		RootBeanDefinition loaderDef = new RootBeanDefinition(SpringTemplateLoader.class);
		loaderDef.getConstructorArgumentValues().addGenericArgumentValue(new DefaultResourceLoader());
		loaderDef.getConstructorArgumentValues().addGenericArgumentValue("/freemarker");
		RootBeanDefinition configDef = new RootBeanDefinition(Configuration.class);
		configDef.getPropertyValues().add("templateLoader", loaderDef);
		beanFactory.registerBeanDefinition("freeMarkerConfig", configDef);
		beanFactory.getBean(Configuration.class);
	}

}
