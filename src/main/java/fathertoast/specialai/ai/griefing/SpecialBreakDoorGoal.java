package fathertoast.specialai.ai.griefing;

import fathertoast.specialai.util.BlockHelper;
import fathertoast.specialai.config.Config;
import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.ai.goal.BreakDoorGoal;
import net.minecraft.entity.monster.CreeperEntity;
import net.minecraft.pathfinding.GroundPathNavigator;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.GroundPathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.function.Predicate;

/**
 * This AI is based on the vanilla door breaking goal, but is modified to work on a larger variety of doors and
 * calculate block break speed rather than using a set break time.
 */
public class SpecialBreakDoorGoal extends BreakDoorGoal {
    /** Determines the difficulty setting(s) the AI should be active for. */
    private static final Predicate<Difficulty> DIFFICULTY_PREDICATE = difficulty -> true;
    
    /** Flagged true to end the task. */
    private boolean completed;
    /** Vector pointing from the entity to the door in the X direction. Used to determine when the entity moves past the door. */
    private float initialDoorVecX;
    /** Vector pointing from the entity to the door in the Z direction. Used to determine when the entity moves past the door. */
    private float initialDoorVecZ;
    
    /** The block to attack. */
    private BlockState targetBlock;
    /** Ticks to count how often to play the "hit" sound. */
    private int hitCounter;
    /** Current block damage. */
    private float blockDamage;
    /** Most recent block damage sent to clients. */
    private int lastBlockDamage = -1;
    
    /**
     * @param entity The owner of this AI.
     */
    public SpecialBreakDoorGoal( MobEntity entity ) {
        super( entity, DIFFICULTY_PREDICATE );
    }
    
    /** @return Returns true if this AI can be activated. */
    @Override
    public boolean canUse() {
        if( Config.GENERAL.DOOR_BREAKING.requiresTarget.get() && mob.getTarget() == null ) {
            return false;
        }
        // Try to find a blocking door
        if( mob.horizontalCollision && GroundPathHelper.hasGroundPathNavigation( mob ) ) {
            findObstructingDoor();
        }
        // Return true if we are allowed to destroy the door
        return hasDoor;
    }
    
    /** Attempt to find an obstructing door and targets it, if possible. */
    private void findObstructingDoor() {
        hasDoor = false;
        GroundPathNavigator navigator = (GroundPathNavigator) mob.getNavigation();
        Path path = navigator.getPath();
        if( path != null && !path.isDone() && navigator.canOpenDoors() ) {
            // Search along the entity's path
            final int maxPoint = Math.min( path.getNextNodeIndex() + 2, path.getNodeCount() );
            for( int i = 0; i < maxPoint; i++ ) {
                PathPoint pathpoint = path.getNode( i );
                // Start at floor level so we can target shorter doors
                BlockPos floorPos = new BlockPos( pathpoint.x, pathpoint.y - 1, pathpoint.z );
                if( mob.distanceToSqr( floorPos.getX(), mob.getY(), floorPos.getZ() ) <= 2.25 && tryTargetDoor( floorPos ) ) {
                    return;
                }
            }
            // Check the space the entity is in
            tryTargetDoor( mob.blockPosition().below() );
        }
    }
    
    /** @return Attempts to target a door at a given position. Will target the highest valid door block and return true if one is found. */
    private boolean tryTargetDoor( BlockPos floorPos ) {
        // Start at the highest colliding block position
        BlockPos pos = floorPos.above( (int) Math.ceil( mob.getBbHeight() ) );
        while( pos.getY() >= floorPos.getY() ) {
            // Check if the block at this position is a valid target
            BlockState target = mob.level.getBlockState( pos );
            if( isValidBlock( target ) && BlockHelper.shouldDamage( target, mob,
                    Config.GENERAL.DOOR_BREAKING.requiresTools.get() && !madCreeper(), mob.level, pos ) ) {
                // The target is valid
                targetBlock = target;
                doorPos = pos;
                hasDoor = true;
                return true;
            }
            pos = pos.below();
        }
        return false;
    }
    
    /** @return Returns true if the block is generally valid for targeting, not taking tool or other requirements under consideration. */
    private boolean isValidBlock( BlockState target ) {
        if( target.getBlock() == Blocks.AIR || Config.GENERAL.DOOR_BREAKING.targetList.BLACKLIST.get().matches( target ) ) {
            return false;
        }
        if( Config.GENERAL.DOOR_BREAKING.targetDoors.get() ) {
            Block block = target.getBlock();
            if( block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock ) {
                return true;
            }
        }
        return Config.GENERAL.DOOR_BREAKING.targetList.WHITELIST.get().matches( target );
    }
    
    /** Called when this AI is activated. */
    @Override
    public void start() {
        if( madCreeper() ) {
            ((CreeperEntity) mob).ignite();
            completed = true;
        }
        else {
            completed = false;
            initialDoorVecX = (float) (doorPos.getX() + 0.5F - mob.getX());
            initialDoorVecZ = (float) (doorPos.getZ() + 0.5F - mob.getZ());
            
            hitCounter = 0;
            blockDamage = 0.0F;
            lastBlockDamage = -1;
        }
    }
    
    /** @return Called each update while active and returns true if this AI can remain active. */
    @Override
    public boolean canContinueToUse() {
        return !completed && doorPos.closerThan( mob.position(), 2.0 );
    }
    
    /** Called when this AI is deactivated. */
    @Override
    public void stop() {
        blockDamage = 0.0F;
        targetBlock = null;
        hasDoor = false;
        mob.level.destroyBlockProgress( mob.getId(), doorPos, -1 );
    }
    
    /** Called each tick while this AI is active. */
    @Override
    public void tick() {
        if( targetBlock == null ) return;
        
        // This projection becomes negative roughly when the entity passes the door
        final float doorVecX = (float) (doorPos.getX() + 0.5F - mob.position().x);
        final float doorVecZ = (float) (doorPos.getZ() + 0.5F - mob.position().z);
        final float projection = initialDoorVecX * doorVecX + initialDoorVecZ * doorVecZ;
        if( projection < 0.0F ) {
            completed = true;
        }
        
        // Play hit effects
        if( hitCounter == 0 ) {
            if( targetBlock.getMaterial() == Material.METAL || targetBlock.getMaterial() == Material.HEAVY_METAL ) {
                BlockHelper.LevelEvent.ATTACK_DOOR_IRON.play( mob, doorPos );
            }
            else {
                BlockHelper.LevelEvent.ATTACK_DOOR_WOOD.play( mob, doorPos );
            }
            if( !mob.swinging ) {
                mob.swing( mob.getUsedItemHand() );
            }
        }
        if( ++hitCounter >= 16 ) {
            hitCounter = 0;
        }
        World world = mob.level;
        
        // Perform block breaking
        blockDamage += BlockHelper.getDestroyProgress( targetBlock, mob, world, doorPos ) * Config.GENERAL.DOOR_BREAKING.breakSpeed.get();
        if( blockDamage >= 1.0F ) {
            // Block is broken
            world.destroyBlock( doorPos, Config.GENERAL.DOOR_BREAKING.leaveDrops.get(), mob );
            BlockHelper.LevelEvent.BREAK_DOOR_WOOD.play( mob, doorPos );
            BlockHelper.LevelEventMeta.playBreakBlock( mob, doorPos );
            if( !mob.swinging ) {
                mob.swing( mob.getUsedItemHand() );
            }
            blockDamage = 0.0F;
            completed = true;
        }
        
        // Update block damage
        final int damage = (int) Math.ceil( blockDamage * 10.0F ) - 1;
        if( damage != lastBlockDamage ) {
            world.destroyBlockProgress( mob.getId(), doorPos, damage );
            lastBlockDamage = damage;
        }
    }
    
    /** @return Returns true if the entity is a creeper and should explode instead of attacking the door. */
    private boolean madCreeper() { return Config.GENERAL.DOOR_BREAKING.madCreepers.get() && mob instanceof CreeperEntity; }
}