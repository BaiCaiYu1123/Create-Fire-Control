package com.hooya.stabilizedturret.content.controller;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.concurrent.Future;

public final class CannonReflectionChecks {
    private static final Object[] NONE = {};
    private static class Parent {
        private String hidden(String value) { return "parent:" + value; }
        private String retry() { return "fallback"; }
        public static String stat(String value) { return "static:" + value; }
    }
    private static final class Child extends Parent {
        int attempts;
        private String value() { return "child"; }
        private String overload(int value) { return "int:" + value; }
        private String overload(String value) { return "string:" + value; }
        private String nullable(int value) { throw new AssertionError("primitive null cannot invoke"); }
        private String nullable(String value) { return value == null ? "null" : value; }
        public String retry() { if (++attempts == 1) throw new IllegalStateException("transient"); return "recovered"; }
        private String stat(Object value) { return "instance"; }
    }
    private static final class Other {
        private String value() { return "other"; }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Child child = new Child(); Other other = new Other();
        require("child".equals(CannonReflection.invoke(child,"value")),"private member");
        require("parent:x".equals(CannonReflection.invoke(child,"hidden","x")),"private inherited member");
        require("int:4".equals(CannonReflection.invoke(child,"overload",4)),"primitive wrapper match");
        require("string:x".equals(CannonReflection.invoke(child,"overload","x")),"overload varies with argument type");
        require(CannonReflection.invoke(child,"overload",4L)==null,"retain strict wrapper compatibility");
        require("null".equals(CannonReflection.invoke(child,"nullable",(Object)null)),"null falls through primitive to reference");
        require("fallback".equals(CannonReflection.invoke(child,"retry")),"throwing child falls back to parent");
        require("recovered".equals(CannonReflection.invoke(child,"retry")),"transient failure is retried");
        require("static:x".equals(CannonReflection.invokeStatic(Child.class,"stat","x")),"static search tolerates instance overload");
        require("instance".equals(CannonReflection.invoke(child,"stat",new Object())),"instance receiver stays live");
        require("other".equals(CannonReflection.invoke(other,"value")),"different owner classes stay separate");
        require(CannonReflection.invoke(child,"missing")==null&&CannonReflection.invoke(child,"value",1)==null,"missing member and wrong arity");
        require(CannonReflection.invoke(null,"value")==null&&CannonReflection.invokeStatic(null,"value")==null,"optional owner absent");
        require("child".equals(CannonReflection.find(Child.class,"value",0).invoke(child)),"declared lookup reuses private member");
        require(CannonReflection.find(Other.class,"hidden",1)==null,"declared lookup miss");
        try (var pool=Executors.newFixedThreadPool(6)) {
            var jobs=new ArrayList<Future<?>>();
            for(int i=0;i<6;i++) jobs.add(pool.submit(()->{for(int n=0;n<10000;n++) {require("child".equals(CannonReflection.invoke(child,"value",NONE)),"concurrent child");require("other".equals(CannonReflection.invoke(other,"value",NONE)),"concurrent other");}}));
            for(var job:jobs)job.get();
        }
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        for(int i=0;i<30000;i++){CannonReflection.invoke(child,"value",NONE);CannonReflection.invoke(child,"missing",NONE);}
        long id=Thread.currentThread().threadId(),start=bean.getThreadAllocatedBytes(id);
        for(int i=0;i<50000;i++){require("child".equals(CannonReflection.invoke(child,"value",NONE)),"warm invocation");require(CannonReflection.invoke(child,"missing",NONE)==null,"warm missing member");}
        long allocated=bean.getThreadAllocatedBytes(id)-start;
        require(allocated<1048576,"100000 warmed lookup/invocation operations must avoid repeated reflection metadata allocation: "+allocated);
        System.out.println("PASS: inheritance, overloads, null/primitive, transient fallback, static/instance, separate owners, optional misses, concurrency; 100000 warm operations allocated "+allocated+" bytes");
    }
}
