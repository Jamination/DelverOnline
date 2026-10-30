package com.delveronline.repl;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Model;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.Particle;
import com.interrupt.dungeoneer.entities.Prefab;
import com.interrupt.dungeoneer.entities.Sprite;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.gfx.drawables.DrawableMesh;
import com.interrupt.dungeoneer.gfx.drawables.DrawableSprite;
import com.interrupt.dungeoneer.serializers.ArraySerializer;
import com.interrupt.dungeoneer.serializers.ColorSerializer;
import com.interrupt.dungeoneer.serializers.HashMapSerializer;
import com.interrupt.dungeoneer.serializers.IntArraySerializer;
import com.interrupt.dungeoneer.serializers.KryoSerializer;
import com.interrupt.dungeoneer.serializers.LibGdxArrayIteratorSerializer;
import com.interrupt.dungeoneer.serializers.PrefabSerializer;
import com.interrupt.dungeoneer.serializers.v2.TileSerializer;
import com.interrupt.dungeoneer.tiles.Tile;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Kryo configured exactly like Delver's own level serializer, used to ship entities, items and levels.
 * One instance per thread that uses it (Kryo is not thread-safe).
 */
public final class NetKryo {
    private final Kryo kryo = new Kryo();

    public NetKryo() {
        kryo.setDefaultSerializer(CompatibleFieldSerializer.class);
        kryo.register(Color.class);
        kryo.register(Vector3.class);
        kryo.register(Vector2.class);
        kryo.register(Tile.class);
        kryo.register(Entity.class);
        kryo.register(Monster.class);
        kryo.register(Particle.class);
        kryo.register(Sprite.class);
        kryo.register(Model.class);
        kryo.register(DrawableSprite.class);
        kryo.register(DrawableMesh.class);
        kryo.register(Array.class);
        kryo.register(Prefab.class, new PrefabSerializer());
        kryo.register(Array.ArrayIterator.class, new LibGdxArrayIteratorSerializer());
        kryo.register(Tile.class, new TileSerializer());
        kryo.register(Color.class, new ColorSerializer());
        kryo.register(Array.class, new ArraySerializer());
        kryo.register(IntArray.class, new IntArraySerializer());
        kryo.register(HashMap.class, new HashMapSerializer());
    }

    public byte[] write(Object o) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
        Output out = new Output(bos);
        kryo.writeClassAndObject(out, o);
        out.close();
        return bos.toByteArray();
    }

    public Object read(byte[] bytes) {
        Input in = new Input(bytes);
        Object o = kryo.readClassAndObject(in);
        in.close();
        return o;
    }

    /** Level bytes in Delver's save format (so the client can load them exactly like a saved level). */
    public static byte[] levelToBytes(Level level) {
        return KryoSerializer.toBytes(level);
    }

    public static Level levelFromBytes(byte[] bytes) {
        return KryoSerializer.loadLevel(bytes);
    }

    public static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 3 + 64);
        byte[] buf = new byte[64 * 1024];
        while (!d.finished()) {
            int n = d.deflate(buf);
            out.write(buf, 0, n);
        }
        d.end();
        return out.toByteArray();
    }

    public static byte[] inflate(byte[] data) {
        Inflater inf = new Inflater();
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
        byte[] buf = new byte[64 * 1024];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw new IllegalStateException("truncated level data");
                out.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IllegalStateException(e);
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }
}
