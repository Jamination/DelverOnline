package com.delveronline.repl;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.delveronline.net.ByteReader;
import com.delveronline.net.ByteWriter;
import com.interrupt.dungeoneer.entities.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective description of the replicable state of an entity class: every non-static, non-final field of a
 * value type declared on the class or its superclasses (up to {@link Entity}). Field order is deterministic
 * (superclass first, then by name) so server and client agree on indices.
 */
public final class FieldSchema {
    public enum Kind { BOOL, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE, STRING, ENUM, BOXED, COLOR, VEC3, VEC2 }

    /** Fields that must never be replicated (identity, client-only presentation, or per-machine caches). */
    private static final Set<String> DENY = new HashSet<>(Arrays.asList(
            "Entity.owner", "Entity.editorState", "Entity.outOfDrawDistance", "Entity.drawUpdateTimer", "Entity.skipTick",
            "Entity.physicsSleeping", "Entity.tickcount", "Entity.lastSplashTime", "Entity.lavaHurtTimer",
            "Entity.slideEffectTimer", "Entity.calcStepHeight", "Entity.lastZ", "Entity.wasOnFloorLast",
            "Entity.inwater", "Entity.attachmentTransform",
            // monster AI bookkeeping: changes every tick, never drawn
            "Monster.tickcount", "Monster.targetx", "Monster.targety", "Monster.targetz", "Monster.last_targetx",
            "Monster.last_targety", "Monster.last_picked_targetx", "Monster.last_picked_targety", "Monster.attacktimer",
            "Monster.rangedAttackTimer", "Monster.regenManaTimer", "Monster.stuntime", "Monster.postAttackMoveWaitTimer",
            "Monster.lastWander", "Monster.waiting", "Monster.bleedTimer", "Monster.stuckTime", "Monster.stuckWanderTimer",
            "Monster.alertValue"));

    /** Per-machine caches and scratch vectors, whatever class declares them. */
    private static final Set<String> DENY_NAMES = new HashSet<>(Arrays.asList(
            "lastMeshFile", "lastTextureFile", "dirWork", "workVec", "tempVec1", "tempVec2", "tempVec3", "tempVec4"));

    /** Float fields that represent discrete values and must not be interpolated. */
    private static final Set<String> NO_INTERP = new HashSet<>(Arrays.asList(
            "Entity.scale", "Entity.mass", "Entity.spawnChance", "Entity.stepHeight"));

    private static final ConcurrentHashMap<Class<?>, FieldSchema> CACHE = new ConcurrentHashMap<>();

    public final Class<?> type;
    public final Field[] fields;
    public final Kind[] kinds;
    public final boolean[] interpolate;
    /** Bytes sent per field (server-side diagnostics, see the 'fieldstats' console command). */
    public final long[] bytesSent;
    /** Index of x, y, z (always present for entities). */
    public final int ix, iy, iz;

    private FieldSchema(Class<?> type) {
        this.type = type;
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = type; c != null && Entity.class.isAssignableFrom(c); c = c.getSuperclass()) chain.add(0, c);
        List<Field> out = new ArrayList<>();
        for (Class<?> c : chain) {
            Field[] declared = c.getDeclaredFields();
            Arrays.sort(declared, Comparator.comparing(Field::getName));
            for (Field f : declared) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isFinal(m) || f.isSynthetic()) continue;
                if (DENY.contains(c.getSimpleName() + "." + f.getName()) || DENY_NAMES.contains(f.getName())) continue;
                // scratch state: private transient fields and Delver's "t_" temporaries
                if ((Modifier.isPrivate(m) && Modifier.isTransient(m)) || f.getName().startsWith("t_")) continue;
                if (kindOf(f) == null) continue;
                f.setAccessible(true);
                out.add(f);
            }
        }
        fields = out.toArray(new Field[0]);
        kinds = new Kind[fields.length];
        interpolate = new boolean[fields.length];
        bytesSent = new long[fields.length];
        int x = -1, y = -1, z = -1;
        for (int i = 0; i < fields.length; i++) {
            Field f = fields[i];
            kinds[i] = kindOf(f);
            String key = f.getDeclaringClass().getSimpleName() + "." + f.getName();
            interpolate[i] = kinds[i] == Kind.FLOAT && !NO_INTERP.contains(key);
            if (f.getDeclaringClass() == Entity.class) {
                if (f.getName().equals("x")) x = i;
                if (f.getName().equals("y")) y = i;
                if (f.getName().equals("z")) z = i;
            }
        }
        ix = x;
        iy = y;
        iz = z;
    }

    /** All schemas built so far. */
    public static java.util.Collection<FieldSchema> all() {
        return CACHE.values();
    }

    public static FieldSchema of(Class<?> type) {
        FieldSchema s = CACHE.get(type);
        if (s == null) {
            s = new FieldSchema(type);
            FieldSchema prev = CACHE.putIfAbsent(type, s);
            if (prev != null) s = prev;
        }
        return s;
    }

    public int size() {
        return fields.length;
    }

    private static Kind kindOf(Field f) {
        Class<?> t = f.getType();
        if (t == boolean.class) return Kind.BOOL;
        if (t == byte.class) return Kind.BYTE;
        if (t == short.class) return Kind.SHORT;
        if (t == char.class) return Kind.CHAR;
        if (t == int.class) return Kind.INT;
        if (t == long.class) return Kind.LONG;
        if (t == float.class) return Kind.FLOAT;
        if (t == double.class) return Kind.DOUBLE;
        if (t == String.class) return Kind.STRING;
        if (t.isEnum()) return Kind.ENUM;
        if (t == Integer.class || t == Float.class || t == Boolean.class || t == Short.class || t == Byte.class
                || t == Double.class || t == Long.class) return Kind.BOXED;
        if (t == Color.class) return Kind.COLOR;
        if (t == Vector3.class) return Kind.VEC3;
        if (t == Vector2.class) return Kind.VEC2;
        return null;
    }

    // ------------------------------------------------------------------ capture & compare

    /** Captured values for one entity: primitive bits in {@code prim}, references (copied) in {@code refs}. */
    public static final class Values {
        public final long[] prim;
        public final Object[] refs;

        public Values(int n) {
            prim = new long[n];
            refs = new Object[n];
        }
    }

    public Values newValues() {
        return new Values(fields.length);
    }

    /**
     * Reads field {@code i} of {@code e} into {@code v}. Returns true when it differs from what {@code v} held.
     */
    public boolean capture(Object e, int i, Values v) {
        try {
            Field f = fields[i];
            switch (kinds[i]) {
                case BOOL: return setPrim(v, i, f.getBoolean(e) ? 1 : 0);
                case BYTE: return setPrim(v, i, f.getByte(e));
                case SHORT: return setPrim(v, i, f.getShort(e));
                case CHAR: return setPrim(v, i, f.getChar(e));
                case INT: return setPrim(v, i, f.getInt(e));
                case LONG: return setPrim(v, i, f.getLong(e));
                case FLOAT: return setPrim(v, i, Float.floatToIntBits(f.getFloat(e)));
                case DOUBLE: return setPrim(v, i, Double.doubleToLongBits(f.getDouble(e)));
                case STRING:
                case ENUM:
                case BOXED: {
                    Object o = f.get(e);
                    if (eq(o, v.refs[i])) return false;
                    v.refs[i] = o;
                    return true;
                }
                case COLOR: {
                    Color c = (Color) f.get(e);
                    Color old = (Color) v.refs[i];
                    if (c == null ? old == null : (old != null && old.equals(c))) return false;
                    v.refs[i] = c == null ? null : new Color(c);
                    return true;
                }
                case VEC3: {
                    Vector3 c = (Vector3) f.get(e);
                    Vector3 old = (Vector3) v.refs[i];
                    if (c == null ? old == null : (old != null && old.equals(c))) return false;
                    v.refs[i] = c == null ? null : new Vector3(c);
                    return true;
                }
                case VEC2: {
                    Vector2 c = (Vector2) f.get(e);
                    Vector2 old = (Vector2) v.refs[i];
                    if (c == null ? old == null : (old != null && old.equals(c))) return false;
                    v.refs[i] = c == null ? null : new Vector2(c);
                    return true;
                }
                default:
                    return false;
            }
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean setPrim(Values v, int i, long bits) {
        if (v.prim[i] == bits) return false;
        v.prim[i] = bits;
        return true;
    }

    private static boolean eq(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    // ------------------------------------------------------------------ wire format

    /** Writes the captured value of field {@code i}. */
    public void write(ByteWriter w, int i, Values v) {
        long p = v.prim[i];
        Object o = v.refs[i];
        switch (kinds[i]) {
            case BOOL: w.writeBool(p != 0); break;
            case BYTE:
            case SHORT:
            case CHAR:
            case INT: w.writeSVarInt((int) p); break;
            case LONG: w.writeLong(p); break;
            case FLOAT: w.writeInt((int) p); break;
            case DOUBLE: w.writeLong(p); break;
            case STRING: w.writeString((String) o); break;
            case ENUM: w.writeVarInt(o == null ? 0 : ((Enum<?>) o).ordinal() + 1); break;
            case BOXED: writeBoxed(w, o); break;
            case COLOR: {
                Color c = (Color) o;
                w.writeBool(c != null);
                if (c != null) w.writeFloat(c.r).writeFloat(c.g).writeFloat(c.b).writeFloat(c.a);
                break;
            }
            case VEC3: {
                Vector3 c = (Vector3) o;
                w.writeBool(c != null);
                if (c != null) w.writeFloat(c.x).writeFloat(c.y).writeFloat(c.z);
                break;
            }
            case VEC2: {
                Vector2 c = (Vector2) o;
                w.writeBool(c != null);
                if (c != null) w.writeFloat(c.x).writeFloat(c.y);
                break;
            }
            default:
        }
    }

    private static void writeBoxed(ByteWriter w, Object o) {
        if (o == null) {
            w.writeByte(0);
        } else if (o instanceof Integer) {
            w.writeByte(1).writeSVarInt((Integer) o);
        } else if (o instanceof Float) {
            w.writeByte(2).writeFloat((Float) o);
        } else if (o instanceof Boolean) {
            w.writeByte(3).writeBool((Boolean) o);
        } else if (o instanceof Short) {
            w.writeByte(4).writeSVarInt((Short) o);
        } else if (o instanceof Byte) {
            w.writeByte(5).writeSVarInt((Byte) o);
        } else if (o instanceof Double) {
            w.writeByte(6).writeDouble((Double) o);
        } else if (o instanceof Long) {
            w.writeByte(7).writeLong((Long) o);
        } else {
            w.writeByte(0);
        }
    }

    private static Object readBoxed(ByteReader r) {
        switch (r.readByte()) {
            case 1: return r.readSVarInt();
            case 2: return r.readFloat();
            case 3: return r.readBool();
            case 4: return (short) r.readSVarInt();
            case 5: return (byte) r.readSVarInt();
            case 6: return r.readDouble();
            case 7: return r.readLong();
            default: return null;
        }
    }

    /**
     * Reads a value for field {@code i}. Floats are returned via {@code floatOut[0]} with a null result so callers
     * can interpolate them; every other kind returns a boxed/reference value (possibly {@link #NULL_VALUE}).
     */
    public Object read(ByteReader r, int i, float[] floatOut) {
        switch (kinds[i]) {
            case BOOL: return r.readBool();
            case BYTE: return (byte) r.readSVarInt();
            case SHORT: return (short) r.readSVarInt();
            case CHAR: return (char) r.readSVarInt();
            case INT: return r.readSVarInt();
            case LONG: return r.readLong();
            case FLOAT:
                floatOut[0] = r.readFloat();
                return null;
            case DOUBLE: return r.readDouble();
            case STRING: return nullable(r.readString());
            case ENUM: {
                int ord = r.readVarInt();
                if (ord == 0) return NULL_VALUE;
                Object[] constants = fields[i].getType().getEnumConstants();
                if (ord - 1 >= constants.length) throw new com.delveronline.net.ProtocolException("bad enum ordinal");
                return constants[ord - 1];
            }
            case BOXED: return nullable(readBoxed(r));
            case COLOR:
                if (!r.readBool()) return NULL_VALUE;
                return new Color(r.readFloat(), r.readFloat(), r.readFloat(), r.readFloat());
            case VEC3:
                if (!r.readBool()) return NULL_VALUE;
                return new Vector3(r.readFloat(), r.readFloat(), r.readFloat());
            case VEC2:
                if (!r.readBool()) return NULL_VALUE;
                return new Vector2(r.readFloat(), r.readFloat());
            default:
                throw new com.delveronline.net.ProtocolException("bad kind");
        }
    }

    /** Marker for "set the field to null". */
    public static final Object NULL_VALUE = new Object();

    private static Object nullable(Object o) {
        return o == null ? NULL_VALUE : o;
    }

    /** Assigns a value produced by {@link #read}. Reference values are always fresh objects (never shared). */
    public void apply(Object e, int i, Object value) {
        try {
            Field f = fields[i];
            Object v = value == NULL_VALUE ? null : value;
            switch (kinds[i]) {
                case BOOL: f.setBoolean(e, (Boolean) v); break;
                case BYTE: f.setByte(e, (Byte) v); break;
                case SHORT: f.setShort(e, (Short) v); break;
                case CHAR: f.setChar(e, (Character) v); break;
                case INT: f.setInt(e, (Integer) v); break;
                case LONG: f.setLong(e, (Long) v); break;
                case DOUBLE: f.setDouble(e, (Double) v); break;
                case BOXED:
                    if (v != null && !f.getType().isInstance(v)) return; // type mismatch: ignore
                    f.set(e, v);
                    break;
                default:
                    f.set(e, v);
            }
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public void applyFloat(Object e, int i, float value) {
        try {
            fields[i].setFloat(e, value);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public float getFloat(Object e, int i) {
        try {
            return fields[i].getFloat(e);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String name(int i) {
        return fields[i].getDeclaringClass().getSimpleName() + "." + fields[i].getName();
    }
}
