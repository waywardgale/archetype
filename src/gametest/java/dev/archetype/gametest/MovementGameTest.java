package dev.archetype.gametest;

import java.lang.reflect.Method;

import dev.archetype.definitions.Position;
import dev.archetype.definitions.Vec;
import dev.archetype.minecraft.MinecraftWorldOps;
import dev.archetype.runtime.MotionResult;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.block.Blocks;

public final class MovementGameTest implements CustomTestMethodInvoker {
    @Override
    public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
        method.invoke(this, helper);
    }

    @GameTest
    public void collisionStopsImpulse(GameTestHelper helper) {
        helper.setBlock(1, 0, 1, Blocks.STONE);
        helper.setBlock(2, 0, 1, Blocks.STONE);
        helper.setBlock(3, 0, 1, Blocks.STONE);
        helper.setBlock(3, 1, 1, Blocks.STONE);
        helper.setBlock(3, 2, 1, Blocks.STONE);
        Pig pig = helper.spawn(EntityTypes.PIG, new BlockPos(1, 1, 1));
        MinecraftWorldOps world = new MinecraftWorldOps(helper.getLevel().getServer());
        MotionResult result = world.displace(pig.getUUID(), new Vec(1.0, 0.0, 0.0), 3.0);
        if (result == null || !result.getBlocked() || result.getTravelled() >= 3.0 ||
            pig.getX() >= helper.absolutePos(new BlockPos(3, 1, 1)).getX())
            throw helper.assertionException("native collision did not stop the impulse");
        helper.succeed();
    }

    @GameTest
    public void safeTeleportRequiresClearSupportedLanding(GameTestHelper helper) {
        helper.setBlock(1, 0, 1, Blocks.STONE);
        helper.setBlock(4, 0, 1, Blocks.STONE);
        helper.setBlock(4, 1, 1, Blocks.STONE);
        Pig pig = helper.spawn(EntityTypes.PIG, new BlockPos(1, 1, 1));
        MinecraftWorldOps world = new MinecraftWorldOps(helper.getLevel().getServer());
        BlockPos landing = helper.absolutePos(new BlockPos(4, 1, 1));
        Position destination = new Position(world.position(pig.getUUID()).getDimension(),
            new Vec(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5));
        double start = pig.getX();
        if (!Boolean.FALSE.equals(world.safeTeleport(pig.getUUID(), destination)) || pig.getX() != start)
            throw helper.assertionException("blocked landing moved the entity");
        helper.setBlock(4, 1, 1, Blocks.AIR);
        if (!Boolean.TRUE.equals(world.safeTeleport(pig.getUUID(), destination)) ||
            Math.abs(pig.getX() - landing.getX() - 0.5) > 0.01)
            throw helper.assertionException("clear supported landing did not move the entity");
        helper.succeed();
    }
}
