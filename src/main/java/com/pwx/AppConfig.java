package com.pwx;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * @author pengweixin
 */
//@Component
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
}