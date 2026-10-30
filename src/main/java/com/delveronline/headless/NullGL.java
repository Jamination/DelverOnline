package com.delveronline.headless;

import com.badlogic.gdx.graphics.GL20;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A GL20 that does nothing but answers queries plausibly, so Delver's renderer and asset code can run
 * without an OpenGL context (dedicated server and headless bot clients).
 */
public final class NullGL {
    private NullGL() {}

    public static GL20 create() {
        final AtomicInteger ids = new AtomicInteger(1);
        return (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(), new Class<?>[]{GL20.class}, (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "glGenTexture":
                case "glGenBuffer":
                case "glGenFramebuffer":
                case "glGenRenderbuffer":
                case "glCreateShader":
                case "glCreateProgram":
                    return ids.getAndIncrement();
                case "glGenTextures":
                case "glGenBuffers":
                case "glGenFramebuffers":
                case "glGenRenderbuffers":
                    fillIds((IntBuffer) args[1], (Integer) args[0], ids);
                    return null;
                case "glGetShaderiv":
                case "glGetProgramiv":
                    answerStatus((Integer) args[1], (IntBuffer) args[2]);
                    return null;
                case "glGetIntegerv":
                    answerInteger((Integer) args[0], (IntBuffer) args[1]);
                    return null;
                case "glGetFloatv":
                    ((FloatBuffer) args[1]).put(((FloatBuffer) args[1]).position(), 1f);
                    return null;
                case "glCheckFramebufferStatus":
                    return GL20.GL_FRAMEBUFFER_COMPLETE;
                case "glGetString":
                    return "NullGL 2.0";
                case "glGetShaderInfoLog":
                case "glGetProgramInfoLog":
                    return "";
                case "glGetAttribLocation":
                case "glGetUniformLocation":
                    return 0;
                case "glGetActiveAttrib":
                case "glGetActiveUniform":
                    return "";
                case "glIsEnabled":
                    return false;
                default:
                    return defaultValue(method);
            }
        });
    }

    private static void fillIds(IntBuffer buf, int n, AtomicInteger ids) {
        int p = buf.position();
        for (int i = 0; i < n; i++) buf.put(p + i, ids.getAndIncrement());
    }

    private static void answerStatus(int pname, IntBuffer out) {
        int value;
        switch (pname) {
            case GL20.GL_COMPILE_STATUS:
            case GL20.GL_LINK_STATUS:
            case GL20.GL_VALIDATE_STATUS:
                value = 1;
                break;
            default:
                value = 0; // active attributes/uniforms, info log length, ...
        }
        out.put(out.position(), value);
    }

    private static void answerInteger(int pname, IntBuffer out) {
        int value;
        switch (pname) {
            case GL20.GL_MAX_TEXTURE_SIZE:
                value = 8192;
                break;
            case GL20.GL_MAX_TEXTURE_IMAGE_UNITS:
            case GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS:
                value = 16;
                break;
            case GL20.GL_MAX_VERTEX_ATTRIBS:
                value = 16;
                break;
            default:
                value = 0;
        }
        out.put(out.position(), value);
    }

    private static Object defaultValue(Method m) {
        Class<?> r = m.getReturnType();
        if (r == void.class) return null;
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == float.class) return 0f;
        if (r == long.class) return 0L;
        if (r == String.class) return "";
        if (Buffer.class.isAssignableFrom(r)) return null;
        return null;
    }
}
