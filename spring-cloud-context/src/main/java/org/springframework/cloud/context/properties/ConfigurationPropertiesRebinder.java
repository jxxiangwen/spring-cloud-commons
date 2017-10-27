/*
 * Copyright 2013-2014 the original author or authors.
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
package org.springframework.cloud.context.properties;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;

/**
 * 关注EnvironmentChangeEvent事件,重新绑定所有使用了ConfigurationProperties注解的bean
 * Listens for {@link EnvironmentChangeEvent} and rebinds beans that were bound to the
 * {@link Environment} using {@link ConfigurationProperties
 * <code>@ConfigurationProperties</code>}. When these beans are re-bound and
 * re-initialized the changes are available immediately to any component that is using the
 * <code>@ConfigurationProperties</code> bean.
 *
 * @author Dave Syer
 * @see RefreshScope for a deeper and optionally more focused refresh of bean components
 */
@Component
@ManagedResource
public class ConfigurationPropertiesRebinder
        implements ApplicationContextAware, ApplicationListener<EnvironmentChangeEvent> {

    /**
     * ConfigurationPropertiesBeans 需要一个 ConfigurationBeanFactoryMetaData，
     * 这个类逻辑很简单，它是一个 BeanFactoryPostProcessor 的实现，将所有的 Bean 都存在了内部的一个 Map 中。
     */
    private ConfigurationPropertiesBeans beans;

    private ConfigurationPropertiesBindingPostProcessor binder;

    private ApplicationContext applicationContext;

    private Map<String, Exception> errors = new ConcurrentHashMap<>();

    public ConfigurationPropertiesRebinder(
            ConfigurationPropertiesBindingPostProcessor binder,
            ConfigurationPropertiesBeans beans) {
        this.binder = binder;
        this.beans = beans;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext)
            throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * A map of bean name to errors when instantiating the bean.
     *
     * @return the errors accumulated since the latest destroy
     */
    public Map<String, Exception> getErrors() {
        return this.errors;
    }

    @ManagedOperation
    public void rebind() {
        this.errors.clear();
        for (String name : this.beans.getBeanNames()) {
            rebind(name);
        }
    }

    @ManagedOperation
    public boolean rebind(String name) {
        if (!this.beans.getBeanNames().contains(name)) {
            return false;
        }
        if (this.applicationContext != null) {
            try {
                Object bean = this.applicationContext.getBean(name);
                if (AopUtils.isCglibProxy(bean)) {
                    bean = getTargetObject(bean);
                }
                this.binder.postProcessBeforeInitialization(bean, name);
                // 初始化bean
                // 1. applyBeanPostProcessorsBeforeInitialization：调用所有 BeanPostProcessor
                // 的 postProcessBeforeInitialization 方法。
                // 2. invokeInitMethods：如果 Bean 继承了 InitializingBean，
                // 执行 afterPropertiesSet 方法，或是如果 Bean 指定了 init-method 属性，如果有则调用对应方法
                // 3. applyBeanPostProcessorsAfterInitialization：调用所有
                // BeanPostProcessor 的 postProcessAfterInitialization 方法。
                this.applicationContext.getAutowireCapableBeanFactory()
                        .initializeBean(bean, name);
                return true;
            } catch (RuntimeException e) {
                this.errors.put(name, e);
                throw e;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> T getTargetObject(Object candidate) {
        try {
            if (AopUtils.isAopProxy(candidate) && (candidate instanceof Advised)) {
                return (T) ((Advised) candidate).getTargetSource().getTarget();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to unwrap proxied object", ex);
        }
        return (T) candidate;
    }

    @ManagedAttribute
    public Set<String> getBeanNames() {
        return new HashSet<String>(this.beans.getBeanNames());
    }

    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        rebind();
    }

}
