package com.delveronline.repl;

import com.interrupt.dungeoneer.entities.AnimatedSprite;
import com.interrupt.dungeoneer.entities.DynamicLight;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Fire;
import com.interrupt.dungeoneer.entities.FogSprite;
import com.interrupt.dungeoneer.entities.Foliage;
import com.interrupt.dungeoneer.entities.Light;
import com.interrupt.dungeoneer.entities.Particle;
import com.interrupt.dungeoneer.entities.ParticleEmitter;
import com.interrupt.dungeoneer.entities.PositionedSound;
import com.interrupt.dungeoneer.entities.ProjectedDecal;
import com.interrupt.dungeoneer.entities.SpriteDecal;

/**
 * Entity classes whose behaviour is purely presentational. Clients simulate them locally (emitters, flickering
 * lights, animated sprites, ambient sounds); the server only replicates their existence and discrete toggles.
 */
public final class Visuals {
    private Visuals() {}

    public static boolean isClientSimulated(Entity e) {
        if (e instanceof Fire) return false; // fire deals damage: server-side gameplay
        return e instanceof Particle
                || e instanceof ParticleEmitter
                || e instanceof Light
                || e instanceof DynamicLight
                || e instanceof AnimatedSprite
                || e instanceof FogSprite
                || e instanceof Foliage
                || e instanceof PositionedSound
                || e instanceof ProjectedDecal
                || e instanceof SpriteDecal;
    }
}
