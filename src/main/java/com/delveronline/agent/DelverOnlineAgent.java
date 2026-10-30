package com.delveronline.agent;

import com.delveronline.hooks.HookRegistry;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * Java agent entry point. Weaves the multiplayer hooks ({@link HookRegistry}) into Delver's classes as they load.
 * The installed game files are never modified; patching happens in memory only.
 */
public final class DelverOnlineAgent {
    private static volatile boolean installed;

    private DelverOnlineAgent() {}

    public static void premain(String args, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(inst);
    }

    public static boolean isInstalled() {
        return installed;
    }

    private static synchronized void install(Instrumentation inst) {
        if (installed) return;
        ElementMatcher.Junction<TypeDescription> scope = nameStartsWith("com.interrupt.").or(named("com.badlogic.gdx.Game"));
        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .ignore(not(scope));
        for (HookRegistry.Hook h : HookRegistry.hooks()) {
            AgentBuilder.Transformer.ForAdvice t = new AgentBuilder.Transformer.ForAdvice(Advice.withCustomMapping())
                    .include(DelverOnlineAgent.class.getClassLoader())
                    .advice(h.method, h.adviceClass);
            // transformations are not terminal: several hooks may apply to the same class
            builder = builder.type(h.type).transform(t);
        }
        builder.installOn(inst);
        installed = true;
    }
}
