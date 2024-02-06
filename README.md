[参考文档](https://mp.weixin.qq.com/s?__biz=Mzg5MDY1NzI0MQ==&mid=2247485174&idx=1&sn=607331d96f3b642b63a82dca7ec6d37b&chksm=cfd80640f8af8f5654d146673793547fb68ccce0afb8455269bf461af7289e606f54063f225b&token=495172829&lang=zh_CN#rd)

# 1、概述

我们平时在Spring的开发工作中，基本都会使用配置注解，尤其以`@Componen`t及`@Configuration`为主，当然在Spring中还可以使用其他的注解来标注一个类为配置类，这是广义上的配置类概念，但是这里我们只讨论@Component和@Configuration，因为与我们的开发工作关联比较紧密

虽然用，都会用，但是这两者有什么区别，可能有很多同学没有仔细研究过，今天我们接着学习源码的机会，来更加深入的了解这两个注解。



# 2、是什么

我们先来看一下定义

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Indexed
public @interface Component {

   /**
    * The value may indicate a suggestion for a logical component name,
    * to be turned into a Spring bean in case of an autodetected component.
    * @return the suggested component name, if any (or empty String otherwise)
    */
   String value() default "";

}
```

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Configuration {

	@AliasFor(annotation = Component.class)
	String value() default "";

	boolean proxyBeanMethods() default true;

}
```

可以看到，`@Configuration` 注解本质上还是` @Component`，因此 `@ComponentScan` 能扫描到@Configuration 注解的类。



# 3、怎么用

先随便建两个实体类

```java
public class Eoo {
}

public class Foo {
}
```

然后我们使用`@Component`实现配置类

```java
@Component
public class AppConfig {
    @Bean
    public Foo foo() {
        System.out.println("foo() invoked...");
        Foo foo = new Foo();
        System.out.println("foo() 方法的 foo hashcode: " + foo.hashCode());
        return foo;
    }

    @Bean
    public Eoo eoo() {
        System.out.println("eoo() invoked...");
        Foo foo = foo();
        System.out.println("eoo() 方法的 foo hashcode: " + foo.hashCode());
        return new Eoo();
    }
}
```

运行结果：

![image-20240206104831369](./configuration-component.assets\image-20240206104831369.png)

从结果可知，`foo()`方法执行了两次：

- 一次是bean方法执行的，
- 一次是`eoo()`调用执行的

所以两次生成的`foo`对象是不一样的。

我们再来看看使用`@Configuration`的效果

```java
@Configuration
public class AppConfig {
    @Bean
    public Foo foo() {
        System.out.println("foo() invoked...");
        Foo foo = new Foo();
        System.out.println("foo() 方法的 foo hashcode: " + foo.hashCode());
        return foo;
    }

    @Bean
    public Eoo eoo() {
        System.out.println("eoo() invoked...");
        Foo foo = foo();
        System.out.println("eoo() 方法的 foo hashcode: " + foo.hashCode());
        return new Eoo();
    }

    public static void main(String[] args) {
        AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext(AppConfig.class);
        String[] beanDefinitionNames = applicationContext.getBeanDefinitionNames();
        // 遍历Spring容器中的beanName
        for (String beanDefinitionName : beanDefinitionNames) {
            System.out.println(beanDefinitionName);
        }
    }
}
```

运行结果：

![image-20240206104737453](./configuration-component.assets\image-20240206104737453.png)

这里可以看到`foo()`方法只执行了一次，同时`eoo()`方法调用`foo()`生成的`foo`对象是同一个。

这里是他们两个的本质区别。



# 4、原理

我们先头脑风暴一下，`eoo()`方法中调用了`foo()`方法，很明显这个`foo()`这个方法就是会形成一个新对象

但是我们从@Configuration的运行结果中可以看到，`foo`只被初始化了一次。也就是说，它并没有再一次被初始化

再深入一点，没有被初始化，那就肯定调的不是原来的那个`foo()`方法了，因为如果是调了，肯定有日志打印出来了。

那在Spring中，拿实例，除了自己初始化的，剩下的肯定都是要到Spring容器中去获取了。

也就是说，我们在调用`foo()`方法的时候去容器中获取一下`foo`这个Bean。这是什么，这是**代理**啊

换句话说，@Configuration标注把我们调用的`eoo()`和`foo()`方法，包括`AppConfig`都被Spring代理了。

可能这么说，还是很模糊，我们深入到源码去看看，接下来也会涉及到Spring的生命周期，可能会有有点复杂。

我们从`AnnotationConfigApplicationContext`的构造方法进去，这里是从给定的组件类派生bean定义并自动刷新上下文。

```java
/**
 * AnnotationConfigApplicationContext是Spring框架中使用注解方式进行配置时的一种应用上下文，用于启动容器、注册配置类或者组件，并进行Bean的初始化与依赖 
*/
public AnnotationConfigApplicationContext(Class<?>... componentClasses) {
    this();
    register(componentClasses);
    refresh();
}
```

`register`方法在这个上下文中扮演了非常重要的角色，主要用于注册配置类或组件到Spring容器中。

当通过`AnnotationConfigApplicationContext`实例化上下文时，可以不立即传入配置类，而是通过调用`register`方法来动态添加配置类。

然后来到我们的`refresh`方法，Spring容器会处理所有注册的类，包括其中定义的Bean和组件扫描、依赖注入等。

继续点进去

```java
@Override
public void refresh() throws BeansException, IllegalStateException {
    synchronized (this.startupShutdownMonitor) {
        StartupStep contextRefresh = this.applicationStartup.start("spring.context.refresh");

        // Prepare this context for refreshing.
        prepareRefresh();

        // Tell the subclass to refresh the internal bean factory.
        ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();

        // Prepare the bean factory for use in this context.
        prepareBeanFactory(beanFactory);

        try {
            // Allows post-processing of the bean factory in context subclasses.
            postProcessBeanFactory(beanFactory);

            StartupStep beanPostProcess = this.applicationStartup.start("spring.context.beans.post-process");
            // Invoke factory processors registered as beans in the context.
            invokeBeanFactoryPostProcessors(beanFactory);
            //...
        }
        //....
    }
}
```

`refresh()`有点复杂，我们这一期就先看前面的这一部分

其实从代码中给的注释，都可以很清楚看到，每一步是在做什么，这一部分不是今天的重点，简单过一下

1. **准备刷新**：这一步会进行一些预处理工作，比如对系统属性或环境变量进行准备和验证。
2. **生成并配置`BeanFactory`**：配置工厂的标准上下文特征，比如上下文的`ClassLoader`和后置处理器。到这里所有bean已经加载了定义，但是还没有实例化任何bean
3. **Bean定义的后处理**：这一步允许已注册的`BeanFactoryPostProcessor`对容器的Bean定义进行修改。例如，`@PropertySource`注解处理是在这个步骤中实现的。
4. **初始化BeanFactory**：对所有的单例Bean进行初始化，包括构建和依赖注入。这一步还会触发`BeanFactoryPostProcessor`的执行。

我们今天的重点，就是在这里，也就是对应的`invokeBeanFactoryPostProcessors()`方法

```java
protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
    PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(beanFactory, getBeanFactoryPostProcessors());

    // Detect a LoadTimeWeaver and prepare for weaving, if found in the meantime
    // (e.g. through an @Bean method registered by ConfigurationClassPostProcessor)
    if (!NativeDetector.inNativeImage() && beanFactory.getTempClassLoader() == null && beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {
        beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory));
        beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader()));
    }
}
```

PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors()这个方法还是比较复杂的，它主要处理所有实现了BeanFactoryPostProcessor及BeanDefinitionRegistryPostProcessor的类

我们点进去继续看

```java
public static void invokeBeanFactoryPostProcessors(
    ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {
    Set<String> processedBeans = new HashSet<>();
	// beanFactory 是DefaultListableBeanFactory，
    // DefaultListableBeanFactory是ConfigurableListableBeanFactory的实现类
    // DefaultListableBeanFactory继承BeanDefinitionRegistry
    if (beanFactory instanceof BeanDefinitionRegistry) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
        List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();
		//这里beanFactoryPostProcessors为空
        for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
            if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
                BeanDefinitionRegistryPostProcessor registryProcessor =
                    (BeanDefinitionRegistryPostProcessor) postProcessor;
                registryProcessor.postProcessBeanDefinitionRegistry(registry);
                registryProcessors.add(registryProcessor);
            }
            else {
                regularPostProcessors.add(postProcessor);
            }
        }

        // 这一块是处理BeanDefinitionRegistryPostProcessors的
        // 把实现了PriorityOrdered, Ordered和其他的分开处理
        // 到这里FactoryBeans还未处理
        // Do not initialize FactoryBeans here: We need to leave all regular beans
        // uninitialized to let the bean factory post-processors apply to them!
        // Separate between BeanDefinitionRegistryPostProcessors that implement
        // PriorityOrdered, Ordered, and the rest.
        List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

        // First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
        String[] postProcessorNames =
            beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
        for (String ppName : postProcessorNames) {
            if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
                currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
                processedBeans.add(ppName);
            }
        }
        sortPostProcessors(currentRegistryProcessors, beanFactory);
        registryProcessors.addAll(currentRegistryProcessors);
        invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
        currentRegistryProcessors.clear();

        // Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
        postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
        for (String ppName : postProcessorNames) {
            if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
                currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
                processedBeans.add(ppName);
            }
        }
        sortPostProcessors(currentRegistryProcessors, beanFactory);
        registryProcessors.addAll(currentRegistryProcessors);
        invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
        currentRegistryProcessors.clear();

        // Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
        boolean reiterate = true;
        while (reiterate) {
            reiterate = false;
            postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
            for (String ppName : postProcessorNames) {
                if (!processedBeans.contains(ppName)) {
                    currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
                    processedBeans.add(ppName);
                    reiterate = true;
                }
            }
            sortPostProcessors(currentRegistryProcessors, beanFactory);
            registryProcessors.addAll(currentRegistryProcessors);
            invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
            currentRegistryProcessors.clear();
        }
		//现在，调用到目前为止处理过的所有处理器的postProcessBeanFactory回调。
        // Now, invoke the postProcessBeanFactory callback of all processors handled so far.
        invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
        invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
    }

    else {
        // Invoke factory processors registered with the context instance.
        invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
    }

    // Do not initialize FactoryBeans here: We need to leave all regular beans
    // uninitialized to let the bean factory post-processors apply to them!
    String[] postProcessorNames =
        beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

    // Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
    // Ordered, and the rest.
    List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
    List<String> orderedPostProcessorNames = new ArrayList<>();
    List<String> nonOrderedPostProcessorNames = new ArrayList<>();
    for (String ppName : postProcessorNames) {
        if (processedBeans.contains(ppName)) {
            // skip - already processed in first phase above
        }
        else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
            priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
        }
        else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
            orderedPostProcessorNames.add(ppName);
        }
        else {
            nonOrderedPostProcessorNames.add(ppName);
        }
    }

    // First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
    sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
    invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

    // Next, invoke the BeanFactoryPostProcessors that implement Ordered.
    List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
    for (String postProcessorName : orderedPostProcessorNames) {
        orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
    }
    sortPostProcessors(orderedPostProcessors, beanFactory);
    invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

    // Finally, invoke all other BeanFactoryPostProcessors.
    List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
    for (String postProcessorName : nonOrderedPostProcessorNames) {
        nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
    }
    invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

    // Clear cached merged bean definitions since the post-processors might have
    // modified the original metadata, e.g. replacing placeholders in values...
    beanFactory.clearMetadataCache();
}
```

看完了之后，我们发现，这里面其实就是两个最重要的方法

- invokeBeanDefinitionRegistryPostProcessors()
- invokeBeanFactoryPostProcessors();

这是处理所有实现了BeanFactoryPostProcessor及BeanDefinitionRegistryPostProcessor的类的核心方法

我们先看invokeBeanDefinitionRegistryPostProcessors()，点进去

```java
private static void invokeBeanDefinitionRegistryPostProcessors(
    Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry, ApplicationStartup applicationStartup) {

    for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
        StartupStep postProcessBeanDefRegistry = applicationStartup.start("spring.context.beandef-registry.post-process")
            .tag("postProcessor", postProcessor::toString);
        postProcessor.postProcessBeanDefinitionRegistry(registry);
        postProcessBeanDefRegistry.end();
    }
}
```

再到postProcessBeanDefinitionRegistry()方法

```java
@Override
public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    int registryId = System.identityHashCode(registry);
    if (this.registriesPostProcessed.contains(registryId)) {
        throw new IllegalStateException(
            "postProcessBeanDefinitionRegistry already called on this post-processor against " + registry);
    }
    if (this.factoriesPostProcessed.contains(registryId)) {
        throw new IllegalStateException(
            "postProcessBeanFactory already called on this post-processor against " + registry);
    }
    this.registriesPostProcessed.add(registryId);

    processConfigBeanDefinitions(registry);
}

@Override
public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    int factoryId = System.identityHashCode(beanFactory);
    if (this.factoriesPostProcessed.contains(factoryId)) {
        throw new IllegalStateException(
            "postProcessBeanFactory already called on this post-processor against " + beanFactory);
    }
    this.factoriesPostProcessed.add(factoryId);
    if (!this.registriesPostProcessed.contains(factoryId)) {
        // BeanDefinitionRegistryPostProcessor hook apparently not supported...
        // Simply call processConfigurationClasses lazily at this point then.
        processConfigBeanDefinitions((BeanDefinitionRegistry) beanFactory);
    }

    enhanceConfigurationClasses(beanFactory);
    beanFactory.addBeanPostProcessor(new ImportAwareBeanPostProcessor(beanFactory));
}
```

这两个就是invokeBeanDefinitionRegistryPostProcessors()和invokeBeanFactoryPostProcessors()最终的处理。

继续往下

![image-20240206162811939](./configuration-component.assets\image-20240206162811939.png)

可以看到，我们自己的appConfig类也在。

我们来到checkConfigurationClassCandidate()

```java
public static boolean checkConfigurationClassCandidate(
    BeanDefinition beanDef, MetadataReaderFactory metadataReaderFactory) {

    String className = beanDef.getBeanClassName();
    if (className == null || beanDef.getFactoryMethodName() != null) {
        return false;
    }

    AnnotationMetadata metadata;
    if (beanDef instanceof AnnotatedBeanDefinition &&
        className.equals(((AnnotatedBeanDefinition) beanDef).getMetadata().getClassName())) {
        // Can reuse the pre-parsed metadata from the given BeanDefinition...
        metadata = ((AnnotatedBeanDefinition) beanDef).getMetadata();
    }
    //....

    Map<String, Object> config = metadata.getAnnotationAttributes(Configuration.class.getName());
    if (config != null && !Boolean.FALSE.equals(config.get("proxyBeanMethods"))) {
        beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL);
    }
    else if (config != null || isConfigurationCandidate(metadata)) {
        beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);
    }
    else {
        return false;
    }

    // It's a full or lite configuration candidate... Let's determine the order value, if any.
    Integer order = getOrder(metadata);
    if (order != null) {
        beanDef.setAttribute(ORDER_ATTRIBUTE, order);
    }

    return true;
}
```

这里我们看到两个配置

```java
public static final String CONFIGURATION_CLASS_FULL = "full";

public static final String CONFIGURATION_CLASS_LITE = "lite";
```

嗯？有意思，Spring把我们的配置类归位两类

如果是有Configuration.class标记的，为full

点进去isConfigurationCandidate()

```java
private static final Set<String> candidateIndicators = new HashSet<>(8);

static {
    candidateIndicators.add(Component.class.getName());
    candidateIndicators.add(ComponentScan.class.getName());
    candidateIndicators.add(Import.class.getName());
    candidateIndicators.add(ImportResource.class.getName());
}

public static boolean isConfigurationCandidate(AnnotationMetadata metadata) {
    // Do not consider an interface or an annotation...
    if (metadata.isInterface()) {
        return false;
    }

    // Any of the typical annotations found?
    for (String indicator : candidateIndicators) {
        if (metadata.isAnnotated(indicator)) {
            return true;
        }
    }

    // Finally, let's look for @Bean methods...
    return hasBeanMethods(metadata);
}
```

所以，当标注为Component， ComponentScan， Import， ImportResource则为lite

这是什么意思呢？？？我们来到enhanceConfigurationClasses()

![image-20240206164515816](./configuration-component.assets\image-20240206164515816.png)

![image-20240206165436371](./configuration-component.assets\image-20240206165436371.png)

如果是@Configuration，则会被put到configBeanDefs这个Map中

![image-20240206165951438](./configuration-component.assets\image-20240206165951438.png)

继续往下

```java
ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer();
for (Map.Entry<String, AbstractBeanDefinition> entry : configBeanDefs.entrySet()) {
    AbstractBeanDefinition beanDef = entry.getValue();
    // If a @Configuration class gets proxied, always proxy the target class
    beanDef.setAttribute(AutoProxyUtils.PRESERVE_TARGET_CLASS_ATTRIBUTE, Boolean.TRUE);
    // Set enhanced subclass of the user-specified bean class
    Class<?> configClass = beanDef.getBeanClass();
    Class<?> enhancedClass = enhancer.enhance(configClass, this.beanClassLoader);
    if (configClass != enhancedClass) {
        if (logger.isTraceEnabled()) {
            logger.trace(String.format("Replacing bean definition '%s' existing class '%s' with " +
                                       "enhanced class '%s'", entry.getKey(), configClass.getName(), enhancedClass.getName()));
        }
        beanDef.setBeanClass(enhancedClass);
    }
}
enhanceConfigClasses.tag("classCount", () -> String.valueOf(configBeanDefs.keySet().size())).end();
```

可以看到，appConfig就是在这个地方，被设置成了自动代理

```java
/**
 * Bean定义属性，该属性可能指示给定Bean是否应该被其目标类代理(如果它首先被代理)
 * 如果代理工厂为一个特定的bean构建了一个目标类代理，并且想要强制bean总是可以被强制转换到它的目标类
 * 那么代理工厂可以设置这个属性自动代理
 */
public static final String PRESERVE_TARGET_CLASS_ATTRIBUTE =
    Conventions.getQualifiedAttributeName(AutoProxyUtils.class, "preserveTargetClass");
```

到了这里，我们可以看到，标注为@Configuration的类，或者是说标注为CONFIGURATION_CLASS_FULL的类，都会被设置成自动代理。

到了这里，就和我们前面头脑风暴的内容呼应上了。

```java
public Class<?> enhance(Class<?> configClass, @Nullable ClassLoader classLoader) {
    //.....
    Class<?> enhancedClass = createClass(newEnhancer(configClass, classLoader));
    if (logger.isTraceEnabled()) {
        logger.trace(String.format("Successfully enhanced %s; enhanced class name is: %s",
                                   configClass.getName(), enhancedClass.getName()));
    }
    return enhancedClass;
}
```

```java
/**
	 * Creates a new CGLIB {@link Enhancer} instance.
	 */
private Enhancer newEnhancer(Class<?> configSuperClass, @Nullable ClassLoader classLoader) {
    Enhancer enhancer = new Enhancer();
    enhancer.setSuperclass(configSuperClass);
    enhancer.setInterfaces(new Class<?>[] {EnhancedConfiguration.class});
    enhancer.setUseFactory(false);
    enhancer.setNamingPolicy(SpringNamingPolicy.INSTANCE);
    enhancer.setStrategy(new BeanFactoryAwareGeneratorStrategy(classLoader));
    enhancer.setCallbackFilter(CALLBACK_FILTER);
    enhancer.setCallbackTypes(CALLBACK_FILTER.getCallbackTypes());
    return enhancer;
}
```

在这里创建了我们的动态代理类 xxxxBySpringCGLIB

到了这里，基本就是这两个类的最本质的区别了

在这里也能看到`@Configuration(proxyBeanMethods = false)`和`@Component`一样效果，都是LITE模式



# 5、总结

一句话概括就是 `@Configuration` 中所有带 `@Bean` 注解的方法都会被动态代理，因此调用该方法返回的都是同一个实例。