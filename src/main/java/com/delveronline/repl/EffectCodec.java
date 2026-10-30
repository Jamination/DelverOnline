package com.delveronline.repl;

import com.delveronline.net.ByteReader;
import com.delveronline.net.ByteWriter;
import com.delveronline.net.ProtocolException;
import com.interrupt.dungeoneer.entities.Entity;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compact encoding of short-lived effect entities (particles): class name plus only the fields that differ from a
 * freshly constructed instance of that class.
 */
public final class EffectCodec {
    private static final ConcurrentHashMap<Class<?>, FieldSchema.Values> DEFAULTS = new ConcurrentHashMap<>();
    private static final ThreadLocal<float[]> FLOAT_OUT = ThreadLocal.withInitial(() -> new float[1]);

    private EffectCodec() {}

    private static FieldSchema.Values defaults(Class<?> type) {
        FieldSchema.Values d = DEFAULTS.get(type);
        if (d == null) {
            FieldSchema s = FieldSchema.of(type);
            d = s.newValues();
            try {
                Object fresh = type.newInstance();
                for (int i = 0; i < s.size(); i++) s.capture(fresh, i, d);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot instantiate " + type, e);
            }
            DEFAULTS.put(type, d);
        }
        return d;
    }

    public static void write(ByteWriter w, Entity e) {
        Class<?> type = e.getClass();
        FieldSchema s = FieldSchema.of(type);
        FieldSchema.Values def = defaults(type);
        FieldSchema.Values cur = s.newValues();
        w.writeString(type.getName());
        int prev = -1;
        for (int i = 0; i < s.size(); i++) {
            s.capture(e, i, cur);
            if (cur.prim[i] == def.prim[i] && Objects.equals(cur.refs[i], def.refs[i])) continue;
            w.writeVarInt(i - prev);
            prev = i;
            s.write(w, i, cur);
        }
        w.writeVarInt(0);
    }

    public static Entity read(ByteReader r) {
        String name = r.readString();
        Class<?> type;
        try {
            type = Class.forName(name, false, EffectCodec.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ProtocolException("unknown effect class " + name);
        }
        if (!Entity.class.isAssignableFrom(type)) throw new ProtocolException("not an entity: " + name);
        Entity e;
        try {
            e = (Entity) type.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new ProtocolException("cannot create " + name);
        }
        FieldSchema s = FieldSchema.of(type);
        float[] fo = FLOAT_OUT.get();
        int idx = -1;
        while (true) {
            int d = r.readVarInt();
            if (d == 0) break;
            idx += d;
            if (idx >= s.size()) throw new ProtocolException("bad effect field");
            Object v = s.read(r, idx, fo);
            if (v == null) {
                s.applyFloat(e, idx, fo[0]);
            } else {
                s.apply(e, idx, v);
            }
        }
        return e;
    }
}
