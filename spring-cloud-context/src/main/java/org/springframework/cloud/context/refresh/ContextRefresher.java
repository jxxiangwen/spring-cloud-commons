package org.springframework.cloud.context.refresh;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.Banner.Mode;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.cloud.bootstrap.BootstrapApplicationListener;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.cloud.logging.LoggingRebinder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.context.support.StandardServletEnvironment;

/**
 * @author Dave Syer
 * @author Venil Noronha
 */
public class ContextRefresher {

    private static final String REFRESH_ARGS_PROPERTY_SOURCE = "refreshArgs";

    /**
     * 标准属性源 systemProperties,systemEnvironment,jndiProperties,
     * servletConfigInitParams,servletContextInitParams
     * 标准属性源指的是 StandardEnvironment 和 StandardServletEnvironment，
     * 前者会注册系统变量（System Properties）和环境变量（System Environment），
     * 后者会注册 Servlet 环境下的 Servlet Context 和 Servlet Config 的初始参数
     * （Init Params）和 JNDI 的属性。
     */
    private Set<String> standardSources = new HashSet<String>(
            Arrays.asList(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    StandardServletEnvironment.JNDI_PROPERTY_SOURCE_NAME,
                    StandardServletEnvironment.SERVLET_CONFIG_PROPERTY_SOURCE_NAME,
                    StandardServletEnvironment.SERVLET_CONTEXT_PROPERTY_SOURCE_NAME));

    private ConfigurableApplicationContext context;
    /**
     * 所有 @RefreshScope 的 Bean 都是延迟加载的，只有在第一次访问时才会初始化
     * 刷新 Bean 也是同理，下次访问时会创建一个新的对象
     */
    private RefreshScope scope;

    public ContextRefresher(ConfigurableApplicationContext context, RefreshScope scope) {
        this.context = context;
        this.scope = scope;
    }

    public synchronized Set<String> refresh() {
        // 抽取出所有非标准属性源
        Map<String, Object> before = extract(
                this.context.getEnvironment().getPropertySources());
        // 创建一个新的springBoot应用并初始化
        addConfigFilesToEnvironment();
        // 查看改变的key
        Set<String> keys = changes(before,
                extract(this.context.getEnvironment().getPropertySources())).keySet();
        // 发布key改变
        // 1.重新绑定上下文中所有使用了 @ConfigurationProperties 注解的 Spring Bean。
        // 查看ConfigurationPropertiesRebinder
        // 2.如果 logging.level.* 配置发生了改变，重新设置日志级别。
        // 查看LoggingRebinder
        this.context.publishEvent(new EnvironmentChangeEvent(keys));
        this.scope.refreshAll();
        return keys;
    }

    private void addConfigFilesToEnvironment() {
        ConfigurableApplicationContext capture = null;
        try {
            StandardEnvironment environment = copyEnvironment(
                    this.context.getEnvironment());
            SpringApplicationBuilder builder = new SpringApplicationBuilder(Empty.class)
                    .bannerMode(Mode.OFF).web(false).environment(environment);
            // Just the listeners that affect the environment (e.g. excluding logging
            // listener because it has side effects)
            builder.application()
                    .setListeners(Arrays.asList(new BootstrapApplicationListener(),
                            new ConfigFileApplicationListener()));
            // 新建一个springApplication运行
            capture = builder.run();
            if (environment.getPropertySources().contains(REFRESH_ARGS_PROPERTY_SOURCE)) {
                environment.getPropertySources().remove(REFRESH_ARGS_PROPERTY_SOURCE);
            }
            MutablePropertySources target = this.context.getEnvironment()
                    .getPropertySources();
            String targetName = null;
            for (PropertySource<?> source : environment.getPropertySources()) {
                String name = source.getName();
                if (target.contains(name)) {
                    targetName = name;
                }
                if (!this.standardSources.contains(name)) {
                    if (target.contains(name)) {
                        target.replace(name, source);
                    } else {
                        if (targetName != null) {
                            target.addAfter(targetName, source);
                        } else {
                            if (target.contains("defaultProperties")) {
                                target.addBefore("defaultProperties", source);
                            } else {
                                target.addLast(source);
                            }
                        }
                    }
                }
            }
        } finally {
            ConfigurableApplicationContext closeable = capture;
            closeable.close();
        }

    }

    // Don't use ConfigurableEnvironment.merge() in case there are clashes with property
    // source names
    private StandardEnvironment copyEnvironment(ConfigurableEnvironment input) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources capturedPropertySources = environment.getPropertySources();
        for (PropertySource<?> source : capturedPropertySources) {
            capturedPropertySources.remove(source.getName());
        }
        for (PropertySource<?> source : input.getPropertySources()) {
            capturedPropertySources.addLast(source);
        }
        environment.setActiveProfiles(input.getActiveProfiles());
        environment.setDefaultProfiles(input.getDefaultProfiles());
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("spring.jmx.enabled", false);
        map.put("spring.main.sources", "");
        capturedPropertySources
                .addFirst(new MapPropertySource(REFRESH_ARGS_PROPERTY_SOURCE, map));
        return environment;
    }

    private Map<String, Object> changes(Map<String, Object> before,
                                        Map<String, Object> after) {
        Map<String, Object> result = new HashMap<String, Object>();
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) {
                result.put(key, null);
            } else if (!equal(before.get(key), after.get(key))) {
                result.put(key, after.get(key));
            }
        }
        for (String key : after.keySet()) {
            if (!before.containsKey(key)) {
                result.put(key, after.get(key));
            }
        }
        return result;
    }

    private boolean equal(Object one, Object two) {
        if (one == null && two == null) {
            return true;
        }
        if (one == null || two == null) {
            return false;
        }
        return one.equals(two);
    }

    private Map<String, Object> extract(MutablePropertySources propertySources) {
        Map<String, Object> result = new HashMap<String, Object>();
        List<PropertySource<?>> sources = new ArrayList<PropertySource<?>>();
        for (PropertySource<?> source : propertySources) {
            sources.add(0, source);
        }
        for (PropertySource<?> source : sources) {
            if (!this.standardSources.contains(source.getName())) {
                extract(source, result);
            }
        }
        return result;
    }

    private void extract(PropertySource<?> parent, Map<String, Object> result) {
        if (parent instanceof CompositePropertySource) {
            try {
                List<PropertySource<?>> sources = new ArrayList<PropertySource<?>>();
                for (PropertySource<?> source : ((CompositePropertySource) parent)
                        .getPropertySources()) {
                    sources.add(0, source);
                }
                for (PropertySource<?> source : sources) {
                    extract(source, result);
                }
            } catch (Exception e) {
                return;
            }
        } else if (parent instanceof EnumerablePropertySource) {
            for (String key : ((EnumerablePropertySource<?>) parent).getPropertyNames()) {
                result.put(key, parent.getProperty(key));
            }
        }
    }

    @Configuration
    protected static class Empty {

    }

}
