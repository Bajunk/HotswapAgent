package org.hotswap.agent.plugin.seam;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.hotswap.agent.annotation.Init;
import org.hotswap.agent.annotation.Plugin;
import org.hotswap.agent.annotation.Transform;
import org.hotswap.agent.annotation.Watch;
import org.hotswap.agent.command.Command;
import org.hotswap.agent.command.ReflectionCommand;
import org.hotswap.agent.command.Scheduler;
import org.hotswap.agent.javassist.CannotCompileException;
import org.hotswap.agent.javassist.CtClass;
import org.hotswap.agent.javassist.CtConstructor;
import org.hotswap.agent.javassist.CtMethod;
import org.hotswap.agent.javassist.NotFoundException;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.util.PluginManagerInvoker;

@Plugin(name = "Seam",
        description = "Seam framework maintains .",
        testedVersions = {"2.3.1"},
        expectedVersions = {"2.2", "2.3"})
public class SeamPlugin {
    private static AgentLogger LOGGER = AgentLogger.getLogger(SeamPlugin.class);

    ReflectionCommand refreshLabels = new ReflectionCommand(this, "org.jboss.seam.core.SeamResourceBundle", "clearCache");

    ReflectionCommand flushBeanIntrospectors = new ReflectionCommand(this, "java.beans.Introspector", "flushCaches");

    @Init
    Scheduler scheduler;

    @Init
    ClassLoader appClassLoader;

    Set<Object> registeredJbossReferenceCaches = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

    @Transform(classNameRegexp = "org.jboss.seam.init.Initialization")
    public static void seamServletCallInitialized(CtClass ctClass) throws NotFoundException, CannotCompileException {
        CtMethod init = ctClass.getDeclaredMethod("init");
        init.insertBefore(PluginManagerInvoker.buildInitializePlugin(SeamPlugin.class));
        LOGGER.debug("org.jboss.seam.init.Initialization enahnced with plugin initialization.");
    }

    @Watch(path = "/", filter = ".*.properites")
    public void refreshSeamProperties() {
        scheduler.scheduleCommand(refreshLabels);
    }

    @Transform(classNameRegexp = ".*", onDefine = false)
    public void flushBeanIntrospectorsCaches() throws Exception {
        scheduler.scheduleCommand(flushBeanIntrospectors);
    }

    public void registerJbossReferenceCache(Object referenceCache) {
        registeredJbossReferenceCaches.add(referenceCache);
        LOGGER.debug("JsfPlugin - registerJbossReferenceCache : " + referenceCache.getClass().getName());
    }

    @Transform(classNameRegexp = "org.jboss.el.util.ReferenceCache")
    public static void referenceCacheRegisterVariable(CtClass ctClass) throws CannotCompileException {
        String registerThis = PluginManagerInvoker.buildCallPluginMethod(SeamPlugin.class, "registerJbossReferenceCache",
                "this", "java.lang.Object");
        for (CtConstructor constructor : ctClass.getDeclaredConstructors()) {
            constructor.insertAfter(registerThis);
        }
    }

    @Transform(classNameRegexp = ".*", onDefine = false)
    public void invalidateClassCache() throws Exception {
        scheduler.scheduleCommand(clearJbossReferenceCache);
    }

    private Command clearJbossReferenceCache = new Command() {
        public void executeCommand() {
            LOGGER.debug("Refreshing Jboss reference caches.");
            try {
                Method clearCacheMethod = resolveClass("org.jboss.el.util.ReferenceCache").getDeclaredMethod("clear");
                for (Object referenceCache : registeredJbossReferenceCaches) {
                    clearCacheMethod.invoke(referenceCache);
                }
            } catch (Exception e) {
                LOGGER.error("Error clear in jboss ReferenceCache .", e);
            }
        }
    };

    private Class<?> resolveClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, appClassLoader);
    }

}
